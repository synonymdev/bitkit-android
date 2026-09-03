@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateCounterpartySendReport
import com.synonym.paykit.OutboundPrivateMessageStatus
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import com.synonym.paykit.PrivateStreamCounterpartyIntakeReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.bitkit.async.appScope
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.satsToMsat
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class PaykitPaymentRequestId(
    val paymentRequestId: String,
    val counterparty: String,
    val counterpartyReceiverPath: String,
)

data class PaykitPaymentRequest(
    val paymentRequestId: String,
    val counterparty: String,
    val counterpartyReceiverPath: String,
    val amountValue: String,
    val amountSats: ULong,
    val note: String? = null,
    val createdAt: Instant? = null,
    val expiresAt: Instant?,
    val acceptedPaymentEndpointIdentifiers: List<String>,
    val deliveryStatus: PaykitPaymentRequestDeliveryStatus? = null,
    val direction: PaykitPaymentRequestDirection = PaykitPaymentRequestDirection.Incoming,
    val lifecycleState: PaymentRequestLifecycleState = PaymentRequestLifecycleState.PROPOSED,
) {
    enum class ParseFailure(
        val logValue: String,
        val shouldLogIncomingRejection: Boolean = true,
    ) {
        MissingLocalRole("missing_local_role"),
        OutgoingRequest("outgoing_request", shouldLogIncomingRejection = false),
        UnsupportedLocalRole("unsupported_local_role"),
        NonActionableState("non_actionable_state", shouldLogIncomingRejection = false),
        MissingTerms("missing_terms"),
        RecurringRequest("recurring_request"),
        UnsupportedAsset("unsupported_asset"),
        InvalidAmount("invalid_amount"),
        AmountOutOfRange("amount_out_of_range"),
        NoSupportedEndpoint("no_supported_endpoint"),
        InvalidExpiration("invalid_expiration"),
        Expired("expired"),
    }

    val id: PaykitPaymentRequestId
        get() = PaykitPaymentRequestId(paymentRequestId, counterparty, counterpartyReceiverPath)

    fun isExpired(now: Instant): Boolean = expiresAt?.let { it <= now } == true

    fun acceptsLightningInvoiceAmountMsats(amountMsats: ULong?): Boolean =
        amountMsats == null || amountMsats == satsToMsat(amountSats)

    fun acceptsLightningInvoiceAmountSats(amountSats: ULong): Boolean =
        amountSats == 0uL || acceptsPaymentAmount(amountSats)

    fun acceptsPaymentAmount(amountSats: ULong): Boolean = amountSats == this.amountSats
}

internal sealed interface PaykitPaymentRequestParseResult {
    data class Parsed(val request: PaykitPaymentRequest) : PaykitPaymentRequestParseResult
    data class Rejected(val reason: PaykitPaymentRequest.ParseFailure) : PaykitPaymentRequestParseResult
}

@Singleton
class PaykitPaymentRequestDiagnostics @Inject constructor() {
    companion object {
        private const val TAG = "PaykitPaymentRequestDiagnostics"
    }

    internal fun logParseRejection(
        counterparty: String,
        reason: PaykitPaymentRequest.ParseFailure,
    ) {
        Logger.warn(
            "Rejected incoming Paykit payment request: category='parse' reason='${reason.logValue}' " +
                "counterparty='${counterparty.redactedForPaymentRequestDiagnostics()}'",
            context = TAG,
        )
    }

    internal fun logPresentationRejection(
        counterparty: String,
        reason: IncomingPaykitPaymentRequestFailureReason,
    ) {
        Logger.warn(
            "Rejected incoming Paykit payment request presentation: category='${reason.category}' " +
                "reason='${reason.logValue}' " +
                "counterparty='${counterparty.redactedForPaymentRequestDiagnostics()}'",
            context = TAG,
        )
    }
}

private fun String.redactedForPaymentRequestDiagnostics(): String =
    PubkyPublicKeyFormat.normalized(this)?.let(PubkyPublicKeyFormat::redacted) ?: "<invalid>"

private data class ParsedPaykitPaymentRequestTerms(
    val terms: PaymentRequestTerms,
    val amountSats: ULong,
    val endpoints: List<String>,
    val expiresAt: Instant?,
)

