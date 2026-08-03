package to.bitkit.repositories

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.BarkService
import to.bitkit.utils.AppError
import to.bitkit.utils.BarkError
import to.bitkit.utils.Logger
import uniffi.bark.ArkInfo
import uniffi.bark.Movement
import uniffi.bark.Vtxo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Business layer for the bark (Ark) spending backend, mirroring [LightningRepo]. Wraps every
 * [BarkService] call so `uniffi.bark` types never reach UI state.
 */
@Suppress("TooManyFunctions")
@Singleton
class BarkRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val barkService: BarkService,
) {
    companion object {
        private const val TAG = "BarkRepo"
    }

    private val lifecycleMutex = Mutex()

    private val _barkState = MutableStateFlow(BarkState())
    val barkState: StateFlow<BarkState> = _barkState.asStateFlow()

    // region lifecycle

    suspend fun start(walletIndex: Int = 0): Result<Unit> = withContext(bgDispatcher) {
        if (!Env.isArkSupported) {
            return@withContext Result.failure(BarkError.NotSupported(Env.network.name))
        }
        lifecycleMutex.withLock {
            if (_barkState.value.lifecycleState.isRunning() && barkService.isRunning) {
                Logger.debug("Ark wallet already running, skipping start", context = TAG)
                return@withContext Result.success(Unit)
            }
            _barkState.update { it.copy(lifecycleState = NodeLifecycleState.Starting) }

            runSuspendCatching {
                barkService.start(walletIndex)
            }.onSuccess {
                _barkState.update { it.copy(lifecycleState = NodeLifecycleState.Running) }
            }.onFailure { e ->
                Logger.error("Failed to start Ark wallet", e, context = TAG)
                _barkState.update { it.copy(lifecycleState = NodeLifecycleState.ErrorStarting(e)) }
            }.map { }
        }
    }.also { if (it.isSuccess) syncState() }

    suspend fun stop(): Result<Unit> = withContext(bgDispatcher) {
        lifecycleMutex.withLock {
            _barkState.update { it.copy(lifecycleState = NodeLifecycleState.Stopping) }
            runSuspendCatching { barkService.stop() }
                .onFailure { Logger.error("Failed to stop Ark wallet", it, context = TAG) }
            _barkState.update { BarkState() }
            Result.success(Unit)
        }
    }

    // endregion

    // region sync

    suspend fun sync(): Result<Unit> = executeWhenRunning("sync") {
        runSuspendCatching {
            _barkState.update { it.copy(isSyncing = true) }
            barkService.sync()
            barkService.syncPendingBoards()
            barkService.tryClaimAllLightningReceives()
        }.onFailure { e ->
            if (e !is CancellationException) _barkState.update { it.copy(lastSyncError = e) }
        }.also {
            _barkState.update { it.copy(isSyncing = false) }
        }.map { }
    }.also { syncState() }

    /**
     * Delegated maintenance is the mobile refresh path: designated co-signers refresh VTXOs on the
     * wallet's behalf, so the app does not have to be online during a round window. Without it,
     * VTXOs eventually expire and the server can sweep them.
     */
    suspend fun runMaintenance(): Result<Unit> = executeWhenRunning("runMaintenance") {
        runSuspendCatching { barkService.maintenanceDelegated() }
    }.also { syncState() }

    /** Refreshes [BarkState] from the wallet without triggering a network sync. */
    suspend fun syncState() {
        if (!barkService.isRunning) return
        runSuspendCatching {
            val balance = barkService.balance()
            val vtxos = barkService.spendableVtxos().map { it.toBarkVtxo() }
            val arkInfo = barkService.arkInfo()?.toBarkArkInfo()
            val nextRefreshHeight = barkService.nextRequiredRefreshBlockheight()
            _barkState.update { state ->
                state.copy(
                    spendableSats = balance.spendableSats,
                    pendingInRoundSats = balance.pendingInRoundSats,
                    pendingBoardSats = balance.pendingBoardSats,
                    pendingLightningSendSats = balance.pendingLightningSendSats,
                    claimableLightningReceiveSats = balance.claimableLightningReceiveSats,
                    pendingExitSats = balance.pendingExitSats,
                    vtxos = vtxos.toImmutableList(),
                    nextRequiredRefreshHeight = nextRefreshHeight,
                    arkInfo = arkInfo,
                    lastSyncError = null,
                )
            }
        }.onFailure { Logger.error("Failed to refresh Ark state", it, context = TAG) }
    }

    // endregion

    // region receive

    suspend fun createInvoice(amountSats: ULong, description: String): Result<String> =
        executeWhenRunning("createInvoice") {
            runSuspendCatching { barkService.bolt11Invoice(amountSats, description).invoice }
        }

    suspend fun newArkAddress(): Result<String> = executeWhenRunning("newArkAddress") {
        runSuspendCatching { barkService.newArkAddress() }
    }

    suspend fun claimPendingReceives(): Result<Unit> = executeWhenRunning("claimPendingReceives") {
        runSuspendCatching { barkService.tryClaimAllLightningReceives() }.map { }
    }.also { syncState() }

    // endregion

    // region send

    suspend fun payInvoice(bolt11: String, amountSats: ULong? = null): Result<Unit> =
        executeWhenRunning("payInvoice") {
            runSuspendCatching { barkService.payLightningInvoice(bolt11, amountSats) }.map { }
        }.also { syncState() }

    suspend fun sendToArkAddress(address: String, amountSats: ULong): Result<Unit> =
        executeWhenRunning("sendToArkAddress") {
            runSuspendCatching { barkService.sendArkoorPayment(address, amountSats) }
        }.also { syncState() }

    // endregion

    // region board and offboard

    /** bark boards from its own on-chain wallet, so savings must be sent here first. */
    suspend fun onchainDepositAddress(): Result<String> = executeWhenRunning("onchainDepositAddress") {
        runSuspendCatching { barkService.onchainAddress() }
    }

    suspend fun onchainSpendableSats(): Result<ULong> = executeWhenRunning("onchainSpendableSats") {
        runSuspendCatching {
            barkService.syncOnchain()
            barkService.onchainBalance().confirmedSats
        }
    }

    suspend fun board(amountSats: ULong): Result<String> = executeWhenRunning("board") {
        runSuspendCatching { barkService.boardAmount(amountSats).txid }
    }.also { syncState() }

    suspend fun offboardAll(bitcoinAddress: String): Result<String> = executeWhenRunning("offboardAll") {
        runSuspendCatching { barkService.offboardAll(bitcoinAddress) }
    }.also { syncState() }

    suspend fun estimateBoardFeeSats(amountSats: ULong): Result<ULong> = executeWhenRunning("estimateBoardFee") {
        runSuspendCatching { barkService.estimateBoardFee(amountSats).feeSats }
    }

    suspend fun estimateOffboardAllFeeSats(bitcoinAddress: String): Result<ULong> =
        executeWhenRunning("estimateOffboardAllFee") {
            runSuspendCatching { barkService.estimateOffboardAllFee(bitcoinAddress).feeSats }
        }

    // endregion

    // region history and exits

    suspend fun history(): Result<List<Movement>> = executeWhenRunning("history") {
        runSuspendCatching { barkService.history() }
    }

    suspend fun hasPendingExits(): Result<Boolean> = executeWhenRunning("hasPendingExits") {
        runSuspendCatching { barkService.hasPendingExits() }
    }

    // endregion

    /**
     * Mirrors [LightningRepo.executeWhenNodeRunning]: waits out a start already in flight instead of
     * failing callers that raced it.
     */
    suspend fun <T> executeWhenRunning(
        operationName: String,
        waitTimeout: Duration = 1.minutes,
        operation: suspend () -> Result<T>,
    ): Result<T> = withContext(bgDispatcher) {
        val lifecycleState = _barkState.value.lifecycleState
        if (lifecycleState.isRunning()) {
            return@withContext executeOperation(operationName, operation)
        }

        if (!lifecycleState.canRun()) {
            return@withContext Result.failure(
                AppError("Cannot execute '$operationName': Ark wallet is '$lifecycleState' and not starting")
            )
        }

        val isRunning = withTimeoutOrNull(waitTimeout) {
            Logger.verbose("Waiting for Ark wallet to run before executing '$operationName'", context = TAG)
            _barkState.first { it.lifecycleState.isRunning() }
            true
        } ?: false

        if (!isRunning) {
            return@withContext Result.failure(AppError("Timed out waiting for Ark wallet to run: '$operationName'"))
        }

        return@withContext executeOperation(operationName, operation)
    }

    private suspend fun <T> executeOperation(
        operationName: String,
        operation: suspend () -> Result<T>,
    ): Result<T> = runCatching {
        operation().getOrThrow()
    }.onFailure {
        if (it is CancellationException) throw it
        Logger.error("Error executing '$operationName'", it, context = TAG)
    }
}

