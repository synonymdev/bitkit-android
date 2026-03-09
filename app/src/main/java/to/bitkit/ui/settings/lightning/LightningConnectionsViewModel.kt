package to.bitkit.ui.settings.lightning

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IBtOrder
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
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.filterOpen
import to.bitkit.ext.filterPending
import to.bitkit.models.Toast
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val logsRepo: LogsRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeState()
        observeLdkEvents()
        loadClosedChannels()
    }

    private fun loadClosedChannels() {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getClosedChannels(SortDirection.DESC)
                .onSuccess { closedChannels ->
                    val channels = lightningRepo.lightningState.value.channels
                    val openChannels = channels.filterOpen()
                    val pendingConnections =
                        getPendingConnections(channels, blocktankRepo.blocktankState.value.paidOrders)

                    _uiState.update { state ->
                        state.copy(
                            closedChannels = closedChannels.mapIndexed { index, closedChannel ->
                                closedChannel.toChannelUi(
                                    baseIndex = openChannels.size + pendingConnections.size + index,
                                    connectionText = context.getString(R.string.lightning__connection),
                                )
                            }.reversed()
                        )
                    }
                }
                .onFailure { e ->
                    Logger.error("Failed to load closed channels", e, context = TAG)
                }
        }
    }

    private fun observeState() {
        viewModelScope.launch(bgDispatcher) {
            combine(
                lightningRepo.lightningState,
                blocktankRepo.blocktankState,
            ) { lightningState, blocktankState ->
                val channels = lightningState.channels
                val isNodeRunning = lightningState.nodeLifecycleState.isRunning()
                val connectionText = context.getString(R.string.lightning__connection)

                _uiState.value.copy(
                    isNodeRunning = isNodeRunning,
                    openChannels = channels.filterOpen().map { channel ->
                        channel.mapToUiModel(channels, blocktankState.paidOrders, connectionText)
                    },
                    pendingConnections = getPendingConnections(channels, blocktankState.paidOrders)
                        .map { it.mapToUiModel(channels, blocktankState.paidOrders, connectionText) },
                    failedOrders = getFailedOrdersAsChannels(blocktankState.paidOrders)
                        .map { it.mapToUiModel(channels, blocktankState.paidOrders, connectionText) },
                    localBalance = calculateLocalBalance(channels),
                    remoteBalance = channels.calculateRemoteBalance(),
                )
            }.collect { newState ->
                _uiState.update { newState }
            }
        }
    }

    private fun observeLdkEvents() {
        viewModelScope.launch {
            lightningRepo.nodeEvents.collect { event ->
                if (event is Event.ChannelPending || event is Event.ChannelReady || event is Event.ChannelClosed) {
                    Logger.debug("Channel event received: ${event::class.simpleName}, triggering refresh")
                    refreshObservedState()
                    if (event is Event.ChannelClosed) {
                        loadClosedChannels()
                    }
                }
            }
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            refreshObservedState()
            delay(500)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    suspend fun refreshObservedState() {
        lightningRepo.sync()
        blocktankRepo.refreshOrders()
        loadClosedChannels()
    }

    private fun getPendingConnections(
        knownChannels: List<ChannelDetails>,
        paidOrders: List<IBtOrder>,
    ): List<ChannelDetails> {
        val pendingLdkChannels = knownChannels.filterPending()
        val pendingOrderChannels = getPendingOrdersAsChannels(knownChannels, paidOrders)

        return pendingOrderChannels + pendingLdkChannels
    }

    private fun calculateLocalBalance(channels: List<ChannelDetails>): ULong {
        return channels
            .filterOpen()
            .sumOf { it.amountOnClose }
    }

    fun zipLogsForSharing(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            logsRepo.zipLogsForSharing()
                .onSuccess { uri -> onReady(uri) }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
        }
    }

    companion object {
        private const val TAG = "LightningConnectionsViewModel"
    }
}

