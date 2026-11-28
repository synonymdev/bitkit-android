package to.bitkit.repositories

import breez_sdk_spark.AutoOptimizationEvent
import breez_sdk_spark.Bolt11InvoiceDetails
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
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
import to.bitkit.data.SettingsStore
import to.bitkit.data.SparkPayoutChain
import to.bitkit.data.SparkSendAttempt
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.SparkExitBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.services.CoreService
import to.bitkit.services.SparkError
import to.bitkit.services.SparkService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    private val coreService: Provider<CoreService>,
    private val settingsStore: SettingsStore,
    private val payoutChain: SparkPayoutChain,
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

        /** How long a sent deposit Spark never claimed is kept across relaunches: a mempool drops it after 2 weeks. */
        private val SENT_DEPOSIT_LIFETIME = 14.days

        private val SEND_STATUS_PRIORITY = listOf(PaymentStatus.COMPLETED, PaymentStatus.PENDING, PaymentStatus.FAILED)

        /** How long the SDK leaves the counter-swap of a leaf swap cut short unclaimed (its 300 s), plus a margin. */
        private val COUNTER_SWAP_GRACE = 330.seconds

        /** The longest an exit quote waits for a running leaf optimization to end. */
        private val OPTIMIZATION_WAIT = 2.minutes

        /** How long a send's settlement waits for the send screen to track it as pending before it reports. */
        private val PENDING_TRACK_WAIT = 10.seconds

        /** Tries to learn the outcome of a send whose result was lost while Spark cannot list its payments. */
        private const val SETTLE_ATTEMPTS = 10

        /** How long a journaled withdrawal Spark doesn't list stays open before a sync closes it as never sent. */
        private val UNLISTED_WITHDRAWAL_GRACE = 10.minutes

        /** How many sent contact withdrawals stay recorded for a request proof to complete from. */
        private const val MAX_SENT_WITHDRAWALS = 20

        private val SETTLE_RETRY_INTERVAL = 30.seconds

        /** Sends again under the same key while Spark can't select the leaves, as after a move to Spark lands. */
        private const val LEAF_SELECTION_RETRIES = 3

        private val LEAF_SELECTION_RETRY_DELAY = 2.seconds

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

    /**
     * When a withdrawal moved the whole balance, in epoch seconds, so a read of 0 is real until a sync after it reads
     * a balance again. A refresh that read its balance before the withdrawal can't clear it.
     */
    @Volatile
    private var withdrewAllAtSecs: ULong? = null

    /** Deposits this app broadcast that Spark hasn't listed yet, by txid, so they show as pending at once. */
    private val broadcastDeposits = ConcurrentHashMap<String, ULong>()

    /** Sends recorded before Spark knew their fee; its completion event reports 0 until a later sync. */
    private val sendsRecordedWithoutFee = ConcurrentHashMap.newKeySet<String>()

    /** Payments whose activity failed to save, written again at the next refresh. */
    private val unsavedPayments = ConcurrentHashMap.newKeySet<String>()

    private val isCollectingEvents = AtomicBoolean(false)
    private val eventsSubscribed = CompletableDeferred<Unit>()

    private val refreshMutex = Mutex()

    /** The invoice of each send still inside Spark's send call, by lowercase payment hash. */
    private val sendsInFlight = ConcurrentHashMap<String, String>()

    /** Sends taken back at start, whose outcome Spark settled while the app was closed, so none is reported again. */
    private val restoredAtStart = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var hasRestoredAtStart = false

    /** Spark payment ids of the sends whose outcome was reported, so the poll and Spark's event report it once. */
    private val reportedSendIds = ConcurrentHashMap.newKeySet<String>()

    /** Counts sends recorded, so a listing drops a failed hash only when it was recorded before the listing. */
    private val sendRecords = AtomicLong()
    private val sendRecordedAt = ConcurrentHashMap<String, Long>()

    /** Sends whose outcome Spark didn't report, by payment hash. */
    private val uncertainSends = ConcurrentHashMap<String, UncertainSend>()

    /** Serialises the imports of staged exit data, so a restore and Spark's start never import it at once. */
    private val exitImportMutex = Mutex()

    /** Held while saved holds are taken back and while a send leaves flight, so neither reads the other halfway. */
    private val holdRestoreMutex = Mutex()

    @Volatile
    private var hasSyncedActivities = false

    /**
     * A withdrawal's send call is running or its returned payment is being recorded, so its journal entry must not be
     * settled before Spark lists it.
     */
    @Volatile
    private var isWithdrawing = false

    /** Counts withdrawal sends that ended, so a list read before one ended never settles its journal. */
    private val withdrawalSendsEnded = AtomicLong(0)

    /** A withdrawal to a contact holds the one journal slot, from before it prepares until its send call returns. */
    private val isClaimingWithdrawal = AtomicBoolean(false)

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

    /**
     * The deposits the transfer flow sent that Spark has not claimed yet, by txid, saved with their claim so a relaunch
     * shows and follows each claim as before.
     */
    private val sentDeposits = ConcurrentHashMap<String, SparkSentDeposit>()

    private val sentDepositsMutex = Mutex()

    @Volatile
    private var hasRestoredSentDeposits = false

    @Volatile
    private var hasResumedSentDeposits = false

    private val _depositClaims = MutableStateFlow<Map<String, SparkDepositClaim>>(emptyMap())

    /** Where the claim of each deposit sent from the transfer flow stands, by txid. */
    val depositClaims: StateFlow<Map<String, SparkDepositClaim>> = _depositClaims.asStateFlow()

    private val _claimChoices = MutableStateFlow<Map<String, SparkClaimChoice>>(emptyMap())

    /** What the user chose over a changed fast transfer fee, by txid, so a reopened status screen keeps its step. */
    val claimChoices: StateFlow<Map<String, SparkClaimChoice>> = _claimChoices.asStateFlow()

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
        // A retry reads as connecting until it fails again.
        _state.update { it.copy(hasStartFailed = false) }
        runSuspendCatching {
            collectEvents()
            // Only the first start of the process takes back holds that settled while the app was closed.
            restoreUncertainSends(atStart = !hasRestoredAtStart)
            hasRestoredAtStart = true
            // Before connecting, so the transfer banner opens a sent deposit's claim while Spark can't list it yet.
            restoreSentDeposits()
            val isExiting = exitStore(walletIndex).isActive()
            _state.update { it.copy(isExiting = isExiting) }
            val connected = sparkService.connect(walletIndex, isExiting)
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
            _state.update { it.copy(hasStartFailed = true) }
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
        // Under the lock, so a save already under way can't write the wiped wallet's deposits back after the wipe.
        sentDepositsMutex.withLock {
            sentDeposits.clear()
            hasRestoredSentDeposits = false
            hasResumedSentDeposits = false
        }
        preparedWithdrawal = null
        withdrawalTxids.clear()
        withdrawalFailedAtSecs = null
        keptBeforeExit = null
        withdrewAllAtSecs = null
        broadcastDeposits.clear()
        sendsRecordedWithoutFee.clear()
        unsavedPayments.clear()
        sendsInFlight.clear()
        uncertainSends.clear()
        restoredAtStart.clear()
        hasRestoredAtStart = false
        hasSyncedActivities = false
        hasMergedRemoteExitState = false
        isLostForExit = false
        _depositClaims.update { emptyMap() }
        _claimChoices.update { emptyMap() }
        _state.update { SparkState(isSupported = sparkService.isSupported) }
        val storage = runSuspendCatching { sparkService.wipeStorage(walletIndex) }
        val exitRecords = runSuspendCatching {
            exitStore(walletIndex).deleteWithCopies()
            SparkExitLostLeafStore(File(Env.sparkExitLostLeavesPath(walletIndex))).delete()
        }
        _walletWiped.emit(walletIndex)
        storage.mapCatching { exitRecords.getOrThrow() }
    }

    /** Set when a reconnect for an exit disconnected Spark and then failed, so [reconnectForExit] connects it again. */
    @Volatile
    private var isLostForExit = false

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
            isLostForExit = false
            refresh()
        }.onFailure {
            Logger.error("Failed to reconnect Spark with exit '$isExiting'", it, context = TAG)
            // Without an SDK Spark neither sends nor receives, so it reads as failed until it connects again.
            if (!sparkService.isConnected) {
                isLostForExit = true
                _state.update { it.copy(isReady = false, hasStartFailed = true) }
            }
        }
    }

    /**
     * Connects Spark again when a reconnect for an exit disconnected it and then failed: Spark has no SDK, so neither
     * the exit's builds nor its leaf optimization would run, and it reads as not ready, until a background run or a
     * restart.
     */
    suspend fun reconnectForExit() = withContext(bgDispatcher) {
        if (!isLostForExit || sparkService.isConnected) return@withContext
        runSuspendCatching {
            sparkService.connect(walletIndex, _state.value.isExiting)
            isLostForExit = false
            refresh()
        }.onFailure { Logger.warn("Failed to connect Spark again for the exit", it, context = TAG) }
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
     * Waits for the sends that passed their exit check before [holdForExit] to finish, so an exit starting quotes the
     * leaves they leave. Fails with [SparkExitError] when one is still sending after [OPTIMIZATION_WAIT]: the exit must
     * not start while a send can still spend its leaves.
     */
    suspend fun awaitSpendsIdle() {
        if (withTimeoutOrNull(OPTIMIZATION_WAIT) { spendsInFlight.first { it == 0 } } == null) {
            Logger.warn("Gave up waiting for Spark sends in flight to end", context = TAG)
            throw SparkExitError("A Spark payment is still sending")
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

    fun canSend(amountSats: ULong): Boolean = _state.value.canSend(amountSats)

    /** A withdrawal's debit already holds its fees, so it is weighed against the whole balance. */
    fun canWithdraw(debitSats: ULong): Boolean = _state.value.isReady && _state.value.balanceSats >= debitSats

    /**
     * Creates a Spark invoice and remembers it as this wallet's own, which [isOwnInvoice] then recognises. An invoice
     * that cannot be read or remembered is not handed out.
     */
    suspend fun createInvoice(amountSats: ULong?, description: String, expirySecs: UInt): Result<String> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                sparkService.receiveBolt11(amountSats, description, expirySecs).also { rememberOwnInvoice(it) }
            }
        }

    private suspend fun rememberOwnInvoice(bolt11: String) {
        val invoice = sparkService.invoiceDetails(bolt11) ?: throw SparkError.InvoiceUnreadable()
        val expiresAt = SparkService.invoiceExpiresAt(invoice.timestamp, invoice.expiry)
        cacheStore.addSparkOwnInvoice(invoice.paymentHash, expiresAt.toEpochSeconds(), clock.now().epochSeconds)
    }

    /**
     * Whether [bolt11], with [paymentHash] in hex, pays this Spark wallet: an unexpired invoice this wallet remembers
     * creating, or one that carries this wallet's Spark address. Remembered invoices that cannot be read count as
     * own, so the invoice is refused instead of paid unchecked. The second check needs Spark connected and is skipped
     * without it, as on a build or network without Spark. An invoice a paired watch created is known only once its
     * hash was added to the cache.
     */
    suspend fun isOwnInvoice(bolt11: String, paymentHash: String): Boolean = withContext(bgDispatcher) {
        val isRemembered = runSuspendCatching { cacheStore.hasSparkOwnInvoice(paymentHash, clock.now().epochSeconds) }
            .onFailure { Logger.warn("Failed to read the wallet's own Spark invoices", it, context = TAG) }
            .getOrDefault(true)
        isRemembered || runSuspendCatching { sparkService.carriesOwnSparkAddress(bolt11) }
            .onFailure { Logger.debug("Skipped the Spark address check of an invoice: '${it.message}'", context = TAG) }
            .getOrDefault(false)
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
     * payment, could not list its payments while an older build's send of the invoice is kept, or its fee was over
     * [maxTotalFeeMsat]; with [SparkError.InvoiceExpired], a
     * [SparkError.SendRefused], when Spark refuses an expired invoice at prepare or send; and with
     * [ServiceError.PaymentDeadlineExpired] past [paymentDeadlineAt]. [onBeforeSend] runs once after the checks and
     * again right before the send; refused there, it fails with [ServiceError.PaymentStoppedAtSubmission]. A failure
     * inside the send is a [PaymentPendingException], as the payment may have been dispatched, and its event decides
     * it. An invoice this wallet already sends is not sent again: one in flight or
     * pending fails with [PaymentPendingException], and one Spark completed returns its hash. After a restore, a
     * still-valid invoice created before it that the Spark operators can't show unsent fails with
     * [SparkError.RestoredInvoiceUnverified], under the same rule as the node's send.
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
            val invoice = method.invoiceDetails
            val expiresAt = invoice.timestamp.safe() + invoice.expiry.safe()
            // A caller asking for the invoice again waits for its outcome, also for a send settled while closed. The
            // restore lock orders it against a settle taking the hold, which reads the marker under the same lock.
            holdRestoreMutex.withLock { restoredAtStart.remove(paymentHash.lowercase()) }
            val earlier = lightningSend(paymentHash)
            // Read after the listing, so a first sync that lands during it counts, as the listing saw it synced.
            val canList = canListPayments()
            when (earlier) {
                is SparkLightningSend.Found if earlier.status == PaymentStatus.COMPLETED -> {
                    Logger.info("Skipped Spark send of '$paymentHash', already paid", context = TAG)
                    return@runSuspendCatching paymentHash
                }
                // A send held in doubt under this build's key is sent again under that key, which can't pay twice:
                // the operators return the transfer it committed, if any.
                is SparkLightningSend.Found if earlier.status == PaymentStatus.PENDING ->
                    if (!isKeyedHold(paymentHash)) throw PaymentPendingException(paymentHash)
                // Spark's list, synced or unreadable, can lack a send it made before the restore, as on the node's
                // path, so the operators decide. Before Spark syncs, its list can lack an earlier attempt they show
                // failed, so that one is refused too.
                else -> refuseUnverifiedRestoredInvoice(
                    bolt11,
                    invoice.timestamp.toEpochSeconds(),
                    expiresAt.toEpochSeconds(),
                    isFailedUnsent = canList,
                )
            }
            // Spark's local list picks the attempt's key and, before Spark's first sync, shows the invoice's earlier
            // sends, as the synced list did above.
            val local = runSuspendCatching { listPayments() }.getOrNull()
            // Past the checks above, a pending send is a held one this build's key retries, whose outcome Spark's local
            // list may already show.
            val isKeyedRetry = earlier is SparkLightningSend.Found && earlier.status == PaymentStatus.PENDING
            val readsLocally = earlier == SparkLightningSend.Unavailable || isKeyedRetry
            if (readsLocally && isPaidLocally(paymentHash, local, isKeyedRetry)) {
                Logger.info("Skipped Spark send of '$paymentHash', already paid", context = TAG)
                return@runSuspendCatching paymentHash
            }
            // A cancel, block or identity change can land while Spark prepares, so the caller checks again here, and
            // once more right before the send.
            if (!onBeforeSend()) throw PaymentAbortedBeforeSend()
            val isEntered = holdRestoreMutex.withLock {
                sendsInFlight.putIfAbsent(paymentHash.lowercase(), bolt11) == null
            }
            if (!isEntered) throw PaymentPendingException(paymentHash)
            // Kept in flight until its result is handled, so a sync meanwhile never takes back its saved hold.
            try {
                // Spark keeps the key as this attempt's payment id, so only its own outcome settles the send. A retry
                // keeps the latest attempt's key, so Spark returns that attempt's payment instead of paying again, and
                // moves past each attempt Spark's list shows failed, as a failed key can't send again.
                // The key's secret comes with Spark's connection, so a send that lost it is refused before it goes.
                val (attempt, attemptId) = runSuspendCatching {
                    nextSendAttempt(paymentHash, local).let { it to sparkService.sendKey(paymentHash, it) }
                }.getOrElse { throw SparkError.SendRefused("Spark is not connected", it) }
                val previousAttempt = cacheStore.data.first().sparkSendAttempts[paymentHash.lowercase()]
                val isReusedKey = previousAttempt?.attempt == attempt
                val wasHeld = sendHold(paymentHash.lowercase()) != null
                // A held send whose attempt Spark lists failed is settled by the next listing, and a send after that
                // goes through the restore checks above, as a new key's send must.
                if (isKeyedRetry && !isReusedKey) throw PaymentPendingException(paymentHash)
                // Spark returns the earlier attempt's payment for its key, so a retry at another amount is refused.
                val sendSats = sendAmountSats(amountSats, invoice)
                if (isReusedKey) ensureKeptAmount(previousAttempt, sendSats)

                // A send refused before anything went out is taken as not having used its key, so a retry refuses the
                // same way. The hold of an earlier attempt under the same key stays, as it may have committed a
                // transfer. Under a new key every earlier attempt failed, so a hold one of them left ends too.
                // A failed write keeps the whole send held, and the refusal still reaches the caller.
                suspend fun dropUnsentSend() {
                    if (isReusedKey && wasHeld) return
                    bestEffort("drop unsent Spark send '$paymentHash'") {
                        cacheStore.dropSparkUnsentSend(paymentHash, !isReusedKey, previousAttempt)
                        uncertainSends.remove(paymentHash.lowercase())
                    }
                }
                val sent = run {
                    sendRecordedAt[paymentHash.lowercase()] = sendRecords.incrementAndGet()
                    cacheStore.addSparkSendHash(paymentHash, expiresAt.toEpochSeconds())
                    cacheStore.setSparkSendAttempt(
                        paymentHash,
                        attempt,
                        sendSats,
                        expiresAt.toEpochSeconds(),
                        clock.now().epochSeconds,
                    )
                    spending {
                        // Kept until the call returns, so a send cut off by process death or cancellation stays held.
                        cacheStore.addSparkUncertainSend(paymentHash, clock.now().epochSeconds, attemptId)
                        // The checks above and both writes suspend, so the caller's authorization, the deadline and
                        // an exit that started meanwhile are checked last, before the send.
                        runSuspendCatching {
                            if (!onBeforeSend()) throw ServiceError.PaymentStoppedAtSubmission()
                            ensurePaymentDeadline(paymentDeadlineAt)
                            ensureNotExiting()
                        }.onFailure {
                            dropUnsentSend()
                            throw it
                        }
                        sendRetryingLeafSelection(prepared, attemptId, paymentDeadlineAt, onBeforeSend)
                    }
                }
                val payment = sent.getOrElse {
                    // The SDK checks the expiry again before anything goes out, so an expired invoice left nothing.
                    // Under a key an earlier attempt used, that attempt may have committed a transfer before the
                    // expiry, so the send stays held.
                    if (it.isInvoiceExpired() && !isReusedKey) {
                        dropUnsentSend()
                        throw SparkError.InvoiceExpired(it)
                    }
                    // Spark selects the leaves to spend before anything goes to the operators, so a short balance left
                    // nothing, for instance when an earlier send spent it after the balance was read. Under a key an
                    // earlier attempt used, it can hide the transfer that attempt committed, so that send stays held.
                    if (it is SdkException.InsufficientFunds && !isReusedKey) {
                        dropUnsentSend()
                        throw SparkError.SendRefused("Spark balance can't cover the payment", it)
                    }
                    // Leaves Spark still can't select after its retries can follow a transfer it committed, as the SDK
                    // selects them again after an operator error, so that send stays held until Spark settles it or its
                    // invoice expires, as any other unknown outcome does.
                    Logger.warn("Failed to learn the outcome of Spark send '$paymentHash'", it, context = TAG)
                    settleUncertainSend(paymentHash, expiresAt, attemptId)
                    throw PaymentPendingException(paymentHash)
                }
                // A send Spark took stays held until Spark settles it or its invoice expires, as Spark's list may not
                // show it yet.
                if (payment.status == PaymentStatus.PENDING) {
                    settleUncertainSend(paymentHash, expiresAt, attemptId)
                } else {
                    settleSentResult(paymentHash, payment, wasHeld)
                }
                Logger.info("Sent Spark payment '${payment.id}' with status '${payment.status}'", context = TAG)
                paymentHash
            } finally {
                withContext(NonCancellable) {
                    holdRestoreMutex.withLock { sendsInFlight.remove(paymentHash.lowercase()) }
                }
            }
        }.onFailure {
            if (it is PaymentAbortedBeforeSend || it is ServiceError.PaymentStoppedAtSubmission) {
                Logger.debug("Aborted Spark invoice pay before send", context = TAG)
            } else {
                Logger.error("Failed to pay invoice with Spark", it, context = TAG)
            }
        }
    }

    /**
     * Whether Spark's [local] list shows the send of [paymentHash] paid; throws while it shows the send pending, or,
     * unless it is a [isKeyedRetry], while the list can't be read and an older build sent the invoice.
     */
    private suspend fun isPaidLocally(paymentHash: String, local: List<Payment>?, isKeyedRetry: Boolean): Boolean {
        when (local?.latestSend(paymentHash)?.status) {
            PaymentStatus.COMPLETED -> return true
            PaymentStatus.PENDING -> throw PaymentPendingException(paymentHash)
            else -> Unit
        }
        // A retry reuses its attempt's key, so Spark never pays an invoice twice under it, but an older build sent
        // under a random key, which a list that can't be read may hide.
        if (!isKeyedRetry && local == null && hasUnkeyedSend(paymentHash)) {
            throw SparkError.SendRefused("Spark could not list its payments to check the invoice")
        }
        return false
    }

    /**
     * Ends the hold of a send whose call returned its settled [payment]: a retry of a send held in doubt settles that
     * hold with its own result, which callers waiting on the held send learn from its event, as Spark may not report a
     * transfer it returned for the key again. A failed one is refused.
     */
    private suspend fun settleSentResult(paymentHash: String, payment: Payment, wasHeld: Boolean) {
        uncertainSends.remove(paymentHash.lowercase())
        bestEffort("end the hold of Spark send '$paymentHash'") {
            cacheStore.removeSparkUncertainSend(paymentHash)
        }
        if (wasHeld && reportedSendIds.add(payment.id)) {
            recordActivity(payment, isOwnAttempt = true)
            payment.toNodeEvent()?.let { _nodeEvents.emit(it) }
        }
        // Spark's failure event during its send call was left to this result, so the result reports it.
        if (payment.status == PaymentStatus.FAILED) {
            // This attempt paid nothing, as when its outcome settles, so a retry isn't held as pending.
            bestEffort("drop the hash of Spark send '$paymentHash'") {
                cacheStore.removeSparkSendHash(paymentHash)
            }
            if (!wasHeld) recordActivity(payment, isOwnAttempt = true)
            // The caller subscribes to events only once this returns, so the result reports the failure, once:
            // marked reported, Spark's event for it is not reported again, as a second error would be.
            reportedSendIds.add(payment.id)
            throw SparkError.SendRefused("Spark send failed")
        }
    }

    private suspend fun sendRetryingLeafSelection(
        prepared: PrepareSendPaymentResponse,
        attemptId: String,
        paymentDeadlineAt: Instant?,
        onBeforeSend: suspend () -> Boolean,
    ): Result<Payment> {
        var result = runSuspendCatching { sparkService.send(prepared, attemptId) }
        val canRetry = {
            runCatching {
                ensurePaymentDeadline(paymentDeadlineAt)
                ensureNotExiting()
            }.isSuccess
        }
        repeat(LEAF_SELECTION_RETRIES) {
            if (result.exceptionOrNull()?.isLeafSelectionFailure() != true || !canRetry()) return result
            Logger.info("Retrying Spark send '$attemptId' as Spark could not select its leaves", context = TAG)
            delay(LEAF_SELECTION_RETRY_DELAY)
            // A cancel, block or identity change can land during the delay, so each resend is checked again. The
            // authorization check can suspend, so the deadline and an exit hold are read after it.
            if (!onBeforeSend() || !canRetry()) return result
            val retried = runSuspendCatching { sparkService.send(prepared, attemptId) }
            // A failed try can have committed a transfer before Spark selected leaves again, so a short balance or an
            // expiry on a retry, which would read as nothing sent, ends with the earlier leaf failure instead.
            val error = retried.exceptionOrNull()
            if (error is SdkException.InsufficientFunds || error?.isInvoiceExpired() == true) return result
            result = retried
        }
        return result
    }

    /** Logs a failed cache write on a send's settlement path, so a storage error never changes the send's outcome. */
    private suspend fun bestEffort(what: String, write: suspend () -> Unit) {
        runSuspendCatching { write() }.onFailure { Logger.warn("Failed to $what", it, context = TAG) }
    }

    private suspend fun nextSendAttempt(paymentHash: String, listing: List<Payment>?): Int {
        var attempt = cacheStore.data.first().sparkSendAttempts[paymentHash.lowercase()]?.attempt ?: 0
        val failed = listing?.filter { it.status == PaymentStatus.FAILED }?.mapTo(HashSet()) { it.id }
            ?: return attempt
        while (sparkService.sendKey(paymentHash, attempt) in failed) attempt++
        return attempt
    }

    private suspend fun hasUnkeyedSend(paymentHash: String): Boolean {
        val hash = paymentHash.lowercase()
        val cache = cacheStore.data.first()
        if (hash in cache.sparkSendAttempts) return false
        return hash in cache.sparkSendHashes || hash in cache.sparkUncertainSends
    }

    private suspend fun settleUncertainSend(paymentHash: String, expiresAt: ULong, attemptId: String) {
        val failedAt = clock.now().epochSeconds
        val startedAt = runSuspendCatching { cacheStore.data.first().sparkUncertainSends[paymentHash.lowercase()] }
            .onFailure { Logger.warn("Failed to read the hold of Spark send '$paymentHash'", it, context = TAG) }
            .getOrNull() ?: failedAt
        val hold = UncertainSend(attemptId, failedAt.toULong(), expiresAt)
        uncertainSends[paymentHash.lowercase()] = hold
        // Its caller now waits on the hold, so its outcome is reported, also when an earlier send under the key was.
        reportedSendIds.remove(attemptId)
        // Held in memory meanwhile, and the hash kept before the send still holds the invoice after a restart.
        bestEffort("keep the hold of Spark send '$paymentHash'") {
            cacheStore.addSparkUncertainSend(paymentHash, startedAt, attemptId)
        }
        scope.launch {
            // The send screen tracks the hash as pending only once the send returns, and resolves tracked ones.
            withTimeoutOrNull(PENDING_TRACK_WAIT) {
                pendingPaymentRepo.state.first { paymentHash in it.pendingPayments }
            }
            repeat(SETTLE_ATTEMPTS) {
                // A send its own payment event settled meanwhile needs no sync, and a later hold of it polls on
                // its own.
                if (uncertainSends[paymentHash.lowercase()] !== hold) return@launch
                runSuspendCatching { sparkService.syncWallet() }
                    .onFailure { Logger.warn("Failed to sync Spark after send '$paymentHash'", it, context = TAG) }
                if (trySettleUncertainSend(paymentHash, held = hold)) return@launch
                delay(SETTLE_RETRY_INTERVAL)
            }
            // Spark's next sync settles it, as a send it resumes then ends with its own payment event.
            Logger.warn("Stopped polling Spark send '$paymentHash' until Spark syncs again", context = TAG)
        }
    }

    /**
     * Settles an uncertain send once Spark shows its outcome; true once it is settled, also by another caller. A
     * failure event for it [isFailureEvent] shows Spark reached the send, as a sync since would. A [listing] just
     * read from Spark is used instead of reading it again, and then only for the hold [held] taken before it was read.
     */
    private suspend fun trySettleUncertainSend(
        paymentHash: String,
        isFailureEvent: Boolean = false,
        listing: List<Payment>? = null,
        held: UncertainSend? = null,
    ): Boolean {
        val key = paymentHash.lowercase()
        val uncertain = uncertainSends[key] ?: return true
        if (held != null && uncertain !== held) return false
        // Spark finishes an interrupted send only on a later sync, and a sync that never reached it still returns.
        val isSynced = hasSyncedSince(uncertain.failedAt)
        if (isSynced) _state.update { it.copy(hasSynced = true) }
        // Spark keeps a send it committed but didn't finish in a cache no call exposes, and a sync that reached Spark
        // can still fail to resume it, so only an expired invoice shows a missing send will never go out.
        val isFinal = (isSynced || isFailureEvent) && clock.now().epochSeconds.toULong() >= uncertain.expiresAt
        val send = listedLightningSend(paymentHash, listing)
        val isSettled = when (send) {
            SparkLightningSend.NotFound -> isFinal
            is SparkLightningSend.Found if send.status == PaymentStatus.COMPLETED -> true
            // A pending send ends with its own payment event, or at its invoice's expiry.
            is SparkLightningSend.Found if send.status == PaymentStatus.PENDING -> isFinal
            // Another attempt's failure is final only when a missing send would be.
            is SparkLightningSend.Found -> isFinal || hasOwnFailure(paymentHash, uncertain.attemptId, listing)
            SparkLightningSend.Unavailable -> false
        }
        // Only the caller that takes the hash off settles it, so its outcome is reported once.
        if (!isSettled) return false
        // A send Spark settled while the app was closed updates its row only, as nobody waits for it now. Its marker
        // stays until its payment is claimed, so Spark's event for it meanwhile claims it too.
        // A hold left to a retry in flight under its key stays polled, as the retry may leave it held.
        val isReported = takeUncertainSend(paymentHash, uncertain, send) ?: return uncertainSends[key] !== uncertain
        when (send) {
            // A send Spark never recorded settles only at its expiry, so it is reported, as a caller may wait for it.
            SparkLightningSend.NotFound -> {
                // A retry that entered Spark's send call since the hold was taken reports its own outcome.
                if (!sendsInFlight.containsKey(key)) {
                    _nodeEvents.emit(
                        Event.PaymentFailed(paymentHash, paymentHash, PaymentFailureReason.UNEXPECTED_ERROR)
                    )
                }
            }
            // Spark still runs a pending send past its invoice's expiry, so its own payment event reports it.
            is SparkLightningSend.Found if send.status == PaymentStatus.PENDING -> Unit
            is SparkLightningSend.Found -> reportListedSend(paymentHash, uncertain, send, listing, isReported)
            SparkLightningSend.Unavailable -> Unit
        }
        restoredAtStart.remove(key)
        return true
    }

    private suspend fun reportListedSend(
        paymentHash: String,
        uncertain: UncertainSend,
        send: SparkLightningSend.Found,
        listing: List<Payment>?,
        isReported: Boolean,
    ) {
        val payments = listing ?: runSuspendCatching { listPayments() }.getOrNull()
        val deciding = payments?.latestSend(paymentHash)
        // The send's own failure decides its row and outcome, also when another attempt failed later.
        val own = payments?.firstOrNull { it.id == uncertain.attemptId }
        val listed = own?.takeIf { deciding?.status == PaymentStatus.FAILED } ?: deciding
        // Claimed before the write, so Spark's event for the same payment during it reports nothing either.
        if (!isReported) listed?.let { reportedSendIds.add(it.id) }
        if (send.status == PaymentStatus.FAILED) {
            // Its row stayed pending while held, so it takes the failure now.
            // A retry that started since the hold was taken keeps its own row.
            listed?.let { recordActivity(it, isOwnAttempt = it === own && !isHeldFrom(it)) }
        }
        when {
            // Spark's event for the same payment, which can follow a reconnect, reports nothing either.
            !isReported -> Unit
            listed == null -> settledSendEvent(paymentHash)?.let { _nodeEvents.emit(it) }
            // A retry started after the hold was taken reads as pending, and its own event reports it.
            listed.status == PaymentStatus.PENDING -> Unit
            // A retry that entered Spark's send call since the hold was taken reports its own outcome.
            listed.status == PaymentStatus.FAILED && isHeldFrom(listed) -> Unit
            // The send's own outcome is claimed, so Spark's event for the same payment reports nothing more.
            listed === own -> if (reportedSendIds.add(own.id)) own.toNodeEvent()?.let { _nodeEvents.emit(it) }
            // A paid attempt is claimed, so Spark's event for the same payment reports nothing more.
            listed.status == PaymentStatus.COMPLETED ->
                if (reportedSendIds.add(listed.id)) listed.toNodeEvent()?.let { _nodeEvents.emit(it) }
            // Another attempt's failure ends the send at its expiry, so it claims no payment.
            else -> listed.toNodeEvent()?.let { _nodeEvents.emit(it) }
        }
    }

    /**
     * Takes the hold [uncertain] off [paymentHash]; null when another caller took it, else whether its outcome is
     * reported, which it is unless it was taken back at start and no caller has asked for the invoice since.
     */
    private suspend fun takeUncertainSend(
        paymentHash: String,
        uncertain: UncertainSend,
        send: SparkLightningSend,
    ): Boolean? {
        val key = paymentHash.lowercase()
        val isFailed = send == SparkLightningSend.NotFound ||
            send is SparkLightningSend.Found && send.status == PaymentStatus.FAILED
        // Both copies go under the restore lock, so a sync never takes back a hold settled here. A send enters Spark's
        // send call under the same lock, so a retry's saved send is never dropped here. A caller asking for the
        // invoice again clears its marker under the same lock, so it either sees the hold and gets its report, or
        // finds it settled.
        return holdRestoreMutex.withLock {
            // A retry in flight under the send's key settles the hold with its own outcome.
            if (sendsInFlight.containsKey(key)) return@withLock null
            if (!uncertainSends.remove(key, uncertain)) return@withLock null
            bestEffort("end the hold of Spark send '$paymentHash'") { cacheStore.removeSparkUncertainSend(paymentHash) }
            if (isFailed) {
                bestEffort("drop the hash of Spark send '$paymentHash'") { cacheStore.removeSparkSendHash(paymentHash) }
            }
            key !in restoredAtStart
        }
    }

    /**
     * A send whose outcome Spark didn't report: the payment id Spark knows its attempt by, null for a hold kept by an
     * older build, and when it failed and when its invoice expires, in epoch seconds.
     */
    private data class UncertainSend(val attemptId: String?, val failedAt: ULong, val expiresAt: ULong)

    /**
     * Takes back the sends whose outcome Spark didn't report before a restart, so a sync can still settle them. Those
     * taken back [atStart] settled, if at all, while the app was closed.
     */
    private suspend fun restoreUncertainSends(atStart: Boolean = false) = holdRestoreMutex.withLock {
        val cache = cacheStore.data.first()
        cache.sparkUncertainSends.forEach { (hash, startedAt) ->
            // A send still in Spark's send call is settled by its result.
            if (sendsInFlight.containsKey(hash)) return@forEach
            val expiresAt = cache.sparkSendExpiries[hash]?.toULong() ?: ULong.MAX_VALUE
            val restored = UncertainSend(cache.sparkSendAttemptIds[hash], startedAt.toULong(), expiresAt)
            if (uncertainSends.putIfAbsent(hash, restored) == null && atStart) restoredAtStart.add(hash)
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

    private fun sendAmountSats(amountSats: ULong?, invoice: Bolt11InvoiceDetails): ULong =
        amountSats ?: invoice.amountMsat?.div(MSATS_PER_SAT) ?: 0uL

    private fun ensureKeptAmount(previous: SparkSendAttempt?, sendSats: ULong) {
        val keptSats = previous?.amountSats ?: return
        if (keptSats != sendSats) throw SparkError.SendRefused("An earlier attempt at '$keptSats' sats may still pay")
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
        sentDeposits.computeIfPresent(txid) { _, sent -> sent.copy(ceilingSats = ceilingSats) }
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
        _claimChoices.update { it + (txid to SparkClaimChoice.Approved) }
        followDepositClaim(txid, SparkClaimSpeed.Instant, repriced.offer.earlyFeeSats)
    }

    /**
     * Leaves the deposit in [txid] to the claim at 3 confirmations, clearing the fee cap an early claim attempt left on
     * it, so Spark doesn't still claim it early.
     */
    fun waitForMatureClaim(txid: String) {
        if (_depositClaims.value[txid] is SparkDepositClaim.Repriced) {
            _claimChoices.update { it + (txid to SparkClaimChoice.Waited) }
        }
        claimJobs.remove(txid)?.cancel()
        setDepositClaim(txid, SparkDepositClaim.Later)
        releaseEarlyClaimCap(txid)
    }

    private fun releaseEarlyClaimCap(txid: String) {
        // Kept until cleared, so a relaunch while it is tried again clears it then.
        val vout = earlyClaimVouts[txid] ?: return
        // Tried again while another claim of the deposit, such as Spark's own sync, leaves the cap in place.
        scope.launch {
            repeat(CLAIM_POLL_ATTEMPTS) { attempt ->
                runSuspendCatching { sparkService.releaseDepositClaimFee(txid, vout) }
                    .onSuccess {
                        if (earlyClaimVouts.remove(txid, vout)) saveSentDeposits()
                        return@launch
                    }
                    .onFailure {
                        Logger.warn("Failed to clear the early claim cap of Spark deposit '$txid'", it, context = TAG)
                    }
                if (attempt < CLAIM_POLL_ATTEMPTS - 1) delay(CLAIM_POLL_INTERVAL)
            }
        }
    }

    private suspend fun claimInstantly(txid: String, ceilingSats: ULong) {
        var isSeen = false
        // Once Spark waits for the early claim, the user's ceiling stays on the deposit as its cap and Spark claims it
        // by itself when the provider offers it within the cap, so the poll only watches for the claim and asks the
        // provider nothing more; an offer above the cap is never charged and the deposit is claimed at maturity.
        var isWaiting = false
        repeat(CLAIM_POLL_ATTEMPTS) {
            runSuspendCatching { refresh() }
                .onFailure { Logger.warn("Failed to refresh Spark state", it, context = TAG) }
            if (isClaimed(txid)) {
                setDepositClaim(txid, SparkDepositClaim.Claimed)
                return
            }
            if (isWaiting && _depositClaims.value[txid] != SparkDepositClaim.Adding) {
                if (isClaimStarted(txid)) setDepositClaim(txid, SparkDepositClaim.Adding)
            } else if (_depositClaims.value[txid] != SparkDepositClaim.Adding) {
                val offer = depositClaimOffer(txid)
                    .onFailure { Logger.warn("Failed to quote Spark deposit '$txid'", it, context = TAG) }
                    .getOrNull()
                if (offer != null) isSeen = true
                val claim = when (val step = SparkClaimStep.of(offer, ceilingSats)) {
                    SparkClaimStep.KeepLooking -> null
                    SparkClaimStep.Adding -> SparkDepositClaim.Adding
                    SparkClaimStep.Unavailable -> SparkDepositClaim.Unavailable
                    is SparkClaimStep.Ask -> SparkDepositClaim.Repriced(step.offer)
                    is SparkClaimStep.ClaimEarly -> claimEarly(step.offer, ceilingSats).getOrNull()
                        ?.also { isWaiting = it == SparkEarlyClaim.Waiting }
                        ?.toDepositClaim(step.offer)
                }
                if (claim != null) setDepositClaim(txid, claim)
                if (claim?.isFinal == true) return
            }
            delay(CLAIM_POLL_INTERVAL)
        }
        if (_depositClaims.value[txid] == SparkDepositClaim.Adding) return
        Logger.warn("Stopped looking for the Instant claim of Spark deposit '$txid'", context = TAG)
        // Shown at the normal speed from here, so Spark no longer claims it early within the user's ceiling.
        releaseEarlyClaimCap(txid)
        setDepositClaim(txid, if (isSeen) SparkDepositClaim.Unavailable else SparkDepositClaim.NotSeen)
    }

    /**
     * Follows the deposit in [txid] as claimed at 3 confirmations when no claim is followed for it, as after a
     * relaunch, so it turns to [SparkDepositClaim.Claimed] once Spark adds it.
     */
    fun followShownDeposit(txid: String) {
        _depositClaims.update { if (txid in it) it else it + (txid to SparkDepositClaim.Later) }
    }

    /** Whether Spark no longer lists the deposit in [txid] as unclaimed, so its claim is under way. */
    private suspend fun isClaimStarted(txid: String): Boolean = runSuspendCatching {
        sparkService.listUnclaimedDeposits().none { it.txid.equals(txid, ignoreCase = true) }
    }.onFailure { Logger.warn("Failed to list unclaimed Spark deposits", it, context = TAG) }.getOrDefault(false)

    private fun isClaimed(txid: String): Boolean =
        _state.value.deposits.any { it.isClaimed && it.txid.equals(txid, ignoreCase = true) }

    private fun setDepositClaim(txid: String, claim: SparkDepositClaim) {
        Logger.debug("Set claim of Spark deposit '$txid' to '$claim'", context = TAG)
        _depositClaims.update { it + (txid to claim) }
        if (claim == SparkDepositClaim.Claimed) sentDeposits.remove(txid)
        saveSentDeposits()
    }

    private fun saveSentDeposits() {
        scope.launch { writeSentDeposits() }
    }

    // Each write saves the claims as they stand then, so the latest state is written last.
    private suspend fun writeSentDeposits() = sentDepositsMutex.withLock {
        val claims = _depositClaims.value
        val choices = _claimChoices.value
        val saved = sentDeposits.mapValues { (txid, sent) ->
            sent.withClaim(claims[txid], choices[txid], earlyClaimVouts[txid])
        }
        runSuspendCatching { cacheStore.update { it.copy(sparkSentDeposits = saved) } }
            .onFailure { Logger.warn("Failed to save the sent Spark deposits", it, context = TAG) }
    }

    private suspend fun restoreSentDeposits() = sentDepositsMutex.withLock {
        if (hasRestoredSentDeposits) return@withLock
        val keptSince = clock.now().epochSeconds - SENT_DEPOSIT_LIFETIME.inWholeSeconds
        val saved = runSuspendCatching { cacheStore.data.first().sparkSentDeposits }
            .onFailure { Logger.warn("Failed to read the sent Spark deposits", it, context = TAG) }
            .getOrElse { return@withLock }
        hasRestoredSentDeposits = true
        saved.filterValues { it.sentAt >= keptSince }.forEach { (txid, sent) ->
            // A deposit sent in this process already follows its own claim.
            if (sentDeposits.putIfAbsent(txid, sent) != null) return@forEach
            broadcastDeposits.putIfAbsent(txid, sent.amountSats)
            sent.earlyClaimVout?.let { earlyClaimVouts.putIfAbsent(txid, it) }
            sent.choice?.let { choice -> _claimChoices.update { it + (txid to choice) } }
            // A deposit whose claim the transfer flow never saved shows as pending but isn't followed again.
            sent.toDepositClaim()?.let { claim -> _depositClaims.update { it + (txid to claim) } }
        }
        _state.update { it.withBroadcastDeposits() }
        if (saved.size != sentDeposits.size) saveSentDeposits()
    }

    // Instant claims a relaunch cut short start again at the approved ceiling; caps of those left to 3 confirmations
    // are cleared again.
    private fun resumeSentDeposits() {
        if (hasResumedSentDeposits || !hasRestoredSentDeposits) return
        hasResumedSentDeposits = true
        sentDeposits.forEach { (txid, sent) ->
            when (_depositClaims.value[txid]) {
                SparkDepositClaim.Looking if !claimJobs.containsKey(txid) ->
                    followDepositClaim(txid, SparkClaimSpeed.Instant, sent.ceilingSats)
                SparkDepositClaim.Later, SparkDepositClaim.Unavailable, SparkDepositClaim.NotSeen ->
                    releaseEarlyClaimCap(txid)
                else -> Unit
            }
        }
    }

    /** How the deposit in [txid] can be claimed, or null while Spark has not seen it yet. */
    suspend fun depositClaimOffer(txid: String): Result<SparkClaimOffer?> = withContext(bgDispatcher) {
        runSuspendCatching {
            val deposit = sparkService.listUnclaimedDeposits().firstOrNull { it.txid.equals(txid, ignoreCase = true) }
                ?: return@runSuspendCatching null
            SparkClaimOffer.from(deposit.txid, deposit.vout, sparkService.fetchClaimQuote(deposit.txid, deposit.vout))
        }
    }

    /**
     * Accepts the early claim of [offer] for at most [maxFeeSats], the fee the user accepted. Spark keeps it as the cap
     * when it waits, so a later offer within it is claimed too.
     */
    suspend fun claimEarly(
        offer: SparkClaimOffer.Choice,
        maxFeeSats: ULong = offer.earlyFeeSats,
    ): Result<SparkEarlyClaim> = withContext(bgDispatcher) {
        earlyClaimVouts[offer.txid] = offer.vout
        // Saved before the claim, so Wait after a relaunch mid-claim still clears the cap it sets.
        if (sentDeposits.containsKey(offer.txid)) writeSentDeposits()
        runSuspendCatching { sparkService.claimDeposit(offer.txid, offer.vout, maxFeeSats) }
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
            withdrewAllAtSecs = clock.now().epochSeconds.toULong()
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
     * Spark's fee on top, or null while Spark cannot send it. Fails with [SparkError.WithdrawalOverBalance] when the
     * balance covers the amount but not the fee on top, and with [SparkError.WithdrawalPending] while an earlier
     * contact withdrawal's journal is open, as [withdraw] would refuse it. bitkit-core weighs it against the Pubky
     * Swaps providers.
     */
    suspend fun withdrawalRouteLeg(endpoint: PaykitEndpoint, amountSats: ULong): Result<ServiceRouteLeg?> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                cacheStore.data.first().pendingSparkWithdrawal?.let {
                    throw SparkError.WithdrawalPending(address = it.address, contact = it.contact)
                }
                if (!canWithdraw(amountSats)) return@runSuspendCatching null
                val method = sparkService.prepareSend(endpoint.value, amountSats).paymentMethod
                val quote = (method as SendPaymentMethod.BitcoinAddress).feeQuote
                // Spark charges its fee and the L1 broadcast fee on top of what reaches the contact.
                val speed = quote.speedMedium
                val debitSats = amountSats.safe() + (speed.userFeeSat.safe() + speed.l1BroadcastFeeSat.safe()).safe()
                if (quote.isEstimate) return@runSuspendCatching null
                if (!canWithdraw(debitSats)) throw SparkError.WithdrawalOverBalance()
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
        val listed = listPayments().mapNotNull { payment ->
            (payment.details as? PaymentDetails.Withdraw)?.takeIf { it.destinationAddress == address }?.txId
        }
        val recorded = cacheStore.data.first().sentSparkWithdrawals.filter { it.address == address }.map { it.txid }
        (listed + recorded).toSet()
    }

    /**
     * Whether [txid] is the sent side of a Spark withdrawal, which LDK never held and so cannot boost. Savings owns the
     * output a withdrawal to it pays, so its receipt, not [isSent], can be boosted.
     */
    suspend fun isWithdrawal(txid: String, isSent: Boolean): Boolean = withContext(bgDispatcher) {
        if (!isSent) return@withContext false
        if (cacheStore.data.first().sentSparkWithdrawals.any { it.txid == txid }) return@withContext true
        if (!_state.value.isReady) return@withContext false
        runSuspendCatching { listPayments() }
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
        val attempts = listPayments().filter { payment ->
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
        recheckBeforeSend: suspend () -> Boolean = { true },
        onBeforeSend: suspend () -> Boolean = { true },
    ): Result<String> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                ensureNotExiting()
                // Claimed before anything suspends, so a second withdrawal started while this one prepares is refused
                // instead of overwriting its journal.
                if (!isClaimingWithdrawal.compareAndSet(false, true)) throw SparkError.WithdrawalPending()
                var journal: PendingSparkWithdrawal? = null
                var sendReturned = false
                val sent = try {
                    if (cacheStore.data.first().pendingSparkWithdrawal != null) throw SparkError.WithdrawalPending()
                    val prepared = sparkService.prepareSend(address, amountSats)
                    val speed = prepared.withdrawalQuote().speedMedium
                    val debitSats =
                        amountSats.safe() + (speed.userFeeSat.safe() + speed.l1BroadcastFeeSat.safe()).safe()
                    // The user confirmed the leg's debit; a quote that rose since then needs a new confirmation.
                    if (maxDebitSats != null && debitSats > maxDebitSats) throw SparkError.WithdrawalFeeChanged()
                    val earlierTxids = withdrawalTxidsTo(address)
                    // The proof onBeforeSend starts has no journal yet, so the attempt reads as in flight from here on
                    // and a reconcile meanwhile never drops that proof as unsent.
                    isWithdrawing = true
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
                        paymentId = UUID.randomUUID().toString(),
                    )
                    journal = opened
                    cacheStore.update { it.copy(pendingSparkWithdrawal = opened) }
                    // The proof and journal writes suspend, so the authorization is checked again after them.
                    var isAllowed = false
                    try {
                        isAllowed = runSuspendCatching { recheckBeforeSend() }.getOrDefault(false)
                    } finally {
                        // Nothing was sent, so a refusal or a cancel closes the journal at once, and the refusal
                        // reaches the caller even when that write fails.
                        if (!isAllowed) {
                            withContext(NonCancellable) {
                                bestEffort("close the refused Spark withdrawal's journal") { clearJournal(opened) }
                            }
                        }
                    }
                    if (!isAllowed) throw ServiceError.PaymentStoppedAtSubmission()
                    spending {
                        // Saving the journal suspends, so the deadline and an exit that started meanwhile are checked
                        // last, before the send.
                        runCatching {
                            ensurePaymentDeadline(paymentDeadlineAt)
                            ensureNotExiting()
                        }.onFailure {
                            bestEffort("close Spark withdrawal journal") { clearJournal(opened) }
                            throw it
                        }
                        runSuspendCatching { sparkService.send(prepared, opened.paymentId) }
                            .also { sendReturned = true }
                    }
                } finally {
                    if (!sendReturned) endWithdrawalSend()
                    isClaimingWithdrawal.set(false)
                }
                val payment = sent.getOrElse {
                    endWithdrawalSend()
                    // Spark selects the leaves to spend before anything goes to the operators, so a short balance left
                    // nothing and the journal closes at once, as on the Lightning rail.
                    if (it is SdkException.InsufficientFunds) {
                        bestEffort("close Spark withdrawal journal") { clearJournal(journal) }
                        throw SparkError.SendRefused("Spark balance can't cover the withdrawal", it)
                    }
                    // Spark refreshes an expiring quote and checks its fee against the prepared one before it
                    // withdraws, so a fee rise left nothing too.
                    if (it.isWithdrawalFeeRise()) {
                        bestEffort("close Spark withdrawal journal") { clearJournal(journal) }
                        throw SparkError.SendRefused("Spark's withdrawal fee rose past its quote", it)
                    }
                    // Spark's synced list settles the journal: a listed withdrawal records, an unlisted one drops. A
                    // sync that never reached Spark still returns, so only one done after the failure drops it.
                    val failedAt = clock.now().epochSeconds.toULong()
                    runSuspendCatching { sparkService.syncWallet() }
                        .onSuccess { syncActivities(settleJournal = hasSyncedSince(failedAt)) }
                    throw it
                }
                Logger.info("Sent Spark withdrawal '${payment.id}' with status '${payment.status}'", context = TAG)
                // The withdrawal stays in flight until it is recorded, so a sync with an older list can't drop it.
                try {
                    recordActivity(payment)
                } finally {
                    endWithdrawalSend()
                }
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
    fun onDepositBroadcast(txid: String, amountSats: ULong, walletId: String? = null) {
        broadcastDeposits[txid] = amountSats
        // A recovered funding the transfer screen already follows keeps its ceiling and claim.
        sentDeposits.compute(txid) { _, sent ->
            sent?.copy(amountSats = amountSats)
                ?: SparkSentDeposit(amountSats, sentAt = clock.now().epochSeconds, walletId = walletId)
        }
        saveSentDeposits()
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
            .map { (txid, amount) ->
                SparkDeposit(txid = txid, amountSats = amount, isClaimed = false, isListed = false)
            }
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
                val merged = runSuspendCatching { sparkService.importExitState(remote.exitState) }.getOrElse {
                    // Spark checks the version and parses the payload without the operators, so this one never passes.
                    throw if (it is SdkException.InvalidInput) SparkError.ExitStateUnimportable(it) else it
                }
                Logger.info("Merged stored Spark exit data: '${merged.importedLeaves}' leaves", context = TAG)
            }
            hasMergedRemoteExitState = true
        }
    }

    /**
     * Stops merging stored exit data Spark can never import, which the caller kept apart, so the next upload replaces
     * it. A staged restore is dropped only when it holds that same [exitState]; other staged data still imports.
     */
    suspend fun skipUnimportableRemoteExitState(exitState: String?) {
        if (exitState != null) deleteStagedExitState(exitState)
        hasMergedRemoteExitState = true
    }

    /** Tries the import of restored exit data again while connected, and says what became of it. */
    suspend fun importStagedExitState(): StagedExitImport = withContext(bgDispatcher) {
        val file = File(Env.sparkExitRestorePath(walletIndex))
        if (!file.exists()) return@withContext StagedExitImport.NothingStaged
        val network = sparkService.networkName
        if (!sparkService.isConnected || network == null) return@withContext StagedExitImport.Pending
        val staged = file.readText()
        importRestoredExitState(walletIndex).fold(
            onSuccess = { StagedExitImport.Imported },
            onFailure = {
                if (it is SdkException.InvalidInput) {
                    StagedExitImport.Rejected(staged, network)
                } else {
                    StagedExitImport.Pending
                }
            },
        )
    }

    /**
     * Drops restored exit data Spark rejected, once the backup set a copy of it aside, unless newer data came. The
     * stored backup is merged again before the next upload, as that copy may be older than it.
     */
    suspend fun discardStagedExitState(exitState: String) {
        hasMergedRemoteExitState = false
        deleteStagedExitState(exitState)
    }

    /** Deletes staged exit data that still holds [exitState], under the lock staging and import take. */
    private suspend fun deleteStagedExitState(exitState: String) = exitImportMutex.withLock {
        val staged = File(Env.sparkExitRestorePath(walletIndex))
        if (staged.exists() && staged.readText() == exitState) staged.delete()
    }

    /**
     * Puts a restored exit data backup back: at once while Spark is connected, otherwise right after it connects,
     * before it is used, so a restored wallet can exit on-chain even while the Spark operators are unreachable. The
     * wallet's start waits for the restore, so the restore connects Spark itself and fails when Spark can't import the
     * data; one that can't connect keeps the data staged for Spark's start.
     */
    suspend fun restoreExitBackup(backup: SparkExitBackupV1, walletIndex: Int): Result<Unit> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                if (backup.network != sparkService.networkName) {
                    Logger.warn("Skipped Spark exit data from network '${backup.network}'", context = TAG)
                    return@runSuspendCatching
                }
                exitImportMutex.withLock { File(Env.sparkExitRestorePath(walletIndex)).writeText(backup.exitState) }
                if (sparkService.isSupported && !sparkService.isConnected) connectForRestore(walletIndex)
                if (sparkService.isConnected) importRestoredExitState(walletIndex).getOrThrow()
            }
        }

    /** Collects Spark's events once and returns once subscribed, so no event of a connect made after it is lost. */
    private suspend fun collectEvents() {
        if (isCollectingEvents.compareAndSet(false, true)) {
            scope.launch {
                sparkService.events.onSubscription { eventsSubscribed.complete(Unit) }.collect { onSdkEvent(it) }
            }
        }
        eventsSubscribed.await()
    }

    private suspend fun connectForRestore(walletIndex: Int) {
        // Spark's listener drops events no one collects, and the wallet's start collects them only after the restore.
        // The sends held before the restore are taken back first, so its events settle them.
        restoreUncertainSends()
        collectEvents()
        runSuspendCatching { sparkService.connect(walletIndex, exitStore(walletIndex).isActive()) }
            .onSuccess { if (it) _exitStateGeneration.update { generation -> generation + 1 } }
            .onFailure { Logger.warn("Failed to connect Spark to import restored exit data", it, context = TAG) }
    }

    private suspend fun importRestoredExitState(walletIndex: Int): Result<Unit> = exitImportMutex.withLock {
        val file = File(Env.sparkExitRestorePath(walletIndex))
        if (!file.exists()) return@withLock Result.success(Unit)
        runSuspendCatching { sparkService.importExitState(file.readText()) }
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
        val payments = listPayments()
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
        dropReplacedSentDeposits(listed = (pending + claimed).mapTo(mutableSetOf()) { it.txid.lowercase() })
        // The open journal's withdrawal pays a contact, also before its send activity is written.
        val journal = cacheStore.data.first().pendingSparkWithdrawal
        val transfers = payments.filterNot { journal?.matches(it) == true }.toWithdrawals()
            .filterNot { isContactWithdrawal(it.txid) }.toImmutableList()
        val failedAt = withdrawalFailedAtSecs
        val hasSyncedSince = failedAt != null && info.lastSyncError == null && (info.lastSyncedAt ?: 0uL) > failedAt
        if (info.balanceSats > 0uL || hasSyncedSince) withdrawalFailedAtSecs = null
        val withdrewAllAt = withdrewAllAtSecs
        if (withdrewAllAt != null && info.balanceSats > 0uL && (info.lastSyncedAt ?: 0uL) > withdrewAllAt) {
            withdrewAllAtSecs = null
        }
        val isStale = withdrawalFailedAtSecs != null || info.lastSyncError != null
        _state.update {
            it.copy(
                // A refresh that read an SDK disconnected meanwhile leaves the readiness its reconnect set.
                isReady = sparkService.isConnected || it.isReady,
                hasReadState = true,
                balanceSats = info.balanceSats,
                // An exit moves the balance to savings, so a zero read during one keeps nothing.
                keptBalanceSats = it.shownBalanceSats.takeIf { kept ->
                    info.balanceSats == 0uL && isStale && withdrewAllAtSecs == null && kept > 0uL && !it.isExiting
                },
                pendingDepositSats = pending.sumOf { deposit -> deposit.amountSats },
                deposits = (pending + claimed).toImmutableList(),
                withdrawals = transfers,
                recoverableFundsSats = info.recoverableFundsSats,
            ).withBroadcastDeposits()
        }
        markClaimedDeposits(claimed)
        // An own failure a write didn't save before a restart is kept in the cache, not in this process's list.
        val unsavedOwnFailures = cacheStore.data.first().sparkUnsavedOwnFailures
        val deciding = payments.decidingRows()
        // A kept failure that another attempt now decides has no row of its own left to save. One not listed yet is
        // kept, as its event can come before the list shows it.
        val decidingIds = deciding.mapTo(mutableSetOf()) { it.id }
        payments.filter { it.id in unsavedOwnFailures && it.id !in decidingIds }
            .forEach { markUnsavedOwnFailure(it.id, isUnsaved = false) }
        deciding
            .filter {
                it.id in unsavedPayments || it.id in unsavedOwnFailures ||
                    (it.id in sendsRecordedWithoutFee && it.fees.toLong() > 0)
            }
            .forEach { recordActivity(it) }
        // Off the refresh, so a slow Electrum server never holds a send that waits for it.
        scope.launch { confirmMinedPayouts(payments) }
        markTransfers(payments)
        // After the first refresh that worked, as Spark's list then drops the deposits it claimed meanwhile.
        resumeSentDeposits()
    }

    // A sent deposit Spark doesn't list whose funding a fee bump replaced or the mempool dropped leaves the banner and
    // the pending amount: its activity no longer exists.
    private suspend fun dropReplacedSentDeposits(listed: Set<String>) {
        val replaced = sentDeposits.filter { (txid, sent) ->
            txid.lowercase() !in listed && !doesFundingExist(txid, sent.walletId ?: WalletScope.default)
        }.keys
        if (replaced.isEmpty()) return
        replaced.forEach { txid ->
            Logger.info("Dropped the replaced Spark deposit '$txid'", context = TAG)
            sentDeposits.remove(txid)
            broadcastDeposits.remove(txid)
        }
        saveSentDeposits()
    }

    private suspend fun doesFundingExist(txid: String, walletId: String) =
        runSuspendCatching { activityRepo.get().getOnchainActivityByTxId(txid, walletId) }
            .getOrNull()?.doesExist != false

    /**
     * Spark lists a withdrawal completed once its provider took the payout on, which can be before the payout
     * transaction is broadcast or mined, so a contact withdrawal's sent activity is confirmed only once its payout is
     * in a block. The payout address's unspent outputs show that first; an output missing there, such as one the
     * contact spent before Bitkit saw it mined, is looked up by its transaction instead.
     */
    private suspend fun confirmMinedPayouts(payments: List<Payment>) {
        payments.filter { it.status == PaymentStatus.COMPLETED && it.paymentType == SparkPaymentType.SEND }
            .forEach { payment ->
                val withdraw = payment.details as? PaymentDetails.Withdraw ?: return@forEach
                unconfirmedSent(withdraw.txId) ?: return@forEach
                val outputs = runSuspendCatching {
                    coreService.get().onchain.unspentOutputs(
                        address = withdraw.destinationAddress,
                        electrumUrl = settingsStore.data.first().electrumServer,
                        network = Env.network,
                    )
                }.onFailure {
                    Logger.warn("Failed to look up Spark withdrawal payout '${withdraw.txId}'", it, context = TAG)
                }.getOrNull()
                // A failed Electrum lookup falls through to the transaction's status, as a missing output does.
                val payout = outputs?.firstOrNull { it.txid == withdraw.txId && it.vout == withdraw.vout }
                val isMined = payout?.let { it.confirmations > 0u } ?: (payoutChain.isMined(withdraw.txId) == true)
                if (!isMined) return@forEach
                // Read again after the lookups, so a seen time written meanwhile isn't written back over.
                val latest = unconfirmedSent(withdraw.txId) ?: return@forEach
                activityRepo.get().upsertActivity(Activity.Onchain(latest.copy(confirmed = true)))
            }
    }

    private suspend fun unconfirmedSent(txid: String) =
        (activityRepo.get().getActivity(txid).getOrNull() as? Activity.Onchain)?.v1
            ?.takeIf { it.txType == PaymentType.SENT && !it.confirmed }

    private fun markClaimedDeposits(claimed: List<SparkDeposit>) {
        val claimedTxids = claimed.map { it.txid.lowercase() }.toSet()
        _depositClaims.update { claims ->
            claims.mapValues { (txid, claim) ->
                if (txid.lowercase() in claimedTxids) SparkDepositClaim.Claimed else claim
            }
        }
        claimJobs.keys.filter { it.lowercase() in claimedTxids }.forEach { claimJobs.remove(it)?.cancel() }
        val settled = sentDeposits.keys.filter { it.lowercase() in claimedTxids }
        if (settled.isEmpty()) return
        settled.forEach { sentDeposits.remove(it) }
        saveSentDeposits()
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
        // Read before the list, so a hold its success event ends while the list is read still keeps it pending.
        val held = sendHold(paymentHash.lowercase())
        runSuspendCatching { listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
            .getOrNull()
            ?.latestSend(paymentHash)
            // A send in doubt or in Spark's send call is pending until Spark settles it, so a listed failure, which
            // may be an earlier attempt's, settles nothing yet.
            ?.takeUnless { it.status == PaymentStatus.FAILED && isHeldFrom(it, held) }
            ?.toNodeEvent()
    }

    /**
     * Whether [failure] leaves its send pending: while it is in Spark's send call, whose result settles it, or in doubt
     * and the failure is not the held attempt's own, as told by its payment id. A hold [held] read earlier counts too.
     */
    private suspend fun isHeldFrom(failure: Payment, held: SendHold? = null): Boolean {
        val hash = failure.sendHash()?.lowercase() ?: return false
        if (sendsInFlight.containsKey(hash)) return true
        return listOfNotNull(held, sendHold(hash)).any { it.attemptId != failure.id }
    }

    private suspend fun hasOwnFailure(paymentHash: String, attemptId: String?, listing: List<Payment>?): Boolean {
        attemptId ?: return false
        val payments = listing ?: runSuspendCatching { listPayments() }.getOrNull() ?: return false
        if (payments.latestSend(paymentHash)?.status != PaymentStatus.FAILED) return false
        return payments.any { it.id == attemptId && it.status == PaymentStatus.FAILED }
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
        val payments = runSuspendCatching { listPayments() }
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
        // Read before the list, so a hold its success event ends while the list is read still keeps it pending.
        val wasUncertain = sendHold(hash) != null
        val send = listedLightningSend(paymentHash)
        val isUncertain = wasUncertain || sendHold(hash) != null
        val isHeld = when (send) {
            SparkLightningSend.NotFound, SparkLightningSend.Unavailable -> isUncertain || isUnlistedHeld
            is SparkLightningSend.Found -> isUncertain && send.status == PaymentStatus.FAILED
        }
        if (isHeld) SparkLightningSend.Found(PaymentStatus.PENDING, null) else send
    }

    /** Whether [paymentHash] is pending only by a hold on a send under this build's key, which a retry can reuse. */
    private suspend fun isKeyedHold(paymentHash: String): Boolean {
        val hash = paymentHash.lowercase()
        if (sendsInFlight.containsKey(hash) || sendHold(hash) == null) return false
        if (hash !in cacheStore.data.first().sparkSendAttempts) return false
        val listed = listedLightningSend(paymentHash)
        return listed !is SparkLightningSend.Found || listed.status != PaymentStatus.PENDING
    }

    /** The hold on the send of [hash] whose outcome Spark didn't report, null when none. */
    private suspend fun sendHold(hash: String): SendHold? {
        uncertainSends[hash]?.let { return SendHold(it.attemptId) }
        val cache = cacheStore.data.first()
        if (hash !in cache.sparkUncertainSends) return null
        return SendHold(cache.sparkSendAttemptIds[hash])
    }

    /** A send in doubt, held until the outcome of its attempt [attemptId], when known, settles it. */
    private data class SendHold(val attemptId: String?)

    /** Spark's listed record of the Lightning send with [paymentHash], as [lightningSend] reads it before its holds. */
    private suspend fun listedLightningSend(
        paymentHash: String,
        listing: List<Payment>? = null,
    ): SparkLightningSend = withContext(bgDispatcher) {
        if (!sparkService.isSupported) return@withContext SparkLightningSend.NotFound
        if (!canListPayments()) return@withContext SparkLightningSend.Unavailable
        val payments = listing ?: runSuspendCatching { listPayments() }
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
     * After a restore, an invoice created before it ([invoiceCreatedAt] in epoch seconds, null when unknown) and not
     * yet expired ([invoiceExpiresAt], null when unknown) that Spark hasn't listed as pending or paid is refused with
     * [SparkError.RestoredInvoiceUnverified] unless the Spark operators show it unsent, as Spark's list can lack its
     * sends after a restore.
     */
    suspend fun earlierLightningSend(
        bolt11: String,
        paymentHash: String?,
        invoiceCreatedAt: Long? = null,
        invoiceExpiresAt: Long? = null,
    ): EarlierSend? =
        withContext(bgDispatcher) {
            // A caller asking for the invoice again waits for its outcome, also for a send settled while closed.
            paymentHash?.lowercase()?.let { holdRestoreMutex.withLock { restoredAtStart.remove(it) } }
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
                runSuspendCatching { listPayments() }
                    .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
                    .getOrNull()
            } else {
                null
            }
            if (payments == null) {
                startedSend(paymentHash)?.let { return@withContext EarlierSend.Pending(it) }
                refuseUnverifiedRestoredInvoice(bolt11, invoiceCreatedAt, invoiceExpiresAt)
                return@withContext null
            }
            seedSendHashes(payments)
            val send = payments
                .latestSend {
                    it.invoice.equals(bolt11, ignoreCase = true) ||
                        it.htlcDetails.paymentHash.equals(paymentHash, ignoreCase = true)
                }
            if (send == null) {
                (retried() ?: paymentHash?.lowercase()?.takeIf { it in unlisted })
                    ?.let { return@withContext EarlierSend.Pending(it) }
                refuseUnverifiedRestoredInvoice(bolt11, invoiceCreatedAt, invoiceExpiresAt)
                return@withContext null
            }
            if (send.status == PaymentStatus.FAILED) {
                retried()?.let { return@withContext EarlierSend.Pending(it) }
                refuseUnverifiedRestoredInvoice(bolt11, invoiceCreatedAt, invoiceExpiresAt)
            }
            val hash = (send.details as PaymentDetails.Lightning).htlcDetails.paymentHash
            when (send.status) {
                PaymentStatus.PENDING -> EarlierSend.Pending(hash)
                PaymentStatus.COMPLETED -> EarlierSend.Paid(hash)
                PaymentStatus.FAILED -> null
            }
        }

    /** Whether a node send needs its invoice's times for the checks after a restore in [earlierLightningSend]. */
    suspend fun holdsRestoredInvoices(): Boolean {
        return cacheStore.data.first().sparkRestoredAt != null
    }

    /**
     * The rule both rails apply after a restore, as Spark's list can lack a send it made before it: [bolt11], when
     * created before the restore and not yet expired, or when its times are unknown, is refused with
     * [SparkError.RestoredInvoiceUnverified] unless [isUnsentPerOperators].
     */
    private suspend fun refuseUnverifiedRestoredInvoice(
        bolt11: String,
        invoiceCreatedAt: Long?,
        invoiceExpiresAt: Long?,
        isFailedUnsent: Boolean = true,
    ) {
        val restoredAt = cacheStore.data.first().sparkRestoredAt ?: return
        val isHeld = sparkService.isSupported &&
            (invoiceCreatedAt ?: 0L) < restoredAt &&
            (invoiceExpiresAt == null || clock.now().epochSeconds < invoiceExpiresAt)
        if (!isHeld || isUnsentPerOperators(bolt11, isFailedUnsent)) return
        Logger.warn("Refused a send of a pre-restore invoice the Spark operators can't show unsent", context = TAG)
        throw SparkError.RestoredInvoiceUnverified()
    }

    /**
     * Whether the Spark operators confirm this wallet never sent [bolt11] over Lightning, and it can't have been paid
     * by a direct Spark transfer, which they don't see: false when they show a send completed or pending, or can't be
     * asked. A failed one counts as unsent when [isFailedUnsent], as they show it failed only once every attempt at the
     * hash was returned.
     */
    private suspend fun isUnsentPerOperators(bolt11: String, isFailedUnsent: Boolean = true): Boolean {
        if (!sparkService.isConnected) return false
        val status = runSuspendCatching { sparkService.lightningSendStatus(bolt11) }
            .onFailure { Logger.warn("Failed to fetch Spark's send status", it, context = TAG) }
            .getOrElse { return false }
        if (status != null && (status != PaymentStatus.FAILED || !isFailedUnsent)) {
            Logger.info("Found a '$status' Spark operator send of the pre-restore invoice", context = TAG)
            return false
        }
        // A send preferring Spark pays an invoice's Spark address as a direct transfer.
        if (!sparkService.prefersSpark) return true
        return runCatching { !sparkService.carriesSparkAddress(bolt11) }.getOrDefault(false)
    }

    private fun ULong.toEpochSeconds(): Long = coerceAtMost(Long.MAX_VALUE.toULong()).toLong()

    /** Whether a Spark send of [paymentHash] is inside its send call, before or after its records are written. */
    fun isSendInFlight(paymentHash: String): Boolean = sendsInFlight.containsKey(paymentHash.lowercase())

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
        // A retry of a failed send shares its hash, so the outcome that settles it wins, then the latest attempt.
        .minWithOrNull(compareBy({ SEND_STATUS_PRIORITY.indexOf(it.status) }, { -it.timestamp.toLong() }))

    /** Drops each send another attempt on its invoice decides, so one Activity row shows the deciding outcome. */
    private fun List<Payment>.decidingRows(): List<Payment> = filter {
        val hash = (it.details as? PaymentDetails.Lightning)?.htlcDetails?.paymentHash
        it.paymentType != SparkPaymentType.SEND || hash == null || latestSend(hash)?.id == it.id
    }

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
            runSuspendCatching { listPayments() }
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
        runSuspendCatching { listPayments() }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
            .getOrNull()
            ?.filter(filter)
            ?.mapNotNull { (it.details as? PaymentDetails.Lightning)?.htlcDetails?.paymentHash?.lowercase() }
            ?.toSet()
    }

    /**
     * Seeds the restored send hashes from Spark's list once it has synced. An older invoice Spark hasn't listed is
     * still refused until the Spark operators show it unsent or failed, or it expires.
     */
    private suspend fun seedRestoredSendHashes() {
        if (cacheStore.data.first().sparkHistoryUnseededSince == null || !canListPayments()) return
        runSuspendCatching { listPayments() }
            .onSuccess { seedSendHashes(it) }
            .onFailure { Logger.warn("Failed to list Spark payments", it, context = TAG) }
    }

    /** Keeps Spark's pending and paid sends once, from a list read after it synced, so a restart holds the node off. */
    private suspend fun seedSendHashes(payments: List<Payment>) {
        if (cacheStore.data.first().sparkHistoryUnseededSince == null) return
        val sends = payments
            .filter { it.status != PaymentStatus.FAILED && it.sendHash() != null }
            .sortedBy { it.timestamp }
        // A seeded send keeps its invoice's expiry, so Spark holds it while valid and the cap never drops it early.
        val expiries = sends.mapNotNull { send ->
            val invoice = (send.details as PaymentDetails.Lightning).invoice
            val expiresAt = runSuspendCatching { sparkService.invoiceExpiresAt(invoice) }.getOrNull()
            expiresAt?.let { requireNotNull(send.sendHash()) to it.coerceAtMost(Long.MAX_VALUE.toULong()).toLong() }
        }.toMap()
        cacheStore.seedSparkSendHashes(sends.mapNotNull { it.sendHash() }, expiries)
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
        // A list read while a withdrawal was in flight, or before one ended, may lack it, so it settles no journal.
        val sendsEndedBefore = withdrawalSendsEnded.get()
        val wasWithdrawing = isWithdrawing
        // A send held after the list was read may be missing from it, so only the holds before it settle from it.
        val heldBefore = uncertainSends.toMap()
        // Read before the list, as one read before Spark's first sync can lack its restored sends.
        val isSynced = canListPayments()
        val payments = listPayments()
        if (isSynced) seedSendHashes(payments)
        val isSaved = payments.decidingRows().map { recordActivity(it) }.all { it }
        // A held send that this list shows settled, as by its own failure, ends here, so its hold never waits on
        // another read of Spark's list.
        if (isSynced) {
            heldBefore.forEach { (hash, held) ->
                trySettleUncertainSend(
                    hash,
                    listing = payments,
                    held = held
                )
            }
        }
        releaseFailedSends(payments, recordsBefore, inFlightBefore)
        if (!settleJournal || wasWithdrawing || isWithdrawing || withdrawalSendsEnded.get() != sendsEndedBefore) {
            return isSaved
        }
        val journal = cacheStore.data.first().pendingSparkWithdrawal ?: return isSaved
        // A listed withdrawal whose activity failed to save keeps its journal for the next sync to retry; one Spark
        // failed before it had withdrawal details never pays out.
        val isListed = payments.any {
            journal.matches(it) && (it.details is PaymentDetails.Withdraw || it.status != PaymentStatus.FAILED)
        }
        if (isListed) return isSaved
        // Spark may list a withdrawal it just sent only on a later sync, so an unlisted one stays open for a while.
        if (clock.now().epochSeconds < journal.startedAt + UNLISTED_WITHDRAWAL_GRACE.inWholeSeconds) return isSaved
        Logger.warn("Dropped Spark withdrawal to '${journal.address}' that Spark never sent", context = TAG)
        if (clearJournal(journal)) {
            _withdrawalSettlements.emit(SparkWithdrawalSettlement(journal.address, journal.amountSats, null))
        }
        return isSaved
    }

    private fun endWithdrawalSend() {
        isWithdrawing = false
        withdrawalSendsEnded.incrementAndGet()
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
        val current = runSuspendCatching { listPayments() }.getOrNull() ?: return
        val liveCurrent = sendsInFlight.keys + inFlightBeforeCurrent + recordedSince(recordsBeforeCurrent) +
            uncertainSendHashes()
        failed
            .filter { it.lowercase() !in liveCurrent }
            .filter { current.latestSend(it)?.status == PaymentStatus.FAILED }
            .forEach { cacheStore.removeSparkSendHash(it) }
    }

    private fun recordedSince(records: Long): Set<String> = sendRecordedAt.filterValues { it > records }.keys

    private suspend fun listPayments(): List<Payment> = sparkService.listPayments()

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
                    seedRestoredSendHashes()
                    refresh()
                    // A send whose outcome stayed unknown past its polling settles on a later sync, also one only
                    // saved, as when its caller was cancelled inside Spark's send call.
                    restoreUncertainSends()
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
        // An earlier attempt's failure, or one during Spark's send call, leaves the send pending until its own outcome,
        // its call's result or its invoice's expiry settles it.
        payment.sendHash(PaymentStatus.FAILED)?.takeIf { isHeldFrom(payment) }?.let {
            trySettleUncertainSend(it, isFailureEvent = true)
            return
        }
        // A failure of an attempt that Spark's list shows another attempt deciding, as pending, paid or a later
        // failure, settles nothing.
        payment.sendHash(PaymentStatus.FAILED)?.let { hash ->
            val deciding = runSuspendCatching { listPayments() }.getOrNull()?.latestSend(hash)
            if (deciding != null && deciding.id != payment.id) return
        }
        val isOwnAttempt = payment.sendHash()?.let { sendHold(it.lowercase())?.attemptId == payment.id } == true
        recordActivity(payment, settlesJournal = false, isOwnAttempt = isOwnAttempt)
        val isRetried = payment.sendHash()?.let { hash ->
            val isFailed = payment.status == PaymentStatus.FAILED
            val isReleased = isFailed && listedLightningSend(hash).let {
                it == SparkLightningSend.NotFound || (it as? SparkLightningSend.Found)?.status == PaymentStatus.FAILED
            }
            // The event settles a send whose outcome was unknown, so retries of its invoice are no longer held. A send
            // enters Spark's send call under the same lock, so a retry started since the checks above keeps its hold
            // and saved send, and reports its own outcome.
            holdRestoreMutex.withLock {
                if (isFailed && isHeldFrom(payment)) return@withLock true
                uncertainSends.remove(hash.lowercase())
                if (restoredAtStart.remove(hash.lowercase())) reportedSendIds.add(payment.id)
                bestEffort("end the hold of Spark send '$hash'") { cacheStore.removeSparkUncertainSend(hash) }
                if (isReleased) {
                    bestEffort("drop the hash of Spark send '$hash'") { cacheStore.removeSparkSendHash(hash) }
                }
                false
            }
        } ?: false
        if (isRetried) return
        // The poll may have reported this payment's outcome already.
        if (payment.sendHash() != null && !reportedSendIds.add(payment.id)) return
        payment.toNodeEvent()?.let { _nodeEvents.emit(it) }
    }

    /**
     * Records [payment] as activity and returns whether it is saved; a failed write is kept in [unsavedPayments]. Only
     * Spark's list or its send response [settlesJournal]: a payment event can come before the list holds the
     * withdrawal, which recovery reads, so its journal stays open until the list shows it. A failure of the send's own
     * attempt [isOwnAttempt] writes its details, which another attempt's failure leaves as they are.
     */
    private suspend fun recordActivity(
        payment: Payment,
        settlesJournal: Boolean = true,
        isOwnAttempt: Boolean = false,
    ): Boolean {
        (payment.details as? PaymentDetails.Withdraw)?.let { return recordWithdrawal(payment, it, settlesJournal) }
        val lightning = payment.details as? PaymentDetails.Lightning ?: return true
        if (payment.status == PaymentStatus.PENDING && payment.paymentType == SparkPaymentType.RECEIVE) {
            return true
        }
        val id = lightning.htlcDetails.paymentHash
        val isOwn = payment.status == PaymentStatus.FAILED &&
            (isOwnAttempt || payment.id in cacheStore.data.first().sparkUnsavedOwnFailures)
        // A send in doubt or in Spark's send call shows as pending until Spark settles it, whatever another attempt on
        // its invoice failed with.
        if (payment.status == PaymentStatus.FAILED && !isOwnAttempt && isHeldFrom(payment)) {
            return true
        }
        // A failed read would drop the stored note, contact and seen time, so the write waits for the next refresh.
        val stored = activityRepo.get().getActivity(id).getOrElse {
            unsavedPayments.add(payment.id)
            if (isOwn) markUnsavedOwnFailure(payment.id, isUnsaved = true)
            return false
        }
        val existing = (stored as? Activity.Lightning)?.v1
        // A receive sharing its hash with a channel send is a move to Spark, which Activity shows as that send;
        // the completed receive proves the send settled.
        if (payment.paymentType == SparkPaymentType.RECEIVE && existing?.txType == PaymentType.SENT) {
            return settleMoveToSpark(payment, existing)
        }
        // Another attempt of the same invoice, on either rail, already paid it.
        if (existing?.status == PaymentState.SUCCEEDED && payment.status != PaymentStatus.COMPLETED) {
            markUnsavedOwnFailure(payment.id, isUnsaved = false)
            return true
        }
        // A failure, possibly an earlier attempt's, only fails the send's row, which keeps its own details.
        val failedRow = existing?.takeIf {
            payment.status == PaymentStatus.FAILED && it.txType == PaymentType.SENT && !isOwn
        }
        if (failedRow?.status == PaymentState.FAILED) return true
        val activity = failedRow?.copy(status = PaymentState.FAILED) ?: LightningActivity.create(
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
            if (isOwn) markUnsavedOwnFailure(payment.id, isUnsaved = true)
            return false
        }
        unsavedPayments.remove(payment.id)
        markUnsavedOwnFailure(payment.id, isUnsaved = false)
        if (payment.paymentType == SparkPaymentType.SEND && payment.fees.toLong() == 0L) {
            sendsRecordedWithoutFee.add(payment.id)
        } else {
            sendsRecordedWithoutFee.remove(payment.id)
        }
        return true
    }

    /**
     * Keeps [paymentId] as an own failure whose row is still to save, or drops it. A failed cache write only loses
     * that detail, so it never stops the settlement, hold release or report around it.
     */
    private suspend fun markUnsavedOwnFailure(paymentId: String, isUnsaved: Boolean) {
        runSuspendCatching {
            if (isUnsaved) {
                cacheStore.addSparkUnsavedOwnFailure(paymentId)
            } else {
                cacheStore.removeSparkUnsavedOwnFailure(paymentId)
            }
        }.onFailure { Logger.warn("Failed to update Spark own failure '$paymentId'", it, context = TAG) }
    }

    private suspend fun settleMoveToSpark(payment: Payment, send: LightningActivity): Boolean {
        if (payment.status != PaymentStatus.COMPLETED || send.status == PaymentState.SUCCEEDED) return true
        val settled = Activity.Lightning(send.copy(status = PaymentState.SUCCEEDED))
        val saved = activityRepo.get().upsertActivity(settled)
            .let { it.isSuccess || it.exceptionOrNull() is ActivityDeletedError }
        if (!saved) {
            unsavedPayments.add(payment.id)
            return false
        }
        unsavedPayments.remove(payment.id)
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
            // Spark completes a withdrawal before its payout is mined, so only the chain confirms it.
            confirmed = existing?.confirmed == true,
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
    /** The idempotency key the send passed, which Spark keeps as the payment's id. */
    val paymentId: String? = null,
) {
    /**
     * Whether Spark lists [payment] for this attempt: by [paymentId], also while Spark lists it without withdrawal
     * details, or by address and amount.
     */
    fun matches(payment: Payment): Boolean {
        if (paymentId != null && payment.id == paymentId) return true
        val withdraw = payment.details as? PaymentDetails.Withdraw ?: return false
        return address == withdraw.destinationAddress && amountSats == payment.amount.toLong().toULong() &&
            withdraw.txId !in earlierTxids
    }
}

