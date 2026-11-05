package to.bitkit.repositories

import breez_sdk_spark.CpfpFundingKind
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.CpfpSigner
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.PrepareRecoverFundsRequest
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.singleKeyCpfpSigner
import com.synonym.bitkitcore.Activity
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.safe
import to.bitkit.services.CoreService
import to.bitkit.services.SparkError
import to.bitkit.services.SparkService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a Spark emergency exit: the quote, the fee funding, then the driver until every step is on-chain. The exit
 * survives restarts through [SparkExitStore], and is driven while Bitkit is open, by WorkManager while it is closed,
 * and after a notification brings the user back when a timelock matures.
 */
@Singleton
class SparkExitRepo @Inject constructor(
    private val work: SparkExitWork,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val sparkService: SparkService,
    private val sparkRepo: SparkRepo,
    private val lightningRepo: LightningRepo,
    private val transferRepo: TransferRepo,
    private val activityRepo: ActivityRepo,
    private val coreService: CoreService,
    private val keychain: Keychain,
    private val httpClient: HttpClient,
    private val clock: Clock,
    private val sparkExitTip: SparkExitTip,
    private val cacheStore: CacheStore,
) {
    companion object {
        private const val TAG = "SparkExitRepo"

        /** How often the exit is driven while Bitkit is in the foreground. */
        private val FOREGROUND_TICK_INTERVAL = 60.seconds

        /** How long a background run waits for Spark to connect before it gives up on the SDK steps. */
        private val BACKGROUND_CONNECT_TIMEOUT = 2.minutes
    }

    private val scope = appScope(bgDispatcher, TAG)
    private val tickMutex = Mutex()
    private var foregroundJob: Job? = null

    @Volatile
    private var walletIndex = 0

    @Volatile
    private var isLoaded = false

    private val _state = MutableStateFlow(SparkExitState())
    val state: StateFlow<SparkExitState> = _state.asStateFlow()

    init {
        scope.launch {
            sparkRepo.walletWiped.collect {
                if (it != walletIndex) return@collect
                work.cancelAll()
                _state.update { SparkExitState() }
            }
        }
    }

    private val store get() = SparkExitStore(File(Env.sparkExitRecordPath(walletIndex)))

    private val lostLeafStore get() = SparkExitLostLeafStore(File(Env.sparkExitLostLeavesPath(walletIndex)))

    private val chain: SparkExitChain?
        get() = EsploraSparkExitChain.defaultHosts(sparkService.networkName)
            ?.let { EsploraSparkExitChain(it, httpClient) }

    // region lifecycle

    /** Loads the exit of [walletIndex] once, takes Spark off Lightning while it runs, and settles a finished one. */
    suspend fun load(walletIndex: Int = sparkRepo.walletIndex): Result<SparkExitRecord?> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (isLoaded && this@SparkExitRepo.walletIndex == walletIndex) return@runSuspendCatching _state.value.record
            this@SparkExitRepo.walletIndex = walletIndex
            val record = runSuspendCatching { store.load() }.getOrElse {
                Logger.error("Failed to read the Spark exit record", it, context = TAG)
                salvage()
            }
            isLoaded = true
            _state.update { it.copy(record = record) }
            rememberLostLeaves(record)
            record?.let { settleIfFinished(it) }
            settleOrphanedTransfers(record)
            if (record?.needsDriving == true) work.schedulePeriodic()
            record
        }
    }

    /**
     * A record that cannot be read, such as one of an unknown version, is kept aside unchanged for support, and what
     * can be read of it carries on as an exit that rebuilds from the chain, so a running exit never disappears.
     */
    private fun salvage(): SparkExitRecord? {
        val store = store
        val text = runCatching { store.loadText() }.getOrNull()
        runCatching { store.setAside(clock.nowMs()) }
            .onFailure { Logger.warn("Failed to set the unreadable Spark exit record aside", it, context = TAG) }
        val salvaged = text?.let { SparkExitJson.salvage(it, clock.nowMs()) } ?: return null
        runCatching { store.save(salvaged) }
            .onFailure { Logger.error("Failed to save the salvaged Spark exit record", it, context = TAG) }
        Logger.warn("Salvaged the Spark exit record in phase '${salvaged.phase}' to rebuild from chain", context = TAG)
        return salvaged
    }

    /** Drives the exit every minute while Bitkit is in the foreground. */
    fun onAppForegrounded() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            load().onFailure { Logger.error("Failed to load the Spark exit", it, context = TAG) }
            while (isActive && _state.value.record?.needsDriving == true) {
                tick()
                delay(FOREGROUND_TICK_INTERVAL)
            }
        }
    }

    fun onAppBackgrounded() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    /**
     * One background run: the steps the stored recovery allows without the SDK first, then, once Spark connects, the
     * SDK check. It never starts the Lightning node. An ended exit only sweeps its leftover, which needs no SDK.
     */
    suspend fun runInBackground(): Result<SparkExitRecord?> = withContext(bgDispatcher) {
        runSuspendCatching {
            load().getOrThrow()
            if (_state.value.record?.needsDriving != true) return@runSuspendCatching _state.value.record
            tick()
            if (_state.value.isExiting && !sparkService.isConnected) {
                withTimeoutOrNull(BACKGROUND_CONNECT_TIMEOUT) { sparkRepo.start(walletIndex) }
                if (sparkService.isConnected) tick()
            }
            _state.value.record
        }
    }

    // endregion

    // region tick

    /** Moves the exit one step forward; a tick already running makes this one a no-op. */
    suspend fun tick(): Result<SparkExitRecord?> = withContext(bgDispatcher) {
        if (!tickMutex.tryLock()) return@withContext Result.success(_state.value.record)
        try {
            runSuspendCatching { tickLocked() }
                .onFailure {
                    Logger.error("Failed to drive the Spark exit", it, context = TAG)
                    _state.update { state -> state.copy(lastError = it.message) }
                }
        } finally {
            tickMutex.unlock()
            // Every tick keeps the background work queued, so one failed or skipped run never ends the chain.
            if (_state.value.record?.needsDriving == true) work.schedulePeriodic()
        }
    }

    private suspend fun tickLocked(): SparkExitRecord? {
        val current = _state.value.record?.takeIf { it.needsDriving } ?: return _state.value.record
        val chain = chain ?: return current
        Logger.debug("Driving Spark exit in phase '${current.phase}'", context = TAG)
        val tip = runSuspendCatching { chain.tipHeight() }.getOrNull()
        _state.update { it.copy(tipHeight = tip ?: it.tipHeight) }
        tip?.let { height -> sparkExitTip.height.update { height } }
        refreshFundingSeen(chain, current)
        val store = store
        val recovering = sparkService.exitRecovering()
        val driver = SparkExitDriver(
            chain = chain,
            recovering = recovering,
            // The record's own funding key, also for exits started before each exit had an index of its own.
            makeSigner = { signer(current.fundingIndex) },
            save = store::save,
            nowMillis = clock::nowMs,
            tip = tip,
        )
        // Read before this tick's losses are kept, so a leaf lost now is still reported once.
        val reportedLost = reportedLostLeafIds()
        val updated = driver.tick(current)
        rememberLostLeaves(updated)
        _state.update { it.copy(record = updated, lastError = null) }
        // A tick that changed the phase, such as a build short of fees, needs the funding state of the new phase.
        if (updated.phase != current.phase) refreshFundingSeen(chain, updated)
        // Spark's balance next to the exit's leaves shows value outside the exit.
        if (recovering != null) _state.update { it.copy(sparkBalanceSats = sparkRepo.state.value.balanceSats) }
        // A rebuild needs the SDK; every other running step can go out from the stored recovery without it. Steps
        // refused again and again keep the exit from moving, however often it runs.
        val canMove = recovering != null || !updated.isRebuilding
        // Without the SDK nothing rebuilds over a step that keeps failing. A step the network holds but does not
        // confirm is underpriced, which no run changes. Either way the alarm may fire.
        val isStuckWithoutSdk = recovering == null && updated.stuckStep != null
        val isOnTrack = tip != null && canMove && updated.surfacedRejection == null && !updated.isFeeChainStuck &&
            !isStuckWithoutSdk && updated.underpricedStep(tip) == null
        afterTick(updated, current, isOnTrack, reportedLost)
        return updated
    }

    private suspend fun afterTick(
        record: SparkExitRecord,
        previous: SparkExitRecord,
        isOnTrack: Boolean,
        reportedLost: Set<String>,
    ) {
        record.transferId?.let { transferRepo.updateClaimableAtHeight(it, record.claimableAtHeight) }
        settleIfFinished(record)
        updateAlerts(record, previous, isOnTrack, reportedLost)
        if (!record.isActive && previous.isActive) {
            Logger.info("Ended Spark exit with phase '${record.phase}'", context = TAG)
            sparkRepo.setExiting(false)
        }
        if (!record.needsDriving) work.cancelAll()
    }

    /**
     * Ends the exit's transfer once the sweep reaches savings, or once the exit ended, so its amount is never counted
     * both as in transfer and in the on-chain balance. Runs on every tick and load until it holds.
     */
    private suspend fun settleIfFinished(record: SparkExitRecord) {
        val transferId = record.transferId ?: return
        if (transferRepo.isSettled(transferId).getOrDefault(true)) return
        var isFinished = !record.isActive
        val sweep = record.recovery?.transactions?.firstOrNull { it.kind == RecoveryTxKind.SWEEP }
        val sweepActivity = sweep?.let { activityRepo.getOnchainActivityByTxId(it.txid) }
        if (sweepActivity != null) {
            if (!sweepActivity.isTransfer) {
                activityRepo.updateActivity(sweepActivity.id, Activity.Onchain(sweepActivity.copy(isTransfer = true)))
            }
            isFinished = true
        }
        if (isFinished) transferRepo.markSettled(transferId)
    }

    /**
     * Settles exit transfers no running exit owns: a record set aside as unreadable, or one written without its
     * transfer id before a crash, would otherwise count its amount as in transfer to Savings for good.
     */
    private suspend fun settleOrphanedTransfers(record: SparkExitRecord?) {
        val owned = record?.takeIf { it.isActive }?.transferId
        transferRepo.activeTransfers.first()
            .filter { it.type == TransferType.SPARK_EXIT && it.id != owned }
            .forEach {
                Logger.warn("Settling Spark exit transfer '${it.id}' with no running exit", context = TAG)
                transferRepo.markSettled(it.id)
            }
    }

    private suspend fun refreshFundingSeen(chain: SparkExitChain, record: SparkExitRecord) {
        val address = record.funding.firstOrNull()?.address ?: return
        _state.update { it.copy(fundingAddress = address) }
        if (record.fundingShortfallSats == null) {
            _state.update { it.copy(fundingState = null) }
            return
        }
        val utxos = runSuspendCatching { chain.utxos(address) }.getOrNull()
        // Only new funding counts as seen; adopted inputs and their CPFP change are already part of the exit.
        val seen = utxos?.let { SparkExitMath.newFunding(it, record) }?.fold(0uL) { sum, utxo ->
            sum.safe() + utxo.value.safe()
        }
        _state.update {
            it.copy(
                fundingSeenSats = seen ?: it.fundingSeenSats,
                fundingState = SparkExitMath.fundingState(record, utxos),
            )
        }
    }

    /**
     * Pushes back the warning that the exit fell behind, to 12 blocks into the operators' window of the next step to
     * mature (about 50 blocks after it, when they may publish their own version), or of the steps already waiting to
     * be published. Only a run that kept the exit on track pushes it; a stalled exit lets it fire. Needing more fee
     * funding is notified once, at once.
     */
    private fun updateAlerts(
        record: SparkExitRecord,
        previous: SparkExitRecord,
        isOnTrack: Boolean,
        reportedLost: Set<String>,
    ) {
        SparkExitAlerts.losses(record, previous, reportedLost).forEach {
            work.notify(it, amountSats = record.lostSats.takeIf { _ -> it == SparkExitAlert.Lost })
        }
        when (record.phase) {
            // Waiting on the operators for a week needs nothing from the user, so nothing reminds them.
            SparkExitRecord.Phase.Running if record.isWaitingForOperators -> {
                work.keepFeeLow(null)
                work.cancelDeadline()
            }
            SparkExitRecord.Phase.Running -> {
                val tip = _state.value.tipHeight
                work.keepFeeLow(record.feeRateSatPerVbyte.takeIf { record.underpricedStep(tip) != null })
                if (!isOnTrack) return
                val untilMaturity = SparkExitMath.untilNextMaturity(record.recovery, tip, Env.network)
                work.armDeadline(
                    SparkExitMath.deadline(untilMaturity, Env.network),
                    clock.nowMs(),
                )
            }
            is SparkExitRecord.Phase.NeedsFunding -> {
                work.cancelDeadline()
                if (previous.phase !is SparkExitRecord.Phase.NeedsFunding) work.notify(SparkExitAlert.Funding)
            }
            else -> work.cancelDeadline()
        }
    }

    private fun reportedLostLeafIds(): Set<String> = runCatching { lostLeafStore.load() }
        .onFailure { Logger.warn("Failed to read the lost Spark exit leaves", it, context = TAG) }
        .getOrDefault(emptySet())

    private fun rememberLostLeaves(record: SparkExitRecord?) {
        val ids = record?.lostLeaves?.map { it.leafId }?.takeIf { it.isNotEmpty() } ?: return
        runCatching { lostLeafStore.add(ids) }
            .onFailure { Logger.warn("Failed to keep the lost Spark exit leaves '$ids'", it, context = TAG) }
    }

    /** What Android lets the exit do while Bitkit is closed, for the progress screen. */
    fun backgroundLimits(): SparkExitBackgroundLimits = work.backgroundLimits()

    // endregion

    // region quote and start

    /** Quotes an exit of every leaf to a fresh savings address at the SDK's fastest fee rate. */
    suspend fun loadQuote(): Result<SparkExitQuote> = withContext(bgDispatcher) {
        runSuspendCatching {
            val recovering = sparkService.exitRecovering() ?: throw SparkError.NotConnected()
            if (sparkRepo.needsSettling()) _state.update { it.copy(isSettling = true) }
            sparkRepo.settleForExit().also { _state.update { it.copy(isSettling = false) } }.getOrThrow()
            val feeRate = maxOf(1uL, sparkService.fastestFeeRate())
            val destination = _state.value.pending?.destination ?: lightningRepo.newAddress().getOrThrow()
            // Leaves an earlier exit lost to an older state stay listed by the operators, but cannot be exited.
            val lost = reportedLostLeafIds() + _state.value.record?.lostLeaves?.map { it.leafId }.orEmpty()
            val quote = recovering.prepareAll(
                PrepareRecoverFundsRequest(
                    feeRateSatPerVbyte = feeRate,
                    fundingKind = CpfpFundingKind.P2wpkh,
                    // Each fee-bumped transaction gets a UTXO of its own, so one lost fee race strands one step's
                    // funding, not the chain after it.
                    fundingStrategy = CpfpFundingStrategy.PER_NODE,
                    destination = destination,
                    selection = ExitLeafSelection.All,
                ),
                lost,
            ).copy(sparkBalanceSats = sparkRepo.state.value.balanceSats)
            // Chosen here, so the funding screen, the fee estimate and the record all use one address.
            val fundingIndex = nextFundingIndex()
            val fundingAddress = fundingKey(fundingIndex).address
            _state.update {
                it.copy(
                    pending = SparkExitPending(quote, destination, feeRate, fundingIndex),
                    fundingAddress = fundingAddress,
                )
            }
            quote
        }.onFailure {
            Logger.error("Failed to quote the Spark exit", it, context = TAG)
        }
    }

    /** The fee Savings pays to send the fee funding at the fast rate, or null when it cannot. */
    suspend fun savingsFundingFee(): Result<SparkExitSavingsFunding> = withContext(bgDispatcher) {
        runSuspendCatching {
            val pending = requireNotNull(_state.value.pending) { "No Spark exit quote" }
            val address = requireNotNull(_state.value.fundingAddress) { "No Spark exit funding address" }
            val fundingSats = pending.quote.fundingSats
            val feeSats = lightningRepo.calculateTotalFee(fundingSats, address, TransactionSpeed.Fast).getOrThrow()
            val spendable = lightningRepo.getBalancesAsync().getOrThrow().spendableOnchainBalanceSats
            SparkExitSavingsFunding(
                feeSats = feeSats,
                canPay = SparkExitMath.canFundFromSavings(spendable, fundingSats, feeSats),
            )
        }
    }

    /** Reads what reached the funding address, for the funding screen while the user pays from another wallet. */
    suspend fun refreshFundingAddress(): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            val chain = requireNotNull(chain) { "No chain host for this network" }
            val address = requireNotNull(_state.value.fundingAddress) { "No Spark exit funding address" }
            val utxos = chain.utxos(address)
            val seen = utxos.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
            _state.update { it.copy(fundingSeenSats = seen, fundingSeenCount = utxos.size) }
            seen
        }
    }

    /**
     * Starts the exit for the current quote: writes its record, moves the Spark balance into transfer to savings and,
     * with [fundFromSavings], pays the fee funding from savings.
     */
    suspend fun start(fundFromSavings: Boolean): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (_state.value.isExiting) throw SparkExitError("A Spark exit is already running")
            val pending = requireNotNull(_state.value.pending) { "No Spark exit quote" }
            val network = requireNotNull(sparkService.networkName) { "Spark is not available on this network" }
            val key = fundingKey(pending.fundingIndex)
            val quote = pending.quote
            var record = SparkExitRecord(
                network = network,
                createdAt = clock.nowMs(),
                phase = SparkExitRecord.Phase.AwaitingFunding(quote.fundingSats),
                feeRateSatPerVbyte = pending.feeRate,
                destination = pending.destination,
                leafIds = quote.leafIds,
                funding = listOf(
                    SparkExitRecord.Funding(index = pending.fundingIndex, address = key.address, pubkey = key.pubkey),
                ),
                expectedSats = quote.incomingSats,
                skippedLeafCount = quote.prepared.skipped.size,
                fundingStrategy = SparkExitRecord.PER_NODE,
                leafTotalSats = quote.prepared.leaves.fold(0uL) { sum, leaf -> sum.safe() + leaf.valueSats.safe() },
            )
            // Saved before the transfer exists, so a failed save never leaves a transfer counted with no exit behind.
            store.save(record)
            cacheStore.update {
                val next = it.sparkExitNextFundingIndexes + (fundingIndexKey to pending.fundingIndex.toInt() + 1)
                it.copy(sparkExitNextFundingIndexes = next)
            }
            val transferId = transferRepo.createTransfer(
                TransferType.SPARK_EXIT,
                quote.incomingSats.toLong()
            ).getOrThrow()
            record = record.copy(transferId = transferId)
            store.save(record)
            _state.update { it.copy(record = record, pending = null, fundingAddress = key.address) }
            isLoaded = true
            // Reconnecting for the exit disconnects Spark, which would cut a running leaf optimization short.
            sparkRepo.awaitOptimizationIdle()
            sparkRepo.setExiting(true)
            work.schedulePeriodic()
            Logger.info(
                "Started Spark exit of '${quote.exitSats}' sats over '${quote.leafIds.size}' leaves",
                context = TAG,
            )
            // Driven before paying, so a failed payment from savings still leaves the exit watching its address.
            scope.launch { tick() }
            onAppForegrounded()
            if (fundFromSavings && quote.fundingSats > 0uL) payFunding(key.address, quote.fundingSats)
        }.onFailure {
            Logger.error("Failed to start the Spark exit", it, context = TAG)
        }
    }

    /** Pays the fee funding from savings as an ordinary payment: it is a fee, not a transfer to spending. */
    private suspend fun payFunding(address: String, sats: ULong) {
        val outcome = lightningRepo.sendOnChain(
            address = address,
            sats = sats,
            speed = TransactionSpeed.Fast,
        ).getOrThrow()
        if (outcome !is OnchainSendOutcome.Accepted) {
            throw SparkExitError(
                "Spark exit funding was not accepted: '$outcome'"
            )
        }
        // After the tick in flight, so its save cannot drop the txid; progress then waits for it instead of asking.
        tickMutex.withLock {
            val record = _state.value.record ?: return@withLock
            val paid = record.copy(fundingPaymentTxid = outcome.txid)
            store.save(paid)
            _state.update {
                it.copy(record = paid, fundingState = SparkExitMath.fundingState(paid, utxos = null))
            }
        }
        runSuspendCatching { lightningRepo.completeAcceptedOrdinaryFollowup(outcome.txid) }
            .onFailure { Logger.warn("Failed to finish the Spark exit funding locally", it, context = TAG) }
        Logger.info("Funded the Spark exit with '$sats' sats in '${outcome.txid}'", context = TAG)
    }

    /**
     * Whether the exit can stop at no cost: nothing was built and its funding address received nothing, so Spark
     * comes back as it was.
     */
    val canCancel: Boolean
        get() {
            val exit = _state.value
            val record = exit.record ?: return false
            return record.phase is SparkExitRecord.Phase.AwaitingFunding &&
                record.recovery == null &&
                record.fundedSats == 0uL &&
                exit.fundingSeenSats == 0uL
        }

    /** The user opened the result of an exit that failed or lost leaves: Emergency exit may start a new one now. */
    suspend fun markResultSeen(): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            tickMutex.withLock {
                val record = _state.value.record?.takeIf { it.hasUnseenResult }
                    ?: return@withLock
                val seen = record.copy(isResultSeen = true)
                store.save(seen)
                _state.update { it.copy(record = seen) }
            }
        }
    }

    suspend fun cancel(): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (!canCancel) throw SparkExitError("The Spark exit can no longer be cancelled")
            val record = requireNotNull(_state.value.record)
            store.delete()
            _state.update { it.copy(record = null) }
            record.transferId?.let { transferRepo.markSettled(it) }
            work.cancelAll()
            sparkRepo.setExiting(false)
            Logger.info("Cancelled the Spark exit before it was funded", context = TAG)
        }
    }

    // endregion

    // region keys

    private val fundingIndexKey get() = "${Env.network.name.lowercase()}/wallet$walletIndex"

    /** A funding key index no exit of this wallet used. */
    private suspend fun nextFundingIndex(): UInt = SparkExitMath.nextFundingIndex(
        kept = cacheStore.data.first().sparkExitNextFundingIndexes[fundingIndexKey],
        stored = runSuspendCatching { store.load() }.getOrNull(),
    )

    /** Every funding UTXO of one exit goes to its index: the SDK's single-key signer signs all inputs with one key. */
    private suspend fun fundingKey(index: UInt): SparkExitFundingKey.Derived {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val path = SparkExitFundingKey.path(index = index.toInt(), network = Env.network)
        val address = coreService.onchain.deriveBitcoinAddress(mnemonic, path, Env.network, passphrase)
        val wif = coreService.onchain.derivePrivateKey(mnemonic, path, Env.network, passphrase)
        return SparkExitFundingKey.Derived(address.address, address.publicKey, SparkExitFundingKey.secretKey(wif))
    }

    private suspend fun signer(index: UInt): CpfpSigner = singleKeyCpfpSigner(fundingKey(index).secretKey)

    // endregion
}