enum class PaykitPaymentRequestDeliveryStatus { Queued, Sent }

enum class PaykitPaymentRequestDirection { Incoming, Outgoing }

data class PaykitPaymentRequestTarget(
    val publicKey: String,
    val receiverPath: String,
)

data class PaykitPaymentRequestDraft(
    val amountSats: ULong,
    val note: String,
    val expiresAt: Instant,
)

data class PaykitPaymentRequestCreation(
    val request: PaykitPaymentRequest,
    val creatorIdentity: String,
    val wasPublishedToActiveState: Boolean,
)

private data class PaykitPaymentRequestTargetContext(
    val savedPublicKeys: List<String>,
    val linkedReceiverPaths: Map<String, Set<String>>,
)

private data class PaykitPaymentRequestTargetDiscovery(
    val targets: List<PaykitPaymentRequestTarget>,
    val isComplete: Boolean,
)

sealed class PaykitPaymentRequestError(message: String) : AppError(message) {
    data object RequestUnavailable : PaykitPaymentRequestError("Payment request is unavailable")
    data object RequestExpired : PaykitPaymentRequestError("Payment request has expired")
    data object OperationInProgress : PaykitPaymentRequestError("Payment request operation is already in progress")
}

@Suppress("TooManyFunctions")
@Singleton
class PaykitPaymentRequestRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val settingsStore: SettingsStore,
    private val presentationStore: PaykitPaymentRequestPresentationStore,
    private val diagnostics: PaykitPaymentRequestDiagnostics,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PaykitPaymentRequestRepo"
        private val TARGET_DISCOVERY_TIMEOUT = 5.seconds
    }

    private val operationMutex = Mutex()
    private val targetDiscoveryMutex = Mutex()
    private val creationMutex = Mutex()
    private val processingLock = Any()
    private val processingRequestIds = mutableSetOf<PaykitPaymentRequestId>()
    private val stateGeneration = AtomicLong()
    private val repoScope = appScope(ioDispatcher, TAG)
    private var expirationJob: Job? = null
    private var cachedTargetContext: PaykitPaymentRequestTargetContext? = null
    private val _pendingRequests = MutableStateFlow<List<PaykitPaymentRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PaykitPaymentRequest>> = _pendingRequests.asStateFlow()
    private val _paymentRequestHistory = MutableStateFlow<List<PaykitPaymentRequest>>(emptyList())
    val paymentRequestHistory: StateFlow<List<PaykitPaymentRequest>> = _paymentRequestHistory.asStateFlow()
    private val _eligibleTargets = MutableStateFlow<List<PaykitPaymentRequestTarget>>(emptyList())
    val eligibleTargets: StateFlow<List<PaykitPaymentRequestTarget>> = _eligibleTargets.asStateFlow()
    private val _isCreatingRequest = MutableStateFlow(false)
    val isCreatingRequest: StateFlow<Boolean> = _isCreatingRequest.asStateFlow()

    @Volatile
    private var activeIdentity: String? = null

    @Volatile
    private var presentedRequestIds = emptySet<PaykitPaymentRequestId>()

    suspend fun activate(identity: String) = withContext(ioDispatcher) {
        val normalizedIdentity = PubkyPublicKeyFormat.normalized(identity) ?: return@withContext
        if (!PubkyPublicKeyFormat.matches(activeIdentity, normalizedIdentity)) {
            stateGeneration.incrementAndGet()
        }
        operationMutex.withLock {
            if (PubkyPublicKeyFormat.matches(activeIdentity, normalizedIdentity)) return@withLock
            clearStateLocked()
            activeIdentity = normalizedIdentity
            presentedRequestIds = runSuspendCatching { presentationStore.load(normalizedIdentity) }
                .onFailure { Logger.warn("Failed to restore surfaced Paykit payment requests", it, context = TAG) }
                .getOrDefault(emptySet())
        }
    }

    fun automaticPendingRequests(): List<PaykitPaymentRequest> =
        _pendingRequests.value.filterNot { it.id in presentedRequestIds }

    fun pendingRequest(id: PaykitPaymentRequestId): PaykitPaymentRequest? =
        _pendingRequests.value.firstOrNull { it.id == id }

    suspend fun markPresented(request: PaykitPaymentRequest): Boolean = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (_pendingRequests.value.none { it.id == request.id }) return@withLock false
            val identity = activeIdentity ?: return@withLock false
            presentedRequestIds = presentedRequestIds + request.id
            runSuspendCatching { presentationStore.save(identity, presentedRequestIds) }
                .onFailure { Logger.warn("Failed to persist surfaced Paykit payment requests", it, context = TAG) }
            true
        }
    }

    suspend fun refresh(): Result<Unit> {
        val generation = stateGeneration.get()
        val expectedIdentity = activeIdentity
        return withContext(ioDispatcher) {
            runSuspendCatching {
                operationMutex.withLock {
                    if (!isAvailable()) {
                        clearStateLocked()
                        return@withLock
                    }
                    runSuspendCatching { synchronizeLocked(generation, expectedIdentity) }
                        .onFailure { discardExpiredRequestsLocked() }
                        .getOrThrow()
                }
            }.onFailure {
                Logger.warn("Failed to refresh incoming Paykit payment requests", it, context = TAG)
            }
        }
    }

    suspend fun refreshEligibleTargets(
        savedPublicKeys: List<String>,
        force: Boolean = false,
    ): Result<Unit> {
        val generation = stateGeneration.get()
        val expectedIdentity = activeIdentity
        return withContext(ioDispatcher) {
            runSuspendCatching {
                targetDiscoveryMutex.withLock discovery@{
                    if (!isAvailable() || expectedIdentity == null) {
                        operationMutex.withLock operation@{
                            if (stateGeneration.get() != generation || activeIdentity != expectedIdentity) {
                                return@operation
                            }
                            cachedTargetContext = null
                            _eligibleTargets.update { emptyList() }
                        }
                        return@discovery
                    }
                    val context = targetContext(savedPublicKeys, expectedIdentity)
                    if (!force && context == cachedTargetContext) return@discovery
                    val previousTargets = _eligibleTargets.value.associateBy { it.publicKey }
                    val discovery = context?.let { eligibleTargets(it, previousTargets) }
                        ?: PaykitPaymentRequestTargetDiscovery(emptyList(), isComplete = true)
                    operationMutex.withLock operation@{
                        if (!isCurrentState(generation, expectedIdentity)) return@operation
                        cachedTargetContext = context.takeIf { discovery.isComplete }
                        _eligibleTargets.update { discovery.targets }
                    }
                }
            }.onFailure {
                Logger.warn("Failed to refresh Paykit payment request recipients", it, context = TAG)
            }
        }
    }

    suspend fun propose(
        draft: PaykitPaymentRequestDraft,
        target: PaykitPaymentRequestTarget,
        savedPublicKeys: List<String>,
    ): Result<PaykitPaymentRequestCreation> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!creationMutex.tryLock()) throw PaykitPaymentRequestError.OperationInProgress
            _isCreatingRequest.update { true }
            try {
                operationMutex.withLock {
                    val generation = stateGeneration.get()
                    val expectedIdentity = activeIdentity ?: throw PaykitPaymentRequestError.RequestUnavailable
                    val proposalDate = clock.now()
                    if (draft.amountSats == 0uL || !isAvailable()) throw PaykitPaymentRequestError.RequestUnavailable
                    if (draft.expiresAt <= proposalDate) throw PaykitPaymentRequestError.RequestExpired

                    val selectedSavedKey = savedPublicKeys.firstOrNull {
                        PubkyPublicKeyFormat.matches(it, target.publicKey)
                    }
                    val targets = selectedSavedKey?.let {
                        targetContext(listOf(it), expectedIdentity)
                    }?.let { eligibleTargets(it).targets }.orEmpty()
                    if (target !in targets) throw PaykitPaymentRequestError.RequestUnavailable
                    val settings = settingsStore.data.first()
                    if (!settings.sharesPrivatePaykitEndpoints) throw PaykitPaymentRequestError.RequestUnavailable
                    val endpointIdentifiers = acceptedPaymentEndpointIdentifiers(settings)
                    if (endpointIdentifiers.isEmpty()) throw PaykitPaymentRequestError.RequestUnavailable

                    val note = draft.note.trim().take(256)
                    val proposal = PaykitPaymentRequestProposalTerms(
                        amountValue = draft.amountSats.toBitcoinAmount(),
                        paymentReference = "bitkit-${UUID.randomUUID()}",
                        proposalExpiresAt = draft.expiresAt.toString(),
                        acceptedPaymentEndpointIdentifiers = endpointIdentifiers,
                        metadataJson = JsonObject(mapOf("note" to JsonPrimitive(note))).toString(),
                    )
                    val record = paykitSdkService.proposePaymentRequest(
                        counterparty = target.publicKey,
                        counterpartyReceiverPath = target.receiverPath,
                        proposal = proposal,
                        expectedIdentity = expectedIdentity,
                    )
                    val reports = processPendingMessages()

                    val request = record.toCreatedPaykitPaymentRequest(
                        draft = draft.copy(note = note),
                        target = target,
                        endpointIdentifiers = endpointIdentifiers,
                        createdAt = proposalDate,
                        reports = reports,
                    )
                    publishCreatedRequest(request, generation, expectedIdentity)
                }
            } finally {
                _isCreatingRequest.update { false }
                creationMutex.unlock()
            }
        }.onFailure {
            Logger.warn("Failed to create Paykit payment request", it, context = TAG)
        }
    }

    private fun publishCreatedRequest(
        request: PaykitPaymentRequest,
        generation: Long,
        creatorIdentity: String,
    ): PaykitPaymentRequestCreation {
        val wasPublishedToActiveState = isCurrentState(generation, creatorIdentity)
        if (wasPublishedToActiveState) {
            _paymentRequestHistory.update { requests ->
                listOf(request) + requests.filterNot { it.id == request.id }
            }
            scheduleExpirationLocked()
        }
        return PaykitPaymentRequestCreation(request, creatorIdentity, wasPublishedToActiveState)
    }

    suspend fun accept(request: PaykitPaymentRequest): Result<Unit> = updateRequest(
        request = request,
        resultingState = PaymentRequestLifecycleState.ACCEPTED,
    ) {
        paykitSdkService.acceptPaymentRequest(
            counterparty = it.counterparty,
            counterpartyReceiverPath = it.counterpartyReceiverPath,
            paymentRequestId = it.paymentRequestId,
        )
    }.onFailure {
        Logger.warn("Failed to accept incoming Paykit payment request", it, context = TAG)
    }

    suspend fun reject(request: PaykitPaymentRequest): Result<Unit> = updateRequest(
        request = request,
        resultingState = PaymentRequestLifecycleState.REJECTED,
    ) {
        paykitSdkService.rejectPaymentRequest(
            counterparty = it.counterparty,
            counterpartyReceiverPath = it.counterpartyReceiverPath,
            paymentRequestId = it.paymentRequestId,
        )
    }.onFailure {
        Logger.warn("Failed to reject incoming Paykit payment request", it, context = TAG)
    }

    fun isPending(request: PaykitPaymentRequest): Boolean =
        !request.isExpired(clock.now()) && _pendingRequests.value.any { it.id == request.id }

    fun isProcessing(request: PaykitPaymentRequest): Boolean = synchronized(processingLock) {
        request.id in processingRequestIds
    }

    suspend fun clear() {
        stateGeneration.incrementAndGet()
        withContext(ioDispatcher) {
            operationMutex.withLock {
                expirationJob?.cancel()
                expirationJob = null
                clearStateLocked()
                activeIdentity = null
                presentedRequestIds = emptySet()
            }
        }
    }

    private suspend fun synchronizeLocked(
        generation: Long,
        expectedIdentity: String?,
    ) {
        processPendingMessages()
        paykitSdkService.receivePrivateMessagesFromLinkedPeers().also(::logIntakeFailures)
        val now = clock.now()
        val records = paykitSdkService.paymentRequests()
        val incoming = records.mapNotNull { record ->
            when (val result = record.parseIncomingPaykitPaymentRequest(now)) {
                is PaykitPaymentRequestParseResult.Parsed -> result.request
                is PaykitPaymentRequestParseResult.Rejected -> {
                    if (result.reason.shouldLogIncomingRejection) {
                        diagnostics.logParseRejection(record.counterparty, result.reason)
                    }
                    null
                }
            }
        }
        val history = records.mapNotNull { it.toPaykitPaymentRequestHistory(now) }
            .sortedByDescending { it.createdAt }
        if (
            stateGeneration.get() != generation ||
            !PubkyPublicKeyFormat.matches(activeIdentity, expectedIdentity)
        ) {
            return
        }
        _pendingRequests.update { incoming }
        _paymentRequestHistory.update { history }
        prunePresentedRequestIds(incoming)
        scheduleExpirationLocked()
    }

    private fun isCurrentState(generation: Long, expectedIdentity: String?): Boolean =
        stateGeneration.get() == generation && PubkyPublicKeyFormat.matches(activeIdentity, expectedIdentity)

    private suspend fun targetContext(
        savedPublicKeys: List<String>,
        expectedIdentity: String,
    ): PaykitPaymentRequestTargetContext? {
        val settings = settingsStore.data.first()
        val endpointIdentifiers = acceptedPaymentEndpointIdentifiers(settings)
        if (!settings.sharesPrivatePaykitEndpoints || endpointIdentifiers.isEmpty()) return null
        val savedKeys = savedPublicKeys.mapNotNull(PubkyPublicKeyFormat::normalized).distinct()
        val identityStatus = paykitSdkService.identityStatus()
        if (
            savedKeys.isEmpty() ||
            identityStatus?.liveSessionAvailable != true ||
            !PubkyPublicKeyFormat.matches(identityStatus.publicKey, expectedIdentity)
        ) {
            return null
        }
        val linkedPaths = mutableMapOf<String, MutableSet<String>>()
        paykitSdkService.linkedPeers()
            .filter { it.state == LinkedPeerState.LINKED }
            .forEach { peer ->
                val publicKey = PubkyPublicKeyFormat.normalized(peer.counterparty) ?: return@forEach
                if (peer.counterpartyReceiverPath in PaykitReceiverPaths.supported) {
                    linkedPaths.getOrPut(publicKey, ::mutableSetOf) += peer.counterpartyReceiverPath
                }
            }

        return PaykitPaymentRequestTargetContext(
            savedPublicKeys = savedKeys,
            linkedReceiverPaths = linkedPaths.mapValues { it.value.toSet() },
        )
    }

    private suspend fun eligibleTargets(
        context: PaykitPaymentRequestTargetContext,
        previousTargets: Map<String, PaykitPaymentRequestTarget> = emptyMap(),
    ): PaykitPaymentRequestTargetDiscovery {
        var isComplete = true
        val targets = context.savedPublicKeys.mapNotNull { publicKey ->
            val linked = context.linkedReceiverPaths[publicKey] ?: return@mapNotNull null
            val lookup = withTimeoutOrNull(TARGET_DISCOVERY_TIMEOUT) {
                runSuspendCatching { paykitSdkService.paymentRequestReceiverPaths(publicKey) }
            }
            if (lookup == null) {
                isComplete = false
                Logger.warn(
                    "Timed out inspecting payment request support for '${PubkyPublicKeyFormat.redacted(publicKey)}'",
                    context = TAG,
                )
                return@mapNotNull previousTargets[publicKey]?.takeIf { it.receiverPath in linked }
            }
            val capable = lookup
                .onFailure {
                    isComplete = false
                    Logger.warn(
                        "Failed to inspect payment request support for '${PubkyPublicKeyFormat.redacted(publicKey)}'",
                        it,
                        context = TAG,
                    )
                }
                .getOrElse {
                    return@mapNotNull previousTargets[publicKey]?.takeIf { it.receiverPath in linked }
                }
            val receiverPath = PaykitReceiverPaths.ordered.firstOrNull { it in linked && it in capable }
                ?: run {
                    isComplete = false
                    return@mapNotNull null
                }
            PaykitPaymentRequestTarget(publicKey, receiverPath)
        }
        return PaykitPaymentRequestTargetDiscovery(
            targets = targets,
            isComplete = isComplete,
        )
    }

    private fun acceptedPaymentEndpointIdentifiers(settings: SettingsData): List<String> = buildList {
        if (PublicPaykitRepo.isLightningPaymentOptionEnabled(settings)) add(MethodId.Bolt11.rawValue)
        if (PublicPaykitRepo.isOnchainPaymentOptionEnabled(settings)) {
            addAll(MethodId.entries.filter { it.isOnchain }.map(MethodId::rawValue))
        }
    }

    private suspend fun isAvailable(): Boolean = activeIdentity != null &&
        PaykitFeatureFlags.isUiEnabled(settingsStore.isPaykitEnabled.first())

    private suspend fun updateRequest(
        request: PaykitPaymentRequest,
        resultingState: PaymentRequestLifecycleState,
        operation: suspend (PaykitPaymentRequest) -> Unit,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val isNewAction = synchronized(processingLock) { processingRequestIds.add(request.id) }
            if (!isNewAction) throw PaykitPaymentRequestError.OperationInProgress
            try {
                operationMutex.withLock {
                    if (request.isExpired(clock.now())) {
                        discardExpiredRequestsLocked()
                        throw PaykitPaymentRequestError.RequestExpired
                    }
                    val current = _pendingRequests.value.firstOrNull { it.id == request.id }
                        ?: throw PaykitPaymentRequestError.RequestUnavailable

                    operation(current)
                    processPendingMessages()
                    val updatedRequest = current.copy(lifecycleState = resultingState)
                    _paymentRequestHistory.update { requests ->
                        listOf(updatedRequest) + requests.filterNot { it.id == current.id }
                    }
                    _pendingRequests.update { requests -> requests.filterNot { it.id == current.id } }
                    discardExpiredRequestsLocked()
                    Unit
                }
            } finally {
                synchronized(processingLock) { processingRequestIds.remove(request.id) }
            }
        }
    }

    private suspend fun processPendingMessages(): List<OutboundPrivateCounterpartySendReport> =
        runSuspendCatching { paykitSdkService.processPendingPrivateMessages() }
            .onSuccess(::logOutboundFailures)
            .onFailure { Logger.warn("Failed to deliver pending Paykit private messages", it, context = TAG) }
            .getOrDefault(emptyList())

    private fun logOutboundFailures(reports: List<OutboundPrivateCounterpartySendReport>) {
        reports.forEach { report ->
            report.error?.let {
                Logger.warn(
                    "Failed to deliver Paykit private messages to " +
                        "'${PubkyPublicKeyFormat.redacted(report.counterparty)}': '${it.redactedContext()}'",
                    context = TAG,
                )
            }
            report.report?.failed.orEmpty().forEach {
                Logger.warn(
                    "Failed to deliver Paykit private message '${it.outboundMessageId}' to " +
                        "'${PubkyPublicKeyFormat.redacted(report.counterparty)}': '${it.error.redactedContext()}'",
                    context = TAG,
                )
            }
        }
    }

    private fun logIntakeFailures(reports: List<PrivateStreamCounterpartyIntakeReport>) {
        reports.forEach {
            val error = it.error ?: return@forEach
            Logger.warn(
                "Failed to receive Paykit private messages from '${PubkyPublicKeyFormat.redacted(it.counterparty)}': " +
                    "'${error.redactedContext()}'",
                context = TAG,
            )
        }
    }

    private suspend fun discardExpiredRequestsLocked() {
        val now = clock.now()
        _pendingRequests.update { requests -> requests.filterNot { it.isExpired(now) } }
        _paymentRequestHistory.update { requests -> requests.withExpiredLifecycle(now) }
        prunePresentedRequestIds(_pendingRequests.value)
        scheduleExpirationLocked()
    }

    private suspend fun prunePresentedRequestIds(requests: List<PaykitPaymentRequest>) {
        val requestIds = requests.mapTo(mutableSetOf()) { it.id }
        val prunedIds = presentedRequestIds.intersect(requestIds)
        if (prunedIds == presentedRequestIds) return
        presentedRequestIds = prunedIds
        val identity = activeIdentity ?: return
        runSuspendCatching { presentationStore.save(identity, prunedIds) }
            .onFailure { Logger.warn("Failed to persist surfaced Paykit payment requests", it, context = TAG) }
    }

    private fun clearStateLocked() {
        expirationJob?.cancel()
        expirationJob = null
        _pendingRequests.update { emptyList() }
        _paymentRequestHistory.update { emptyList() }
        _eligibleTargets.update { emptyList() }
        cachedTargetContext = null
    }

    private fun scheduleExpirationLocked() {
        expirationJob?.cancel()
        expirationJob = null

        val nextExpiration = (_pendingRequests.value + _paymentRequestHistory.value)
            .asSequence()
            .filter { it.lifecycleState == PaymentRequestLifecycleState.PROPOSED }
            .mapNotNull { it.expiresAt }
            .minOrNull()
            ?: return
        val delayDuration = (nextExpiration - clock.now()).coerceAtLeast(Duration.ZERO)
        expirationJob = repoScope.launch {
            delay(delayDuration)
            operationMutex.withLock {
                expirationJob = null
                discardExpiredRequestsLocked()
            }
        }
    }
}

