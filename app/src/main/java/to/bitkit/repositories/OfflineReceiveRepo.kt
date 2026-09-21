package to.bitkit.repositories

import androidx.compose.runtime.Immutable
import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.appScope
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMillis
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.USat
import to.bitkit.models.WalletScope
import to.bitkit.services.OfflineReceiveInvoiceParser
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineReceiveRepo @Inject constructor(
    private val service: OfflineReceiveService,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val invoiceParser: OfflineReceiveInvoiceParser,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val preparationMutex = Mutex()
    private val displayMutex = Mutex()
    private val sessionLock = Any()
    private var attemptedRequest: OfflineReceiveRequest? = null
    private var preparedInvoice: PreparedOfflineInvoice? = null
    private var persistedMetadata: Triple<String, List<String>, String>? = null
    private var sessionRevision = 0L
    private var paymentRevision = 0L
    private val _session = MutableStateFlow(OfflineReceiveSession())
    val session = _session.asStateFlow()

    init {
        val scope = appScope(ioDispatcher, "OfflineReceiveRepo")
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            lightningRepo.nodeEventUpdates.collect { update ->
                val event = update.event as? Event.PaymentReceived ?: return@collect
                synchronized(sessionLock) {
                    paymentRevision++
                    if (preparedInvoice?.paymentHash == event.paymentHash) preparedInvoice = null
                    val invoice = _session.value.invoice
                    if (invoice?.paymentHash == event.paymentHash) {
                        sessionRevision++
                        persistedMetadata = null
                        _session.value = OfflineReceiveSession(isSettled = true)
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            walletRepo.walletState.map { it.walletExists }.distinctUntilChanged().collect { exists ->
                if (!exists) clearSession()
            }
        }
    }

    fun clearSession() {
        synchronized(sessionLock) {
            sessionRevision++
            preparedInvoice = null
            attemptedRequest = null
            persistedMetadata = null
            _session.value = OfflineReceiveSession()
        }
    }

    suspend fun showInvoice(invoice: PreparedOfflineInvoice): Result<Unit> = withContext(ioDispatcher) {
        displayMutex.withLock {
            runSuspendCatching {
                val revision = synchronized(sessionLock) {
                    checkAvailable(preparedInvoice == invoice)
                    checkAvailable(invoice.expiresAtMillis > nowMillis())
                    sessionRevision
                }
                val metadata = persistMetadata(invoice)
                synchronized(sessionLock) {
                    checkAvailable(sessionRevision == revision && preparedInvoice == invoice)
                    persistedMetadata = metadata
                    _session.value = OfflineReceiveSession(invoice = invoice)
                }
            }
        }
    }

    private suspend fun persistMetadata(invoice: PreparedOfflineInvoice): Triple<String, List<String>, String> {
        val wallet = walletRepo.walletState.value
        val metadataKey = Triple(invoice.paymentHash, wallet.selectedTags.toList(), wallet.onchainAddress)
        if (synchronized(sessionLock) { persistedMetadata == metadataKey }) return metadataKey
        preActivityMetadataRepo.upsertPreActivityMetadata(
            listOf(
                PreActivityMetadata(
                    walletId = WalletScope.default,
                    paymentId = invoice.paymentHash,
                    createdAt = nowTimestamp().toEpochMilli().toULong(),
                    tags = metadataKey.second,
                    paymentHash = invoice.paymentHash,
                    txId = null,
                    address = wallet.onchainAddress,
                    isReceive = true,
                    feeRate = 0u,
                    isTransfer = false,
                    channelId = "",
                )
            )
        ).getOrThrow()
        return metadataKey
    }

    suspend fun canReceive(amountSats: ULong): Result<Boolean> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (amountSats == 0uL || amountSats > ULong.MAX_VALUE / 1_000uL ||
                !lightningRepo.lightningState.value.nodeLifecycleState.isRunning()
            ) {
                return@runSuspendCatching false
            }
            if (amountSats > walletRepo.inboundLiquiditySats()) return@runSuspendCatching false
            service.canReceive(amountSats).getOrThrow()
        }
    }

    suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> =
        withContext(ioDispatcher) {
            preparationMutex.withLock {
                runSuspendCatching { prepareValidatedInvoice(request) }
            }
        }

    private suspend fun prepareValidatedInvoice(request: OfflineReceiveRequest): PreparedOfflineInvoice {
        val revision = synchronized(sessionLock) { sessionRevision }
        val amountSats = request.amountSats
        val description = request.description
        checkAvailable(request.requestId.isNotBlank())
        val previousAttempt = synchronized(sessionLock) { attemptedRequest }
        checkAvailable(previousAttempt?.requestId != request.requestId || previousAttempt == request)
        if (previousAttempt != request) {
            checkAvailable(canReceive(amountSats).getOrThrow())
        }
        synchronized(sessionLock) {
            checkAvailable(sessionRevision == revision)
            attemptedRequest = request
        }
        val invoice = service.prepareInvoice(request).getOrThrow()
        checkAvailable(invoice.bolt11.isNotBlank())
        checkAvailable(invoice.amountSats == amountSats)
        checkAvailable(invoice.description == description)
        checkAvailable(invoice.expiresAtMillis > nowMillis())
        val decoded = invoiceParser.parse(invoice.bolt11)
        val expirySeconds = USat(decoded.timestampSeconds) + USat(decoded.expirySeconds)
        checkAvailable(decoded.network == Env.network)
        checkAvailable(decoded.amountMsat == USat(amountSats) * USat(1_000uL))
        checkAvailable(decoded.description == description)
        checkAvailable(decoded.paymentHash == invoice.paymentHash)
        checkAvailable(expirySeconds <= Long.MAX_VALUE.toULong() / 1_000uL)
        checkAvailable((USat(expirySeconds) * USat(1_000uL)).toLong() == invoice.expiresAtMillis)
        registerUnpaidInvoice(invoice, revision)
        return invoice
    }

    private suspend fun registerUnpaidInvoice(invoice: PreparedOfflineInvoice, revision: Long) {
        while (true) {
            val observedPayments = synchronized(sessionLock) { paymentRevision }
            val payments = lightningRepo.listPaymentsOrNull() ?: throw OfflineReceiveUnavailable()
            val alreadyPaid = payments.any {
                it.direction == PaymentDirection.INBOUND && it.status == PaymentStatus.SUCCEEDED &&
                    (it.kind as? PaymentKind.Bolt11)?.hash == invoice.paymentHash
            }
            checkAvailable(!alreadyPaid)
            synchronized(sessionLock) {
                checkAvailable(sessionRevision == revision)
                if (paymentRevision == observedPayments) {
                    preparedInvoice = invoice
                    return
                }
            }
        }
    }

    private fun checkAvailable(condition: Boolean) {
        if (!condition) throw OfflineReceiveUnavailable()
    }
}

@Immutable
data class OfflineReceiveSession(
    val invoice: PreparedOfflineInvoice? = null,
    val isSettled: Boolean = false,
)