@Immutable
data class BarkState(
    val lifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val spendableSats: ULong = 0uL,
    val pendingInRoundSats: ULong = 0uL,
    val pendingBoardSats: ULong = 0uL,
    val pendingLightningSendSats: ULong = 0uL,
    val claimableLightningReceiveSats: ULong = 0uL,
    val pendingExitSats: ULong = 0uL,
    val vtxos: ImmutableList<BarkVtxo> = persistentListOf(),
    val nextRequiredRefreshHeight: UInt? = null,
    val arkInfo: BarkArkInfo? = null,
    val isSyncing: Boolean = false,
    val lastSyncError: Throwable? = null,
) {
    /** Sats that are neither spendable yet nor settled, shown as in-transfer in the UI. */
    val pendingIncomingSats: ULong get() = pendingBoardSats + pendingInRoundSats

    val hasAnyFunds: Boolean
        get() = spendableSats > 0uL ||
            pendingIncomingSats > 0uL ||
            pendingLightningSendSats > 0uL ||
            claimableLightningReceiveSats > 0uL ||
            pendingExitSats > 0uL
}

@Immutable
data class BarkVtxo(
    val id: String,
    val amountSats: ULong,
    val expiryHeight: UInt,
    val kind: String,
    val state: String,
)

@Immutable
data class BarkArkInfo(
    val roundIntervalSecs: ULong,
    val requiredBoardConfirmations: UInt,
    val minBoardAmountSats: ULong,
    val maxVtxoAmountSats: ULong?,
)

private fun Vtxo.toBarkVtxo() = BarkVtxo(
    id = id,
    amountSats = amountSats,
    expiryHeight = expiryHeight,
    kind = kind,
    state = state,
)

private fun ArkInfo.toBarkArkInfo() = BarkArkInfo(
    roundIntervalSecs = roundIntervalSecs,
    requiredBoardConfirmations = requiredBoardConfirmations,
    minBoardAmountSats = minBoardAmountSats,
    maxVtxoAmountSats = maxVtxoAmountSats,
)