/**
 * A deposit the transfer flow sent, kept until Spark claims it: its amount, when it was sent in epoch seconds, the
 * Instant fee ceiling the user approved, and where its claim stood with the user's choice over a changed fee.
 */
@Serializable
data class SparkSentDeposit(
    val amountSats: ULong,
    val sentAt: Long,
    /** The hardware wallet that sent it, whose activity says when a fee bump replaced it; null for Savings. */
    val walletId: String? = null,
    val ceilingSats: ULong = 0uL,
    /** Null until the transfer flow follows its claim: such a deposit shows as pending but isn't followed again. */
    val step: Step? = null,
    /** The offer whose early fee the user is asked to approve when [step] is [Step.Repriced]. */
    val repricedOffer: SparkClaimOffer.Choice? = null,
    val choice: SparkClaimChoice? = null,
    /** The output an early claim was attempted for, whose fee cap Wait clears. */
    val earlyClaimVout: UInt? = null,
) {
    /** Where a saved claim stood; a claimed deposit is no longer kept. */
    enum class Step { Looking, Adding, Repriced, Unavailable, Later, NotSeen }

    fun withClaim(claim: SparkDepositClaim?, choice: SparkClaimChoice?, earlyClaimVout: UInt?) = copy(
        step = when (claim) {
            null -> null
            SparkDepositClaim.Looking, SparkDepositClaim.Claimed -> Step.Looking
            SparkDepositClaim.Adding -> Step.Adding
            is SparkDepositClaim.Repriced -> Step.Repriced
            SparkDepositClaim.Unavailable -> Step.Unavailable
            SparkDepositClaim.Later -> Step.Later
            SparkDepositClaim.NotSeen -> Step.NotSeen
        },
        repricedOffer = (claim as? SparkDepositClaim.Repriced)?.offer,
        choice = choice,
        earlyClaimVout = earlyClaimVout,
    )

    fun toDepositClaim(): SparkDepositClaim? = when (step) {
        null -> null
        Step.Looking -> SparkDepositClaim.Looking
        Step.Adding -> SparkDepositClaim.Adding
        Step.Repriced -> repricedOffer?.let { SparkDepositClaim.Repriced(it) } ?: SparkDepositClaim.Looking
        Step.Unavailable -> SparkDepositClaim.Unavailable
        Step.Later -> SparkDepositClaim.Later
        Step.NotSeen -> SparkDepositClaim.NotSeen
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
    /** Spark read its balance and payments at least once, so they stay shown while it cannot connect. */
    val hasReadState: Boolean = false,
    /** The last start failed; the next start clears it when it begins. */
    val hasStartFailed: Boolean = false,
    /** What Spark read, which sends and withdrawals use. */
    val balanceSats: ULong = 0uL,
    /**
     * The balance shown while Spark reads 0 after a failed withdrawal or a failed sync, with the funds likely still
     * there; null once Spark reads a balance again or syncs after the failure, and after a full withdrawal, whose 0 is
     * real. Never offered to a send.
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

    /** Whether Spark is ready and can send [amountSats] once its service fee is paid. */
    fun canSend(amountSats: ULong): Boolean = isReady && sendableSats >= amountSats

    /** The balance shown in Spending and on the Spark connection, which [keptBalanceSats] stands in for. */
    val shownBalanceSats: ULong get() = keptBalanceSats ?: balanceSats

    /** The [spendingSats] shown in Spending, which [keptBalanceSats] stands in for. */
    val shownSpendingSats: ULong get() = if (isExiting) 0uL else shownBalanceSats

    /** The deposit whose progress the transfer banner opens: the first one Spark has not claimed yet. */
    val inProgressDepositTxid: String? get() = deposits.firstOrNull { !it.isClaimed }?.txid
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
    /** Whether Spark lists it, rather than only the app that just broadcast it. */
    val isListed: Boolean = true,
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
    @Serializable
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

/** What the user chose over a changed fast transfer fee. */
@Serializable
enum class SparkClaimChoice { Approved, Waited }

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

/**
 * Spark refuses a withdrawal whose refreshed fee quote costs more than the prepared one, even at its slowest speed,
 * with this input error before it withdraws (`affordable_speed` in the 0.28.0 SDK's `send/bitcoin_address.rs`).
 */
internal fun Throwable.isWithdrawalFeeRise(): Boolean =
    this is SdkException.InvalidInput && v1.contains("onchain fee rose to", ignoreCase = true)

/**
 * Spark fails a send with this error when it holds the balance but can't select leaves to spend, for instance right
 * after a move to Spark lands (`select_leaves_with_retry` in the 0.28.3 SDK's `spark-wallet/src/wallet.rs`). The SDK
 * selects leaves again after an operator error, so the error can follow a transfer the send committed.
 */
internal fun Throwable.isLeafSelectionFailure(): Boolean =
    this is SdkException.SparkException && v1.contains("Failed to select leaves after all retries", ignoreCase = true)

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

/** What became of restored exit data waiting for Spark to import it. */
sealed interface StagedExitImport {
    data object NothingStaged : StagedExitImport
    data object Imported : StagedExitImport

    /** Spark is not connected, or the import failed for a reason that may pass. */
    data object Pending : StagedExitImport

    /** Spark can never import [exitState], restored for [network]. */
    data class Rejected(val exitState: String, val network: String) : StagedExitImport
}