private fun List<PaykitPaymentRequest>.withExpiredLifecycle(now: Instant): List<PaykitPaymentRequest> = map { request ->
    if (request.lifecycleState == PaymentRequestLifecycleState.PROPOSED && request.isExpired(now)) {
        request.copy(lifecycleState = PaymentRequestLifecycleState.PROPOSAL_EXPIRED)
    } else {
        request
    }
}

private val bitcoinAmountPattern = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

internal fun PaymentRequestRecord.parseIncomingPaykitPaymentRequest(
    now: Instant,
): PaykitPaymentRequestParseResult = parsePaykitPaymentRequest(
    expectedRole = PaymentRequestLocalRole.PAYER,
    now = now,
    requiresActionableRequest = true,
)

@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun PaymentRequestRecord.parsePaykitPaymentRequest(
    expectedRole: PaymentRequestLocalRole,
    now: Instant,
    requiresActionableRequest: Boolean = true,
): PaykitPaymentRequestParseResult {
    val role = localRole
        ?: return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.MissingLocalRole)
    if (role != expectedRole) {
        return PaykitPaymentRequestParseResult.Rejected(
            if (expectedRole == PaymentRequestLocalRole.PAYER && role == PaymentRequestLocalRole.PAYEE) {
                PaykitPaymentRequest.ParseFailure.OutgoingRequest
            } else {
                PaykitPaymentRequest.ParseFailure.UnsupportedLocalRole
            },
        )
    }
    if (requiresActionableRequest && state != PaymentRequestLifecycleState.PROPOSED) {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.NonActionableState)
    }
    if (state == PaymentRequestLifecycleState.ACTIVE_RECURRING) {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.RecurringRequest)
    }
    val requestTerms = terms
        ?: return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.MissingTerms)
    if (requestTerms.recurrence != null) {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.RecurringRequest)
    }
    if (requestTerms.amount.asset != "btc") {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.UnsupportedAsset)
    }
    val amountSats = requestTerms.amount.value.toSats()
        ?: return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.InvalidAmount)
    if (amountSats > ULong.MAX_VALUE / 1000uL) {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.AmountOutOfRange)
    }
    val endpoints = requestTerms.acceptedPaymentEndpointIdentifiers
        .filter { MethodId.fromRawValue(it) != null }
        .distinct()
    if (requiresActionableRequest && endpoints.isEmpty()) {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.NoSupportedEndpoint)
    }

    val expiresAt = requestTerms.proposalExpiresAt?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.InvalidExpiration)
    }
    if (requiresActionableRequest && expiresAt != null && expiresAt <= now) {
        return PaykitPaymentRequestParseResult.Rejected(PaykitPaymentRequest.ParseFailure.Expired)
    }

    val parsedTerms = ParsedPaykitPaymentRequestTerms(requestTerms, amountSats, endpoints, expiresAt)
    return PaykitPaymentRequestParseResult.Parsed(toPaykitPaymentRequest(expectedRole, parsedTerms, now))
}

