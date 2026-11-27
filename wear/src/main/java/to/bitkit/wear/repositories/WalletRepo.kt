package to.bitkit.wear.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@Singleton
class WalletRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val seedStore: SeedStore,
    private val sparkService: WatchSparkService,
    private val settingsStore: SettingsStore,
    private val deviceLock: DeviceLock,
) {
    companion object {
        private const val TAG = "WalletRepo"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

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
    }

    suspend fun hasWallet(): Boolean = withContext(ioDispatcher) { seedStore.exists() }

    /** Whether the wallet was deleted because its seed key was lost, and the wearer was not told yet. */
    suspend fun isRemovedNoticePending(): Boolean = withContext(ioDispatcher) {
        runSuspendCatching { settingsStore.isRemovedNoticePending() }.getOrDefault(false)
    }

    suspend fun acknowledgeRemoval(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching { settingsStore.setRemovedNoticePending(false) }
    }

    /** Stores [seed] as this watch's wallet. The caller validates the recovery phrase first. */
    suspend fun restore(seed: WalletSeed): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!isSupported) throw SparkError.Unsupported()
            seedStore.save(seed).getOrThrow()
        }.onFailure {
            Logger.error("Failed to restore wallet", it, context = TAG)
        }
    }

    /**
     * Tells the wallet whether the app is on screen. Coming to the front reads the lock state again and reconnects a
     * wallet that a lock disconnected.
     */
    suspend fun setForeground(isForeground: Boolean) = withContext(ioDispatcher) {
        this@WalletRepo.isForeground = isForeground
        deviceLock.refresh()
        if (isForeground) resume()
    }

    /**
     * Connects the stored wallet and loads its balance; a wallet already connected only refreshes it. It stays
     * disconnected while the watch is locked, which only a watch with a screen lock ever is. When the seed key is
     * gone or permanently unusable, the wallet is wiped and [WalletStatus.Removed] reported.
     */
    suspend fun start(): Result<Unit> = withContext(ioDispatcher) {
        deviceLock.refresh()
        if (deviceLock.isLocked.value) {
            _walletState.update { it.copy(status = WalletStatus.Locked) }
            return@withContext Result.failure(WalletError.DeviceLocked())
        }
        _walletState.update { it.copy(status = WalletStatus.Connecting) }
        runSuspendCatching {
            val seed = seedStore.load().getOrThrow()
            sparkService.connect(seed)
            // A lock that landed while connecting found nothing to disconnect.
            if (deviceLock.isLocked.value) {
                sparkService.disconnect()
                throw WalletError.DeviceLocked()
            }
            sparkService.syncWallet()
            refreshBalance().getOrThrow()
        }.onSuccess {
            _walletState.update { it.copy(status = WalletStatus.Ready) }
        }.onFailure {
            Logger.error("Failed to start wallet", it, context = TAG)
            onStartFailure(it)
        }
    }

    private suspend fun onStartFailure(error: Throwable) {
        if (deviceLock.isLocked.value) {
            _walletState.update { it.copy(status = WalletStatus.Locked) }
            return
        }
        when (error) {
            is SeedStoreError.KeyInvalidated -> {
                wipe()
                runSuspendCatching { settingsStore.setRemovedNoticePending(true) }
                _walletState.update { it.copy(status = WalletStatus.Removed) }
            }
            is SeedStoreError.DeviceLocked, is WalletError.DeviceLocked ->
                _walletState.update { it.copy(status = WalletStatus.Locked) }
            else -> _walletState.update { it.copy(status = WalletStatus.Error) }
        }
    }

    private suspend fun lock() {
        if (_walletState.value.status in disconnectedStatuses) return
        runSuspendCatching { sparkService.disconnect() }
            .onFailure { Logger.warn("Failed to disconnect on lock", it, context = TAG) }
        _walletState.update { it.copy(status = WalletStatus.Locked) }
        Logger.info("Disconnected wallet because the watch locked", context = TAG)
    }

    private suspend fun resume() {
        if (!isForeground || _walletState.value.status != WalletStatus.Locked) return
        start()
    }

    suspend fun refreshBalance(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val balance = sparkService.getInfo().balanceSats
            _walletState.update { it.copy(balanceSats = balance) }
        }
    }

    /**
     * Removes the wallet from the watch: disconnects Spark, deletes its storage, then deletes the seed and the
     * settings. Every step runs even when an earlier one fails; the first failure is returned.
     */
    suspend fun wipe(): Result<Unit> = withContext(ioDispatcher) {
        sparkService.isWiping = true
        try {
            val spark = runSuspendCatching { sparkService.wipeStorage() }
            val seed = seedStore.wipe()
            val settings = runSuspendCatching { settingsStore.reset() }
            _walletState.update { WalletState() }
            listOf(spark, seed, settings).firstOrNull { it.isFailure } ?: Result.success(Unit)
        } finally {
            sparkService.isWiping = false
        }
    }.onFailure {
        Logger.error("Failed to wipe wallet", it, context = TAG)
    }
}

private val disconnectedStatuses = setOf(WalletStatus.Idle, WalletStatus.Locked, WalletStatus.Removed)

enum class WalletStatus {
    Idle,
    Connecting,
    Ready,
    Error,

    /** Disconnected because the watch is locked. */
    Locked,

    /** The wallet was deleted from the watch because its seed key was lost. */
    Removed,
}

sealed class WalletError(message: String) : AppError(message) {
    class DeviceLocked : WalletError("The watch is locked")
}

data class WalletState(
    val status: WalletStatus = WalletStatus.Idle,
    val balanceSats: ULong? = null,
)
