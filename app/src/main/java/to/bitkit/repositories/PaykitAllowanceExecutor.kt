package to.bitkit.repositories

import com.synonym.paykit.AccountingAmount
import com.synonym.paykit.AllowanceAccountingBlock
import com.synonym.paykit.AllowanceAccountingHistory
import com.synonym.paykit.AllowanceAccountingReconciliation
import com.synonym.paykit.AllowanceAccountingState
import com.synonym.paykit.AllowanceLifecycleState
import com.synonym.paykit.AllowanceSelectionInput
import com.synonym.paykit.PaymentAttemptDecision
import com.synonym.paykit.PaymentAttemptRecord
import com.synonym.paykit.PaymentExecutionChecks
import com.synonym.paykit.PaymentExecutionMode
import com.synonym.paykit.PaymentExecutionStatus
import com.synonym.paykit.PaymentOccurrence
import com.synonym.paykit.PaymentOutcome
import com.synonym.paykit.PaymentOutcomeReport
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.safe
import to.bitkit.repositories.PaykitAllowanceLocalState.JournalEntry
import to.bitkit.repositories.PaykitAllowanceLocalState.Stage
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The side effects of paying one request, kept apart so the admission logic is testable without a node. */
@Singleton
@Suppress("TooManyFunctions")
class PaykitAllowancePayer @Inject constructor(
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val paymentProofRepo: PaykitPaymentProofRepo,
    private val lightningRepo: LightningRepo,
    private val paymentRequestRepo: PaykitPaymentRequestRepo,
) {
    suspend fun resolve(
        request: PaykitPaymentRequest,
        eligibleIdentifiers: List<String>,
    ): Result<PrivatePaykitAllowancePayment?> = privatePaykitRepo.resolveAllowancePayment(request, eligibleIdentifiers)

    suspend fun consumePaymentList(
        publicKey: String,
        context: PrivatePaykitPaymentContext,
        attemptId: String,
    ): Result<Unit> = privatePaykitRepo.consumePrivatePaymentList(publicKey, context, owner = attemptId)

    /** Gives back a payment list the attempt consumed, so the request's manual fallback can still use it. */
    suspend fun releasePaymentList(
        publicKey: String,
        context: PrivatePaykitPaymentContext,
        attemptId: String,
    ): Result<Unit> = privatePaykitRepo.releasePrivatePaymentList(publicKey, context, owner = attemptId)

    /** Accepts through [accept] with this install as the request's owner, so a failed payment stays payable here. */
    suspend fun <T> acceptOnThisInstall(request: PaykitPaymentRequest, accept: suspend () -> T): Result<T> =
        paymentRequestRepo.acceptOnThisInstall(request, accept)

    suspend fun prepareProof(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        paymentAppId: String,
        allowanceId: String?,
    ): Result<Unit> {
        val kind = PaykitPaymentProofKind.fromPaymentEndpointIdentifier(paymentEndpointIdentifier)
            ?: return Result.failure(PaykitPaymentRequestError.RequestUnavailable)
        return paymentProofRepo.prepare(
            request,
            paymentEndpointIdentifier,
            paymentAppId,
            kind,
            allowanceId = allowanceId
        )
    }

    suspend fun associateLightningPayment(
        request: PaykitPaymentRequest,
        paymentHash: String,
        paymentEndpointIdentifier: String,
        paymentAppId: String,
    ): Result<Unit> =
        paymentProofRepo.associateLightningPayment(request, paymentHash, paymentEndpointIdentifier, paymentAppId)

    suspend fun payLightning(
        bolt11: String,
        sats: ULong?,
        paymentDeadlineAt: Instant?,
        maxTotalRoutingFeeMsat: ULong,
    ): Result<String> = lightningRepo.payInvoice(bolt11, sats, paymentDeadlineAt, maxTotalRoutingFeeMsat) { true }

    /** Clears the proof when [error] proves the payment never left; returns whether it did. */
    suspend fun failLightningPayment(paymentHash: String, error: Throwable): Boolean =
        paymentProofRepo.failLightningPayment(paymentHash, error)

    suspend fun discardLightningProof(paymentHash: String) = paymentProofRepo.failLightningPayment(paymentHash)

    suspend fun cancelProofPreparation(request: PaykitPaymentRequest) = paymentProofRepo.cancelPreparation(request)

    /**
     * SUCCEEDED once the wallet holds positive evidence that [txid] paid [requestId], else null: a refused or unknown
     * broadcast stays unresolved until the wallet's recovery of the send attempt settles it.
     */
    suspend fun onchainPaymentOutcome(requestId: PaykitPaymentRequestId, txid: String): PaymentOutcome? {
        val attempt = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
        val isAttemptAccepted = attempt != null && attempt.requestId == requestId && attempt.hasPositiveEvidence &&
            (attempt.txid.equals(txid, ignoreCase = true) || txid.lowercase() in attempt.candidateTxids)
        if (isAttemptAccepted) return PaymentOutcome.SUCCEEDED
        val hasProof = runSuspendCatching {
            paymentProofRepo.hasVerifiedOnchainProof(
                requestId,
                txid
            )
        }.getOrDefault(false)
        return PaymentOutcome.SUCCEEDED.takeIf { hasProof }
    }

    /** True once the node can list its payments, so a payment missing from the list is one it never sent. */
    suspend fun canListLightningPayments(): Boolean =
        runSuspendCatching { lightningRepo.listPaymentsOrNull() }.getOrNull() != null

    /** The node's status for an outbound payment, or null when the node does not know it (or is not running). */
    suspend fun lightningPaymentStatus(paymentHash: String): PaymentStatus? =
        runSuspendCatching { lightningRepo.listPaymentsOrNull() }
            .getOrNull()
            ?.firstOrNull { it.direction == PaymentDirection.OUTBOUND && it.id.equals(paymentHash, ignoreCase = true) }
            ?.status
}

/**
 * Runs Allowance admission for incoming requests through the SDK: evaluate, capacity preflight, automatic
 * Acceptance, reserve, begin, pay, record the outcome. Every attempt is journaled before its handoff, so a restart
 * resolves it from the node instead of paying again.
 */
