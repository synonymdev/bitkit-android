package to.bitkit.ui.settings.lightning

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IBtOrder
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
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.TransactionDetails
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
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("LongParameterList")
@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    internal val blocktankRepo: BlocktankRepo,
    private val logsRepo: LogsRepo,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val walletRepo: WalletRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    private val _selectedChannel = MutableStateFlow<ChannelUi?>(null)
    val selectedChannel = _selectedChannel.asStateFlow()

    private val _txDetails = MutableStateFlow<TransactionDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _txTime = MutableStateFlow<ULong?>(null)
    val txTime = _txTime.asStateFlow()

    private val _closeConnectionUiState = MutableStateFlow(CloseConnectionUiState())
    val closeConnectionUiState = _closeConnectionUiState.asStateFlow()

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
                                    baseIndex = openChannels.size + pendingConnections.size + index
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
                val openChannels = channels.filterOpen()

                _uiState.value.copy(
                    isNodeRunning = isNodeRunning,
                    openChannels = openChannels.map { channel -> channel.mapToUiModel() },
                    pendingConnections = getPendingConnections(channels, blocktankState.paidOrders)
                        .map { it.mapToUiModel() },
                    failedOrders = getFailedOrdersAsChannels(blocktankState.paidOrders).map { it.mapToUiModel() },
                    localBalance = calculateLocalBalance(channels),
                    remoteBalance = channels.calculateRemoteBalance(),
                )
            }.collect { newState ->
                _uiState.update { newState }
                refreshSelectedChannelIfNeeded(lightningRepo.lightningState.value.channels)
            }
        }
    }

    private fun observeLdkEvents() {
        viewModelScope.launch {
            ldkNodeEventBus.events.collect { event ->
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

    private fun refreshSelectedChannelIfNeeded(channels: List<ChannelDetails>) {
        val currentSelectedChannel = _selectedChannel.value ?: return

        // Filter out closed channels from the list
        val closedChannelIds = _uiState.value.closedChannels.map { it.details.channelId }.toSet()
        val activeChannels = channels.filterNot { it.channelId in closedChannelIds }

        // Don't refresh if the selected channel is closed
        if (currentSelectedChannel.details.channelId in closedChannelIds) {
            return
        }

        // Try to find updated version in active channels
        val updatedChannel = findUpdatedChannel(currentSelectedChannel.details, activeChannels)
        _selectedChannel.update { updatedChannel?.mapToUiModel() }
    }

    private fun findUpdatedChannel(
        currentChannel: ChannelDetails,
        allChannels: List<ChannelDetails>,
    ): ChannelDetails? {
        allChannels.find { it.channelId == currentChannel.channelId }?.let { return it }

        // If current channel has funding txo, try to match by it
        currentChannel.fundingTxo?.let { fundingTxo ->
            allChannels
                .find { it.fundingTxo?.txid == fundingTxo.txid && it.fundingTxo?.vout == fundingTxo.vout }
                ?.let { return it }
        }

        // Try to find in pending/failed order channels
        val blocktankState = blocktankRepo.blocktankState.value
        val pendingOrderChannels = getPendingOrdersAsChannels(
            allChannels,
            blocktankState.paidOrders,
        )
        val failedOrderChannels = getFailedOrdersAsChannels(
            blocktankState.paidOrders,
        )
        val orderChannels = pendingOrderChannels + failedOrderChannels

        // Direct channel ID match in order channels
        orderChannels.find { it.channelId == currentChannel.channelId }?.let { return it }

        // If the current channel was a fake channel (order), check if it became a real channel
        val orders = blocktankRepo.blocktankState.value.orders
        val orderForCurrentChannel = orders.find { it.id == currentChannel.channelId }

        if (orderForCurrentChannel != null) {
            // Check if order now has a funding tx
            val fundingTxId = orderForCurrentChannel.channel?.fundingTx?.id
            if (fundingTxId != null) {
                // Try to find real channel with matching funding tx
                allChannels.find { channel -> channel.fundingTxo?.txid == fundingTxId }?.let { return it }
            }

            // Order might have transitioned states, check if it's still in our fake channels
            orderChannels.find { it.channelId == orderForCurrentChannel.id }?.let { return it }
        }

        return null
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

    private fun ClosedChannelDetails.toChannelUi(baseIndex: Int): ChannelUi {
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
        val connectionText = context.getString(R.string.lightning__connection)
        return ChannelUi(
            name = "$connectionText ${baseIndex + 1}",
            details = channelDetails,
            closureReason = this.channelClosureReason.takeIf { it.isNotEmpty() }
        )
    }

    private fun ChannelDetails.mapToUiModel(): ChannelUi = ChannelUi(
        name = getChannelName(this),
        details = this
    )

    private fun getChannelName(channel: ChannelDetails): String {
        val default = channel.inboundScidAlias?.toString() ?: "${channel.channelId.take(10)}…"

        val channels = lightningRepo.lightningState.value.channels
        val paidBlocktankOrders = blocktankRepo.blocktankState.value.paidOrders

        // orders without a corresponding known channel are considered pending
        val pendingChannels = paidBlocktankOrders.filter { order ->
            channels.none { channel -> channel.fundingTxo?.txid == order.channel?.fundingTx?.id }
        }
        val pendingIndex = pendingChannels.indexOfFirst { order -> channel.channelId == order.id }

        // TODO: sort channels to get consistent index; node.listChannels returns a list in random order
        val channelIndex = channels.indexOfFirst { channel.channelId == it.channelId }

        val connectionText = context.getString(R.string.lightning__connection)

        return when {
            channelIndex == -1 -> {
                if (pendingIndex == -1) {
                    default
                } else {
                    val index = channels.size + pendingIndex
                    "$connectionText ${index + 1}"
                }
            }

            else -> "$connectionText ${channelIndex + 1}"
        }
    }

    private fun getPendingConnections(
        knownChannels: List<ChannelDetails>,
        paidOrders: List<IBtOrder>,
    ): List<ChannelDetails> {
        val pendingLdkChannels = knownChannels.filterPending()
        val pendingOrderChannels = getPendingOrdersAsChannels(knownChannels, paidOrders)

        return pendingOrderChannels + pendingLdkChannels
    }

    private fun getPendingOrdersAsChannels(
        knownChannels: List<ChannelDetails>,
        paidOrders: List<IBtOrder>,
    ): List<ChannelDetails> {
        return paidOrders.mapNotNull { order ->
            // Only process orders that don't have a corresponding known channel
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

    private fun getFailedOrdersAsChannels(
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

    private fun calculateLocalBalance(channels: List<ChannelDetails>): ULong {
        return channels
            .filterOpen()
            .sumOf { it.amountOnClose }
    }

    fun zipLogsForSharing(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            logsRepo.zipLogsForSharing()
                .onSuccess { uri -> onReady(uri) }
                .onFailure { err ->
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
        }
    }

    fun setSelectedChannel(channelUi: ChannelUi) {
        _selectedChannel.update { channelUi }
    }

    fun clearSelectedChannel() = _selectedChannel.update { null }

    fun findAndSelectChannel(channelId: String): Boolean {
        val channels = lightningRepo.lightningState.value.channels
        val blocktankState = blocktankRepo.blocktankState.value

        val channelUi = findChannelUi(channelId, channels, blocktankState)
        if (channelUi != null) {
            setSelectedChannel(channelUi)
            return true
        }

        return false
    }

    private fun findChannelUi(
        channelId: String,
        channels: List<ChannelDetails>,
        blocktankState: to.bitkit.repositories.BlocktankState,
    ): ChannelUi? {
        return channels.find { it.channelId == channelId }?.mapToUiModel()
            ?: getPendingOrdersAsChannels(channels, blocktankState.paidOrders)
                .find { it.channelId == channelId }?.mapToUiModel()
            ?: getFailedOrdersAsChannels(blocktankState.paidOrders)
                .find { it.channelId == channelId }?.mapToUiModel()
            ?: _uiState.value.closedChannels.find { it.details.channelId == channelId }
            ?: blocktankState.orders.find { it.id == channelId }?.let { order ->
                createChannelDetails().copy(
                    channelId = order.id,
                    counterpartyNodeId = order.lspNode?.pubkey.orEmpty(),
                    fundingTxo = order.channel?.fundingTx?.let { OutPoint(txid = it.id, vout = it.vout.toUInt()) },
                    channelValueSats = order.clientBalanceSat + order.lspBalanceSat,
                    outboundCapacityMsat = order.clientBalanceSat * 1000u,
                    inboundCapacityMsat = order.lspBalanceSat * 1000u,
                ).mapToUiModel()
            }
    }

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch(bgDispatcher) {
            runCatching {
                val transactionDetails = lightningRepo.getTransactionDetails(txid).getOrNull()
                _txDetails.update { transactionDetails }
                if (transactionDetails != null) {
                    Logger.debug("fetchTransactionDetails success for: '$txid'", context = TAG)
                } else {
                    Logger.warn("Transaction details not found for: '$txid'", context = TAG)
                }
            }.onFailure { e ->
                Logger.warn("fetchTransactionDetails error for: '$txid'", e, context = TAG)
                _txDetails.update { null }
            }
        }
    }

    fun clearTransactionDetails() {
        _txDetails.update { null }
        _txTime.update { null }
    }

    fun fetchActivityTimestamp(channelId: String) {
        viewModelScope.launch(bgDispatcher) {
            runCatching {
                val activities = activityRepo.getActivities(
                    filter = ActivityFilter.ONCHAIN,
                    txType = PaymentType.SENT,
                    tags = null,
                    search = null,
                    minDate = null,
                    maxDate = null,
                    limit = null,
                    sortDirection = null
                ).getOrNull() ?: emptyList()

                val transferActivity = activities.firstOrNull { activity ->
                    activity is Activity.Onchain &&
                        activity.v1.isTransfer &&
                        activity.v1.channelId == channelId
                } as? Activity.Onchain

                _txTime.update { transferActivity?.v1?.confirmTimestamp ?: transferActivity?.v1?.timestamp }
            }.onFailure { e ->
                Logger.warn("fetchActivityTimestamp error for channelId: '$channelId'", e, context = TAG)
                _txTime.update { null }
            }
        }
    }

    fun clearCloseConnectionState() {
        _closeConnectionUiState.update { CloseConnectionUiState() }
    }

    fun closeChannel() {
        val channel = _selectedChannel.value?.details ?: run {
            val error = IllegalStateException("No channel selected for closing")
            Logger.error(error.message, e = error, context = TAG)
            throw error
        }

        viewModelScope.launch {
            _closeConnectionUiState.update { it.copy(isLoading = true) }

            lightningRepo.closeChannel(channel).fold(
                onSuccess = {
                    walletRepo.syncNodeAndWallet()

                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.lightning__close_success_title),
                        description = context.getString(R.string.lightning__close_success_msg),
                    )

                    _closeConnectionUiState.update {
                        it.copy(
                            isLoading = false,
                            closeSuccess = true,
                        )
                    }
                },
                onFailure = { error ->
                    Logger.error("Failed to close channel", e = error, context = TAG)

                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__close_error),
                        description = context.getString(R.string.lightning__close_error_msg),
                    )

                    _closeConnectionUiState.update { it.copy(isLoading = false) }
                }
            )
        }
    }

    companion object {
        private const val TAG = "LightningConnectionsViewModel"
    }
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

data class CloseConnectionUiState(
    val isLoading: Boolean = false,
    val closeSuccess: Boolean = false,
)
