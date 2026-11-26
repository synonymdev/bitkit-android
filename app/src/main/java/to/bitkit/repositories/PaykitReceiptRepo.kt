package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/** How far the receipt for a received payment got: issued and queued, delivered to the payer, or stopped. */
enum class PaykitReceiptStatus {
    Sending, Sent, Failed;

    companion object {
        /** The status a payment shows when it carries several receipts: any failure, else any still sending. */
        fun combined(first: PaykitReceiptStatus?, second: PaykitReceiptStatus): PaykitReceiptStatus = when {
            first == null -> second
            first == Failed || second == Failed -> Failed
            first == Sending || second == Sending -> Sending
            else -> Sent
        }
    }
}

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
    private var candidates: Map<String, List<PaykitReceiptCandidate>> = emptyMap()
    private var identity: String? = null

    /** Confirmed transactions do not change, so their outputs are read once per launch. */
    private var outputsByTxid: Map<String, Map<String, ULong>> = emptyMap()

    private val _statuses = MutableStateFlow<Map<String, PaykitReceiptStatus>>(emptyMap())

    /** Receipt status by the payment's hash, txid or USDT transfer id, lowercase. */
    val statuses: StateFlow<Map<String, PaykitReceiptStatus>> = _statuses.asStateFlow()

    /** Set while a pass waits for the lock; calls meanwhile join it, since it reads the latest state when it runs. */
    private val isPassQueued = AtomicBoolean(false)

    /** Bumped by [resetForWipe]; a pass that started before the wipe writes nothing after it. */
    private val wipeGeneration = AtomicLong(0)

    /** Orders a pass's sent-id write against the wipe's reset; it never waits on the SDK lock, unlike [mutex]. */
    private val deliveryStoreMutex = Mutex()

    suspend fun reconcile(): Result<Unit> = withContext(ioDispatcher) {
        if (!isPassQueued.compareAndSet(false, true)) return@withContext Result.success(Unit)
        var started = false
        runSuspendCatching {
            try {
                mutex.withLock {
                    started = true
                    isPassQueued.set(false)
                    reconcileLocked()
                }
            } finally {
                if (!started) isPassQueued.set(false)
            }
        }.onFailure {
            Logger.warn("Failed to reconcile Paykit receipts", it, context = TAG)
        }
    }

    suspend fun retry(statusKey: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                val generation = wipeGeneration.get()
                val key = statusKey.lowercase()
                val receipts = checkNotNull(candidates[key]) { "No receipt for this payment" }
                val expectedIdentity = checkNotNull(identity) { "No Paykit identity" }
                _statuses.update { it + (key to PaykitReceiptStatus.Sending) }
                receipts.forEach { issue(it, expectedIdentity) }
                check(publish(candidates.values.flatten(), expectedIdentity, generation)) {
                    "Could not read issued receipts"
                }
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
        wipeGeneration.incrementAndGet()
        reset()
        deliveryStoreMutex.withLock { outboundDeliveryStore.reset() }
    }

    private fun reset() {
        attemptedReceiptIds = emptySet()
        outputsByTxid = emptyMap()
        candidates = emptyMap()
        identity = null
        _statuses.update { emptyMap() }
    }

    private suspend fun reconcileLocked() {
        val generation = wipeGeneration.get()
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
                onchainReceives = onchainReceives(records),
                usdtReceives = usdtReceives(currentIdentity),
                network = Env.network,
            )
        } else {
            emptyList()
        }
        candidates = found.groupBy { it.receive.statusKey.lowercase() }
        if (found.isEmpty()) {
            _statuses.update { emptyMap() }
            return
        }

        val issued = issuedViews()
        found
            .filter { issued[it.receiptId]?.status.needsIssuing() }
            .filter { it.receiptId !in attemptedReceiptIds }
            .forEach {
                issue(it, currentIdentity)
                attemptedReceiptIds += it.receiptId
            }
        publish(found, currentIdentity, generation)
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

    /** Returns false when it could not read the issued receipts and left the statuses as they were. */
    private suspend fun publish(
        found: Collection<PaykitReceiptCandidate>,
        expectedIdentity: String,
        generation: Long,
    ): Boolean {
        var views = readIssuedViews() ?: return false
        val queued = found.filter {
            views[it.receiptId]?.status == ReceiptIssuanceStatus.ACCESS_QUEUED &&
                !isSent(it, views, expectedIdentity, generation)
        }
        var unreachable = emptyList<String>()
        if (queued.isNotEmpty()) {
            unreachable = drain(queued, expectedIdentity)
            views = readIssuedViews() ?: return false
        }
        val updated = mutableMapOf<String, PaykitReceiptStatus>()
        found.forEach { candidate ->
            val reachable = unreachable.none { PubkyPublicKeyFormat.matches(it, candidate.counterparty) }
            val view = views[candidate.receiptId]
            val status = when {
                isSent(candidate, views, expectedIdentity, generation) -> PaykitReceiptStatus.Sent
                view?.status != ReceiptIssuanceStatus.ACCESS_QUEUED -> PaykitReceiptStatus.Failed
                view.lastError != null || !reachable -> PaykitReceiptStatus.Failed
                else -> PaykitReceiptStatus.Sending
            }
            val key = candidate.receive.statusKey.lowercase()
            updated[key] = PaykitReceiptStatus.combined(updated[key], status)
        }
        _statuses.update { if (generation == wipeGeneration.get()) updated.toMap() else it }
        return true
    }

    private suspend fun isSent(
        candidate: PaykitReceiptCandidate,
        views: Map<String, ReceiptIssuanceView>,
        identity: String,
        generation: Long,
    ): Boolean {
        if (views[candidate.receiptId]?.status == ReceiptIssuanceStatus.ACCESS_SENT) {
            deliveryStoreMutex.withLock {
                if (generation == wipeGeneration.get()) outboundDeliveryStore.markSent(identity, candidate.receiptId)
            }
            return true
        }
        return outboundDeliveryStore.isSent(identity, candidate.receiptId)
    }

    /** Drains each counterparty's queue. Returns the counterparties that could not be reached. */
    private suspend fun drain(found: Collection<PaykitReceiptCandidate>, expectedIdentity: String): List<String> {
        val counterparties = found.map { it.counterparty }
            .fold(emptyList<String>()) { acc, key ->
                if (acc.any { PubkyPublicKeyFormat.matches(it, key) }) acc else acc + key
            }
        return counterparties.filter { counterparty ->
            runSuspendCatching {
                paykitSdkService.processOutboundPrivateMessages(counterparty, Priority.Background, expectedIdentity)
            }.onFailure {
                Logger.warn("Failed to send Paykit receipt access", it, context = TAG)
            }.isFailure
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

    /**
     * Confirmed on-chain receives with their outputs, so a receipt counts only what was paid to the request. Outputs
     * are read for each receive a proof names, and for every receive since the earliest request with an on-chain
     * address was proposed, since a batch can pay that address while the activity shows another one.
     */
    private suspend fun onchainReceives(records: List<PaymentRequestRecord>): List<PaykitRecordedReceive> {
        val mentions = records.flatMap { record -> record.paymentProofs.map { it.proof.exportText().lowercase() } }
        val endpointsSince = records
            .filter { record ->
                record.terms?.paymentEndpoints.orEmpty().any { (identifier, payload) ->
                    PublicPaykitRepo.parseEndpoint(identifier, payload, Env.network)?.methodId?.isOnchain == true
                }
            }
            .minOfOrNull { record ->
                val proposedAt = record.proposalRecordedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                proposedAt?.minus(PaykitReceiptMatcher.RECEIVE_TIME_TOLERANCE)?.epochSeconds ?: 0L
            }
        return activityRepo.getActivities(filter = ActivityFilter.ONCHAIN, txType = PaymentType.RECEIVED).getOrThrow()
            .filterIsInstance<Activity.Onchain>()
            .filter { it.v1.confirmed && it.v1.doesExist }
            .map {
                val txid = it.v1.txId.lowercase()
                val address = it.v1.address.lowercase()
                val afterEndpoint = endpointsSince?.let { since -> it.v1.timestamp.toLong() >= since } ?: false
                val named = afterEndpoint ||
                    mentions.any { text -> txid in text || (address.isNotEmpty() && address in text) }
                bitcoinReceive(
                    paymentId = it.v1.txId,
                    rail = PaykitReceiptRail.Onchain,
                    sats = it.v1.value,
                    address = it.v1.address,
                    receivedAt = it.v1.timestamp,
                    contact = it.v1.contact,
                    outputs = if (named) outputsOf(txid) else null,
                )
            }
    }

    /** Sats a stored transaction paid to each address, keyed lowercase; null when its details are not stored. */
    private suspend fun outputsOf(txid: String): Map<String, ULong>? = outputsByTxid[txid]
        ?: activityRepo.getTransactionDetails(txid).getOrNull()?.outputs
            ?.filter { it.value > 0 }
            ?.mapNotNull { output -> output.scriptpubkeyAddress?.lowercase()?.let { it to output.value.toULong() } }
            ?.groupBy({ it.first }, { it.second })
            ?.mapValues { (_, values) -> values.fold(0uL) { sum, value -> sum + value } }
            ?.also { outputsByTxid = outputsByTxid + (txid to it) }

    private fun usdtReceives(currentIdentity: String): List<PaykitVerifiedUsdtReceive> =
        usdtPayments.get().receipts.value
            .filter { it.verified && !it.beforeRequest && PubkyPublicKeyFormat.matches(it.identity, currentIdentity) }
            .map { PaykitVerifiedUsdtReceive(it.requestId, it.proofEventId, it.transferId, it.amount) }
}

/** Not yet stored or queued, or that attempt failed: issuing again resumes the same issuance. */
private fun ReceiptIssuanceStatus?.needsIssuing(): Boolean = when (this) {
    null, ReceiptIssuanceStatus.PENDING_STORAGE, ReceiptIssuanceStatus.STORED, ReceiptIssuanceStatus.FAILED -> true
    ReceiptIssuanceStatus.ACCESS_QUEUED,
    ReceiptIssuanceStatus.ACCESS_SENT,
    ReceiptIssuanceStatus.ACCESS_FAILED,
    ReceiptIssuanceStatus.UNKNOWN,
    -> false
}
