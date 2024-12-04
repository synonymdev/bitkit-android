package to.bitkit.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ChannelId
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.data.AppDb
import to.bitkit.data.AppStorage
import to.bitkit.data.entities.OrderEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.env.Tag.DEV
import to.bitkit.env.Tag.LSP
import to.bitkit.ext.first
import to.bitkit.models.BalanceState
import to.bitkit.models.LnPeer
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.ui.screens.wallets.activity.testActivityItems
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
    private val appStorage: AppStorage,
    private val db: AppDb,
    private val keychain: Keychain,
    private val blocktankService: BlocktankService,
    private val lightningService: LightningService,
    private val firebaseMessaging: FirebaseMessaging,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private val _balanceState = MutableStateFlow(BalanceState())
    val balanceState = _balanceState.asStateFlow()

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

    var activityItems = mutableStateOf<List<PaymentDetails>?>(null)
        private set

    private val walletExists: Boolean get() = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    private var onLdkEvent: ((Event) -> Unit)? = null

    fun setOnEvent(onEvent: (Event) -> Unit) {
        onLdkEvent = onEvent
    }

    fun start(walletIndex: Int = 0) {
        if (!walletExists) return
        if (_nodeLifecycleState.isRunningOrStarting()) return

        viewModelScope.launch {
            _nodeLifecycleState = NodeLifecycleState.Starting
            syncState()

            runCatching {
                lightningService.let {
                    it.setup(walletIndex)
                    it.start { event ->
                        syncState()
                        onLdkEvent?.invoke(event)
                    }
                }
            }.onFailure { Log.e(APP, "Init error", it) }

            _nodeLifecycleState = NodeLifecycleState.Running
            syncState()

            launch(bgDispatcher) { refreshBip21() }

            // Always sync on start but don't need to wait for this
            launch(bgDispatcher) { sync() }

            launch(bgDispatcher) { registerForNotificationsIfNeeded() }
            launch(bgDispatcher) { observeDbConfig() }
            launch(bgDispatcher) { syncDbOrders() }
        }
    }

    suspend fun observeLdkWallet() {
        lightningService.syncFlow()
            .filter { _nodeLifecycleState == NodeLifecycleState.Running }
            .collect {
                runCatching { sync() }
                Log.v(APP, "App state synced with ldk-node.")
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
                    _uiState.update { it.copy(orders = btOrders) }
                }
        }
    }

    private var isSyncingWallet = false

    private suspend fun sync() {
        syncState()

        if (isSyncingWallet) {
            Log.w(APP, "Sync already in progress, waiting for existing sync.")
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

        viewModelScope.launch {
            launch(bgDispatcher) { syncBalances() }
            launch(bgDispatcher) { syncActivityItems() }
            // debugActivityItems()
        }
    }

    private fun syncBalances() {
        lightningService.balances?.let { balance ->
            _uiState.update { it.copy(balanceDetails = balance) }
            _balanceState.update {
                it.copy(
                    totalOnchainSats = balance.totalOnchainBalanceSats,
                    totalLightningSats = balance.totalLightningBalanceSats,
                    totalSats = balance.totalLightningBalanceSats + balance.totalOnchainBalanceSats,
                )
            }
        }
    }

    private fun syncActivityItems() {
        lightningService.payments?.let { payments ->
            val sorted = payments.sortedByDescending { it.latestUpdateTimestamp }

            // TODO: eventually load other activity types from storage
            val allActivity = mutableListOf<PaymentDetails>()

            sorted.forEach { details ->
                when (details.kind) {
                    is PaymentKind.Onchain -> {
                        allActivity.add(details)
                    }

                    is PaymentKind.Bolt11 -> {
                        if (!(details.status == PaymentStatus.PENDING && details.direction == PaymentDirection.INBOUND)) {
                            allActivity.add(details)
                        }
                    }

                    is PaymentKind.Spontaneous -> {
                        allActivity.add(details)
                    }

                    is PaymentKind.Bolt11Jit -> Unit
                    is PaymentKind.Bolt12Offer -> Unit
                    is PaymentKind.Bolt12Refund -> Unit
                }
            }

            // TODO: append activity items from lightning balances
            activityItems.value = allActivity
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
                            _uiState.update { it.copy(orders = btOrders) }
                        }
                }
            }
        }
    }

    private suspend fun registerForNotificationsIfNeeded() {
        val token = firebaseMessaging.token.await()
        val cachedToken = keychain.loadString(Keychain.Key.PUSH_NOTIFICATION_TOKEN.name)

        if (cachedToken == token) {
            Log.d(LSP, "Skipped registering for notifications, current device token already registered")
            return
        }

        runCatching { blocktankService.registerDevice(token) }
            .onFailure { Log.e(LSP, "Failed to register device for notifications", it) }
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

        val hasChannels = lightningService.channels?.isNotEmpty() == true
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
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "Success", description = "Peer disconnected.")
            _uiState.update {
                it.copy(peers = lightningService.peers.orEmpty())
            }
        }
    }

    fun findChannelById(id: ChannelId): ChannelDetails? = lightningService.channels?.find { it.channelId == id }

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

    fun createInvoice(amountSats: ULong, description: String = "Bitkit", expirySeconds: UInt = 7200u): String {
        return runBlocking { lightningService.receive(amountSats, description, expirySeconds) }
    }

    fun onScanSuccess(data: String) {
        viewModelScope.launch {
            // TODO: handle
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "Success", description = "Not implemented:\n$data")
        }
    }

    fun openChannel() {
        viewModelScope.launch(bgDispatcher) {
            val peer = lightningService.peers?.first ?: error("No peer connected to open channel.")
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
                lightningService.wipeStorage(0)
                appStorage.clear()
                keychain.wipe()
            }.onFailure {
                ToastEventBus.send(it)
            }
        }
    }

    // region debug
    fun manualRegisterForNotifications() {
        viewModelScope.launch(bgDispatcher) {
            val token = firebaseMessaging.token.await()
            runCatching { blocktankService.registerDevice(token) }
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
        val orderIds = _uiState.value.orders.map { it.id }.takeIf { it.isNotEmpty() } ?: error("No orders to sync.")
        viewModelScope.launch {
            runCatching { blocktankService.getOrders(orderIds) }
                .onSuccess { orders ->
                    _uiState.update { it.copy(orders = orders) }
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun debugBtCreateOrder(sats: Int) {
        viewModelScope.launch {
            runCatching { blocktankService.createOrder(spendingBalanceSats = sats, 6) }
                .onSuccess { order ->
                    launch {
                        db.ordersDao().upsert(OrderEntity(order.id))
                        Log.d(APP, "Order ID saved to DB: ${order.id}")
                    }
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun debugBtPayOrder(order: BtOrder) {
        viewModelScope.launch {
            runCatching { lightningService.send(order.payment.onchain.address, order.feeSat) }
                .onSuccess { txId ->
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = "Order paid",
                        description = "Tx ID: $txId"
                    )
                    // TODO: watch this order for updates
                    launch {
                        Log.d(DEV, "Syncing orders")
                        delay(1500)
                        syncState()
                        debugBtOrdersSync()
                    }
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun debugBtManualOpenChannel(order: BtOrder) {
        viewModelScope.launch {
            runCatching { blocktankService.openChannel(order.id) }
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = "Success",
                        description = "Opened channel manually"
                    )
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun debugActivityItems() {
        viewModelScope.launch {
            val testItems = testActivityItems.toList().sortedByDescending { it.latestUpdateTimestamp }
            activityItems.value = testItems
            // ToastEventBus.send(
            //     type = Toast.ToastType.INFO,
            //     title = "Success",
            //     description = "Test activity items added"
            // )
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
    val orders: List<BtOrder> = emptyList(),
)

enum class NodeLifecycleState {
    Stopped,
    Starting,
    Running,
    Stopping;

    fun isStoppedOrStopping() = this == Stopped || this == Stopping
    fun isRunningOrStarting() = this == Running || this == Starting
}
// endregion
