package to.bitkit.repositories

import com.synonym.paykit.AllowanceFilter
import com.synonym.paykit.AllowanceHistoryStatus
import com.synonym.paykit.AllowanceLocalRole
import com.synonym.paykit.AllowanceRecord
import com.synonym.paykit.AllowanceTerms
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaymentOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import to.bitkit.async.appScope
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.safe
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

sealed interface PaykitAllowanceEvent {
    data class PaidAutomatically(
        val counterparty: String,
        val amountSats: ULong,
        val paymentId: String,
    ) : PaykitAllowanceEvent

    data class LimitReached(val counterparty: String, val amountSats: ULong) : PaykitAllowanceEvent

    data object LedgerChanged : PaykitAllowanceEvent
}

enum class PaykitAllowanceAutoPayResult {
    /** No accepted Allowance covers the request's link, or the request is already being paid. */
    NOT_COVERED,

    /** An Allowance exists but this request stays on the manual flow (over a limit, ended, no payable endpoint). */
    MANUAL,

    /** The Lightning payment was handed to the node; the outcome arrives through the node's payment events. */
    STARTED,

    /** The on-chain payment was broadcast and recorded. */
    COMPLETED,

    /** The payee has not published a payment list newer than the one last paid; the next refresh tries again. */
    DEFERRED,
}

sealed class PaykitAllowanceError(message: String) : AppError(message) {
    data object ContactNotLinked : PaykitAllowanceError("The contact has no linked Paykit receiver")
    data object Unavailable : PaykitAllowanceError("The allowance is unavailable")
    data object PaymentAlreadyRecorded : PaykitAllowanceError("A payment for this request is already in progress")
    data object PaymentListPending : PaykitAllowanceError("The payee has not published a new payment list yet")
}

/**
 * Allowances for the active identity. One user-facing entry groups the same grant across a contact's links (their
 * wallet, and their Paykit Server folder), and covered incoming requests are paid headlessly through
 * [PaykitAllowanceExecutor]. The repository also settles its own Lightning attempts from the node's payment events
 * and attributes automatic payments to the contact's activity.
 */
