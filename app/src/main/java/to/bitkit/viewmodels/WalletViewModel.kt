package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BoltzSwapEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.of
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.Toast
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.RecoveryModeError
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.BoltzService
import to.bitkit.services.MigrationService
import to.bitkit.ui.onboarding.LOADING_MS
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.isTxSyncTimeout
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions", "LongParameterList")
@HiltViewModel
class WalletViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val backupRepo: BackupRepo,
    private val blocktankRepo: BlocktankRepo,
    private val pubkyRepo: PubkyRepo,
    private val migrationService: MigrationService,
    private val connectivityRepo: ConnectivityRepo,
    private val boltzService: BoltzService,
) : ViewModel() {
    companion object {
        private const val TAG = "WalletViewModel"
        private val TIMEOUT_RESTORE_WAIT = 30.seconds

        /** Base backoff between swap updates stream attempts; scales linearly per attempt. */
        private val SWAP_UPDATES_RETRY_DELAY = 5.seconds

        /** Upper bound for the backoff between swap updates stream attempts. */
        private val SWAP_UPDATES_RETRY_CAP = 60.seconds

        /**
         * Ceiling on swap updates stream start attempts per run (~14 min of backoff). Giving up is
         * safe: the stream is retried on the next node start and when entering a swap flow.
         */
        private const val SWAP_UPDATES_MAX_ATTEMPTS = 20
    }

    val lightningState = lightningRepo.lightningState
    val walletState = walletRepo.walletState
    val balanceState = walletRepo.balanceState

    @Volatile
    private var isStarting = false

    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    val isRecoveryMode = lightningRepo.isRecoveryMode

    val isShowingMigrationLoading: StateFlow<Boolean> = migrationService.isShowingMigrationLoading
    val isRestoringFromRNRemoteBackup: StateFlow<Boolean> = migrationService.isRestoringFromRNRemoteBackup

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Initial)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private var syncJob: Job? = null
    private var pendingWalletStart = false

    init {
        checkAndPerformRNMigration()
        collectStates()
        observeNetworkState()
    }

    private fun observeNetworkState() = viewModelScope.launch(bgDispatcher) {
        connectivityRepo.isOnline.collect { state ->
            if (state == ConnectivityState.CONNECTED && pendingWalletStart) {
                pendingWalletStart = false
                val isChecked = migrationService.isMigrationChecked()
                if (!isChecked && migrationService.hasRNWalletData()) {
                    Logger.info("Network restored, retrying RN migration...", context = TAG)
                    checkAndPerformRNMigration()
                } else {
                    Logger.info("Network restored, retrying wallet start...", context = TAG)
                    start()
                }
            }
        }
    }

    private suspend fun isNetworkConnected(): Boolean {
        val state = connectivityRepo.isOnline.first()
        return state == ConnectivityState.CONNECTED
    }

    private fun checkAndPerformRNMigration() = viewModelScope.launch(bgDispatcher) {
        val isChecked = migrationService.isMigrationChecked()
        if (isChecked) {
            loadCacheIfWalletExists()
            return@launch
        }

        val hasNative = migrationService.hasNativeWalletData()
        if (hasNative) {
            migrationService.markMigrationChecked()
            loadCacheIfWalletExists()
            return@launch
        }

        val hasRN = migrationService.hasRNWalletData()
        if (!hasRN) {
            migrationService.markMigrationChecked()
            loadCacheIfWalletExists()
            return@launch
        }

        migrationService.setShowingMigrationLoading(true)
        Logger.info("RN wallet data found, starting migration...", context = TAG)

        runCatching {
            migrationService.migrateFromReactNative()
            walletRepo.setWalletExistsState()
            walletExists = walletRepo.walletExists()
            loadCacheIfWalletExists()
            if (walletExists) {
                // Re-check network before starting wallet (like iOS)
                if (!isNetworkConnected()) {
                    Logger.warn("Network offline, dismissing loader and skipping wallet start", context = TAG)
                    migrationService.setShowingMigrationLoading(false)
                    pendingWalletStart = true
                    return@launch
                }
                val channelMigration = buildChannelMigrationIfAvailable()
                startNode(0, channelMigration)
            } else {
                migrationService.setShowingMigrationLoading(false)
            }
        }.onFailure {
            Logger.error("RN migration failed", it, context = TAG)
            migrationService.markMigrationChecked()
            migrationService.setShowingMigrationLoading(false)
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = "Migration Failed",
                description = "Please restore your wallet manually using your recovery phrase"
            )
        }
    }

    private fun loadCacheIfWalletExists() {
        if (walletExists) {
            walletRepo.loadFromCache()
        }
    }

    private fun collectStates() = viewModelScope.launch {
        walletState.collect {
            walletExists = it.walletExists
            if (it.walletExists && _restoreState.value == RestoreState.InProgress.Wallet) {
                restoreFromBackup()
            }
        }
    }

    private suspend fun restoreFromBackup() {
        _restoreState.update { RestoreState.InProgress.Metadata }
        runCatching {
            restoreFromMostRecentBackup()
        }.onFailure {
            Logger.error("Restore from backup failed", it, context = TAG)
        }
        _restoreState.update { RestoreState.Completed }
    }

    private suspend fun restoreFromMostRecentBackup() {
        val (rnTimestamp, vssTimestamp) = coroutineScope {
            val rn = async { migrationService.getRNRemoteBackupTimestamp() }
            val vss = async { backupRepo.getLatestBackupTime() }
            rn.await() to vss.await()
        }

        val shouldRestoreRN = when {
            rnTimestamp == null -> false
            vssTimestamp == null || vssTimestamp == 0uL -> true
            else -> rnTimestamp >= vssTimestamp
        }

        if (shouldRestoreRN) {
            restoreFromRNRemoteBackup()
        } else {
            backupRepo.performFullRestoreFromLatestBackup(onCacheRestored = walletRepo::loadFromCache)
        }

        pubkyRepo.initialize()
    }

    private suspend fun restoreFromRNRemoteBackup() = runCatching {
        migrationService.restoreFromRNRemoteBackup()
        walletRepo.loadFromCache()
    }.onFailure {
        Logger.warn("RN remote backup restore failed, falling back to VSS", it, context = TAG)
        backupRepo.performFullRestoreFromLatestBackup(onCacheRestored = walletRepo::loadFromCache)
    }

    fun onRestoreContinue() {
        viewModelScope.launch(bgDispatcher) {
            if (!settingsStore.restoredMonitoredTypesFromBackup) {
                settingsStore.update { it.copy(pendingRestoreAddressTypePrune = true) }
            }
        }
        _restoreState.update { RestoreState.Settled }
    }

    fun onRestoreRetry() = viewModelScope.launch(bgDispatcher) {
        _restoreState.update { it.countRetry() }
        setInitNodeLifecycleState()
        lightningRepo.restartNode()
    }

    @Suppress("ForbiddenComment")
    fun onProceedWithoutRestore(onDone: () -> Unit) = viewModelScope.launch {
        // TODO start LDK without trying to restore backup state from VSS if possible
        lightningRepo.stop()
        delay(LOADING_MS.milliseconds)
        _restoreState.update { RestoreState.Settled }
        onDone()
    }

    fun setInitNodeLifecycleState() = lightningRepo.setInitNodeLifecycleState()

    fun start(walletIndex: Int = 0) {
        if (!walletExists || isStarting) return

        viewModelScope.launch(bgDispatcher) {
            isStarting = true
            try {
                if (!isNetworkConnected()) {
                    Logger.warn("Network offline, skipping wallet start", context = TAG)
                    pendingWalletStart = true

                    if (migrationService.isShowingMigrationLoading.value) {
                        migrationService.setShowingMigrationLoading(false)
                    }
                    return@launch
                }

                waitForRestoreIfNeeded()

                val channelMigration = buildChannelMigrationIfAvailable()
                startNode(walletIndex, channelMigration)
            } finally {
                isStarting = false
            }
        }
    }

    private suspend fun waitForRestoreIfNeeded() {
        if (!_restoreState.value.isOngoing()) return
        withTimeoutOrNull(TIMEOUT_RESTORE_WAIT) {
            _restoreState.first { !it.isOngoing() }
        } ?: Logger.warn("waitForRestoreIfNeeded timeout, proceeding anyway", context = TAG)
    }

    private fun buildChannelMigrationIfAvailable(): ChannelDataMigration? =
        migrationService.peekPendingChannelMigration()?.let { migration ->
            ChannelDataMigration(
                channelManager = migration.channelManager.map { it.toUByte() },
                channelMonitors = migration.channelMonitors.map { monitor -> monitor.map { it.toUByte() } },
            )
        }

    private suspend fun startNode(
        walletIndex: Int = 0,
        channelMigration: ChannelDataMigration?,
    ) {
        lightningRepo.start(walletIndex, channelMigration = channelMigration)
            .onSuccess {
                if (channelMigration != null) {
                    migrationService.consumePendingChannelMigration()
                }
                walletRepo.setWalletExistsState()
                connectMigrationPeers()
                migrationService.cleanupInvalidMigrationTransfers()
                walletRepo.syncBalances()
                if (_restoreState.value.isIdle()) {
                    walletRepo.refreshBip21()
                }
                ensureSwapUpdatesRunning()
                // checkForOrphanedChannelMonitorRecovery()
            }
            .onFailure {
                Logger.error("Node startup error", it, context = TAG)
                if (it !is RecoveryModeError) {
                    ToastEventBus.send(it)
                }
            }
    }

    private var swapEventsCollected = false
    private var swapUpdatesJob: Job? = null

    @Volatile
    private var swapUpdatesRunning = false

    /**
     * Ensure the swap updates stream is running so pending LN -> onchain swaps are tracked and
     * auto-claimed. A live stream is left untouched: restarting it would abort bitkit-core's
     * background tasks and could race an in-flight claim. New swaps are reconciled at creation
     * inside bitkit-core, and the stream reconciles every pending swap periodically, so a
     * running stream is always enough. Runs only where swaps are supported and enabled in dev
     * settings, see [BoltzService.isSwapEnabled].
     */
    fun ensureSwapUpdatesRunning() {
        if (!boltzService.isSwapSupported) return
        if (swapUpdatesRunning || swapUpdatesJob?.isActive == true) return
        swapUpdatesJob = viewModelScope.launch {
            if (!boltzService.isSwapEnabled()) return@launch
            collectSwapEventsOnce()
            startSwapUpdates()
        }
    }

    /**
     * Open the swap updates stream so any pending LN -> onchain swaps resume and auto-claim.
     * Uses the wallet's current fee rate for the claim tx. Retries up to a ceiling: without the
     * stream a paid swap has nothing to broadcast its claim, so give up only after
     * [SWAP_UPDATES_MAX_ATTEMPTS] and leave the next trigger to retry. Once started, bitkit-core
     * keeps the WebSocket alive with its own reconnect loop.
     */
    private suspend fun startSwapUpdates() {
        var attempt = 0
        while (attempt < SWAP_UPDATES_MAX_ATTEMPTS) {
            val started = runSuspendCatching {
                val speed = settingsStore.data.first().defaultTransactionSpeed
                val feeRate = lightningRepo.getFeeRateForSpeed(speed).getOrNull()?.toDouble()
                boltzService.startUpdates(feeRateSatPerVb = feeRate, acceptZeroConf = true)
            }.onFailure {
                Logger.warn("Failed to start swap updates, attempt '${attempt + 1}'", it, context = TAG)
            }.isSuccess

            if (started) {
                swapUpdatesRunning = true
                return
            }
            attempt++
            delay((SWAP_UPDATES_RETRY_DELAY * attempt).coerceAtMost(SWAP_UPDATES_RETRY_CAP))
        }
        Logger.warn("Gave up starting swap updates after '$attempt' attempts", context = TAG)
    }

    /** Refresh balances when a swap lands on-chain so savings reflect it without a manual sync. */
    private fun collectSwapEventsOnce() {
        if (swapEventsCollected) return
        swapEventsCollected = true
        // UNDISPATCHED so we subscribe before the updates stream starts: the events flow has
        // no replay, so a swap claimed early would otherwise leave balances stale.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            boltzService.events.collect { event ->
                if (event is BoltzSwapEvent.Claimed) {
                    Logger.info("Savings swap claimed: ${event.swapId}", context = TAG)
                    walletRepo.syncBalances()
                }
            }
        }
    }

    private suspend fun connectMigrationPeers() {
        val peerUris = migrationService.tryFetchMigrationPeersFromBackup()
        for (uri in peerUris) {
            runCatching {
                val peer = PeerDetails.of(uri)
                lightningRepo.connectPeer(peer)
            }.onFailure {
                Logger.error("Failed to connect migration peer: $uri", it, context = TAG)
            }
        }
    }

    fun stop() {
        if (!walletExists) return

        swapUpdatesJob?.cancel()
        swapUpdatesRunning = false
        viewModelScope.launch(bgDispatcher) {
            stopSwapUpdates()
        }
        lightningRepo.stopDebounced()
    }

    private suspend fun stopSwapUpdates() {
        runSuspendCatching { boltzService.stopUpdates() }
            .onFailure { Logger.error("Failed to stop swap updates", it, context = TAG) }
    }

    fun refreshState() = viewModelScope.launch {
        walletRepo.syncNodeAndWallet()
            .onFailure {
                Logger.error("Failed to refresh state: ${it.message}", it)
                if (it is CancellationException || it.isTxSyncTimeout()) return@onFailure
                ToastEventBus.send(it)
            }
    }

    /**
     * Refresh wallet balances and channel state from the running node without a chain sync, so a
     * just-received payment is reflected immediately (e.g. when entering the transfer-to-savings flow).
     */
    fun refreshBalances() = viewModelScope.launch {
        walletRepo.syncBalances()
        lightningRepo.syncState()
    }

    fun onPullToRefresh() {
        // Cancel any existing sync, manual or event triggered
        syncJob?.cancel()
        walletRepo.cancelSyncByEvent()
        lightningRepo.clearPendingSync()

        syncJob = viewModelScope.launch {
            _isRefreshing.update { true }
            try {
                walletRepo.syncNodeAndWallet(source = SyncSource.MANUAL)
            } finally {
                _isRefreshing.update { false }
            }
        }
    }

    fun disconnectPeer(peer: PeerDetails) {
        viewModelScope.launch {
            lightningRepo.disconnectPeer(peer)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = context.getString(R.string.common__success),
                        description = context.getString(R.string.wallet__peer_disconnected)
                    )
                }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = it.message ?: context.getString(R.string.common__error_body)
                    )
                }
        }
    }

    fun updateBip21Invoice(amountSats: ULong? = walletState.value.bip21AmountSats) = viewModelScope.launch {
        walletRepo.updateBip21Invoice(amountSats).onFailure { error ->
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.wallet__error_invoice_update),
                description = error.message ?: context.getString(R.string.common__error_body)
            )
        }
    }

    fun refreshReceiveState() = viewModelScope.launch {
        launch { blocktankRepo.refreshInfo() }
        lightningRepo.syncState()
        lightningRepo.updateGeoBlockState()
        walletRepo.refreshBip21()
    }

    fun wipeWallet() = viewModelScope.launch(bgDispatcher) {
        walletRepo.wipeWallet().onFailure {
            ToastEventBus.send(it)
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        walletRepo.createWallet(bip39Passphrase)
            .onSuccess {
                backupRepo.scheduleFullBackup()
            }
            .onFailure {
                ToastEventBus.send(it)
            }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        _restoreState.update { RestoreState.InProgress.Wallet }

        walletRepo.restoreWallet(
            mnemonic = mnemonic,
            bip39Passphrase = bip39Passphrase,
        ).onFailure {
            ToastEventBus.send(it)
        }
    }

    // region debug methods

    fun addTagToSelected(newTag: String) = viewModelScope.launch {
        walletRepo.addTagToSelected(newTag).onFailure {
            ToastEventBus.send(it)
        }
    }

    fun removeTag(tag: String) = viewModelScope.launch {
        walletRepo.removeTag(tag).onFailure {
            ToastEventBus.send(it)
        }
    }

    fun resetPreActivityMetadataTagsForCurrentInvoice() = viewModelScope.launch {
        walletRepo.resetPreActivityMetadataTagsForCurrentInvoice()
    }

    fun updateBip21Description(newText: String) {
        if (newText.isEmpty()) {
            Logger.warn(context.getString(R.string.common__empty))
        }
        walletRepo.setBip21Description(newText)
    }

    suspend fun handleHideBalanceOnOpen() {
        val hideBalanceOnOpen = settingsStore.data.map { it.hideBalanceOnOpen }.first()
        if (hideBalanceOnOpen) {
            settingsStore.update { it.copy(hideBalance = true) }
        }
    }
}

sealed interface RestoreState {
    data object Initial : RestoreState

    sealed interface InProgress : RestoreState {
        object Wallet : InProgress
        object Metadata : InProgress
    }

    data class Retry(val count: Int) : RestoreState
    data object Completed : RestoreState
    data object Settled : RestoreState

    fun retryCount() = (this as? Retry)?.count ?: 0
    fun countRetry(): RestoreState = if (this is Retry) Retry(count + 1) else Retry(1)
    fun isOngoing() = this is InProgress
    fun isIdle() = this is Initial || this is Settled
}
