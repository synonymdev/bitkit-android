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
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val seedStore: SeedStore,
    private val sparkService: WatchSparkService,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "WalletRepo"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val _walletState = MutableStateFlow(WalletState())
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    val isSupported: Boolean get() = sparkService.isSupported

    init {
        scope.launch {
            sparkService.events.collect { refreshBalance() }
        }
    }

    suspend fun hasWallet(): Boolean = withContext(ioDispatcher) { seedStore.exists() }

    /** Connects the stored wallet and loads its balance; a wallet already connected only refreshes it. */
    suspend fun start(): Result<Unit> = withContext(ioDispatcher) {
        _walletState.update { it.copy(status = WalletStatus.Connecting) }
        runSuspendCatching {
            val seed = seedStore.load().getOrThrow()
            sparkService.connect(seed)
            sparkService.syncWallet()
            refreshBalance().getOrThrow()
        }.onSuccess {
            _walletState.update { it.copy(status = WalletStatus.Ready) }
        }.onFailure {
            Logger.error("Failed to start wallet", it, context = TAG)
            _walletState.update { it.copy(status = WalletStatus.Error) }
        }
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

enum class WalletStatus { Idle, Connecting, Ready, Error }

data class WalletState(
    val status: WalletStatus = WalletStatus.Idle,
    val balanceSats: ULong? = null,
)
