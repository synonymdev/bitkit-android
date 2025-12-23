package to.bitkit.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PeerDetails
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
import to.bitkit.ui.onboarding.LOADING_MS
import to.bitkit.ui.screens.wallets.receive.CjitEntryDetails
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.isTxSyncTimeout
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val backupRepo: BackupRepo,
    private val blocktankRepo: BlocktankRepo,
) : ViewModel() {

    val lightningState = lightningRepo.lightningState
    val walletState = walletRepo.walletState
    val balanceState = walletRepo.balanceState

    // Local UI state
    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    val isRecoveryMode = lightningRepo.isRecoveryMode

    var restoreState by mutableStateOf<RestoreState>(RestoreState.Initial)
        private set

    private val _uiState = MutableStateFlow(MainUiState())

    @Deprecated("Prioritize get the wallet and lightning states from LightningRepo or WalletRepo")
    val uiState = _uiState.asStateFlow()

    private var syncJob: Job? = null

    init {
        if (walletExists) {
            walletRepo.loadFromCache()
        }
        collectStates()
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
                if (state.walletExists && restoreState == RestoreState.InProgress.Wallet) {
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
        restoreState = RestoreState.InProgress.Metadata
        backupRepo.performFullRestoreFromLatestBackup(onCacheRestored = walletRepo::loadFromCache)
        // data backup is not critical and mostly for user convenience so there is no reason to propagate errors up
        restoreState = RestoreState.Completed
    }

    fun onRestoreContinue() {
        restoreState = RestoreState.Settled
    }

    fun proceedWithoutRestore(onDone: () -> Unit) {
        viewModelScope.launch {
            // TODO start LDK without trying to restore backup state from VSS if possible
            lightningRepo.stop()
            delay(LOADING_MS.milliseconds)
            restoreState = RestoreState.Settled
            onDone()
        }
    }

    fun setInitNodeLifecycleState() = lightningRepo.setInitNodeLifecycleState()

    fun start(walletIndex: Int = 0) {
        if (!walletExists) return

        viewModelScope.launch(bgDispatcher) {
            lightningRepo.start(walletIndex)
                .onSuccess {
                    walletRepo.setWalletExistsState()
                    walletRepo.syncBalances()
                    // Skip refresh during restore, it will be called after completion
                    if (restoreState.isIdle()) {
                        walletRepo.refreshBip21()
                    }
                }
                .onFailure { error ->
                    Logger.error("Node startup error", error)
                    if (error !is RecoveryModeException) {
                        ToastEventBus.send(error)
                    }
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
                        title = "Success",
                        description = "Peer disconnected."
                    )
                }
                .onFailure { error ->
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Error",
                        description = error.message ?: "Unknown error"
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
                    title = "Error updating invoice",
                    description = error.message ?: "Unknown error"
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
        restoreState = RestoreState.InProgress.Wallet

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

    // region Receive Flow State
    private val _pendingCjitEntry = MutableStateFlow<CjitEntryDetails?>(null)
    val pendingCjitEntry = _pendingCjitEntry.asStateFlow()

    private val _pendingCjitInvoice = MutableStateFlow<String?>(null)
    val pendingCjitInvoice = _pendingCjitInvoice.asStateFlow()

    fun setPendingCjitEntry(entry: CjitEntryDetails?) = _pendingCjitEntry.update { entry }

    fun setPendingCjitInvoice(invoice: String?) = _pendingCjitInvoice.update { invoice }

    fun resetReceiveFlowState() {
        _pendingCjitEntry.update { null }
        _pendingCjitInvoice.update { null }
    }
    // endregion
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
