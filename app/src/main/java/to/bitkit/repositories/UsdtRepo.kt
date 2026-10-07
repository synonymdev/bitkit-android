package to.bitkit.repositories

import androidx.compose.runtime.Stable
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitUsdt
import to.bitkit.services.UsdtService
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class UsdtRepo @Inject constructor(
    private val service: UsdtService,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    @Volatile var isSendPresented = false

    private var nextRefresh = 0L
    private var rateLimitedUntil = 0L
    private val _state = MutableStateFlow(UsdtWalletState())
    val state = _state.asStateFlow()
    private val _receivedTxs = MutableSharedFlow<UsdtTransfer>(extraBufferCapacity = 8)
    val receivedTxs = _receivedTxs.asSharedFlow()

    suspend fun refresh(history: Boolean, force: Boolean = false) = withContext(ioDispatcher) {
        mutex.withLock {
            val refreshAfter = if (force) rateLimitedUntil else maxOf(nextRefresh, rateLimitedUntil)
            if (nowMillis() < refreshAfter) return@withLock Result.success(Unit)
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

    private suspend fun announceNewReceipts(previous: List<UsdtTransfer>?, current: List<UsdtTransfer>) {
        if (previous == null) return
        val knownIds = previous.filter { it.isIncoming && it.status == UsdtTransferStatus.CONFIRMED }
            .map { it.id }.toSet()
        current.filter {
            it.isIncoming && it.status == UsdtTransferStatus.CONFIRMED && it.amount > 0u && it.id !in knownIds
        }.forEach { _receivedTxs.emit(it) }
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
        (to.bitkit.env.Env.usdtDestinations + service.wallet().orchestraDestinations()).distinct().toImmutableList()
    }

    fun backupSnapshot(): String? = service.backupSnapshot()

    suspend fun restoreBackup(snapshot: String) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                val wallet = service.wallet()
                wallet.restoreBackup(snapshot)
                _state.update {
                    it.copy(
                        address = wallet.receiveAddress(),
                        transfers = wallet.history().toImmutableList(),
                        historyComplete = false,
                    )
                }
            }
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
                service.wallet().quoteTransfer(
                    recipient,
                    usdtParseAmount(amount.replace(DecimalFormatSymbols.getInstance(locale).decimalSeparator, '.')),
                    destination
                )
            }
        }
    }

    suspend fun send(quote: UsdtQuote, beforeSend: suspend () -> Unit = {}) = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                beforeSend()
                service.send(quote).also { transfer ->
                    _state.update {
                        it.copy(
                            transfers = (listOf(transfer) + it.transfers.filter { saved -> saved.id != transfer.id })
                                .toImmutableList()
                        )
                    }
                }
            }
        }
    }
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
)
