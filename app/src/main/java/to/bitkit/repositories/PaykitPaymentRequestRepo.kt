@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.OutboundPrivateCounterpartySendReport
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PrivateStreamCounterpartyIntakeReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
    val paymentReference: String,
    val expiresAt: Instant?,
    val acceptedPaymentEndpointIdentifiers: List<String>,
    val metadata: String,
) {
    val id: PaykitPaymentRequestId
        get() = PaykitPaymentRequestId(paymentRequestId, counterparty, counterpartyReceiverPath)

    fun isExpired(now: Instant): Boolean = expiresAt?.let { it <= now } == true
}

sealed class PaykitPaymentRequestError(message: String) : AppError(message) {
    data object RequestUnavailable : PaykitPaymentRequestError("Payment request is unavailable")
    data object RequestExpired : PaykitPaymentRequestError("Payment request has expired")
}

@Singleton
class PaykitPaymentRequestRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "PaykitPaymentRequestRepo"
    }

    private val operationMutex = Mutex()
    private val stateGeneration = AtomicLong()
    private val repoScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var expirationJob: Job? = null
    private val _pendingRequests = MutableStateFlow<List<PaykitPaymentRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PaykitPaymentRequest>> = _pendingRequests.asStateFlow()

    suspend fun refresh(): Result<Unit> {
        val generation = stateGeneration.get()
        return withContext(ioDispatcher) {
            runSuspendCatching {
                operationMutex.withLock {
                    runSuspendCatching { synchronizeLocked(generation) }
                        .onFailure { discardExpiredRequestsLocked() }
                        .getOrThrow()
                }
            }.onFailure {
                Logger.warn("Failed to refresh incoming Paykit payment requests", it, context = TAG)
            }
        }
    }

    suspend fun accept(request: PaykitPaymentRequest): Result<Unit> = updateRequest(request) {
        paykitSdkService.acceptPaymentRequest(
            counterparty = it.counterparty,
            counterpartyReceiverPath = it.counterpartyReceiverPath,
            paymentRequestId = it.paymentRequestId,
        )
    }.onFailure {
        Logger.warn("Failed to accept incoming Paykit payment request", it, context = TAG)
    }

    suspend fun clear() {
        stateGeneration.incrementAndGet()
        withContext(ioDispatcher) {
            operationMutex.withLock {
                expirationJob?.cancel()
                expirationJob = null
                _pendingRequests.update { emptyList() }
            }
        }
    }

    private suspend fun synchronizeLocked(generation: Long) {
        processPendingMessages()
        paykitSdkService.receivePrivateMessagesFromLinkedPeers().also(::logIntakeFailures)
        val now = clock.now()
        val requests = paykitSdkService.actionableReceivedPaymentRequests().mapNotNull {
            it.toPaykitPaymentRequest(now)
        }
        if (stateGeneration.get() != generation) return
        _pendingRequests.update { requests }
        scheduleExpirationLocked()
    }

    private suspend fun updateRequest(
        request: PaykitPaymentRequest,
        operation: suspend (PaykitPaymentRequest) -> Unit,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            operationMutex.withLock {
                if (request.isExpired(clock.now())) {
                    discardExpiredRequestsLocked()
                    throw PaykitPaymentRequestError.RequestExpired
                }
                val current = _pendingRequests.value.firstOrNull { it.id == request.id }
                    ?: throw PaykitPaymentRequestError.RequestUnavailable

                operation(current)
                _pendingRequests.update { requests -> requests.filterNot { it.id == current.id } }
                discardExpiredRequestsLocked()
                processPendingMessages()
            }
        }
    }

    private suspend fun processPendingMessages() {
        runSuspendCatching { paykitSdkService.processPendingPrivateMessages() }
            .onSuccess(::logOutboundFailures)
            .onFailure { Logger.warn("Failed to deliver pending Paykit private messages", it, context = TAG) }
    }

    private fun logOutboundFailures(reports: List<OutboundPrivateCounterpartySendReport>) {
        reports.forEach {
            val error = it.error ?: return@forEach
            Logger.warn(
                "Failed to deliver Paykit private messages to '${PubkyPublicKeyFormat.redacted(it.counterparty)}': " +
                    "'${error.redactedContext()}'",
                context = TAG,
            )
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

    private fun discardExpiredRequestsLocked() {
        val now = clock.now()
        _pendingRequests.update { requests -> requests.filterNot { it.isExpired(now) } }
        scheduleExpirationLocked()
    }

    private fun scheduleExpirationLocked() {
        expirationJob?.cancel()
        expirationJob = null

        val nextExpiration = _pendingRequests.value.mapNotNull { it.expiresAt }.minOrNull() ?: return
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

private val bitcoinAmountPattern = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

@Suppress("ReturnCount")
private fun PaymentRequestRecord.toPaykitPaymentRequest(now: Instant): PaykitPaymentRequest? {
    if (localRole != PaymentRequestLocalRole.PAYER || state != PaymentRequestLifecycleState.PROPOSED) return null
    val requestTerms = terms ?: return null
    if (requestTerms.recurrence != null || requestTerms.amount.asset != "btc") return null
    val amountSats = requestTerms.amount.value.toSats() ?: return null
    val endpoints = requestTerms.acceptedPaymentEndpointIdentifiers
        .filter { MethodId.fromRawValue(it) != null }
        .distinct()
    if (endpoints.isEmpty()) return null

    val expiresAt = requestTerms.proposalExpiresAt?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: return null
    }
    if (expiresAt != null && expiresAt <= now) return null

    return PaykitPaymentRequest(
        paymentRequestId = paymentRequestId,
        counterparty = counterparty,
        counterpartyReceiverPath = counterpartyReceiverPath,
        amountValue = requestTerms.amount.value,
        amountSats = amountSats,
        paymentReference = requestTerms.paymentReference.exportText(),
        expiresAt = expiresAt,
        acceptedPaymentEndpointIdentifiers = endpoints,
        metadata = requestTerms.metadata.exportText(),
    )
}

private fun String.toSats(): ULong? {
    if (!bitcoinAmountPattern.matches(this)) return null
    return runCatching {
        BigDecimal(this).movePointRight(8).toBigIntegerExact().toString().toULong()
    }.getOrNull()?.takeIf { it > 0uL }
}
