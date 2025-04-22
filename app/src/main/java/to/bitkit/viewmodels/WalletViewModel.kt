package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.generateEntropyMnemonic
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.BalanceState
import to.bitkit.models.LnPeer
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.Toast
import to.bitkit.services.BlocktankNotificationsService
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
    private val appStorage: AppStorage,
    private val db: AppDb,
    private val keychain: Keychain,
    private val coreService: CoreService,
    private val blocktankNotificationsService: BlocktankNotificationsService,
    private val lightningService: LightningService,
    private val firebaseMessaging: FirebaseMessaging,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val settingsStore: SettingsStore,
    private val addressChecker: AddressChecker,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private val _balanceState = MutableStateFlow(appStorage.loadBalance() ?: BalanceState())
    val balanceState = _balanceState.asStateFlow()

    private var _nodeLifecycleState: NodeLifecycleState = NodeLifecycleState.Stopped

    var walletExists by mutableStateOf(keychain.exists(Keychain.Key.BIP39_MNEMONIC.name))
        private set

    var isRestoringWallet by mutableStateOf(false)

    fun setWalletExistsState() {
        walletExists = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)
    }

    fun setInitNodeLifecycleState() {
        _nodeLifecycleState = NodeLifecycleState.Initializing
        _uiState.update { it.copy(nodeLifecycleState = _nodeLifecycleState) }
    }

    private var _onchainAddress: String
        get() = appStorage.onchainAddress
        set(value) = let { appStorage.onchainAddress = value }

    private var _bolt11: String
        get() = appStorage.bolt11
        set(value) = let { appStorage.bolt11 = value }

    private var _bip21: String
        get() = appStorage.bip21
        set(value) = let { appStorage.bip21 = value }

    fun start(walletIndex: Int = 0) {
        if (!walletExists) return
        if (_nodeLifecycleState.isRunningOrStarting()) return

        viewModelScope.launch {
            if (_nodeLifecycleState != NodeLifecycleState.Initializing) {
                // Initializing means it's a wallet restore or create so we need to show the loading view
                _nodeLifecycleState = NodeLifecycleState.Starting
            }

            syncState()
            try {
                lightningService.setup(walletIndex)
                lightningService.start { event ->
                    syncState()
                    ldkNodeEventBus.emit(event)

                    refreshBip21ForEvent(event)
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(nodeLifecycleState = NodeLifecycleState.ErrorStarting(error)) }
                Logger.error("Node startup error", error)
                throw error
            }

            _nodeLifecycleState = NodeLifecycleState.Running
            syncState()

            try {
                lightningService.connectToTrustedPeers()
            } catch (e: Throwable) {
                Logger.error("Failed to connect to trusted peers", e)
            }

            launch(bgDispatcher) { refreshBip21() }

            // Always sync on start but don't need to wait for this
            launch(bgDispatcher) { sync() }

            launch(bgDispatcher) { registerForNotificationsIfNeeded() }
            launch(bgDispatcher) { observeDbConfig() }
        }
    }

    private suspend fun refreshBip21ForEvent(event: Event) {
        when (event) {
            is Event.PaymentReceived, is Event.ChannelReady, is Event.ChannelClosed -> refreshBip21()
            else -> Unit
        }
    }

    suspend fun observeLdkWallet() {
        lightningService.syncFlow()
            .filter { _nodeLifecycleState == NodeLifecycleState.Running }
            .collect {
                runCatching { sync() }
                Logger.verbose("App state synced with ldk-node.")
            }
    }

    private suspend fun observeDbConfig() {
        db.configDao().getAll().collect { Logger.info("Database config sync: $it") }
    }

    private var isSyncingWallet = false

    private suspend fun sync() {
        syncState()

        if (isSyncingWallet) {
            Logger.warn("Sync already in progress, waiting for existing sync.")
            while (isSyncingWallet) {
                delay(500)
            }
            return
        }

        isSyncingWallet = true
        syncState()

        try {
            lightningService.sync()
        } catch (e: Exception) {
            isSyncingWallet = false
            throw e
        }

        isSyncingWallet = false
        syncState()
    }

    private fun syncState() {
        _uiState.update {
            it.copy(
                nodeId = lightningService.nodeId.orEmpty(),
                onchainAddress = _onchainAddress,
                bolt11 = _bolt11,
                bip21 = _bip21,
                nodeStatus = lightningService.status,
                nodeLifecycleState = _nodeLifecycleState,
                peers = lightningService.peers.orEmpty(),
                channels = lightningService.channels.orEmpty(),
            )
        }

        viewModelScope.launch(bgDispatcher) { syncBalances() }
    }

    private fun syncBalances() {
        lightningService.balances?.let { balance ->
            _uiState.update { it.copy(balanceDetails = balance) }
            val totalSats = balance.totalLightningBalanceSats + balance.totalOnchainBalanceSats

            val newBalance = BalanceState(
                totalOnchainSats = balance.totalOnchainBalanceSats,
                totalLightningSats = balance.totalLightningBalanceSats,
                totalSats = totalSats,
            )
            _balanceState.update { newBalance }
            appStorage.cacheBalance(newBalance)

            if (totalSats > 0u) {
                viewModelScope.launch {
                    settingsStore.setShowEmptyState(false)
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
        val token = firebaseMessaging.token.await()
        val cachedToken = keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)

        if (cachedToken == token) {
            Logger.debug("Skipped registering for notifications, current device token already registered")
            return
        }

        try {
            blocktankNotificationsService.registerDevice(token)
        } catch (e: Throwable) {
            Logger.error("Failed to register device for notifications", e)
        }
    }

    private val incomingLightningCapacitySats: ULong?
        get() = lightningService.channels?.sumOf { it.inboundCapacityMsat / 1000u }

    suspend fun refreshBip21() {
        Logger.debug("Refreshing bip21", context = "WalletViewModel")
        if (_onchainAddress.isEmpty()) {
            _onchainAddress = lightningService.newAddress()
        } else {
            // Check if current address has been used
            val addressInfo = addressChecker.getAddressInfo(_onchainAddress)
            val hasTransactions = addressInfo.chain_stats.tx_count > 0 || addressInfo.mempool_stats.tx_count > 0

            if (hasTransactions) {
                // Address has been used, generate a new one
                _onchainAddress = lightningService.newAddress()
            }
        }

        var newBip21 = "bitcoin:$_onchainAddress"

        val hasChannels = lightningService.channels?.isNotEmpty() == true
        if (hasChannels) {

            // TODO: check current bolt11 for expiry (fix payments not working with commented code & rm next line):
            _bolt11 = createInvoice(description = "Bitkit")

            // if (_bolt11.isEmpty()) {
            //     _bolt11 = createInvoice(description = "Bitkit")
            // } else {
            //     // Check if existing invoice has expired and create a new one if so
            //     decode(invoice = _bolt11).let { decoded ->
            //         if (decoded is Scanner.Lightning && decoded.invoice.isExpired) {
            //             _bolt11 = createInvoice(description = "Bitkit")
            //         }
            //     }
            // }
        } else {
            _bolt11 = ""
        }

        if (_bolt11.isNotEmpty()) {
            newBip21 += "?lightning=$_bolt11"
        }

        _bip21 = newBip21

        syncState()
    }

    fun disconnectPeer(peer: LnPeer) {
        viewModelScope.launch {
            lightningService.disconnectPeer(peer)
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "Success", description = "Peer disconnected.")
            _uiState.update {
                it.copy(peers = lightningService.peers.orEmpty())
            }
        }
    }

    fun send(bolt11: String) {
        viewModelScope.launch(bgDispatcher) {
            runCatching { lightningService.send(bolt11) }
                .onSuccess { syncState() }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Error sending",
                        description = it.message ?: "Unknown error"
                    )
                }
        }
    }

    fun updateBip21Invoice(
        amountSats: ULong? = null,
        description: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _bolt11 = createInvoice(amountSats = amountSats, description= description)
            val newBip21 = Bip21Utils.buildBip21Url(
                bitcoinAddress = lightningService.newAddress(),
                amountSats = amountSats,
                message = description.ifBlank { "Bitkit" },
                lightningInvoice = _bolt11
            )
            _bip21 = newBip21

            syncState()
        }
    }


    suspend fun createInvoice(
        amountSats: ULong? = null,
        description: String,
        expirySeconds: UInt = 86_400u, // 1 day
    ): String {
        return lightningService.receive(amountSats, description, expirySeconds)
    }

    fun openChannel() {
        viewModelScope.launch(bgDispatcher) {
            val peer = lightningService.peers?.firstOrNull() ?: error("No peer connected to open channel.")
            runCatching { lightningService.openChannel(peer, 50000u, 10000u) }
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
            runCatching { lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId) }
                .onFailure { ToastEventBus.send(it) }
            syncState()
        }
    }

    fun wipeStorage() {
        viewModelScope.launch {
            if (Env.network != Network.REGTEST) {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Error",
                    description = "Can only wipe on regtest."
                )
                return@launch
            }
            runCatching {
                if (_nodeLifecycleState.isRunningOrStarting()) {
                    stopLightningNode()
                }
                lightningService.wipeStorage(walletIndex = 0)
                appStorage.clear()
                keychain.wipe()
                coreService.activity.removeAll() // todo: extract to repo & syncState after, like in removeAllActivities
                setWalletExistsState()
            }.onFailure {
                ToastEventBus.send(it)
            }
        }
    }

    suspend fun createWallet(bip39Passphrase: String?) {
        val mnemonic = generateEntropyMnemonic()
        keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
        if (bip39Passphrase != null) {
            keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
        }
    }

    suspend fun restoreWallet(mnemonic: String, bip39Passphrase: String?) {
        keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
        if (bip39Passphrase != null) {
            keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
        }
    }

    // region debug
    fun manualRegisterForNotifications() {
        viewModelScope.launch(bgDispatcher) {
            val token = firebaseMessaging.token.await()
            runCatching { blocktankNotificationsService.registerDevice(token) }
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
            _onchainAddress = lightningService.newAddress()
            syncState()
        }
    }

    fun debugDb() {
        viewModelScope.launch {
            db.configDao().getAll().collect {
                Logger.debug("${it.count()} entities in DB: $it")
            }
        }
    }

    fun debugFcmToken() {
        viewModelScope.launch(bgDispatcher) {
            val token = firebaseMessaging.token.await()
            Logger.debug("FCM registration token: $token")
        }
    }

    fun debugKeychain() {
        viewModelScope.launch {
            val key = "test"
            if (keychain.exists(key)) {
                val value = keychain.loadString(key)
                Logger.debug("Keychain entry: $key = $value")
                keychain.delete(key)
            }
            keychain.saveString(key, "testValue")
        }
    }

    fun debugMnemonic() {
        viewModelScope.launch {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            Logger.debug(mnemonic)
        }
    }

    fun debugLspNotifications() {
        viewModelScope.launch(bgDispatcher) {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                blocktankNotificationsService.testNotification(token)
            } catch (e: Throwable) {
                Logger.error("Error in LSP notification test:", e)
                ToastEventBus.send(e)
            }
        }
    }

    fun debugBlocktankInfo() {
        viewModelScope.launch(bgDispatcher) {
            try {
                val info = coreService.blocktank.info(refresh = true)
                Logger.debug("Blocktank info: $info")
            } catch (e: Throwable) {
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
    // endregion

    fun stopIfNeeded() {
        if (_nodeLifecycleState.isStoppedOrStopping()) return

        viewModelScope.launch {
            stopLightningNode()
        }
    }

    private suspend fun stopLightningNode() {
        _nodeLifecycleState = NodeLifecycleState.Stopping
        lightningService.stop()
        _nodeLifecycleState = NodeLifecycleState.Stopped
        syncState()
    }
}

// region state
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
)

// endregion
