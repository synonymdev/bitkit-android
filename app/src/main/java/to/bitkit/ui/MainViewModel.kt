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
import to.bitkit.LnPeer
import to.bitkit.SEED
import to.bitkit.Tag.DEV
import to.bitkit.services.BitcoinService
import to.bitkit.data.AppDb
import to.bitkit.data.keychain.KeychainStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.syncTo
import to.bitkit.services.LightningService
import to.bitkit.services.closeChannel
import to.bitkit.services.connectPeer
import to.bitkit.services.createInvoice
import to.bitkit.services.openChannel
import to.bitkit.services.payInvoice
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val bitcoinService: BitcoinService,
    private val lightningService: LightningService,
    private val keychain: KeychainStore,
    private val appDb: AppDb,
) : ViewModel() {
    val ldkNodeId = mutableStateOf("Loading…")
    val ldkBalance = mutableStateOf("Loading…")
    val btcAddress = mutableStateOf("Loading…")
    val btcBalance = mutableStateOf("Loading…")
    val mnemonic = mutableStateOf(SEED)

    val peers = mutableStateListOf<LnPeer>()
    val channels = mutableStateListOf<ChannelDetails>()

    private val node = lightningService.node

    init {
        viewModelScope.launch {
            sync()
        }
    }

    private suspend fun sync() {
        bitcoinService.syncWithRevealedSpks()
        ldkNodeId.value = lightningService.nodeId
        ldkBalance.value = lightningService.balances.totalLightningBalanceSats.toString()
        btcAddress.value = bitcoinService.getAddress()
        btcBalance.value = bitcoinService.balance?.total?.toSat().toString()
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
            lightningService.openChannel(peers.first())
            sync()
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            sync()
        }
    }

    // region debug
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

    fun debugWipeBdk() {
        bitcoinService.wipeStorage()
    }
    // endregion

    fun refresh() {
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
}

fun MainViewModel.togglePeerConnection(peer: LnPeer) =
    if (peer.isConnected) disconnectPeer(peer.nodeId) else connectPeer(peer)
