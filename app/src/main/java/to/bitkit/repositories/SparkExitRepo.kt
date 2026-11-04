package to.bitkit.repositories

import breez_sdk_spark.CpfpFundingKind
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.async.appScope
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

    private val chain: SparkExitChain?
        get() = EsploraSparkExitChain.defaultHosts(sparkService.networkName)
            .takeIf { it.isNotEmpty() }
            ?.let { EsploraSparkExitChain(it, httpClient) }

    // region lifecycle

    /** Loads the exit of [walletIndex] once, takes Spark off Lightning while it runs, and settles a finished one. */
    suspend fun load(walletIndex: Int = sparkRepo.walletIndex): Result<SparkExitRecord?> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (isLoaded && this@SparkExitRepo.walletIndex == walletIndex) return@runSuspendCatching _state.value.record
            this@SparkExitRepo.walletIndex = walletIndex
            val record = runSuspendCatching { store.load() }.getOrElse {
                // Kept aside for support, so a new exit never overwrites it; a new exit prepares from chain state.
                Logger.error("Failed to read the Spark exit record", it, context = TAG)
                runSuspendCatching { store.setAside(clock.nowMs()) }
                null
            }
            isLoaded = true
            _state.update { it.copy(record = record) }
            record?.let { settleIfFinished(it) }
            settleOrphanedTransfers(record)
            if (record?.isActive == true) work.schedulePeriodic()
            record
        }
    }

    /** Drives the exit every minute while Bitkit is in the foreground. */
    fun onAppForegrounded() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            load().onFailure { Logger.error("Failed to load the Spark exit", it, context = TAG) }
            while (isActive && _state.value.isExiting) {
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
     * SDK check. It never starts the Lightning node.
     */
    suspend fun runInBackground(): Result<SparkExitRecord?> = withContext(bgDispatcher) {
        runSuspendCatching {
            load().getOrThrow()
            if (!_state.value.isExiting) return@runSuspendCatching _state.value.record
            tick()
            if (!sparkService.isConnected) {
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
            if (_state.value.isExiting) work.schedulePeriodic()
        }
    }

    private suspend fun tickLocked(): SparkExitRecord? {
        val current = _state.value.record?.takeIf { it.isActive } ?: return _state.value.record
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
            makeSigner = ::signer,
            save = store::save,
            nowMillis = clock::nowMs,
        )
        val updated = driver.tick(current)
        _state.update { it.copy(record = updated, lastError = null) }
        // A rebuild needs the SDK; every other running step can go out from the stored recovery without it. Steps
        // refused again and again keep the exit from moving, however often it runs.
        val canMove = recovering != null || !updated.isRebuilding
        val isOnTrack = tip != null && canMove && updated.surfacedRejection == null
        afterTick(updated, current, isOnTrack)
        return updated
    }

    private suspend fun afterTick(record: SparkExitRecord, previous: SparkExitRecord, isOnTrack: Boolean) {
        record.transferId?.let { transferRepo.updateClaimableAtHeight(it, record.claimableAtHeight) }
        settleIfFinished(record)
        updateAlerts(record, previous, isOnTrack)
        if (!record.isActive) {
            Logger.info("Ended Spark exit with phase '${record.phase}'", context = TAG)
            work.cancelAll()
            sparkRepo.setExiting(false)
        }
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
        if (record.fundingShortfallSats == null) return
        val utxos = runSuspendCatching { chain.utxos(address) }.getOrNull() ?: return
        _state.update { it.copy(fundingSeenSats = utxos.fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }) }
    }

    /**
     * Pushes back the warning that the exit fell behind, to halfway through the operators' window of the next step to
     * mature (about 50 blocks after it, when they may publish their own version), or of the steps already waiting to
     * be published. Only a run that kept the exit on track pushes it; a stalled exit lets it fire. Needing more fee
     * funding is notified once, at once.
     */
    private fun updateAlerts(record: SparkExitRecord, previous: SparkExitRecord, isOnTrack: Boolean) {
        when (record.phase) {
            SparkExitRecord.Phase.Running -> {
                if (!isOnTrack) return
                val tip = _state.value.tipHeight
                val untilMaturity = SparkExitMath.untilNextMaturity(record.recovery, tip, Env.network)
                work.armDeadline(
                    SparkExitMath.deadline(untilMaturity, Env.network, SparkExitWork.PERIOD),
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

    /** What Android lets the exit do while Bitkit is closed, for the progress screen. */
    fun backgroundLimits(): SparkExitBackgroundLimits = work.backgroundLimits()

    // endregion

    // region quote and start

    /** Quotes an exit of every leaf to a fresh savings address at the SDK's fastest fee rate. */
    suspend fun loadQuote(): Result<SparkExitQuote> = withContext(bgDispatcher) {
        runSuspendCatching {
            val recovering = sparkService.exitRecovering() ?: throw SparkError.NotConnected()
            val feeRate = maxOf(1uL, sparkService.fastestFeeRate())
            val destination = _state.value.pending?.destination ?: lightningRepo.newAddress().getOrThrow()
            val prepared = recovering.prepare(
                PrepareRecoverFundsRequest(
                    feeRateSatPerVbyte = feeRate,
                    fundingKind = CpfpFundingKind.P2wpkh,
                    destination = destination,
                    selection = ExitLeafSelection.All,
                ),
            )
            val quote = SparkExitQuote(prepared)
            val fundingAddress = fundingKey().address
            _state.update {
                it.copy(pending = SparkExitPending(quote, destination, feeRate), fundingAddress = fundingAddress)
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
            val seen = chain.utxos(address).fold(0uL) { sum, utxo -> sum.safe() + utxo.value.safe() }
            _state.update { it.copy(fundingSeenSats = seen) }
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
            val key = fundingKey()
            val quote = pending.quote
            var record = SparkExitRecord(
                network = network,
                createdAt = clock.nowMs(),
                phase = SparkExitRecord.Phase.AwaitingFunding(quote.fundingSats),
                feeRateSatPerVbyte = pending.feeRate,
                destination = pending.destination,
                leafIds = quote.leafIds,
                funding = listOf(SparkExitRecord.Funding(index = 0u, address = key.address, pubkey = key.pubkey)),
                expectedSats = quote.incomingSats,
                skippedLeafCount = quote.prepared.skipped.size,
            )
            // Saved before the transfer exists, so a failed save never leaves a transfer counted with no exit behind.
            store.save(record)
            val transferId = transferRepo.createTransfer(
                TransferType.SPARK_EXIT,
                quote.incomingSats.toLong()
            ).getOrThrow()
            record = record.copy(transferId = transferId)
            store.save(record)
            _state.update { it.copy(record = record, pending = null, fundingAddress = key.address) }
            isLoaded = true
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

    /** Every funding UTXO goes to index 0: the SDK's single-key signer signs every funding input with one key. */
    private suspend fun fundingKey(): SparkExitFundingKey.Derived {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val path = SparkExitFundingKey.path(index = 0, network = Env.network)
        val address = coreService.onchain.deriveBitcoinAddress(mnemonic, path, Env.network, passphrase)
        val wif = coreService.onchain.derivePrivateKey(mnemonic, path, Env.network, passphrase)
        return SparkExitFundingKey.Derived(address.address, address.publicKey, SparkExitFundingKey.secretKey(wif))
    }

    private suspend fun signer(): CpfpSigner = singleKeyCpfpSigner(fundingKey().secretKey)

    // endregion
}

class SparkExitError(message: String) : AppError(message)

/** A quote the user is looking at, with what the exit is built from once it starts. */
data class SparkExitPending(
    val quote: SparkExitQuote,
    val destination: String,
    val feeRate: ULong,
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
    val fundingAddress: String? = null,
    val lastError: String? = null,
) {
    val isExiting: Boolean get() = record?.isActive == true
}
