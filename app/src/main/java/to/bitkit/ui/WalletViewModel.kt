package to.bitkit.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.lightningdevkit.ldknode.generateEntropyMnemonic
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.entities.OrderEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.env.Tag.DEV
import to.bitkit.env.Tag.LDK
import to.bitkit.env.Tag.LSP
import to.bitkit.ext.first
import to.bitkit.ext.toast
import to.bitkit.models.LnPeer
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.ScannedData
import to.bitkit.models.ScannedOptions
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.shared.ServiceError
import to.bitkit.ui.screens.wallet.activity.testActivityItems
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
    private val appStorage: AppStorage,
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

    private var _onchainAddress: String
        get() = appStorage.onchainAddress
        set(value) = let { appStorage.onchainAddress = value }

    private var _bolt11: String
        get() = appStorage.bolt11
        set(value) = let { appStorage.bolt11 = value }

    private var _bip21: String
        get() = appStorage.bip21
        set(value) = let { appStorage.bip21 = value }

    private var _scannedData: ScannedData? = null

    var showSendSheet by mutableStateOf(false)

    var activityItems = mutableStateOf<List<PaymentDetails>?>(null)
        private set
    var latestActivityItems = mutableStateOf<List<PaymentDetails>?>(null)
        private set
    var latestLightningActivityItems = mutableStateOf<List<PaymentDetails>?>(null)
        private set
    var latestOnchainActivityItems = mutableStateOf<List<PaymentDetails>?>(null)
        private set

    private val walletExists: Boolean get() = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    private var _onEvent: ((Event) -> Unit)? = null

    fun setOnEvent(onEvent: (Event) -> Unit) {
        _onEvent = onEvent
    }

    fun start(walletIndex: Int = 0) {
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
                    it.setup(walletIndex, mnemonic)
                    it.start { event ->
                        syncState()
                        _onEvent?.invoke(event)
                    }
                }
            }.onFailure { Log.e(APP, "Init error", it) }

            _nodeLifecycleState = NodeLifecycleState.Running
            syncState()

            launch { refreshBip21() }

            // Always sync on start but don't need to wait for this
            sync()

            launch(bgDispatcher) { observeDbConfig() }
            launch(bgDispatcher) { syncDbOrders() }
        }
    }

    private suspend fun observeDbConfig() {
        db.configDao().getAll().collect { Log.i(APP, "Database config sync: $it") }
    }

    private suspend fun syncDbOrders() {
        db.ordersDao().getAll().filter { it.isNotEmpty() }.collect { dbOrders ->
            Log.d(APP, "Database orders sync: $dbOrders")

            runCatching { blocktankService.getOrders(dbOrders.map { it.id }) }
                .onFailure { Log.e(APP, "Failed to fetch orders from Blocktank.", it) }
                .onSuccess { btOrders ->
                    updateContentState { it.copy(orders = btOrders) }
                }
        }
    }

    private var isSyncingWallet = false

    private fun sync() {
        viewModelScope.launch(bgDispatcher) bg@{
            syncState()

            if (isSyncingWallet) {
                Log.w(APP, "Sync already in progress, waiting for existing sync.")
                while (isSyncingWallet) {
                    delay(500)
                }
                return@bg
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
    }

    private fun syncState() {
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

        viewModelScope.launch {
            // Load balances async
            launch {
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
            // Load activity items async
            launch {
                lightningService.payments?.let { payments ->
                    val sorted = payments.sortedByDescending { it.latestUpdateTimestamp }

                    // TODO: eventually load other activity types from storage
                    val allActivity = mutableListOf<PaymentDetails>()
                    val latestLightningActivity = mutableListOf<PaymentDetails>()
                    val latestOnchainActivity = mutableListOf<PaymentDetails>()

                    sorted.forEach { details ->
                        when (details.kind) {
                            is PaymentKind.Onchain -> {
                                allActivity.add(details)
                                latestOnchainActivity.add(details)
                            }

                            is PaymentKind.Bolt11 -> {
                                if (!(details.status == PaymentStatus.PENDING && details.direction == PaymentDirection.INBOUND)) {
                                    allActivity.add(details)
                                    latestLightningActivity.add(details)
                                }
                            }

                            is PaymentKind.Spontaneous -> {
                                allActivity.add(details)
                                latestLightningActivity.add(details)
                            }

                            is PaymentKind.Bolt11Jit -> Unit
                            is PaymentKind.Bolt12Offer -> Unit
                            is PaymentKind.Bolt12Refund -> Unit
                        }
                    }

                    // TODO: append activity items from lightning balances

                    val limitLatest = 3
                    activityItems.value = allActivity
                    latestActivityItems.value = allActivity.take(limitLatest)
                    latestLightningActivityItems.value = latestLightningActivity.take(limitLatest)
                    latestOnchainActivityItems.value = latestOnchainActivity.take(limitLatest)

                }
            }
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            sync()
            launch(bgDispatcher) {
                db.ordersDao().getAll().filter { it.isNotEmpty() }.first().let { dbOrders ->
                    runCatching { blocktankService.getOrders(dbOrders.map { it.id }) }
                        .onFailure { Log.e(APP, "Failed to fetch orders from Blocktank.", it) }
                        .onSuccess { btOrders ->
                            updateContentState { it.copy(orders = btOrders) }
                        }
                }
            }
        }
    }

    fun registerForNotifications() {
        viewModelScope.launch(bgDispatcher) {
            val token = firebaseMessaging.token.await()

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
        get() = lightningService.channels?.sumOf { it.inboundCapacityMsat / 1000u }

    private suspend fun refreshBip21() {
        if (_onchainAddress.isEmpty()) {
            _onchainAddress = lightningService.newAddress()
        } else {
            // TODO: check if onchain has been used and generate new on if it has
        }

        _bip21 = "bitcoin:$_onchainAddress"

        val hasChannels = _uiState.value.asContent()?.channels?.isNotEmpty() == true
        if (!hasChannels) {
            _bolt11 = ""
        }

        if (_bolt11.isNotEmpty()) {
            _bip21 += "?lightning=$_bolt11"
        }

        // TODO: check current bolt11 for expiry and/or if it's been used

        val hasIncomingLightingCapacity = (incomingLightningCapacitySats ?: 0u) > 0u
        if (hasChannels && hasIncomingLightingCapacity) {
            // Append lightning invoice if we have incoming capacity
            _bolt11 = lightningService.receive(description = "Bitkit")

            _bip21 = "bitcoin:$_onchainAddress?lightning=$_bolt11"
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

    fun createInvoice(amountSats: ULong, description: String = "Bitkit", expirySeconds: UInt = 7200u): String {
        return runBlocking { lightningService.receive(amountSats, description, expirySeconds) }
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
            runCatching {
                lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            }.onFailure {
                Log.e(LDK, "Failed to close channel", it)
            }
            syncState()
        }
    }

    fun wipeStorage() {
        if (Env.network != Network.REGTEST) {
            toast("Can only nuke on regtest.")
            return
        }
        viewModelScope.launch {
            runCatching {
                if (_nodeLifecycleState.isRunningOrStarting()) {
                    stopLightningNode()
                }
                lightningService.wipeStorage(0)
                keychain.wipe()
            }.onSuccess {
                start() // restart UI
            }.onFailure {
                runOnUiThread { toast("Failed to wipe: $it") }
            }
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

    fun debugFcmToken() {
        viewModelScope.launch(bgDispatcher) {
            val token = firebaseMessaging.token.await()
            Log.d(DEV, "FCM registration token: $token")
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

    fun debugMnemonic() {
        viewModelScope.launch {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            Log.d(DEV, "Mnemonic: \n$mnemonic")
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
                        syncState()
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

    fun debugActivityItems() {
        val testItems = testActivityItems.toList()
        activityItems.value = testItems
        latestActivityItems.value = testItems.take(3)
        latestLightningActivityItems.value = testItems.filter { it.kind is PaymentKind.Bolt11 }.take(3)
        latestOnchainActivityItems.value = testItems.filter { it.kind is PaymentKind.Onchain }.take(3)
        toast("Test activity items added")
    }

    fun debugTransactionSheet() {
        NewTransactionSheetDetails.save(
            appContext,
            NewTransactionSheetDetails(
                type = NewTransactionSheetType.LIGHTNING,
                direction = NewTransactionSheetDirection.RECEIVED,
                sats = 123456789,
            )
        )
        toast("Transaction cached. Restart app to see sheet.")
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
    Stopping;

    fun isStoppedOrStopping() = this == Stopped || this == Stopping
    fun isRunningOrStarting() = this == Running || this == Starting
}
// endregion
