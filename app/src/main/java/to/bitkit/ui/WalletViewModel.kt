package to.bitkit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.env.LnPeer
import to.bitkit.env.SEED
import to.bitkit.di.BgDispatcher
import to.bitkit.services.BitcoinService
import to.bitkit.services.LightningService
import to.bitkit.services.closeChannel
import to.bitkit.services.connectPeer
import to.bitkit.services.createInvoice
import to.bitkit.services.openChannel
import to.bitkit.services.payInvoice
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val bitcoinService: BitcoinService,
    private val lightningService: LightningService,
) : ViewModel() {
    private val node = lightningService.node

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            delay(1000) // TODO replace with actual load time of ldk-node warmUp
            sync()
        }
    }

    private suspend fun sync() {
        lightningService.sync()
        bitcoinService.syncWithRevealedSpks()
        _uiState.value = MainUiState.Content(
            ldkNodeId = lightningService.nodeId,
            ldkBalance = lightningService.balances.totalLightningBalanceSats.toString(),
            btcAddress = bitcoinService.getNextAddress(),
            btcBalance = bitcoinService.balance?.total?.toSat().toString(),
            mnemonic = SEED,
            peers = lightningService.peers,
            channels = lightningService.channels,
        )
    }

    fun getNewAddress() {
        updateContentState { it.copy(btcAddress = node.onchainPayment().newAddress()) }
    }

    fun connectPeer(peer: LnPeer) {
        lightningService.connectPeer(peer)

        updateContentState {
            val peers = it.peers.toMutableList().apply {
                replaceAll { p -> p.run { copy(isConnected = p.nodeId == nodeId) } }
            }
            it.copy(peers = peers)
        }
    }

    private fun disconnectPeer(nodeId: String) {
        node.disconnect(nodeId)

        updateContentState {
            val peers = it.peers.toMutableList().apply {
                replaceAll { p -> p.takeIf { pp -> pp.nodeId == nodeId }?.copy(isConnected = false) ?: p }
            }
            it.copy(peers = peers)
        }
    }

    fun payInvoice(invoice: String) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.payInvoice(invoice)
            sync()
        }
    }

    fun createInvoice() = lightningService.createInvoice()

    fun openChannel() {
        val contentState = _uiState.value as? MainUiState.Content ?: error("No peer connected to open channel.")
        viewModelScope.launch(bgDispatcher) {
            lightningService.openChannel(contentState.peers.first())
            sync()
        }
    }

    fun closeChannel(channel: ChannelDetails) {
        viewModelScope.launch(bgDispatcher) {
            lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            sync()
        }
    }

    private fun updateContentState(update: (MainUiState.Content) -> MainUiState.Content) {
        val stateValue = this._uiState.value
        if (stateValue is MainUiState.Content) {
            this._uiState.value = update(stateValue)
        }
    }

    // region debug
    fun refresh() {
        _uiState.value = MainUiState.Loading
        viewModelScope.launch {
            delay(500)
            sync()
        }
    }

    fun togglePeerConnection(peer: LnPeer) {
        if (peer.isConnected) disconnectPeer(peer.nodeId)
        else connectPeer(peer)
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
}
// endregion