internal fun getPendingOrdersAsChannels(
    knownChannels: List<ChannelDetails>,
    paidOrders: List<IBtOrder>,
): List<ChannelDetails> {
    return paidOrders.mapNotNull { order ->
        if (knownChannels.any { channel -> channel.fundingTxo?.txid == order.channel?.fundingTx?.id }) {
            return@mapNotNull null
        }

        if (order.state2 !in listOf(BtOrderState2.CREATED, BtOrderState2.PAID)) return@mapNotNull null

        createChannelDetails().copy(
            channelId = order.id,
            counterpartyNodeId = order.lspNode?.pubkey.orEmpty(),
            fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
            channelValueSats = order.clientBalanceSat + order.lspBalanceSat,
            outboundCapacityMsat = order.clientBalanceSat * 1000u,
            inboundCapacityMsat = order.lspBalanceSat * 1000u,
        )
    }
}

internal fun getFailedOrdersAsChannels(
    paidOrders: List<IBtOrder>,
): List<ChannelDetails> {
    return paidOrders.mapNotNull { order ->
        if (order.state2 != BtOrderState2.EXPIRED) return@mapNotNull null

        createChannelDetails().copy(
            channelId = order.id,
            counterpartyNodeId = order.lspNode?.pubkey.orEmpty(),
            fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
            channelValueSats = order.clientBalanceSat + order.lspBalanceSat,
            outboundCapacityMsat = order.clientBalanceSat * 1000u,
            inboundCapacityMsat = order.lspBalanceSat * 1000u,
            isChannelReady = false,
            isUsable = false,
        )
    }
}

@Suppress("ForbiddenComment")
internal fun getChannelName(
    channel: ChannelDetails,
    allChannels: List<ChannelDetails>,
    paidOrders: List<IBtOrder>,
    connectionText: String,
): String {
    val default = channel.inboundScidAlias?.toString() ?: "${channel.channelId.take(10)}…"

    // orders without a corresponding known channel are considered pending
    val pendingChannels = paidOrders.filter { order ->
        allChannels.none { ch -> ch.fundingTxo?.txid == order.channel?.fundingTx?.id }
    }
    val pendingIndex = pendingChannels.indexOfFirst { order -> channel.channelId == order.id }

    // TODO: sort channels to get consistent index; node.listChannels returns a list in random order
    val channelIndex = allChannels.indexOfFirst { channel.channelId == it.channelId }

    return when {
        channelIndex == -1 -> {
            if (pendingIndex == -1) {
                default
            } else {
                val index = allChannels.size + pendingIndex
                "$connectionText ${index + 1}"
            }
        }

        else -> "$connectionText ${channelIndex + 1}"
    }
}

internal fun ChannelDetails.mapToUiModel(
    allChannels: List<ChannelDetails>,
    paidOrders: List<IBtOrder>,
    connectionText: String,
): ChannelUi = ChannelUi(
    name = getChannelName(this, allChannels, paidOrders, connectionText),
    details = this,
)

internal fun ClosedChannelDetails.toChannelUi(
    baseIndex: Int,
    connectionText: String,
): ChannelUi {
    val channelDetails = createChannelDetails().copy(
        channelId = this.channelId,
        counterpartyNodeId = this.counterpartyNodeId,
        fundingTxo = OutPoint(txid = this.fundingTxoTxid, vout = this.fundingTxoIndex),
        channelValueSats = this.channelValueSats,
        outboundCapacityMsat = this.outboundCapacityMsat,
        inboundCapacityMsat = this.inboundCapacityMsat,
        unspendablePunishmentReserve = this.unspendablePunishmentReserve,
        counterpartyUnspendablePunishmentReserve = this.counterpartyUnspendablePunishmentReserve,
        isChannelReady = false,
        isUsable = false,
    )
    return ChannelUi(
        name = "$connectionText ${baseIndex + 1}",
        details = channelDetails,
        closureReason = this.channelClosureReason.takeIf { it.isNotEmpty() }
    )
}

data class LightningConnectionsUiState(
    val isNodeRunning: Boolean = true,
    val isRefreshing: Boolean = false,
    val openChannels: List<ChannelUi> = emptyList(),
    val pendingConnections: List<ChannelUi> = emptyList(),
    val failedOrders: List<ChannelUi> = emptyList(),
    val closedChannels: List<ChannelUi> = emptyList(),
    val localBalance: ULong = 0u,
    val remoteBalance: ULong = 0u,
)

data class ChannelUi(
    val name: String,
    val details: ChannelDetails,
    val closureReason: String? = null,
)
