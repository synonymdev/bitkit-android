package to.bitkit.repositories

import com.synonym.bitkitcore.LnurlPayData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.appScope
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.callbackAmountMsats
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.supportPaymentRequest
import to.bitkit.models.USD
import to.bitkit.models.msatFloorOf
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.asNodeException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

fun interface QuickPayInvoiceParser {
    fun parse(bolt11: String): String?
}

fun interface QuickPayPaymentLookup {
    suspend fun rows(): List<QuickPayReconcileRow>?
}

data class QuickPaySession(val id: String = UUID.randomUUID().toString())

sealed interface QuickPayPayRequest {
    val amountSats: ULong

    data class Bolt11(
        val bolt11: String,
        override val amountSats: ULong,
    ) : QuickPayPayRequest

    data class LnurlPay(
        val data: LnurlPayData,
        override val amountSats: ULong,
    ) : QuickPayPayRequest
}

sealed interface QuickPaySessionEvent {
    data class Success(
        val paymentHash: String,
        val amountWithFee: Long,
    ) : QuickPaySessionEvent

    data class Pending(
        val paymentHash: String,
        val amount: Long,
        val paymentRequest: String,
    ) : QuickPaySessionEvent

    data object FallBackToConfirm : QuickPaySessionEvent

    data class Error(
        val error: Throwable,
        val paymentRequest: String?,
    ) : QuickPaySessionEvent
}

enum class QuickPayCompletionKind {
    NONE,
    SETTLED_SUCCESS,
    SETTLED_FAILURE,
}

data class QuickPayCompletionOutcome(
    val kind: QuickPayCompletionKind = QuickPayCompletionKind.NONE,
    val invoicePaymentHash: String? = null,
    val sessionNotified: Boolean = false,
) {
    val wasQuickPay: Boolean get() = kind != QuickPayCompletionKind.NONE

    companion object {
        val None = QuickPayCompletionOutcome()
    }
}

class QuickPayConversionError : AppError("Currency conversion failed")

class QuickPayPaymentFailedError(
    val paymentHash: String,
    val reason: PaymentFailureReason?,
    val paymentRequest: String?,
) : AppError(reason?.name)

