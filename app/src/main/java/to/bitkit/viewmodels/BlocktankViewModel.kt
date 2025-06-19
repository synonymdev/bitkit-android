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
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.services.CoreService
import to.bitkit.services.CurrencyService
import to.bitkit.services.LightningService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import uniffi.bitkitcore.CreateCjitOptions
import uniffi.bitkitcore.CreateOrderOptions
import uniffi.bitkitcore.IBtEstimateFeeResponse2
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.IBtOrder
import uniffi.bitkitcore.IcJitEntry
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.min

private const val EUR_CURRENCY = "EUR"
@HiltViewModel
class BlocktankViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningService: LightningService,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {
    var orders = mutableListOf<IBtOrder>()
        private set
    var cJitEntries = mutableListOf<IcJitEntry>()
        private set
    var info by mutableStateOf<IBtInfo?>(null)
        private set
    var minCjitSats by mutableStateOf<Int?>(null) // TODO: cache
        private set

    private var isRefreshing = false

    private val defaultChannelExpiryWeeks = 6u
    private val defaultSource = "bitkit-android"

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

    suspend fun createCjit(amountSats: ULong, description: String = Env.DEFAULT_INVOICE_MESSAGE): IcJitEntry {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val lspBalance = getDefaultLspBalance(clientBalance = amountSats)
        val channelSizeSat = amountSats + lspBalance

        return coreService.blocktank.createCjit(
            channelSizeSat = channelSizeSat,
            invoiceSat = amountSats,
            invoiceDescription = description,
            nodeId = nodeId,
            channelExpiryWeeks = defaultChannelExpiryWeeks,
            options = CreateCjitOptions(source = defaultSource, discountCode = null)
        )
    }

    suspend fun createOrder(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong = spendingBalanceSats * 2u,
        channelExpiryWeeks: UInt = defaultChannelExpiryWeeks,
    ): IBtOrder {
        val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)

        Logger.info("Buying channel with lspBalanceSat: $receivingBalanceSats, channelExpiryWeeks: $channelExpiryWeeks, options: $options")

        return coreService.blocktank.newOrder(
            lspBalanceSat = receivingBalanceSats,
            channelExpiryWeeks = channelExpiryWeeks,
            options = options,
        )
    }

    suspend fun estimateOrderFee(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong,
        channelExpiryWeeks: UInt = defaultChannelExpiryWeeks,
    ): IBtEstimateFeeResponse2 {
        val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)

        return coreService.blocktank.estimateFee(
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

    private suspend fun defaultCreateOrderOptions(clientBalanceSat: ULong): CreateOrderOptions {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val timestamp = nowTimestamp().toString()
        val signature = lightningService.sign("channelOpen-$timestamp")

        return CreateOrderOptions(
            clientBalanceSat = clientBalanceSat,
            lspNodeId = null,
            couponCode = "",
            source = defaultSource,
            discountCode = null,
            zeroConf = true,
            zeroConfPayment = false,
            zeroReserve = true,
            clientNodeId = nodeId,
            signature = signature,
            timestamp = timestamp,
            refundOnchainAddress = null,
            announceChannel = false,
        )
    }

    private suspend fun getDefaultLspBalance(clientBalance: ULong): ULong {
        if (info == null) {
            refreshInfo()
        }
        val maxLspBalance = info?.options?.maxChannelSizeSat ?: 0uL

        // Calculate thresholds in sats
        val threshold1 = currencyRepo.convertFiatToSats(BigDecimal(225), EUR_CURRENCY).getOrNull()
        val threshold2 = currencyRepo.convertFiatToSats(BigDecimal(495), EUR_CURRENCY).getOrNull()
        val defaultLspBalanceSats = currencyRepo.convertFiatToSats(BigDecimal(450), EUR_CURRENCY).getOrNull()

        Logger.debug("getDefaultLspBalance - clientBalance: $clientBalance")
        Logger.debug("getDefaultLspBalance - maxLspBalance: $maxLspBalance")
        Logger.debug("getDefaultLspBalance - defaultLspBalance: $defaultLspBalanceSats")

        if (threshold1 == null || threshold2 == null || defaultLspBalanceSats == null) {
            Logger.error("Failed to get rates for lspBalance calculation", context = "BlocktankViewModel")
            throw ServiceError.CurrencyRateUnavailable
        }

        // Safely calculate lspBalance to avoid arithmetic overflow
        var lspBalance: ULong = 0u
        if (defaultLspBalanceSats > clientBalance) {
            lspBalance = defaultLspBalanceSats - clientBalance
        }
        if (clientBalance > threshold1) {
            lspBalance = clientBalance
        }
        if (clientBalance > threshold2) {
            lspBalance = maxLspBalance
        }

        return min(lspBalance, maxLspBalance)
    }

    suspend fun refreshMinCjitSats() {
        try {
            val lspBalance = getDefaultLspBalance(clientBalance = 0u)

            // Get fees and calculate minimum
            val fees = estimateOrderFee(spendingBalanceSats = 0u, receivingBalanceSats = lspBalance)
            val minimum = (ceil(fees.feeSat.toDouble() * 1.1 / 1000) * 1000).toInt()

            minCjitSats = minimum
            Logger.debug("Updated minCjitSats to: $minimum")
        } catch (e: Throwable) {
            Logger.error("Failed to refresh minCjitSats", e)
            throw e
        }
    }
}
