package to.bitkit.repositories

import com.synonym.bitkitcore.CreateCjitOptions
import com.synonym.bitkitcore.CreateOrderOptions
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.data.CacheStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowTimestamp
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.min

@Singleton
class BlocktankRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningService: LightningService,
    private val currencyRepo: CurrencyRepo,
    private val cacheStore: CacheStore,
    @Named("enablePolling") private val enablePolling: Boolean,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val _blocktankState = MutableStateFlow(BlocktankState())
    val blocktankState: StateFlow<BlocktankState> = _blocktankState.asStateFlow()

    @Volatile
    private var isRefreshing = false

    init {
        startPolling()
        observePaidOrders()

        repoScope.launch {
            refreshInfo()
            refreshOrders()
        }
    }

    private fun startPolling() {
        if (!enablePolling) return
        flow {
            while (currentCoroutineContext().isActive) {
                emit(Unit)
                delay(Env.blocktankOrderRefreshInterval)
            }
        }.flowOn(bgDispatcher)
            .onEach { refreshOrders() }
            .launchIn(repoScope)
    }

    private fun observePaidOrders() {
        repoScope.launch {
            cacheStore.data
                .map { it.paidOrders }
                .distinctUntilChanged()
                .map { it.keys }
                .collect { paidOrderIds ->
                    _blocktankState.update { state ->
                        state.copy(
                            paidOrders = state.orders.filter { order -> order.id in paidOrderIds },
                        )
                    }
                }
        }
    }

    suspend fun refreshInfo() = withContext(bgDispatcher) {
        try {
            // Load from cache first
            val cachedInfo = coreService.blocktank.info(refresh = false)
            _blocktankState.update { it.copy(info = cachedInfo) }

            // Then refresh from server
            val info = coreService.blocktank.info(refresh = true)
            _blocktankState.update { it.copy(info = info) }

            Logger.debug("Blocktank info refreshed", context = TAG)
        } catch (e: Throwable) {
            Logger.error("Failed to refresh blocktank info", e, context = TAG)
        }
    }

    suspend fun refreshOrders() = withContext(bgDispatcher) {
        if (isRefreshing) return@withContext
        isRefreshing = true

        try {
            Logger.debug("Refreshing blocktank orders…", context = TAG)

            val paidOrderIds = cacheStore.data.first().paidOrders.keys

            // Sync instantly from cache
            val cachedOrders = coreService.blocktank.orders(refresh = false)
            val cachedCjitEntries = coreService.blocktank.cjitOrders(refresh = false)
            _blocktankState.update { state ->
                state.copy(
                    orders = cachedOrders,
                    cjitEntries = cachedCjitEntries,
                    paidOrders = cachedOrders.filter { order -> order.id in paidOrderIds },
                )
            }

            // Then refresh from server
            val orders = coreService.blocktank.orders(refresh = true)
            val cjitEntries = coreService.blocktank.cjitOrders(refresh = true)
            _blocktankState.update { state ->
                state.copy(
                    orders = orders,
                    cjitEntries = cjitEntries,
                    paidOrders = orders.filter { order -> order.id in paidOrderIds },
                )
            }

            Logger.debug(
                "Orders refreshed: ${orders.size} orders, ${cjitEntries.size} cjit entries",
                context = TAG
            )
        } catch (e: Throwable) {
            Logger.error("Failed to refresh orders", e, context = TAG)
        } finally {
            isRefreshing = false
        }
    }

    suspend fun refreshMinCjitSats() = withContext(bgDispatcher) {
        try {
            val lspBalance = getDefaultLspBalance(clientBalance = 0u)
            val fees = estimateOrderFee(
                spendingBalanceSats = 0u,
                receivingBalanceSats = lspBalance,
            ).getOrThrow()

            val minimum = (ceil(fees.feeSat.toDouble() * 1.1 / 1000) * 1000).toInt()
            _blocktankState.update { it.copy(minCjitSats = minimum) }

            Logger.debug("Updated minCjitSats to: $minimum", context = TAG)
        } catch (e: Throwable) {
            Logger.error("Failed to refresh minCjitSats", e, context = TAG)
        }
    }

    suspend fun createCjit(
        amountSats: ULong,
        description: String = Env.DEFAULT_INVOICE_MESSAGE,
    ): Result<IcJitEntry> = withContext(bgDispatcher) {
        try {
            val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted
            val lspBalance = getDefaultLspBalance(clientBalance = amountSats)
            val channelSizeSat = amountSats + lspBalance

            val cjitEntry = coreService.blocktank.createCjit(
                channelSizeSat = channelSizeSat,
                invoiceSat = amountSats,
                invoiceDescription = description,
                nodeId = nodeId,
                channelExpiryWeeks = DEFAULT_CHANNEL_EXPIRY_WEEKS,
                options = CreateCjitOptions(source = DEFAULT_SOURCE, discountCode = null)
            )

            repoScope.launch { refreshOrders() }

            Result.success(cjitEntry)
        } catch (e: Throwable) {
            Logger.error("Failed to create CJIT", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun createOrder(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong = spendingBalanceSats * 2u,
        channelExpiryWeeks: UInt = DEFAULT_CHANNEL_EXPIRY_WEEKS,
    ): Result<IBtOrder> = withContext(bgDispatcher) {
        try {
            val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)

            Logger.info(
                "Buying channel with lspBalanceSat: $receivingBalanceSats, channelExpiryWeeks: $channelExpiryWeeks, options: $options",
                context = TAG,
            )

            val order = coreService.blocktank.newOrder(
                lspBalanceSat = receivingBalanceSats,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options,
            )

            repoScope.launch { refreshOrders() }

            Result.success(order)
        } catch (e: Throwable) {
            Logger.error("Failed to create order", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun estimateOrderFee(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong,
        channelExpiryWeeks: UInt = DEFAULT_CHANNEL_EXPIRY_WEEKS,
    ): Result<IBtEstimateFeeResponse2> = withContext(bgDispatcher) {
        try {
            val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)

            val estimate = coreService.blocktank.estimateFee(
                lspBalanceSat = receivingBalanceSats,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options,
            )

            Result.success(estimate)
        } catch (e: Throwable) {
            Logger.error("Failed to estimate order fee", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun openChannel(orderId: String): Result<IBtOrder> = withContext(bgDispatcher) {
        try {
            Logger.debug("Opening channel for order: '$orderId'", context = TAG)
            val order = coreService.blocktank.open(orderId)

            // Update the order in state
            val updatedOrders = _blocktankState.value.orders.toMutableList()
            val index = updatedOrders.indexOfFirst { it.id == order.id }
            if (index != -1) {
                updatedOrders[index] = order
            }

            _blocktankState.update { state -> state.copy(orders = updatedOrders) }

            Result.success(order)
        } catch (e: Throwable) {
            Logger.error("Failed to open channel for order: $orderId", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun getOrder(
        orderId: String,
        refresh: Boolean = false,
    ): Result<IBtOrder?> = withContext(bgDispatcher) {
        try {
            if (refresh) {
                refreshOrders()
            }
            val order = _blocktankState.value.orders.find { it.id == orderId }
            Result.success(order)
        } catch (e: Throwable) {
            Logger.error("Failed to get order: $orderId", e, context = TAG)
            Result.failure(e)
        }
    }

    private suspend fun defaultCreateOrderOptions(clientBalanceSat: ULong): CreateOrderOptions {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted
        val timestamp = nowTimestamp().toString()
        val signature = lightningService.sign("channelOpen-$timestamp")

        return CreateOrderOptions(
            clientBalanceSat = clientBalanceSat,
            lspNodeId = null,
            couponCode = "",
            source = DEFAULT_SOURCE,
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

    suspend fun getDefaultLspBalance(clientBalance: ULong): ULong = withContext(bgDispatcher) {
        if (_blocktankState.value.info == null) {
            refreshInfo()
        }

        val maxLspBalance = _blocktankState.value.info?.options?.maxChannelSizeSat ?: 0uL

        // Calculate thresholds in sats
        val threshold1 = currencyRepo.convertFiatToSats(BigDecimal(225), EUR_CURRENCY).getOrNull()
        val threshold2 = currencyRepo.convertFiatToSats(BigDecimal(495), EUR_CURRENCY).getOrNull()
        val defaultLspBalanceSats = currencyRepo.convertFiatToSats(BigDecimal(450), EUR_CURRENCY).getOrNull()

        Logger.debug("getDefaultLspBalance - clientBalance: $clientBalance", context = TAG)
        Logger.debug("getDefaultLspBalance - maxLspBalance: $maxLspBalance", context = TAG)
        Logger.debug(
            "getDefaultLspBalance - defaultLspBalance: $defaultLspBalanceSats",
            context = TAG
        )

        if (threshold1 == null || threshold2 == null || defaultLspBalanceSats == null) {
            Logger.error("Failed to get rates for lspBalance calculation", context = TAG)
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

        return@withContext min(lspBalance, maxLspBalance)
    }

    suspend fun resetState() = withContext(bgDispatcher) {
        _blocktankState.update { BlocktankState() }
    }

    companion object {
        private const val TAG = "BlocktankRepo"
        private const val DEFAULT_CHANNEL_EXPIRY_WEEKS = 6u
        private const val DEFAULT_SOURCE = "bitkit-android"
        private const val EUR_CURRENCY = "EUR"
    }
}

data class BlocktankState(
    val orders: List<IBtOrder> = emptyList(),
    val paidOrders: List<IBtOrder> = emptyList(),
    val cjitEntries: List<IcJitEntry> = emptyList(),
    val info: IBtInfo? = null,
    val minCjitSats: Int? = null,
)