@Singleton
@Suppress("LongParameterList", "LargeClass")
class QuickPayRepo @Inject constructor(
    cacheStore: CacheStore,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    private val lightningRepo: LightningRepo,
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val invoiceParser: QuickPayInvoiceParser,
    private val paymentLookup: QuickPayPaymentLookup,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    clock: Clock,
) {
    companion object {
        private const val TAG = "QuickPayRepo"
    }

    private val spend = QuickPaySpendStore(cacheStore, clock)
    private val scope = appScope(ioDispatcher, TAG)
    private val mutex = Mutex()
    private val opsByKey = mutableMapOf<String, InFlightOp>()
    private val sessionFlows = ConcurrentHashMap<String, MutableSharedFlow<QuickPaySessionEvent>>()

    init {
        scope.launch {
            lightningRepo.lightningState
                .map { it.nodeLifecycleState.isRunning() to it.isSyncHealthy }
                .distinctUntilChanged()
                .collect { (running, _) ->
                    if (running) reconcileAgainstLdk()
                }
        }
    }

    fun attach(session: QuickPaySession): Flow<QuickPaySessionEvent> {
        val flow = MutableSharedFlow<QuickPaySessionEvent>(extraBufferCapacity = 8)
        sessionFlows[session.id] = flow
        return flow
    }

    fun detach(session: QuickPaySession) {
        scope.launch { detachSession(session.id) }
    }

    fun detachAll() {
        val ids = sessionFlows.keys.toList()
        scope.launch {
            ids.forEach { detachSession(it) }
        }
    }

    fun pay(session: QuickPaySession, request: QuickPayPayRequest) {
        scope.launch { payNow(session, request) }
    }

    suspend fun canApply(amountSats: ULong): Result<Boolean> = withContext(ioDispatcher) {
        runSuspendCatching {
            val settings = settingsStore.data.first()
            if (!settings.isQuickPayEnabled || amountSats == 0uL) return@runSuspendCatching false

            val thresholdSats = currencyRepo.convertFiatToSats(
                settings.quickPayAmount.toDouble(),
                USD,
            ).getOrNull() ?: return@runSuspendCatching false
            if (amountSats > thresholdSats) return@runSuspendCatching false

            val converted = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrNull()
                ?: return@runSuspendCatching false
            val reserveCents = quickPayReserveCents(converted.toUsdCents(), settings.quickPayAmount, amountSats)
            val capCents = quickPayCapCents(settings.quickPayAmount, settings.quickPayDailyLimitMultiplier)
            val snapshot = mutex.withLock { spend.snapshot() }
            if (!snapshot.supported) return@runSuspendCatching false
            if (snapshot.spentCents + reserveCents <= capCents) return@runSuspendCatching true

            Logger.info(
                "Skipping QuickPay: daily spend '${snapshot.spentCents}' + '$reserveCents' exceeds cap '$capCents'",
                context = TAG,
            )
            false
        }
    }

    internal suspend fun reserveBound(
        paymentHash: String,
        amountSats: ULong,
    ): Result<QuickPayLedgerRecord?> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (paymentHash.isBlank()) return@runSuspendCatching null
            val prepared = prepareReserve(amountSats) ?: return@runSuspendCatching null
            mutex.withLock {
                spend.reserve(
                    paymentHash,
                    prepared.amountCents,
                    prepared.capCents,
                    opsByKey.values.map { it.invoiceHash }.toSet(),
                )
            }
        }
    }

    suspend fun signalCompletion(
        paymentId: String?,
        paymentHash: String?,
        success: Boolean,
        feePaidMsat: ULong? = null,
        failureReason: PaymentFailureReason? = null,
    ): QuickPayCompletionOutcome = withContext(ioDispatcher) {
        mutex.withLock {
            signalCompletionLocked(
                paymentId = paymentId,
                paymentHash = paymentHash,
                success = success,
                feePaidMsat = feePaidMsat,
                failureReason = failureReason,
            )
        }
    }

    internal suspend fun reconcileAgainstLdk() {
        val rows = loadPaymentRows()
        mutex.withLock { reconcileLocked(rows) }
    }

    internal suspend fun payNow(session: QuickPaySession, request: QuickPayPayRequest) {
        val invoice = resolveInvoice(session, request) ?: return
        val invoiceHash = invoiceHashOrEmit(session, invoice) ?: return
        when (preparePay(session, invoice, invoiceHash)) {
            PreparePayResult.LIVE -> return
            PreparePayResult.REJECTED -> return
            PreparePayResult.RECOVERED -> settleRecovered(invoiceHash)
            PreparePayResult.FRESH -> {
                dispatchBolt11(invoice, invoiceHash)
                awaitCompletionOrPending(invoiceHash)
            }
        }
    }

    internal suspend fun hasOpen(paymentHash: String): Boolean = mutex.withLock {
        opsByKey[paymentHash] != null || spend.matching(paymentHash) != null
    }

    private fun invoiceHashOrEmit(session: QuickPaySession, invoice: ResolvedInvoice): String? {
        val invoiceHash = invoiceParser.parse(invoice.bolt11)
        if (invoiceHash != null) return invoiceHash
        emitToSession(
            session.id,
            QuickPaySessionEvent.Error(
                invoice.parseError ?: QuickPayConversionError(),
                invoice.bolt11,
            ),
        )
        return null
    }

    private suspend fun preparePay(
        session: QuickPaySession,
        invoice: ResolvedInvoice,
        invoiceHash: String,
    ): PreparePayResult {
        val prepared = runSuspendCatching { prepareReserve(invoice.amountSats) }.getOrElse {
            emitToSession(session.id, QuickPaySessionEvent.Error(it, invoice.bolt11))
            return PreparePayResult.REJECTED
        }
        return mutex.withLock {
            val existing = opsByKey[invoiceHash]
            if (existing != null) {
                existing.sessionId = session.id
                when {
                    existing.emitted -> emitToSession(
                        session.id,
                        QuickPaySessionEvent.Pending(
                            paymentHash = existing.invoiceHash,
                            amount = existing.displaySats.toLong(),
                            paymentRequest = existing.paymentRequest,
                        ),
                    )
                    existing.job?.isActive != true -> emitPendingLocked(existing)
                }
                return@withLock PreparePayResult.LIVE
            }
            val open = spend.matching(invoiceHash)
            if (open != null) {
                registerOpLocked(recoveredOp(session, invoice, invoiceHash, open))
                return@withLock PreparePayResult.RECOVERED
            }
            val keepHashes = opsByKey.values.map { it.invoiceHash }.toSet()
            if (prepared == null ||
                spend.reserve(invoiceHash, prepared.amountCents, prepared.capCents, keepHashes) == null
            ) {
                rejectCapLocked(session, invoice)
                return@withLock PreparePayResult.REJECTED
            }
            if (sessionFlows[session.id] == null) {
                spend.release(invoiceHash)
                return@withLock PreparePayResult.REJECTED
            }
            registerOpLocked(
                InFlightOp(
                    invoiceHash = invoiceHash,
                    displaySats = invoice.amountSats,
                    paymentRequest = invoice.bolt11,
                    dispatched = false,
                    sessionId = session.id,
                    job = coroutineContext[Job],
                    paymentId = null,
                ),
            )
            PreparePayResult.FRESH
        }
    }

    private suspend fun settleRecovered(invoiceHash: String) {
        val rows = loadPaymentRows()
        mutex.withLock {
            val op = opsByKey[invoiceHash] ?: return@withLock
            val record = spend.matching(invoiceHash) ?: run {
                emitPendingLocked(op)
                return@withLock
            }
            val match = rows?.let { pickLedgerMatch(record, it) }
            when (match?.status) {
                QuickPayReconcileRow.Status.SUCCEEDED -> {
                    signalCompletionLocked(
                        paymentId = record.paymentId,
                        paymentHash = invoiceHash,
                        success = true,
                    )
                }
                QuickPayReconcileRow.Status.FAILED -> {
                    val outcome = signalCompletionLocked(
                        paymentId = record.paymentId,
                        paymentHash = invoiceHash,
                        success = false,
                    )
                    if (outcome.kind == QuickPayCompletionKind.NONE) {
                        emitPendingLocked(op)
                    }
                }
                else -> emitPendingLocked(op)
            }
        }
    }

    private suspend fun dispatchBolt11(invoice: ResolvedInvoice, invoiceHash: String) {
        lightningRepo.payInvoice(bolt11 = invoice.bolt11, sats = null) {
            tryMarkDispatched(invoiceHash)
        }.fold(
            onSuccess = { onInvoiceAccepted(invoiceHash, it) },
            onFailure = { onInvoiceRejected(invoiceHash, invoice.bolt11, it) },
        )
    }

    private suspend fun tryMarkDispatched(invoiceHash: String): Boolean = mutex.withLock {
        val current = opsByKey[invoiceHash] ?: return@withLock false
        if (current.cancelBeforeDispatch) return@withLock false
        current.dispatched = true
        true
    }

    private suspend fun onInvoiceAccepted(invoiceHash: String, paymentId: String) {
        mutex.withLock {
            spend.markSubmitted(invoiceHash, paymentId)
            val current = opsByKey[invoiceHash] ?: return@withLock
            current.paymentId = paymentId
            if (paymentId.isNotBlank() && paymentId != invoiceHash) {
                opsByKey[paymentId] = current
            }
        }
    }

    private suspend fun onInvoiceRejected(
        invoiceHash: String,
        paymentRequest: String,
        error: Throwable,
    ) {
        if (error is PaymentAbortedBeforeSend) {
            releaseIfNotDispatched(invoiceHash)
            return
        }
        handleDispatchError(invoiceHash, paymentRequest, error)
    }

    private suspend fun awaitCompletionOrPending(invoiceHash: String) {
        val current = mutex.withLock { opsByKey[invoiceHash] } ?: return
        withTimeoutOrNull(LightningRepo.SEND_LN_TIMEOUT) {
            current.settled.await()
        }
        mutex.withLock {
            val live = opsByKey[invoiceHash] ?: return@withLock
            emitPendingLocked(live)
        }
    }

    private suspend fun releaseIfNotDispatched(invoiceHash: String) {
        mutex.withLock {
            val current = opsByKey[invoiceHash]
            if (current == null || current.dispatched) return@withLock
            spend.release(invoiceHash)
            removeOpLocked(current)
        }
    }

    private suspend fun resolveInvoice(
        session: QuickPaySession,
        request: QuickPayPayRequest,
    ): ResolvedInvoice? {
        return when (request) {
            is QuickPayPayRequest.Bolt11 -> ResolvedInvoice(
                bolt11 = request.bolt11,
                amountSats = request.amountSats,
                parseError = null,
            )
            is QuickPayPayRequest.LnurlPay -> {
                lightningRepo.fetchLnurlInvoice(
                    data = request.data,
                    amountMsats = request.data.callbackAmountMsats(request.amountSats),
                ).fold(
                    onSuccess = { ResolvedInvoice(it.bolt11, request.amountSats, null) },
                    onFailure = {
                        if (sessionFlows[session.id] != null) {
                            emitToSession(
                                session.id,
                                QuickPaySessionEvent.Error(it, request.data.supportPaymentRequest()),
                            )
                        }
                        null
                    },
                )
            }
        }
    }

    private suspend fun handleDispatchError(
        invoiceHash: String,
        paymentRequest: String,
        error: Throwable,
    ) {
        when (val kind = classifyDispatchError(error)) {
            QuickPayDispatchClass.PRE_DISPATCH_REJECTION -> {
                mutex.withLock {
                    signalCompletionLocked(
                        paymentId = null,
                        paymentHash = invoiceHash,
                        success = false,
                    )
                    emitOutcomeLocked(invoiceHash, error, paymentRequest)
                }
            }
            QuickPayDispatchClass.DUPLICATE_PAYMENT,
            QuickPayDispatchClass.AMBIGUOUS,
            -> {
                val rows = loadPaymentRows()
                mutex.withLock {
                    settleAmbiguousLocked(
                        invoiceHash = invoiceHash,
                        paymentRequest = paymentRequest,
                        error = error,
                        rows = rows,
                        duplicate = kind == QuickPayDispatchClass.DUPLICATE_PAYMENT,
                    )
                }
            }
        }
    }

    private suspend fun settleAmbiguousLocked(
        invoiceHash: String,
        paymentRequest: String,
        error: Throwable,
        rows: List<QuickPayReconcileRow>?,
        duplicate: Boolean,
    ) {
        val op = opsByKey[invoiceHash]
        if (!duplicate && op != null && !op.dispatched) {
            spend.release(invoiceHash)
            emitErrorLocked(op, error, paymentRequest)
            removeOpLocked(op)
            return
        }
        val record = spend.matching(invoiceHash)
        val applied = if (record != null && rows != null) {
            applyAmbiguousLookupLocked(record, rows, duplicate)
        } else {
            AmbiguousApply.UNCHANGED
        }
        val remaining = spend.matching(invoiceHash)
        if (remaining != null) {
            op?.dispatched = true
            op?.let { emitPendingLocked(it) }
            return
        }
        if (op == null) return
        when (applied) {
            AmbiguousApply.SUCCEEDED -> emitSuccessLocked(op, feePaidMsat = null)
            AmbiguousApply.FAILED,
            AmbiguousApply.UNCHANGED,
            -> emitErrorLocked(op, error, paymentRequest)
        }
        removeOpLocked(op)
    }

    private suspend fun detachSession(sessionId: String) {
        mutex.withLock {
            sessionFlows.remove(sessionId)
            val op = opsByKey.values.firstOrNull { it.sessionId == sessionId } ?: return@withLock
            if (op.sessionId != sessionId) return@withLock
            op.sessionId = null
            if (op.dispatched) return@withLock
            op.cancelBeforeDispatch = true
            op.job?.cancel()
            spend.release(op.invoiceHash)
            removeOpLocked(op)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun signalCompletionLocked(
        paymentId: String?,
        paymentHash: String?,
        success: Boolean,
        feePaidMsat: ULong? = null,
        failureReason: PaymentFailureReason? = null,
    ): QuickPayCompletionOutcome {
        val keys = listOfNotNull(paymentId, paymentHash).filter { it.isNotBlank() }
        if (keys.isEmpty()) return QuickPayCompletionOutcome.None

        val snapshot = spend.snapshot()
        if (!snapshot.supported) return QuickPayCompletionOutcome.None
        val ledger = snapshot.ledger ?: return QuickPayCompletionOutcome.None
        val index = keys.firstNotNullOfOrNull { ledger.recordIndex(it) } ?: return QuickPayCompletionOutcome.None
        val record = ledger.records[index]
        val op = opsByKey[record.invoicePaymentHash] ?: record.paymentId?.let { opsByKey[it] }
        if (!success && !isAttributedFailure(record, op, paymentId, paymentHash)) {
            return QuickPayCompletionOutcome.None
        }

        spend.settle(keys, success)

        val kind = if (success) {
            QuickPayCompletionKind.SETTLED_SUCCESS
        } else {
            QuickPayCompletionKind.SETTLED_FAILURE
        }
        var sessionNotified = false
        if (op != null) {
            sessionNotified = if (success) {
                emitSuccessLocked(op, feePaidMsat)
            } else {
                emitErrorLocked(
                    op,
                    QuickPayPaymentFailedError(
                        paymentHash = record.invoicePaymentHash,
                        reason = failureReason,
                        paymentRequest = op.paymentRequest,
                    ),
                    op.paymentRequest,
                )
            }
            removeOpLocked(op)
        }
        return QuickPayCompletionOutcome(
            kind = kind,
            invoicePaymentHash = record.invoicePaymentHash,
            sessionNotified = sessionNotified,
        )
    }

    private fun isAttributedFailure(
        record: QuickPayLedgerRecord,
        op: InFlightOp?,
        paymentId: String?,
        paymentHash: String?,
    ): Boolean {
        if (record.paymentId != null && (record.paymentId == paymentId || record.paymentId == paymentHash)) {
            return true
        }
        if (op?.dispatched == true &&
            (paymentHash == record.invoicePaymentHash || paymentId == record.invoicePaymentHash)
        ) {
            return true
        }
        if (record.phase == QuickPayRecordPhase.SUBMITTED &&
            (paymentHash == record.invoicePaymentHash || paymentId == record.invoicePaymentHash)
        ) {
            return true
        }
        return false
    }

    private suspend fun applyAmbiguousLookupLocked(
        record: QuickPayLedgerRecord,
        rows: List<QuickPayReconcileRow>,
        duplicate: Boolean,
    ): AmbiguousApply {
        val match = pickLedgerMatch(record, rows) ?: return AmbiguousApply.UNCHANGED
        return when (match.status) {
            QuickPayReconcileRow.Status.PENDING -> AmbiguousApply.UNCHANGED
            QuickPayReconcileRow.Status.SUCCEEDED -> {
                if (duplicate &&
                    record.phase == QuickPayRecordPhase.SUBMITTING &&
                    record.paymentId == null
                ) {
                    spend.release(record.invoicePaymentHash)
                } else {
                    spend.drop(record.invoicePaymentHash)
                }
                AmbiguousApply.SUCCEEDED
            }
            QuickPayReconcileRow.Status.FAILED -> {
                val attributed = isAttributedFailure(
                    record,
                    opsByKey[record.invoicePaymentHash],
                    match.paymentId,
                    match.invoicePaymentHash,
                )
                if (!attributed) {
                    return AmbiguousApply.UNCHANGED
                }
                spend.release(record.invoicePaymentHash)
                AmbiguousApply.FAILED
            }
        }
    }

    private suspend fun reconcileLocked(rows: List<QuickPayReconcileRow>?) {
        val live = opsByKey.values.map { it.invoiceHash }.toSet()
        spend.applyReconcile(rows, live) { record, match ->
            isAttributedFailure(record, opsByKey[record.invoicePaymentHash], match.paymentId, match.invoicePaymentHash)
        }
    }

    private fun recoveredOp(
        session: QuickPaySession,
        invoice: ResolvedInvoice,
        invoiceHash: String,
        open: QuickPayLedgerRecord,
    ) = InFlightOp(
        invoiceHash = invoiceHash,
        displaySats = invoice.amountSats,
        paymentRequest = invoice.bolt11,
        dispatched = true,
        sessionId = session.id,
        job = null,
        paymentId = open.paymentId,
    )

    private fun rejectCapLocked(session: QuickPaySession, invoice: ResolvedInvoice) {
        Logger.info(
            "Skipping QuickPay pay: daily spend reserve failed for '${invoice.amountSats}'",
            context = TAG,
        )
        emitToSession(session.id, QuickPaySessionEvent.FallBackToConfirm)
    }

    private fun registerOpLocked(op: InFlightOp) {
        opsByKey[op.invoiceHash] = op
        op.paymentId?.takeIf { it.isNotBlank() && it != op.invoiceHash }?.let { opsByKey[it] = op }
    }

    private fun removeOpLocked(op: InFlightOp) {
        opsByKey.entries.removeAll { it.value === op }
    }

    private fun emitOutcomeLocked(
        invoiceHash: String,
        error: Throwable,
        paymentRequest: String,
    ) {
        val op = opsByKey[invoiceHash] ?: return
        emitErrorLocked(op, error, paymentRequest)
        removeOpLocked(op)
    }

    private fun emitPendingLocked(op: InFlightOp) {
        if (op.settled.isCompleted || op.emitted) return
        val sessionId = op.sessionId ?: return
        op.emitted = true
        pendingPaymentRepo.track(op.invoiceHash)
        emitToSession(
            sessionId,
            QuickPaySessionEvent.Pending(
                paymentHash = op.invoiceHash,
                amount = op.displaySats.toLong(),
                paymentRequest = op.paymentRequest,
            ),
        )
        op.settled.complete(Unit)
    }

    private fun emitSuccessLocked(op: InFlightOp, feePaidMsat: ULong?): Boolean {
        if (op.emitted) {
            op.settled.complete(Unit)
            return false
        }
        op.emitted = true
        val feeSats = msatFloorOf(feePaidMsat ?: 0u)
        val notified = emitToSession(
            op.sessionId,
            QuickPaySessionEvent.Success(
                paymentHash = op.invoiceHash,
                amountWithFee = (op.displaySats.safe() + feeSats.safe()).toLong(),
            ),
        )
        op.settled.complete(Unit)
        return notified
    }

    private fun emitErrorLocked(op: InFlightOp, error: Throwable, paymentRequest: String?): Boolean {
        if (op.emitted) {
            op.settled.complete(Unit)
            return false
        }
        op.emitted = true
        val notified = emitToSession(op.sessionId, QuickPaySessionEvent.Error(error, paymentRequest))
        op.settled.complete(Unit)
        return notified
    }

    private suspend fun prepareReserve(amountSats: ULong): PreparedReserve? {
        val settings = settingsStore.data.first()
        val thresholdSats = currencyRepo.convertFiatToSats(
            settings.quickPayAmount.toDouble(),
            USD,
        ).getOrNull()
        if (thresholdSats == null || thresholdSats == 0uL || amountSats > thresholdSats) {
            return null
        }
        val converted = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrElse {
            throw QuickPayConversionError()
        }
        val amountCents = quickPayReserveCents(converted.toUsdCents(), settings.quickPayAmount, amountSats)
        val capCents = quickPayCapCents(settings.quickPayAmount, settings.quickPayDailyLimitMultiplier)
        return PreparedReserve(amountCents, capCents)
    }

    private suspend fun loadPaymentRows(): List<QuickPayReconcileRow>? =
        runSuspendCatching { paymentLookup.rows() }.getOrNull()

    private fun emitToSession(sessionId: String?, event: QuickPaySessionEvent): Boolean {
        if (sessionId == null) return false
        return sessionFlows[sessionId]?.tryEmit(event) == true
    }

    private data class InFlightOp(
        val invoiceHash: String,
        val displaySats: ULong,
        val paymentRequest: String,
        var dispatched: Boolean,
        var sessionId: String?,
        var job: Job?,
        var paymentId: String?,
        var cancelBeforeDispatch: Boolean = false,
        var emitted: Boolean = false,
        val settled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class ResolvedInvoice(
        val bolt11: String,
        val amountSats: ULong,
        val parseError: Throwable?,
    )

    private data class PreparedReserve(
        val amountCents: Long,
        val capCents: Long,
    )

    private enum class PreparePayResult { LIVE, RECOVERED, FRESH, REJECTED }

    private enum class AmbiguousApply { UNCHANGED, SUCCEEDED, FAILED }
}

internal class QuickPaySpendStore(
    private val cacheStore: CacheStore,
    private val clock: Clock,
) {
    companion object {
        const val LEDGER_VERSION = 1
    }

    suspend fun snapshot(): SpendSnapshot {
        val data = cacheStore.data.first()
        val (ledger, supported) = data.resolvedLedger()
        val dayKey = currentDayKey()
        if (!supported) return SpendSnapshot(0L, supported = false, ledger = ledger)
        val spend = spendFor(ledger, dayKey)
        return SpendSnapshot(spend.spentCents, supported = true, ledger = ledger)
    }

    suspend fun matching(hash: String): QuickPayLedgerRecord? {
        val (ledger, supported) = cacheStore.data.first().resolvedLedger()
        if (!supported) return null
        return ledger.recordMatching(hash)
    }

    suspend fun reserve(
        paymentHash: String,
        amountCents: Long,
        capCents: Long,
        keepHashes: Set<String> = emptySet(),
    ): QuickPayLedgerRecord? {
        var reserved: QuickPayLedgerRecord? = null
        val wrote = writeLedger { ledger, dayKey ->
            if (ledger.recordMatching(paymentHash) != null) return@writeLedger ledger
            val spend = spendFor(ledger, dayKey)
            if (spend.spentCents > Long.MAX_VALUE - amountCents) return@writeLedger ledger
            val total = spend.spentCents + amountCents
            if (total > capCents) return@writeLedger ledger
            val next = ledger.pruned(spend.dayKey, keepHashes)
            val record = QuickPayLedgerRecord(
                id = UUID.randomUUID().toString(),
                amountCents = amountCents,
                dayKey = spend.dayKey,
                invoicePaymentHash = paymentHash,
                paymentId = null,
                phase = QuickPayRecordPhase.SUBMITTING,
            )
            reserved = record
            next.copy(
                dayKey = spend.dayKey,
                spentCents = total,
                records = next.records + record,
            )
        }
        return if (!wrote) null else reserved
    }

    suspend fun release(paymentHash: String) {
        writeLedger { ledger, _ -> releaseRecord(ledger, paymentHash) }
    }

    suspend fun drop(paymentHash: String) {
        writeLedger { ledger, _ ->
            val index = ledger.recordIndex(paymentHash) ?: return@writeLedger ledger
            ledger.copy(records = ledger.records.toMutableList().also { it.removeAt(index) })
        }
    }

    suspend fun markSubmitted(invoiceHash: String, paymentId: String) {
        writeLedger { ledger, _ ->
            val index = ledger.recordIndex(invoiceHash) ?: return@writeLedger ledger
            val record = ledger.records[index]
            ledger.copy(
                records = ledger.records.toMutableList().also {
                    it[index] = record.copy(
                        paymentId = paymentId,
                        phase = QuickPayRecordPhase.SUBMITTED,
                    )
                },
            )
        }
    }

    suspend fun settle(keys: List<String>, success: Boolean) {
        writeLedger { current, _ ->
            val i = keys.firstNotNullOfOrNull { current.recordIndex(it) } ?: return@writeLedger current
            val found = current.records[i]
            val remaining = current.records.toMutableList().also { it.removeAt(i) }
            val spent = if (!success && found.dayKey == current.dayKey) {
                (current.spentCents - found.amountCents).coerceAtLeast(0L)
            } else {
                current.spentCents
            }
            current.copy(records = remaining, spentCents = spent)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun applyReconcile(
        rows: List<QuickPayReconcileRow>?,
        liveSubmittingHashes: Set<String>,
        shouldReleaseFailed: (QuickPayLedgerRecord, QuickPayReconcileRow) -> Boolean,
    ) {
        if (rows == null) return
        writeLedger { ledger, dayKey ->
            val next = ledger.pruned(dayKey, liveSubmittingHashes)
            val remaining = mutableListOf<QuickPayLedgerRecord>()
            var spent = next.spentCents
            for (record in next.records) {
                if (liveSubmittingHashes.contains(record.invoicePaymentHash)) {
                    remaining.add(record)
                    continue
                }
                val match = pickLedgerMatch(record, rows)
                if (match == null) {
                    remaining.add(record)
                    continue
                }
                when (match.status) {
                    QuickPayReconcileRow.Status.PENDING -> remaining.add(record)
                    QuickPayReconcileRow.Status.SUCCEEDED -> Unit
                    QuickPayReconcileRow.Status.FAILED -> {
                        if (!shouldReleaseFailed(record, match)) {
                            remaining.add(record)
                        } else if (record.dayKey == next.dayKey) {
                            spent = (spent - record.amountCents).coerceAtLeast(0L)
                        }
                    }
                }
            }
            next.copy(records = remaining, spentCents = spent)
        }
    }

    private suspend fun writeLedger(transform: (QuickPayLedger, String) -> QuickPayLedger): Boolean {
        var supported = true
        cacheStore.update { data ->
            val (ledger, ok) = data.resolvedLedger()
            if (!ok) {
                supported = false
                return@update data
            }
            val dayKey = currentDayKey()
            val next = transform(ledger, dayKey)
            data.copy(quickPayLedger = next)
        }
        return supported
    }

    private fun currentDayKey(): String =
        clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
}

internal data class SpendSnapshot(
    val spentCents: Long,
    val supported: Boolean,
    val ledger: QuickPayLedger?,
)

internal fun classifyDispatchError(error: Throwable): QuickPayDispatchClass {
    return when (error.asNodeException()) {
        is NodeException.InvalidInvoice,
        is NodeException.InvalidAmount,
        is NodeException.InvalidPaymentHash,
        is NodeException.InvalidPaymentId,
        is NodeException.InvalidNetwork,
        -> QuickPayDispatchClass.PRE_DISPATCH_REJECTION
        is NodeException.DuplicatePayment -> QuickPayDispatchClass.DUPLICATE_PAYMENT
        else -> QuickPayDispatchClass.AMBIGUOUS
    }
}

enum class QuickPayDispatchClass {
    PRE_DISPATCH_REJECTION,
    DUPLICATE_PAYMENT,
    AMBIGUOUS,
}

data class QuickPayReconcileRow(
    val paymentId: String,
    val invoicePaymentHash: String,
    val isOutboundBolt11: Boolean,
    val status: Status,
) {
    enum class Status { SUCCEEDED, FAILED, PENDING }

    constructor(payment: PaymentDetails) : this(
        paymentId = payment.id,
        invoicePaymentHash = when (val kind = payment.kind) {
            is PaymentKind.Bolt11 -> kind.hash
            else -> payment.id
        },
        isOutboundBolt11 = payment.direction == PaymentDirection.OUTBOUND && payment.kind is PaymentKind.Bolt11,
        status = when (payment.status) {
            PaymentStatus.SUCCEEDED -> Status.SUCCEEDED
            PaymentStatus.FAILED -> Status.FAILED
            PaymentStatus.PENDING -> Status.PENDING
        },
    )
}

@Serializable
enum class QuickPayRecordPhase {
    @SerialName("submitting")
    SUBMITTING,

    @SerialName("submitted")
    SUBMITTED,
}

@Serializable
data class QuickPayLedgerRecord(
    val id: String,
    val amountCents: Long,
    val dayKey: String,
    val invoicePaymentHash: String,
    val paymentId: String? = null,
    val phase: QuickPayRecordPhase,
)

@Serializable
data class QuickPayLedger(
    val version: Int,
    val dayKey: String,
    val spentCents: Long,
    val records: List<QuickPayLedgerRecord> = emptyList(),
)

private fun quickPayCapCents(thresholdUsd: Int, multiplier: Int): Long =
    thresholdUsd.toLong() * 100L * multiplier.toLong()

private fun quickPayReserveCents(
    convertedCents: Long,
    thresholdUsd: Int,
    amountSats: ULong,
): Long {
    val clamped = minOf(convertedCents, thresholdUsd.toLong() * 100L)
    if (amountSats == 0uL) return clamped
    return maxOf(clamped, 1L)
}

private data class QuickPayDaySpend(
    val dayKey: String,
    val spentCents: Long,
)

private fun AppCacheData.resolvedLedger(): Pair<QuickPayLedger, Boolean> {
    val ledger = quickPayLedger
    if (ledger != null) {
        return ledger to (ledger.version == QuickPaySpendStore.LEDGER_VERSION)
    }
    return QuickPayLedger(
        version = QuickPaySpendStore.LEDGER_VERSION,
        dayKey = "",
        spentCents = 0L,
        records = emptyList(),
    ) to true
}

private fun QuickPayLedger.recordMatching(hash: String): QuickPayLedgerRecord? =
    records.find { it.invoicePaymentHash == hash || it.paymentId == hash || it.id == hash }

private fun QuickPayLedger.recordIndex(hash: String): Int? =
    records.indexOfFirst { it.invoicePaymentHash == hash || it.paymentId == hash || it.id == hash }
        .takeIf { it >= 0 }

private fun QuickPayLedger.pruned(
    currentDay: String,
    keepHashes: Set<String> = emptySet(),
): QuickPayLedger {
    if (currentDay.isEmpty()) return this
    return copy(
        records = records.filter { it.dayKey >= currentDay || it.invoicePaymentHash in keepHashes },
    )
}

private fun spendFor(ledger: QuickPayLedger, dayKey: String): QuickPayDaySpend = when {
    ledger.dayKey.isEmpty() || dayKey > ledger.dayKey -> QuickPayDaySpend(dayKey, 0L)
    dayKey == ledger.dayKey -> QuickPayDaySpend(dayKey, ledger.spentCents)
    else -> QuickPayDaySpend(ledger.dayKey, ledger.spentCents)
}

private fun releaseRecord(ledger: QuickPayLedger, paymentHash: String): QuickPayLedger {
    val index = ledger.recordIndex(paymentHash) ?: return ledger
    val record = ledger.records[index]
    val remaining = ledger.records.toMutableList().also { it.removeAt(index) }
    val spent = if (record.dayKey == ledger.dayKey) {
        (ledger.spentCents - record.amountCents).coerceAtLeast(0L)
    } else {
        ledger.spentCents
    }
    return ledger.copy(records = remaining, spentCents = spent)
}

private fun pickLedgerMatch(
    record: QuickPayLedgerRecord,
    rows: List<QuickPayReconcileRow>,
): QuickPayReconcileRow? {
    val matches = rows.filter { row ->
        row.isOutboundBolt11 && (
            row.invoicePaymentHash == record.invoicePaymentHash ||
                row.paymentId == record.invoicePaymentHash ||
                row.paymentId == record.paymentId ||
                (record.paymentId != null && row.invoicePaymentHash == record.paymentId)
            )
    }
    if (matches.isEmpty()) return null
    record.paymentId?.let { pid -> matches.find { it.paymentId == pid } }?.let { return it }
    return matches.maxBy {
        when (it.status) {
            QuickPayReconcileRow.Status.SUCCEEDED -> 2
            QuickPayReconcileRow.Status.PENDING -> 1
            QuickPayReconcileRow.Status.FAILED -> 0
        }
    }
}
