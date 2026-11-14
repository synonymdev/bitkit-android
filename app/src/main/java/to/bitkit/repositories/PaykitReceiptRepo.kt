package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.ReceiptIssuanceStatus
import com.synonym.paykit.ReceiptIssuanceView
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.data.PaykitOutboundDeliveryStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** How far the receipt for a received payment got: issued and queued, delivered to the payer, or stopped. */
enum class PaykitReceiptStatus { Sending, Sent, Failed }

/**
 * Issues a Paykit receipt for each request this wallet issued once its payment is recorded here, on any rail, and
 * sends Receipt Access to the payer. It scans what is recorded, so a payment received while the app was closed gets
 * its receipt on the next open; a receipt that failed is tried again once per launch and on [retry].
 */
@Singleton
class PaykitReceiptRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val paykitSdkService: PaykitSdkService,
    private val activityRepo: ActivityRepo,
    private val usdtPayments: Lazy<PaykitUsdtPaymentRepo>,
    private val outboundDeliveryStore: PaykitOutboundDeliveryStore,
) {
    companion object {
        private const val TAG = "PaykitReceiptRepo"
    }

    private val mutex = Mutex()

    @Volatile
    private var attemptedReceiptIds = emptySet<String>()
    private var candidates: Map<String, PaykitReceiptCandidate> = emptyMap()
    private var identity: String? = null

    private val _statuses = MutableStateFlow<Map<String, PaykitReceiptStatus>>(emptyMap())

    /** Receipt status by the payment's hash, txid or USDT transfer id, lowercase. */
    val statuses: StateFlow<Map<String, PaykitReceiptStatus>> = _statuses.asStateFlow()

    suspend fun reconcile(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { reconcileLocked() } }.onFailure {
            Logger.warn("Failed to reconcile Paykit receipts", it, context = TAG)
        }
    }

    suspend fun retry(statusKey: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val candidate = checkNotNull(candidates[statusKey.lowercase()]) { "No receipt for this payment" }
                val expectedIdentity = checkNotNull(identity) { "No Paykit identity" }
                _statuses.update { it + (candidate.receive.statusKey.lowercase() to PaykitReceiptStatus.Sending) }
                issue(candidate, expectedIdentity)
                publish(candidates.values, expectedIdentity)
            }
        }.onFailure {
            Logger.warn("Failed to retry a Paykit receipt", it, context = TAG)
            val key = statusKey.lowercase()
            _statuses.update { statuses ->
                if (statuses[key] != PaykitReceiptStatus.Sending) return@update statuses
                statuses + (key to PaykitReceiptStatus.Failed)
            }
        }
    }

    /**
     * Runs without [mutex]: the wipe holds the SDK lock, which a reconcile may be waiting for while it holds [mutex].
     * Once the wipe closes the SDK, a pending reconcile finds no identity and resets again.
     */
    suspend fun resetForWipe() {
        reset()
        outboundDeliveryStore.reset()
    }

    private fun reset() {
        attemptedReceiptIds = emptySet()
        candidates = emptyMap()
        identity = null
        _statuses.update { emptyMap() }
    }

    private suspend fun reconcileLocked() {
        val status = paykitSdkService.identityStatus(Priority.Background)
        val currentIdentity = status?.publicKey
            ?.takeIf { status.capability == PubkyIdentityCapability.PRIVATE_LINK_CAPABLE }
        if (currentIdentity == null) {
            reset()
            return
        }
        if (!PubkyPublicKeyFormat.matches(identity, currentIdentity)) reset()
        identity = currentIdentity

        val records = paykitSdkService.allPaymentRequests(currentIdentity, Priority.Background)
            .filter { it.localRole == PaymentRequestLocalRole.PAYEE }
        val hasPayments = records.any { it.paymentProofs.isNotEmpty() || !it.terms?.paymentEndpoints.isNullOrEmpty() }
        val found = if (hasPayments) {
            PaykitReceiptMatcher.candidates(
                records = records,
                lightningReceives = lightningReceives(),
                onchainReceives = onchainReceives(),
                usdtReceives = usdtReceives(currentIdentity),
                network = Env.network,
            )
        } else {
            emptyList()
        }
        candidates = found.associateBy { it.receive.statusKey.lowercase() }
        if (found.isEmpty()) {
            _statuses.update { emptyMap() }
            return
        }

        val issued = issuedViews()
        found
            .filter { issued[it.receiptId]?.status != ReceiptIssuanceStatus.ACCESS_QUEUED }
            .filter { it.receiptId !in attemptedReceiptIds }
            .forEach {
                issue(it, currentIdentity)
                attemptedReceiptIds += it.receiptId
            }
        publish(found, currentIdentity)
    }

    private suspend fun issue(candidate: PaykitReceiptCandidate, expectedIdentity: String) {
        runSuspendCatching {
            paykitSdkService.issueReceipt(candidate, expectedIdentity)
        }.onSuccess {
            Logger.info("Issued Paykit receipt '${candidate.receiptId}' with status '${it.status}'", context = TAG)
        }.onFailure {
            Logger.warn("Failed to issue Paykit receipt '${candidate.receiptId}'", it, context = TAG)
        }
    }

    private suspend fun publish(found: Collection<PaykitReceiptCandidate>, expectedIdentity: String) {
        var views = readIssuedViews() ?: return
        val undelivered = found.filter { deliveryOf(it, views, expectedIdentity) != true }
        var unreachable = emptyList<String>()
        if (undelivered.isNotEmpty()) {
            unreachable = drain(undelivered, views, expectedIdentity)
            views = readIssuedViews() ?: return
        }
        val updated = found.associate { candidate ->
            val sent = deliveryOf(candidate, views, expectedIdentity)
            val reachable = unreachable.none { PubkyPublicKeyFormat.matches(it, candidate.counterparty) }
            val status = when {
                sent == true -> PaykitReceiptStatus.Sent
                views[candidate.receiptId]?.status != ReceiptIssuanceStatus.ACCESS_QUEUED -> PaykitReceiptStatus.Failed
                sent == false -> PaykitReceiptStatus.Failed
                !reachable -> PaykitReceiptStatus.Failed
                else -> PaykitReceiptStatus.Sending
            }
            candidate.receive.statusKey.lowercase() to status
        }
        _statuses.update { updated }
    }

    private suspend fun deliveryOf(
        candidate: PaykitReceiptCandidate,
        views: Map<String, ReceiptIssuanceView>,
        identity: String,
    ): Boolean? = outboundDeliveryStore.deliveryOf(
        identity = identity,
        receiptId = candidate.receiptId,
        messageId = views[candidate.receiptId]?.outboundMessageId,
    )

    /**
     * Drains each counterparty's queue and marks its receipts sent right after, before a later drain can prune the
     * evidence. Returns the counterparties that could not be reached.
     */
    private suspend fun drain(
        found: Collection<PaykitReceiptCandidate>,
        views: Map<String, ReceiptIssuanceView>,
        expectedIdentity: String,
    ): List<String> {
        val counterparties = found.map { it.counterparty }
            .fold(emptyList<String>()) { acc, key ->
                if (acc.any { PubkyPublicKeyFormat.matches(it, key) }) acc else acc + key
            }
        return counterparties.filter { counterparty ->
            val failed = runSuspendCatching {
                paykitSdkService.processOutboundPrivateMessages(counterparty, Priority.Background, expectedIdentity)
            }.onFailure {
                Logger.warn("Failed to send Paykit receipt access", it, context = TAG)
            }.isFailure
            found.filter { PubkyPublicKeyFormat.matches(it.counterparty, counterparty) }
                .forEach { deliveryOf(it, views, expectedIdentity) }
            failed
        }
    }

    private suspend fun readIssuedViews(): Map<String, ReceiptIssuanceView>? = runSuspendCatching {
        issuedViews()
    }.onFailure {
        Logger.warn("Failed to read issued Paykit receipts", it, context = TAG)
    }.getOrNull()

    private suspend fun issuedViews(): Map<String, ReceiptIssuanceView> =
        paykitSdkService.issuedReceipts().associateBy { it.receiptId }

    private suspend fun lightningReceives(): Map<String, PaykitRecordedReceive> =
        activityRepo.getActivities(filter = ActivityFilter.LIGHTNING, txType = PaymentType.RECEIVED).getOrThrow()
            .filterIsInstance<Activity.Lightning>()
            .filter { it.v1.status == PaymentState.SUCCEEDED }
            .map {
                bitcoinReceive(
                    paymentId = it.v1.id,
                    rail = PaykitReceiptRail.Lightning,
                    sats = it.v1.value,
                    receivedAt = it.v1.timestamp,
                    contact = it.v1.contact,
                )
            }
            .associateBy { it.paymentId }

    private suspend fun onchainReceives(): List<PaykitRecordedReceive> =
        activityRepo.getActivities(filter = ActivityFilter.ONCHAIN, txType = PaymentType.RECEIVED).getOrThrow()
            .filterIsInstance<Activity.Onchain>()
            .filter { it.v1.confirmed && it.v1.doesExist }
            .map {
                bitcoinReceive(
                    paymentId = it.v1.txId,
                    rail = PaykitReceiptRail.Onchain,
                    sats = it.v1.value,
                    address = it.v1.address,
                    receivedAt = it.v1.timestamp,
                    contact = it.v1.contact,
                )
            }

    private fun usdtReceives(currentIdentity: String): List<PaykitVerifiedUsdtReceive> =
        usdtPayments.get().receipts.value
            .filter { it.verified && !it.beforeRequest && PubkyPublicKeyFormat.matches(it.identity, currentIdentity) }
            .map { PaykitVerifiedUsdtReceive(it.requestId, it.proofEventId, it.transferId, it.amount) }
}