private fun PaymentRequestRecord.toPaykitPaymentRequest(
    expectedRole: PaymentRequestLocalRole,
    parsedTerms: ParsedPaykitPaymentRequestTerms,
    now: Instant,
) = PaykitPaymentRequest(
    paymentRequestId = paymentRequestId,
    counterparty = counterparty,
    counterpartyReceiverPath = counterpartyReceiverPath,
    amountValue = parsedTerms.terms.amount.value,
    amountSats = parsedTerms.amountSats,
    note = parsedTerms.terms.metadata.note(),
    createdAt = lastEventAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
    expiresAt = parsedTerms.expiresAt,
    acceptedPaymentEndpointIdentifiers = parsedTerms.endpoints,
    deliveryStatus = if (expectedRole == PaymentRequestLocalRole.PAYEE) {
        if (proposalOutboundStatus == OutboundPrivateMessageStatus.SENT) {
            PaykitPaymentRequestDeliveryStatus.Sent
        } else {
            PaykitPaymentRequestDeliveryStatus.Queued
        }
    } else {
        null
    },
    direction = if (expectedRole == PaymentRequestLocalRole.PAYER) {
        PaykitPaymentRequestDirection.Incoming
    } else {
        PaykitPaymentRequestDirection.Outgoing
    },
    lifecycleState = if (
        state == PaymentRequestLifecycleState.PROPOSED && parsedTerms.expiresAt?.let { it <= now } == true
    ) {
        PaymentRequestLifecycleState.PROPOSAL_EXPIRED
    } else {
        state
    },
)

