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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.data.CacheStore
import to.bitkit.data.CachedUsdtBalance
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitUsdt
import to.bitkit.services.UsdtService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.security.MessageDigest
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Singleton
class UsdtRepo @Inject constructor(
    private val service: UsdtService,
    private val cacheStore: CacheStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
) {
    companion object {
        private const val TAG = "UsdtRepo"
    }

    private val displayCacheNetwork = MessageDigest.getInstance("SHA-256")
        .digest("${Env.network}:${Env.usdtRpcUrl}:${Env.usdtBundlerUrl}".encodeToByteArray()).contentToString()
    private val mutex = Mutex()

    @Volatile var isSendPresented = false

    private var nextRefresh = 0L
    private var rateLimitedUntil = 0L
    private val _state = MutableStateFlow(UsdtWalletState())
    val state = _state.asStateFlow()
    val recoveryGeneration: Long get() = service.recoveryGeneration
    fun consumeReceipt(id: String): Boolean = _state.getAndUpdate {
        it.copy(pendingReceipts = it.pendingReceipts.filterNot { receipt -> receipt.id == id }.toImmutableList())
    }.pendingReceipts.any { it.id == id }

    suspend fun refresh(history: Boolean, force: Boolean = false) = withContext(ioDispatcher) {
        mutex.withLock {
            val refreshAfter = if (force) rateLimitedUntil else maxOf(nextRefresh, rateLimitedUntil)
            if (clock.now().toEpochMilliseconds() < refreshAfter) {
                return@withLock if (force) Result.failure(UsdtException.RateLimited()) else Result.success(Unit)
            }
            _state.update { it.copy(refreshing = true, balanceIsStale = true) }
            try {
                runSuspendCatching {
                    val wallet = service.wallet()
                    val address = wallet.receiveAddress()
                    if (_state.value.address != address) {
                        _state.update { UsdtWalletState(address = address, refreshing = true) }
                    }
                    if (_state.value.balance == null) {
                        runSuspendCatching { cacheStore.data.first().usdtDisplayBalance }
                            .onSuccess { cached ->
                                _state.update {
                                    it.copy(cachedBalance = cached?.balanceFor(address, displayCacheNetwork))
                                }
                            }.onFailure { Logger.warn("Failed to load display balance", it, context = TAG) }
                    }
                    val previous = _state.value.transfers.takeIf { _state.value.historyComplete }
                    val saved = wallet.history().toImmutableList()
                    _state.update { it.copy(receiveUri = wallet.receiveUri(), transfers = saved) }
                    val settlement = runSuspendCatching {
                        val balance = wallet.balance()
                        _state.update { it.copy(balance = balance, balanceIsStale = false) }
                        runSuspendCatching {
                            cacheStore.update {
                                it.copy(usdtDisplayBalance = CachedUsdtBalance(address, displayCacheNetwork, balance))
                            }
                        }.onFailure { Logger.warn("Failed to cache display balance", it, context = TAG) }
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
                        rateLimitedUntil = clock.now().toEpochMilliseconds() + 60.seconds.inWholeMilliseconds
                    }
                    nextRefresh = maxOf(
                        rateLimitedUntil,
                        clock.now().toEpochMilliseconds() + 10.seconds.inWholeMilliseconds
                    )
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
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
                if (clock.now().toEpochMilliseconds() < rateLimitedUntil) return@withLock Result.success(Unit)
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
                        rateLimitedUntil = clock.now().toEpochMilliseconds() + 60.seconds.inWholeMilliseconds
                        nextRefresh = maxOf(nextRefresh, rateLimitedUntil)
                    }
                }
            }
        } ?: Result.success(Unit)
    }

    suspend fun paymentEndpoint(): Result<Endpoint> = operation { PaykitUsdt.endpoint(paymentAddress) }

    suspend fun sendDestinations() = operation {
        (Env.usdtDestinations + service.wallet().orchestraDestinations()).distinct().toImmutableList()
    }

    fun hasStoredWallet(): Boolean = runCatching { service.hasStoredWallet }.getOrDefault(false)

    fun shouldRefresh(enabled: Boolean): Boolean = enabled || state.value.hasFundsOrPendingTransfers ||
        (state.value.balance == null && hasStoredWallet())

    fun backupSnapshot(): String? = service.backupSnapshot()

    val isRecoveryRestorePending: Boolean get() = service.isRecoveryRestorePending

    suspend fun waitForRecoveryIdle() = mutex.withLock { }

    suspend fun retryTransfer(id: String): Result<Unit> = paymentOperation {
        val wallet = wallet()
        val transfer = wallet.history().firstOrNull { it.id == id } ?: throw UsdtException.InvalidBackup()
        if (transfer.status == UsdtTransferStatus.PENDING) {
            val transfers = wallet.refreshTransfers().toImmutableList()
            _state.update { it.copy(transfers = transfers) }
        }
    }

    suspend fun restoreBackup(snapshot: String) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                val wallet = service.wallet(requiresNetwork = false)
                wallet.restoreBackup(snapshot)
                clearDisplayBalance()
                _state.update {
                    it.copy(
                        address = wallet.receiveAddress(),
                        balance = null,
                        cachedBalance = null,
                        balanceIsStale = true,
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

    private suspend fun <T> paymentOperation(block: suspend UsdtService.() -> T): Result<T> = operation {
        if (clock.now().toEpochMilliseconds() < rateLimitedUntil) throw UsdtException.RateLimited()
        runSuspendCatching { block(this) }.onFailure {
            if (it is UsdtException.RateLimited) {
                rateLimitedUntil = clock.now().toEpochMilliseconds() + 60.seconds.inWholeMilliseconds
            }
        }.getOrThrow()
    }

    suspend fun wipe(clearCredentials: suspend () -> Unit = {}) = withContext(ioDispatcher) {
        mutex.withLock {
            clearDisplayBalance()
            clearCredentials()
            runSuspendCatching { service.wipe() }.also {
                _state.update { UsdtWalletState() }
                nextRefresh = 0L
                rateLimitedUntil = 0L
            }
        }
    }

    private suspend fun clearDisplayBalance() {
        runSuspendCatching { cacheStore.update { it.copy(usdtDisplayBalance = null) } }
            .onFailure { Logger.warn("Failed to clear display balance", it, context = TAG) }
    }

    suspend fun quote(
        recipient: String,
        amount: String,
        destination: UsdtDestination,
        locale: Locale = Locale.getDefault(),
    ) = paymentOperation {
        val wallet = wallet()
        if (recipient.trim().equals(wallet.receiveAddress(), ignoreCase = true)) throw UsdtSelfSendError()
        wallet.quoteTransfer(
            recipient,
            usdtParseAmount(amount.replace(DecimalFormatSymbols.getInstance(locale).decimalSeparator, '.')),
            destination
        )
    }

    suspend fun quoteMaximum(recipient: String, destination: UsdtDestination) = paymentOperation {
        val wallet = wallet()
        if (recipient.trim().equals(wallet.receiveAddress(), ignoreCase = true)) throw UsdtSelfSendError()
        wallet.quoteMaxTransfer(recipient, destination)
    }

    suspend fun send(
        quote: UsdtQuote,
        submissionDeadline: Instant? = null,
        beforeSend: suspend () -> Unit = {},
    ) = paymentOperation {
        runSuspendCatching {
            beforeSend()
            quote.requireValidForSubmission(clock.now())
            send(quote, submissionDeadline).also { transfer ->
                _state.update {
                    it.copy(
                        transfers = (listOf(transfer) + it.transfers.filter { saved -> saved.id != transfer.id })
                            .toImmutableList()
                    )
                }
            }
        }.onFailure {
            runSuspendCatching { wallet().history().toImmutableList() }
                .onSuccess { transfers -> _state.update { it.copy(transfers = transfers) } }
        }.getOrThrow()
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
    val cachedBalance: ULong? = null,
    val balanceIsStale: Boolean = true,
    val refreshing: Boolean = false,
    val transfers: ImmutableList<UsdtTransfer> = persistentListOf(),
    val historyComplete: Boolean = false,
    val pendingReceipts: ImmutableList<UsdtTransfer> = persistentListOf(),
    val pendingDepositIds: ImmutableSet<String> = persistentSetOf(),
)

class UsdtSelfSendError : AppError("Cannot send USDT to own address")
