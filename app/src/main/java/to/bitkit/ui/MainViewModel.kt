package to.bitkit.ui

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.LnPeer
import to.bitkit.SEED
import to.bitkit.Tag.DEV
import to.bitkit.bdk.BitcoinService
import to.bitkit.data.AppDb
import to.bitkit.data.keychain.KeychainStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.syncTo
import to.bitkit.ldk.LightningService
import to.bitkit.ldk.closeChannel
import to.bitkit.ldk.connectPeer
import to.bitkit.ldk.createInvoice
import to.bitkit.ldk.openChannel
import to.bitkit.ldk.payInvoice
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: KeychainStore,
    private val appDb: AppDb,
) : ViewModel() {
    val ldkNodeId = mutableStateOf("Loading…")
    val ldkBalance = mutableStateOf("Loading…")
    val btcAddress = mutableStateOf("Loading…")
    val btcBalance = mutableStateOf("Loading…")
    val mnemonic = mutableStateOf(SEED)

    val peers = mutableStateListOf<PeerDetails>()
    val channels = mutableStateListOf<ChannelDetails>()

    val lightningService = LightningService.shared
    private val bitcoinService = BitcoinService.shared

    private val node = lightningService.node

    init {
        viewModelScope.launch {
            sync()
        }
    }

    fun sync() {
        ldkNodeId.value = lightningService.nodeId
        ldkBalance.value = lightningService.balances.totalLightningBalanceSats.toString()
        btcAddress.value = bitcoinService.address
        btcBalance.value = bitcoinService.balance.total.toString()
        mnemonic.value = SEED
        peers.syncTo(lightningService.peers)
        channels.syncTo(lightningService.channels)
    }

    fun getNewAddress() {
        btcAddress.value = node.onchainPayment().newAddress()
    }

    fun connectPeer(peer: LnPeer) {
        lightningService.connectPeer(peer)
        peers.replaceAll {
            it.run { copy(isConnected = it.nodeId == nodeId) }
        }
        channels.syncTo(lightningService.channels)
    }

    fun disconnectPeer(nodeId: String) {
        node.disconnect(nodeId)
        peers.replaceAll {
            it.takeIf { it.nodeId == nodeId }?.copy(isConnected = false) ?: it
        }
        channels.syncTo(lightningService.channels)
    }

    fun payInvoice(invoice: String) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.payInvoice(invoice)
            sync()
        }
    }

    fun createInvoice() = lightningService.createInvoice()

    fun openChannel() {
        viewModelScope.launch(bgDispatcher) {
            lightningService.openChannel()
            sync()
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            sync()
        }
    }

    fun debugDb() {
        viewModelScope.launch {
            appDb.configDao().getAll().collect {
                Log.d(DEV, "${it.count()} entities in DB: $it")
            }
        }
    }

    fun debugKeychain() {
        viewModelScope.launch {
            val key = "test"
            if (keychain.exists(key)) {
                keychain.delete(key)
            }
            keychain.saveString(key, "testValue")
        }
    }
}

fun MainViewModel.refresh() {
    viewModelScope.launch {
        "Refreshing…".also {
            ldkNodeId.value = it
            ldkBalance.value = it
            btcAddress.value = it
            btcBalance.value = it
        }
        peers.clear()
        channels.clear()

        delay(50)
        lightningService.sync()
        sync()
    }
}

fun MainViewModel.togglePeerConnection(peer: PeerDetails) =
    if (peer.isConnected) disconnectPeer(peer.nodeId) else connectPeer(LnPeer(peer.nodeId, peer.address))
