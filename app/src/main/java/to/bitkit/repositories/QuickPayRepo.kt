package to.bitkit.repositories

import kotlinx.coroutines.CancellationException
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
import org.lightningdevkit.ldknode.Bolt11Invoice
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

@Singleton
@Suppress("LongParameterList", "LargeClass")
class QuickPayRepo @Inject constructor(
    private val cacheStore: CacheStore,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    private val lightningRepo: LightningRepo,
    private val pendingPaymentRepo: PendingPaymentRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "QuickPayRepo"
        const val LEDGER_VERSION = 1
    }

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
        scope.launch {
            val ids = sessionFlows.keys.toList()
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
            val spend = mutex.withLock { currentSpend() }
            if (!spend.supported) return@runSuspendCatching false
            if (spend.spentCents + reserveCents <= capCents) return@runSuspendCatching true

            Logger.info(
                "Skipping QuickPay: daily spend '${spend.spentCents}' + '$reserveCents' exceeds cap '$capCents'",
                context = TAG,
            )
            false
        }
    }

    suspend fun reserveBound(
        paymentHash: String,
        amountSats: ULong,
    ): Result<QuickPayLedgerRecord?> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (paymentHash.isBlank()) return@runSuspendCatching null
            val settings = settingsStore.data.first()
            val thresholdSats = currencyRepo.convertFiatToSats(
                settings.quickPayAmount.toDouble(),
                USD,
            ).getOrNull()
            if (thresholdSats == null || thresholdSats == 0uL || amountSats > thresholdSats) {
                return@runSuspendCatching null
            }
            val converted = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrElse {
                throw QuickPayConversionError()
            }
            val amountCents = quickPayReserveCents(converted.toUsdCents(), settings.quickPayAmount, amountSats)
            val capCents = quickPayCapCents(settings.quickPayAmount, settings.quickPayDailyLimitMultiplier)
            mutex.withLock {
                var reserved: QuickPayLedgerRecord? = null
                val wrote = writeLedger { ledger, dayKey ->
                    if (ledger.recordMatching(paymentHash) != null) return@writeLedger ledger
                    val spend = spendFor(ledger, dayKey)
                    if (spend.spentCents > Long.MAX_VALUE - amountCents) return@writeLedger ledger
                    val total = spend.spentCents + amountCents
                    if (total > capCents) return@writeLedger ledger
                    var next = ledger.pruned(spend.dayKey)
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
                if (!wrote) null else reserved
            }
        }
    }

    suspend fun noteTerminal(
        paymentId: String?,
        paymentHash: String?,
        success: Boolean,
        feePaidMsat: ULong? = null,
        failureReason: PaymentFailureReason? = null,
    ): QuickPayTerminalOutcome = withContext(ioDispatcher) {
        mutex.withLock {
            noteTerminalLocked(
                paymentId = paymentId,
                paymentHash = paymentHash,
                success = success,
                feePaidMsat = feePaidMsat,
                failureReason = failureReason,
            )
        }
    }

    suspend fun reconcileAgainstLdk() {
        val rows = lightningRepo.listPaymentsOrNull()?.map { QuickPayReconcileRow(it) }
        mutex.withLock {
            val live = opsByKey.values
                .filter { !it.dispatched }
                .map { it.invoiceHash }
                .toSet()
            reconcileLocked(rows, live)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
    private suspend fun payNow(session: QuickPaySession, request: QuickPayPayRequest) {
        val invoice = resolveInvoice(session, request) ?: return
        val invoiceHash = parseInvoiceHash(invoice.bolt11)
        if (invoiceHash == null) {
            emitToSession(
                session.id,
                QuickPaySessionEvent.Error(
                    invoice.parseError ?: QuickPayConversionError(),
                    invoice.bolt11,
                ),
            )
            return
        }

        val recovered = mutex.withLock {
            val existing = opsByKey[invoiceHash]
            if (existing != null) {
                existing.sessionId = session.id
                true
            } else {
                val open = currentLedger()?.recordMatching(invoiceHash)
                if (open != null) {
                    registerOp(
                        InFlightOp(
                            invoiceHash = invoiceHash,
                            displaySats = invoice.amountSats,
                            paymentRequest = invoice.bolt11,
                            dispatched = true,
                            sessionId = session.id,
                            job = null,
                            paymentId = open.paymentId,
                        ),
                    )
                    true
                } else {
                    false
                }
            }
        }
        if (recovered) {
            reconcileAgainstLdk()
            return
        }

        val reserved = reserveBound(invoiceHash, invoice.amountSats).getOrElse {
            emitToSession(session.id, QuickPaySessionEvent.Error(it, invoice.bolt11))
            return
        }
        if (reserved == null) {
            Logger.info("Skipping QuickPay pay: daily spend reserve failed for '${invoice.amountSats}'", context = TAG)
            emitToSession(session.id, QuickPaySessionEvent.FallBackToConfirm)
            return
        }

        val op = InFlightOp(
            invoiceHash = invoiceHash,
            displaySats = invoice.amountSats,
            paymentRequest = invoice.bolt11,
            dispatched = false,
            sessionId = session.id,
            job = null,
            paymentId = null,
        )
        val cancelledBeforeDispatch = mutex.withLock {
            if (sessionFlows[session.id] == null) {
                writeLedger { ledger, _ -> releaseRecord(ledger, invoiceHash) }
                true
            } else {
                op.job = coroutineContext[Job]
                registerOp(op)
                false
            }
        }
        if (cancelledBeforeDispatch) return

        try {
            val paid = lightningRepo.payInvoice(bolt11 = invoice.bolt11, sats = null) {
                mutex.withLock {
                    val current = opsByKey[invoiceHash]
                    if (current == null || current.cancelBeforeDispatch) {
                        throw CancellationException("QuickPay cancelled before send")
                    }
                    current.dispatched = true
                }
            }
            paid.onSuccess { paymentId ->
                markSubmittedLocked(invoiceHash, paymentId)
                mutex.withLock {
                    val current = opsByKey[invoiceHash] ?: return@withLock
                    current.paymentId = paymentId
                    if (paymentId.isNotBlank() && paymentId != invoiceHash) {
                        opsByKey[paymentId] = current
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                handleDispatchError(invoiceHash, invoice.bolt11, error)
            }

            val current = mutex.withLock { opsByKey[invoiceHash] } ?: return
            withTimeoutOrNull(LightningRepo.SEND_LN_TIMEOUT) {
                current.settled.await()
            }
            mutex.withLock {
                val live = opsByKey[invoiceHash] ?: return@withLock
                if (live.settled.isCompleted || live.emitted) return@withLock
                val attachedId = live.sessionId
                if (attachedId != null) {
                    live.emitted = true
                    pendingPaymentRepo.track(invoiceHash)
                    emitToSession(
                        attachedId,
                        QuickPaySessionEvent.Pending(
                            paymentHash = invoiceHash,
                            amount = invoice.amountSats.toLong(),
                            paymentRequest = invoice.bolt11,
                        ),
                    )
                }
            }
        } catch (e: CancellationException) {
            mutex.withLock {
                val current = opsByKey[invoiceHash]
                if (current == null || current.dispatched) return@withLock
                writeLedger { ledger, _ -> releaseRecord(ledger, invoiceHash) }
                removeOpLocked(current)
            }
            throw e
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

    private fun parseInvoiceHash(bolt11: String): String? {
        return runCatching { Bolt11Invoice.fromStr(bolt11).paymentHash() }.getOrNull()
    }

    private suspend fun handleDispatchError(
        invoiceHash: String,
        paymentRequest: String,
        error: Throwable,
    ) {
        when (classifyDispatchError(error)) {
            QuickPayDispatchClass.PRE_DISPATCH_REJECTION,
            QuickPayDispatchClass.DUPLICATE_PAYMENT,
            -> {
                mutex.withLock {
                    val outcome = noteTerminalLocked(
                        paymentId = null,
                        paymentHash = invoiceHash,
                        success = false,
                    )
                    emitOutcome(outcome, invoiceHash, error, paymentRequest)
                }
            }
            QuickPayDispatchClass.AMBIGUOUS -> {
                val rows = lightningRepo.listPaymentsOrNull()?.map { QuickPayReconcileRow(it) }
                mutex.withLock {
                    val record = currentLedger()?.recordMatching(invoiceHash)
                    if (record != null && rows != null) {
                        applyAmbiguousLookupLocked(record, rows)
                    }
                    val remaining = currentLedger()?.recordMatching(invoiceHash)
                    val op = opsByKey[invoiceHash]
                    if (remaining == null) {
                        op?.let { removeOpLocked(it) }
                    } else {
                        op?.dispatched = true
                    }
                    emitToSession(
                        op?.sessionId,
                        QuickPaySessionEvent.Error(error, paymentRequest),
                    )
                    op?.emitted = true
                }
            }
        }
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
            writeLedger { ledger, _ -> releaseRecord(ledger, op.invoiceHash) }
            removeOpLocked(op)
        }
    }

    private suspend fun markSubmittedLocked(invoiceHash: String, paymentId: String) {
        mutex.withLock {
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
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun noteTerminalLocked(
        paymentId: String?,
        paymentHash: String?,
        success: Boolean,
        feePaidMsat: ULong? = null,
        failureReason: PaymentFailureReason? = null,
    ): QuickPayTerminalOutcome {
        val keys = listOfNotNull(paymentId, paymentHash).filter { it.isNotBlank() }
        if (keys.isEmpty()) return QuickPayTerminalOutcome.None

        val snapshot = currentSpend()
        if (!snapshot.supported) return QuickPayTerminalOutcome.None
        val ledger = snapshot.ledger ?: return QuickPayTerminalOutcome.None
        val index = keys.firstNotNullOfOrNull { ledger.recordIndex(it) } ?: return QuickPayTerminalOutcome.None
        val record = ledger.records[index]
        val op = opsByKey[record.invoicePaymentHash] ?: record.paymentId?.let { opsByKey[it] }
        if (!success && !isAttributedFailure(record, op, paymentId, paymentHash)) {
            return QuickPayTerminalOutcome.None
        }

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

        val kind = if (success) {
            QuickPayTerminalKind.SETTLED_SUCCESS
        } else {
            QuickPayTerminalKind.SETTLED_FAILURE
        }
        val outcome = QuickPayTerminalOutcome(
            kind = kind,
            invoicePaymentHash = record.invoicePaymentHash,
        )
        if (op != null && !op.emitted) {
            op.emitted = true
            val event = if (success) {
                val feeSats = msatFloorOf(feePaidMsat ?: 0u)
                QuickPaySessionEvent.Success(
                    paymentHash = record.invoicePaymentHash,
                    amountWithFee = (op.displaySats.safe() + feeSats.safe()).toLong(),
                )
            } else {
                QuickPaySessionEvent.Error(
                    QuickPayPaymentFailedError(
                        paymentHash = record.invoicePaymentHash,
                        reason = failureReason,
                        paymentRequest = op.paymentRequest,
                    ),
                    op.paymentRequest,
                )
            }
            emitToSession(op.sessionId, event)
            op.settled.complete(Unit)
            removeOpLocked(op)
        } else {
            op?.settled?.complete(Unit)
            op?.let { removeOpLocked(it) }
        }
        return outcome
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
    ) {
        val match = pickMatch(record, rows) ?: return
        when (match.status) {
            QuickPayReconcileRow.Status.PENDING -> Unit
            QuickPayReconcileRow.Status.SUCCEEDED -> {
                writeLedger { ledger, _ ->
                    val index = ledger.recordIndex(record.invoicePaymentHash) ?: return@writeLedger ledger
                    ledger.copy(records = ledger.records.toMutableList().also { it.removeAt(index) })
                }
            }
            QuickPayReconcileRow.Status.FAILED -> {
                val attributed = isAttributedFailure(
                    record,
                    opsByKey[record.invoicePaymentHash],
                    match.paymentId,
                    match.invoicePaymentHash,
                )
                if (!attributed) {
                    return
                }
                writeLedger { ledger, _ -> releaseRecord(ledger, record.invoicePaymentHash) }
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun reconcileLocked(
        rows: List<QuickPayReconcileRow>?,
        liveSubmittingHashes: Set<String>,
    ) {
        if (rows == null) return
        writeLedger { ledger, dayKey ->
            var next = ledger.pruned(dayKey)
            val remaining = mutableListOf<QuickPayLedgerRecord>()
            var spent = next.spentCents
            for (record in next.records) {
                if (liveSubmittingHashes.contains(record.invoicePaymentHash)) {
                    remaining.add(record)
                    continue
                }
                val match = pickMatch(record, rows)
                if (match == null) {
                    remaining.add(record)
                    continue
                }
                when (match.status) {
                    QuickPayReconcileRow.Status.PENDING -> remaining.add(record)
                    QuickPayReconcileRow.Status.SUCCEEDED -> Unit
                    QuickPayReconcileRow.Status.FAILED -> {
                        val op = opsByKey[record.invoicePaymentHash]
                        if (!isAttributedFailure(record, op, match.paymentId, match.invoicePaymentHash)) {
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

    private fun pickMatch(
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

    private suspend fun currentSpend(): SpendSnapshot {
        val data = cacheStore.data.first()
        val (ledger, supported) = data.resolvedLedger()
        val dayKey = currentDayKey()
        if (!supported) return SpendSnapshot(dayKey, 0L, supported = false, ledger = ledger)
        val spend = spendFor(ledger, dayKey)
        return SpendSnapshot(spend.dayKey, spend.spentCents, supported = true, ledger = ledger)
    }

    private suspend fun currentLedger(): QuickPayLedger? {
        val (ledger, supported) = cacheStore.data.first().resolvedLedger()
        return ledger.takeIf { supported }
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

    private fun registerOp(op: InFlightOp) {
        opsByKey[op.invoiceHash] = op
        op.paymentId?.takeIf { it.isNotBlank() && it != op.invoiceHash }?.let { opsByKey[it] = op }
    }

    private fun removeOpLocked(op: InFlightOp) {
        opsByKey.entries.removeAll { it.value === op }
    }

    private fun emitOutcome(
        outcome: QuickPayTerminalOutcome,
        invoiceHash: String,
        error: Throwable,
        paymentRequest: String,
    ) {
        val op = opsByKey[invoiceHash]
        if (op != null && !op.emitted) {
            op.emitted = true
            emitToSession(op.sessionId, QuickPaySessionEvent.Error(error, paymentRequest))
            op.settled.complete(Unit)
        }
        op?.let { removeOpLocked(it) }
        if (outcome == QuickPayTerminalOutcome.None) {
            emitToSession(op?.sessionId, QuickPaySessionEvent.Error(error, paymentRequest))
        }
    }

    private fun emitToSession(sessionId: String?, event: QuickPaySessionEvent) {
        if (sessionId == null) return
        sessionFlows[sessionId]?.tryEmit(event)
    }

    private fun currentDayKey(): String =
        clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

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

    private data class SpendSnapshot(
        val dayKey: String,
        val spentCents: Long,
        val supported: Boolean,
        val ledger: QuickPayLedger?,
    )
}

internal fun classifyDispatchError(error: Throwable): QuickPayDispatchClass {
    if (PrivatePaykitErrorClassifier.isDuplicatePaymentError(error)) {
        return QuickPayDispatchClass.DUPLICATE_PAYMENT
    }
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

data class QuickPaySession(val id: String = UUID.randomUUID().toString())

sealed interface QuickPayPayRequest {
    val amountSats: ULong

    data class Bolt11(
        val bolt11: String,
        override val amountSats: ULong,
    ) : QuickPayPayRequest

    data class LnurlPay(
        val data: com.synonym.bitkitcore.LnurlPayData,
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

enum class QuickPayTerminalKind {
    NONE,
    SETTLED_SUCCESS,
    SETTLED_FAILURE,
}

data class QuickPayTerminalOutcome(
    val kind: QuickPayTerminalKind = QuickPayTerminalKind.NONE,
    val invoicePaymentHash: String? = null,
) {
    val wasQuickPay: Boolean get() = kind != QuickPayTerminalKind.NONE

    companion object {
        val None = QuickPayTerminalOutcome()
    }
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

class QuickPayConversionError : AppError("Currency conversion failed")

class QuickPayPaymentFailedError(
    val paymentHash: String,
    val reason: PaymentFailureReason?,
    val paymentRequest: String?,
) : AppError(reason?.name)

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
        return ledger to (ledger.version == QuickPayRepo.LEDGER_VERSION)
    }
    return QuickPayLedger(
        version = QuickPayRepo.LEDGER_VERSION,
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

private fun QuickPayLedger.pruned(currentDay: String): QuickPayLedger {
    if (currentDay.isEmpty()) return this
    return copy(records = records.filter { it.dayKey >= currentDay })
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
