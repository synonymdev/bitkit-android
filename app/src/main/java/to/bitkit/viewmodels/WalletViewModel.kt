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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.NodeStatus
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.LnPeer
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    val lightningState = lightningRepo.lightningState
    val walletState = walletRepo.walletState
    val balanceState = walletRepo.balanceState

    // Local UI state
    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    var isRestoringWallet by mutableStateOf(false)
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

    private fun collectStates() { //This is necessary to avoid a bigger refactor in all application
        viewModelScope.launch(bgDispatcher) {
            walletState.collect { state ->
                walletExists = state.walletExists
                isRestoringWallet = state.isRestoringWallet
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

    fun setRestoringWalletState(isRestoringWallet: Boolean) {
        walletRepo.setRestoringWalletState(isRestoring = isRestoringWallet)
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
            lightningRepo.sync()
                .onFailure { error ->
                    Logger.error("Failed to sync: ${error.message}", error)
                    ToastEventBus.send(error)
                }
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            walletRepo.syncNodeAndWallet()
                .onSuccess {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
                .onFailure { error ->
                    ToastEventBus.send(error)
                    _uiState.update { it.copy(isRefreshing = false) }
                }
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

    fun send(bolt11: String) {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.payInvoice(bolt11)
                .onFailure { error ->
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Error sending",
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

    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = 86_400u, // 1 day
    ): String {
        val result = lightningRepo.createInvoice(
            amountSats,
            description.ifBlank { Env.DEFAULT_INVOICE_MESSAGE },
            expirySeconds
        )
        return result.getOrThrow()
    }

    fun openChannel() {
        viewModelScope.launch(bgDispatcher) {
            val peer = lightningRepo.getPeers()?.firstOrNull()

            if (peer == null) {
                ToastEventBus.send(
                    type = Toast.ToastType.INFO,
                    title = "Channel Pending",
                    description = "No peer connected to open channel"
                )
                return@launch
            }

            lightningRepo.openChannel(peer, 50000u, 10000u)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = "Channel Pending",
                        description = "Awaiting next block..."
                    )
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.closeChannel(
                channel.userChannelId,
                channel.counterpartyNodeId
            ).onFailure {
                ToastEventBus.send(it)
            }
        }
    }

    fun wipeStorage() {
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
    fun manualRegisterForNotifications() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.registerForNotifications()
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = "Success",
                        description = "Registered for notifications."
                    )
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun debugDb() {
        viewModelScope.launch {
            walletRepo.getDbConfig().collect {
                Logger.debug("${it.count()} entities in DB: $it")
            }
        }
    }

    fun debugFcmToken() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.getFcmToken().onSuccess { token ->
                Logger.debug("FCM registration token: $token")
            }
        }
    }

    fun debugKeychain() {
        viewModelScope.launch {
            val key = "test"
            val value = "testValue"
            walletRepo.debugKeychain(key, value).onSuccess { existingValue ->
                Logger.debug("Keychain entry: $key = $existingValue")
            }
        }
    }

    fun debugMnemonic() {
        viewModelScope.launch {
            walletRepo.getMnemonic().onSuccess { mnemonic ->
                Logger.debug(mnemonic)
            }
        }
    }

    fun debugLspNotifications() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.testNotification().onFailure { e ->
                Logger.error("Error in LSP notification test:", e)
            }
        }
    }

    fun debugBlocktankInfo() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.getBlocktankInfo().onSuccess { info ->
                Logger.debug("Blocktank info: $info")
            }.onFailure { e ->
                Logger.error("Error getting Blocktank info:", e)
            }
        }
    }

    fun debugTransactionSheet() {
        viewModelScope.launch {
            NewTransactionSheetDetails.save(
                appContext,
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.LIGHTNING,
                    direction = NewTransactionSheetDirection.RECEIVED,
                    sats = 123456789,
                )
            )
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = "Transaction sheet cached",
                description = "Restart app to see sheet."
            )
        }
    }

    fun stopIfNeeded() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.stop()
        }
    }

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
}

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
    data object NavigateGeoBlockScreen: WalletViewModelEffects
}
