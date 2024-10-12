package to.bitkit.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import to.bitkit.data.AppDb
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Env.SEED
import to.bitkit.env.Tag.APP
import to.bitkit.env.Tag.DEV
import to.bitkit.env.Tag.LDK
import to.bitkit.env.Tag.LSP
import to.bitkit.ext.call
import to.bitkit.ext.toast
import to.bitkit.ext.first
import to.bitkit.models.LnPeer
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.services.OnChainService
import to.bitkit.shared.ServiceError
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val db: AppDb,
    private val keychain: Keychain,
    private val blocktankService: BlocktankService,
    private val onChainService: OnChainService,
    private val lightningService: LightningService,
    private val firebaseMessaging: FirebaseMessaging,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val node by lazy { lightningService.node ?: throw ServiceError.NodeNotSetup }

    fun start() {
        viewModelScope.launch {
            runCatching {
                lightningService.let {
                    it.setup()
                    it.start { event ->
                        syncState()
                        uiThread.call { onLdkEvent(event) }
                    }
                }
                onChainService.let {
                    it.setup()
                    it.fullScan()
                }
            }.onFailure { Log.e(APP, "Init error", it) }
            syncState()

            launch(coroutineContext) { sync() }
            db.configDao().getAll().collect { Log.d(APP, "Database config sync: $it") }
        }
    }

    private suspend fun sync() {
        lightningService.sync()
        syncState()
    }

    private suspend fun syncState() {
        _uiState.value = MainUiState.Content(
            ldkNodeId = lightningService.nodeId.orEmpty(),
            ldkBalance = lightningService.balances?.totalLightningBalanceSats.toString(),
            btcAddress = onChainService.getAddress(),
            btcBalance = onChainService.balance?.total?.toSat().toString(),
            mnemonic = SEED,
            peers = lightningService.peers.orEmpty(),
            channels = lightningService.channels.orEmpty(),
        )
    }

    private fun onLdkEvent(event: Event) {
        try {
            when (event) {
                is Event.PaymentReceived -> {
                    toast("Received ${event.amountMsat / 1000u} sats")
                }
                is Event.ChannelPending -> {
                    toast("Channel Pending")
                }
                is Event.ChannelReady -> {
                    toast("Channel Opened")
                }
                is Event.ChannelClosed -> {
                    toast("Channel Closed")
                }
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
            uiThread.call {
                when (result.isSuccess) {
                    true -> toast("Device registered with LSP Notifications Server.")
                    else -> toast("Failed to register device with LSP Notifications Server.")
                }
            }
        }
    }

    fun getNewAddress() {
        updateContentState { it.copy(btcAddress = node.onchainPayment().newAddress()) }
    }

    fun connectPeer(peer: LnPeer) {
        viewModelScope.launch {
            lightningService.connectPeer(peer)
            uiThread.call { toast("Peer connected.") }
            updateContentState {
                it.copy(peers = lightningService.peers.orEmpty())
            }
        }
    }

    fun disconnectPeer(peer: LnPeer) {
        viewModelScope.launch {
            node.disconnect(peer.nodeId)
            uiThread.call { toast("Peer disconnected.") }
            updateContentState {
                it.copy(peers = lightningService.peers.orEmpty())
            }
        }
    }

    fun payInvoice(invoice: String) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.payInvoice(invoice)
            syncState()
        }
    }

    fun createInvoice(): String {
        return runBlocking { lightningService.createInvoice(112u, "description", 7200u) }
    }

    fun openChannel() {
        val contentState = _uiState.value.asContent() ?: error("UI not ready..")
        viewModelScope.launch(bgDispatcher) {
            val peer = contentState.peers.first ?: error("No peer connected to open channel.")
            uiThread.call { toast("Channel Pending.") }
            lightningService.openChannel(peer, 50000u, 10000u)
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            syncState()
            uiThread.call { toast("Channel Closed.") }
        }
    }

    private fun updateContentState(update: (MainUiState.Content) -> MainUiState.Content) {
        val stateValue = this._uiState.value
        if (stateValue is MainUiState.Content) {
            this._uiState.value = update(stateValue)
        }
    }

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

    fun debugWipeBdk() {
        onChainService.stop()
        onChainService.wipeStorage()
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

    fun refresh() {
        _uiState.value = MainUiState.Loading
        viewModelScope.launch {
            delay(500)
            syncState()
        }
    }
    // endregion
}

// region state
sealed class MainUiState {
    data object Loading : MainUiState()
    data class Content(
        val ldkNodeId: String,
        val ldkBalance: String,
        val btcAddress: String,
        val btcBalance: String,
        val mnemonic: String,
        val peers: List<LnPeer>,
        val channels: List<ChannelDetails>,
    ) : MainUiState()

    data class Error(
        val title: String = "Error Title",
        val message: String = "Error short description.",
    ) : MainUiState()

    fun asContent() = this as? Content
}
// endregion
