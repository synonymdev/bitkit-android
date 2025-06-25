package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.ext.createChannelDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.filterOpen
import to.bitkit.viewmodels.filterPaid
import javax.inject.Inject

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
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
                _uiState.update { it.copy(isNodeRunning = false) }
                return@launch
            }

            val balances = lightningRepo.getBalances()
            val channels = lightningRepo.getChannels().orEmpty()
            val pendingChannels = getPendingOrdersAsChannels(channels)

            _uiState.update {
                it.copy(
                    isNodeRunning = true,
                    balances = balances,
                    allChannels = channels,
                    openChannels = channels.filterOpen(),
                    pendingChannels = pendingChannels,
                    localBalance = calculateLocalBalance(channels),
                    remoteBalance = calculateRemoteBalance(channels),
                )
            }
        }
    }

    private fun getPendingOrdersAsChannels(knownChannels: List<ChannelDetails>): List<ChannelDetails> {
        val paidOrders = orders.filterPaid()

        return paidOrders
            .filter { order ->
                // orders without a corresponding known channel are considered pending
                knownChannels.none { channel -> channel.fundingTxo?.txid == order.channel?.fundingTx?.id }
            }
            .filter { order -> order.state2 == BtOrderState2.CREATED || order.state2 == BtOrderState2.PAID }
            .map { order ->
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

    private fun calculateLocalBalance(channels: List<ChannelDetails>?): ULong {
        return channels
            ?.filterOpen()
            ?.sumOf { it.outboundCapacityMsat / 1000u }
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
    val balances: BalanceDetails? = null,
    val allChannels: List<ChannelDetails> = emptyList(),
    val openChannels: List<ChannelDetails> = emptyList(),
    val pendingChannels: List<ChannelDetails> = emptyList(),
    val localBalance: ULong = 0uL,
    val remoteBalance: ULong = 0uL,
)
