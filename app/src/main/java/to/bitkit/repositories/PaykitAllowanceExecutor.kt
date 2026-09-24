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
import com.synonym.paykit.PaymentExecutionStatus
import com.synonym.paykit.PaymentOccurrence
import com.synonym.paykit.PaymentOutcome
import com.synonym.paykit.PaymentOutcomeReport
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.PaykitAllowanceLocalState.JournalEntry
import to.bitkit.repositories.PaykitAllowanceLocalState.Stage
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/** An on-chain send failed; [isDefinitelyNotBroadcast] is true only when the transaction surely never left. */
class PaykitAllowanceOnchainSendError(
    val isDefinitelyNotBroadcast: Boolean,
    cause: Throwable,
) : AppError(cause)

/** The side effects of paying one request, kept apart so the admission logic is testable without a node. */
@Singleton
@Suppress("TooManyFunctions")
class PaykitAllowancePayer @Inject constructor(
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val paymentProofRepo: PaykitPaymentProofRepo,
    private val lightningRepo: LightningRepo,
) {
    suspend fun resolve(
        request: PaykitPaymentRequest,
        eligibleIdentifiers: List<String>,
    ): Result<PrivatePaykitAllowancePayment?> = privatePaykitRepo.resolveAllowancePayment(request, eligibleIdentifiers)

    suspend fun consumePaymentList(publicKey: String, context: PrivatePaykitPaymentContext): Result<Unit> =
        privatePaykitRepo.consumePrivatePaymentList(publicKey, context)

    suspend fun prepareProof(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        allowanceId: String?,
    ): Result<Unit> {
        val kind = PaykitPaymentProofKind.fromPaymentEndpointIdentifier(paymentEndpointIdentifier)
            ?: return Result.failure(PaykitPaymentRequestError.RequestUnavailable)
        return paymentProofRepo.prepare(request, paymentEndpointIdentifier, kind, allowanceId)
    }

    suspend fun associateLightningPayment(
        request: PaykitPaymentRequest,
        paymentHash: String,
        paymentEndpointIdentifier: String,
    ): Result<Unit> = paymentProofRepo.associateLightningPayment(request, paymentHash, paymentEndpointIdentifier)

    suspend fun markOnchainPaymentStarted(request: PaykitPaymentRequest, address: String): Result<Unit> =
        paymentProofRepo.markOnchainPaymentStarted(request, address)

    suspend fun payLightning(bolt11: String, sats: ULong?): Result<String> = lightningRepo.payInvoice(bolt11, sats)

    /** Sends at the medium fee rate. A failure is a [PaykitAllowanceOnchainSendError]. */
    suspend fun payOnchain(address: String, sats: ULong): Result<String> {
        var sendAttempted = false
        var broadcastTxid: String? = null
        val result = lightningRepo.sendOnChain(
            address = address,
            sats = sats,
            speed = TransactionSpeed.Medium,
            beforeSendAttempt = { sendAttempted = true },
            onBroadcast = { broadcastTxid = it },
        )
        broadcastTxid?.let { return Result.success(it) }
        val error = result.exceptionOrNull() ?: return result
        return Result.failure(
            PaykitAllowanceOnchainSendError(
                isDefinitelyNotBroadcast = !sendAttempted || error.isDefiniteOnchainPreBroadcastFailure(),
                cause = error,
            ),
        )
    }

    suspend fun completeOnchainPayment(
        request: PaykitPaymentRequest,
        txid: String,
        paymentEndpointIdentifier: String,
    ) {
        paymentProofRepo.completeOnchainPayment(request, txid, paymentEndpointIdentifier)
    }

    /** Clears the proof when [error] proves the payment never left; returns whether it did. */
    suspend fun failLightningPayment(paymentHash: String, error: Throwable): Boolean =
        paymentProofRepo.failLightningPayment(paymentHash, error)

    suspend fun failOnchainPayment(request: PaykitPaymentRequest) = paymentProofRepo.failOnchainPayment(request)

    suspend fun cancelProofPreparation(request: PaykitPaymentRequest) = paymentProofRepo.cancelPreparation(request)

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
        private val SETTLEABLE_STAGES = setOf(Stage.SUBMITTED, Stage.SENDING, Stage.SENT, Stage.UNKNOWN)
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

    fun succeededAutomaticPayments(identity: String): List<JournalEntry> =
        localState(identity).journal.filter { it.isAutomatic && it.stage == Stage.SUCCEEDED }

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
        val outcomes = history.occurrences.flatMap { it.attempts }.mapNotNull { attempt ->
            verifiedOutcome(attempt, identity)?.let { PaymentOutcomeReport(attempt.attemptId, it) }
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
            // No ledger means nothing was ever admitted. Creating one here would also end the rc55 storage layout.
            paykitSdkService.allowanceAccountingState() ?: return@runSuspendCatching
            val state = ensureReconciled(identity)
            for (attempt in state.history.occurrences.flatMap { it.attempts }) {
                if (attempt.attemptId in liveAttemptIds) continue
                when (attempt.status) {
                    PaymentExecutionStatus.PREPARED -> {
                        if (attempt.epoch == state.epoch) record(attempt.attemptId, PaymentOutcome.FAILED, identity)
                    }
                    PaymentExecutionStatus.SUBMITTED, PaymentExecutionStatus.UNKNOWN -> {
                        val outcome = verifiedOutcome(attempt, identity)
                            ?: PaymentOutcome.UNKNOWN.takeIf { attempt.status == PaymentExecutionStatus.SUBMITTED }
                        outcome?.let { record(attempt.attemptId, it, identity) }
                    }
                    PaymentExecutionStatus.SUCCEEDED, PaymentExecutionStatus.FAILED -> Unit
                }
            }
        }.onFailure { Logger.warn("Failed to recover Paykit allowance attempts", it, context = TAG) }
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

    private suspend fun verifiedOutcome(attempt: PaymentAttemptRecord, identity: String): PaymentOutcome? =
        when (attempt.status) {
            PaymentExecutionStatus.PREPARED -> PaymentOutcome.FAILED
            PaymentExecutionStatus.SUCCEEDED, PaymentExecutionStatus.FAILED -> null
            PaymentExecutionStatus.SUBMITTED, PaymentExecutionStatus.UNKNOWN -> localState(identity).journal
                .firstOrNull { it.attemptId == attempt.attemptId }
                ?.let { journalOutcome(it) }
        }

    private suspend fun journalOutcome(entry: JournalEntry): PaymentOutcome? = when (entry.stage) {
        // The journal is written before the node call, so no payment left this wallet.
        Stage.PREPARED, Stage.SUBMITTED, Stage.FAILED -> PaymentOutcome.FAILED
        Stage.SUCCEEDED -> PaymentOutcome.SUCCEEDED
        Stage.SENDING, Stage.SENT, Stage.UNKNOWN -> {
            val paymentHash = entry.paymentHash
            if (paymentHash == null) {
                PaymentOutcome.SUCCEEDED.takeIf { entry.transactionId != null }
            } else {
                when (payer.lightningPaymentStatus(paymentHash)) {
                    PaymentStatus.SUCCEEDED -> PaymentOutcome.SUCCEEDED
                    PaymentStatus.FAILED -> PaymentOutcome.FAILED
                    PaymentStatus.PENDING, null -> null
                }
            }
        }
    }

    private suspend fun record(attemptId: String, outcome: PaymentOutcome, identity: String) {
        paykitSdkService.recordPaymentOutcome(PaymentOutcomeReport(attemptId, outcome))
        val stage = when (outcome) {
            PaymentOutcome.SUCCEEDED -> Stage.SUCCEEDED
            PaymentOutcome.FAILED -> Stage.FAILED
            PaymentOutcome.UNKNOWN -> Stage.UNKNOWN
        }
        updateJournalEntry(attemptId, identity) { it.copy(stage = stage) }
        _events.tryEmit(PaykitAllowanceEvent.LedgerChanged)
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
                    PubkyPublicKeyFormat.matches(it.counterparty, request.counterparty) &&
                    it.counterpartyReceiverPath == request.counterpartyReceiverPath
            }
        if (!isCovered || !inFlightRequestIds.add(request.id)) {
            return@withContext PaykitAllowanceAutoPayResult.NOT_COVERED
        }

        try {
            runSuspendCatching {
                ensureReconciled(identity)
                admitAndPay(request, allowances, identity)
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
        val scope = request.scope()
        val selectionTime = trustedTime(identity)
        val candidates = paykitSdkService.evaluateAllowanceCandidates(scope, selectionTime)
        val candidate = candidates.firstOrNull { it.blocked == null }
        val allowance = candidate?.let { eligible -> allowances.firstOrNull { it.allowanceId == eligible.allowanceId } }
        if (candidate == null || allowance == null) {
            val reasons = candidates.mapNotNull { it.blocked }
            Logger.info("Kept an incoming request manual: no eligible allowance, blocked by '$reasons'", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        if (!PaykitAllowanceCapacity.fits(request.amountSats, allowance, automaticAttempts(), clock.now())) {
            Logger.info("Kept an incoming request manual: the allowance limit is reached", context = TAG)
            notifyLimitReached(request, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        val payment = payer.resolve(request, candidate.eligiblePaymentEndpointIdentifiers).getOrThrow()
        if (payment == null) {
            Logger.info("Kept an incoming request manual: no payable private endpoint", context = TAG)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        val endpointIdentifier = payment.endpoint.methodId.rawValue
        val association = paykitSdkService.acceptPaymentRequestAutomatically(
            scope = scope,
            selection = AllowanceSelectionInput(
                allowanceId = candidate.allowanceId,
                expectedRevision = null,
                trustedTime = selectionTime,
            ),
            checks = checks(request, endpointIdentifier, selectionTime),
        )
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
            notifyLimitReached(request, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }

        liveAttemptIds.add(prepared.attemptId)
        try {
            return executeAutomaticAttempt(request, payment, prepared, identity)
        } finally {
            liveAttemptIds.remove(prepared.attemptId)
        }
    }

    private suspend fun executeAutomaticAttempt(
        request: PaykitPaymentRequest,
        payment: PrivatePaykitAllowancePayment,
        prepared: PaymentAttemptRecord,
        identity: String,
    ): PaykitAllowanceAutoPayResult {
        val endpointIdentifier = payment.endpoint.methodId.rawValue
        journal(
            JournalEntry(
                attemptId = prepared.attemptId,
                isAutomatic = true,
                requestId = request.id,
                allowanceId = prepared.allowanceId,
                amountSats = request.amountSats,
                paymentEndpointIdentifier = endpointIdentifier,
                paymentHash = payment.lightningPaymentHash,
                onchainAddress = payment.endpoint.value.takeIf { payment.endpoint.methodId.isOnchain },
                stage = Stage.PREPARED,
                createdAtMillis = clock.now().toEpochMilliseconds(),
            ),
            identity,
        )

        // Begin fetches nothing, so pull the link first: an End or a cancellation must be seen before the handoff.
        runSuspendCatching {
            paykitSdkService.receivePrivateMessages(request.counterparty, request.counterpartyReceiverPath)
        }.onFailure { Logger.warn("Failed to receive private messages before an allowance payment", it, context = TAG) }
        val handoff = paykitSdkService.beginPaymentExecution(
            attemptId = prepared.attemptId,
            checks = checks(request, endpointIdentifier, trustedTime(identity)),
        )
        val submitted = (handoff as? PaymentAttemptDecision.Ready)?.attempt
        if (submitted == null) {
            val reason = (handoff as? PaymentAttemptDecision.Blocked)?.reason
            Logger.info("Kept an incoming request manual: the allowance handoff is blocked by '$reason'", context = TAG)
            record(prepared.attemptId, PaymentOutcome.FAILED, identity)
            return PaykitAllowanceAutoPayResult.MANUAL
        }
        setStage(Stage.SUBMITTED, submitted.attemptId, identity)

        runSuspendCatching {
            payer.consumePaymentList(request.counterparty, payment.context).getOrThrow()
            payer.prepareProof(request, endpointIdentifier, submitted.allowanceId).getOrThrow()
        }.exceptionOrNull()?.let {
            payer.cancelProofPreparation(request)
            record(submitted.attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }

        val paymentHash = payment.lightningPaymentHash
            ?: return payOnchain(request, payment, submitted.attemptId, identity)
        return payLightning(request, payment, paymentHash, submitted.attemptId, identity)
    }

    private suspend fun payLightning(
        request: PaykitPaymentRequest,
        payment: PrivatePaykitAllowancePayment,
        paymentHash: String,
        attemptId: String,
        identity: String,
    ): PaykitAllowanceAutoPayResult {
        payer.associateLightningPayment(request, paymentHash, payment.endpoint.methodId.rawValue).onFailure {
            payer.cancelProofPreparation(request)
            record(attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }

        setStage(Stage.SENDING, attemptId, identity)
        val sats = request.amountSats.takeUnless { payment.lightningInvoiceHasAmount }
        payer.payLightning(payment.endpoint.value, sats).onFailure {
            // Only an error that proves the payment never left releases the reservation; anything else stays open.
            val neverSent = payer.failLightningPayment(paymentHash, it)
            record(attemptId, if (neverSent) PaymentOutcome.FAILED else PaymentOutcome.UNKNOWN, identity)
            throw it
        }
        // The node's payment event can settle the attempt before the send call returns; keep that outcome.
        updateJournalEntry(attemptId, identity) { if (it.stage == Stage.SENDING) it.copy(stage = Stage.SENT) else it }
        Logger.info("Handed an allowance payment to the node", context = TAG)
        return PaykitAllowanceAutoPayResult.STARTED
    }

    private suspend fun payOnchain(
        request: PaykitPaymentRequest,
        payment: PrivatePaykitAllowancePayment,
        attemptId: String,
        identity: String,
    ): PaykitAllowanceAutoPayResult {
        val address = payment.endpoint.value
        payer.markOnchainPaymentStarted(request, address).onFailure {
            payer.cancelProofPreparation(request)
            record(attemptId, PaymentOutcome.FAILED, identity)
            throw it
        }

        setStage(Stage.SENDING, attemptId, identity)
        val txid = payer.payOnchain(address, request.amountSats).getOrElse {
            if ((it as? PaykitAllowanceOnchainSendError)?.isDefinitelyNotBroadcast == true) {
                payer.failOnchainPayment(request)
                record(attemptId, PaymentOutcome.FAILED, identity)
            } else {
                record(attemptId, PaymentOutcome.UNKNOWN, identity)
            }
            throw it
        }

        updateJournalEntry(attemptId, identity) { it.copy(transactionId = txid) }
        payer.completeOnchainPayment(request, txid, payment.endpoint.methodId.rawValue)
        record(attemptId, PaymentOutcome.SUCCEEDED, identity)
        _events.tryEmit(PaykitAllowanceEvent.PaidAutomatically(request.counterparty, request.amountSats, txid))
        Logger.info("Paid an allowance payment on-chain", context = TAG)
        return PaykitAllowanceAutoPayResult.COMPLETED
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
                    if (succeeded && entry.isAutomatic) {
                        _events.tryEmit(
                            PaykitAllowanceEvent.PaidAutomatically(
                                counterparty = entry.requestId.counterparty,
                                amountSats = entry.amountSats,
                                paymentId = paymentHash.lowercase(),
                            ),
                        )
                    }
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
     */
    suspend fun beginManualPayment(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
    ): Result<String?> = withContext(ioDispatcher) {
        val identity = activeIdentity
        if (
            identity == null ||
            request.billingPeriod != null ||
            request.direction != PaykitPaymentRequestDirection.Incoming
        ) {
            return@withContext Result.success(null)
        }
        val result = runSuspendCatching { reportManualPayment(request, paymentEndpointIdentifier, identity) }
        val error = result.exceptionOrNull() ?: return@withContext result
        if (error is PaykitAllowanceError.PaymentAlreadyRecorded) return@withContext result
        Logger.warn("Failed to report a manual payment to the allowance ledger", error, context = TAG)
        Result.success(null)
    }

    private suspend fun reportManualPayment(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
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
            beginManualAttempt(request, paymentEndpointIdentifier, prepared, identity)
        }
        if (attemptId.getOrNull() == null) liveAttemptIds.remove(prepared.attemptId)
        return attemptId.getOrThrow()
    }

    private suspend fun beginManualAttempt(
        request: PaykitPaymentRequest,
        paymentEndpointIdentifier: String,
        prepared: PaymentAttemptRecord,
        identity: String,
    ): String? {
        journal(
            JournalEntry(
                attemptId = prepared.attemptId,
                isAutomatic = false,
                requestId = request.id,
                allowanceId = null,
                amountSats = request.amountSats,
                paymentEndpointIdentifier = paymentEndpointIdentifier,
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
        setStage(Stage.SUBMITTED, prepared.attemptId, identity)
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
            paykitSdkService.processOutboundPrivateMessages(request.counterparty, request.counterpartyReceiverPath)
        }.onFailure { Logger.warn("Failed to send the automatic acceptance right away", it, context = TAG) }
    }

    private suspend fun journal(entry: JournalEntry, identity: String) {
        updateLocalState(identity) { state ->
            state.copy(
                journal = (state.journal.filterNot { it.attemptId == entry.attemptId } + entry)
                    .takeLast(MAX_JOURNAL_ENTRIES),
            )
        }
    }

    private suspend fun setStage(stage: Stage, attemptId: String, identity: String) {
        updateJournalEntry(attemptId, identity) { it.copy(stage = stage) }
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

    private suspend fun notifyLimitReached(request: PaykitPaymentRequest, identity: String) {
        var isNew = false
        updateLocalState(identity) { state ->
            isNew = request.paymentRequestId !in state.notifiedRequestIds
            state.copy(notifiedRequestIds = state.notifiedRequestIds + request.paymentRequestId)
        }
        if (isNew) _events.tryEmit(PaykitAllowanceEvent.LimitReached(request.counterparty, request.amountSats))
    }

    // endregion
}

private fun PaykitPaymentRequest.scope() =
    PaymentRequestScope(counterparty, counterpartyReceiverPath, paymentRequestId)

private fun Throwable.isDefiniteOnchainPreBroadcastFailure(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .any {
            it is ServiceError.NodeNotSetup ||
                it is ServiceError.NodeNotStarted ||
                it is NodeException.NotRunning ||
                it is NodeException.OnchainTxCreationFailed ||
                it is NodeException.OnchainTxSigningFailed ||
                it is NodeException.WalletOperationFailed ||
                it is NodeException.PersistenceFailed ||
                it is NodeException.InvalidAddress ||
                it is NodeException.InvalidAmount ||
                it is NodeException.InvalidNetwork ||
                it is NodeException.InvalidFeeRate ||
                it is NodeException.InsufficientFunds ||
                it is NodeException.CoinSelectionFailed ||
                it is NodeException.NoSpendableOutputs
        }
