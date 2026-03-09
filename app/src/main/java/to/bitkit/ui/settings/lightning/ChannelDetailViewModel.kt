package to.bitkit.ui.settings.lightning

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.createChannelDetails
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import javax.inject.Inject

@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class ChannelDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelDetailUiState())
    val uiState = _uiState.asStateFlow()

    fun loadChannel(channelId: String) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(channelLoadState = ChannelLoadState.Loading) }

            val closedChannels = loadClosedChannels()
            val channelUi = findChannelUi(channelId, closedChannels)

            if (channelUi == null) {
                _uiState.update { it.copy(channelLoadState = ChannelLoadState.NotFound) }
                return@launch
            }

            val isClosedChannel = closedChannels.any { it.details.channelId == channelId }

            _uiState.update {
                it.copy(
                    channelLoadState = ChannelLoadState.Success(channelUi),
                    paidOrders = blocktankRepo.blocktankState.value.paidOrders,
                    cjitEntries = blocktankRepo.blocktankState.value.cjitEntries,
                    isClosedChannel = isClosedChannel,
                )
            }

            fetchActivityTimestamp(channelId)
            observeChannelUpdates(channelId, closedChannels)
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            lightningRepo.sync()
            blocktankRepo.refreshOrders()
            delay(500)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun findChannelUi(channelId: String, closedChannels: List<ChannelUi>): ChannelUi? {
        val channels = lightningRepo.lightningState.value.channels
        val blocktankState = blocktankRepo.blocktankState.value
        val connectionText = context.getString(R.string.lightning__connection)

        return channels.find { it.channelId == channelId }
            ?.mapToUiModel(channels, blocktankState.paidOrders, connectionText)
            ?: getPendingOrdersAsChannels(channels, blocktankState.paidOrders)
                .find { it.channelId == channelId }
                ?.mapToUiModel(channels, blocktankState.paidOrders, connectionText)
            ?: getFailedOrdersAsChannels(blocktankState.paidOrders)
                .find { it.channelId == channelId }
                ?.mapToUiModel(channels, blocktankState.paidOrders, connectionText)
            ?: closedChannels.find { it.details.channelId == channelId }
            ?: blocktankState.orders.find { it.id == channelId }?.let { order ->
                createChannelDetails().copy(
                    channelId = order.id,
                    counterpartyNodeId = order.lspNode?.pubkey.orEmpty(),
                    fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
                    channelValueSats = order.clientBalanceSat + order.lspBalanceSat,
                    outboundCapacityMsat = order.clientBalanceSat * 1000u,
                    inboundCapacityMsat = order.lspBalanceSat * 1000u,
                ).mapToUiModel(channels, blocktankState.paidOrders, connectionText)
            }
    }

    private suspend fun loadClosedChannels(): List<ChannelUi> {
        val connectionText = context.getString(R.string.lightning__connection)
        val channels = lightningRepo.lightningState.value.channels
        val paidOrders = blocktankRepo.blocktankState.value.paidOrders

        return activityRepo.getClosedChannels(SortDirection.DESC)
            .getOrNull()
            ?.mapIndexed { index, closedChannel ->
                closedChannel.toChannelUi(
                    baseIndex = channels.size + getPendingOrdersAsChannels(channels, paidOrders).size + index,
                    connectionText = connectionText,
                )
            }
            ?.reversed()
            .orEmpty()
    }

    private fun fetchActivityTimestamp(channelId: String) {
        viewModelScope.launch(bgDispatcher) {
            val activities = activityRepo.getActivities(
                filter = ActivityFilter.ONCHAIN,
                txType = PaymentType.SENT,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = null,
                sortDirection = null,
            ).getOrNull().orEmpty()

            val transferActivity = activities.firstOrNull { activity ->
                activity is Activity.Onchain &&
                    activity.v1.isTransfer &&
                    activity.v1.channelId == channelId
            } as? Activity.Onchain

            _uiState.update {
                it.copy(txTime = transferActivity?.v1?.confirmTimestamp ?: transferActivity?.v1?.timestamp)
            }
        }
    }

    private fun observeChannelUpdates(channelId: String, closedChannels: List<ChannelUi>) {
        viewModelScope.launch(bgDispatcher) {
            combine(
                lightningRepo.lightningState,
                blocktankRepo.blocktankState,
            ) { lightningState, blocktankState ->
                val channels = lightningState.channels
                val connectionText = context.getString(R.string.lightning__connection)

                val updatedChannel = channels.find { it.channelId == channelId }
                    ?.mapToUiModel(channels, blocktankState.paidOrders, connectionText)
                    ?: getPendingOrdersAsChannels(channels, blocktankState.paidOrders)
                        .find { it.channelId == channelId }
                        ?.mapToUiModel(channels, blocktankState.paidOrders, connectionText)
                    ?: getFailedOrdersAsChannels(blocktankState.paidOrders)
                        .find { it.channelId == channelId }
                        ?.mapToUiModel(channels, blocktankState.paidOrders, connectionText)

                val isClosedChannel = closedChannels.any { it.details.channelId == channelId }

                Triple(updatedChannel, blocktankState, isClosedChannel)
            }.collect { (updatedChannel, blocktankState, isClosedChannel) ->
                if (updatedChannel != null) {
                    _uiState.update {
                        it.copy(
                            channelLoadState = ChannelLoadState.Success(updatedChannel),
                            paidOrders = blocktankState.paidOrders,
                            cjitEntries = blocktankState.cjitEntries,
                            isClosedChannel = isClosedChannel,
                        )
                    }
                }
            }
        }
    }
}

data class ChannelDetailUiState(
    val channelLoadState: ChannelLoadState = ChannelLoadState.Loading,
    val paidOrders: List<IBtOrder> = emptyList(),
    val cjitEntries: List<IcJitEntry> = emptyList(),
    val txTime: ULong? = null,
    val isRefreshing: Boolean = false,
    val isClosedChannel: Boolean = false,
)

sealed interface ChannelLoadState {
    data object Loading : ChannelLoadState
    data class Success(val channel: ChannelUi) : ChannelLoadState
    data object NotFound : ChannelLoadState
}