class SparkExitError(message: String) : AppError(message)

/** A quote the user is looking at, with what the exit is built from once it starts. */
data class SparkExitPending(
    val quote: SparkExitQuote,
    val destination: String,
    val feeRate: ULong,
    /** The funding key index this exit takes. */
    val fundingIndex: UInt,
)

data class SparkExitSavingsFunding(
    val feeSats: ULong,
    val canPay: Boolean,
)

data class SparkExitState(
    val record: SparkExitRecord? = null,
    val pending: SparkExitPending? = null,
    val tipHeight: UInt? = null,
    /** Confirmed or not, what reached the funding address. */
    val fundingSeenSats: ULong = 0uL,
    /** How many payments make up [fundingSeenSats] before the exit starts: each adds its own input fee. */
    val fundingSeenCount: Int = 0,
    /** What progress shows while the exit needs fee funding. */
    val fundingState: SparkExitFundingState? = null,
    val fundingAddress: String? = null,
    val lastError: String? = null,
    /** Spark's balance as last seen while the SDK drove the exit. */
    val sparkBalanceSats: ULong? = null,
    /** The quote waits for Spark to finish moving leaves, so value in flight is not left out of the exit. */
    val isSettling: Boolean = false,
) {
    val isExiting: Boolean get() = record?.isActive == true

    /** Emergency exit opens progress rather than a new exit. */
    val showsResult: Boolean get() = record?.showsResult == true

    /**
     * Spark's balance beyond the quote's leaves while the exit runs: value outside the exit, which stays on Spark. A
     * signal only, exact while the exit's leaves still count in the balance; published leaves leave it.
     */
    val outsideExitSats: ULong?
        get() {
            val active = record?.takeIf { it.isActive } ?: return null
            return SparkExitMath.outsideExitSats(active.leafTotalSats, sparkBalanceSats)
        }
}
