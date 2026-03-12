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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.Toast
import to.bitkit.models.safe
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@Suppress("TooManyFunctions")
@HiltViewModel
class ChannelDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "ChannelDetailViewModel"
    }

    private val _uiState = MutableStateFlow(ChannelDetailUiState())
    val uiState = _uiState.asStateFlow()

    private var observerJob: Job? = null
    private val connectionText by lazy { context.getString(R.string.lightning__connection) }

    fun loadChannel(channelId: String) = viewModelScope.launch {
        _uiState.update { it.copy(channelLoadState = ChannelLoadState.Loading) }

        val closedChannels = loadClosedChannels()
        val channelUi = findChannelUi(channelId, closedChannels)

        if (channelUi == null) {
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.lightning__channel_not_found),
            )
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
                nodeId = lightningRepo.getNodeId().orEmpty(),
            )
        }

        fetchActivityTimestamp(channelId)
        observeChannelUpdates(channelId)
    }

    fun onPullToRefresh() = viewModelScope.launch {
        _uiState.update { it.copy(isRefreshing = true) }
        lightningRepo.sync()
        blocktankRepo.refreshOrders()
        delay(500.milliseconds)
        _uiState.update { it.copy(isRefreshing = false) }
    }

    private fun findChannelUi(channelId: String, closedChannels: List<ChannelUi>): ChannelUi? {
        val channels = lightningRepo.lightningState.value.channels
        val blocktankState = blocktankRepo.blocktankState.value

        return resolveActiveChannel(channelId, channels, blocktankState.paidOrders)
            ?: closedChannels.find { it.details.channelId == channelId }
            ?: blocktankState.orders.find { it.id == channelId }?.let { order ->
                createChannelDetails().copy(
                    channelId = order.id,
                    counterpartyNodeId = order.lspNode?.pubkey.orEmpty(),
                    fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
                    channelValueSats = order.clientBalanceSat.safe() + order.lspBalanceSat.safe(),
                    outboundCapacityMsat = order.clientBalanceSat * 1000u,
                    inboundCapacityMsat = order.lspBalanceSat * 1000u,
                ).mapToUiModel(channels, blocktankState.paidOrders, connectionText)
            }
    }

    private fun resolveActiveChannel(
        channelId: String,
        channels: List<ChannelDetails>,
        paidOrders: List<IBtOrder>,
    ): ChannelUi? =
        channels.find { it.channelId == channelId }
            ?.mapToUiModel(channels, paidOrders, connectionText)
            ?: getPendingOrdersAsChannels(channels, paidOrders)
                .find { it.channelId == channelId }
                ?.mapToUiModel(channels, paidOrders, connectionText)
            ?: getFailedOrdersAsChannels(paidOrders)
                .find { it.channelId == channelId }
                ?.mapToUiModel(channels, paidOrders, connectionText)

    private suspend fun loadClosedChannels(): List<ChannelUi> {
        val channels = lightningRepo.lightningState.value.channels
        val paidOrders = blocktankRepo.blocktankState.value.paidOrders

        return activityRepo.getClosedChannels(SortDirection.DESC)
            .onFailure { Logger.error("Failed to load closed channels", it, context = TAG) }
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

    private fun fetchActivityTimestamp(channelId: String) = viewModelScope.launch {
        val activities = activityRepo.getActivities(
            filter = ActivityFilter.ONCHAIN,
            txType = PaymentType.SENT,
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

    private fun observeChannelUpdates(channelId: String) {
        observerJob?.cancel()
        observerJob = viewModelScope.launch {
            combine(
                lightningRepo.lightningState,
                blocktankRepo.blocktankState,
            ) { lightningState, blocktankState ->
                val channels = lightningState.channels
                val updatedChannel = resolveActiveChannel(channelId, channels, blocktankState.paidOrders)
                Pair(updatedChannel, blocktankState)
            }.collect { (updatedChannel, blocktankState) ->
                if (updatedChannel != null) {
                    _uiState.update {
                        it.copy(
                            channelLoadState = ChannelLoadState.Success(updatedChannel),
                            paidOrders = blocktankState.paidOrders,
                            cjitEntries = blocktankState.cjitEntries,
                            nodeId = lightningRepo.getNodeId().orEmpty(),
                        )
                    }
                } else {
                    val freshClosed = loadClosedChannels()
                    val isNowClosed = freshClosed.any { it.details.channelId == channelId }
                    val isPendingOrder = blocktankState.orders.any { it.id == channelId }
                    if (isNowClosed) {
                        val closedChannel = freshClosed.first { it.details.channelId == channelId }
                        _uiState.update {
                            it.copy(
                                channelLoadState = ChannelLoadState.Success(closedChannel),
                                isClosedChannel = true,
                                paidOrders = blocktankState.paidOrders,
                                cjitEntries = blocktankState.cjitEntries,
                                nodeId = lightningRepo.getNodeId().orEmpty(),
                            )
                        }
                    } else if (!isPendingOrder) {
                        _uiState.update { it.copy(channelLoadState = ChannelLoadState.NotFound) }
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
    val nodeId: String = "",
)

sealed interface ChannelLoadState {
    data object Loading : ChannelLoadState
    data class Success(val channel: ChannelUi) : ChannelLoadState
    data object NotFound : ChannelLoadState
}
