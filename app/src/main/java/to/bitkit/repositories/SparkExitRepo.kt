package to.bitkit.repositories

import breez_sdk_spark.CpfpFundingKind
import breez_sdk_spark.CpfpFundingStrategy
import breez_sdk_spark.CpfpSigner
import breez_sdk_spark.ExitLeafSelection
import breez_sdk_spark.PrepareRecoverFundsRequest
import breez_sdk_spark.RecoveryTxKind
import breez_sdk_spark.singleKeyCpfpSigner
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.FeeRates
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import to.bitkit.models.FeeRate
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

    /** The exit a start is still settling: no tick drives it until its optimization wait, requote and reconnect end. */
    @Volatile
    private var startingCreatedAt: Long? = null

    /** One start or fee payment at a time: a second tap never pays or creates twice. */
    private val paymentMutex = Mutex()
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
                // Closed first: a tick or background run still going writes nothing more of the wiped exit.
                isWriteClosed = true
                foregroundJob?.cancel()
                foregroundJob = null
                tickMutex.withLock {
                    runCatching { store.delete() }
                        .onFailure { e ->
                            Logger.warn(
                                "Failed to delete the wiped Spark exit record",
                                e,
                                context = TAG
                            )
                        }
                    runCatching { lostLeafStore.delete() }
                    work.cancelAll()
                    _state.update { SparkExitState() }
                    isLoaded = false
                    // A later exit, of a new or restored wallet, writes again.
                    isWriteClosed = false
                }
            }
        }
    }

    private val store get() = SparkExitStore(File(Env.sparkExitRecordPath(walletIndex)))

    /** Closed while a wipe of the wallet runs: a save then throws and writes nothing. */
    @Volatile
    private var isWriteClosed = false

    private fun saveRecord(record: SparkExitRecord) {
        if (isWriteClosed) throw SparkExitError("The Spark exit's wallet was wiped")
        store.save(record)
    }

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

    private fun salvage(): SparkExitRecord? {
        val store = store
        val text = runCatching { store.loadText() }.getOrNull()
        runCatching { store.setAside(clock.nowMs()) }
            .onFailure { Logger.warn("Failed to set the unreadable Spark exit record aside", it, context = TAG) }
        val salvaged = text?.let { SparkExitJson.salvage(it, clock.nowMs()) }
        if (salvaged == null) {
            // Nothing to rebuild from: only the copy set aside stays.
            runCatching { store.delete() }
            return null
        }
        runCatching { saveRecord(salvaged) }
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
            // Spark has not started in a cold background run, so its wallet index is not known yet.
            load(work.activeWalletIndex()).getOrThrow()
            if (_state.value.record?.needsDriving != true) return@runSuspendCatching _state.value.record
            tick()
            if (_state.value.isExiting && !sparkService.isConnected) {
                // The connect keeps going past the timeout, so the next run finds Spark connecting or connected.
                val connect = scope.async { sparkRepo.start(walletIndex) }
                withTimeoutOrNull(BACKGROUND_CONNECT_TIMEOUT) { connect.await() }
                if (sparkService.isConnected) tick()
            }
            _state.value.record
        }
    }

    // endregion

    // region tick

    /** Moves the exit one step forward; a tick already running makes this one a no-op. */
    suspend fun tick(): Result<SparkExitRecord?> = withContext(bgDispatcher) {
        val isStarting = _state.value.record?.let { it.createdAt == startingCreatedAt } == true
        if (isStarting || !tickMutex.tryLock()) return@withContext Result.success(_state.value.record)
        try {
            runSuspendCatching { tickLocked() }
                .onFailure {
                    Logger.error("Failed to drive the Spark exit", it, context = TAG)
                    _state.update { state -> state.copy(lastError = it.message) }
                    // Steps the run saved before it failed are on disk, newer than the record it started from.
                    runSuspendCatching { store.load() }.getOrNull()?.let { saved ->
                        _state.update { state -> state.copy(record = saved) }
                    }
                    // A run that failed did not keep the exit on track: the alerts still run and the alarm may fire.
                    _state.value.record?.takeIf { record -> record.needsDriving }?.let { record ->
                        runSuspendCatching { afterTick(record, record, isOnTrack = false, reportedLostLeafIds()) }
                            .onFailure { e -> Logger.warn("Failed to update Spark exit alerts", e, context = TAG) }
                    }
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
        refreshNetworkFees(chain)
        refreshFundingSeen(chain, current)
        if (current.isActive) noteSavingsSend(current)
        val store = store
        val recovering = sparkService.exitRecovering()
        val driver = driver(chain, recovering, current, tip)
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
            updated.unpublishedStep == null &&
            !isStuckWithoutSdk && updated.underpricedStep(tip) == null
        afterTick(updated, current, isOnTrack, reportedLost)
        return updated
    }

    private fun driver(
        chain: SparkExitChain,
        recovering: SparkExitRecovering?,
        record: SparkExitRecord,
        tip: UInt?,
    ) = SparkExitDriver(
        chain = chain,
        recovering = recovering,
        // The record's own funding key, also for exits started before each exit had an index of its own.
        makeSigner = { signer(record.fundingIndex) },
        save = ::saveRecord,
        nowMillis = clock::nowMs,
        tip = tip,
        trustedFeeRate = { lightningRepo.getFeeRates().getOrNull()?.fast?.toULong() },
    )

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

    private suspend fun settleIfFinished(record: SparkExitRecord) {
        // The sweep's activity is marked as a transfer whenever the wallet has it, also after the transfer settled:
        // a cold background run can end the exit before the wallet synced the sweep.
        val sweepTxid = record.recovery?.transactions?.firstOrNull { it.kind == RecoveryTxKind.SWEEP }?.txid
            ?: record.landedSweepTxid
        val sweepActivity = sweepTxid?.let { activityRepo.getOnchainActivityByTxId(it) }
        if (sweepActivity != null && !sweepActivity.isTransfer) {
            activityRepo.updateActivity(sweepActivity.id, Activity.Onchain(sweepActivity.copy(isTransfer = true)))
        }
        val transferId = record.transferId ?: return
        if (transferRepo.isSettled(transferId).getOrDefault(true)) return
        // Leaves the operators could not co-sign are still on their way to savings, before and while they are retried:
        // the transfer keeps counting them, less what already confirmed.
        if (!record.isActive || (sweepActivity != null && !record.hasLeavesOutstanding)) {
            transferRepo.markSettled(transferId)
        }
    }

    private suspend fun settleOrphanedTransfers(record: SparkExitRecord?) {
        val owned = record?.takeIf { it.isActive }?.transferId
        transferRepo.activeTransfers.first()
            .filter { it.type == TransferType.SPARK_EXIT && it.id != owned }
            .forEach {
                Logger.warn("Settling Spark exit transfer '${it.id}' with no running exit", context = TAG)
                transferRepo.markSettled(it.id)
            }
    }

    private suspend fun refreshNetworkFees(chain: SparkExitChain) {
        val fees = runSuspendCatching {
            if (sparkService.isConnected) {
                SparkExitNetworkFees(sparkService.fastestFeeRate(), sparkService.halfHourFeeRate())
            } else {
                chain.networkFees()
            }
        }.onFailure { Logger.warn("Failed to read the network fee rates for the Spark exit", it, context = TAG) }
            .getOrNull()
        _state.update { it.copy(networkFees = fees ?: it.networkFees) }
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

    private fun updateAlerts(
        record: SparkExitRecord,
        previous: SparkExitRecord,
        isOnTrack: Boolean,
        reportedLost: Set<String>,
    ) {
        // A started exit nothing reached yet is reminded about once, 4 hours in; funds seen or paid, or another phase,
        // call the reminder off.
        val remindIn = SparkExitAlerts.fundingReminderIn(
            record,
            _state.value.fundingSeenSats,
            clock.nowMs(),
            Env.network
        )
        if (remindIn != null) {
            work.scheduleFundingReminder(record.createdAt, remindIn)
        } else {
            work.cancelFundingReminder(record.createdAt)
        }
        SparkExitAlerts.losses(record, previous, reportedLost).forEach {
            work.notify(it, amountSats = record.lostSats.takeIf { _ -> it == SparkExitAlert.Lost })
        }
        // The exit stopped rebuilding and says it is not moving: no later run fixes that, so the user hears now.
        if (SparkExitAlerts.isNewlyStalled(record, previous)) {
            val untilMaturity = SparkExitMath.untilNextMaturity(record.recovery, _state.value.tipHeight, Env.network)
            val deadline = SparkExitMath.deadline(untilMaturity, Env.network)
            work.notify(
                SparkExitAlert.NotMoving,
                deadlineAtMillis = clock.nowMs() + deadline.deadlineIn.inWholeMilliseconds
            )
        }
        if (SparkExitAlerts.isFundingAskBack(record, previous)) work.notify(SparkExitAlert.Funding)
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
            // While a higher fee confirms, the generation out there is still driven, and its deadline stands.
            is SparkExitRecord.Phase.NeedsFunding if record.isDrivingWhileFunding -> {
                if (previous.phase !is SparkExitRecord.Phase.NeedsFunding && !record.isAwaitingOwnPayment) {
                    work.notify(SparkExitAlert.Funding)
                }
                if (!isOnTrack) return
                val untilMaturity = SparkExitMath.untilNextMaturity(
                    record.recovery,
                    _state.value.tipHeight,
                    Env.network
                )
                work.armDeadline(SparkExitMath.deadline(untilMaturity, Env.network), clock.nowMs())
            }
            is SparkExitRecord.Phase.NeedsFunding -> {
                work.cancelDeadline()
                // A payment from savings, such as a higher fee, is on its way: nothing to ask the user for.
                if (previous.phase !is SparkExitRecord.Phase.NeedsFunding && !record.isAwaitingOwnPayment) {
                    work.notify(SparkExitAlert.Funding)
                }
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
            val quote = recovering.prepareAll(quoteRequest(feeRate, destination), lost)
                .copy(sparkBalanceSats = sparkRepo.state.value.balanceSats)
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

    /**
     * The higher rates the running exit can move to, each with what its funding address must still receive and what
     * that costs from savings, found by building the exit at each rate without broadcasting. Needs Spark connected.
     */
    suspend fun feeBumpOptions(): Result<List<SparkExitFeeBumpOption>> = withContext(bgDispatcher) {
        runSuspendCatching {
            val record = requireNotNull(_state.value.record?.takeIf { it.isActive }) { "No running Spark exit" }
            val chain = requireNotNull(chain) { "No chain host for this network" }
            val recovering = sparkService.exitRecovering() ?: throw SparkError.NotConnected()
            refreshNetworkFees(chain)
            val fees = requireNotNull(_state.value.networkFees) { "No network fee rates" }
            val driver = driver(chain, recovering, record, _state.value.tipHeight)
            val address = requireNotNull(record.funding.firstOrNull()?.address) { "No Spark exit funding address" }
            // The rates every option's savings payment is priced at, and later sent at.
            val feeRates = lightningRepo.getFeeRates().getOrThrow()
            val priced = SparkExitFeeBumpOption.rates(record.feeRateSatPerVbyte, fees).map { (speed, rate) ->
                runSuspendCatching {
                    feeBumpOption(speed, rate, driver.feeBumpShortfall(record, rate), address, feeRates)
                }
            }
            // A rate at which a leaf can't pay its own exit is not offered. With no rate left only for that reason,
            // that is the error the screen names.
            priced.mapNotNull { it.getOrNull() }.ifEmpty {
                val errors = priced.mapNotNull { it.exceptionOrNull() }
                throw errors.firstOrNull { it !is SparkExitFeeBumpLeavesBehind } ?: errors.firstOrNull()
                    ?: SparkExitError("No rate to offer")
            }
        }.onFailure {
            Logger.error("Failed to price a higher Spark exit fee", it, context = TAG)
        }
    }

    /** What savings can spend now, for the fee bump's rows. */
    suspend fun spendableSavingsSats(): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching { lightningRepo.getBalancesAsync().getOrThrow().spendableOnchainBalanceSats }
    }

    /**
     * Moves the running exit to [option]'s rate: pays what its funding address still needs from savings, like the
     * first funding, then rebuilds what has not confirmed at that rate, which replaces it in the mempool.
     */
    suspend fun payHigherFee(option: SparkExitFeeBumpOption): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (!paymentMutex.tryLock()) throw SparkExitError("A Spark exit payment is already being made")
            try {
                payHigherFeeLocked(option)
            } finally {
                paymentMutex.unlock()
            }
        }.onFailure {
            Logger.error("Failed to raise the Spark exit fee", it, context = TAG)
        }
    }

    /**
     * The option at [rate]: [extraSats] more at the funding address,
     * and what savings pays to send it at [feeRates].
     */
    internal suspend fun feeBumpOption(
        speed: FeeRate,
        rate: ULong,
        extraSats: ULong,
        address: String,
        feeRates: FeeRates,
    ): SparkExitFeeBumpOption {
        val paymentFee = if (extraSats == 0uL) {
            0uL
        } else {
            // A placeholder fee would show and approve a total the payment does not keep to: any error fails it.
            lightningRepo.calculateTotalFee(
                amountSats = extraSats,
                address = address,
                speed = TransactionSpeed.Fast,
                feeRates = feeRates,
                allowFallback = false,
            ).getOrThrow()
        }
        return SparkExitFeeBumpOption(speed, rate, extraSats, extraSats.safe() + paymentFee.safe(), feeRates)
    }

    private suspend fun payHigherFeeLocked(option: SparkExitFeeBumpOption) {
        val record = requireNotNull(_state.value.record?.takeIf { it.isActive }) { "No running Spark exit" }
        val address = requireNotNull(record.funding.firstOrNull()?.address) { "No Spark exit funding address" }
        val txid = if (option.extraSats > 0uL) {
            sendFunding(address, option.extraSats, option.feeRates, option.totalSats.safe() - option.extraSats.safe())
        } else {
            null
        }
        tickMutex.withLock {
            val current = _state.value.record ?: return@withLock
            if (current.isRebuilding && current.isActive) {
                // It started rebuilding meanwhile: the rebuild adopts the payment, and an exit that ends before it
                // does keeps watching it until its outputs join the leftover.
                Logger.warn("Not applying a higher fee to a Spark exit that started rebuilding", context = TAG)
                txid?.let { watchPayment(current, it) }
                return@withLock
            }
            if (!current.isActive) {
                // The exit ended while savings paid: the payment is watched until its outputs reach the address, then
                // joins the leftover the sweep sends back to savings.
                val paid = txid ?: return@withLock
                Logger.warn("Watching higher fee '$paid' paid after the Spark exit ended", context = TAG)
                watchPayment(current, paid)
                return@withLock
            }
            val bumped = current.applyFeeBump(option.rateSatPerVbyte, option.extraSats, txid)
            saveRecord(bumped)
            _state.update {
                it.copy(record = bumped, fundingState = SparkExitMath.fundingState(bumped, utxos = null))
            }
        }
        if (_state.value.record?.needsDriving == true) work.schedulePeriodic()
        Logger.info(
            "Raised the Spark exit fee to '${option.rateSatPerVbyte}' sat/vB, paying '${option.extraSats}' sats",
            context = TAG,
        )
        scope.launch { tick() }
    }

    private fun watchPayment(record: SparkExitRecord, txid: String) {
        val watched = record.copy(
            fundingPaymentTxid = txid,
            fundingPaymentSeenAtHeight = null,
            fundingPaymentMissingSince = null,
        )
        saveRecord(watched)
        _state.update { it.copy(record = watched) }
    }

    // Each fee-bumped transaction gets a UTXO of its own, so one lost fee race strands one step's funding, not the
    // chain after it.
    private fun quoteRequest(feeRate: ULong, destination: String) = PrepareRecoverFundsRequest(
        feeRateSatPerVbyte = feeRate,
        fundingKind = CpfpFundingKind.P2wpkh,
        fundingStrategy = CpfpFundingStrategy.PER_NODE,
        destination = destination,
        selection = ExitLeafSelection.All,
    )

    /**
     * The fee Savings pays to send the fee funding at the fast rate, at least [floorSats], the fee a start was
     * refused for. Fails when the rates cannot be read; the rates it priced are the ones the payment at the start
     * sends at.
     */
    suspend fun savingsFundingFee(floorSats: ULong? = null): Result<SparkExitSavingsFunding> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                val pending = requireNotNull(_state.value.pending) { "No Spark exit quote" }
                val address = requireNotNull(_state.value.fundingAddress) { "No Spark exit funding address" }
                val fundingSats = pending.quote.fundingSats
                val feeRates = lightningRepo.getFeeRates().getOrThrow()
                val estimateSats = lightningRepo.calculateTotalFee(
                    amountSats = fundingSats,
                    address = address,
                    speed = TransactionSpeed.Fast,
                    feeRates = feeRates,
                    allowFallback = false,
                ).getOrThrow()
                // Coin selection can need more fee than the estimate: the start approves what it was refused for.
                val feeSats = maxOf(estimateSats, floorSats ?: 0uL)
                _state.update {
                    it.copy(pending = it.pending?.copy(savingsFeeRates = feeRates, savingsFeeSats = feeSats))
                }
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
            if (!paymentMutex.tryLock()) throw SparkExitError("A Spark exit is already starting")
            try {
                startLocked(fundFromSavings)
            } finally {
                paymentMutex.unlock()
            }
        }.onFailure {
            Logger.error("Failed to start the Spark exit", it, context = TAG)
        }
    }

    private suspend fun startLocked(fundFromSavings: Boolean) {
        if (_state.value.isExiting) throw SparkExitError("A Spark exit is already running")
        val pending = requireNotNull(_state.value.pending) { "No Spark exit quote" }
        val network = requireNotNull(sparkService.networkName) { "Spark is not available on this network" }
        val key = fundingKey(pending.fundingIndex)
        var quote = pending.quote
        // Spark stops paying and receiving in the same step the transfer counts its balance, before any wait.
        sparkRepo.holdForExit()
        val transferId = transferRepo.createTransfer(TransferType.SPARK_EXIT, quote.incomingSats.toLong())
            .onFailure { sparkRepo.releaseExitHold() }
            .getOrThrow()
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
            leafTotalSats = quote.leafTotalSats,
            transferId = transferId,
        )
        // Saved with its transfer in one write, so no running exit is ever stored without the transfer counting it. A
        // failed save settles the transfer again; one a killed start left behind is settled as an orphan on load.
        runCatching { saveRecord(record) }.onFailure {
            transferRepo.markSettled(transferId)
            sparkRepo.releaseExitHold()
        }.getOrThrow()
        cacheStore.update {
            val next = it.sparkExitNextFundingIndexes + (fundingIndexKey to pending.fundingIndex.toInt() + 1)
            it.copy(sparkExitNextFundingIndexes = next)
        }
        val createdAt = record.createdAt
        // Published for the screens and a cancel, but driven only once the start settled the leaves it exits.
        startingCreatedAt = createdAt
        _state.update { it.copy(record = record, pending = null, fundingAddress = key.address) }
        isLoaded = true
        // Reconnecting for the exit disconnects Spark, which would cut a running leaf optimization short.
        val isCurrent = try {
            sparkRepo.awaitOptimizationIdle()
            // A cancel during the wait ended this exit: nothing more of the start runs, and nothing is paid.
            tickMutex.withLock {
                if (!isStarting(createdAt)) return@withLock false
                // An optimization since the quote swapped leaves: the exit is quoted again over the leaves Spark holds.
                requote(pending)?.let { fresh ->
                    // A failed write goes on with the confirmed quote, as a failed requote does.
                    val requoted = writeRequote(requireNotNull(_state.value.record), fresh) ?: return@let
                    quote = fresh
                    record = requoted
                    _state.update { it.copy(record = record) }
                }
                // Under the lock, so a cancel can't release Spark between the check and taking it off Lightning.
                sparkRepo.setExiting(true, reconnect = true)
                true
            }
        } finally {
            startingCreatedAt = null
        }
        if (!isCurrent) throw SparkExitStartCancelled()
        work.schedulePeriodic()
        Logger.info("Started Spark exit of '${quote.exitSats}' sats over '${quote.leafIds.size}' leaves", context = TAG)
        // Driven before paying, so a failed payment from savings still leaves the exit watching its address.
        scope.launch { tick() }
        onAppForegrounded()
        // Savings pays at most what the user confirmed: Progress asks for the rest of a higher fresh amount.
        val savingsSats = minOf(quote.fundingSats, pending.quote.fundingSats)
        if (fundFromSavings && savingsSats > 0uL) {
            runSuspendCatching {
                payFunding(createdAt, key.address, savingsSats, pending.savingsFeeRates, pending.savingsFeeSats)
            }
                // A refused payment shows on progress at once, with the offer to pay again.
                .onFailure { _state.value.record?.let { current -> noteSavingsSend(current) } }
                .getOrThrow()
        }
    }

    /**
     * The started exit moved onto [fresh]: a transfer for its amount, saved, then the confirmed quote's transfer
     * settled. Null when that write fails, with a transfer made for it settled again.
     */
    private suspend fun writeRequote(current: SparkExitRecord, fresh: SparkExitQuote): SparkExitRecord? {
        val freshTransfer = transferRepo.createTransfer(TransferType.SPARK_EXIT, fresh.incomingSats.toLong())
            .onFailure { Logger.warn("Failed to create the requoted Spark exit transfer", it, context = TAG) }
            .getOrNull()
            ?: return null
        val requoted = current.copy(
            phase = SparkExitRecord.Phase.AwaitingFunding(fresh.fundingSats),
            leafIds = fresh.leafIds,
            expectedSats = fresh.incomingSats,
            skippedLeafCount = fresh.prepared.skipped.size,
            leafTotalSats = fresh.leafTotalSats,
            transferId = freshTransfer,
        )
        runCatching { saveRecord(requoted) }.onFailure {
            Logger.warn("Failed to save the requoted Spark exit", it, context = TAG)
            transferRepo.markSettled(freshTransfer)
            return null
        }
        current.transferId?.let { transferRepo.markSettled(it) }
        return requoted
    }

    /** The exit created at [createdAt] is still the one on record and running: no cancel or wipe ended it. */
    private fun isStarting(createdAt: Long): Boolean =
        _state.value.record?.let { it.createdAt == createdAt && it.isActive } == true

    private suspend fun requote(pending: SparkExitPending): SparkExitQuote? {
        val recovering = sparkService.exitRecovering() ?: return null
        val lost = reportedLostLeafIds() + _state.value.record?.lostLeaves?.map { it.leafId }.orEmpty()
        val request = quoteRequest(pending.feeRate, pending.destination)
        val fresh = runSuspendCatching { recovering.prepareAll(request, lost) }
            .onFailure { Logger.warn("Failed to quote the Spark exit again at its start", it, context = TAG) }
            .getOrNull()
            ?: return null
        if (fresh.leafIds.toSet() == pending.quote.leafIds.toSet() || fresh.prepared.leaves.isEmpty()) return null
        Logger.warn("Quoting the Spark exit again: its leaves changed since the quote", context = TAG)
        return fresh.copy(sparkBalanceSats = sparkRepo.state.value.balanceSats)
    }

    private suspend fun payFunding(
        createdAt: Long,
        address: String,
        sats: ULong,
        feeRates: FeeRates?,
        approvedFeeSats: ULong?,
    ) {
        // Under the tick lock from the check to the saved txid: a cancel either ends the exit before anything is paid
        // or finds the payment and refuses. After the tick in flight, so its save cannot drop the txid.
        tickMutex.withLock {
            if (!isStarting(createdAt)) {
                Logger.info("Spark exit ended before savings paid its funding; nothing is paid", context = TAG)
                return@withLock
            }
            val txid = sendFunding(address, sats, feeRates, approvedFeeSats)
            val record = _state.value.record ?: return@withLock
            val paid = record.copy(fundingPaymentTxid = txid)
            saveRecord(paid)
            _state.update {
                it.copy(record = paid, fundingState = SparkExitMath.fundingState(paid, utxos = null))
            }
        }
    }

    private suspend fun savingsSend(record: SparkExitRecord): OnchainSendAttempt? {
        val address = record.funding.firstOrNull()?.address ?: return null
        val attempt = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
        return attempt?.takeIf { it.address == address }
    }

    private suspend fun noteSavingsSend(record: SparkExitRecord) {
        val attempt = savingsSend(record)
        _state.update {
            it.copy(
                isSavingsSendUnresolved = attempt?.isUnresolved == true,
                isSavingsPaymentRefused = SparkExitMath.isSavingsPaymentRefused(record, attempt),
            )
        }
    }

    /**
     * Pays the exit's fee funding from savings again after a node refused the payment: the same attempt, re-signed at
     * the rate the user approved over the inputs it already spends. Every version spends those inputs, so at most one
     * ever confirms, and it never needs the refused one to be known gone from the mempool first.
     */
    suspend fun retrySavingsPayment(): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (!paymentMutex.tryLock()) throw SparkExitError("A Spark exit payment is already running")
            try {
                // Under the tick lock from the check to the saved txid, as the start's payment: a cancel either ends
                // the exit before savings pays again or finds the payment and refuses.
                tickMutex.withLock {
                    val record = requireNotNull(_state.value.record?.takeIf { it.isActive }) { "No running Spark exit" }
                    val refused = savingsSend(record)?.takeIf { SparkExitMath.isSavingsPaymentRefused(record, it) }
                        ?: throw SparkExitError("No refused savings payment to pay again")
                    val outcome = lightningRepo.retryOriginalOnchainSend(
                        attemptId = refused.attemptId,
                        walletId = refused.walletId,
                        feeRateSatsPerVByte = refused.feeRateSatsPerVByte,
                    ) { check(it.attemptId == refused.attemptId && it.address == refused.address) }
                        .getOrThrow()
                    val txid = fundingTxid(outcome, refused.amountSats)
                    val current = _state.value.record ?: return@withLock
                    val paid = current.copy(fundingPaymentTxid = txid)
                    saveRecord(paid)
                    _state.update {
                        it.copy(
                            record = paid,
                            fundingState = SparkExitMath.fundingState(paid, utxos = null),
                            isSavingsPaymentRefused = false,
                        )
                    }
                }
            } finally {
                paymentMutex.unlock()
            }
        }.onFailure {
            Logger.error("Failed to pay the Spark exit funding from savings again", it, context = TAG)
        }
    }

    /** Savings never pays more fee than [approvedFeeSats], the one confirmed: a higher one asks again. */
    private suspend fun sendFunding(
        address: String,
        sats: ULong,
        feeRates: FeeRates?,
        approvedFeeSats: ULong?,
    ): String {
        val outcome = lightningRepo.sendOnChain(
            address = address,
            sats = sats,
            speed = TransactionSpeed.Fast,
            feeRates = feeRates,
            approvedMiningFeeSats = approvedFeeSats,
        ).getOrThrow()
        return fundingTxid(outcome, sats)
    }

    private suspend fun fundingTxid(outcome: OnchainSendOutcome, sats: ULong): String {
        if (outcome is OnchainSendOutcome.Rejected) {
            throw SparkExitError("Spark exit funding was not accepted: '$outcome'")
        }
        // A send no host confirmed may still go out: it counts as paid, so the exit keeps watching its address and
        // cannot be cancelled. The own-payment wait forgets it if no host ever sees it.
        if (outcome is OnchainSendOutcome.Unknown) {
            Logger.warn("Spark exit funding '${outcome.txid}' went out with an unknown result", context = TAG)
            return outcome.txid
        }
        runSuspendCatching { lightningRepo.completeAcceptedOrdinaryFollowup(outcome.txid) }
            .onFailure { Logger.warn("Failed to finish the Spark exit funding locally", it, context = TAG) }
        Logger.info("Funded the Spark exit with '$sats' sats in '${outcome.txid}'", context = TAG)
        return outcome.txid
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
                record.fundingPaymentTxid == null &&
                !exit.isSavingsSendUnresolved &&
                exit.fundingSeenSats == 0uL
        }

    /** The user opened the result of an exit that failed or lost leaves: Emergency exit may start a new one now. */
    suspend fun markResultSeen(): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            tickMutex.withLock {
                val record = _state.value.record?.takeIf { it.hasUnseenResult }
                    ?: return@withLock
                val seen = record.copy(isResultSeen = true)
                saveRecord(seen)
                _state.update { it.copy(record = seen) }
            }
        }
    }

    /**
     * Stops an exit that nothing was paid into. The funding address is read again first: anything that reached it
     * keeps the record, as an ended exit whose leftover sweep sends it to savings; an address that cannot be read
     * refuses the cancel. Under the tick lock, so a tick in flight cannot save the record back.
     */
    suspend fun cancel(): Result<SparkExitCancel> = withContext(bgDispatcher) {
        runSuspendCatching {
            tickMutex.withLock {
                val record = _state.value.record ?: throw SparkExitError("No Spark exit to cancel")
                val address = requireNotNull(record.funding.firstOrNull()?.address) { "No Spark exit funding address" }
                // A savings send to the address still unresolved may be retried later: the exit keeps watching it.
                val attempt = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }
                    .getOrElse { throw SparkExitCancelUnreadable() }
                val isSending = attempt?.address == address && attempt.isUnresolved
                _state.update { it.copy(isSavingsSendUnresolved = isSending) }
                if (!canCancel) throw SparkExitError("The Spark exit can no longer be cancelled")
                val chain = requireNotNull(chain) { "No chain host for this network" }
                val utxos = runSuspendCatching { chain.utxos(address) }.getOrElse { throw SparkExitCancelUnreadable() }
                record.transferId?.let { transferRepo.markSettled(it) }
                val outcome = if (utxos.isEmpty()) {
                    store.delete()
                    _state.update { it.copy(record = null) }
                    work.cancelAll()
                    SparkExitCancel.Cancelled
                } else {
                    val leftover = utxos.map { SparkExitRecord.Leftover(it.txid, it.vout, it.value) }
                    val ended = record.copy(
                        phase = SparkExitRecord.Phase.Failed(CANCELLED_REASON),
                        leftover = leftover,
                        isResultSeen = true,
                        isCancelled = true,
                    )
                    saveRecord(ended)
                    _state.update { it.copy(record = ended) }
                    work.schedulePeriodic()
                    SparkExitCancel.Sweeping
                }
                // Nothing was paid from Spark: the balance it showed before the exit shows again.
                sparkRepo.releaseExitHold()
                sparkRepo.setExiting(false, reconnect = true)
                Logger.info("Cancelled the Spark exit with outcome '$outcome'", context = TAG)
                outcome
            }
        }
    }

    // endregion

    // region keys

    private val fundingIndexKey get() = "${Env.network.name.lowercase()}/wallet$walletIndex"

    private suspend fun nextFundingIndex(): UInt = SparkExitMath.nextFundingIndex(
        kept = cacheStore.data.first().sparkExitNextFundingIndexes[fundingIndexKey],
        stored = runSuspendCatching { store.load() }.getOrNull(),
    )

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

