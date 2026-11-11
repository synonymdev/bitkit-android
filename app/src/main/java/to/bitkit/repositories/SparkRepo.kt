package to.bitkit.repositories

import breez_sdk_spark.ClaimDeferredReason
import breez_sdk_spark.ClaimDepositOutcome
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.FetchClaimDepositQuoteResponse
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendOnchainFeeQuote
import breez_sdk_spark.SendPaymentMethod
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.ServiceRouteLeg
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.services.SparkError
import to.bitkit.services.SparkService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import breez_sdk_spark.PaymentType as SparkPaymentType

/**
 * Lightning without channels, through the Breez SDK on Spark. Savings to Spending funds this wallet,
 * receives fall back to it when no channel has the inbound capacity, and sends fall back to it when no
 * channel has the outbound capacity. Its payments surface as ldk-node [Event]s through [nodeEvents], so the
 * receive and send flows built on node events work for both rails.
 */
@Singleton
class SparkRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val sparkService: SparkService,
    private val activityRepo: Provider<ActivityRepo>,
    private val cacheStore: CacheStore,
    private val contactResolver: Provider<PrivatePaykitContactResolver>,
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "SparkRepo"
        private const val MSATS_PER_SAT = 1_000uL
        const val ROUTE_SERVICE = "spark"
        private const val ROUTE_ATOMICITY = "trusted"

        /** How often to look at a deposit until its Instant claim is decided. */
        private val CLAIM_POLL_INTERVAL = 5.seconds

        /** Looks for 5 minutes before telling the user Spark hasn't seen the deposit yet. */
        private const val CLAIM_POLL_ATTEMPTS = 60

        private val SEND_STATUS_PRIORITY = listOf(PaymentStatus.COMPLETED, PaymentStatus.PENDING, PaymentStatus.FAILED)

        /** How long a send's settlement waits for the send screen to track it as pending before it reports. */
        private val PENDING_TRACK_WAIT = 10.seconds

        /** Tries to learn the outcome of a send whose result was lost while Spark cannot list its payments. */
        private const val SETTLE_ATTEMPTS = 10

        /** How long a journaled withdrawal Spark doesn't list stays open before a sync closes it as never sent. */
        private val UNLISTED_WITHDRAWAL_GRACE = 10.minutes

        /** How many sent contact withdrawals stay recorded for a request proof to complete from. */
        private const val MAX_SENT_WITHDRAWALS = 20

        private val SETTLE_RETRY_INTERVAL = 30.seconds

        /** Seconds a withdrawal quote must still hold to be reused, so it can't expire mid-send. */
        private const val QUOTE_EXPIRY_MARGIN_SECS = 30uL
    }

    private val scope = appScope(bgDispatcher, TAG)

    @Volatile
    private var preparedWithdrawal: PreparedWithdrawal? = null

    /** Withdrawals this app sent, by txid, known before Spark lists them so their arrival reads as a transfer. */
    private val withdrawalTxids = ConcurrentHashMap.newKeySet<String>()

    /**
     * When a withdrawal last failed, in seconds since 1970, until Spark reads a balance again or syncs after it: while
     * its operators stay down, Spark can read 0 with the funds still there, so the last balance stays shown.
     */
    @Volatile
    private var withdrawalFailedAtSecs: ULong? = null

    /** Deposits this app broadcast that Spark hasn't listed yet, by txid, so they show as pending at once. */
    private val broadcastDeposits = ConcurrentHashMap<String, ULong>()

    /** Sends recorded before Spark knew their fee; its completion event reports 0 until a later sync. */
    private val sendsRecordedWithoutFee = ConcurrentHashMap.newKeySet<String>()

    /** Payments whose activity failed to save, written again at the next refresh. */
    private val unsavedPayments = ConcurrentHashMap.newKeySet<String>()

    private val isCollectingEvents = AtomicBoolean(false)

    private val refreshMutex = Mutex()

    /** The invoice of each send still inside Spark's send call, by lowercase payment hash. */
    private val sendsInFlight = ConcurrentHashMap<String, String>()

    /** Counts sends recorded, so a listing drops a failed hash only when it was recorded before the listing. */
    private val sendRecords = AtomicLong()
    private val sendRecordedAt = ConcurrentHashMap<String, Long>()

    /** Sends whose outcome Spark didn't report, by payment hash. */
    private val uncertainSends = ConcurrentHashMap<String, UncertainSend>()

    @Volatile
    private var hasSyncedActivities = false

    /** A withdrawal's send call is running, so its journal entry must not be settled before Spark lists it. */
    @Volatile
    private var isWithdrawing = false

    /** The remote exit data backup was merged in, or there was none, so an upload cannot drop what it held. */
    @Volatile
    var hasMergedRemoteExitState = false
        private set

    /** Watchers of the claims of deposits this app sent, by txid; they outlive the screens that start them. */
    private val claimJobs = ConcurrentHashMap<String, Job>()

    /** Vouts of the deposits an early claim was attempted for, so Wait can clear the fee cap it left on them. */
    private val earlyClaimVouts = ConcurrentHashMap<String, UInt>()

    private val _depositClaims = MutableStateFlow<Map<String, SparkDepositClaim>>(emptyMap())

    /** Where the claim of each deposit sent from the transfer flow stands, by txid. */
    val depositClaims: StateFlow<Map<String, SparkDepositClaim>> = _depositClaims.asStateFlow()

    private val _state = MutableStateFlow(SparkState(isSupported = sparkService.isSupported))
    val state: StateFlow<SparkState> = _state.asStateFlow()

    private val _nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val nodeEvents: SharedFlow<Event> = _nodeEvents.asSharedFlow()

    private val _exitStateGeneration = MutableStateFlow(0L)
    private val _withdrawalSettlements = MutableSharedFlow<SparkWithdrawalSettlement>(extraBufferCapacity = 8)

    /** How each journaled contact withdrawal closed, which a pending send screen waits on. */
    val withdrawalSettlements: SharedFlow<SparkWithdrawalSettlement> = _withdrawalSettlements.asSharedFlow()

    /**
     * Counts Spark's connections and changes to its unilateral exit data, so the exit data backup is redone whenever it
     * moved past the one last backed up, also when it moved while nothing observed it.
     */
    val exitStateGeneration: StateFlow<Long> = _exitStateGeneration.asStateFlow()

    suspend fun start(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        if (!sparkService.isSupported) return@withContext Result.success(Unit)
        runSuspendCatching {
            if (isCollectingEvents.compareAndSet(false, true)) {
                scope.launch { sparkService.events.collect { onSdkEvent(it) } }
            }
            restoreUncertainSends()
            val connected = sparkService.connect(walletIndex)
            importRestoredExitState(walletIndex)
            // The SDK starts syncing inside build(), before the listener exists, so its first Synced can be missed. A
            // sync that never reached Spark still returns, so only one that did may drop an unlisted withdrawal.
            val synced = runSuspendCatching { sparkService.syncWallet() }
                .onFailure { Logger.warn("Failed to sync Spark after connecting", it, context = TAG) }
                .isSuccess && hasSyncedSince(null)
            if (synced) _state.update { it.copy(hasSynced = true) }
            if (connected) _exitStateGeneration.update { it + 1 }
            refresh()
            // Only a full sync settles a pending withdrawal journal, so the sync runs until the journal clears. Only a
            // sync that saved every write marks the activities synced, so a failed write is redone on the next start.
            if (!hasSyncedActivities || cacheStore.data.first().pendingSparkWithdrawal != null) {
                if (syncActivities(settleJournal = synced)) hasSyncedActivities = true
            }
        }.onFailure {
            Logger.error("Failed to start Spark wallet", it, context = TAG)
        }
    }

    /** Holds Spark's start off for the whole wallet wipe. */
    fun setWiping(isWiping: Boolean) = run { sparkService.isWiping = isWiping }

    /**
     * Disconnects Spark and deletes its storage and in-memory state. Spark stays connected for the life of the
     * process otherwise, so a node stopped in background keeps Lightning available through it.
     */
    suspend fun wipe(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        claimJobs.values.forEach { it.cancel() }
        claimJobs.clear()
        earlyClaimVouts.clear()
        preparedWithdrawal = null
        withdrawalTxids.clear()
        withdrawalFailedAtSecs = null
        broadcastDeposits.clear()
        sendsRecordedWithoutFee.clear()
        unsavedPayments.clear()
        sendsInFlight.clear()
        uncertainSends.clear()
        hasSyncedActivities = false
        hasMergedRemoteExitState = false
        _depositClaims.update { emptyMap() }
        _state.update { SparkState(isSupported = sparkService.isSupported) }
        runSuspendCatching { sparkService.wipeStorage(walletIndex) }
    }

    fun canReceive(): Boolean = _state.value.isReady

    fun canSend(amountSats: ULong): Boolean = _state.value.isReady && _state.value.sendableSats >= amountSats

    /** A withdrawal's debit already holds its fees, so it is weighed against the whole balance. */
    fun canWithdraw(debitSats: ULong): Boolean = _state.value.isReady && _state.value.balanceSats >= debitSats

    suspend fun createInvoice(amountSats: ULong?, description: String, expirySecs: UInt): Result<String> =
        withContext(bgDispatcher) {
            runSuspendCatching { sparkService.receiveBolt11(amountSats, description, expirySecs) }
        }

    /** Returns the Lightning routing fee plus Spark's 0.25% service fee for paying [bolt11]. */
    suspend fun estimateFeeSats(bolt11: String, amountSats: ULong?): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            val method = sparkService.prepareSend(bolt11, amountSats).paymentMethod
            (method as SendPaymentMethod.Bolt11Invoice).feeSats()
        }
    }

    /**
     * How far a Max send of the amountless [bolt11] stays below [SparkState.sendableSats], which keeps only the
     * service fee: the SDK prices sending the whole balance with its fees included, so the amount plus its routing
     * and service fees fits the balance.
     */
    suspend fun maxSendReserveSats(bolt11: String): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            val balanceSats = sparkService.getInfo().balanceSats
            val method = sparkService.prepareSend(bolt11, balanceSats, FeePolicy.FEES_INCLUDED).paymentMethod
            val feeSats = (method as SendPaymentMethod.Bolt11Invoice).feeSats()
            val maxSats = balanceSats.safe() - feeSats.safe()
            SparkFees.sendableSats(balanceSats).safe() - maxSats.safe()
        }
    }

    private fun SendPaymentMethod.Bolt11Invoice.feeSats(): ULong =
        sparkTransferFeeSats?.takeIf { sparkService.prefersSpark } ?: lightningFeeSats

    /**
     * Pays [bolt11] and returns its payment hash, the id ldk-node payments use, so the send flow can match it.
     * Fails with [SparkError.SendRefused] when nothing left the wallet: Spark was not connected, could not prepare the
     * payment, or its fee was over [maxTotalFeeMsat]; with [SparkError.InvoiceExpired], a [SparkError.SendRefused],
     * when Spark refuses an expired invoice at prepare or send; and with [ServiceError.PaymentDeadlineExpired] past
     * [paymentDeadlineAt]. A failure inside the send is a [PaymentPendingException], as the payment may have been
     * dispatched, and its event decides it. An invoice this wallet already sends is not sent again: one in flight or
     * pending fails with [PaymentPendingException], and one Spark completed returns its hash.
     */
    suspend fun payInvoice(
        bolt11: String,
        amountSats: ULong?,
        paymentDeadlineAt: Instant? = null,
        maxTotalFeeMsat: ULong? = null,
        onBeforeSend: suspend () -> Boolean = { true },
    ): Result<PaymentId> = withContext(bgDispatcher) {
        runSuspendCatching {
            ensurePaymentDeadline(paymentDeadlineAt)
            if (!sparkService.isConnected) throw SparkError.SendRefused("Spark is not connected")
            val prepared = runSuspendCatching { sparkService.prepareSend(bolt11, amountSats) }.getOrElse {
                if (it.isInvoiceExpired()) throw SparkError.InvoiceExpired(it)
                throw SparkError.SendRefused("Spark could not prepare the payment", it)
            }
            val method = prepared.paymentMethod as? SendPaymentMethod.Bolt11Invoice
                ?: throw SparkError.SendRefused("Unexpected Spark payment method '${prepared.paymentMethod}'")
            val feeSats = method.feeSats()
            if (maxTotalFeeMsat != null && feeSats.safe() * MSATS_PER_SAT.safe() > maxTotalFeeMsat) {
                throw SparkError.SendRefused("Spark fee '$feeSats' sats is over the limit '$maxTotalFeeMsat' msat")
            }
            ensurePaymentDeadline(paymentDeadlineAt)
            val paymentHash = method.invoiceDetails.paymentHash
            when (val earlier = lightningSend(paymentHash)) {
                is SparkLightningSend.Found if earlier.status == PaymentStatus.COMPLETED -> {
                    Logger.info("Skipped Spark send of '$paymentHash', already paid", context = TAG)
                    return@runSuspendCatching paymentHash
                }
                is SparkLightningSend.Found if earlier.status == PaymentStatus.PENDING ->
                    throw PaymentPendingException(paymentHash)
                else -> Unit
            }
            // A cancel, block or identity change can land while Spark prepares, so the last check runs right here.
            if (!onBeforeSend()) throw PaymentAbortedBeforeSend()
            if (sendsInFlight.putIfAbsent(paymentHash.lowercase(), bolt11) != null) {
                throw PaymentPendingException(paymentHash)
            }
            val invoice = method.invoiceDetails
            val expiresAt = invoice.timestamp.safe() + invoice.expiry.safe()
            val sent = try {
                sendRecordedAt[paymentHash.lowercase()] = sendRecords.incrementAndGet()
                cacheStore.addSparkSendHash(paymentHash, expiresAt.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
                // The checks above and saving the hash suspend, so the deadline is checked last, before the send.
                runCatching { ensurePaymentDeadline(paymentDeadlineAt) }.onFailure {
                    cacheStore.removeSparkSendHash(paymentHash)
                    throw it
                }
                // Kept until the call returns, so a send cut off by process death or cancellation stays held.
                cacheStore.addSparkUncertainSend(paymentHash, clock.now().epochSeconds)
                runSuspendCatching { sparkService.send(prepared) }
            } finally {
                sendsInFlight.remove(paymentHash.lowercase())
            }
            val payment = sent.getOrElse {
                // The SDK checks the expiry again before anything goes out, so an expired invoice left nothing.
                if (it.isInvoiceExpired()) {
                    cacheStore.removeSparkUncertainSend(paymentHash)
                    cacheStore.removeSparkSendHash(paymentHash)
                    throw SparkError.InvoiceExpired(it)
                }
                // Spark selects the leaves to spend before anything goes to the operators, so a short balance left
                // nothing, for instance when an earlier send spent it after the balance was read.
                if (it is SdkException.InsufficientFunds) {
                    cacheStore.removeSparkUncertainSend(paymentHash)
                    cacheStore.removeSparkSendHash(paymentHash)
                    throw SparkError.SendRefused("Spark balance can't cover the payment", it)
                }
                Logger.warn("Failed to learn the outcome of Spark send '$paymentHash'", it, context = TAG)
                settleUncertainSend(paymentHash, expiresAt)
                throw PaymentPendingException(paymentHash)
            }
            cacheStore.removeSparkUncertainSend(paymentHash)
            Logger.info("Sent Spark payment '${payment.id}' with status '${payment.status}'", context = TAG)
            paymentHash
        }.onFailure {
            Logger.error("Failed to pay invoice with Spark", it, context = TAG)
        }
    }

    private suspend fun settleUncertainSend(paymentHash: String, expiresAt: ULong) {
        val failedAt = clock.now().epochSeconds
        uncertainSends[paymentHash.lowercase()] = UncertainSend(failedAt.toULong(), expiresAt)
        cacheStore.addSparkUncertainSend(paymentHash, failedAt)
        scope.launch {
            // The send screen tracks the hash as pending only once the send returns, and resolves tracked ones.
            withTimeoutOrNull(PENDING_TRACK_WAIT) {
                pendingPaymentRepo.state.first { paymentHash in it.pendingPayments }
            }
            repeat(SETTLE_ATTEMPTS) {
                runSuspendCatching { sparkService.syncWallet() }
                    .onFailure { Logger.warn("Failed to sync Spark after send '$paymentHash'", it, context = TAG) }
                if (trySettleUncertainSend(paymentHash)) return@launch
                delay(SETTLE_RETRY_INTERVAL)
            }
            // Spark's next sync settles it, as a send it resumes then ends with its own payment event.
            Logger.warn("Stopped polling Spark send '$paymentHash' until Spark syncs again", context = TAG)
        }
    }

    /** Settles an uncertain send once Spark shows its outcome; true once it is settled, also by another caller. */
    private suspend fun trySettleUncertainSend(paymentHash: String): Boolean {
        val key = paymentHash.lowercase()
        val uncertain = uncertainSends[key] ?: return true
        // Spark finishes an interrupted send only on a later sync, and a sync that never reached it still returns.
        val isSynced = hasSyncedSince(uncertain.failedAt)
        if (isSynced) _state.update { it.copy(hasSynced = true) }
        // Spark keeps a send it committed but didn't finish in a cache no call exposes, and a sync that reached Spark
        // can still fail to resume it, so only an expired invoice shows a missing send will never go out.
        val isFinal = isSynced && clock.now().epochSeconds.toULong() >= uncertain.expiresAt
        val send = listedLightningSend(paymentHash)
        val isSettled = when (send) {
            SparkLightningSend.NotFound -> isFinal
            // A failed row may be an earlier attempt's, so it is final only when a missing one would be.
            is SparkLightningSend.Found -> send.status != PaymentStatus.FAILED || isFinal
            SparkLightningSend.Unavailable -> false
        }
        // Only the caller that takes the hash off settles it, so its outcome is reported once.
        if (!isSettled || !uncertainSends.remove(key, uncertain)) return isSettled
        cacheStore.removeSparkUncertainSend(paymentHash)
        when (send) {
            SparkLightningSend.NotFound -> {
                cacheStore.removeSparkSendHash(paymentHash)
                _nodeEvents.emit(Event.PaymentFailed(paymentHash, paymentHash, PaymentFailureReason.UNEXPECTED_ERROR))
            }
            // A pending send ends with its own payment event.
            is SparkLightningSend.Found if send.status == PaymentStatus.PENDING -> Unit
            is SparkLightningSend.Found -> {
                if (send.status == PaymentStatus.FAILED) cacheStore.removeSparkSendHash(paymentHash)
                settledSendEvent(paymentHash)?.let { _nodeEvents.emit(it) }
            }
            SparkLightningSend.Unavailable -> Unit
        }
        return true
    }

    /** A send whose outcome Spark didn't report: when it failed, and when its invoice expires, in epoch seconds. */
    private data class UncertainSend(val failedAt: ULong, val expiresAt: ULong)

    /** Takes back the sends whose outcome Spark didn't report before a restart, so a sync can still settle them. */
    private suspend fun restoreUncertainSends() {
        val cache = cacheStore.data.first()
        cache.sparkUncertainSends.forEach { (hash, failedAt) ->
            val expiresAt = cache.sparkSendExpiries[hash]?.toULong() ?: ULong.MAX_VALUE
            uncertainSends.putIfAbsent(hash, UncertainSend(failedAt.toULong(), expiresAt))
        }
    }

    /** Sends whose outcome Spark didn't report, also ones kept from before a restart not yet taken back. */
    private suspend fun uncertainSendHashes(): Set<String> =
        uncertainSends.keys + cacheStore.data.first().sparkUncertainSends.keys

    /**
     * Whether Spark's last wallet sync reached it, and started after [seconds] when given. A sync time is required
     * either way, as the SDK sets it only on a wallet sync that succeeded and reports no error before any sync ends.
     */
    private suspend fun hasSyncedSince(seconds: ULong?): Boolean {
        val info = runSuspendCatching { sparkService.getInfo() }
            .onFailure { Logger.warn("Failed to read Spark's sync state", it, context = TAG) }
            .getOrNull() ?: return false
        info.lastSyncError?.let { Logger.warn("Spark's last sync failed: '$it'", context = TAG) }
        val syncedAt = info.lastSyncedAt
        return info.lastSyncError == null && syncedAt != null && (seconds == null || syncedAt > seconds)
    }

    private fun ensurePaymentDeadline(paymentDeadlineAt: Instant?) {
        if (paymentDeadlineAt != null && clock.now() > paymentDeadlineAt) throw ServiceError.PaymentDeadlineExpired()
    }

    /** The most an Instant claim of a deposit of [amountSats] may cost: the ceiling the user accepts. */
    suspend fun estimateDepositClaimFeeSats(amountSats: ULong): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching { SparkService.maxDepositClaimFeeSats(sparkService.fastestFeeRate(), amountSats) }
    }

    /** What Spark keeps to claim a deposit at 3 confirmations, at the current half-hour fee rate. */
    suspend fun estimateMatureClaimFeeSats(): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching { SparkService.matureDepositClaimFeeSats(sparkService.halfHourFeeRate()) }
    }

    /**
     * Follows the claim of the deposit in [txid] that the user sent at [speed]. Instant claims it early as soon as
     * Spark offers that within [ceilingSats], and asks the user when it costs more; Later leaves it to the claim at
     * maturity. Runs in the app scope, so leaving the transfer screens does not drop an Instant claim.
     */
    fun followDepositClaim(txid: String, speed: SparkClaimSpeed, ceilingSats: ULong) {
        claimJobs.remove(txid)?.cancel()
        when (speed) {
            SparkClaimSpeed.Later -> setDepositClaim(txid, SparkDepositClaim.Later)
            SparkClaimSpeed.Instant -> {
                setDepositClaim(txid, SparkDepositClaim.Looking)
                claimJobs[txid] = scope.launch { claimInstantly(txid, ceilingSats) }
            }
        }
    }

    /** Accepts the repriced Instant claim of [txid], at the fee the provider asks now. */
    fun payRepricedClaim(txid: String) {
        val repriced = _depositClaims.value[txid] as? SparkDepositClaim.Repriced ?: return
        followDepositClaim(txid, SparkClaimSpeed.Instant, repriced.offer.earlyFeeSats)
    }

    /**
     * Leaves the deposit in [txid] to the claim at 3 confirmations, clearing the fee cap an early claim attempt left on
     * it, so Spark doesn't still claim it early.
     */
    fun waitForMatureClaim(txid: String) {
        claimJobs.remove(txid)?.cancel()
        setDepositClaim(txid, SparkDepositClaim.Later)
        val vout = earlyClaimVouts.remove(txid) ?: return
        // Tried again while another claim of the deposit, such as Spark's own sync, leaves the cap in place.
        scope.launch {
            repeat(CLAIM_POLL_ATTEMPTS) { attempt ->
                runSuspendCatching { sparkService.releaseDepositClaimFee(txid, vout) }
                    .onSuccess { return@launch }
                    .onFailure {
                        Logger.warn("Failed to clear the early claim cap of Spark deposit '$txid'", it, context = TAG)
                    }
                if (attempt < CLAIM_POLL_ATTEMPTS - 1) delay(CLAIM_POLL_INTERVAL)
            }
        }
    }

    private suspend fun claimInstantly(txid: String, ceilingSats: ULong) {
        var isSeen = false
        repeat(CLAIM_POLL_ATTEMPTS) {
            runSuspendCatching { refresh() }
                .onFailure { Logger.warn("Failed to refresh Spark state", it, context = TAG) }
            if (isClaimed(txid)) {
                setDepositClaim(txid, SparkDepositClaim.Claimed)
                return
            }
            if (_depositClaims.value[txid] != SparkDepositClaim.Adding) {
                val offer = depositClaimOffer(txid)
                    .onFailure { Logger.warn("Failed to quote Spark deposit '$txid'", it, context = TAG) }
                    .getOrNull()
                if (offer != null) isSeen = true
                val claim = when (val step = SparkClaimStep.of(offer, ceilingSats)) {
                    SparkClaimStep.KeepLooking -> null
                    SparkClaimStep.Adding -> SparkDepositClaim.Adding
                    SparkClaimStep.Unavailable -> SparkDepositClaim.Unavailable
                    is SparkClaimStep.Ask -> SparkDepositClaim.Repriced(step.offer)
                    is SparkClaimStep.ClaimEarly -> claimEarly(step.offer).getOrNull()?.toDepositClaim(step.offer)
                }
                if (claim != null) setDepositClaim(txid, claim)
                if (claim?.isFinal == true) return
            }
            delay(CLAIM_POLL_INTERVAL)
        }
        if (_depositClaims.value[txid] == SparkDepositClaim.Adding) return
        Logger.warn("Stopped looking for the Instant claim of Spark deposit '$txid'", context = TAG)
        setDepositClaim(txid, if (isSeen) SparkDepositClaim.Unavailable else SparkDepositClaim.NotSeen)
    }

    private fun isClaimed(txid: String): Boolean =
        _state.value.deposits.any { it.isClaimed && it.txid.equals(txid, ignoreCase = true) }

    private fun setDepositClaim(txid: String, claim: SparkDepositClaim) {
        Logger.debug("Set claim of Spark deposit '$txid' to '$claim'", context = TAG)
        _depositClaims.update { it + (txid to claim) }
    }

    /** How the deposit in [txid] can be claimed, or null while Spark has not seen it yet. */
    suspend fun depositClaimOffer(txid: String): Result<SparkClaimOffer?> = withContext(bgDispatcher) {
        runSuspendCatching {
            val deposit = sparkService.listUnclaimedDeposits().firstOrNull { it.txid.equals(txid, ignoreCase = true) }
                ?: return@runSuspendCatching null
            SparkClaimOffer.from(deposit.txid, deposit.vout, sparkService.fetchClaimQuote(deposit.txid, deposit.vout))
        }
    }

    /** Accepts the early claim of [offer] at its quoted fee. */
    suspend fun claimEarly(offer: SparkClaimOffer.Choice): Result<SparkEarlyClaim> = withContext(bgDispatcher) {
        earlyClaimVouts[offer.txid] = offer.vout
        runSuspendCatching { sparkService.claimDeposit(offer.txid, offer.vout, offer.earlyFeeSats) }
            .map { SparkEarlyClaim.from(it) }
            .recoverCatching { if (it is SdkException.DepositClaimInProgress) SparkEarlyClaim.Claimed else throw it }
            .onSuccess {
                Logger.info("Early claim of Spark deposit '${offer.txid}': '$it'", context = TAG)
                runSuspendCatching { refresh() }
                    .onFailure { Logger.warn("Failed to refresh Spark state", it, context = TAG) }
            }
            .onFailure { Logger.error("Failed to claim Spark deposit '${offer.txid}' early", it, context = TAG) }
    }

    suspend fun depositAddress(): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching { sparkService.depositAddress() }
    }

    /**
     * Moves the whole Spark balance to the on-chain [address], fees included, for Spending to Savings. Fails with
     * [SparkError.WithdrawalFeeChanged] when its fee is above [confirmedFeeSats], the fee the user confirmed, and with
     * [SparkError.WithdrawalTooSmall] when what reaches [address] would be dust.
     */
    suspend fun withdrawAll(address: String, confirmedFeeSats: ULong): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            val prepared = prepareWithdrawAll(address)
            if (prepared.feeSats() > confirmedFeeSats) throw SparkError.WithdrawalFeeChanged()
            val payment = sparkService.send(prepared.response)
            preparedWithdrawal = null
            (payment.details as? PaymentDetails.Withdraw)?.txId?.let { withdrawalTxids += it }
            Unit
        }.onSuccess {
            withdrawalFailedAtSecs = null
            runSuspendCatching { refresh() }
                .onFailure { Logger.warn("Failed to refresh Spark after its withdrawal", it, context = TAG) }
        }.onFailure {
            withdrawalFailedAtSecs = clock.now().epochSeconds.toULong()
            Logger.error("Failed to withdraw from Spark", it, context = TAG)
        }
    }

    /** The fee [withdrawAll] pays at its speed, which comes out of the balance it moves. */
    suspend fun estimateWithdrawAllFeeSats(address: String): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            prepareWithdrawAll(address).feeSats()
        }
    }

    private suspend fun prepareWithdrawAll(address: String): PreparedWithdrawal {
        // A held quote is reused, as asking for a second one took minutes on regtest.
        val balanceSats = sparkService.getInfo().balanceSats
        val nowSecs = clock.now().epochSeconds.toULong()
        preparedWithdrawal
            ?.takeIf { it.address == address && it.balanceSats == balanceSats }
            ?.takeIf { it.response.withdrawalQuote().expiresAt > nowSecs.safe() + QUOTE_EXPIRY_MARGIN_SECS.safe() }
            ?.let { return it }
        val response = try {
            sparkService.prepareSend(address, balanceSats, FeePolicy.FEES_INCLUDED)
        } catch (e: SdkException.InvalidInput) {
            // Spark refuses to quote a balance that even its cheapest fee leaves as dust.
            if (e.isBelowMinimum()) throw SparkError.WithdrawalTooSmall()
            throw e
        }
        return PreparedWithdrawal(address, balanceSats, response).also { preparedWithdrawal = it }
    }

    private fun PreparedWithdrawal.feeSats(): ULong {
        val speed = response.withdrawalQuote().speedMedium
        val feeSats = speed.userFeeSat.safe() + speed.l1BroadcastFeeSat.safe()
        if (balanceSats < feeSats.safe() + Defaults.dustLimit.toULong().safe()) throw SparkError.WithdrawalTooSmall()
        return feeSats
    }

    /**
     * A Paykit route that withdraws exactly [amountSats] to the contact's on-chain [endpoint] at medium speed, with
     * Spark's fee on top, or null while Spark cannot send it. bitkit-core weighs it against the Pubky Swaps providers.
     */
    suspend fun withdrawalRouteLeg(endpoint: PaykitEndpoint, amountSats: ULong): Result<ServiceRouteLeg?> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                if (!canWithdraw(amountSats)) return@runSuspendCatching null
                val method = sparkService.prepareSend(endpoint.value, amountSats).paymentMethod
                val quote = (method as SendPaymentMethod.BitcoinAddress).feeQuote
                // Spark charges its fee and the L1 broadcast fee on top of what reaches the contact.
                val speed = quote.speedMedium
                val debitSats = amountSats.safe() + (speed.userFeeSat.safe() + speed.l1BroadcastFeeSat.safe()).safe()
                if (quote.isEstimate || !canWithdraw(debitSats)) return@runSuspendCatching null
                ServiceRouteLeg(
                    service = ROUTE_SERVICE,
                    endpoint = endpoint,
                    deliveredSat = amountSats,
                    debitSat = debitSats,
                    atomicity = ROUTE_ATOMICITY,
                    validUntilUnix = quote.expiresAt,
                )
            }
        }

    /**
     * Txids of the withdrawals Spark lists or the app recorded as sent to [address], so a new attempt to it can tell
     * its own withdrawal apart, also while Spark's list lacks one after a restart.
     */
    suspend fun withdrawalTxidsTo(address: String): Set<String> = withContext(bgDispatcher) {
        val listed = sparkService.listPayments().mapNotNull { payment ->
            (payment.details as? PaymentDetails.Withdraw)?.takeIf { it.destinationAddress == address }?.txId
        }
        val recorded = cacheStore.data.first().sentSparkWithdrawals.filter { it.address == address }.map { it.txid }
        (listed + recorded).toSet()
    }

    /** Whether [txid] is a Spark withdrawal, which LDK never held and so cannot boost. */
    suspend fun isWithdrawal(txid: String): Boolean = withContext(bgDispatcher) {
        if (cacheStore.data.first().sentSparkWithdrawals.any { it.txid == txid }) return@withContext true
        if (!_state.value.isReady) return@withContext false
        runSuspendCatching { sparkService.listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments for '$txid'", it, context = TAG) }
            .getOrDefault(emptyList())
            .any { (it.details as? PaymentDetails.Withdraw)?.txId == txid }
    }

    /**
     * What became of an attempt to withdraw exactly [amountSats] to [address]: sent once Spark's synced list holds a
     * completed withdrawal to it that is not one of [earlierTxids], unsent once Spark synced and no journaled or
     * pending withdrawal to [address] is still settling, else unknown.
     */
    suspend fun withdrawalOutcome(
        address: String,
        amountSats: ULong,
        earlierTxids: Set<String>,
    ): SparkWithdrawalOutcome = withContext(bgDispatcher) {
        // A withdrawal recorded as sent stays sent, also once Spark's list lacks it after a restart.
        cacheStore.data.first().sentSparkWithdrawals.firstOrNull {
            it.address == address && it.amountSats == amountSats && it.txid !in earlierTxids
        }?.let { return@withContext SparkWithdrawalOutcome.Sent(it.txid) }
        if (!_state.value.hasSynced || isWithdrawing) return@withContext SparkWithdrawalOutcome.Unknown
        val attempts = sparkService.listPayments().filter { payment ->
            val withdraw = payment.details as? PaymentDetails.Withdraw ?: return@filter false
            withdraw.destinationAddress == address && payment.amount.toLong().toULong() == amountSats &&
                withdraw.txId !in earlierTxids
        }
        // Spark keeps a withdrawal pending until its provider accepts the payout, which can still fail it.
        val txid = attempts.firstOrNull { it.status == PaymentStatus.COMPLETED }
            ?.let { (it.details as PaymentDetails.Withdraw).txId }
        when {
            txid != null -> SparkWithdrawalOutcome.Sent(txid)
            attempts.any { it.status == PaymentStatus.PENDING } -> SparkWithdrawalOutcome.Unknown
            cacheStore.data.first().pendingSparkWithdrawal?.address == address -> SparkWithdrawalOutcome.Unknown
            else -> SparkWithdrawalOutcome.Unsent
        }
    }

    /**
     * Withdraws exactly [amountSats] to a Paykit [contact]'s on-chain [address] at medium speed and returns the
     * withdrawal's txid. A journal entry written before the send lets the next start settle a withdrawal the app was
     * killed during, from Spark's payment list, and refuses another withdrawal until then. Past [paymentDeadlineAt],
     * it sends nothing.
     */
    suspend fun withdraw(
        address: String,
        amountSats: ULong,
        contact: String?,
        maxDebitSats: ULong? = null,
        tags: List<String> = emptyList(),
        paymentDeadlineAt: Instant? = null,
        onBeforeSend: suspend () -> Boolean = { true },
    ): Result<String> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                if (cacheStore.data.first().pendingSparkWithdrawal != null) throw SparkError.WithdrawalPending()
                val prepared = sparkService.prepareSend(address, amountSats)
                val speed = prepared.withdrawalQuote().speedMedium
                val debitSats = amountSats.safe() + (speed.userFeeSat.safe() + speed.l1BroadcastFeeSat.safe()).safe()
                // The user confirmed the leg's debit; a quote that rose since then needs a new confirmation.
                if (maxDebitSats != null && debitSats > maxDebitSats) throw SparkError.WithdrawalFeeChanged()
                val earlierTxids = withdrawalTxidsTo(address)
                // The proof onBeforeSend starts has no journal yet, so the attempt reads as in flight from here on and
                // a reconcile meanwhile never drops that proof as unsent.
                isWithdrawing = true
                var journal: PendingSparkWithdrawal? = null
                val sent = try {
                    if (!onBeforeSend()) throw PaymentAbortedBeforeSend()
                    // A quote can expire while the request and its allowance are checked.
                    ensurePaymentDeadline(paymentDeadlineAt)
                    val opened = PendingSparkWithdrawal(
                        address,
                        amountSats,
                        contact,
                        earlierTxids,
                        tags,
                        startedAt = clock.now().epochSeconds,
                    )
                    journal = opened
                    cacheStore.update { it.copy(pendingSparkWithdrawal = opened) }
                    // Saving the journal suspends, so the deadline is checked last, before the send.
                    runCatching { ensurePaymentDeadline(paymentDeadlineAt) }.onFailure {
                        clearJournal(opened)
                        throw it
                    }
                    runSuspendCatching { sparkService.send(prepared) }
                } finally {
                    isWithdrawing = false
                }
                val payment = sent.getOrElse {
                    // Spark selects the leaves to spend before anything goes to the operators, so a short balance left
                    // nothing and the journal closes at once, as on the Lightning rail.
                    if (it is SdkException.InsufficientFunds) {
                        clearJournal(journal)
                        throw SparkError.SendRefused("Spark balance can't cover the withdrawal", it)
                    }
                    // Spark's synced list settles the journal: a listed withdrawal records, an unlisted one drops. A
                    // sync that never reached Spark still returns, so only one done after the failure drops it.
                    val failedAt = clock.now().epochSeconds.toULong()
                    runSuspendCatching { sparkService.syncWallet() }
                        .onSuccess { syncActivities(settleJournal = hasSyncedSince(failedAt)) }
                    throw it
                }
                Logger.info("Sent Spark withdrawal '${payment.id}' with status '${payment.status}'", context = TAG)
                recordActivity(payment)
                runSuspendCatching { refresh() }.onFailure {
                    Logger.warn("Failed to refresh Spark after withdrawal '${payment.id}'", it, context = TAG)
                }
                // A failed withdrawal paid nothing: its journal closed as recorded, so the caller sees it unsent and
                // releases the proof, private list and allowance attempt instead of showing success.
                if (payment.status == PaymentStatus.FAILED) throw SparkError.WithdrawalNotSent()
                // One still pending can fail until its provider accepts the payout, so its outcome stays unknown, on
                // the pending screen, until its journal settles.
                if (payment.status != PaymentStatus.COMPLETED) throw SparkError.WithdrawalNotCompleted()
                (payment.details as? PaymentDetails.Withdraw)?.txId ?: payment.id
            }.onFailure {
                Logger.error("Failed to withdraw to a Paykit contact with Spark", it, context = TAG)
            }
        }

    /**
     * Lists a deposit the app just broadcast as pending right away, then syncs so Spark sees it sooner than its
     * minute-long background sync; Spark's own deposit state replaces it once listed.
     */
    fun onDepositBroadcast(txid: String, amountSats: ULong) {
        broadcastDeposits[txid] = amountSats
        _state.update { it.withBroadcastDeposits() }
        scope.launch {
            runSuspendCatching {
                sparkService.syncWallet()
                refresh()
            }.onFailure { Logger.warn("Failed to sync Spark after deposit '$txid'", it, context = TAG) }
        }
    }

    private fun SparkState.withBroadcastDeposits(): SparkState {
        val known = deposits.map { it.txid }.toSet()
        val local = broadcastDeposits.filterKeys { it !in known }
            .map { (txid, amount) -> SparkDeposit(txid = txid, amountSats = amount, isClaimed = false) }
        if (local.isEmpty()) return this
        val all = local + deposits
        return copy(
            deposits = all.toImmutableList(),
            pendingDepositSats = all.filterNot { it.isClaimed }.sumOf { it.amountSats },
        )
    }

    /**
     * The exit data backup, or null until Spark has connected and synced once, since an export taken before its
     * first sync can miss what the wallet holds.
     */
    suspend fun exitBackup(): Result<SparkExitBackupV1?> = withContext(bgDispatcher) {
        runSuspendCatching {
            val network = sparkService.networkName
            if (!sparkService.isConnected || network == null || !_state.value.hasSynced) return@runSuspendCatching null
            SparkExitBackupV1(createdAt = nowMillis(), network = network, exitState = sparkService.exportExitState())
        }
    }

    /**
     * Merges the exit data backup already stored, [remote], or none, into Spark before the first upload replaces it,
     * so that upload holds everything the stored one did. Spark must be connected.
     */
    suspend fun mergeRemoteExitBackup(remote: SparkExitBackupV1?): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (remote != null && remote.network == sparkService.networkName) {
                val merged = sparkService.importExitState(remote.exitState)
                Logger.info("Merged stored Spark exit data: '${merged.importedLeaves}' leaves", context = TAG)
            }
            hasMergedRemoteExitState = true
        }
    }

    /**
     * Puts a restored exit data backup back: at once while Spark is connected, otherwise right after it connects,
     * before it is used, so a restored wallet can exit on-chain even while the Spark operators are unreachable.
     */
    suspend fun restoreExitBackup(backup: SparkExitBackupV1, walletIndex: Int): Result<Unit> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                if (backup.network != sparkService.networkName) {
                    Logger.warn("Skipped Spark exit data from network '${backup.network}'", context = TAG)
                    return@runSuspendCatching
                }
                File(Env.sparkExitRestorePath(walletIndex)).writeText(backup.exitState)
                if (sparkService.isConnected) importRestoredExitState(walletIndex).getOrThrow()
            }
        }

    private suspend fun importRestoredExitState(walletIndex: Int): Result<Unit> {
        val file = File(Env.sparkExitRestorePath(walletIndex))
        if (!file.exists()) return Result.success(Unit)
        return runSuspendCatching { sparkService.importExitState(file.readText()) }
            .onSuccess {
                file.delete()
                Logger.info(
                    "Imported Spark exit data: '${it.importedLeaves}' leaves, '${it.skippedForeignLeaves}' foreign, " +
                        "'${it.skippedConflictingLeaves}' conflicting, '${it.skippedChains}' already held",
                    context = TAG,
                )
            }
            .onFailure { Logger.error("Failed to import Spark exit data", it, context = TAG) }
            .map {}
    }

    suspend fun refreshState(): Result<Unit> = withContext(bgDispatcher) {
        if (!sparkService.isConnected) return@withContext Result.success(Unit)
        runSuspendCatching { refresh() }.onFailure { Logger.warn("Failed to refresh Spark state", it, context = TAG) }
    }

    private suspend fun refresh() = refreshMutex.withLock {
        val info = sparkService.getInfo()
        info.lastSyncError?.let { Logger.warn("Failed to sync Spark, balances may be stale: '$it'", context = TAG) }
        val unclaimed = sparkService.listUnclaimedDeposits()
        val payments = sparkService.listPayments()
        val claimedPayments = payments
            .filter { it.method == PaymentMethod.DEPOSIT && it.status == PaymentStatus.COMPLETED }
        val claimed = claimedPayments
            .mapNotNull { payment ->
                val deposit = payment.details as? PaymentDetails.Deposit ?: return@mapNotNull null
                SparkDeposit(
                    txid = deposit.txId,
                    amountSats = payment.amount.toLong().toULong(),
                    isClaimed = true,
                    feeSats = payment.fees.toLong().toULong(),
                    claimedAt = payment.timestamp,
                )
            }
        // Spark can still list a deposit it already claimed early as unclaimed, so a claimed one is listed only once.
        // Keyed by output, as one transaction can hold several deposits.
        val claimedOutputs = claimedPayments.mapNotNullTo(mutableSetOf()) { payment ->
            (payment.details as? PaymentDetails.Deposit)?.let { it.txId.lowercase() to it.vout }
        }
        val pending = unclaimed
            .filter { (it.txid.lowercase() to it.vout) !in claimedOutputs }
            .map { SparkDeposit(txid = it.txid, amountSats = it.amountSats, isClaimed = false) }
        (pending + claimed).forEach { broadcastDeposits.remove(it.txid) }
        val transfers = payments.toWithdrawals().filterNot { isContactWithdrawal(it.txid) }.toImmutableList()
        val failedAt = withdrawalFailedAtSecs
        val hasSyncedSince = failedAt != null && info.lastSyncError == null && (info.lastSyncedAt ?: 0uL) > failedAt
        if (info.balanceSats > 0uL || hasSyncedSince) withdrawalFailedAtSecs = null
        val isStale = withdrawalFailedAtSecs != null || info.lastSyncError != null
        _state.update {
            it.copy(
                isReady = true,
                balanceSats = info.balanceSats,
                keptBalanceSats = it.shownBalanceSats.takeIf { kept ->
                    info.balanceSats == 0uL && isStale && kept > 0uL
                },
                pendingDepositSats = pending.sumOf { deposit -> deposit.amountSats },
                deposits = (pending + claimed).toImmutableList(),
                withdrawals = transfers,
            ).withBroadcastDeposits()
        }
        markClaimedDeposits(claimed)
        payments
            .filter { it.id in unsavedPayments || (it.id in sendsRecordedWithoutFee && it.fees.toLong() > 0) }
            .forEach { recordActivity(it) }
        markTransfers(payments)
    }

    private fun markClaimedDeposits(claimed: List<SparkDeposit>) {
        val claimedTxids = claimed.map { it.txid.lowercase() }.toSet()
        _depositClaims.update { claims ->
            claims.mapValues { (txid, claim) ->
                if (txid.lowercase() in claimedTxids) SparkDepositClaim.Claimed else claim
            }
        }
        claimJobs.keys.filter { it.lowercase() in claimedTxids }.forEach { claimJobs.remove(it)?.cancel() }
    }

    private fun List<Payment>.toWithdrawals() = filter { it.status != PaymentStatus.FAILED }
        .mapNotNull { payment ->
            val withdraw = payment.details as? PaymentDetails.Withdraw ?: return@mapNotNull null
            SparkWithdrawal(
                txid = withdraw.txId,
                amountSats = payment.amount.toLong().toULong(),
                feeSats = payment.fees.toLong().toULong(),
            )
        }
        .toImmutableList()

    /** A withdrawal to a Paykit contact is recorded as an on-chain send: it is no Transfer to Savings. */
    private suspend fun isContactWithdrawal(txid: String): Boolean =
        (activityRepo.get().getActivity(txid).getOrNull() as? Activity.Onchain)?.v1?.txType == PaymentType.SENT

    /**
     * Marks the on-chain receive [txid] as a transfer from Spending when it is a Spark withdrawal, so it shows no
     * Received sheet and reads as a transfer in Activity. Returns whether it is one.
     */
    suspend fun markWithdrawalAsTransfer(txid: String): Boolean = withContext(bgDispatcher) {
        val isWithdrawal = txid in withdrawalTxids || _state.value.withdrawals.any { it.txid == txid }
        if (!isWithdrawal) return@withContext false
        val activities = activityRepo.get()
        activities.getOnchainActivityByTxId(txid)?.takeUnless { it.isTransfer || it.txType == PaymentType.SENT }?.let {
            activities.updateActivity(it.id, Activity.Onchain(it.copy(isTransfer = true)))
        }
        true
    }

    private suspend fun markTransfers(payments: List<Payment>) {
        val activities = activityRepo.get()
        payments.filter { it.status != PaymentStatus.FAILED }
            .mapNotNull { (it.details as? PaymentDetails.Withdraw)?.txId }
            .forEach { txid ->
                // A withdrawal to a Paykit contact is a send of its own; only an arrival in Savings is a transfer.
                val activity = activities.getOnchainActivityByTxId(txid)
                    ?.takeUnless { it.isTransfer || it.txType == PaymentType.SENT } ?: return@forEach
                activities.updateActivity(activity.id, Activity.Onchain(activity.copy(isTransfer = true)))
            }
    }

    /**
     * The completion event of a Spark Lightning send that has already settled. A Spark-to-Spark payment settles inside
     * the send call, so its event can fire before the caller starts watching for it.
     */
    suspend fun settledSendEvent(paymentHash: String): Event? = withContext(bgDispatcher) {
        if (!sparkService.isConnected) return@withContext null
        runSuspendCatching { sparkService.listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
            .getOrNull()
            ?.latestSend(paymentHash)
            ?.toNodeEvent()
    }

    /**
     * False while Spark is supported but not connected or not yet synced, since its payment list is read from local
     * storage that a restored wallet fills only on its first sync, so a missing payment proves nothing until then.
     */
    fun canListPayments(): Boolean =
        !sparkService.isSupported || (sparkService.isConnected && _state.value.hasSynced)

    /**
     * The status that settles each of Spark's Lightning sends, by payment hash; empty where Spark is unsupported and
     * null while it can't list its payments, so a send it may hold is never read as one it never made.
     */
    suspend fun lightningSendStatuses(): Map<String, PaymentStatus>? = withContext(bgDispatcher) {
        if (!sparkService.isSupported) return@withContext emptyMap()
        if (!canListPayments()) return@withContext null
        val unlisted = unexpiredStartedSends()
        val uncertain = uncertainSendHashes()
        val payments = runSuspendCatching { sparkService.listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
            .getOrElse { return@withContext null }
        val listed = payments
            .mapNotNull { payment ->
                (payment.details as? PaymentDetails.Lightning)
                    ?.takeIf { payment.paymentType == SparkPaymentType.SEND }
                    ?.htlcDetails?.paymentHash
            }
            .distinct()
            .associateWith { hash ->
                val status = requireNotNull(payments.latestSend(hash)).status
                // A failed row may be an earlier attempt's, while Spark may still finish the uncertain retry.
                if (status == PaymentStatus.FAILED && hash.lowercase() in uncertain) PaymentStatus.PENDING else status
            }
        val listedHashes = listed.keys.mapTo(mutableSetOf()) { it.lowercase() }
        listed + (unlisted + uncertain - listedHashes).associateWith { PaymentStatus.PENDING }
    }

    /**
     * Spark's record of the Lightning send with [paymentHash], for recovery after its completion event was missed.
     * [SparkLightningSend.Unavailable] while Spark is supported but cannot list its payments, so a send it may hold
     * is never read as one it never made. A send Spark may still resume reads as pending: one whose outcome Spark
     * didn't report, unless a row settles it, and a started one with no row whose invoice is unexpired, also while
     * Spark can't list its payments, so a retry never sends it again.
     */
    suspend fun lightningSend(paymentHash: String): SparkLightningSend = withContext(bgDispatcher) {
        val hash = paymentHash.lowercase()
        val isUnlistedHeld = hash in unexpiredStartedSends()
        val send = listedLightningSend(paymentHash)
        val isUncertain = hash in uncertainSendHashes()
        val isHeld = when (send) {
            SparkLightningSend.NotFound, SparkLightningSend.Unavailable -> isUncertain || isUnlistedHeld
            is SparkLightningSend.Found -> isUncertain && send.status == PaymentStatus.FAILED
        }
        if (isHeld) SparkLightningSend.Found(PaymentStatus.PENDING, null) else send
    }

    /** Spark's listed record of the Lightning send with [paymentHash], as [lightningSend] reads it before its holds. */
    private suspend fun listedLightningSend(paymentHash: String): SparkLightningSend = withContext(bgDispatcher) {
        if (!sparkService.isSupported) return@withContext SparkLightningSend.NotFound
        if (!canListPayments()) return@withContext SparkLightningSend.Unavailable
        val payments = runSuspendCatching { sparkService.listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
            .getOrElse { return@withContext SparkLightningSend.Unavailable }
        payments.latestSend(paymentHash)
            ?.let { SparkLightningSend.Found(it.status, (it.details as PaymentDetails.Lightning).htlcDetails.preimage) }
            ?: SparkLightningSend.NotFound
    }

    /**
     * Spark's pending or paid send of [bolt11], matched by the invoice or its [paymentHash], so the node never pays it
     * again; a send still inside Spark's send call is pending. Null when Spark's send failed or none is known.
     * While Spark can't list its payments, a send it started for [paymentHash] counts as pending, since it may be live.
     */
    suspend fun earlierLightningSend(bolt11: String, paymentHash: String?): EarlierSend? =
        withContext(bgDispatcher) {
            val recordsBefore = sendRecords.get()
            val keptUncertain = cacheStore.data.first().sparkUncertainSends.keys
            val isOfInvoice = { send: Map.Entry<String, String> ->
                send.value.equals(bolt11, ignoreCase = true) || send.key == paymentHash?.lowercase()
            }
            sendsInFlight.entries.firstOrNull(isOfInvoice)?.let { return@withContext EarlierSend.Pending(it.key) }
            // A retry started, or recorded, while the listing below is read may still be pending, as may a send whose
            // outcome Spark didn't report until a sync settles it.
            val retried = {
                sendsInFlight.entries.firstOrNull(isOfInvoice)?.key
                    ?: paymentHash?.lowercase()?.takeIf {
                        it in recordedSince(recordsBefore) || uncertainSends.containsKey(it) || it in keptUncertain
                    }
            }
            val unlisted = unexpiredStartedSends()
            // Before Spark's first sync its local list can be empty although a restored send is live.
            val payments = if (sparkService.isSupported && canListPayments()) {
                runSuspendCatching { sparkService.listPayments() }
                    .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
                    .getOrNull()
            } else {
                null
            }
            if (payments == null) return@withContext startedSend(paymentHash)?.let { EarlierSend.Pending(it) }
            val send = payments
                .latestSend {
                    it.invoice.equals(bolt11, ignoreCase = true) ||
                        it.htlcDetails.paymentHash.equals(paymentHash, ignoreCase = true)
                }
                ?: return@withContext (retried() ?: paymentHash?.lowercase()?.takeIf { it in unlisted })
                    ?.let { EarlierSend.Pending(it) }
            if (send.status == PaymentStatus.FAILED) retried()?.let { return@withContext EarlierSend.Pending(it) }
            val hash = (send.details as PaymentDetails.Lightning).htlcDetails.paymentHash
            when (send.status) {
                PaymentStatus.PENDING -> EarlierSend.Pending(hash)
                PaymentStatus.COMPLETED -> EarlierSend.Paid(hash)
                PaymentStatus.FAILED -> null
            }
        }

    /** The count of sends recorded so far, read before a node send's checks for [hasRecordedSendSince]. */
    fun sendRecordMark(): Long = sendRecords.get()

    /** Whether a send of [paymentHash] was recorded after [sendRecordMark] read [records]. */
    fun hasRecordedSendSince(records: Long, paymentHash: String?): Boolean =
        paymentHash != null && (sendRecordedAt[paymentHash.lowercase()] ?: 0L) > records

    /**
     * Spark's started sends whose invoice is unexpired. A sync can still resume one Spark committed but lists no row
     * for, also after a restart, so it is pending until its invoice expires, the rule `trySettleUncertainSend` holds.
     */
    private suspend fun unexpiredStartedSends(): Set<String> {
        val now = clock.now().epochSeconds
        val cache = cacheStore.data.first()
        return cache.sparkSendHashes.filterTo(mutableSetOf()) { hash ->
            cache.sparkSendExpiries[hash]?.let { it > now } == true
        }
    }

    private suspend fun startedSend(paymentHash: String?): String? {
        val hash = paymentHash?.lowercase() ?: return null
        return hash.takeIf { it in cacheStore.data.first().sparkSendHashes }
    }

    private fun List<Payment>.latestSend(paymentHash: String): Payment? =
        latestSend { it.htlcDetails.paymentHash.equals(paymentHash, ignoreCase = true) }

    private fun List<Payment>.latestSend(matches: (PaymentDetails.Lightning) -> Boolean): Payment? = filter {
        val details = it.details as? PaymentDetails.Lightning
        it.paymentType == SparkPaymentType.SEND && details != null && matches(details)
    }
        // A retry of a failed send shares its hash, so the outcome that settles it wins.
        .minByOrNull { SEND_STATUS_PRIORITY.indexOf(it.status) }

    /** Payment hashes of the Lightning payments Spark received. */
    suspend fun receivedLightningPaymentHashes(): Set<String> =
        lightningPaymentHashes { it.paymentType == SparkPaymentType.RECEIVE && it.status == PaymentStatus.COMPLETED }
            .orEmpty()

    /**
     * Payment hashes of the Lightning payments Spark sent or is sending, or null while Spark runs here but can't list
     * them, so a send it may have made isn't read as never tried.
     */
    suspend fun attemptedLightningPaymentHashes(): Set<String>? {
        if (!sparkService.isSupported) return emptySet()
        val unlisted = unexpiredStartedSends()
        val uncertain = uncertainSendHashes()
        val sends = lightningPaymentStatuses { it.paymentType == SparkPaymentType.SEND } ?: return null
        return sends.filterValues { it.any { status -> status != PaymentStatus.FAILED } }.keys +
            (unlisted - sends.keys) + uncertain
    }

    /** The statuses of Spark's Lightning payments that match [filter], by lowercase payment hash. */
    private suspend fun lightningPaymentStatuses(filter: (Payment) -> Boolean): Map<String, Set<PaymentStatus>>? =
        withContext(bgDispatcher) {
            if (!sparkService.isConnected) return@withContext null
            runSuspendCatching { sparkService.listPayments() }
                .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
                .getOrNull()
                ?.filter(filter)
                ?.mapNotNull { payment ->
                    (payment.details as? PaymentDetails.Lightning)?.htlcDetails?.paymentHash?.lowercase()
                        ?.let { it to payment.status }
                }
                ?.groupBy({ it.first }, { it.second })
                ?.mapValues { it.value.toSet() }
        }

    private suspend fun lightningPaymentHashes(filter: (Payment) -> Boolean): Set<String>? = withContext(bgDispatcher) {
        if (!sparkService.isConnected) return@withContext null
        runSuspendCatching { sparkService.listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
            .getOrNull()
            ?.filter(filter)
            ?.mapNotNull { (it.details as? PaymentDetails.Lightning)?.htlcDetails?.paymentHash?.lowercase() }
            ?.toSet()
    }

    /**
     * Records Spark's payments as activity, returning whether all were saved, so a failed write leaves the sync to
     * redo. With [settleJournal], after a sync that just succeeded, a journaled withdrawal the list still lacks never
     * sent, unless it is still being sent in this process or started less than [UNLISTED_WITHDRAWAL_GRACE] ago.
     */
    private suspend fun syncActivities(settleJournal: Boolean): Boolean {
        // Read after the records, as a send counts as in flight before it is recorded, so one of the two shows it.
        val recordsBefore = sendRecords.get()
        val inFlightBefore = sendsInFlight.keys.toSet()
        val payments = sparkService.listPayments()
        val isSaved = payments.map { recordActivity(it) }.all { it }
        releaseFailedSends(payments, recordsBefore, inFlightBefore)
        if (!settleJournal || isWithdrawing) return isSaved
        val journal = cacheStore.data.first().pendingSparkWithdrawal ?: return isSaved
        // A listed withdrawal whose activity failed to save keeps its journal for the next sync to retry.
        if (payments.any { journal.matches(it) }) return isSaved
        // Spark may list a withdrawal it just sent only on a later sync, so an unlisted one stays open for a while.
        if (clock.now().epochSeconds < journal.startedAt + UNLISTED_WITHDRAWAL_GRACE.inWholeSeconds) return isSaved
        Logger.warn("Dropped Spark withdrawal to '${journal.address}' that Spark never sent", context = TAG)
        if (clearJournal(journal)) {
            _withdrawalSettlements.emit(SparkWithdrawalSettlement(journal.address, journal.amountSats, null))
        }
        return isSaved
    }

    /**
     * Clears [journal] only while it is still the open one, so a stale settle never drops a newer withdrawal's. Returns
     * whether it cleared it: only then is the settlement this attempt's to announce.
     */
    private suspend fun clearJournal(journal: PendingSparkWithdrawal, sentTxid: String? = null): Boolean {
        var cleared = false
        cacheStore.update {
            cleared = it.pendingSparkWithdrawal == journal
            if (!cleared) return@update it
            // The request's proof completes after the journal clears, so the sent withdrawal it records outlives it.
            val sent = sentTxid?.let { txid -> SentSparkWithdrawal(journal.address, journal.amountSats, txid) }
            it.copy(
                pendingSparkWithdrawal = null,
                sentSparkWithdrawals = (it.sentSparkWithdrawals + listOfNotNull(sent)).takeLast(MAX_SENT_WITHDRAWALS),
            )
        }
        return cleared
    }

    private suspend fun releaseFailedSends(
        payments: List<Payment>,
        recordsBefore: Long,
        inFlightBefore: Set<String>,
    ) {
        val sendHashes = { status: PaymentStatus ->
            payments.mapNotNullTo(mutableSetOf()) { it.sendHash(status)?.lowercase() }
        }
        // A send in flight, also one that ended while this list was read, or recorded after it was read while the
        // activities were recorded, may be missing from it or show only its earlier failed attempt, so it stays, as
        // does one whose outcome Spark didn't report, also from before a restart.
        val live = sendHashes(PaymentStatus.PENDING) + sendsInFlight.keys + inFlightBefore +
            recordedSince(recordsBefore) + uncertainSendHashes()
        val paid = sendHashes(PaymentStatus.COMPLETED) - live
        cacheStore.trimSparkSendHashes(
            paid = paid,
            failed = sendHashes(PaymentStatus.FAILED) - paid - live,
            listed = payments.mapNotNullTo(mutableSetOf()) { it.sendHash()?.lowercase() },
            live = live,
        )
        // A send that failed, also while the app was closed, no longer holds the node off its invoice, unless a
        // retry of it is still live.
        val failed = payments.mapNotNull { it.sendHash(PaymentStatus.FAILED) }.distinct()
            .filter { payments.latestSend(it)?.status == PaymentStatus.FAILED }
        if (failed.isEmpty()) return
        // A retry can start while the list is recorded, so the release reads Spark's sends again first.
        val recordsBeforeCurrent = sendRecords.get()
        val inFlightBeforeCurrent = sendsInFlight.keys.toSet()
        val current = runSuspendCatching { sparkService.listPayments() }.getOrNull() ?: return
        val liveCurrent = sendsInFlight.keys + inFlightBeforeCurrent + recordedSince(recordsBeforeCurrent) +
            uncertainSendHashes()
        failed
            .filter { it.lowercase() !in liveCurrent }
            .filter { current.latestSend(it)?.status == PaymentStatus.FAILED }
            .forEach { cacheStore.removeSparkSendHash(it) }
    }

    private fun recordedSince(records: Long): Set<String> = sendRecordedAt.filterValues { it > records }.keys

    /** The payment hash of this send, at [status] when given. */
    private fun Payment.sendHash(status: PaymentStatus? = null): String? = (details as? PaymentDetails.Lightning)
        ?.takeIf { paymentType == SparkPaymentType.SEND && (status == null || this.status == status) }
        ?.htlcDetails?.paymentHash

    private suspend fun onSdkEvent(event: SdkEvent) {
        runSuspendCatching {
            when (event) {
                is SdkEvent.Synced -> {
                    // Spark also reports Synced for a partial or skipped sync, while its wallet sync still fails.
                    if (hasSyncedSince(null)) _state.update { it.copy(hasSynced = true) }
                    refresh()
                    // A send whose outcome stayed unknown past its polling settles on a later sync.
                    uncertainSends.keys.toList().forEach { trySettleUncertainSend(it) }
                    // A journal left open past its grace period would block every later withdrawal until a restart.
                    // Spark emits Synced after a sync that never reached it too, so only one since the journal
                    // drops it.
                    cacheStore.data.first().pendingSparkWithdrawal?.let {
                        syncActivities(settleJournal = hasSyncedSince(it.startedAt.toULong()))
                    }
                }
                is SdkEvent.PaymentSucceeded -> onPayment(event.payment)
                is SdkEvent.PaymentFailed -> onPayment(event.payment)
                is SdkEvent.PaymentPending -> recordActivity(event.payment, settlesJournal = false)
                is SdkEvent.UnclaimedDeposits -> Logger.warn(
                    "Found '${event.unclaimedDeposits.size}' unclaimed Spark deposits",
                    context = TAG,
                )
                is SdkEvent.NewDeposits -> refresh()
                is SdkEvent.UnilateralExitStateChanged -> _exitStateGeneration.update { it + 1 }
                is SdkEvent.ClaimedDeposits -> refresh()
                else -> Unit
            }
        }.onFailure {
            Logger.error("Failed to handle Spark event '$event'", it, context = TAG)
        }
    }

    private suspend fun onPayment(payment: Payment) {
        // The event carries its outcome, so a failed balance read never holds back its settlement.
        runSuspendCatching { refresh() }
            .onFailure { Logger.warn("Failed to refresh Spark after payment '${payment.id}'", it, context = TAG) }
        recordActivity(payment, settlesJournal = false)
        // The event settles a send whose outcome was unknown, so retries of its invoice are no longer held.
        payment.sendHash()?.let {
            uncertainSends.remove(it.lowercase())
            cacheStore.removeSparkUncertainSend(it)
        }
        payment.sendHash(PaymentStatus.FAILED)?.let { hash ->
            val latest = listedLightningSend(hash)
            val isReleased = latest == SparkLightningSend.NotFound ||
                (latest as? SparkLightningSend.Found)?.status == PaymentStatus.FAILED
            if (isReleased) cacheStore.removeSparkSendHash(hash)
        }
        payment.toNodeEvent()?.let { _nodeEvents.emit(it) }
    }

    /**
     * Records [payment] as activity and returns whether it is saved; a failed write is kept in [unsavedPayments]. Only
     * Spark's list or its send response [settlesJournal]: a payment event can come before the list holds the
     * withdrawal, which recovery reads, so its journal stays open until the list shows it.
     */
    private suspend fun recordActivity(payment: Payment, settlesJournal: Boolean = true): Boolean {
        (payment.details as? PaymentDetails.Withdraw)?.let { return recordWithdrawal(payment, it, settlesJournal) }
        val lightning = payment.details as? PaymentDetails.Lightning ?: return true
        if (payment.status == PaymentStatus.PENDING && payment.paymentType == SparkPaymentType.RECEIVE) {
            return true
        }
        val id = lightning.htlcDetails.paymentHash
        // A failed read would drop the stored note, contact and seen time, so the write waits for the next refresh.
        val stored = activityRepo.get().getActivity(id).getOrElse {
            unsavedPayments.add(payment.id)
            return false
        }
        val existing = (stored as? Activity.Lightning)?.v1
        // Another attempt of the same invoice, on either rail, already paid it.
        if (existing?.status == PaymentState.SUCCEEDED && payment.status != PaymentStatus.COMPLETED) return true
        val activity = LightningActivity.create(
            id = id,
            txType = when (payment.paymentType) {
                SparkPaymentType.SEND -> PaymentType.SENT
                SparkPaymentType.RECEIVE -> PaymentType.RECEIVED
            },
            status = when (payment.status) {
                PaymentStatus.COMPLETED -> PaymentState.SUCCEEDED
                PaymentStatus.PENDING -> PaymentState.PENDING
                PaymentStatus.FAILED -> PaymentState.FAILED
            },
            value = payment.amount.toLong().toULong(),
            invoice = lightning.invoice,
            timestamp = payment.timestamp,
            fee = payment.fees.toLong().toULong(),
            // A note stored on the activity, such as an LNURL comment, outlives the invoice's description.
            message = existing?.message?.takeIf { it.isNotEmpty() } ?: lightning.description.orEmpty(),
            preimage = lightning.htlcDetails.preimage,
            contact = existing?.contact ?: receivedContact(payment, id),
            seenAt = existing?.seenAt,
        )
        val saved = activityRepo.get().upsertActivity(Activity.Lightning(activity))
            .let { it.isSuccess || it.exceptionOrNull() is ActivityDeletedError }
        if (!saved) {
            unsavedPayments.add(payment.id)
            return false
        }
        unsavedPayments.remove(payment.id)
        if (payment.paymentType == SparkPaymentType.SEND && payment.fees.toLong() == 0L) {
            sendsRecordedWithoutFee.add(payment.id)
        } else {
            sendsRecordedWithoutFee.remove(payment.id)
        }
        return true
    }

    /**
     * Records a withdrawal to a Paykit contact as an on-chain send, settling the journal entry it matches. Transfers to
     * Savings have no row here: the node lists their arrival as the transfer.
     */
    private suspend fun recordWithdrawal(
        payment: Payment,
        withdraw: PaymentDetails.Withdraw,
        settlesJournal: Boolean,
    ): Boolean {
        val txid = withdraw.txId
        // A failed read would drop the stored contact and seen time, so the write waits for the next refresh.
        val stored = activityRepo.get().getActivity(txid).getOrElse {
            unsavedPayments.add(payment.id)
            return false
        }
        val existing = (stored as? Activity.Onchain)?.v1?.takeIf { it.txType == PaymentType.SENT }
        val journal = cacheStore.data.first().pendingSparkWithdrawal?.takeIf { it.matches(payment) }
        if (existing == null && journal == null) return true
        val activity = OnchainActivity.create(
            id = txid,
            txType = PaymentType.SENT,
            txId = txid,
            value = payment.amount.toLong().toULong(),
            fee = payment.fees.toLong().toULong(),
            address = withdraw.destinationAddress,
            timestamp = payment.timestamp,
            confirmed = payment.status == PaymentStatus.COMPLETED,
            doesExist = payment.status != PaymentStatus.FAILED,
            contact = existing?.contact ?: journal?.contact,
            seenAt = existing?.seenAt,
        )
        // The journal stays until the activity is saved, so a failed save is retried from Spark's list and on refresh.
        val upserted = activityRepo.get().upsertActivity(Activity.Onchain(activity))
            .onFailure { Logger.warn("Failed to record Spark withdrawal '$txid'", it, context = TAG) }
        val isDeleted = upserted.exceptionOrNull() is ActivityDeletedError
        val saved = upserted.isSuccess || isDeleted
        if (!saved) {
            unsavedPayments.add(payment.id)
            return false
        }
        unsavedPayments.remove(payment.id)
        // A pending withdrawal can still fail, so only a completed or failed one closes its journal.
        if (journal != null && settlesJournal && payment.status != PaymentStatus.PENDING) {
            // The tags picked on Confirm travel with the journal, so a recovered withdrawal keeps them too.
            // A failed tag write keeps the journal, so the next refresh tags it again; a deleted activity takes none.
            if (journal.tags.isNotEmpty() && !isDeleted) {
                activityRepo.get().addTagsToActivity(txid, journal.tags).onFailure {
                    Logger.warn("Failed to tag Spark withdrawal '$txid'", it, context = TAG)
                    unsavedPayments.add(payment.id)
                    return false
                }
            }
            val sentTxid = txid.takeIf { payment.status == PaymentStatus.COMPLETED }
            if (clearJournal(journal, sentTxid)) {
                _withdrawalSettlements.emit(SparkWithdrawalSettlement(journal.address, journal.amountSats, sentTxid))
            }
        }
        return true
    }

    private suspend fun receivedContact(payment: Payment, paymentHash: String): String? {
        if (payment.paymentType != SparkPaymentType.RECEIVE || payment.status == PaymentStatus.FAILED) return null
        if (cacheStore.data.first().isContactDetached(paymentHash, WalletScope.default)) return null
        return runSuspendCatching { contactResolver.get().contactPublicKeyForPrivateInvoicePaymentHash(paymentHash) }
            .onFailure { Logger.warn("Failed to resolve contact of Spark receive '$paymentHash'", it, context = TAG) }
            .getOrNull()
    }

    private fun Payment.toNodeEvent(): Event? {
        val lightning = details as? PaymentDetails.Lightning ?: return null
        val hash = lightning.htlcDetails.paymentHash
        val amountMsat = amount.toLong().toULong() * MSATS_PER_SAT
        return when (paymentType to status) {
            SparkPaymentType.RECEIVE to PaymentStatus.COMPLETED ->
                Event.PaymentReceived(hash, hash, amountMsat, emptyList())
            SparkPaymentType.SEND to PaymentStatus.COMPLETED ->
                Event.PaymentSuccessful(
                    hash,
                    hash,
                    lightning.htlcDetails.preimage,
                    fees.toLong().toULong() * MSATS_PER_SAT
                )
            SparkPaymentType.SEND to PaymentStatus.FAILED ->
                Event.PaymentFailed(hash, hash, PaymentFailureReason.UNEXPECTED_ERROR)
            else -> null
        }
    }
}

private fun PrepareSendPaymentResponse.withdrawalQuote(): SendOnchainFeeQuote =
    (paymentMethod as SendPaymentMethod.BitcoinAddress).feeQuote

/** A withdrawal to a Paykit contact that started and that Spark's payment list has not settled yet. */
@Serializable
data class PendingSparkWithdrawal(
    val address: String,
    val amountSats: ULong,
    val contact: String?,
    /** Withdrawals to [address] listed before this attempt, which never settle it. */
    val earlierTxids: Set<String> = emptySet(),
    val tags: List<String> = emptyList(),
    /** Epoch seconds the attempt started, which keeps it open while Spark may still list it. */
    val startedAt: Long = 0,
) {
    fun matches(payment: Payment): Boolean {
        val withdraw = payment.details as? PaymentDetails.Withdraw ?: return false
        return address == withdraw.destinationAddress && amountSats == payment.amount.toLong().toULong() &&
            withdraw.txId !in earlierTxids
    }
}

/** A withdrawal to a Paykit contact that Spark sent as [txid]. */
@Serializable
data class SentSparkWithdrawal(
    val address: String,
    val amountSats: ULong,
    val txid: String,
)

/** A journaled withdrawal to [address] closed: sent as [txid], or never sent when [txid] is null. */
data class SparkWithdrawalSettlement(
    val address: String,
    val amountSats: ULong,
    val txid: String?,
)

/** What became of a Spark withdrawal attempt, from Spark's payment list. */
sealed interface SparkWithdrawalOutcome {
    data class Sent(val txid: String) : SparkWithdrawalOutcome

    /** Spark synced, holds no withdrawal for the attempt and has none settling: it never sent. */
    data object Unsent : SparkWithdrawalOutcome

    data object Unknown : SparkWithdrawalOutcome
}

private class PreparedWithdrawal(
    val address: String,
    val balanceSats: ULong,
    val response: PrepareSendPaymentResponse,
)

data class SparkState(
    val isSupported: Boolean = false,
    val isReady: Boolean = false,
    /** Spark synced its payments at least once since it connected, so its payment list is complete. */
    val hasSynced: Boolean = false,
    /** What Spark read, which sends and withdrawals use. */
    val balanceSats: ULong = 0uL,
    /**
     * The balance shown while Spark reads 0 after a failed withdrawal or a failed sync, with the funds likely still
     * there; null once Spark reads a balance again or syncs after the failure. Never offered to a send.
     */
    val keptBalanceSats: ULong? = null,
    /** Deposits seen on-chain that Spark claims into [balanceSats] after 3 confirmations. */
    val pendingDepositSats: ULong = 0uL,
    val deposits: ImmutableList<SparkDeposit> = persistentListOf(),
    val withdrawals: ImmutableList<SparkWithdrawal> = persistentListOf(),
) {
    /** The most Spark can send once its service fee is paid, so a payment near the full balance isn't offered. */
    val sendableSats: ULong get() = SparkFees.sendableSats(balanceSats)

    /** The balance shown in Spending and on the Spark connection, which [keptBalanceSats] stands in for. */
    val shownBalanceSats: ULong get() = keptBalanceSats ?: balanceSats
}

object SparkFees {
    /** Spark keeps 0.25% of a Lightning send as its service fee, on top of routing. */
    const val SERVICE_FEE_PPM = 2_500uL

    private const val PPM = 1_000_000uL

    /** The largest amount whose fee, rounded up, fits beside it: `amount + ceil(amount * ppm / 1e6) <= balance`. */
    fun sendableSats(balanceSats: ULong): ULong {
        val sendable = balanceSats.safe() * PPM.safe() / (PPM + SERVICE_FEE_PPM)
        return if (sendable == 0uL || sendable.safe() + serviceFeeSats(sendable).safe() <= balanceSats) {
            sendable
        } else {
            sendable - 1uL
        }
    }

    fun serviceFeeSats(amountSats: ULong): ULong = (amountSats.safe() * SERVICE_FEE_PPM.safe() + (PPM - 1uL)) / PPM
}

/** Spark's record of a Lightning send. */
sealed interface SparkLightningSend {
    /** Spark is supported but cannot list its payments yet. */
    data object Unavailable : SparkLightningSend

    data object NotFound : SparkLightningSend

    data class Found(val status: PaymentStatus, val preimage: String?) : SparkLightningSend
}

/** A Transfer to Savings out of Spark, with what Spark kept for it. */
data class SparkWithdrawal(
    val txid: String,
    val amountSats: ULong,
    val feeSats: ULong,
)

/** An on-chain deposit into Spark: pending until Spark claims it after 3 confirmations. */
data class SparkDeposit(
    val txid: String,
    val amountSats: ULong,
    val isClaimed: Boolean,
    val feeSats: ULong = 0uL,
    val claimedAt: ULong? = null,
)

/**
 * How a deposit into Spark can be claimed. The provider decides per deposit whether to credit it early, at 0
 * confirmations or 1-2, for a fee above the standard claim's at 3 confirmations.
 */
sealed interface SparkClaimOffer {
    val txid: String
    val vout: UInt

    /** No early claim is offered, so Spark claims it at [confirmations] for [feeSats]. */
    data class Standard(
        override val txid: String,
        override val vout: UInt,
        val feeSats: ULong,
        val confirmations: UInt,
    ) : SparkClaimOffer

    /** The early claim fits this wallet's fee cap, so Spark claims it at [confirmations] by itself. */
    data class Automatic(
        override val txid: String,
        override val vout: UInt,
        val feeSats: ULong,
        val confirmations: UInt,
    ) : SparkClaimOffer

    /** The early claim costs more than the cap, so it waits for the user to accept [earlyFeeSats]. */
    data class Choice(
        override val txid: String,
        override val vout: UInt,
        val earlyFeeSats: ULong,
        val earlyConfirmations: UInt,
        val standardFeeSats: ULong,
        val standardConfirmations: UInt,
    ) : SparkClaimOffer

    companion object {
        fun from(txid: String, vout: UInt, quote: FetchClaimDepositQuoteResponse): SparkClaimOffer {
            val mature = quote.mature
            val early = quote.instant
                ?: return Standard(txid, vout, mature.feeSats, mature.confirmationsRequired)
            val cap = early.maxFeeSats
            if (cap != null && early.feeSats <= cap) {
                return Automatic(txid, vout, early.feeSats, early.confirmationsRequired)
            }
            return Choice(
                txid = txid,
                vout = vout,
                earlyFeeSats = early.feeSats,
                earlyConfirmations = early.confirmationsRequired,
                standardFeeSats = mature.feeSats,
                standardConfirmations = mature.confirmationsRequired,
            )
        }
    }
}

/** What accepting an early claim did. */
sealed interface SparkEarlyClaim {
    /** The claim is submitted and the deposit reaches the balance shortly. */
    data object Claimed : SparkEarlyClaim

    /** Not offered at this depth yet; Spark claims it early once a confirmation brings the offer. */
    data object Waiting : SparkEarlyClaim

    /** The provider now asks [requiredFeeSats], more than was accepted. */
    data class Repriced(val requiredFeeSats: ULong) : SparkEarlyClaim

    /** The provider declined it, so Spark claims the deposit at 3 confirmations. */
    data object Declined : SparkEarlyClaim

    companion object {
        fun from(outcome: ClaimDepositOutcome): SparkEarlyClaim = when (outcome) {
            is ClaimDepositOutcome.Settled, ClaimDepositOutcome.Submitted -> Claimed
            is ClaimDepositOutcome.Deferred -> when (val reason = outcome.reason) {
                ClaimDeferredReason.NoEarlyClaimAvailable -> Waiting
                is ClaimDeferredReason.MaxFeeExceeded -> Repriced(reason.requiredFeeSats)
                is ClaimDeferredReason.ProviderDeclined -> Declined
            }
        }
    }
}

/** How fast the user wants a deposit in the spending balance. */
enum class SparkClaimSpeed {
    /** Pays the provider to add it before confirmations, up to the ceiling the user accepted. */
    Instant,

    /** Waits for 3 confirmations, at the lower fee of the claim at maturity. */
    Later,
}

/** Where the claim of a deposit the user sent stands. */
sealed interface SparkDepositClaim {
    /** Spark has not listed the deposit yet, or offers no early claim at this depth. */
    data object Looking : SparkDepositClaim

    /** The early claim is under way and the deposit reaches the balance shortly. */
    data object Adding : SparkDepositClaim

    /** The deposit is in the spending balance. */
    data object Claimed : SparkDepositClaim

    /** The early claim now costs more than the user accepted; it waits for the user to pay it or wait. */
    data class Repriced(val offer: SparkClaimOffer.Choice) : SparkDepositClaim

    /** No early claim for this deposit, so Spark adds it after 3 confirmations. */
    data object Unavailable : SparkDepositClaim

    /** The user chose to wait for 3 confirmations. */
    data object Later : SparkDepositClaim

    /** Spark did not list the deposit in time; it still adds it after 3 confirmations. */
    data object NotSeen : SparkDepositClaim

    /** Whether nothing more is asked of the provider for this deposit. */
    val isFinal: Boolean get() = this !is Looking && this !is Adding
}

/** What an Instant transfer does with the claim [SparkClaimOffer] Spark has for its deposit. */
sealed interface SparkClaimStep {
    /** Not listed yet: look again. */
    data object KeepLooking : SparkClaimStep

    /** The early claim fits the user's cap, so Spark makes it by itself. */
    data object Adding : SparkClaimStep

    /** No early claim on offer. */
    data object Unavailable : SparkClaimStep

    /** Within the ceiling the user accepted: claim it now. */
    data class ClaimEarly(val offer: SparkClaimOffer.Choice) : SparkClaimStep

    /** Above the ceiling: ask the user. */
    data class Ask(val offer: SparkClaimOffer.Choice) : SparkClaimStep

    companion object {
        fun of(offer: SparkClaimOffer?, ceilingSats: ULong): SparkClaimStep = when (offer) {
            null -> KeepLooking
            is SparkClaimOffer.Automatic -> Adding
            is SparkClaimOffer.Standard -> Unavailable
            is SparkClaimOffer.Choice if offer.earlyFeeSats <= ceilingSats -> ClaimEarly(offer)
            is SparkClaimOffer.Choice -> Ask(offer)
        }
    }
}

internal fun SdkException.InvalidInput.isBelowMinimum(): Boolean =
    v1.startsWith("Amount is below the minimum", ignoreCase = true)

/** Spark refuses an expired Lightning invoice with this input error, at prepare and again at send. */
internal fun Throwable.isInvoiceExpired(): Boolean =
    this is SdkException.InvalidInput && v1.contains("invoice has expired", ignoreCase = true)

/** What accepting the early claim of [offer] leaves the deposit at. */
fun SparkEarlyClaim.toDepositClaim(offer: SparkClaimOffer.Choice): SparkDepositClaim = when (this) {
    SparkEarlyClaim.Claimed -> SparkDepositClaim.Adding
    SparkEarlyClaim.Waiting -> SparkDepositClaim.Looking
    is SparkEarlyClaim.Repriced -> SparkDepositClaim.Repriced(offer.copy(earlyFeeSats = requiredFeeSats))
    SparkEarlyClaim.Declined -> SparkDepositClaim.Unavailable
}
