package to.bitkit.repositories

import breez_sdk_spark.AutoOptimizationEvent
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
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
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

        /** How often to look at a deposit until its Instant claim is decided. */
        private val CLAIM_POLL_INTERVAL = 5.seconds

        /** Looks for 5 minutes before telling the user Spark hasn't seen the deposit yet. */
        private const val CLAIM_POLL_ATTEMPTS = 60

        private val SEND_STATUS_PRIORITY = listOf(PaymentStatus.COMPLETED, PaymentStatus.PENDING, PaymentStatus.FAILED)

        /** How long the SDK leaves the counter-swap of a leaf swap cut short unclaimed (its 300 s), plus a margin. */
        private val COUNTER_SWAP_GRACE = 330.seconds

        /** The longest an exit quote waits for a running leaf optimization to end. */
        private val OPTIMIZATION_WAIT = 2.minutes

        /** How long a send's settlement waits for the send screen to track it as pending before it reports. */
        private val PENDING_TRACK_WAIT = 10.seconds

        /** Tries to learn the outcome of a send whose result was lost while Spark cannot list its payments. */
        private const val SETTLE_ATTEMPTS = 10

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

    /** The kept balance when an exit's hold started, shown again should the exit pay nothing. */
    @Volatile
    private var keptBeforeExit: ULong? = null

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

    /** The remote exit data backup was merged in, or there was none, so an upload cannot drop what it held. */
    @Volatile
    var hasMergedRemoteExitState = false
        private set

    /** The SDK is swapping leaves with the operators; their value is in flight until it ends. */
    private val isOptimizing = MutableStateFlow(false)

    private val spendsInFlight = MutableStateFlow(0)

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

    /**
     * Counts Spark's connections and changes to its unilateral exit data, so the exit data backup is redone whenever it
     * moved past the one last backed up, also when it moved while nothing observed it.
     */
    val exitStateGeneration: StateFlow<Long> = _exitStateGeneration.asStateFlow()

    /** The wallet Spark last started for. */
    @Volatile
    var walletIndex: Int = 0
        private set

    private val _walletWiped = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Fires with the wallet index once its Spark wallet, and any emergency exit of it, was wiped. */
    val walletWiped: SharedFlow<Int> = _walletWiped.asSharedFlow()

    suspend fun start(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        if (!sparkService.isSupported) return@withContext Result.success(Unit)
        this@SparkRepo.walletIndex = walletIndex
        runSuspendCatching {
            if (isCollectingEvents.compareAndSet(false, true)) {
                scope.launch { sparkService.events.collect { onSdkEvent(it) } }
            }
            restoreUncertainSends()
            val isExiting = exitStore(walletIndex).isActive()
            _state.update { it.copy(isExiting = isExiting) }
            val connected = sparkService.connect(walletIndex, isExiting)
            importRestoredExitState(walletIndex)
            // The SDK starts syncing inside build(), before the listener exists, so its first Synced can be missed.
            runSuspendCatching { sparkService.syncWallet() }
                .onSuccess { if (hasSyncedSince(null)) _state.update { it.copy(hasSynced = true) } }
                .onFailure { Logger.warn("Failed to sync Spark after connecting", it, context = TAG) }
            if (connected) _exitStateGeneration.update { it + 1 }
            refresh()
            if (!hasSyncedActivities) hasSyncedActivities = syncActivities()
        }.onFailure {
            Logger.error("Failed to start Spark wallet", it, context = TAG)
        }
    }

    /** Holds Spark's start off for the whole wallet wipe. */
    fun setWiping(isWiping: Boolean) = run { sparkService.isWiping = isWiping }

    /**
     * Disconnects Spark and deletes its storage, its emergency exit records and its in-memory state. Spark stays
     * connected for the life of the process otherwise, so a node stopped in background keeps Lightning available.
     */
    suspend fun wipe(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        claimJobs.values.forEach { it.cancel() }
        claimJobs.clear()
        earlyClaimVouts.clear()
        preparedWithdrawal = null
        withdrawalTxids.clear()
        withdrawalFailedAtSecs = null
        keptBeforeExit = null
        broadcastDeposits.clear()
        sendsRecordedWithoutFee.clear()
        unsavedPayments.clear()
        sendsInFlight.clear()
        uncertainSends.clear()
        hasSyncedActivities = false
        hasMergedRemoteExitState = false
        _depositClaims.update { emptyMap() }
        _state.update { SparkState(isSupported = sparkService.isSupported) }
        val storage = runSuspendCatching { sparkService.wipeStorage(walletIndex) }
        val exitRecords = runSuspendCatching {
            exitStore(walletIndex).delete()
            SparkExitLostLeafStore(File(Env.sparkExitLostLeavesPath(walletIndex))).delete()
        }
        _walletWiped.emit(walletIndex)
        storage.mapCatching { exitRecords.getOrThrow() }
    }

    /**
     * Takes Spark off the Lightning rail while an emergency exit runs, so no payment moves leaves the exit is
     * spending, and connects Spark again so its leaf optimization follows: off while exiting, on once it ends.
     */
    suspend fun setExiting(isExiting: Boolean, reconnect: Boolean = false): Result<Unit> = withContext(bgDispatcher) {
        val isCurrent = _state.value.isExiting == isExiting && sparkService.isConnected
        // An ended exit forgets the balance kept before it: a cancel already showed it again.
        if (!isExiting) keptBeforeExit = null
        if (isCurrent && !reconnect) return@withContext Result.success(Unit)
        _state.update { it.copy(isExiting = isExiting, keptBalanceSats = it.keptBalanceSats.takeUnless { isExiting }) }
        runSuspendCatching {
            onOptimizationCutShort()
            sparkService.disconnect()
            sparkService.connect(walletIndex, isExiting)
            refresh()
        }.onFailure {
            Logger.error("Failed to reconnect Spark with exit '$isExiting'", it, context = TAG)
        }
    }

    /**
     * Holds Spark for an exit about to start: no sends, receives, spending or kept balance, in the same step the exit's
     * transfer starts counting it. [setExiting] reconnects it for the exit afterwards.
     */
    fun holdForExit() {
        keptBeforeExit = _state.value.keptBalanceSats
        _state.update { it.copy(isExiting = true, keptBalanceSats = null) }
    }

    /**
     * Releases the hold of an exit that paid nothing: its start failed before anything was saved, or it was cancelled.
     * The balance kept before it shows again while Spark still reads 0.
     */
    fun releaseExitHold() {
        val kept = keptBeforeExit
        keptBeforeExit = null
        _state.update { it.copy(isExiting = false, keptBalanceSats = if (it.balanceSats == 0uL) kept else null) }
    }

    /** Whether an exit quote has to wait first: a leaf optimization runs, or one ran or was cut short recently. */
    suspend fun needsSettling(): Boolean =
        isOptimizing.value || cacheStore.data.first().sparkOptimizationActivityAt != null

    /**
     * Settles the leaves for an emergency exit quote, which exits the leaves listed then. The balance leaves out an
     * unclaimed counter-swap, and a leaf optimization cut short leaves its counter-swap unclaimed for the SDK's 300 s
     * grace. So: wait for a running optimization, then until the last one is [COUNTER_SWAP_GRACE] old, then sync,
     * which claims counter-swaps past the grace.
     */
    suspend fun settleForExit(): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            awaitOptimizationIdle()
            cacheStore.data.first().sparkOptimizationActivityAt?.let {
                delay(Instant.fromEpochMilliseconds(it) + COUNTER_SWAP_GRACE - clock.now())
            }
            sparkService.syncWallet()
            cacheStore.update { it.copy(sparkOptimizationActivityAt = null) }
            refresh()
        }
    }

    /** Waits, up to [OPTIMIZATION_WAIT], for a running leaf optimization to end. */
    suspend fun awaitOptimizationIdle() {
        if (withTimeoutOrNull(OPTIMIZATION_WAIT) { isOptimizing.first { !it } } == null) {
            Logger.warn("Gave up waiting for a Spark leaf optimization to end", context = TAG)
        }
    }

    /**
     * Waits for the sends that passed their exit check before [holdForExit] to finish, at most [OPTIMIZATION_WAIT], so
     * an exit starting quotes the leaves they leave.
     */
    suspend fun awaitSpendsIdle() {
        if (withTimeoutOrNull(OPTIMIZATION_WAIT) { spendsInFlight.first { it == 0 } } == null) {
            Logger.warn("Gave up waiting for Spark sends in flight to end", context = TAG)
        }
    }

    private suspend fun <T> spending(send: suspend () -> T): T {
        spendsInFlight.update { it + 1 }
        try {
            return send()
        } finally {
            spendsInFlight.update { it - 1 }
        }
    }

    private fun ensureNotExiting() {
        if (_state.value.isExiting) throw SparkError.SendRefused("Spark is in an emergency exit")
    }

    private suspend fun onOptimization(event: AutoOptimizationEvent) {
        when (event) {
            is AutoOptimizationEvent.Started, is AutoOptimizationEvent.RoundCompleted -> {
                isOptimizing.update { true }
                stampOptimizationActivity()
            }
            AutoOptimizationEvent.Cancelled, is AutoOptimizationEvent.Failed -> {
                isOptimizing.update { false }
                stampOptimizationActivity()
            }
            AutoOptimizationEvent.Completed, AutoOptimizationEvent.Skipped -> {
                isOptimizing.update { false }
                cacheStore.update { it.copy(sparkOptimizationActivityAt = null) }
            }
        }
    }

    private suspend fun onOptimizationCutShort() {
        if (!isOptimizing.value) return
        isOptimizing.update { false }
        stampOptimizationActivity()
    }

    private suspend fun stampOptimizationActivity() {
        val now = clock.now().toEpochMilliseconds()
        cacheStore.update { it.copy(sparkOptimizationActivityAt = now) }
    }

    private fun exitStore(walletIndex: Int) = SparkExitStore(File(Env.sparkExitRecordPath(walletIndex)))

    fun canReceive(): Boolean = _state.value.isReady && !_state.value.isExiting

    fun canSend(amountSats: ULong): Boolean = _state.value.isReady && _state.value.sendableSats >= amountSats

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
            ensureNotExiting()
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
                spending {
                    // The checks above and saving the hash suspend, so the deadline and an exit that started
                    // meanwhile are checked last, before the send.
                    runCatching {
                        ensurePaymentDeadline(paymentDeadlineAt)
                        ensureNotExiting()
                    }.onFailure {
                        cacheStore.removeSparkSendHash(paymentHash)
                        throw it
                    }
                    runSuspendCatching { sparkService.send(prepared) }
                }
            } finally {
                sendsInFlight.remove(paymentHash.lowercase())
            }
            val payment = sent.getOrElse {
                // The SDK checks the expiry again before anything goes out, so an expired invoice left nothing.
                if (it.isInvoiceExpired()) {
                    cacheStore.removeSparkSendHash(paymentHash)
                    throw SparkError.InvoiceExpired(it)
                }
                // Spark selects the leaves to spend before anything goes to the operators, so a short balance left
                // nothing, for instance when an earlier send spent it after the balance was read.
                if (it is SdkException.InsufficientFunds) {
                    cacheStore.removeSparkSendHash(paymentHash)
                    throw SparkError.SendRefused("Spark balance can't cover the payment", it)
                }
                Logger.warn("Failed to learn the outcome of Spark send '$paymentHash'", it, context = TAG)
                settleUncertainSend(paymentHash, expiresAt)
                throw PaymentPendingException(paymentHash)
            }
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
            ensureNotExiting()
            val prepared = prepareWithdrawAll(address)
            if (prepared.feeSats() > confirmedFeeSats) throw SparkError.WithdrawalFeeChanged()
            val payment = spending {
                // Preparing suspends, so an exit can start meanwhile.
                ensureNotExiting()
                sparkService.send(prepared.response)
            }
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
     * first sync can miss what the wallet holds. Also null while an emergency exit runs: the operators stop reporting
     * the leaves it moves, so the backup from before it is kept.
     */
    suspend fun exitBackup(): Result<SparkExitBackupV1?> = withContext(bgDispatcher) {
        runSuspendCatching {
            val network = sparkService.networkName
            if (!sparkService.isConnected || network == null || !_state.value.hasSynced) return@runSuspendCatching null
            if (_state.value.isExiting || exitStore(walletIndex).isActive()) {
                Logger.info("Skipped the Spark exit data backup while an exit runs", context = TAG)
                return@runSuspendCatching null
            }
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
        val failedAt = withdrawalFailedAtSecs
        val hasSyncedSince = failedAt != null && info.lastSyncError == null && (info.lastSyncedAt ?: 0uL) > failedAt
        if (info.balanceSats > 0uL || hasSyncedSince) withdrawalFailedAtSecs = null
        val isStale = withdrawalFailedAtSecs != null || info.lastSyncError != null
        _state.update {
            it.copy(
                isReady = true,
                balanceSats = info.balanceSats,
                // An exit moves the balance to savings, so a zero read during one keeps nothing.
                keptBalanceSats = it.shownBalanceSats.takeIf { kept ->
                    info.balanceSats == 0uL && isStale && kept > 0uL && !it.isExiting
                },
                pendingDepositSats = pending.sumOf { deposit -> deposit.amountSats },
                deposits = (pending + claimed).toImmutableList(),
                withdrawals = payments.toWithdrawals(),
                recoverableFundsSats = info.recoverableFundsSats,
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

    /**
     * Marks the on-chain receive [txid] as a transfer from Spending when it is a Spark withdrawal, so it shows no
     * Received sheet and reads as a transfer in Activity. Returns whether it is one.
     */
    suspend fun markWithdrawalAsTransfer(txid: String): Boolean = withContext(bgDispatcher) {
        val isWithdrawal = txid in withdrawalTxids || _state.value.withdrawals.any { it.txid == txid }
        if (!isWithdrawal) return@withContext false
        val activities = activityRepo.get()
        activities.getOnchainActivityByTxId(txid)?.takeUnless { it.isTransfer }?.let {
            activities.updateActivity(it.id, Activity.Onchain(it.copy(isTransfer = true)))
        }
        true
    }

    private suspend fun markTransfers(payments: List<Payment>) {
        val activities = activityRepo.get()
        payments.filter { it.status != PaymentStatus.FAILED }
            .mapNotNull { (it.details as? PaymentDetails.Withdraw)?.txId }
            .forEach { txid ->
                val activity = activities.getOnchainActivityByTxId(txid)?.takeUnless { it.isTransfer } ?: return@forEach
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
            .associateWith { hash -> requireNotNull(payments.latestSend(hash)).status }
        val listedHashes = listed.keys.mapTo(mutableSetOf()) { it.lowercase() }
        listed + (unlisted - listedHashes).associateWith { PaymentStatus.PENDING }
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
        val sends = lightningPaymentStatuses { it.paymentType == SparkPaymentType.SEND } ?: return null
        return sends.filterValues { it.any { status -> status != PaymentStatus.FAILED } }.keys + (unlisted - sends.keys)
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

    /** Records every Spark payment, returning whether all were saved, so a failed write leaves the sync to redo. */
    private suspend fun syncActivities(): Boolean {
        // Read after the records, as a send counts as in flight before it is recorded, so one of the two shows it.
        val recordsBefore = sendRecords.get()
        val inFlightBefore = sendsInFlight.keys.toSet()
        val payments = sparkService.listPayments()
        val isSaved = payments.map { recordActivity(it) }.all { it }
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
        if (failed.isEmpty()) return isSaved
        // A retry can start while the list is recorded, so the release reads Spark's sends again first.
        val recordsBeforeCurrent = sendRecords.get()
        val inFlightBeforeCurrent = sendsInFlight.keys.toSet()
        val current = runSuspendCatching { sparkService.listPayments() }.getOrNull() ?: return isSaved
        val liveCurrent = sendsInFlight.keys + inFlightBeforeCurrent + recordedSince(recordsBeforeCurrent) +
            uncertainSendHashes()
        failed
            .filter { it.lowercase() !in liveCurrent }
            .filter { current.latestSend(it)?.status == PaymentStatus.FAILED }
            .forEach { cacheStore.removeSparkSendHash(it) }
        return isSaved
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
                }
                is SdkEvent.PaymentSucceeded -> onPayment(event.payment)
                is SdkEvent.PaymentFailed -> onPayment(event.payment)
                is SdkEvent.PaymentPending -> recordActivity(event.payment)
                is SdkEvent.UnclaimedDeposits -> Logger.warn(
                    "Found '${event.unclaimedDeposits.size}' unclaimed Spark deposits",
                    context = TAG,
                )
                is SdkEvent.NewDeposits -> refresh()
                is SdkEvent.UnilateralExitStateChanged -> _exitStateGeneration.update { it + 1 }
                is SdkEvent.ClaimedDeposits -> refresh()
                is SdkEvent.AutoOptimization -> onOptimization(event.optimizationEvent)
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
        recordActivity(payment)
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

    /** Returns whether the payment's activity is saved; a failed write is kept in [unsavedPayments]. */
    private suspend fun recordActivity(payment: Payment): Boolean {
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
    val recoverableFundsSats: ULong = 0uL,
    /** An emergency exit is moving the balance on-chain: Spark then neither sends nor receives. */
    val isExiting: Boolean = false,
) {
    /** The most Spark can send once its service fee is paid, so a payment near the full balance isn't offered. */
    val sendableSats: ULong get() = if (isExiting) 0uL else SparkFees.sendableSats(balanceSats)

    /** Spark can take payments and transfers: connected, and no emergency exit runs. */
    val isUsable: Boolean get() = isReady && !isExiting

    /** The balance Spending counts; during an emergency exit it is in transfer to Savings instead. */
    val spendingSats: ULong get() = if (isExiting) 0uL else balanceSats

    /** The balance shown in Spending and on the Spark connection, which [keptBalanceSats] stands in for. */
    val shownBalanceSats: ULong get() = keptBalanceSats ?: balanceSats

    /** The [spendingSats] shown in Spending, which [keptBalanceSats] stands in for. */
    val shownSpendingSats: ULong get() = if (isExiting) 0uL else shownBalanceSats
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
