package to.bitkit.repositories

import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.ChannelLiquidityOptions
import com.synonym.bitkitcore.ChannelLiquidityParams
import com.synonym.bitkitcore.CreateCjitOptions
import com.synonym.bitkitcore.CreateOrderOptions
import com.synonym.bitkitcore.DefaultLspBalanceParams
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.calculateChannelLiquidityOptions
import com.synonym.bitkitcore.getDefaultLspBalance
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.models.CjitQuoteValidator
import to.bitkit.models.EUR
import to.bitkit.models.safe
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

@Singleton
class BlocktankRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningService: LightningService,
    private val currencyRepo: CurrencyRepo,
    private val cacheStore: CacheStore,
    private val lightningRepo: LightningRepo,
) {
    private val repoScope = appScope(bgDispatcher, TAG)

    private val _blocktankState = MutableStateFlow(BlocktankState())
    val blocktankState: StateFlow<BlocktankState> = _blocktankState.asStateFlow()

    @Volatile
    private var isRefreshing = false

    init {
        observePaidOrders()

        repoScope.launch {
            refreshInfo()
            loadCachedOrders()
        }
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
                            paidOrders = state.orders.filter { order -> order.id in paidOrderIds }.toImmutableList(),
                        )
                    }
                }
        }
    }

    suspend fun getCjitEntry(channel: ChannelDetails): IcJitEntry? = withContext(bgDispatcher) {
        val fundingTxo = channel.fundingTxo ?: return@withContext null

        fun List<IcJitEntry>.matching(): IcJitEntry? = firstOrNull { entry ->
            val fundingTx = entry.channel?.fundingTx ?: return@firstOrNull false
            fundingTx.id == fundingTxo.txid && fundingTx.vout == fundingTxo.vout.toULong()
        }

        _blocktankState.value.cjitEntries.matching()
    }

    suspend fun refreshInfo() = withContext(bgDispatcher) {
        runCatching {
            // Load from cache first
            val cachedInfo = coreService.blocktank.info(refresh = false)
            _blocktankState.update { it.copy(info = cachedInfo) }

            // Then refresh from server
            val info = coreService.blocktank.info(refresh = true)
            _blocktankState.update { it.copy(info = info) }

            Logger.debug("Blocktank info refreshed", context = TAG)
        }.onFailure {
            Logger.error("Failed to refresh blocktank info", it, context = TAG)
        }
    }

    suspend fun loadCachedOrders(): Result<Unit> = withContext(bgDispatcher) {
        if (isRefreshing) return@withContext Result.success(Unit)
        isRefreshing = true

        runCatching {
            val paidOrderIds = cacheStore.data.first().paidOrders.keys

            val cachedOrders = coreService.blocktank.orders(refresh = false)
            val cachedCjitEntries = coreService.blocktank.cjitEntries(refresh = false)
            _blocktankState.update { state ->
                state.copy(
                    orders = cachedOrders.toImmutableList(),
                    cjitEntries = cachedCjitEntries.toImmutableList(),
                    paidOrders = cachedOrders.filter { order -> order.id in paidOrderIds }.toImmutableList(),
                )
            }

            Logger.debug(
                "Loaded cached orders: ${cachedOrders.size} orders, ${cachedCjitEntries.size} cjit entries",
                context = TAG,
            )
        }.also {
            isRefreshing = false
        }
    }

    suspend fun refreshMinCjitSats() = withContext(bgDispatcher) {
        runCatching {
            val lspBalance = getDefaultLspBalance(clientBalance = 0u)
            val fees = estimateOrderFee(spendingBalanceSats = 0u, receivingBalanceSats = lspBalance).getOrThrow()

            val minimum = (ceil(fees.feeSat.toDouble() * 1.1 / 1000) * 1000).toInt()
            _blocktankState.update { it.copy(minCjitSats = minimum) }

            Logger.debug("Updated minCjitSats to: $minimum", context = TAG)
        }.onFailure {
            Logger.error("Failed to refresh minCjitSats", it, context = TAG)
        }
    }

    suspend fun createCjit(
        amountSats: ULong,
        description: String = "",
    ): Result<IcJitEntry> = withContext(bgDispatcher) {
        runSuspendCatching {
            val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted()
            val maxChannelSizeSat = freshMaxChannelSizeSat()
            if (maxChannelSizeSat != null && amountSats > maxChannelSizeSat) {
                throw ServiceError.ChannelSizeExceedsMaximum()
            }
            val lspBalance = getDefaultLspBalance(clientBalance = amountSats)
            if (!canFitChannelSize(amountSats, lspBalance)) {
                throw ServiceError.ChannelSizeExceedsMaximum()
            }
            val channelSizeSat = amountSats.safe() + lspBalance.safe()

            val cjitEntry = coreService.blocktank.createCjit(
                channelSizeSat = channelSizeSat,
                invoiceSat = amountSats,
                invoiceDescription = description,
                nodeId = nodeId,
                channelExpiryWeeks = DEFAULT_CHANNEL_EXPIRY_WEEKS,
                options = CreateCjitOptions(source = DEFAULT_SOURCE, discountCode = null)
            )
            CjitQuoteValidator.validate(
                invoiceSat = amountSats,
                feeSat = cjitEntry.feeSat,
                channelSizeSat = cjitEntry.channelSizeSat,
            ).getOrThrow()

            repoScope.launch { loadCachedOrders() }

            return@runSuspendCatching cjitEntry
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it.toCjitError()) },
        ).onFailure {
            Logger.error("Failed to create CJIT", it, context = TAG)
        }
    }

    suspend fun maxCjitAmountSats(): Result<ULong?> = withContext(bgDispatcher) {
        runSuspendCatching {
            val maxChannelSizeSat = freshMaxChannelSizeSat() ?: return@runSuspendCatching null
            var lowerBound = 0uL
            var upperBound = maxChannelSizeSat

            while (lowerBound < upperBound) {
                val distance = upperBound.safe() - lowerBound.safe()
                val step = (distance.safe() + 1uL.safe()) / 2uL
                val candidate = lowerBound.safe() + step.safe()
                if (canCreateCjit(candidate, maxChannelSizeSat)) {
                    lowerBound = candidate
                } else {
                    upperBound = candidate - 1uL
                }
            }

            lowerBound
        }.onFailure {
            Logger.error("Failed to calculate max CJIT amount", it, context = TAG)
        }
    }

    suspend fun createOrder(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong = spendingBalanceSats * 2u,
        channelExpiryWeeks: UInt = DEFAULT_CHANNEL_EXPIRY_WEEKS,
    ): Result<IBtOrder> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (lightningService.nodeId == null) throw ServiceError.NodeNotStarted()

            currentCoroutineContext().ensureActive()
            val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)
            currentCoroutineContext().ensureActive()

            Logger.info(
                "Buying channel with " +
                    "clientBalanceSat: '$spendingBalanceSats', " +
                    "lspBalanceSat: '$receivingBalanceSats', " +
                    "channelExpiryWeeks: '$channelExpiryWeeks', " +
                    "zeroConf: '${options.zeroConf}', " +
                    "zeroReserve: '${options.zeroReserve}', " +
                    "announceChannel: '${options.announceChannel}'",
                context = TAG,
            )

            val order = coreService.blocktank.newOrder(
                lspBalanceSat = receivingBalanceSats,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options,
            )

            repoScope.launch { loadCachedOrders() }

            return@runSuspendCatching order
        }.onFailure {
            Logger.error("Failed to create order", it, context = TAG)
        }
    }

    suspend fun estimateOrderFee(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong,
        channelExpiryWeeks: UInt = DEFAULT_CHANNEL_EXPIRY_WEEKS,
    ): Result<IBtEstimateFeeResponse2> = withContext(bgDispatcher) {
        Logger.info("Estimating order fee for spendingSats=$spendingBalanceSats, receivingSats=$receivingBalanceSats")

        runCatching {
            val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)

            val estimate = coreService.blocktank.estimateFee(
                lspBalanceSat = receivingBalanceSats,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options,
            )

            Logger.debug("Estimated order fee: '$estimate'")

            return@runCatching estimate
        }.onFailure {
            Logger.error("Failed to estimate order fee", it, context = TAG)
        }
    }

    suspend fun openChannel(orderId: String): Result<IBtOrder> = withContext(bgDispatcher) {
        runCatching {
            Logger.debug("Opening channel for order: '$orderId'", context = TAG)
            val order = coreService.blocktank.open(orderId)

            // Update the order in state
            val updatedOrders = _blocktankState.value.orders.toMutableList()
            val index = updatedOrders.indexOfFirst { it.id == order.id }
            if (index != -1) {
                updatedOrders[index] = order
            }

            _blocktankState.update { state -> state.copy(orders = updatedOrders.toImmutableList()) }

            return@runCatching order
        }.onFailure {
            Logger.error("Failed to open channel for order: $orderId", it, context = TAG)
        }
    }

    suspend fun getOrder(
        orderId: String,
        refresh: Boolean = false,
    ): Result<IBtOrder?> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (refresh) {
                loadCachedOrders().getOrThrow()
            }
            val order = _blocktankState.value.orders.find { it.id == orderId }
            return@runSuspendCatching order
        }.onFailure {
            Logger.error("Failed to get order: $orderId", it, context = TAG)
        }
    }

    /** Reads [orderIds] from the local order cache, without calling the LSP, and merges them into state. */
    suspend fun fetchOrders(orderIds: List<String>): Result<List<IBtOrder>> = withContext(bgDispatcher) {
        runSuspendCatching {
            val fetched = coreService.blocktank.orders(orderIds = orderIds, refresh = false)
            val fetchedById = fetched.associateBy { it.id }
            _blocktankState.update { state ->
                val cachedIds = state.orders.map { it.id }.toSet()
                val updated = state.orders.map { fetchedById[it.id] ?: it } +
                    fetched.filterNot { it.id in cachedIds }
                state.copy(orders = updated.toImmutableList())
            }
            fetched
        }.onFailure {
            Logger.warn("Failed to fetch orders '$orderIds'", it, context = TAG)
        }
    }

    private suspend fun defaultCreateOrderOptions(clientBalanceSat: ULong): CreateOrderOptions {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted()
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

        val satsPerEur = getSatsPerEur()
            ?: throw ServiceError.CurrencyRateUnavailable()

        val params = DefaultLspBalanceParams(
            clientBalanceSat = clientBalance,
            maxChannelSizeSat = _blocktankState.value.info?.options?.maxChannelSizeSat ?: 0uL,
            satsPerEur = satsPerEur
        )

        return@withContext getDefaultLspBalance(params)
    }

    private suspend fun freshMaxChannelSizeSat(): ULong? {
        refreshInfo()

        return _blocktankState.value.info?.options?.maxChannelSizeSat?.takeIf { it > 0uL }
    }

    private suspend fun canCreateCjit(amountSats: ULong, maxChannelSizeSat: ULong): Boolean {
        if (amountSats > maxChannelSizeSat) return false

        val lspBalance = getDefaultLspBalance(clientBalance = amountSats)
        val remainingCapacity = maxChannelSizeSat.safe() - amountSats.safe()
        return lspBalance <= remainingCapacity
    }

    private fun canFitChannelSize(amountSats: ULong, lspBalance: ULong): Boolean {
        val maxChannelSizeSat = _blocktankState.value.info?.options?.maxChannelSizeSat?.takeIf { it > 0uL }
            ?: return true

        if (amountSats > maxChannelSizeSat) return false

        val remainingCapacity = maxChannelSizeSat.safe() - amountSats.safe()
        return lspBalance <= remainingCapacity
    }

    fun calculateLiquidityOptions(clientBalanceSat: ULong): Result<ChannelLiquidityOptions> {
        val blocktankInfo = blocktankState.value.info
            ?: return Result.failure(ServiceError.BlocktankInfoUnavailable())

        val satsPerEur = getSatsPerEur()
            ?: return Result.failure(ServiceError.CurrencyRateUnavailable())

        val existingChannelsTotalSat = totalBtChannelsValueSats(blocktankInfo)

        val params = ChannelLiquidityParams(
            clientBalanceSat = clientBalanceSat,
            existingChannelsTotalSat = existingChannelsTotalSat,
            minChannelSizeSat = blocktankInfo.options.minChannelSizeSat,
            maxChannelSizeSat = blocktankInfo.options.maxChannelSizeSat,
            satsPerEur = satsPerEur
        )

        return Result.success(calculateChannelLiquidityOptions(params))
    }

    private fun getSatsPerEur(): ULong? {
        return currencyRepo.convertFiatToSats(BigDecimal(1), EUR).getOrNull()
    }

    private fun totalBtChannelsValueSats(info: IBtInfo?): ULong {
        val channels = lightningRepo.getChannels() ?: return 0u
        val btNodeIds = info?.nodes?.map { it.pubkey } ?: return 0u

        val btChannels = channels.filter { btNodeIds.contains(it.counterpartyNodeId) }

        return btChannels.sumOf { it.channelValueSats }
    }

    suspend fun resetState() = withContext(bgDispatcher) {
        _blocktankState.update { BlocktankState() }
        Logger.debug("Blocktank state reset", context = TAG)
    }

    suspend fun restoreFromBackup(payload: BlocktankBackupV1): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.blocktank.upsertOrderList(payload.orders)
            coreService.blocktank.upsertCjitList(payload.cjitEntries)
            payload.info?.let { info ->
                coreService.blocktank.setInfo(info)
            }

            // We don't refresh orders here because we rely on the polling mechanism.
            // We also don't restore `paidOrders` the refresh interval uses restored paidOrderIds to rebuild the list.

            _blocktankState.update {
                it.copy(
                    orders = payload.orders.toImmutableList(),
                    cjitEntries = payload.cjitEntries.toImmutableList(),
                    info = payload.info,
                )
            }
        }.onSuccess {
            Logger.debug("Restored ${payload.orders.size} orders, ${payload.cjitEntries.size} CJITs", TAG)
        }
    }

    companion object {
        private const val TAG = "BlocktankRepo"
        private const val DEFAULT_CHANNEL_EXPIRY_WEEKS = 6u
        private const val DEFAULT_SOURCE = "bitkit-android"
    }
}

