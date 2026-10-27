package to.bitkit.repositories

import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeout
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.services.CoreService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class BlocktankRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val cacheStore: CacheStore,
    @Named("enablePolling") private val enablePolling: Boolean,
) {
    private val repoScope = appScope(bgDispatcher, TAG)

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

        val cached = _blocktankState.value.cjitEntries
        cached.matching()?.let { return@withContext it }

        // A ChannelReady can only be a CJIT if a live cached entry is still awaiting its channel; otherwise skip
        // the server round-trip so a non-CJIT transfer confirmation isn't delayed by a slow Blocktank API.
        val hasPendingCjit = cached.any {
            it.channel == null && it.state != CJitStateEnum.EXPIRED && it.state != CJitStateEnum.FAILED
        }
        if (cached.isNotEmpty() && !hasPendingCjit) return@withContext null

        // Match against the freshly fetched list so a concurrent refreshOrders() can't clobber state before we read.
        return@withContext refreshCjitEntries().matching()
    }

    private suspend fun refreshCjitEntries(): List<IcJitEntry> {
        repeat(CJIT_REFRESH_ATTEMPTS) { attempt ->
            val entries = runCatching {
                withTimeout(CJIT_REFRESH_TIMEOUT) {
                    coreService.blocktank.cjitEntries(refresh = true)
                }
            }.mapCatching { entries ->
                _blocktankState.update { it.copy(cjitEntries = entries.toImmutableList()) }
                entries
            }.getOrElse {
                if (it is CancellationException && it !is TimeoutCancellationException) throw it
                if (attempt == CJIT_REFRESH_ATTEMPTS - 1) {
                    Logger.warn("Failed to refresh CJIT entries; using cached state", it, context = TAG)
                }
                null
            }

            entries?.let { return it }
            delay(CJIT_REFRESH_RETRY_DELAY)
        }
        return _blocktankState.value.cjitEntries
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

    suspend fun refreshOrders(): Result<Unit> = withContext(bgDispatcher) {
        if (isRefreshing) return@withContext Result.success(Unit)
        isRefreshing = true

        runCatching {
            Logger.verbose("Refreshing blocktank orders…", context = TAG)

            val paidOrderIds = cacheStore.data.first().paidOrders.keys

            // Sync instantly from cache
            val cachedOrders = coreService.blocktank.orders(refresh = false)
            val cachedCjitEntries = coreService.blocktank.cjitEntries(refresh = false)
            _blocktankState.update { state ->
                state.copy(
                    orders = cachedOrders.toImmutableList(),
                    cjitEntries = cachedCjitEntries.toImmutableList(),
                    paidOrders = cachedOrders.filter { order -> order.id in paidOrderIds }.toImmutableList(),
                )
            }

            // Then refresh from server
            val orders = coreService.blocktank.orders(refresh = true)
            val cjitEntries = coreService.blocktank.cjitEntries(refresh = true)
            _blocktankState.update { state ->
                state.copy(
                    orders = orders.toImmutableList(),
                    cjitEntries = cjitEntries.toImmutableList(),
                    paidOrders = orders.filter { order -> order.id in paidOrderIds }.toImmutableList(),
                )
            }

            Logger.debug(
                "Orders refreshed: ${orders.size} orders, " +
                    "${cjitEntries.size} cjit entries, " +
                    "${_blocktankState.value.paidOrders.size} paid orders",
                context = TAG
            )
            openChannelWithPaidOrders()
        }.also {
            isRefreshing = false
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
                refreshOrders().getOrThrow()
            }
            val order = _blocktankState.value.orders.find { it.id == orderId }
            return@runSuspendCatching order
        }.onFailure {
            Logger.error("Failed to get order: $orderId", it, context = TAG)
        }
    }

    /** Fetches [orderIds] from the LSP, including orders the local cache has never seen, and caches them. */
    suspend fun fetchOrders(orderIds: List<String>): Result<List<IBtOrder>> = withContext(bgDispatcher) {
        runSuspendCatching {
            val fetched = coreService.blocktank.orders(orderIds = orderIds, refresh = true)
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

    private suspend fun openChannelWithPaidOrders() = withContext(bgDispatcher) {
        _blocktankState.value.paidOrders.filter { it.state2 == BtOrderState2.PAID }.forEach { order ->
            openChannel(order.id)
        }
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
        private const val CJIT_REFRESH_ATTEMPTS = 3
        private val CJIT_REFRESH_TIMEOUT = 5.seconds
        private val CJIT_REFRESH_RETRY_DELAY = 1.seconds
    }
}

@Stable
data class BlocktankState(
    val orders: ImmutableList<IBtOrder> = persistentListOf(),
    val paidOrders: ImmutableList<IBtOrder> = persistentListOf(),
    val cjitEntries: ImmutableList<IcJitEntry> = persistentListOf(),
    val info: IBtInfo? = null,
)