@Singleton
@Suppress("TooManyFunctions")
class PaykitAllowanceExecutor @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val store: PaykitAllowanceStore,
    private val payer: PaykitAllowancePayer,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PaykitAllowanceExecutor"
        private const val MAX_JOURNAL_ENTRIES = 200

        /** 1% of each paid sat (1 000 msat) may go to routing fees on an automatic payment. */
        private const val MAX_ROUTING_FEE_MSAT_PER_SAT = 10uL
        private val SETTLEABLE_STAGES = setOf(Stage.SUBMITTED, Stage.SENDING, Stage.SENT, Stage.UNKNOWN)

        /** Spends kept for the monthly limit: two of the longest windows. */
        private val SPEND_RETENTION = 62.days
        private val ENDED_STATES = setOf(AllowanceLifecycleState.ENDED, AllowanceLifecycleState.REJECTED)
    }

    private var accountingAmount: (String, String) -> AccountingAmount = ::AccountingAmount
    private val stateMutex = Mutex()
    private val settleMutex = Mutex()
    private val inFlightRequestIds = ConcurrentHashMap.newKeySet<PaykitPaymentRequestId>()
    private val liveAttemptIds = ConcurrentHashMap.newKeySet<String>()
    private val _events = MutableSharedFlow<PaykitAllowanceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PaykitAllowanceEvent> = _events.asSharedFlow()

    @Volatile
    var activeIdentity: String? = null
        private set

    internal constructor(
        ioDispatcher: CoroutineDispatcher,
        paykitSdkService: PaykitSdkService,
        store: PaykitAllowanceStore,
        payer: PaykitAllowancePayer,
        clock: Clock,
        accountingAmount: (String, String) -> AccountingAmount,
    ) : this(ioDispatcher, paykitSdkService, store, payer, clock) {
        this.accountingAmount = accountingAmount
    }

    fun activate(identity: String?) = run { activeIdentity = identity }

    // region Local state

    fun localState(identity: String): PaykitAllowanceLocalState = store.load(identity)

    suspend fun updateLocalState(
        identity: String,
        change: (PaykitAllowanceLocalState) -> PaykitAllowanceLocalState,
    ): PaykitAllowanceLocalState = stateMutex.withLock {
        val updated = change(localState(identity))
        runSuspendCatching { store.save(identity, updated) }
            .onFailure { Logger.warn("Failed to save Paykit allowance state", it, context = TAG) }
        updated
    }

    /** Like [updateLocalState], but a failed save throws instead of being logged. */
    suspend fun updateLocalStateDurably(
        identity: String,
        change: (PaykitAllowanceLocalState) -> PaykitAllowanceLocalState,
    ): PaykitAllowanceLocalState = stateMutex.withLock {
        change(localState(identity)).also { store.save(identity, it) }
    }

    fun isHandling(requestId: PaykitPaymentRequestId): Boolean = requestId in inFlightRequestIds

    /**
     * Trusted time for eligibility: the real clock, never earlier than the last value given to the SDK, because the
     * SDK refuses a watermark that moves backwards.
     */
    suspend fun trustedTime(identity: String): String {
        var millis = clock.now().toEpochMilliseconds()
        updateLocalState(identity) { state ->
            state.lastTrustedTimeMillis?.let { millis = maxOf(millis, it) }
            state.copy(lastTrustedTimeMillis = millis)
        }
        return PaykitAllowanceTime.format(Instant.fromEpochMilliseconds(millis))
    }

    /**
     * Whether [paymentHash] is an automatic Allowance payment, so no foreground send flow should claim it. The newest
     * entry for the hash decides: a manual retry of a failed automatic invoice is journaled later and is the user's.
     */
    fun isAutomaticPayment(paymentHash: String): Boolean {
        val identity = activeIdentity ?: return false
        return localState(identity).journal
            .lastOrNull { it.paymentHash.equals(paymentHash, ignoreCase = true) }
            ?.isAutomatic == true
    }

    fun succeededAutomaticPayments(identity: String): List<JournalEntry> =
        localState(identity).journal.filter { it.isAutomatic && it.stage == Stage.SUCCEEDED }

    /** Remembers every ended or declined allowance in [allowances], so it never pays automatically again. */
    suspend fun rememberEndedAllowances(identity: String, allowances: List<PaykitAllowance>) {
        val ended = allowances
            .filter { it.lifecycleState in ENDED_STATES }
            .map { it.key }
            .toSet()
        if (ended.isEmpty() || localState(identity).endedAllowanceIds.containsAll(ended)) return
        updateLocalState(identity) { it.copy(endedAllowanceIds = it.endedAllowanceIds + ended) }
    }

    /** Like [rememberEndedAllowances], before the End is sent; a failed save throws, so the End is not sent. */
    suspend fun rememberEnding(identity: String, allowances: List<PaykitAllowance>) {
        updateLocalStateDurably(identity) { state ->
            state.copy(endedAllowanceIds = state.endedAllowanceIds + allowances.map { it.key })
        }
    }

    /**
     * False when [ledger] is older than one this wallet has read, or missing after one was read; otherwise raises the
     * floor to it.
     */
    private suspend fun acceptsLedger(ledger: AllowanceAccountingState?, identity: String): Boolean {
        val floor = localState(identity).ledgerRevisionFloor
        if (ledger == null) return floor == null
        if (floor != null && ledger.revision <= floor) return ledger.revision == floor
        updateLocalState(identity) { state ->
            state.copy(ledgerRevisionFloor = maxOf(state.ledgerRevisionFloor ?: 0uL, ledger.revision))
        }
        return true
    }

    // endregion

    // region Ledger

    /**
     * Brings the SDK ledger to a reconciled state. A wallet with no ledger attests an empty history: it has never paid
     * through an Allowance. After a restore, outcomes come from the journal and the node.
     */
    suspend fun ensureReconciled(identity: String): AllowanceAccountingState = withContext(ioDispatcher) {
        val current = paykitSdkService.allowanceAccountingState()
        if (current != null && !current.requiresReconciliation) return@withContext current

        val history = current?.history ?: AllowanceAccountingHistory(emptyList(), emptyList(), emptyList())
        val journal = localState(identity).journal.associateBy { it.attemptId }
        val outcomes = history.occurrences.flatMap { it.attempts }.mapNotNull { attempt ->
            verifiedOutcome(attempt, journal)?.let { PaymentOutcomeReport(attempt.attemptId, it) }
        }
        val reconciled = paykitSdkService.reconcileAllowanceAccounting(
            AllowanceAccountingReconciliation(
                expectedRevision = current?.revision,
                history = history,
                outcomes = outcomes,
                trustedTime = trustedTime(identity),
            ),
        )
        Logger.info("Reconciled Paykit allowance accounting with '${outcomes.size}' verified outcomes", context = TAG)
        reconciled
    }

    /**
     * Resolves attempts a crash or kill left open. Prepared attempts never got a handoff and are released; submitted
     * ones are settled from the journal and the node. Nothing is paid again, and attempts this process is still
     * executing are left alone.
     */
    suspend fun recover(identity: String) {
        withContext(ioDispatcher) { recoverOpenAttempts(identity) }
    }

    private suspend fun recoverOpenAttempts(identity: String) {
        runSuspendCatching {
            // No ledger means nothing was ever admitted, so there is nothing to recover.
            val current = paykitSdkService.allowanceAccountingState() ?: return@runSuspendCatching
            // A ledger behind this wallet stays as it is until it catches up; recovery would write over it.
            if (!acceptsLedger(current, identity)) {
                Logger.info("Skipped allowance recovery: the ledger is behind this wallet", context = TAG)
                return@runSuspendCatching
            }
            val state = ensureReconciled(identity)
            if (!acceptsLedger(state, identity)) return@runSuspendCatching
            // The ledger keeps every attempt, while the journal is trimmed and an iOS backup carries no totals.
            updateLocalState(identity) { it.withLedgerAutoPaid(state.history) }
            // One read of the journal per pass: each attempt below is settled from this snapshot.
            val journal = localState(identity).journal.associateBy { it.attemptId }
            for (attempt in state.history.occurrences.flatMap { it.attempts }) {
                if (attempt.attemptId in liveAttemptIds) continue
                when (attempt.status) {
                    PaymentExecutionStatus.PREPARED -> {
                        if (attempt.epoch == state.epoch) record(attempt.attemptId, PaymentOutcome.FAILED, identity)
                    }
                    PaymentExecutionStatus.SUBMITTED, PaymentExecutionStatus.UNKNOWN -> {
                        val entry = journal[attempt.attemptId]
                        val outcome = if (entry != null && awaitsNode(entry)) {
                            null
                        } else {
                            entry?.let { journalOutcome(it) }
                                ?: PaymentOutcome.UNKNOWN.takeIf { attempt.status == PaymentExecutionStatus.SUBMITTED }
                        }
                        outcome?.let { record(attempt.attemptId, it, identity) }
                        // One that paid nothing gives the payee's list back, as a live failure does.
                        if (outcome == PaymentOutcome.FAILED && entry?.isAutomatic == true) {
                            releasePaymentList(entry, identity)
                        }
                    }
                    // Reconciliation can settle an attempt in the ledger before its journal entry follows.
                    PaymentExecutionStatus.SUCCEEDED -> syncSettledJournal(attempt.attemptId, Stage.SUCCEEDED, identity)
                    PaymentExecutionStatus.FAILED -> syncSettledJournal(attempt.attemptId, Stage.FAILED, identity)
                }
            }
        }.onFailure { Logger.warn("Failed to recover Paykit allowance attempts", it, context = TAG) }
    }

    private suspend fun syncSettledJournal(attemptId: String, stage: Stage, identity: String) {
        val current = localState(identity).journal.firstOrNull { it.attemptId == attemptId } ?: return
        if (current.stage == Stage.SUCCEEDED) return
        if (current.stage == Stage.FAILED) {
            // A failed automatic attempt still holding its list could not save the cleared claim; give it back now.
            if (stage == Stage.FAILED && current.isAutomatic && current.paymentListVersion != null) {
                releasePaymentList(current, identity)
            }
            return
        }
        val before = applyJournalStage(attemptId, stage, identity) ?: return
        if (stage == Stage.FAILED && before.isAutomatic) releasePaymentList(before, identity)
    }

    /**
     * True when [request] fits no active Allowance of its contact, checked locally while the node cannot pay yet;
     * such a request asks at once instead of waiting for channels, and raises Limit Reached.
     */
    suspend fun classifyAboveLimit(
        request: PaykitPaymentRequest,
        allowances: List<PaykitAllowance>,
        identity: String,
    ): Boolean = withContext(ioDispatcher) {
        val amountSats = request.allowanceSats() ?: return@withContext false
        val now = clock.now()
        val matching = allowances.filter {
            it.isAllower && it.status(now) == PaykitAllowance.Status.ACTIVE &&
                PubkyPublicKeyFormat.matches(it.counterparty, request.counterparty)
        }
        if (matching.isEmpty()) return@withContext false
        val attempts = automaticAttempts()
        val localAttempts = PaykitAllowanceCapacity.attempts(localState(identity).automaticSpends)
        if (matching.any { PaykitAllowanceCapacity.fits(amountSats, it, attempts, now, localAttempts) }) {
            return@withContext false
        }
        notifyLimitReached(request, identity)
        true
    }

    /** Settles journaled Lightning attempts whose outcome the node already knows, for events missed while inactive. */
    suspend fun settleOpenLightningAttempts(): Unit = withContext(ioDispatcher) {
        val identity = activeIdentity ?: return@withContext
        val paymentHashes = localState(identity).journal
            .filter { it.stage in SETTLEABLE_STAGES }
            .mapNotNull { it.paymentHash }
            .distinct()
        for (paymentHash in paymentHashes) {
            when (payer.lightningPaymentStatus(paymentHash)) {
                PaymentStatus.SUCCEEDED -> lightningPaymentSettled(paymentHash, succeeded = true)
                PaymentStatus.FAILED -> lightningPaymentSettled(paymentHash, succeeded = false)
                PaymentStatus.PENDING, null -> Unit
            }
        }
    }

    private suspend fun verifiedOutcome(
        attempt: PaymentAttemptRecord,
        journal: Map<String, JournalEntry>,
    ): PaymentOutcome? = when (attempt.status) {
        PaymentExecutionStatus.PREPARED -> PaymentOutcome.FAILED
        PaymentExecutionStatus.SUCCEEDED, PaymentExecutionStatus.FAILED -> null
        PaymentExecutionStatus.SUBMITTED, PaymentExecutionStatus.UNKNOWN -> journal[attempt.attemptId]
            ?.let { journalOutcome(it) }
    }

    private suspend fun journalOutcome(entry: JournalEntry): PaymentOutcome? = when (entry.stage) {
        Stage.FAILED -> PaymentOutcome.FAILED
        // The live journal is written before the node call, but a restored snapshot may predate the send,
        // so a node that knows the payment decides.
        Stage.PREPARED, Stage.SUBMITTED -> if (awaitsNode(entry)) {
            null
        } else {
            when (entry.paymentHash?.let { payer.lightningPaymentStatus(it) }) {
                PaymentStatus.SUCCEEDED -> PaymentOutcome.SUCCEEDED
                PaymentStatus.PENDING -> null
                PaymentStatus.FAILED, null -> PaymentOutcome.FAILED
            }
        }
        Stage.SUCCEEDED -> PaymentOutcome.SUCCEEDED
        Stage.SENDING, Stage.SENT, Stage.UNKNOWN -> {
            val paymentHash = entry.paymentHash
            if (paymentHash == null) {
                entry.transactionId?.let { payer.onchainPaymentOutcome(entry.requestId, it) }
            } else {
                when (payer.lightningPaymentStatus(paymentHash)) {
                    PaymentStatus.SUCCEEDED -> PaymentOutcome.SUCCEEDED
                    PaymentStatus.FAILED -> PaymentOutcome.FAILED
                    PaymentStatus.PENDING, null -> null
                }
            }
        }
    }

    // Before the node runs (a restore starts Pubky first) its empty list would read as never sent.
    private suspend fun awaitsNode(entry: JournalEntry): Boolean =
        (entry.stage == Stage.PREPARED || entry.stage == Stage.SUBMITTED) &&
            entry.paymentHash != null &&
            !payer.canListLightningPayments()

    private suspend fun record(attemptId: String, outcome: PaymentOutcome, identity: String) {
        paykitSdkService.recordPaymentOutcome(PaymentOutcomeReport(attemptId, outcome))
        val stage = when (outcome) {
            PaymentOutcome.SUCCEEDED -> Stage.SUCCEEDED
            PaymentOutcome.FAILED -> Stage.FAILED
            PaymentOutcome.UNKNOWN -> Stage.UNKNOWN
        }
        applyJournalStage(attemptId, stage, identity)
        _events.tryEmit(PaykitAllowanceEvent.LedgerChanged)
    }

    /**
     * Moves an attempt's journal entry to [stage]. The one place that announces an automatic payment as paid: only the
     * update that moves it to SUCCEEDED does, so settlement and recovery never announce it twice.
     */
    private suspend fun applyJournalStage(attemptId: String, stage: Stage, identity: String): JournalEntry? {
        var before: JournalEntry? = null
        updateLocalState(identity) { state ->
            val current = state.journal.firstOrNull { it.attemptId == attemptId }
            before = current
            val updated = state.copy(
                journal = state.journal.map { if (it.attemptId == attemptId) it.copy(stage = stage) else it },
                // A failed attempt paid nothing, so it no longer counts against its limit.
                automaticSpends = if (stage == Stage.FAILED) {
                    state.automaticSpends.filterNot { it.attemptId == attemptId }
                } else {
                    state.automaticSpends
                },
            )
            if (current?.isPaidAutomaticallyBy(stage) == true) {
                updated.withAutoPaid(current.requestId, current.allowanceId, current.amountSats)
            } else {
                updated
            }
        }
        val entry = before ?: return null
        if (entry.isPaidAutomaticallyBy(stage)) {
            val paymentId = entry.paymentHash?.lowercase() ?: entry.transactionId
            if (paymentId != null) {
                _events.tryEmit(
                    PaykitAllowanceEvent.PaidAutomatically(entry.requestId.counterparty, entry.amountSats, paymentId),
                )
            }
        }
        return entry
    }

    private suspend fun automaticAttempts(): List<PaykitAllowanceCapacity.Attempt> =
        runSuspendCatching { paykitSdkService.allowanceAccountingState() }
            .getOrNull()
            ?.let { PaykitAllowanceCapacity.attempts(it.history) }
            .orEmpty()

    // endregion

    // region Automatic payment

    suspend fun autoPay(
        request: PaykitPaymentRequest,
        allowances: List<PaykitAllowance>,
        identity: String,
    ): PaykitAllowanceAutoPayResult = withContext(ioDispatcher) {
        val isCovered = request.direction == PaykitPaymentRequestDirection.Incoming &&
            request.billingPeriod == null &&
            request.lifecycleState == PaymentRequestLifecycleState.PROPOSED &&
            allowances.any {
                it.isAllower &&
                    it.lifecycleState == AllowanceLifecycleState.ACCEPTED &&
                    PubkyPublicKeyFormat.matches(it.counterparty, request.counterparty)
            }
        if (!isCovered || !inFlightRequestIds.add(request.id)) {
            return@withContext PaykitAllowanceAutoPayResult.NOT_COVERED
        }

        try {
            runSuspendCatching {
                val isLedgerCurrent = acceptsLedger(paykitSdkService.allowanceAccountingState(), identity) &&
                    acceptsLedger(ensureReconciled(identity), identity)
                if (!isLedgerCurrent) {
                    Logger.info("Kept an incoming request manual: the ledger is behind this wallet", context = TAG)
                    return@runSuspendCatching PaykitAllowanceAutoPayResult.MANUAL
                }
                // Survive a cancelled caller: stopping after the acceptance would leave the request unpaid.
                withContext(NonCancellable) { admitAndPay(request, allowances, identity) }
            }.getOrElse {
                Logger.warn("Kept an incoming request on the manual flow after an allowance error", it, context = TAG)
                PaykitAllowanceAutoPayResult.MANUAL
            }
        } finally {
            inFlightRequestIds.remove(request.id)
        }
    }

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun admitAndPay(
        request: PaykitPaymentRequest,
        allowances: List<PaykitAllowance>,
        identity: String,
    ): PaykitAllowanceAutoPayResult {
        val amountSats = request.allowanceSats()
        if (amountSats == null) {
            Logger.info("Kept an incoming request manual: allowances pay only bitcoin amounts", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }
        val scope = request.scope()
        val selectionTime = trustedTime(identity)
        val candidates = paykitSdkService.evaluateAllowanceCandidates(scope, selectionTime)
        val local = localState(identity)
        val unblocked = candidates.filter { it.blocked == null }
        val candidate = unblocked.firstOrNull {
            PaykitAllowance.key(request.counterparty, it.allowanceId) !in local.endedAllowanceIds
        }
        if (candidate == null && unblocked.isNotEmpty()) {
            Logger.info("Kept an incoming request manual: its allowance has ended", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }
        val allowance = candidate?.let { eligible ->
            allowances.firstOrNull {
                it.allowanceId == eligible.allowanceId &&
                    PubkyPublicKeyFormat.matches(it.counterparty, request.counterparty)
            }
        }
        if (candidate == null || allowance == null) {
            val reasons = candidates.mapNotNull { it.blocked }
            Logger.info("Kept an incoming request manual: no eligible allowance, blocked by '$reasons'", context = TAG)
            // The SDK blocks a request above the per-payment limit before the capacity check below can notify.
            val now = clock.now()
            val isAbovePerPaymentMax = allowances.any { granted ->
                granted.isAllower && granted.status(now) == PaykitAllowance.Status.ACTIVE &&
                    PubkyPublicKeyFormat.matches(granted.counterparty, request.counterparty) &&
                    granted.perPaymentMaxSats?.let { amountSats > it } == true
            }
            if (isAbovePerPaymentMax) notifyLimitReached(request, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        val ledger = paykitSdkService.allowanceAccountingState()
        if (!acceptsLedger(ledger, identity)) {
            Logger.info("Kept an incoming request manual: the ledger is behind this wallet", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }
        val attempts = ledger?.let { PaykitAllowanceCapacity.attempts(it.history) }.orEmpty()
        val localAttempts = PaykitAllowanceCapacity.attempts(local.automaticSpends)
        if (!PaykitAllowanceCapacity.fits(amountSats, allowance, attempts, clock.now(), localAttempts)) {
            Logger.info("Kept an incoming request manual: the allowance limit is reached", context = TAG)
            notifyLimitReached(request, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        val payment = payer.resolve(request, candidate.eligiblePaymentEndpointIdentifiers).getOrElse {
            if (it !is PaykitAllowanceError.PaymentListPending) throw it
            Logger.info("Deferred an incoming request until the payee publishes a new payment list", context = TAG)
            return PaykitAllowanceAutoPayResult.DEFERRED
        }
        if (payment == null) {
            Logger.info("Kept an incoming request manual: no payable private endpoint", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }
        if (payment.lightningPaymentHash == null) {
            // An on-chain fee falls outside every allowance limit, so only Lightning pays automatically.
            Logger.info("Kept an incoming request manual: the payee offers no Lightning invoice", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        val endpointIdentifier = payment.endpoint.methodId.rawValue
        val association = payer.acceptOnThisInstall(request) {
            // Every identity-wide execution step needs this app to own the request's execution claim first.
            paykitSdkService.claimPaymentRequestForExecution(request.counterparty, request.paymentRequestId)
            paykitSdkService.acceptPaymentRequestAutomatically(
                scope = scope,
                selection = AllowanceSelectionInput(
                    allowanceId = candidate.allowanceId,
                    expectedRevision = null,
                    trustedTime = selectionTime,
                ),
                checks = checks(request, endpointIdentifier, selectionTime),
            )
        }.getOrThrow()
        sendQueuedMessages(request)

        val occurrence = PaymentOccurrence(scope, billingPeriod = null)
        val reservation = paykitSdkService.reserveAutomaticPayment(
            occurrence = occurrence,
            expectedAssociationRevision = association.revisions.lastOrNull()?.revision ?: 1uL,
            checks = checks(request, endpointIdentifier, trustedTime(identity)),
        )
        val prepared = (reservation as? PaymentAttemptDecision.Ready)?.attempt
        if (prepared == null) {
            val reason = (reservation as? PaymentAttemptDecision.Blocked)?.reason
            Logger.info("Kept an incoming request manual: the reservation is blocked by '$reason'", context = TAG)
            runSuspendCatching { paykitSdkService.markPaymentManualOnly(occurrence) }
                .onFailure { Logger.warn("Failed to mark an allowance payment manual only", it, context = TAG) }
            // Only a capacity block means the request is above the allowance; other blocks just keep it manual.
            if (reason.isCapacityBlock()) notifyLimitReached(request, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        liveAttemptIds.add(prepared.attemptId)
        try {
            return executeAutomaticAttempt(request, amountSats, payment, prepared, identity)
        } finally {
            liveAttemptIds.remove(prepared.attemptId)
        }
    }

    private suspend fun executeAutomaticAttempt(
        request: PaykitPaymentRequest,
        amountSats: ULong,
        payment: PrivatePaykitAllowancePayment,
        prepared: PaymentAttemptRecord,
        identity: String,
    ): PaykitAllowanceAutoPayResult {
        val endpointIdentifier = payment.endpoint.methodId.rawValue
        runSuspendCatching {
            journal(
                JournalEntry(
                    attemptId = prepared.attemptId,
                    isAutomatic = true,
                    requestId = request.id,
                    allowanceId = prepared.allowanceId,
                    amountSats = amountSats,
                    paymentEndpointIdentifier = endpointIdentifier,
                    paymentHash = payment.lightningPaymentHash,
                    onchainAddress = payment.endpoint.value.takeIf { payment.endpoint.methodId.isOnchain },
                    stage = Stage.PREPARED,
                    createdAtMillis = clock.now().toEpochMilliseconds(),
                    paymentListVersion = payment.context.paymentListVersion,
                    previousPaymentListVersion = payment.context.previousPaymentListVersion,
                ),
                identity,
                PaykitAllowanceLocalState.AutomaticSpend(
                    attemptId = prepared.attemptId,
                    counterparty = request.counterparty,
                    allowanceId = checkNotNull(prepared.allowanceId) { "An automatic attempt names its allowance" },
                    amountSats = amountSats,
                    admittedAtMillis = (PaykitAllowanceTime.parse(prepared.admittedAt) ?: clock.now())
                        .toEpochMilliseconds(),
                ),
            )
        }.onFailure {
            record(prepared.attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }

        // An End saved while this attempt was admitted stops it before the handoff.
        if (PaykitAllowance.key(request.counterparty, prepared.allowanceId) in localState(identity).endedAllowanceIds) {
            Logger.info("Kept an incoming request manual: its allowance has ended", context = TAG)
            record(prepared.attemptId, PaymentOutcome.FAILED, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        // Begin fetches nothing, so pull the link first: an End or a cancellation must be seen before the handoff.
        runSuspendCatching {
            paykitSdkService.receivePrivateMessages(request.counterparty)
        }.onFailure { Logger.warn("Failed to receive private messages before an allowance payment", it, context = TAG) }
        val handoff = runSuspendCatching {
            paykitSdkService.beginPaymentExecution(
                attemptId = prepared.attemptId,
                checks = checks(request, endpointIdentifier, trustedTime(identity)),
            )
        }.getOrElse {
            // Nothing was sent, so the reservation is released instead of blocking the manual payment.
            record(prepared.attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }
        val submitted = (handoff as? PaymentAttemptDecision.Ready)?.attempt
        if (submitted == null) {
            val reason = (handoff as? PaymentAttemptDecision.Blocked)?.reason
            Logger.info("Kept an incoming request manual: the allowance handoff is blocked by '$reason'", context = TAG)
            record(prepared.attemptId, PaymentOutcome.FAILED, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }
        runSuspendCatching { setStage(Stage.SUBMITTED, submitted.attemptId, identity) }.onFailure {
            record(submitted.attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }

        payer.consumePaymentList(request.counterparty, payment.context, submitted.attemptId).onFailure {
            record(submitted.attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }
        payer.prepareProof(request, endpointIdentifier, payment.appId, submitted.allowanceId).onFailure {
            payer.cancelProofPreparation(request)
            failUnsent(submitted.attemptId, identity)
            throw it
        }

        val paymentHash = checkNotNull(payment.lightningPaymentHash) { "Allowances pay only Lightning invoices" }
        return payLightning(request, amountSats, payment, paymentHash, submitted.attemptId, identity)
    }

    private suspend fun payLightning(
        request: PaykitPaymentRequest,
        amountSats: ULong,
        payment: PrivatePaykitAllowancePayment,
        paymentHash: String,
        attemptId: String,
        identity: String,
    ): PaykitAllowanceAutoPayResult {
        payer.associateLightningPayment(
            request,
            paymentHash,
            payment.endpoint.methodId.rawValue,
            payment.appId,
        ).onFailure {
            payer.cancelProofPreparation(request)
            failUnsent(attemptId, identity)
            throw it
        }

        runSuspendCatching { setStage(Stage.SENDING, attemptId, identity) }.onFailure {
            payer.discardLightningProof(paymentHash)
            failUnsent(attemptId, identity)
            throw it
        }
        val sats = amountSats.takeUnless { payment.lightningInvoiceHasAmount }
        // Routing fees fall outside the allowance's accounting, so they are capped at 1% of the requested amount.
        val maxRoutingFeeMsat = amountSats.safe() * MAX_ROUTING_FEE_MSAT_PER_SAT.safe()
        payer.payLightning(
            payment.endpoint.value,
            sats,
            request.submissionDeadline(null),
            maxRoutingFeeMsat
        ).onFailure {
            // Only an error that proves the payment never left releases the reservation; anything else stays open,
            // and the node's payment event records its outcome.
            if (payer.failLightningPayment(paymentHash, it)) {
                failUnsent(attemptId, identity)
                throw it
            }
            Logger.warn("Left an allowance payment with an uncertain send result to the node", it, context = TAG)
            record(attemptId, PaymentOutcome.UNKNOWN, identity)
            return PaykitAllowanceAutoPayResult.STARTED
        }
        // The node's payment event can settle the attempt before the send call returns; keep that outcome.
        updateJournalEntry(attemptId, identity) { if (it.stage == Stage.SENDING) it.copy(stage = Stage.SENT) else it }
        Logger.info("Handed an allowance payment to the node", context = TAG)
        return PaykitAllowanceAutoPayResult.STARTED
    }

    /** Called for every settled outbound Lightning payment; only journaled ones are Allowance work. */
    suspend fun lightningPaymentSettled(paymentHash: String, succeeded: Boolean): Unit = withContext(ioDispatcher) {
        settleMutex.withLock {
            val identity = activeIdentity ?: return@withLock
            val entries = localState(identity).journal.filter {
                it.paymentHash.equals(paymentHash, ignoreCase = true) && it.stage in SETTLEABLE_STAGES
            }
            for (entry in entries) {
                runSuspendCatching {
                    val outcome = if (succeeded) PaymentOutcome.SUCCEEDED else PaymentOutcome.FAILED
                    record(entry.attemptId, outcome, identity)
                    // A payment the node gave up on paid nothing, so the payee's list stays usable for the manual one.
                    if (!succeeded && entry.isAutomatic) releasePaymentList(entry, identity)
                }.onFailure { Logger.warn("Failed to record an allowance payment outcome", it, context = TAG) }
            }
        }
    }

    // endregion

    // region Manual payments

    /**
     * Reports a user-approved payment of an incoming request to the shared ledger before it leaves the wallet.
     * Fails with [PaykitAllowanceError.PaymentAlreadyRecorded] when another live attempt exists for the request, so
     * the same request is never paid twice. Any other problem leaves the payment unreported and returns null.
     * A Lightning payment passes its [paymentHash], so a backup taken before the send still lets the node settle it.
     */
    suspend fun beginManualPayment(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        paymentHash: String? = null,
    ): Result<String?> = withContext(ioDispatcher) {
        val identity = activeIdentity
        val amountSats = request.allowanceSats()
        if (
            identity == null ||
            amountSats == null ||
            request.billingPeriod != null ||
            request.direction != PaykitPaymentRequestDirection.Incoming
        ) {
            return@withContext Result.success(null)
        }
        val result = runSuspendCatching {
            reportManualPayment(
                request,
                amountSats,
                paymentEndpointIdentifier,
                paymentHash,
                identity,
            )
        }
        val error = result.exceptionOrNull() ?: return@withContext result
        if (error is PaykitAllowanceError.PaymentAlreadyRecorded) return@withContext result
        Logger.warn("Failed to report a manual payment to the allowance ledger", error, context = TAG)
        Result.success(null)
    }

    private suspend fun reportManualPayment(
        request: PaykitPaymentRequest,
        amountSats: ULong,
        paymentEndpointIdentifier: String,
        paymentHash: String?,
        identity: String,
    ): String? {
        ensureReconciled(identity)
        val decision = paykitSdkService.reserveManualPayment(
            occurrence = PaymentOccurrence(request.scope(), billingPeriod = null),
            checks = checks(request, paymentEndpointIdentifier, trustedTime(identity)),
        )
        val prepared = when (decision) {
            is PaymentAttemptDecision.Ready -> decision.attempt
            is PaymentAttemptDecision.Blocked if decision.reason == AllowanceAccountingBlock.PaymentAlreadyRecorded ->
                throw PaykitAllowanceError.PaymentAlreadyRecorded
            is PaymentAttemptDecision.Blocked -> {
                Logger.info("Skipped reporting a manual payment: blocked by '${decision.reason}'", context = TAG)
                return null
            }
        }
        // Recovery leaves the attempt alone until finishManualPayment reports its outcome.
        liveAttemptIds.add(prepared.attemptId)
        val attemptId = runSuspendCatching {
            beginManualAttempt(request, amountSats, paymentEndpointIdentifier, paymentHash, prepared, identity)
        }
        if (attemptId.getOrNull() == null) liveAttemptIds.remove(prepared.attemptId)
        // Nothing was sent yet, so a failed journal write releases the attempt instead of blocking a retry.
        attemptId.onFailure {
            runSuspendCatching { record(prepared.attemptId, PaymentOutcome.FAILED, identity) }
                .onFailure { Logger.warn("Failed to release a manual allowance attempt", it, context = TAG) }
        }
        return attemptId.getOrThrow()
    }

    private suspend fun beginManualAttempt(
        request: PaykitPaymentRequest,
        amountSats: ULong,
        paymentEndpointIdentifier: String,
        paymentHash: String?,
        prepared: PaymentAttemptRecord,
        identity: String,
    ): String? {
        journal(
            JournalEntry(
                attemptId = prepared.attemptId,
                isAutomatic = false,
                requestId = request.id,
                allowanceId = null,
                amountSats = amountSats,
                paymentEndpointIdentifier = paymentEndpointIdentifier,
                paymentHash = paymentHash?.lowercase(),
                stage = Stage.PREPARED,
                createdAtMillis = clock.now().toEpochMilliseconds(),
            ),
            identity,
        )
        val handoff = paykitSdkService.beginPaymentExecution(
            attemptId = prepared.attemptId,
            checks = checks(request, paymentEndpointIdentifier, trustedTime(identity)),
        )
        if (handoff !is PaymentAttemptDecision.Ready) {
            record(prepared.attemptId, PaymentOutcome.FAILED, identity)
            return null
        }
        // An on-chain send marks nothing before it broadcasts, so recovery must not read its attempt as never sent.
        val isOnchain = PaykitPaymentProofKind.fromPaymentEndpointIdentifier(paymentEndpointIdentifier) ==
            PaykitPaymentProofKind.Onchain
        setStage(if (isOnchain) Stage.SENDING else Stage.SUBMITTED, prepared.attemptId, identity)
        Logger.info("Reported a manual payment to the allowance ledger", context = TAG)
        return prepared.attemptId
    }

    suspend fun manualLightningPaymentSent(attemptId: String, paymentHash: String) {
        val identity = activeIdentity ?: return
        withContext(ioDispatcher) {
            updateJournalEntry(attemptId, identity) {
                it.copy(paymentHash = paymentHash.lowercase(), stage = Stage.SENT)
            }
        }
    }

    suspend fun finishManualPayment(
        attemptId: String,
        outcome: PaymentOutcome,
        transactionId: String? = null,
    ) {
        liveAttemptIds.remove(attemptId)
        val identity = activeIdentity ?: return
        withContext(ioDispatcher) {
            settleMutex.withLock {
                // The node's payment events may have settled a Lightning attempt already.
                val stage = localState(identity).journal.firstOrNull { it.attemptId == attemptId }?.stage
                if (stage == Stage.SUCCEEDED || stage == Stage.FAILED) return@withLock
                transactionId?.let { txid -> updateJournalEntry(attemptId, identity) { it.copy(transactionId = txid) } }
                runSuspendCatching { record(attemptId, outcome, identity) }
                    .onFailure { Logger.warn("Failed to record a manual payment outcome", it, context = TAG) }
            }
        }
    }

    /**
     * Records a released manual on-chain send of [requestId] as failed: the release spends its coins back to the wallet
     * and removes the evidence recovery reads, so the attempt would otherwise stay unknown and hold capacity. Throws
     * when an outcome could not be recorded, so the release is not finished without it.
     */
    suspend fun onchainSendReleased(payerIdentity: String?, requestId: PaykitPaymentRequestId) {
        val identity = activeIdentity?.takeIf { PubkyPublicKeyFormat.matches(it, payerIdentity) } ?: return
        withContext(ioDispatcher) {
            settleMutex.withLock {
                val attempts = localState(identity).journal.filter {
                    it.requestId == requestId && !it.isAutomatic && it.paymentHash == null &&
                        it.stage in SETTLEABLE_STAGES
                }
                for (attempt in attempts) record(attempt.attemptId, PaymentOutcome.FAILED, identity)
            }
        }
    }

    // endregion

    // region Helpers

    private fun checks(
        request: PaykitPaymentRequest,
        endpointIdentifier: String,
        trustedTime: String,
    ) = PaymentExecutionChecks(
        trustedTime = trustedTime,
        paymentEndpointIdentifier = endpointIdentifier,
        actualAmount = accountingAmount(request.amountValue, PaykitIssuerInterop.BITCOIN_ASSET),
        endpointCurrent = true,
        localEnabled = true,
        recurrenceEligible = true,
    )

    private suspend fun sendQueuedMessages(request: PaykitPaymentRequest) {
        runSuspendCatching {
            paykitSdkService.processOutboundPrivateMessages(request.counterparty)
        }.onFailure { Logger.warn("Failed to send the automatic acceptance right away", it, context = TAG) }
    }

    /**
     * Journal writes before a handoff must be durable: a failed save throws, so no money moves unjournaled. An
     * automatic attempt's [spend] is saved with it.
     */
    private suspend fun journal(
        entry: JournalEntry,
        identity: String,
        spend: PaykitAllowanceLocalState.AutomaticSpend? = null,
    ) {
        val retainedSince = (clock.now() - SPEND_RETENTION).toEpochMilliseconds()
        updateLocalStateDurably(identity) { state ->
            state.copy(
                journal = (state.journal.filterNot { it.attemptId == entry.attemptId } + entry)
                    .takeLast(MAX_JOURNAL_ENTRIES),
                automaticSpends = state.automaticSpends
                    .filter { it.admittedAtMillis >= retainedSince && it.attemptId != spend?.attemptId } +
                    listOfNotNull(spend),
            )
        }
    }

    private suspend fun setStage(stage: Stage, attemptId: String, identity: String) {
        updateLocalStateDurably(identity) { state ->
            state.copy(journal = state.journal.map { if (it.attemptId == attemptId) it.copy(stage = stage) else it })
        }
    }

    private suspend fun failUnsent(attemptId: String, identity: String) {
        releasePaymentList(attemptId, identity)
        record(attemptId, PaymentOutcome.FAILED, identity)
    }

    private suspend fun updateJournalEntry(
        attemptId: String,
        identity: String,
        change: (JournalEntry) -> JournalEntry,
    ) {
        updateLocalState(identity) { state ->
            state.copy(journal = state.journal.map { if (it.attemptId == attemptId) change(it) else it })
        }
    }

    private suspend fun releasePaymentList(entry: JournalEntry, identity: String) =
        releasePaymentList(entry.attemptId, identity)

    /**
     * Gives back the payment list the attempt journaled, and clears the journaled version only once that succeeded, so
     * a failed release or a restart leaves it for the next recovery. Releasing is owner-checked, so a repeated release
     * never undoes another payment's claim.
     */
    private suspend fun releasePaymentList(attemptId: String, identity: String) {
        val entry = localState(identity).journal.firstOrNull { it.attemptId == attemptId } ?: return
        val version = entry.paymentListVersion ?: return
        val context = PrivatePaykitPaymentContext(
            paymentAppsByEndpoint = emptyMap(),
            paymentListVersion = version,
            previousPaymentListVersion = entry.previousPaymentListVersion,
        )
        payer.releasePaymentList(entry.requestId.counterparty, context, attemptId).onSuccess {
            updateJournalEntry(attemptId, identity) {
                it.copy(paymentListVersion = null, previousPaymentListVersion = null)
            }
        }
    }

    private suspend fun notifyLimitReached(request: PaykitPaymentRequest, identity: String) {
        var isNew = false
        updateLocalState(identity) { state ->
            isNew = request.paymentRequestId !in state.notifiedRequestIds
            state.copy(notifiedRequestIds = state.notifiedRequestIds + request.paymentRequestId)
        }
        if (isNew) _events.tryEmit(PaykitAllowanceEvent.LimitReached(request.counterparty, request.amount.atomic))
    }

    // endregion
}

private fun PaykitPaymentRequest.scope() = PaymentRequestScope(counterparty, paymentRequestId)

private fun JournalEntry.isPaidAutomaticallyBy(stage: Stage): Boolean =
    isAutomatic && this.stage != Stage.SUCCEEDED && stage == Stage.SUCCEEDED

private fun PaykitAllowanceLocalState.withAutoPaid(
    requestId: PaykitPaymentRequestId,
    allowanceId: String?,
    amountSats: ULong,
): PaykitAllowanceLocalState {
    // Both the journal and the ledger report a payment; whichever comes first counts it.
    if (isAutoPaid(requestId)) return this
    // Allowance ids are unique per contact only, so payments count toward the contact's own allowance.
    val key = PaykitAllowance.key(requestId.counterparty, allowanceId)
    val total = (autoPaidSatsByAllowance[key] ?: 0uL).safe() + amountSats.safe()
    return copy(
        autoPaidSatsByAllowance = autoPaidSatsByAllowance + (key to total),
        autoPaidRequestIds = autoPaidRequestIds + requestId,
    )
}

private fun PaykitAllowanceLocalState.isAutoPaid(requestId: PaykitPaymentRequestId) = autoPaidRequestIds.any {
    it.paymentRequestId == requestId.paymentRequestId &&
        PubkyPublicKeyFormat.matches(it.counterparty, requestId.counterparty)
}

private fun PaykitAllowanceLocalState.withLedgerAutoPaid(
    history: AllowanceAccountingHistory,
): PaykitAllowanceLocalState =
    history.occurrences.filter { it.key.billingPeriod == null }.fold(this) { state, occurrence ->
        val scope = occurrence.key.request
        val requestId = PaykitPaymentRequestId(scope.paymentRequestId, scope.counterparty)
        val paid = occurrence.attempts.firstOrNull {
            it.mode == PaymentExecutionMode.AUTOMATIC && it.status == PaymentExecutionStatus.SUCCEEDED
        }
        val amountSats = paid?.amount?.value()?.let(PaykitAllowance::satsFromBitcoinAmount)
        if (paid == null || amountSats == null) {
            state
        } else {
            state.withAutoPaid(requestId, paid.allowanceId, amountSats)
        }
    }

/**
 * Allowances pay only bitcoin-denominated requests; null for any other request. A conversion offered for other rails,
 * such as USDT, does not change the bitcoin amount a Lightning payment sends. When it prices a bitcoin rail too, the
 * SDK blocks the automatic payment and the request stays manual.
 */
internal fun PaykitPaymentRequest.allowanceSats(): ULong? = amount.atomic.takeIf { amount.asset == PaykitAsset.BTC }

/** SDK rule codes for a request above an allowance's per-payment, period or lifetime limit. */
private val CAPACITY_RULE_CODES = setOf(
    "amount_outside_range",
    "period_amount_limit",
    "period_count_limit",
    "lifetime_amount_limit",
)

private fun AllowanceAccountingBlock?.isCapacityBlock(): Boolean =
    this is AllowanceAccountingBlock.SharedRule && code in CAPACITY_RULE_CODES
