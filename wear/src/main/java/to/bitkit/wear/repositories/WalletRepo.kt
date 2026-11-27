package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.wear.data.NetworkMonitor
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.di.IoDispatcher
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.security.DeviceLock
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Singleton
class WalletRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val seedStore: SeedStore,
    private val sparkService: WatchSparkService,
    private val settingsStore: SettingsStore,
    private val deviceLock: DeviceLock,
    private val networkMonitor: NetworkMonitor,
) {
    companion object {
        private const val TAG = "WalletRepo"

        /** How long connecting to Spark may take before the start counts as failed. */
        val CONNECT_TIMEOUT = 30.seconds

        /** How long the wallet sync may take before the start counts as failed; a first sync took about 3.5 minutes. */
        val SYNC_TIMEOUT = 5.minutes
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val startMutex = Mutex()

    private val walletMutex = Mutex()

    @Volatile
    private var isForeground = false

    private val _walletState = MutableStateFlow(WalletState())
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    val isSupported: Boolean get() = sparkService.isSupported

    init {
        scope.launch {
            sparkService.events.collect { refreshBalance() }
        }
        scope.launch {
            deviceLock.isLocked.collect { isLocked -> if (isLocked) lock() else resume() }
        }
        scope.launch {
            networkMonitor.isOnline.collect { isOnline -> if (isOnline) resume() else onNetworkLoss() }
        }
    }

    suspend fun hasWallet(): Boolean = withContext(ioDispatcher) { seedStore.exists() }

    /** Whether the wallet was deleted because its seed key was lost, and the wearer was not told yet. */
    suspend fun isRemovedNoticePending(): Boolean = withContext(ioDispatcher) {
        runSuspendCatching { settingsStore.isRemovedNoticePending() }.getOrDefault(false)
    }

    /** Runs to the end even when the caller is cancelled, so the notice never shows twice. */
    suspend fun acknowledgeRemoval(): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        runSuspendCatching { settingsStore.setRemovedNoticePending(false) }
            .onFailure { Logger.warn("Failed to acknowledge wallet removal", it, context = TAG) }
    }

    /**
     * Run at every launch before the wallet is used: finishes a wipe that the last process did not complete, and
     * deletes Spark storage that no stored seed owns.
     */
    suspend fun recover(): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        walletMutex.withLock { recoverLocked() }
    }.onFailure {
        Logger.error("Failed to recover wallet storage", it, context = TAG)
    }

    /**
     * Stores [seed] as this watch's wallet, after [recover]ing what an earlier wallet left. The caller validates the
     * recovery phrase first.
     */
    suspend fun restore(seed: WalletSeed): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        walletMutex.withLock {
            runSuspendCatching {
                if (!isSupported) throw SparkError.Unsupported()
                recoverLocked().getOrThrow()
                seedStore.save(seed).getOrThrow()
                _walletState.update { WalletState() }
            }
        }
    }.onFailure {
        Logger.error("Failed to restore wallet", it, context = TAG)
    }

    /**
     * Tells the wallet whether the app is on screen. Coming to the front reads the lock state again, reconnects a
     * wallet that a lock disconnected and syncs one that went offline.
     */
    suspend fun setForeground(isForeground: Boolean) = withContext(ioDispatcher) {
        this@WalletRepo.isForeground = isForeground
        deviceLock.refresh()
        if (isForeground) resume()
    }

    /**
     * Connects the stored wallet, syncs it and loads its balance; a wallet already connected only syncs it. Starts
     * run one at a time. It stays disconnected while the watch is locked, which only a watch with a screen lock ever
     * is. When the seed key is certainly lost, the wallet is wiped and [WalletStatus.Removed] reported. A sync that
     * does not reach Spark fails the start: [WalletStatus.Offline] once this session has synced,
     * [WalletStatus.Error] before. A start that a wipe overtook connects nothing and leaves the state alone.
     */
    suspend fun start(): Result<Unit> = withContext(ioDispatcher) {
        startMutex.withLock {
            deviceLock.refresh()
            if (deviceLock.isLocked.value) {
                _walletState.update { it.copy(status = WalletStatus.Locked) }
                return@withLock Result.failure(WalletError.DeviceLocked())
            }
            val wipeCount = sparkService.wipeCount
            _walletState.update { it.copy(status = WalletStatus.Connecting) }
            runSuspendCatching {
                val seed = seedStore.load().getOrThrow()
                val connectStart = TimeSource.Monotonic.markNow()
                withTimeoutOrNull(CONNECT_TIMEOUT) {
                    sparkService.connect(seed, wipeCount)
                    true
                } ?: throw WalletError.Timeout("connect")
                Logger.info("Connected wallet in '${connectStart.elapsedNow()}'", context = TAG)
                // A lock that landed while connecting found nothing to disconnect.
                if (deviceLock.isLocked.value) {
                    sparkService.disconnect()
                    throw WalletError.DeviceLocked()
                }
                val syncStart = TimeSource.Monotonic.markNow()
                val sync = withTimeoutOrNull(SYNC_TIMEOUT) { sparkService.syncWallet() }
                    ?: throw WalletError.Timeout("sync")
                if (!sync.hasReachedSpark) throw WalletError.NotSynced(sync.error)
                Logger.info("Synced wallet in '${syncStart.elapsedNow()}'", context = TAG)
                refreshBalance().getOrThrow()
            }.onSuccess {
                if (sparkService.wipeCount != wipeCount) return@onSuccess
                _walletState.update { it.copy(status = WalletStatus.Ready, hasSynced = true) }
            }.onFailure {
                if (sparkService.wipeCount != wipeCount) {
                    Logger.info("Dropped wallet start that a wipe overtook", context = TAG)
                    return@onFailure
                }
                onStartFailure(it)
            }
        }
    }

    private suspend fun onStartFailure(error: Throwable) {
        if (deviceLock.isLocked.value) {
            Logger.info("Stopped wallet start because the watch locked", context = TAG)
            _walletState.update { it.copy(status = WalletStatus.Locked) }
            return
        }
        when (error) {
            is SeedStoreError.KeyInvalidated -> {
                Logger.error("Failed to start wallet", error, context = TAG)
                wipe(isKeyLost = true)
                _walletState.update { it.copy(status = WalletStatus.Removed) }
            }
            is SeedStoreError.NotFound -> {
                Logger.warn("Failed to start wallet", error, context = TAG)
                _walletState.update { it.copy(status = WalletStatus.Missing) }
            }
            is SeedStoreError.DeviceLocked, is WalletError.DeviceLocked -> {
                Logger.info("Stopped wallet start because the watch locked", context = TAG)
                _walletState.update { it.copy(status = WalletStatus.Locked) }
            }
            else -> {
                Logger.error("Failed to start wallet", error, context = TAG)
                _walletState.update {
                    it.copy(status = if (it.hasSynced) WalletStatus.Offline else WalletStatus.Error)
                }
            }
        }
    }

    private fun onNetworkLoss() {
        _walletState.update { if (it.status == WalletStatus.Ready) it.copy(status = WalletStatus.Offline) else it }
    }

    private suspend fun lock() {
        if (_walletState.value.status in disconnectedStatuses) return
        runSuspendCatching { sparkService.disconnect() }
            .onFailure { Logger.warn("Failed to disconnect on lock", it, context = TAG) }
        _walletState.update { it.copy(status = WalletStatus.Locked) }
        Logger.info("Disconnected wallet because the watch locked", context = TAG)
    }

    private suspend fun resume() {
        if (!isForeground) return
        when (_walletState.value.status) {
            WalletStatus.Locked -> start()
            WalletStatus.Offline if networkMonitor.isOnline.value -> start()
            else -> Unit
        }
    }

    /** Loads the balance from Spark. A balance read while the wallet is wiped is dropped. */
    suspend fun refreshBalance(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (sparkService.isWiping) return@runSuspendCatching
            val balance = sparkService.getInfo().balanceSats
            _walletState.update { if (sparkService.isWiping) it else it.copy(balanceSats = balance) }
        }
    }

    /**
     * Removes the wallet from the watch as one operation that the caller cannot cancel: it notes that a wipe is under
     * way, deletes the seed, then Spark's storage, then the settings together with the note. Every step runs even
     * when an earlier one fails, and the first failure is returned. When the process dies or a step fails, the note
     * stays and [recover] finishes the wipe at the next launch.
     */
    suspend fun wipe(): Result<Unit> = wipe(isKeyLost = false)

    private suspend fun wipe(isKeyLost: Boolean): Result<Unit> = withContext(ioDispatcher + NonCancellable) {
        walletMutex.withLock { wipeLocked(isKeyLost) }
    }.onFailure {
        Logger.error("Failed to wipe wallet", it, context = TAG)
    }

    private suspend fun wipeLocked(isKeyLost: Boolean): Result<Unit> {
        sparkService.beginWipe()
        try {
            runSuspendCatching { settingsStore.beginWipe(isKeyLost) }
                .onFailure { Logger.warn("Failed to note the wallet wipe", it, context = TAG) }
            val seed = seedStore.wipe()
            val spark = runSuspendCatching { sparkService.wipeStorage() }
            val isWiped = seed.isSuccess && spark.isSuccess
            val settings = if (isWiped) runSuspendCatching { settingsStore.finishWipe() } else Result.success(Unit)
            _walletState.update { WalletState() }
            return listOf(seed, spark, settings).firstOrNull { it.isFailure } ?: Result.success(Unit)
        } finally {
            sparkService.endWipe()
        }
    }

    private suspend fun recoverLocked(): Result<Unit> = runSuspendCatching {
        if (settingsStore.isWipePending()) {
            Logger.warn("Finishing a wallet wipe that did not complete", context = TAG)
            wipeLocked(isKeyLost = false).getOrThrow()
            return@runSuspendCatching
        }
        if (!seedStore.exists()) sparkService.wipeStorage()
    }
}