/** The funding address could not be read, so a cancel could not tell whether money reached it. */
class SparkExitCancelUnreadable : AppError("Could not read the Spark exit funding address")

/** The exit was cancelled while it started, before anything was paid: nothing more of the start ran. */
class SparkExitStartCancelled : AppError("The Spark exit was cancelled while it started")

/** A higher fee rate would leave [sats] of the exit's leaves on Spark, so it is not offered. */
class SparkExitFeeBumpLeavesBehind(val sats: ULong) : AppError("A higher fee would leave part of the exit on Spark")

/** The record's failed reason for an exit the user cancelled after funding reached its address. */
const val CANCELLED_REASON = "Cancelled"

/** What cancelling did: the exit is gone, or funding had reached its address and is being sent to savings. */
enum class SparkExitCancel { Cancelled, Sweeping }

/** A quote the user is looking at, with what the exit is built from once it starts. */
data class SparkExitPending(
    val quote: SparkExitQuote,
    val destination: String,
    val feeRate: ULong,
    /** The funding key index this exit takes. */
    val fundingIndex: UInt,
    /** The rates the fee of paying the funding from savings was priced at, which that payment sends at. */
    val savingsFeeRates: FeeRates? = null,
    /** The fee of paying the funding from savings the user confirmed, which that payment never exceeds. */
    val savingsFeeSats: ULong? = null,
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
    /** A savings send to the funding address is unresolved and may be retried: cancel stays off. */
    val isSavingsSendUnresolved: Boolean = false,
    /** A node refused the savings payment of the exit's fee funding: progress says so and offers to pay again. */
    val isSavingsPaymentRefused: Boolean = false,
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
    /** What the network asks now, for the fee bump and its hint. */
    val networkFees: SparkExitNetworkFees? = null,
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

    /**
     * What the exit still brings to savings: what it expects less what reached savings already, and less the first
     * fee funding nobody paid yet, which the expected amount counts as returned. Null without an exit.
     */
    val inTransferSats: ULong?
        get() {
            val exit = record ?: return null
            // Reached only once its sweep or cooperative step confirmed: the plan lists the sweep from the first build.
            // A leaf an older state claimed never reaches savings, so it leaves the transfer too.
            val confirmed = exit.recovery?.let { SparkExitMath.confirmedReceived(it, exit.destination) }.orEmpty()
            val received = (exit.earlierReceivedSats + confirmed).values
                .fold(0uL) { sum, sats -> sum.safe() + sats.safe() }
            val unpaid = (exit.phase as? SparkExitRecord.Phase.AwaitingFunding)
                ?.takeIf { exit.fundingPaymentTxid == null }
                ?.let { it.requiredSats.safe() - maxOf(exit.fundedSats, fundingSeenSats).safe() }
                ?: 0uL
            return ((exit.expectedSats.safe() - received.safe()).safe() - unpaid.safe()).safe() - exit.lostSats.safe()
        }
}
