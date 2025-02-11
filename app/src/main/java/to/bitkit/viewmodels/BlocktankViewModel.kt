package to.bitkit.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.ext.nowTimestamp
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.utils.ServiceError
import uniffi.bitkitcore.CreateCjitOptions
import uniffi.bitkitcore.CreateOrderOptions
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.IBtOrder
import uniffi.bitkitcore.IcJitEntry
import javax.inject.Inject

@HiltViewModel
class BlocktankViewModel @Inject constructor(
    private val coreService: CoreService,
    private val lightningService: LightningService,
) : ViewModel() {
    var orders = mutableListOf<IBtOrder>()
        private set
    var cJitEntries = mutableListOf<IcJitEntry>()
        private set
    var info by mutableStateOf<IBtInfo?>(null)
        private set

    init {
        viewModelScope.launch {
            refreshInfo()
            refreshOrders()
        }
    }

    suspend fun refreshInfo() {
        info = coreService.blocktank.info(refresh = false) // instantly load from cache first
        info = coreService.blocktank.info(refresh = true)
    }

    suspend fun refreshOrders() {
        // Sync instantly from cache
        orders = coreService.blocktank.orders(refresh = false).toMutableList()
        cJitEntries = coreService.blocktank.cjitOrders(refresh = false).toMutableList()
        // Update from server
        orders = coreService.blocktank.orders(refresh = true).toMutableList()
        cJitEntries = coreService.blocktank.cjitOrders(refresh = true).toMutableList()
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

    suspend fun createOrder(spendingBalanceSats: ULong, channelExpiryWeeks: UInt = 6u): IBtOrder {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val receivingBalanceSats = spendingBalanceSats * 2u
        val timestamp = nowTimestamp().toString()
        val signature = lightningService.sign("channelOpen-$timestamp")

        val options = CreateOrderOptions(
            clientBalanceSat = spendingBalanceSats,
            lspNodeId = null,
            couponCode = "",
            source = "bitkit-android",
            discountCode = null,
            turboChannel = false,
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

    suspend fun open(orderId: String): IBtOrder {
        return coreService.blocktank.open(orderId)
    }
}