private val disconnectedStatuses =
    setOf(WalletStatus.Idle, WalletStatus.Locked, WalletStatus.Removed, WalletStatus.Missing)

enum class WalletStatus {
    Idle,
    Connecting,
    Ready,

    /** This session synced, but the last sync did not reach Spark, so the balance shown is the last one known. */
    Offline,

    /** The wallet could not be loaded, and no sync reached Spark this session. */
    Error,

    /** Disconnected because the watch is locked. */
    Locked,

    /** The wallet was deleted from the watch because its seed key was lost. */
    Removed,

    /** No seed is stored, so there is no wallet to open. */
    Missing,
}

sealed class WalletError(message: String) : AppError(message) {
    class DeviceLocked : WalletError("The watch is locked")

    /** The wallet sync did not reach Spark, as without a network. */
    class NotSynced(error: String?) : WalletError("Sync did not reach Spark: '$error'")

    /** Spark did not finish [step] in time. */
    class Timeout(step: String) : WalletError("Timed out on '$step'")
}

/** [balanceSats] is Spark's stored balance; only once [hasSynced] has this session reached Spark to confirm it. */
data class WalletState(
    val status: WalletStatus = WalletStatus.Idle,
    val balanceSats: ULong? = null,
    val hasSynced: Boolean = false,
)