internal fun Throwable.toCjitError(): Throwable {
    if (this is ServiceError.ChannelSizeExceedsMaximum ||
        this is ServiceError.CjitQuoteInvalid ||
        this is ServiceError.NodeCapacityUnavailable
    ) {
        return this
    }

    return when {
        isNodeCapacityError() -> ServiceError.NodeCapacityUnavailable()
        isMaxChannelSizeError() -> ServiceError.ChannelSizeExceedsMaximum()
        else -> this
    }
}

private fun Throwable.isNodeCapacityError(): Boolean {
    return toString().contains("capacity is above our capacity limit", ignoreCase = true)
}

private fun Throwable.isMaxChannelSizeError(): Boolean {
    val description = toString()
    val maximumErrors = listOf(
        "Channel size is too big",
        "channelSizeExceedsMaximum",
        "maxChannelSizeSat",
    )
    return maximumErrors.any { description.contains(it, ignoreCase = true) }
}

@Stable
data class BlocktankState(
    val orders: ImmutableList<IBtOrder> = persistentListOf(),
    val paidOrders: ImmutableList<IBtOrder> = persistentListOf(),
    val cjitEntries: ImmutableList<IcJitEntry> = persistentListOf(),
    val info: IBtInfo? = null,
    val minCjitSats: Int? = null,
)
