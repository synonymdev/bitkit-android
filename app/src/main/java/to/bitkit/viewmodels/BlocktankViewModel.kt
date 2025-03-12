package to.bitkit.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowTimestamp
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import uniffi.bitkitcore.CreateCjitOptions
import uniffi.bitkitcore.CreateOrderOptions
import uniffi.bitkitcore.IBtEstimateFeeResponse2
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.IBtOrder
import uniffi.bitkitcore.IcJitEntry
import javax.inject.Inject

@HiltViewModel
class BlocktankViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningService: LightningService,
) : ViewModel() {
    var orders = mutableListOf<IBtOrder>()
        private set
    var cJitEntries = mutableListOf<IcJitEntry>()
        private set
    var info by mutableStateOf<IBtInfo?>(null)
        private set

    private var isRefreshing = false

    private val pollingFlow: Flow<Unit>
        get() = flow {
            while (currentCoroutineContext().isActive) {
                emit(Unit)
                delay(Env.blocktankOrderRefreshInterval)
            }
        }.flowOn(bgDispatcher)


    init {
        viewModelScope.launch {
            refreshInfo()
        }
        startPolling()
    }

    suspend fun refreshInfo() {
        try {
            info = coreService.blocktank.info(refresh = false) // instantly load from cache first
            info = coreService.blocktank.info(refresh = true)
        } catch (e: Throwable) {
            Logger.error("Failed to refresh Blocktank info", e)
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            pollingFlow.collect {
                refreshOrders()
            }
        }
    }

    fun triggerRefreshOrders() {
        viewModelScope.launch {
            refreshOrders()
        }
    }

    suspend fun refreshOrders() {
        if (isRefreshing) return
        isRefreshing = true
        try {
            Logger.debug("Refreshing orders...")

            // Sync instantly from cache
            orders = coreService.blocktank.orders(refresh = false).toMutableList()
            cJitEntries = coreService.blocktank.cjitOrders(refresh = false).toMutableList()
            // Update from server
            orders = coreService.blocktank.orders(refresh = true).toMutableList()
            cJitEntries = coreService.blocktank.cjitOrders(refresh = true).toMutableList()

            Logger.debug("Orders refreshed")
        } catch (e: Throwable) {
            Logger.error("Failed to refresh orders", e)
        } finally {
            isRefreshing = false
        }
    }

    suspend fun createCjit(amountSats: ULong, description: String): IcJitEntry {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        return coreService.blocktank.createCjit(
            channelSizeSat = amountSats * 2u, // TODO: confirm default from RN app
            invoiceSat = amountSats,
            invoiceDescription = description,
            nodeId = nodeId,
            channelExpiryWeeks = 2u, // TODO: check default value in RN app
            options = CreateCjitOptions(source = "bitkit-android", discountCode = null)
        )
    }

    suspend fun createOrder(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong = spendingBalanceSats * 2u,
        channelExpiryWeeks: UInt = 6u,
    ): IBtOrder {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val timestamp = nowTimestamp().toString()
        val signature = lightningService.sign("channelOpen-$timestamp")

        val options = CreateOrderOptions(
            clientBalanceSat = spendingBalanceSats,
            lspNodeId = null,
            couponCode = "",
            source = "bitkit-android",
            discountCode = null,
            turboChannel = true,
            zeroConfPayment = false,
            zeroReserve = true,
            clientNodeId = nodeId,
            signature = signature,
            timestamp = timestamp,
            refundOnchainAddress = null,
            announceChannel = false,
        )

        return coreService.blocktank.newOrder(
            lspBalanceSat = receivingBalanceSats,
            channelExpiryWeeks = channelExpiryWeeks,
            options = options,
        )
    }

    suspend fun estimateOrderFee(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong,
        channelExpiryWeeks: UInt = 6u,
    ): IBtEstimateFeeResponse2 {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val options = defaultCreateOrderOptions.copy(
            clientBalanceSat = spendingBalanceSats,
            clientNodeId = nodeId,
        )

        return coreService.blocktank.newOrderFeeEstimate(
            lspBalanceSat = receivingBalanceSats,
            channelExpiryWeeks = channelExpiryWeeks,
            options = options,
        )
    }

    suspend fun openChannel(orderId: String): IBtOrder {
        val order = coreService.blocktank.open(orderId)

        val index = orders.indexOfFirst { it.id == order.id }
        if (index != -1) {
            orders[index] = order
        }
        return order
    }

    fun totalBtChannelsValueSats(): ULong {
        val channels = lightningService.channels ?: return 0u

        val btNodeIds = info?.nodes?.map { it.pubkey } ?: return 0u
        val btChannels = channels.filter { btNodeIds.contains(it.counterpartyNodeId) }
        val totalValue = btChannels.sumOf { it.channelValueSats }
        return totalValue
    }
}

private val defaultCreateOrderOptions = CreateOrderOptions(
    clientBalanceSat = 0uL,
    lspNodeId = null,
    couponCode = "",
    source = "bitkit-android",
    discountCode = null,
    turboChannel = true,
    zeroConfPayment = false,
    zeroReserve = true,
    clientNodeId = null,
    signature = null,
    timestamp = null,
    refundOnchainAddress = null,
    announceChannel = false,
)
