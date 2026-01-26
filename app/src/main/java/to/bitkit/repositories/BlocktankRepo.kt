package to.bitkit.repositories

import com.synonym.bitkitcore.BtOrderState2
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
import com.synonym.bitkitcore.giftOrder
import com.synonym.bitkitcore.giftPay
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
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.async.ServiceQueue
import to.bitkit.data.CacheStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.nowTimestamp
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.models.EUR
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
class BlocktankRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningService: LightningService,
    private val currencyRepo: CurrencyRepo,
    private val cacheStore: CacheStore,
    @Named("enablePolling") private val enablePolling: Boolean,
    private val lightningRepo: LightningRepo,
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
                delay(Env.lspOrdersRefreshInterval)
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

    suspend fun getCjitEntry(channel: ChannelDetails): IcJitEntry? = withContext(bgDispatcher) {
        return@withContext _blocktankState.value.cjitEntries.firstOrNull { order ->
            order.channelSizeSat == channel.channelValueSats &&
                order.lspNode.pubkey == channel.counterpartyNodeId
        }
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

    suspend fun refreshOrders() = withContext(bgDispatcher) {
        if (isRefreshing) return@withContext
        isRefreshing = true

        runCatching {
            Logger.verbose("Refreshing blocktank orders…", context = TAG)

            val paidOrderIds = cacheStore.data.first().paidOrders.keys

            // Sync instantly from cache
            val cachedOrders = coreService.blocktank.orders(refresh = false)
            val cachedCjitEntries = coreService.blocktank.cjitEntries(refresh = false)
            _blocktankState.update { state ->
                state.copy(
                    orders = cachedOrders,
                    cjitEntries = cachedCjitEntries,
                    paidOrders = cachedOrders.filter { order -> order.id in paidOrderIds },
                )
            }

            // Then refresh from server
            val orders = coreService.blocktank.orders(refresh = true)
            val cjitEntries = coreService.blocktank.cjitEntries(refresh = true)
            _blocktankState.update { state ->
                state.copy(
                    orders = orders,
                    cjitEntries = cjitEntries,
                    paidOrders = orders.filter { order -> order.id in paidOrderIds },
                )
            }

            Logger.debug(
                "Orders refreshed: ${orders.size} orders, " +
                    "${cjitEntries.size} cjit entries, " +
                    "${_blocktankState.value.paidOrders.size} paid orders",
                context = TAG
            )
            openChannelWithPaidOrders()
        }.onFailure {
            Logger.error("Failed to refresh orders", it, context = TAG)
        }

        isRefreshing = false
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
        runCatching {
            if (coreService.isGeoBlocked()) throw ServiceError.GeoBlocked()
            val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted()
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

            return@runCatching cjitEntry
        }.onFailure {
            Logger.error("Failed to create CJIT", it, context = TAG)
        }
    }

    suspend fun createOrder(
        spendingBalanceSats: ULong,
        receivingBalanceSats: ULong = spendingBalanceSats * 2u,
        channelExpiryWeeks: UInt = DEFAULT_CHANNEL_EXPIRY_WEEKS,
    ): Result<IBtOrder> = withContext(bgDispatcher) {
        runCatching {
            if (coreService.isGeoBlocked()) throw ServiceError.GeoBlocked()

            val options = defaultCreateOrderOptions(clientBalanceSat = spendingBalanceSats)

            Logger.info(
                "Buying channel with " +
                    "lspBalanceSat: '$receivingBalanceSats', " +
                    "channelExpiryWeeks: '$channelExpiryWeeks', " +
                    "options: '$options'",
                context = TAG,
            )

            val order = coreService.blocktank.newOrder(
                lspBalanceSat = receivingBalanceSats,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options,
            )

            repoScope.launch { refreshOrders() }

            return@runCatching order
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

    @Suppress("TooGenericExceptionCaught")
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

            _blocktankState.update { state -> state.copy(orders = updatedOrders) }

            return@runCatching order
        }.onFailure {
            Logger.error("Failed to open channel for order: $orderId", it, context = TAG)
        }
    }

    suspend fun getOrder(
        orderId: String,
        refresh: Boolean = false,
    ): Result<IBtOrder?> = withContext(bgDispatcher) {
        runCatching {
            if (refresh) {
                refreshOrders()
            }
            val order = _blocktankState.value.orders.find { it.id == orderId }
            return@runCatching order
        }.onFailure {
            Logger.error("Failed to get order: $orderId", it, context = TAG)
        }
    }

    private suspend fun openChannelWithPaidOrders() = withContext(bgDispatcher) {
        _blocktankState.value.paidOrders.filter { it.state2 == BtOrderState2.PAID }.forEach { order ->
            openChannel(order.id)
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
                    orders = payload.orders,
                    cjitEntries = payload.cjitEntries,
                    info = payload.info,
                )
            }
        }.onSuccess {
            Logger.debug("Restored ${payload.orders.size} orders, ${payload.cjitEntries.size} CJITs", TAG)
        }
    }

    suspend fun claimGiftCode(
        code: String,
        amount: ULong,
        waitTimeout: Duration = TIMEOUT_GIFT_CODE,
    ): Result<GiftClaimResult> = withContext(bgDispatcher) {
        runCatching {
            require(code.isNotBlank()) { "Gift code cannot be blank" }
            require(amount > 0u) { "Gift amount must be positive" }

            Logger.debug("Starting gift code claim: amount=$amount, timeout=$waitTimeout", context = TAG)

            lightningRepo.executeWhenNodeRunning(
                operationName = "claimGiftCode",
                waitTimeout = waitTimeout,
            ) {
                delay(PEER_CONNECTION_DELAY_MS)

                val channels = lightningRepo.getChannelsAsync().getOrThrow()
                val maxInboundCapacity = channels.calculateRemoteBalance()

                Logger.debug(
                    "Liquidity check: maxInbound=$maxInboundCapacity, required=$amount",
                    context = TAG
                )

                if (maxInboundCapacity >= amount) {
                    Logger.debug("Sufficient liquidity available, claiming with existing channel", context = TAG)
                    Result.success(claimGiftCodeWithLiquidity(code, amount))
                } else {
                    Logger.debug("Insufficient liquidity, opening new channel", context = TAG)
                    Result.success(claimGiftCodeWithoutLiquidity(code, amount))
                }
            }.getOrThrow()
        }.onFailure {
            Logger.error("Failed to claim gift code", it, context = TAG)
        }
    }

    private suspend fun claimGiftCodeWithLiquidity(code: String, amount: ULong): GiftClaimResult {
        val invoice = lightningRepo.createInvoice(
            amountSats = null,
            description = "blocktank-gift-code:$code",
            expirySeconds = 3600u,
        ).getOrThrow()

        Logger.debug("Created invoice for gift code, requesting payment from LSP", context = TAG)

        val giftResponse = ServiceQueue.CORE.background {
            giftPay(invoice = invoice)
        }

        Logger.debug("Gift payment request completed: id=${giftResponse.id}", context = TAG)

        return GiftClaimResult.SuccessWithLiquidity(
            paymentHashOrTxId = giftResponse.bolt11PaymentId ?: giftResponse.id,
            sats = giftResponse.bolt11Payment?.paidSat?.toLong()
                ?: giftResponse.appliedGiftCode?.giftSat?.toLong()
                ?: amount.toLong(),
            invoice = invoice,
            code = code,
        )
    }

    private suspend fun claimGiftCodeWithoutLiquidity(code: String, amount: ULong): GiftClaimResult {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted()

        Logger.debug("Creating gift order for code (insufficient liquidity)", context = TAG)

        val order = ServiceQueue.CORE.background {
            giftOrder(clientNodeId = nodeId, code = "blocktank-gift-code:$code")
        }

        val orderId = checkNotNull(order.orderId) { "Order ID is null after gift order creation" }
        Logger.debug("Gift order created: $orderId", context = TAG)

        val openedOrder = openChannel(orderId).getOrThrow()
        Logger.debug("Channel opened for gift order: ${openedOrder.id}", context = TAG)

        val fundingTxId = openedOrder.channel?.fundingTx?.id
        if (fundingTxId == null) {
            Logger.warn("Channel opened but funding transaction ID is null", context = TAG)
        }

        return GiftClaimResult.SuccessWithoutLiquidity(
            paymentHashOrTxId = fundingTxId ?: orderId,
            sats = amount.toLong(),
            invoice = openedOrder.payment?.bolt11Invoice?.request ?: "",
            code = code,
        )
    }

    companion object {
        private const val TAG = "BlocktankRepo"
        private const val DEFAULT_CHANNEL_EXPIRY_WEEKS = 6u
        private const val DEFAULT_SOURCE = "bitkit-android"
        private const val PEER_CONNECTION_DELAY_MS = 2_000L
        private val TIMEOUT_GIFT_CODE = 30.seconds
    }
}

data class BlocktankState(
    val orders: List<IBtOrder> = emptyList(),
    val paidOrders: List<IBtOrder> = emptyList(),
    val cjitEntries: List<IcJitEntry> = emptyList(),
    val info: IBtInfo? = null,
    val minCjitSats: Int? = null,
)

sealed class GiftClaimResult {
    abstract val paymentHashOrTxId: String
    abstract val sats: Long
    abstract val invoice: String
    abstract val code: String

    data class SuccessWithLiquidity(
        override val paymentHashOrTxId: String,
        override val sats: Long,
        override val invoice: String,
        override val code: String,
    ) : GiftClaimResult()

    data class SuccessWithoutLiquidity(
        override val paymentHashOrTxId: String,
        override val sats: Long,
        override val invoice: String,
        override val code: String,
    ) : GiftClaimResult()
}
