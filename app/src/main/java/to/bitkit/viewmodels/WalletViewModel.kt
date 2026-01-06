package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.RecoveryModeException
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.MigrationService
import to.bitkit.ui.onboarding.LOADING_MS
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.isTxSyncTimeout
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class WalletViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val backupRepo: BackupRepo,
    private val blocktankRepo: BlocktankRepo,
    private val migrationService: MigrationService,
) : ViewModel() {
    companion object {
        private const val TAG = "WalletViewModel"
        private val RESTORE_WAIT_TIMEOUT = 30.seconds
    }

    val lightningState = lightningRepo.lightningState
    val walletState = walletRepo.walletState
    val balanceState = walletRepo.balanceState

    @Volatile
    private var isStarting = false

    // Local UI state
    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    val isRecoveryMode = lightningRepo.isRecoveryMode

    val isShowingMigrationLoading: StateFlow<Boolean> = migrationService.isShowingMigrationLoading

    val isRestoringFromRNRemoteBackup: StateFlow<Boolean> =
        migrationService.isRestoringFromRNRemoteBackup

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Initial)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _uiState = MutableStateFlow(MainUiState())

    @Deprecated("Prioritize get the wallet and lightning states from LightningRepo or WalletRepo")
    val uiState = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        checkAndPerformRNMigration()
        collectStates()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun checkAndPerformRNMigration() {
        viewModelScope.launch(bgDispatcher) {
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

            runCatching {
                migrationService.migrateFromReactNative()
                walletRepo.setWalletExistsState()
                walletExists = walletRepo.walletExists()
                loadCacheIfWalletExists()
                if (walletExists) {
                    val channelMigration = buildChannelMigrationIfAvailable()
                    startNode(0, channelMigration)
                } else {
                    migrationService.setShowingMigrationLoading(false)
                }
            }.onFailure { e ->
                Logger.error("RN migration failed: $e", e, context = "WalletViewModel")
                migrationService.markMigrationChecked()
                migrationService.setShowingMigrationLoading(false)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Migration Failed",
                    description = "Please restore your wallet manually using your recovery phrase"
                )
            }
        }
    }

    private fun loadCacheIfWalletExists() {
        if (walletExists) {
            walletRepo.loadFromCache()
        }
    }

    private fun collectStates() {
        viewModelScope.launch {
            walletState.collect { state ->
                walletExists = state.walletExists
                _uiState.update {
                    it.copy(
                        onchainAddress = state.onchainAddress,
                        bolt11 = state.bolt11,
                        bip21 = state.bip21,
                        bip21AmountSats = state.bip21AmountSats,
                        bip21Description = state.bip21Description,
                        selectedTags = state.selectedTags,
                    )
                }
                if (state.walletExists && _restoreState.value == RestoreState.InProgress.Wallet) {
                    restoreFromBackup()
                }
            }
        }

        viewModelScope.launch {
            lightningState.collect { state ->
                _uiState.update {
                    it.copy(
                        nodeId = state.nodeId,
                        nodeStatus = state.nodeStatus,
                        nodeLifecycleState = state.nodeLifecycleState,
                        peers = state.peers,
                        channels = state.channels,
                    )
                }
            }
        }
    }

    private suspend fun restoreFromBackup() {
        _restoreState.update { RestoreState.InProgress.Metadata }
        runCatching {
            restoreFromMostRecentBackup()
        }.onFailure { e ->
            Logger.error("Restore from backup failed", e, context = TAG)
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
    }

    private suspend fun restoreFromRNRemoteBackup() {
        runCatching {
            migrationService.restoreFromRNRemoteBackup()
            walletRepo.loadFromCache()
        }.onFailure { e ->
            Logger.warn("RN remote backup restore failed, falling back to VSS", e, context = TAG)
            backupRepo.performFullRestoreFromLatestBackup(onCacheRestored = walletRepo::loadFromCache)
        }
    }

    fun onRestoreContinue() {
        _restoreState.update { RestoreState.Settled }
    }

    fun proceedWithoutRestore(onDone: () -> Unit) {
        viewModelScope.launch {
            // TODO start LDK without trying to restore backup state from VSS if possible
            lightningRepo.stop()
            delay(LOADING_MS.milliseconds)
            _restoreState.update { RestoreState.Settled }
            onDone()
        }
    }

    fun setInitNodeLifecycleState() = lightningRepo.setInitNodeLifecycleState()

    fun start(walletIndex: Int = 0) {
        if (!walletExists || isStarting) return

        viewModelScope.launch(bgDispatcher) {
            isStarting = true
            try {
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
        withTimeoutOrNull(RESTORE_WAIT_TIMEOUT) {
            _restoreState.first { !it.isOngoing() }
        } ?: Logger.warn("Restore wait timed out, proceeding anyway", context = TAG)
    }

    private fun buildChannelMigrationIfAvailable(): ChannelDataMigration? {
        val migration = migrationService.peekPendingChannelMigration() ?: return null
        return ChannelDataMigration(
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
                walletRepo.syncBalances()
                if (_restoreState.value.isIdle()) {
                    walletRepo.refreshBip21()
                }
            }
            .onFailure { error ->
                Logger.error("Node startup error", error, context = TAG)
                if (error !is RecoveryModeException) {
                    ToastEventBus.send(error)
                }
            }
    }

    fun stop() {
        if (!walletExists) return

        viewModelScope.launch(bgDispatcher) {
            lightningRepo.stop()
                .onFailure { error ->
                    Logger.error("Node stop error", error)
                    ToastEventBus.send(error)
                }
        }
    }

    fun refreshState() = viewModelScope.launch {
        walletRepo.syncNodeAndWallet()
            .onFailure { error ->
                Logger.error("Failed to refresh state: ${error.message}", error)
                if (error is CancellationException || error.isTxSyncTimeout()) return@onFailure
                ToastEventBus.send(error)
            }
    }

    fun onPullToRefresh() {
        // Cancel any existing sync, manual or event triggered
        syncJob?.cancel()
        walletRepo.cancelSyncByEvent()
        lightningRepo.clearPendingSync()

        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                walletRepo.syncNodeAndWallet(source = SyncSource.MANUAL)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
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
                        description = "Peer disconnected.,"
                    )
                }
                .onFailure { error ->
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = error.message ?: context.getString(R.string.common__error_desc)
                    )
                }
        }
    }

    fun updateBip21Invoice(
        amountSats: ULong? = walletState.value.bip21AmountSats,
    ) {
        viewModelScope.launch {
            walletRepo.updateBip21Invoice(amountSats).onFailure { error ->
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.wallet__error_invoice_update),
                    description = error.message ?: context.getString(R.string.common__error_desc)
                )
            }
        }
    }

    fun refreshReceiveState() = viewModelScope.launch {
        launch { blocktankRepo.refreshInfo() }
        lightningRepo.updateGeoBlockState()
        walletRepo.refreshBip21()
    }

    fun wipeWallet() {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.wipeWallet().onFailure { error ->
                ToastEventBus.send(error)
            }
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        walletRepo.createWallet(bip39Passphrase)
            .onSuccess {
                backupRepo.scheduleFullBackup()
            }
            .onFailure { error ->
                ToastEventBus.send(error)
            }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        setInitNodeLifecycleState()
        _restoreState.update { RestoreState.InProgress.Wallet }

        walletRepo.restoreWallet(
            mnemonic = mnemonic,
            bip39Passphrase = bip39Passphrase,
        ).onFailure { error ->
            ToastEventBus.send(error)
        }
    }

    // region debug methods

    fun addTagToSelected(newTag: String) = viewModelScope.launch {
        walletRepo.addTagToSelected(newTag).onFailure { e ->
            ToastEventBus.send(e)
        }
    }

    fun removeTag(tag: String) = viewModelScope.launch {
        walletRepo.removeTag(tag).onFailure { e ->
            ToastEventBus.send(e)
        }
    }

    fun resetPreActivityMetadataTagsForCurrentInvoice() = viewModelScope.launch {
        walletRepo.resetPreActivityMetadataTagsForCurrentInvoice()
    }

    fun updateBip21Description(newText: String) {
        if (newText.isEmpty()) {
            Logger.warn("Empty")
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

// TODO rename to walletUiState
data class MainUiState(
    val nodeId: String = "",
    val onchainAddress: String = "",
    val bolt11: String = "",
    val bip21: String = "",
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: List<PeerDetails> = emptyList(),
    val channels: List<ChannelDetails> = emptyList(),
    val isRefreshing: Boolean = false,
    val bip21AmountSats: ULong? = null,
    val bip21Description: String = "",
    val selectedTags: List<String> = listOf(),
)

sealed interface RestoreState {
    data object Initial : RestoreState
    sealed interface InProgress : RestoreState {
        object Wallet : InProgress
        object Metadata : InProgress
    }

    data object Completed : RestoreState
    data object Settled : RestoreState

    fun isOngoing() = this is InProgress
    fun isIdle() = this is Initial || this is Settled
}
