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
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.LnPeer
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

    val peers = mutableStateListOf<PeerDetails>()
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
                this += lightningService.peers
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

    fun connectPeer(peer: LnPeer) {
        lightningService.connectPeer(peer)
        peers.replaceAll {
            it.apply {
                return@replaceAll it.copy(isConnected = it.nodeId == nodeId)
            }
        }
    }

    fun disconnectPeer(nodeId: String) {
        node.disconnect(nodeId)
        peers.replaceAll {
            it.apply {
                if (it.nodeId == nodeId) {
                    return@replaceAll it.copy(isConnected = false)
                }
            }
        }
    }

    fun payInvoice(invoice: String) {
        viewModelScope.launch {
            lightningService.payInvoice(invoice)
            sync()
        }
    }

    fun createInvoice() = lightningService.createInvoice()

    fun openChannel() {
        viewModelScope.launch {
            lightningService.openChannel()
            sync()
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch {
            lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
        }
        sync()
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
        peers.apply {
            clear()
        }
        delay(50)
        lightningService.sync()
        sync()
    }
}

fun MainViewModel.togglePeerConnection(peer: PeerDetails) =
    if (peer.isConnected) disconnectPeer(peer.nodeId) else connectPeer(LnPeer(peer.nodeId, peer.address))
