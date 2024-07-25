package to.bitkit.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.LnPeer
import to.bitkit.PEER
import to.bitkit.SEED
import to.bitkit.bdk.BitcoinService
import to.bitkit.di.IoDispatcher
import to.bitkit.ldk.LightningService
import to.bitkit.ldk.closeChannel
import to.bitkit.ldk.connectPeer
import to.bitkit.ldk.createInvoice
import to.bitkit.ldk.openChannel
import to.bitkit.ldk.payInvoice
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val ldkNodeId = mutableStateOf("Loading…")
    val ldkBalance = mutableStateOf("Loading…")
    val btcAddress = mutableStateOf("Loading…")
    val btcBalance = mutableStateOf("Loading…")
    val mnemonic = mutableStateOf(SEED)

    val peers = mutableStateListOf<String>()
    val channels = mutableStateListOf<ChannelDetails>()

    val lightningService = LightningService.shared
    private val bitcoinService = BitcoinService.shared

    private val node = lightningService.node

    init {
        sync()
    }

    fun sync() {
        viewModelScope.launch {
            ldkNodeId.value = lightningService.nodeId
            ldkBalance.value = lightningService.balances.totalOnchainBalanceSats.toString()
            btcAddress.value = bitcoinService.address
            btcBalance.value = bitcoinService.balance.total.toString()
            mnemonic.value = SEED
            peers.apply {
                clear()
                this += lightningService.peers.mapNotNull { it.takeIf { p -> p.isConnected }?.nodeId }
            }
            channels.apply {
                clear()
                this += lightningService.channels
            }
        }
    }

    fun getNewAddress() {
        btcAddress.value = node.onchainPayment().newAddress()
    }

    fun connectPeer(pubKey: String = PEER.nodeId, host: String = PEER.host, port: String = PEER.port) {
        lightningService.connectPeer(LnPeer(pubKey, host, port))
        sync()
    }

    fun disconnectPeer() {
        node.disconnect(PEER.nodeId)
        sync()
    }

    fun payInvoice(invoice: String) {
        lightningService.payInvoice(invoice)
        sync()
    }

    fun createInvoice() = lightningService.createInvoice()

    fun openChannel() {
        lightningService.openChannel()
        sync()
    }

    fun closeChannel(channel: ChannelDetails) {
        lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
        sync()
    }
}

fun MainViewModel.refresh() {
    viewModelScope.launch {
        val text = "Refreshing…".also {
            ldkNodeId.value = it
            ldkBalance.value = it
            btcAddress.value = it
            btcBalance.value = it
        }
        peers.apply {
            clear()
            this += text
        }
        delay(50)
        lightningService.sync()
        sync()
    }
}

fun MainViewModel.togglePeerConnection() {
    if (peers.contains(PEER.nodeId)) {
        disconnectPeer()
    } else {
        connectPeer()
    }
}
