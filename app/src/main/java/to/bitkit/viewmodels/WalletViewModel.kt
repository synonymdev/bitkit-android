package to.bitkit.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.LnPeer
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.onboarding.LOADING_MS
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val settingsStore: SettingsStore,
    private val backupRepo: BackupRepo,
) : ViewModel() {

    val lightningState = lightningRepo.lightningState
    val walletState = walletRepo.walletState
    val balanceState = walletRepo.balanceState

    // Local UI state
    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    var restoreState by mutableStateOf<RestoreState>(RestoreState.NotRestoring)
        private set

    private val _uiState = MutableStateFlow(MainUiState())

    @Deprecated("Prioritize get the wallet and lightning states from LightningRepo or WalletRepo")
    val uiState = _uiState.asStateFlow()

    private val _walletEffect = MutableSharedFlow<WalletViewModelEffects>(extraBufferCapacity = 1)
    val walletEffect = _walletEffect.asSharedFlow()
    private fun walletEffect(effect: WalletViewModelEffects) = viewModelScope.launch { _walletEffect.emit(effect) }

    init {
        collectStates()
    }

    private fun collectStates() { // This is necessary to avoid a bigger refactor in all application
        viewModelScope.launch(bgDispatcher) {
            walletState.collect { state ->
                walletExists = state.walletExists
                _uiState.update {
                    it.copy(
                        onchainAddress = state.onchainAddress,
                        balanceInput = state.balanceInput,
                        bolt11 = state.bolt11,
                        bip21 = state.bip21,
                        bip21AmountSats = state.bip21AmountSats,
                        bip21Description = state.bip21Description,
                        selectedTags = state.selectedTags,
                        receiveOnSpendingBalance = state.receiveOnSpendingBalance,
                        balanceDetails = state.balanceDetails
                    )
                }
                if (state.walletExists && restoreState == RestoreState.RestoringWallet) {
                    triggerBackupRestore()
                }
            }
        }

        viewModelScope.launch(bgDispatcher) {
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

    private fun triggerBackupRestore() {
        restoreState = RestoreState.RestoringBackups

        viewModelScope.launch(bgDispatcher) {
            backupRepo.performFullRestoreFromLatestBackup()
            // data backup is not critical and mostly for user convenience so there is no reason to propagate errors up
            restoreState = RestoreState.BackupRestoreCompleted
        }
    }

    fun setRestoringWalletState() {
        restoreState = RestoreState.RestoringWallet
    }

    fun onRestoreContinue() {
        restoreState = RestoreState.NotRestoring
    }

    fun proceedWithoutRestore(onDone: () -> Unit) {
        viewModelScope.launch {
            // TODO start LDK without trying to restore backup state from VSS if possible
            lightningRepo.stop()
            delay(LOADING_MS.milliseconds)
            restoreState = RestoreState.NotRestoring
            onDone()
        }
    }

    fun setInitNodeLifecycleState() {
        lightningRepo.setInitNodeLifecycleState()
    }

    fun start(walletIndex: Int = 0) {
        if (!walletExists) return

        viewModelScope.launch(bgDispatcher) {
            lightningRepo.start(walletIndex)
                .onSuccess {
                    walletRepo.setWalletExistsState()
                    walletRepo.syncBalances()
                    walletRepo.refreshBip21()
                }
                .onFailure { error ->
                    Logger.error("Node startup error", error)
                    ToastEventBus.send(error)
                }
        }
    }

    suspend fun observeLdkWallet() {
        walletRepo.observeLdkWallet()
    }

    fun refreshState() {
        viewModelScope.launch {
            walletRepo.syncNodeAndWallet()
                .onFailure { error ->
                    Logger.error("Failed to refresh state: ${error.message}", error)
                    ToastEventBus.send(error)
                }
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            walletRepo.syncNodeAndWallet()
                .onFailure { error ->
                    Logger.error("Failed to refresh state: ${error.message}", error)
                    ToastEventBus.send(error)
                }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun disconnectPeer(peer: LnPeer) {
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
        amountSats: ULong? = null,
    ) {
        viewModelScope.launch {
            walletRepo.updateBip21Invoice(
                amountSats = amountSats,
                description = walletState.value.bip21Description,
            ).onFailure { error ->
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Error updating invoice",
                    description = error.message ?: "Unknown error"
                )
            }
        }
    }

    fun toggleReceiveOnSpending() {
        viewModelScope.launch {
            walletRepo.toggleReceiveOnSpendingBalance().onSuccess {
                updateBip21Invoice(
                    amountSats = walletState.value.bip21AmountSats,
                )
            }.onFailure { e ->
                if (e is ServiceError.GeoBlocked) {
                    walletEffect(WalletViewModelEffects.NavigateGeoBlockScreen)
                    return@launch
                }

                updateBip21Invoice(
                    amountSats = walletState.value.bip21AmountSats,
                )
            }
        }
    }

    fun refreshBip21() {
        viewModelScope.launch {
            walletRepo.refreshBip21()
        }
    }

    fun wipeWallet() {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.wipeWallet().onFailure { error ->
                ToastEventBus.send(error)
            }
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        walletRepo.createWallet(bip39Passphrase).onFailure { error ->
            ToastEventBus.send(error)
        }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        walletRepo.restoreWallet(
            mnemonic = mnemonic,
            bip39Passphrase = bip39Passphrase
        ).onFailure { error ->
            ToastEventBus.send(error)
        }
    }

    // region debug methods

    fun addTagToSelected(newTag: String) {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.addTagToSelected(newTag)
        }
    }

    fun removeTag(tag: String) {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.removeTag(tag)
        }
    }

    fun updateBip21Description(newText: String) {
        if (newText.isEmpty()) {
            Logger.warn("Empty")
        }
        walletRepo.updateBip21Description(newText)
    }

    fun updateBalanceInput(newText: String) {
        walletRepo.updateBalanceInput(newText = newText)
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
    val balanceInput: String = "",
    val balanceDetails: BalanceDetails? = null,
    val onchainAddress: String = "",
    val bolt11: String = "",
    val bip21: String = "",
    val nodeStatus: NodeStatus? = null,
    val nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped,
    val peers: List<LnPeer> = emptyList(),
    val channels: List<ChannelDetails> = emptyList(),
    val isRefreshing: Boolean = false,
    val receiveOnSpendingBalance: Boolean = true,
    val bip21AmountSats: ULong? = null,
    val bip21Description: String = "",
    val selectedTags: List<String> = listOf(),
)

sealed interface WalletViewModelEffects {
    data object NavigateGeoBlockScreen : WalletViewModelEffects
}

sealed interface RestoreState {
    data object NotRestoring : RestoreState
    data object RestoringWallet : RestoreState
    data object RestoringBackups : RestoreState
    data object BackupRestoreCompleted : RestoreState

    fun isRestoring() = this is RestoringWallet || this is RestoringBackups
}
