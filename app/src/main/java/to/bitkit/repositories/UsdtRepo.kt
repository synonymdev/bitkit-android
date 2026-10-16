package to.bitkit.repositories

import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtParseAmount
import com.synonym.bitkitcore.usdtParsePaymentRequest
import com.synonym.bitkitcore.usdtValidateRecipient
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitUsdt
import to.bitkit.services.UsdtService
import to.bitkit.utils.AppError
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Suppress("TooManyFunctions")
@Singleton
class UsdtRepo @Inject constructor(
    private val service: UsdtService,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    @Volatile var isSendPresented = false

    private var nextRefresh = 0L
    private var rateLimitedUntil = 0L
    private val _state = MutableStateFlow(UsdtWalletState())
    val state = _state.asStateFlow()
    fun consumeReceipt(id: String): Boolean = _state.getAndUpdate {
        it.copy(pendingReceipts = it.pendingReceipts.filterNot { receipt -> receipt.id == id }.toImmutableList())
    }.pendingReceipts.any { it.id == id }

    suspend fun refresh(history: Boolean, force: Boolean = false) = withContext(ioDispatcher) {
        mutex.withLock {
            val refreshAfter = if (force) rateLimitedUntil else maxOf(nextRefresh, rateLimitedUntil)
            if (nowMillis() < refreshAfter) {
                return@withLock if (force) Result.failure(UsdtException.RateLimited()) else Result.success(Unit)
            }
            runSuspendCatching {
                val wallet = service.wallet()
                val address = wallet.receiveAddress()
                if (_state.value.address != address) _state.update { UsdtWalletState(address = address) }
                val previous = _state.value.transfers.takeIf { _state.value.historyComplete }
                val saved = wallet.history().toImmutableList()
                _state.update { it.copy(receiveUri = wallet.receiveUri(), transfers = saved) }
                val settlement = runSuspendCatching {
                    val balance = wallet.balance()
                    _state.update { it.copy(balance = balance) }
                    val transfers = wallet.refreshTransfers().toImmutableList()
                    _state.update { it.copy(transfers = transfers) }
                }
                if (settlement.exceptionOrNull() is UsdtException.RateLimited) settlement.getOrThrow()
                if (history && !isSendPresented) {
                    val complete = wallet.syncHistory()
                    val current = wallet.history().toImmutableList()
                    _state.update { it.copy(historyComplete = it.historyComplete || complete, transfers = current) }
                    announceNewReceipts(previous, current)
                }
                settlement.getOrThrow()
            }.also {
                if (it.exceptionOrNull() is UsdtException.RateLimited) {
                    rateLimitedUntil = nowMillis() + 60.seconds.inWholeMilliseconds
                }
                nextRefresh = maxOf(rateLimitedUntil, nowMillis() + 10.seconds.inWholeMilliseconds)
            }
        }
    }

    private fun announceNewReceipts(previous: List<UsdtTransfer>?, current: List<UsdtTransfer>) {
        if (previous == null) return
        val knownIds = previous.filter { it.isIncoming && it.status == UsdtTransferStatus.CONFIRMED }
            .map { it.id }.toSet()
        val received = current.filter {
            it.isIncoming && it.status == UsdtTransferStatus.CONFIRMED && it.amount > 0u && it.id !in knownIds
        }
        _state.update {
            it.copy(
                pendingReceipts = (it.pendingReceipts + received).distinctBy { receipt -> receipt.id }.toImmutableList()
            )
        }
    }

    suspend fun waitForTransfer(id: String): Result<Unit> = withContext(ioDispatcher) {
        withTimeoutOrNull(5.seconds) {
            mutex.withLock {
                if (nowMillis() < rateLimitedUntil) return@withLock Result.success(Unit)
                runSuspendCatching {
                    val wallet = service.wallet()
                    while (true) {
                        val transfer = wallet.checkRecentExecution(id) ?: return@runSuspendCatching
                        _state.update {
                            val transfers = it.transfers.filter { saved -> saved.id != transfer.id }
                            it.copy(transfers = (listOf(transfer) + transfers).toImmutableList())
                        }
                        if (transfer.status != UsdtTransferStatus.PENDING) return@runSuspendCatching
                        delay(1.seconds)
                    }
                }.onFailure {
                    if (it is UsdtException.RateLimited) {
                        rateLimitedUntil = nowMillis() + 60.seconds.inWholeMilliseconds
                        nextRefresh = maxOf(nextRefresh, rateLimitedUntil)
                    }
                }
            }
        } ?: Result.success(Unit)
    }

    suspend fun paymentEndpoint(): Result<Endpoint> = operation { PaykitUsdt.endpoint(wallet().receiveAddress()) }

    suspend fun sendDestinations() = operation {
        (Env.usdtDestinations + service.wallet().orchestraDestinations()).distinct().toImmutableList()
    }

    fun hasStoredWallet(): Boolean = runCatching { service.hasStoredWallet }.getOrDefault(false)

    fun shouldRefresh(enabled: Boolean): Boolean = enabled || state.value.hasFundsOrPendingTransfers ||
        (state.value.balance == null && hasStoredWallet())

    fun backupSnapshot(): String? = service.backupSnapshot()

    suspend fun restoreBackup(snapshot: String) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                val wallet = service.wallet(requiresNetwork = false)
                wallet.restoreBackup(snapshot)
                _state.update {
                    it.copy(
                        address = wallet.receiveAddress(),
                        transfers = wallet.history().toImmutableList(),
                        historyComplete = false,
                        pendingReceipts = persistentListOf(),
                        pendingDepositIds = persistentSetOf(),
                    )
                }
            }
        }
    }

    internal fun updateDeposits(deposits: List<UsdtDeposit>) {
        _state.update { wallet ->
            wallet.copy(
                pendingDepositIds = (
                    wallet.pendingDepositIds - deposits.map { it.id }.toSet() +
                        deposits.filter { it.needsSettlement }.map { it.id }
                    ).toImmutableSet(),
            )
        }
    }

    internal suspend fun <T> operation(block: suspend UsdtService.() -> T): Result<T> = withContext(ioDispatcher) {
        mutex.withLock { runSuspendCatching { block(service) } }
    }

    suspend fun wipe(clearCredentials: suspend () -> Unit = {}) = withContext(ioDispatcher) {
        mutex.withLock {
            clearCredentials()
            runSuspendCatching { service.wipe() }.also {
                _state.update { UsdtWalletState() }
                nextRefresh = 0L
                rateLimitedUntil = 0L
            }
        }
    }

    suspend fun quote(
        recipient: String,
        amount: String,
        destination: UsdtDestination,
        locale: Locale = Locale.getDefault(),
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                val wallet = service.wallet()
                if (recipient.trim().equals(wallet.receiveAddress(), ignoreCase = true)) throw UsdtSelfSendError()
                wallet.quoteTransfer(
                    recipient,
                    usdtParseAmount(amount.replace(DecimalFormatSymbols.getInstance(locale).decimalSeparator, '.')),
                    destination
                )
            }
        }
    }

    suspend fun quoteMaximum(recipient: String, destination: UsdtDestination) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                val wallet = service.wallet()
                if (recipient.trim().equals(wallet.receiveAddress(), ignoreCase = true)) throw UsdtSelfSendError()
                wallet.quoteMaxTransfer(recipient, destination)
            }
        }
    }

    suspend fun send(
        quote: UsdtQuote,
        submissionDeadline: Instant? = null,
        beforeSend: suspend () -> Unit = {},
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                beforeSend()
                quote.requireValidForSubmission(clock.now())
                service.send(quote, submissionDeadline).also { transfer ->
                    _state.update {
                        it.copy(
                            transfers = (listOf(transfer) + it.transfers.filter { saved -> saved.id != transfer.id })
                                .toImmutableList()
                        )
                    }
                }
            }.onFailure {
                runSuspendCatching { service.wallet().history().toImmutableList() }
                    .onSuccess { transfers -> _state.update { it.copy(transfers = transfers) } }
            }
        }
    }
}

internal fun UsdtQuote.requireValidForSubmission(now: Instant) {
    if (expiresAt <= (now + 5.seconds).epochSeconds.toULong()) throw UsdtException.QuoteExpired()
}

fun parseUsdtPaymentRequest(value: String, destination: UsdtDestination = UsdtDestination.ARBITRUM) = runCatching {
    if (value.contains(':')) {
        usdtParsePaymentRequest(value)
    } else {
        UsdtPaymentRequest(usdtValidateRecipient(value, destination), null, null)
    }
}

@Stable
data class UsdtWalletState(
    val address: String = "",
    val receiveUri: String = "",
    val balance: ULong? = null,
    val transfers: ImmutableList<UsdtTransfer> = persistentListOf(),
    val historyComplete: Boolean = false,
    val pendingReceipts: ImmutableList<UsdtTransfer> = persistentListOf(),
    val pendingDepositIds: ImmutableSet<String> = persistentSetOf(),
)

class UsdtSelfSendError : AppError("Cannot send USDT to own address")
