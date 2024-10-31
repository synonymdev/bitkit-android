package to.bitkit.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.generateEntropyMnemonic
import to.bitkit.data.AppDb
import to.bitkit.data.entities.OrderEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.env.Tag.DEV
import to.bitkit.env.Tag.LDK
import to.bitkit.env.Tag.LSP
import to.bitkit.env.Tag.PERF
import to.bitkit.ext.first
import to.bitkit.ext.toast
import to.bitkit.models.LnPeer
import to.bitkit.models.ScannedData
import to.bitkit.models.ScannedOptions
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.shared.ServiceError
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val db: AppDb,
    private val keychain: Keychain,
    private val blocktankService: BlocktankService,
    private val lightningService: LightningService,
    private val firebaseMessaging: FirebaseMessaging,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _contentState get() = _uiState.value.asContent() ?: error("UI not ready..")

    private var _nodeLifecycleState = NodeLifecycleState.Stopped
    private var _onchainAddress: String = ""
    private var _bolt11: String = ""
    private var _bip21: String = ""
    private var _scannedData: ScannedData? = null

    var showSendSheet by mutableStateOf(false)

    // TODO subscribe to value?
    private val walletExists: Boolean get() = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    fun start() {
        if (!walletExists) {
            _uiState.value = MainUiState.NoWallet
            return
        }
        viewModelScope.launch {
            // TODO move to lightningService.setup
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound

            _nodeLifecycleState = NodeLifecycleState.Starting
            syncState()
            runCatching {
                lightningService.let {
                    it.setup(walletIndex = 0, mnemonic)
                    it.start { event ->
                        syncState()
                        onLdkEvent(event)
                    }
                }
            }.onFailure { Log.e(APP, "Init error", it) }

            _nodeLifecycleState = NodeLifecycleState.Running
            syncState()

            launch { refreshBip21() }
            launch(bgDispatcher) { sync() }

            launch { db.configDao().getAll().collect { Log.i(APP, "Database config sync: $it") } }
            launch {
                db.ordersDao().getAll().filter { it.isNotEmpty() }.collect { dbOrders ->
                    Log.d(APP, "Database orders sync: $dbOrders")

                    runCatching { blocktankService.getOrders(dbOrders.map { it.id }) }
                        .onFailure { Log.e(APP, "Failed to fetch orders from Blocktank.", it) }
                        .onSuccess { btOrders ->
                            updateContentState { it.copy(orders = btOrders) }
                        }
                }
            }
        }
    }

    private suspend fun sync() {
        lightningService.sync()
        syncState()
    }

    private fun syncState() {
        val startTime = System.currentTimeMillis()

        _uiState.value = MainUiState.Content(
            nodeId = lightningService.nodeId.orEmpty(),
            onchainAddress = _onchainAddress,
            bolt11 = _bolt11,
            bip21 = _bip21,
            nodeStatus = lightningService.status,
            nodeLifecycleState = _nodeLifecycleState,
            peers = lightningService.peers.orEmpty(),
            channels = lightningService.channels.orEmpty(),
            orders = _uiState.value.asContent()?.orders.orEmpty(),
        )

        // Load balances async
        viewModelScope.launch {
            launch(coroutineContext) {
                lightningService.balances?.let { b ->
                    updateContentState {
                        it.copy(
                            totalOnchainSats = b.totalOnchainBalanceSats,
                            totalLightningSats = b.totalLightningBalanceSats,
                            totalBalanceSats = b.totalLightningBalanceSats + b.totalOnchainBalanceSats,
                            balanceDetails = b,
                        )
                    }
                }
            }
        }

        val endTime = System.currentTimeMillis()
        val duration = (endTime - startTime) / 1000.0
        Log.v(PERF, "UI state updated in $duration sec")
    }

    private suspend fun onLdkEvent(event: Event) = runOnUiThread {
        try {
            when (event) {
                is Event.PaymentReceived -> toast("Received ${event.amountMsat / 1000u} sats")
                is Event.ChannelPending -> toast("Channel Pending")
                is Event.ChannelReady -> toast("Channel Opened")
                is Event.ChannelClosed -> toast("Channel Closed")
                is Event.PaymentSuccessful -> Unit
                is Event.PaymentClaimable -> Unit
                is Event.PaymentFailed -> Unit
            }
        } catch (e: Exception) {
            Log.e(LDK, "Ldk event handler error", e)
        }
    }

    fun registerForNotifications(fcmToken: String? = null) {
        viewModelScope.launch(bgDispatcher) {
            val token = fcmToken ?: firebaseMessaging.token.await()

            val result = runCatching { blocktankService.registerDevice(token) }
                .onFailure { Log.e(LSP, "Failed to register device with LSP", it) }
            runOnUiThread {
                when (result.isSuccess) {
                    true -> toast("Device registered with LSP Notifications Server.")
                    else -> toast("Failed to register device with LSP Notifications Server.")
                }
            }
        }
    }

    private val incomingLightningCapacitySats: ULong?
        get() {
            return _uiState.value.asContent()?.let {
                var capacity: ULong = 0u
                it.channels.forEach { channel ->
                    capacity += channel.inboundCapacityMsat / 1000u
                }
                capacity
            }
        }

    private suspend fun refreshBip21() {
        if (_onchainAddress.isEmpty()) {
            _onchainAddress = lightningService.newAddress()
        } else {
            // TODO: check if onchain has been used and generate new on if it has
        }

        _bip21 = "bitcoin:$_onchainAddress"

        if (_bolt11.isNotEmpty()) {
            _bip21 += "?lightning=$_bolt11"
        }

        // TODO: check current bolt11 for expiry and/or if it's been used

        val hasChannels = _uiState.value.asContent()?.channels?.isNotEmpty() == true
        val hasIncomingLightingCapacity = incomingLightningCapacitySats?.let { it > 0u } == true
        if (hasChannels && hasIncomingLightingCapacity) {
            // Append lightning invoice if we have incoming capacity
            _bolt11 = lightningService.receive(description = "Bitkit")

            _bip21 = "bitcoin:$_onchainAddress?lightning=$_bolt11"
        }
    }

    fun connectPeer(peer: LnPeer) {
        viewModelScope.launch {
            lightningService.connectPeer(peer)
            runOnUiThread { toast("Peer connected.") }
            updateContentState {
                it.copy(peers = lightningService.peers.orEmpty())
            }
        }
    }

    fun disconnectPeer(peer: LnPeer) {
        viewModelScope.launch {
            lightningService.disconnectPeer(peer)
            runOnUiThread { toast("Peer disconnected.") }
            updateContentState {
                it.copy(peers = lightningService.peers.orEmpty())
            }
        }
    }

    fun send(bolt11: String) {
        viewModelScope.launch(bgDispatcher) {
            runCatching { lightningService.send(bolt11) }
                .onSuccess { syncState() }
                .onFailure { runOnUiThread { toast("Error sending: $it") } }
        }
    }

    fun createInvoice(): String {
        return runBlocking { lightningService.receive(112u, "description", 7200u) }
    }

    fun onPasteFromClipboard(data: String) {
        if (data.isBlank()) {
            Log.e(APP, "No data in clipboard")
            return
        }
        _scannedData = runCatching { ScannedData(data) }
            .onFailure {
                Log.e(APP, "Failed to read data from clipboard", it)
                toast("${it.message}")
            }
            .getOrNull()

        Log.d(APP, "Pasted data: $_scannedData")

        // TODO: nav to next view instead
        _scannedData?.options?.first?.let {
            when (it) {
                is ScannedOptions.Onchain -> {
                    toast("Onchain address: ${it.address}")
                }

                is ScannedOptions.Bolt11 -> {
                    send(it.invoice)
                }
            }
        }
    }

    fun onSendManually(data: String) {
        _scannedData = runCatching { ScannedData(data) }.getOrNull()
        _scannedData = runCatching { ScannedData(data) }
            .onFailure {
                Log.e(APP, "Failed to read data from text field", it)
                toast("${it.message}")
            }
            .getOrNull()
        // TODO: nav to next view
        toast("Input: $data. Coming soon.")
    }

    fun onScanSuccess(data: String) {
        _scannedData = runCatching { ScannedData(data) }
            .onFailure {
                Log.e(APP, "Failed to read data from scanner", it)
                toast("${it.message}")
            }
            .getOrNull()
        Log.d(APP, "Scanned data: $data")
        showSendSheet = true
        // TODO: handle
    }

    fun openChannel() {
        val peer = _contentState.peers.first ?: error("No peer connected to open channel.")
        viewModelScope.launch(bgDispatcher) {
            runOnUiThread { toast("Channel Pending.") }
            lightningService.openChannel(peer, 50000u, 10000u)
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch(bgDispatcher) {
            val result = runCatching {
                lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            }
            syncState()
            runOnUiThread { toast(if (result.isSuccess) "Channel Closed." else "Unable to Close Channel.") }
        }
    }

    private fun updateContentState(update: (MainUiState.Content) -> MainUiState.Content) {
        val stateValue = this._uiState.value
        if (stateValue is MainUiState.Content) {
            this._uiState.value = update(stateValue)
        }
    }

    private suspend fun runOnUiThread(block: suspend CoroutineScope.() -> Unit) = withContext(uiThread, block)

    // region debug
    fun debugDb() {
        viewModelScope.launch {
            db.configDao().getAll().collect {
                Log.d(DEV, "${it.count()} entities in DB: $it")
            }
        }
    }

    fun debugKeychain() {
        viewModelScope.launch {
            val key = "test"
            if (keychain.exists(key)) {
                val value = keychain.loadString(key)
                Log.d(DEV, "Keychain entry: $key = $value")
                keychain.delete(key)
            }
            keychain.saveString(key, "testValue")
        }
    }

    fun debugWipe() {
        if (Env.network != Network.REGTEST) {
            toast("Can only nuke on regtest.")
            return
        }
        viewModelScope.launch {
            runCatching {
                lightningService.stop()
                lightningService.wipeStorage(0)
                keychain.wipe()
            }.onSuccess {
                start() // restart UI
            }.onFailure {
                runOnUiThread { toast("Failed to wipe: $it") }
            }
        }
    }

    fun debugLspNotifications() {
        viewModelScope.launch(bgDispatcher) {
            val token = FirebaseMessaging.getInstance().token.await()
            blocktankService.testNotification(token)
        }
    }

    fun debugBlocktankInfo() {
        viewModelScope.launch(bgDispatcher) { blocktankService.getInfo() }
    }

    fun debugSync() {
        _uiState.value = MainUiState.Loading
        viewModelScope.launch {
            delay(500)
            syncState()
            db.ordersDao().getAll().filter { it.isNotEmpty() }.first().let { dbOrders ->
                runCatching { blocktankService.getOrders(dbOrders.map { it.id }) }
                    .onFailure { Log.e(APP, "Failed to fetch orders from Blocktank.", it) }
                    .onSuccess { btOrders ->
                        updateContentState { it.copy(orders = btOrders) }
                    }
            }
        }
    }

    fun debugBtOrdersSync() {
        val orderIds = _contentState.orders.map { it.id }.takeIf { it.isNotEmpty() } ?: error("No orders to sync.")
        viewModelScope.launch {
            runCatching { blocktankService.getOrders(orderIds) }
                .onFailure { runOnUiThread { toast("Failed to fetch orders from Blocktank.") } }
                .onSuccess { orders ->
                    runOnUiThread { toast("Orders synced") }
                    updateContentState {
                        it.copy(orders = orders)
                    }
                }
        }
    }

    fun debugBtCreateOrder(sats: Int) {
        viewModelScope.launch {
            val result = runCatching { blocktankService.createOrder(spendingBalanceSats = sats, 6) }
                .onSuccess { order ->
                    launch {
                        db.ordersDao().upsert(OrderEntity(order.id))
                        Log.d(APP, "Order ID saved to DB: ${order.id}")
                    }
                }
            runOnUiThread { toast(if (result.isSuccess) "Order created" else "Failed to create order") }
        }
    }

    fun debugBtPayOrder(order: BtOrder) {
        viewModelScope.launch {
            runCatching { lightningService.send(order.payment.onchain.address, order.feeSat) }
                .onFailure { runOnUiThread { toast("Failed to pay for order ${it.message}") } }
                .onSuccess { txId ->
                    runOnUiThread { toast("Payment sent $txId") }
                    // TODO: watch this order for updates
                    launch {
                        Log.d(DEV, "Syncing orders")
                        delay(1500)
                        debugBtOrdersSync()
                    }
                }
        }
    }

    fun debugBtManualOpenChannel(order: BtOrder) {
        viewModelScope.launch {
            runCatching { blocktankService.openChannel(order.id) }
                .onFailure { runOnUiThread { toast("Manual open error: ${it.message}") } }
                .onSuccess { runOnUiThread { toast("Manual open success") } }
        }
    }
    // endregion

    fun createWallet(bip39Passphrase: String) {
        _uiState.value = MainUiState.Loading

        viewModelScope.launch {
            val mnemonic = generateEntropyMnemonic()
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
            if (bip39Passphrase.isNotBlank()) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            start()
        }
    }

    fun restoreWallet(bip39Passphrase: String, bip39Mnemonic: String) {
        _uiState.value = MainUiState.Loading

        viewModelScope.launch {
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, bip39Mnemonic)
            if (bip39Passphrase.isNotBlank()) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            start()
        }
    }

    fun stop() {
        viewModelScope.launch {
            _nodeLifecycleState = NodeLifecycleState.Stopping
            lightningService.stop()
            _nodeLifecycleState = NodeLifecycleState.Stopped
            syncState()
        }
    }
}

// region state
sealed class MainUiState {
    data object Loading : MainUiState()
    data object NoWallet : MainUiState()
    data class Content(
        val nodeId: String,
        val totalOnchainSats: ULong? = null,
        val totalLightningSats: ULong? = null,
        val totalBalanceSats: ULong? = null,
        val balanceDetails: BalanceDetails? = null,
        val onchainAddress: String,
        val bolt11: String,
        val bip21: String,
        val nodeStatus: NodeStatus?,
        val nodeLifecycleState: NodeLifecycleState,
        val peers: List<LnPeer>,
        val channels: List<ChannelDetails>,
        val orders: List<BtOrder>,
    ) : MainUiState()

    data class Error(
        val title: String = "Error Title",
        val message: String = "Error short description.",
    ) : MainUiState()

    fun asContent() = this as? Content
}

enum class NodeLifecycleState {
    Stopped,
    Starting,
    Running,
    Stopping,
}
// endregion
