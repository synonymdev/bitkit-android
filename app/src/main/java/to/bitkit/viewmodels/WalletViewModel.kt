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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BalanceState
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
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private val _balanceState = MutableStateFlow(walletRepo.getBalanceState())
    val balanceState = _balanceState.asStateFlow()

    var walletExists by mutableStateOf(walletRepo.walletExists())
        private set

    init {
        collectNodeLifecycleState()
    }

    var isRestoringWallet by mutableStateOf(false)

    fun setWalletExistsState() {
        walletExists = walletRepo.walletExists()
    }

    fun setInitNodeLifecycleState() {
        _uiState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Initializing) }
    }

    fun start(walletIndex: Int = 0) {
        if (!walletExists) return
        if (_uiState.value.nodeLifecycleState.isRunningOrStarting()) return

        viewModelScope.launch(bgDispatcher) {
            if (_uiState.value.nodeLifecycleState != NodeLifecycleState.Initializing) {
                // Initializing means it's a wallet restore or create so we need to show the loading view
                _uiState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Starting) }
            }

            syncState()

            lightningRepo.start(walletIndex) { event ->
                syncState()
                refreshBip21ForEvent(event)
            }.onFailure { error ->
                Logger.error("Node startup error", error)
                throw error
            }.onSuccess {
                syncState()

                lightningRepo.connectToTrustedPeers().onFailure { e ->
                    Logger.error("Failed to connect to trusted peers", e)
                }

                // Refresh BIP21 and sync
                launch(bgDispatcher) { refreshBip21() }
                launch(bgDispatcher) { sync() }
                launch(bgDispatcher) { registerForNotificationsIfNeeded() }
                launch(bgDispatcher) { observeDbConfig() }
            }
        }
    }

    private suspend fun refreshBip21ForEvent(event: Event) {
        when (event) {
            is Event.PaymentReceived, is Event.ChannelReady, is Event.ChannelClosed -> refreshBip21()
            else -> Unit
        }
    }

    suspend fun observeLdkWallet() {
        lightningRepo.getSyncFlow()
            .filter { _uiState.value.nodeLifecycleState == NodeLifecycleState.Running }
            .collect {
                runCatching { sync() }
                Logger.verbose("App state synced with ldk-node.")
            }
    }

    private fun collectNodeLifecycleState() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.nodeLifecycleState.collect { currentState ->
                _uiState.update { it.copy(nodeLifecycleState = currentState) }
            }
        }
    }

    private suspend fun observeDbConfig() {
        walletRepo.getDbConfig().collect {
            Logger.info("Database config sync: $it")
        }
    }

    private var isSyncingWallet = false

    private suspend fun sync() {
        syncState()

        if (isSyncingWallet) {
            Logger.warn("Sync already in progress, waiting for existing sync.")
            return
        }

        isSyncingWallet = true
        syncState()

        lightningRepo.sync()
            .onSuccess {
                isSyncingWallet = false
                syncState()
            }
            .onFailure { e ->
                isSyncingWallet = false
                throw e
            }
    }

    private fun syncState() {
        _uiState.update {
            it.copy(
                nodeId = lightningRepo.getNodeId().orEmpty(),
                onchainAddress = walletRepo.getOnchainAddress(),
                bolt11 = walletRepo.getBolt11(),
                bip21 = walletRepo.getBip21(),
                nodeStatus = lightningRepo.getStatus(),
                peers = lightningRepo.getPeers().orEmpty(),
                channels = lightningRepo.getChannels().orEmpty(),
            )
        }

        viewModelScope.launch(bgDispatcher) { syncBalances() }
    }

    private fun syncBalances() {
        lightningRepo.getBalances()?.let { balance ->
            _uiState.update { it.copy(balanceDetails = balance) }
            val totalSats = balance.totalLightningBalanceSats + balance.totalOnchainBalanceSats

            val newBalance = BalanceState(
                totalOnchainSats = balance.totalOnchainBalanceSats,
                totalLightningSats = balance.totalLightningBalanceSats,
                totalSats = totalSats,
            )
            _balanceState.update { newBalance }
            walletRepo.saveBalanceState(newBalance)

            if (totalSats > 0u) {
                viewModelScope.launch {
                    walletRepo.setShowEmptyState(false)
                }
            }
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            sync()
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                sync()
            } catch (e: Throwable) {
                ToastEventBus.send(e)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun registerForNotificationsIfNeeded() {
        walletRepo.registerForNotifications()
            .onFailure { e ->
                Logger.error("Failed to register device for notifications", e)
            }
    }

    private val incomingLightningCapacitySats: ULong?
        get() = lightningRepo.getChannels()?.sumOf { it.inboundCapacityMsat / 1000u }

    suspend fun refreshBip21() {
        Logger.debug("Refreshing bip21", context = "WalletViewModel")

        // Check current address or generate new one
        val currentAddress = walletRepo.getOnchainAddress()
        if (currentAddress.isEmpty()) {
            lightningRepo.newAddress()
                .onSuccess { address -> walletRepo.setOnchainAddress(address) }
                .onFailure { error -> Logger.error("Error generating new address", error) }
        } else {
            // Check if current address has been used
            lightningRepo.checkAddressUsage(currentAddress)
                .onSuccess { hasTransactions ->
                    if (hasTransactions) {
                        // Address has been used, generate a new one
                        lightningRepo.newAddress()
                            .onSuccess { address -> walletRepo.setOnchainAddress(address) }
                    }
                }
        }

        updateBip21Invoice()
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
                    _uiState.update {
                        it.copy(peers = lightningRepo.getPeers().orEmpty())
                    }
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
                .onSuccess { syncState() }
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
        description: String = "",
        generateBolt11IfAvailable: Boolean = true
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(bip21AmountSats = amountSats, bip21Description = description) }

            val hasChannels = lightningRepo.hasChannels()

            if (hasChannels && generateBolt11IfAvailable) {
                lightningRepo.createInvoice(
                    amountSats = _uiState.value.bip21AmountSats,
                    description = _uiState.value.bip21Description
                ).onSuccess { bolt11 ->
                    walletRepo.setBolt11(bolt11)
                }
            } else {
                walletRepo.setBolt11("")
            }

            val newBip21 = walletRepo.buildBip21Url(
                bitcoinAddress = walletRepo.getOnchainAddress(),
                amountSats = _uiState.value.bip21AmountSats,
                message = description.ifBlank { Env.DEFAULT_INVOICE_MESSAGE },
                lightningInvoice = walletRepo.getBolt11()
            )
            walletRepo.setBip21(newBip21)
            walletRepo.saveInvoiceWithTags(bip21Invoice = newBip21, tags = _uiState.value.selectedTags)
            _uiState.update { it.copy(selectedTags = listOf()) }
            syncState()
        }
    }

    fun updateReceiveOnSpending() {
        _uiState.update { it.copy(receiveOnSpendingBalance = !it.receiveOnSpendingBalance) }
        updateBip21Invoice(
            amountSats = _uiState.value.bip21AmountSats,
            description = _uiState.value.bip21Description,
            generateBolt11IfAvailable = _uiState.value.receiveOnSpendingBalance
        )
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
            ).onSuccess {
                syncState()
            }.onFailure {
                ToastEventBus.send(it)
            }
        }
    }

    fun wipeStorage() {
        viewModelScope.launch(bgDispatcher) {
            if (lightningRepo.nodeLifecycleState.value.isRunningOrStarting()) {
                stopLightningNode()
            }
            walletRepo.wipeWallet()
                .onSuccess {
                    lightningRepo.wipeStorage(walletIndex = 0)
                    setWalletExistsState()
                }.onFailure {
                    ToastEventBus.send(it)
                }
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        walletRepo.createWallet(bip39Passphrase).onFailure {
            ToastEventBus.send(it)
        }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        walletRepo.restoreWallet(
            mnemonic = mnemonic,
            bip39Passphrase = bip39Passphrase
        ).onFailure {
            ToastEventBus.send(it)
        }
    }

    // region debug
    fun manualRegisterForNotifications() {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.registerForNotifications()
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

    fun manualNewAddress() {
        viewModelScope.launch {
            lightningRepo.newAddress().onSuccess { address ->
                walletRepo.setOnchainAddress(address)
                syncState()
            }.onFailure { ToastEventBus.send(it) }
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
            walletRepo.getFcmToken().onSuccess { token ->
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
            walletRepo.testNotification().onFailure { e ->
                Logger.error("Error in LSP notification test:", e)
            }
        }
    }

    fun debugBlocktankInfo() {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.getBlocktankInfo().onSuccess { info ->
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

    private suspend fun stopLightningNode() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.stop().onSuccess {
                syncState()
            }
        }
    }

    fun addTagToSelected(newTag: String) {
        _uiState.update {
            it.copy(
                selectedTags = (it.selectedTags + newTag).distinct()
            )
        }
    }

    fun removeTag(tag: String) {
        _uiState.update {
            it.copy(
                selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }
            )
        }
    }
}

data class MainUiState(
    val nodeId: String = "",
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
