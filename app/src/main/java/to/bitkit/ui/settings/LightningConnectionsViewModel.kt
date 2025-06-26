package to.bitkit.ui.settings

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.env.Env
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.createChannelDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.services.filterOpen
import to.bitkit.services.filterPending
import to.bitkit.utils.Logger
import java.io.File
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    private val application: Application,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
    private val logsRepo: LogsRepo,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    private var orders: List<IBtOrder> = emptyList()

    fun setBlocktankOrders(orders: List<IBtOrder>) {
        this.orders = orders
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            syncState()
            delay(500)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun syncState() {
        viewModelScope.launch {
            val isNodeRunning = lightningRepo.lightningState.value.nodeLifecycleState.isRunning()

            val channels = lightningRepo.getChannels().orEmpty()
            val openChannels = channels.filterOpen()
            // TODO add closed channels list once tracked

            _uiState.update {
                it.copy(
                    isNodeRunning = isNodeRunning,
                    openChannels = openChannels.map { channel ->
                        channel.mapToUiModel()
                    },
                    pendingConnections = getPendingConnections(channels).map { channel ->
                        channel.mapToUiModel()
                    },
                    failedOrders = getFailedOrdersAsChannels().map { channel ->
                        channel.mapToUiModel()
                    },
                    localBalance = calculateLocalBalance(channels),
                    remoteBalance = calculateRemoteBalance(channels),
                )
            }
        }
    }

    private suspend fun ChannelDetails.mapToUiModel(): ChannelUi = ChannelUi(
        name = getChannelName(this),
        details = this
    )

    private suspend fun getChannelName(channel: ChannelDetails): String {
        val default = channel.inboundScidAlias?.toString() ?: "${channel.channelId.take(10)}…"

        val channels = lightningRepo.getChannels().orEmpty()
        val paidOrders = cacheStore.data.first().paidOrders

        val paidBlocktankOrders = orders.filter { order -> order.id in paidOrders.keys }

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

    private suspend fun getFailedOrdersAsChannels(): List<ChannelDetails> {
        val paidOrders = cacheStore.data.first().paidOrders

        return paidOrders.keys.mapNotNull { orderId ->
            val order = orders.find { it.id == orderId } ?: return@mapNotNull null

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
}

data class LightningConnectionsUiState(
    val isNodeRunning: Boolean = false,
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
