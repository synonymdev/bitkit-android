package to.bitkit.ui.settings.lightning

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.Toast
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.filterOpen
import to.bitkit.services.filterPending
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import to.bitkit.utils.TxDetails
import java.io.File
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val application: Application,
    private val lightningRepo: LightningRepo,
    internal val blocktankRepo: BlocktankRepo,
    private val logsRepo: LogsRepo,
    private val addressChecker: AddressChecker,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val walletRepo: WalletRepo,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    private val _selectedChannel = MutableStateFlow<ChannelUi?>(null)
    val selectedChannel = _selectedChannel.asStateFlow()

    private val _txDetails = MutableStateFlow<TxDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _closeConnectionUiState = MutableStateFlow(CloseConnectionUiState())
    val closeConnectionUiState = _closeConnectionUiState.asStateFlow()

    init {
        observeState()
        observeLdkEvents()
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
                    remoteBalance = calculateRemoteBalance(channels),
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
                }
            }
        }
    }

    private fun refreshSelectedChannelIfNeeded(channels: List<ChannelDetails>) {
        val currentSelectedChannel = _selectedChannel.value ?: return
        val updatedChannel = findUpdatedChannel(currentSelectedChannel.details, channels)

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

        val connectionText = application.getString(R.string.lightning__connection)

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
                counterpartyNodeId = order.lspNode.pubkey,
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

    private fun calculateLocalBalance(channels: List<ChannelDetails>): ULong {
        return channels
            .filterOpen()
            .sumOf { it.amountOnClose }
    }

    private fun calculateRemoteBalance(channels: List<ChannelDetails>): ULong {
        return channels
            .filterOpen()
            .sumOf { it.inboundCapacityMsat / 1000u }
    }

    fun zipAndShareLogs(onReady: (Uri) -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            try {
                val result = logsRepo.zipLogs()
                if (result.isFailure) {
                    Logger.error("Failed to zip logs", result.exceptionOrNull())
                    onError()
                    return@launch
                }

                val base64String = requireNotNull(result.getOrNull())

                withContext(Dispatchers.IO) {
                    val tempDir = application.externalCacheDir?.resolve("logs")?.apply { mkdirs() }
                        ?: error("External cache dir is not available")

                    val zipFileName = "bitkit_logs_${System.currentTimeMillis()}.zip"
                    val tempFile = File(tempDir, zipFileName)

                    // Convert base64 back to bytes and write to file
                    val zipBytes = Base64.getDecoder().decode(base64String)
                    tempFile.writeBytes(zipBytes)

                    val contentUri = FileProvider.getUriForFile(
                        application,
                        Env.FILE_PROVIDER_AUTHORITY,
                        tempFile
                    )

                    withContext(Dispatchers.Main) {
                        onReady(contentUri)
                    }
                }
            } catch (e: Exception) {
                Logger.error("Error preparing logs for sharing", e)
                onError()
            }
        }
    }

    fun setSelectedChannel(channelUi: ChannelUi) {
        _selectedChannel.update { channelUi }
    }

    fun clearSelectedChannel() = _selectedChannel.update { null }

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch(bgDispatcher) {
            try {
                // TODO replace with bitkit-core method when available
                _txDetails.value = addressChecker.getTransaction(txid)
                Logger.debug("fetchTransactionDetails success for '$txid'")
            } catch (e: Exception) {
                Logger.error("fetchTransactionDetails error for '$txid'", e)
                _txDetails.value = null
            }
        }
    }

    fun clearTransactionDetails() = _txDetails.update { null }

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
                        title = application.getString(R.string.lightning__close_success_title),
                        description = application.getString(R.string.lightning__close_success_msg),
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
                        title = application.getString(R.string.lightning__close_error),
                        description = application.getString(R.string.lightning__close_error_msg),
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
    val localBalance: ULong = 0u,
    val remoteBalance: ULong = 0u,
)

data class ChannelUi(
    val name: String,
    val details: ChannelDetails,
)

data class CloseConnectionUiState(
    val isLoading: Boolean = false,
    val closeSuccess: Boolean = false,
)
