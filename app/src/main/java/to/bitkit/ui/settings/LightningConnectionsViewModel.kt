package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.data.CacheStore
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.createChannelDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.filterOpen
import to.bitkit.services.filterPending
import javax.inject.Inject

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    private var orders: List<IBtOrder> = emptyList()

    fun setBlocktankOrders(orders: List<IBtOrder>) {
        this.orders = orders
    }

    fun syncState() {
        viewModelScope.launch {
            val lightningState = lightningRepo.lightningState.value
            if (!lightningState.nodeLifecycleState.isRunning()) {
                // TODO handle on UI
                _uiState.update { it.copy(isNodeRunning = false) }
                return@launch
            }

            val channels = lightningRepo.getChannels().orEmpty()
            val openChannels = channels.filterOpen()

            _uiState.update {
                it.copy(
                    isNodeRunning = true,
                    openChannels = openChannels,
                    pendingConnections = getPendingConnections(channels),
                    failedOrders = getFailedOrdersAsChannels(channels),
                    localBalance = calculateLocalBalance(channels),
                    remoteBalance = calculateRemoteBalance(channels),
                )
            }
        }
    }

    private suspend fun getPendingConnections(knownChannels: List<ChannelDetails>): List<ChannelDetails> {
        val pendingLdkChannels = knownChannels.filterPending()
        val pendingOrderChannels = getPendingOrdersAsChannels(knownChannels)

        return pendingOrderChannels + pendingLdkChannels
    }

    private suspend fun getPendingOrdersAsChannels(knownChannels: List<ChannelDetails>): List<ChannelDetails> {
        val paidOrders = cacheStore.data.first().paidOrders

        return paidOrders.keys.mapNotNull { orderId ->
            val order = orders.find { it.id == orderId } ?: return@mapNotNull null

            // Only process orders that don't have a corresponding known channel
            if (knownChannels.any { channel -> channel.fundingTxo?.txid == order.channel?.fundingTx?.id }) {
                return@mapNotNull null
            }

            if (order.state2 != BtOrderState2.CREATED && order.state2 != BtOrderState2.PAID) return@mapNotNull null

            createChannelDetails().copy(
                channelId = order.id,
                counterpartyNodeId = order.lspNode.pubkey,
                fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
                channelValueSats = order.clientBalanceSat + order.lspBalanceSat,
                outboundCapacityMsat = order.clientBalanceSat * 1000u,
                inboundCapacityMsat = order.lspBalanceSat * 1000u,
            )
        }
    }

    private suspend fun getFailedOrdersAsChannels(knownChannels: List<ChannelDetails>): List<ChannelDetails> {
        val paidOrders = cacheStore.data.first().paidOrders

        return paidOrders.keys.mapNotNull { orderId ->
            val order = orders.find { it.id == orderId } ?: return@mapNotNull null

            // Only process orders that don't have a corresponding known channel
            if (knownChannels.any { channel -> channel.fundingTxo?.txid == order.channel?.fundingTx?.id }) {
                return@mapNotNull null
            }

            if (order.state2 != BtOrderState2.EXPIRED) return@mapNotNull null

            createChannelDetails().copy(
                channelId = order.id,
                counterpartyNodeId = order.lspNode.pubkey,
                fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
                channelValueSats = order.clientBalanceSat + order.lspBalanceSat,
                outboundCapacityMsat = order.clientBalanceSat * 1000u,
                inboundCapacityMsat = order.lspBalanceSat * 1000u,
                isChannelReady = false,
                isUsable = false,
            )
        }
    }

    private fun calculateLocalBalance(channels: List<ChannelDetails>?): ULong {
        return channels
            ?.filterOpen()
            ?.sumOf { it.amountOnClose }
            ?: 0u
    }

    private fun calculateRemoteBalance(channels: List<ChannelDetails>?): ULong {
        return channels
            ?.filterOpen()
            ?.sumOf { it.inboundCapacityMsat / 1000u }
            ?: 0u
    }
}

data class LightningConnectionsUiState(
    val isNodeRunning: Boolean = false,
    val openChannels: List<ChannelDetails> = emptyList(),
    val pendingConnections: List<ChannelDetails> = emptyList(),
    val failedOrders: List<ChannelDetails> = emptyList(),
    val localBalance: ULong = 0uL,
    val remoteBalance: ULong = 0uL,
)