@Singleton
@Suppress("TooManyFunctions", "LongParameterList")
class PaykitAllowanceRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val executor: PaykitAllowanceExecutor,
    private val lightningRepo: LightningRepo,
    private val activityRepo: ActivityRepo,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PaykitAllowanceRepo"

        /** A request created up to this long before its Allowance was accepted still counts as created after it. */
        val ACCEPTANCE_CLOCK_TOLERANCE = 30.seconds

        /** Endpoints Bitkit pays automatically: bolt11 and every on-chain method of the network. */
        fun allowedPaymentEndpointIdentifiers(network: Network = Env.network): List<String> =
            (listOf(MethodId.Bolt11) + MethodId.entries.filter { it.isOnchain }).map { it.rawValueForNetwork(network) }

        /** The supported receiver paths among [paths], the wallet link first. */
        fun orderedReceiverPaths(paths: Collection<String>): List<String> =
            PaykitReceiverPaths.ordered.filter { it in paths }
    }

    private val scope = appScope(ioDispatcher, TAG)
    private val refreshMutex = Mutex()
    private val publishLock = Any()
    private val isProcessingRequests = AtomicBoolean(false)
    private val manualRequestSignatures = ConcurrentHashMap<PaykitPaymentRequestId, Int>()
    private val _allowances = MutableStateFlow<List<PaykitAllowance>>(emptyList())
    private val _localState = MutableStateFlow(PaykitAllowanceLocalState())
    private val _autoPaidRequestIds = MutableStateFlow<Set<PaykitPaymentRequestId>>(emptySet())
    private var allowanceTerms: (PaykitAllowanceLimits, Instant, List<String>) -> AllowanceTerms =
        { limits, monthAnchor, endpoints -> limits.terms(monthAnchor, endpoints) }

    @Volatile
    private var activeIdentity: String? = null

    private val _entries = MutableStateFlow<List<PaykitAllowanceEntry>>(emptyList())

    /** Grants grouped across a contact's links, newest first. */
    val entries: StateFlow<List<PaykitAllowanceEntry>> = _entries.asStateFlow()

    private val _autoPaidSats = MutableStateFlow<Map<String, ULong>>(emptyMap())

    /** Sats paid automatically per entry id, for "Paid so far". */
    val autoPaidSats: StateFlow<Map<String, ULong>> = _autoPaidSats.asStateFlow()

    val events: SharedFlow<PaykitAllowanceEvent> = executor.events

    internal constructor(
        ioDispatcher: CoroutineDispatcher,
        paykitSdkService: PaykitSdkService,
        executor: PaykitAllowanceExecutor,
        lightningRepo: LightningRepo,
        activityRepo: ActivityRepo,
        clock: Clock,
        allowanceTerms: (PaykitAllowanceLimits, Instant, List<String>) -> AllowanceTerms,
    ) : this(ioDispatcher, paykitSdkService, executor, lightningRepo, activityRepo, clock) {
        this.allowanceTerms = allowanceTerms
    }

    init {
        scope.launch { executor.events.collect { onAllowanceEvent(it) } }
        scope.launch { lightningRepo.nodeEvents.collect { onNodeEvent(it) } }
        scope.launch {
            lightningRepo.lightningState
                .map { it.nodeLifecycleState.isRunning() }
                .distinctUntilChanged()
                .filter { it }
                .collect { executor.settleOpenLightningAttempts() }
        }
    }

    fun entry(id: String): PaykitAllowanceEntry? = _entries.value.firstOrNull { it.id == id }

    // region Lifecycle

    suspend fun activate(identity: String?) {
        val normalizedIdentity = identity?.let(PubkyPublicKeyFormat::normalized)
        if (normalizedIdentity == null) {
            deactivate()
            return
        }
        withContext(ioDispatcher) {
            val identityChanged = activeIdentity != normalizedIdentity
            activeIdentity = normalizedIdentity
            executor.activate(normalizedIdentity)
            if (identityChanged) {
                manualRequestSignatures.clear()
                executor.recover(normalizedIdentity)
            }
            refresh()
        }
    }

    fun deactivate() {
        activeIdentity = null
        executor.activate(null)
        manualRequestSignatures.clear()
        publish(emptyList(), PaykitAllowanceLocalState())
    }

    suspend fun refresh(): Result<Unit> = withContext(ioDispatcher) {
        val identity = activeIdentity ?: return@withContext Result.success(Unit)
        refreshMutex.withLock {
            val listing = runSuspendCatching {
                paykitSdkService.listAllowances(
                    AllowanceFilter(
                        counterparty = null,
                        counterpartyReceiverPath = null,
                        localRole = null,
                        states = emptyList(),
                    ),
                )
                    .filter {
                        it.historyStatus == AllowanceHistoryStatus.CONSISTENT ||
                            it.historyStatus == AllowanceHistoryStatus.UNRESOLVED_REFERENCES
                    }
                    .mapNotNull { PaykitAllowance.from(it) }
            }.onFailure { Logger.warn("Failed to list Paykit allowances", it, context = TAG) }
            if (activeIdentity == identity) {
                publish(listing.getOrDefault(_allowances.value), executor.localState(identity))
            }
            listing.map {}
        }
    }

    /** Proposes the same terms on every supported linked receiver path of the contact and records one entry. */
    suspend fun propose(contactPublicKey: String, limits: PaykitAllowanceLimits): Result<Unit> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                val identity = activeIdentity ?: throw PaykitAllowanceError.Unavailable
                val contactKey = PubkyPublicKeyFormat.normalized(contactPublicKey)
                    ?: throw PaykitAllowanceError.ContactNotLinked
                val linkedPaths = paykitSdkService.linkedPeers()
                    .filter {
                        PubkyPublicKeyFormat.matches(it.counterparty, contactKey) && it.state == LinkedPeerState.LINKED
                    }
                    .map { it.counterpartyReceiverPath }
                val receiverPaths = orderedReceiverPaths(linkedPaths)
                if (receiverPaths.isEmpty()) throw PaykitAllowanceError.ContactNotLinked

                val terms = allowanceTerms(
                    limits,
                    PaykitAllowanceTime.monthStart(clock.now()),
                    allowedPaymentEndpointIdentifiers(),
                )
                val allowanceIds = proposeOnLinks(contactKey, receiverPaths, terms)
                val group = PaykitAllowanceLocalState.Group(
                    id = UUID.randomUUID().toString(),
                    counterparty = contactKey,
                    limits = limits,
                    allowanceIds = allowanceIds,
                    createdAtMillis = clock.now().toEpochMilliseconds(),
                )
                executor.updateLocalState(identity) { it.copy(groups = it.groups + group) }
                Logger.info("Proposed an allowance on '${allowanceIds.size}' links", context = TAG)
                refresh()
                Unit
            }
        }

    suspend fun accept(entryId: String): Result<Unit> = respond(entryId) {
        paykitSdkService.acceptAllowance(it.counterparty, it.counterpartyReceiverPath, it.allowanceId)
    }

    suspend fun reject(entryId: String): Result<Unit> = respond(entryId) {
        paykitSdkService.rejectAllowance(it.counterparty, it.counterpartyReceiverPath, it.allowanceId)
    }

    suspend fun end(entryId: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val entry = entry(entryId) ?: throw PaykitAllowanceError.Unavailable
            val endable = entry.allowances.filter { it.canEnd }
            if (endable.isEmpty()) throw PaykitAllowanceError.Unavailable
            for (allowance in endable) {
                paykitSdkService.endAllowance(
                    allowance.counterparty,
                    allowance.counterpartyReceiverPath,
                    allowance.allowanceId,
                )
                sendQueuedMessages(allowance.counterparty, allowance.counterpartyReceiverPath)
            }
            refresh()
            Unit
        }
    }

    private suspend fun respond(
        entryId: String,
        response: suspend (PaykitAllowance) -> AllowanceRecord,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val identity = activeIdentity ?: throw PaykitAllowanceError.Unavailable
            val entry = entry(entryId) ?: throw PaykitAllowanceError.Unavailable
            val answerable = entry.allowances.filter { it.isAnswerable }
            if (answerable.isEmpty()) throw PaykitAllowanceError.Unavailable
            for (allowance in answerable) {
                response(allowance)
                sendQueuedMessages(allowance.counterparty, allowance.counterpartyReceiverPath)
            }
            val ids = entry.allowances.map { it.allowanceId }
            executor.updateLocalState(identity) { it.copy(presentedProposalIds = it.presentedProposalIds + ids) }
            refresh()
            Unit
        }
    }

    private suspend fun proposeOnLinks(
        contactKey: String,
        receiverPaths: List<String>,
        terms: AllowanceTerms,
    ): List<String> {
        val allowanceIds = mutableListOf<String>()
        var firstError: Throwable? = null
        for (receiverPath in receiverPaths) {
            runSuspendCatching {
                paykitSdkService.proposeAllowance(contactKey, receiverPath, AllowanceLocalRole.ALLOWER, terms)
            }.onSuccess {
                allowanceIds += it.allowanceId
                sendQueuedMessages(contactKey, receiverPath)
            }.onFailure {
                if (receiverPath == PaykitReceiverPaths.WALLET) throw it
                if (firstError == null) firstError = it
                Logger.warn("Failed to propose an allowance on the secondary link '$receiverPath'", it, context = TAG)
            }
        }
        if (allowanceIds.isEmpty()) throw firstError ?: PaykitAllowanceError.Unavailable
        return allowanceIds
    }

    private suspend fun sendQueuedMessages(counterparty: String, receiverPath: String) {
        runSuspendCatching { paykitSdkService.processOutboundPrivateMessages(counterparty, receiverPath) }
            .onFailure {
                Logger.warn("Failed to send allowance messages on '$receiverPath' right away", it, context = TAG)
            }
    }

    // endregion

    // region Presentation

    /** The first received proposal that still needs an answer and was not shown yet. */
    fun proposalForPresentation(): PaykitAllowanceEntry? {
        val presented = _localState.value.presentedProposalIds
        return _entries.value.firstOrNull { entry ->
            entry.isAnswerable && entry.allowances.none { it.allowanceId in presented }
        }
    }

    suspend fun markProposalPresented(entryId: String) {
        val identity = activeIdentity ?: return
        val ids = entry(entryId)?.allowances?.map { it.allowanceId } ?: return
        val state = withContext(ioDispatcher) {
            executor.updateLocalState(identity) { it.copy(presentedProposalIds = it.presentedProposalIds + ids) }
        }
        if (activeIdentity == identity) publish(_allowances.value, state)
    }

    // endregion

    // region Automatic payments

    /**
     * Whether an active Allowance this wallet granted covers the request's exact link. A request created before the
     * Allowance was accepted stays manual: it may already have been shown to the user for a decision.
     */
    fun coversRequest(request: PaykitPaymentRequest): Boolean {
        val now = clock.now()
        return _allowances.value.any { allowance ->
            allowance.isAllower &&
                allowance.status(now) == PaykitAllowance.Status.ACTIVE &&
                PubkyPublicKeyFormat.matches(allowance.counterparty, request.counterparty) &&
                allowance.counterpartyReceiverPath == request.counterpartyReceiverPath &&
                isCreatedAfterAcceptance(request, allowance)
        }
    }

    /** Pays covered incoming requests headlessly; returns true when any request was handled. */
    suspend fun processIncomingRequests(requests: List<PaykitPaymentRequest>): Boolean = withContext(ioDispatcher) {
        val identity = activeIdentity ?: return@withContext false
        val signature = allowancesSignature()
        val covered = requests.filter {
            it.requiresAcceptance && coversRequest(it) && manualRequestSignatures[it.id] != signature
        }
        if (covered.isEmpty() || !canPayNow()) return@withContext false
        if (!isProcessingRequests.compareAndSet(false, true)) return@withContext false

        try {
            payCovered(covered, identity, signature)
        } finally {
            isProcessingRequests.set(false)
        }
    }

    /**
     * True while a request is covered by an active allowance or is being paid automatically: keep it off the Send
     * sheet. A covered request counts until processIncomingRequests has found it manual.
     */
    suspend fun isAutomaticallyHandling(request: PaykitPaymentRequest): Boolean {
        if (executor.isHandling(request.id)) return true
        return request.requiresAcceptance &&
            coversRequest(request) &&
            manualRequestSignatures[request.id] != allowancesSignature()
    }

    /**
     * A node that just started lists its channels before they reconnect, and a payment sent then fails with no route.
     * Covered requests wait for the next refresh instead of falling back to the manual flow.
     */
    private fun canPayNow(): Boolean {
        val state = lightningRepo.lightningState.value
        return state.nodeLifecycleState.isRunning() && (state.channels.isEmpty() || state.channels.any { it.isUsable })
    }

    /** Request ids paid automatically, for the "Auto-paid" tag in payment history. */
    fun isAutoPaid(id: PaykitPaymentRequestId): Boolean = id in _autoPaidRequestIds.value

    private suspend fun payCovered(
        covered: List<PaykitPaymentRequest>,
        identity: String,
        signature: Int,
    ): Boolean {
        var handledAny = false
        for (request in covered) {
            val result = executor.autoPay(request, _allowances.value, identity)
            Logger.info("Decided '$result' for an incoming request covered by an allowance", context = TAG)
            when (result) {
                PaykitAllowanceAutoPayResult.STARTED, PaykitAllowanceAutoPayResult.COMPLETED -> handledAny = true
                PaykitAllowanceAutoPayResult.MANUAL -> manualRequestSignatures[request.id] = signature
                PaykitAllowanceAutoPayResult.NOT_COVERED, PaykitAllowanceAutoPayResult.DEFERRED -> Unit
            }
        }
        if (handledAny) refresh()
        return handledAny
    }

    private fun isCreatedAfterAcceptance(request: PaykitPaymentRequest, allowance: PaykitAllowance): Boolean {
        val acceptedAt = allowance.lastEventAt ?: return true
        val createdAt = request.createdAt ?: return true
        return createdAt >= acceptedAt - ACCEPTANCE_CLOCK_TOLERANCE
    }

    private fun allowancesSignature(): Int {
        val lifecycle = _allowances.value.map { it.allowanceId to it.lifecycleState }
        val autoPaidTotal = _autoPaidSats.value.values.fold(0uL) { total, sats -> total.safe() + sats.safe() }
        return listOf(lifecycle, autoPaidTotal).hashCode()
    }

    // endregion

    // region Ledger

    /**
     * Reports a manual payment of a request to the allowance ledger; returns the attempt id or null when no ledger
     * applies. Fails with [PaykitAllowanceError.PaymentAlreadyRecorded] while another attempt for the request is live.
     */
    suspend fun beginManualPayment(request: PaykitPaymentRequest, paymentEndpointIdentifier: String): Result<String?> =
        executor.beginManualPayment(request, paymentEndpointIdentifier)

    suspend fun manualLightningPaymentSent(attemptId: String, paymentHash: String) =
        executor.manualLightningPaymentSent(attemptId, paymentHash)

    /** [succeeded] null = outcome unknown (pending). */
    suspend fun finishManualPayment(attemptId: String, succeeded: Boolean?, transactionId: String? = null) {
        val outcome = when (succeeded) {
            true -> PaymentOutcome.SUCCEEDED
            false -> PaymentOutcome.FAILED
            null -> PaymentOutcome.UNKNOWN
        }
        executor.finishManualPayment(attemptId, outcome, transactionId)
    }

    /** The repository already settles from the node's payment events; extra calls for the same hash are no-ops. */
    suspend fun lightningPaymentSettled(paymentHash: String, succeeded: Boolean) =
        executor.lightningPaymentSettled(paymentHash, succeeded)

    suspend fun recover() {
        val identity = activeIdentity ?: return
        executor.recover(identity)
    }

    private suspend fun onNodeEvent(event: Event) {
        when (event) {
            is Event.PaymentSuccessful -> executor.lightningPaymentSettled(event.paymentHash, succeeded = true)
            is Event.PaymentFailed -> (event.paymentHash ?: event.paymentId)?.let {
                executor.lightningPaymentSettled(it, succeeded = false)
            }
            else -> Unit
        }
    }

    private suspend fun onAllowanceEvent(event: PaykitAllowanceEvent) {
        when (event) {
            is PaykitAllowanceEvent.PaidAutomatically -> {
                activityRepo.setContact(event.counterparty, event.paymentId)
                reloadLocalState()
            }
            PaykitAllowanceEvent.LedgerChanged -> reloadLocalState()
            is PaykitAllowanceEvent.LimitReached -> Unit
        }
    }

    private fun reloadLocalState() {
        val identity = activeIdentity ?: return
        publish(_allowances.value, executor.localState(identity))
    }

    // endregion

    private fun publish(
        allowances: List<PaykitAllowance>,
        state: PaykitAllowanceLocalState,
    ) = synchronized(publishLock) {
        val entries = allowances
            .groupBy { state.group(containing = it.allowanceId)?.id ?: it.allowanceId }
            .map { (id, members) ->
                PaykitAllowanceEntry(
                    id = id,
                    allowances = members,
                    limits = state.groups.firstOrNull { it.id == id }?.limits,
                )
            }
        val paid = state.journal.filter { it.isAutomatic && it.stage == PaykitAllowanceLocalState.Stage.SUCCEEDED }
        val paidByAllowance = paid.groupBy { it.allowanceId }.mapValues { (_, payments) ->
            payments.fold(0uL) { total, payment -> total.safe() + payment.amountSats.safe() }
        }
        _allowances.update { allowances }
        _localState.update { state }
        _autoPaidRequestIds.update { paid.mapTo(mutableSetOf()) { it.requestId } }
        _entries.update { entries }
        _autoPaidSats.update {
            entries.associate { entry ->
                entry.id to entry.allowances.fold(0uL) { total, allowance ->
                    total.safe() + (paidByAllowance[allowance.allowanceId] ?: 0uL).safe()
                }
            }
        }
    }
}