private fun PaymentRequestRecord.toPaykitPaymentRequestHistory(now: Instant): PaykitPaymentRequest? {
    val role = localRole ?: return null
    if (role == PaymentRequestLocalRole.UNKNOWN) return null
    return when (val result = parsePaykitPaymentRequest(role, now, requiresActionableRequest = false)) {
        is PaykitPaymentRequestParseResult.Parsed -> result.request
        is PaykitPaymentRequestParseResult.Rejected -> null
    }
}

private fun PaymentRequestRecord.toCreatedPaykitPaymentRequest(
    draft: PaykitPaymentRequestDraft,
    target: PaykitPaymentRequestTarget,
    endpointIdentifiers: List<String>,
    createdAt: Instant,
    reports: List<OutboundPrivateCounterpartySendReport>,
): PaykitPaymentRequest {
    val wasSent = proposalOutboundMessageId?.let { messageId ->
        reports.any { report ->
            PubkyPublicKeyFormat.matches(report.counterparty, counterparty) &&
                report.counterpartyReceiverPath == counterpartyReceiverPath &&
                messageId in report.report?.sent.orEmpty()
        }
    } == true
    return PaykitPaymentRequest(
        paymentRequestId = paymentRequestId,
        counterparty = target.publicKey,
        counterpartyReceiverPath = target.receiverPath,
        amountValue = draft.amountSats.toBitcoinAmount(),
        amountSats = draft.amountSats,
        note = draft.note.takeIf(String::isNotBlank),
        createdAt = lastEventAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: createdAt,
        expiresAt = draft.expiresAt,
        acceptedPaymentEndpointIdentifiers = endpointIdentifiers,
        deliveryStatus = if (wasSent) {
            PaykitPaymentRequestDeliveryStatus.Sent
        } else {
            PaykitPaymentRequestDeliveryStatus.Queued
        },
        direction = PaykitPaymentRequestDirection.Outgoing,
        lifecycleState = PaymentRequestLifecycleState.PROPOSED,
    )
}

private fun PrivateJsonObject.note(): String? = runCatching {
    Json.parseToJsonElement(exportText())
        .jsonObject["note"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}.getOrNull()

private fun ULong.toBitcoinAmount(): String =
    BigDecimal(toString()).movePointLeft(8).stripTrailingZeros().toPlainString()

private fun String.toSats(): ULong? {
    if (!bitcoinAmountPattern.matches(this)) return null
    return runCatching {
        BigDecimal(this).movePointRight(8).toBigIntegerExact().toString().toULong()
    }.getOrNull()?.takeIf { it > 0uL }
}
