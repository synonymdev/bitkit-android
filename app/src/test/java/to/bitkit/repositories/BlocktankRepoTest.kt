package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.CreateOrderOptions
import com.synonym.bitkitcore.FundingTx
import com.synonym.bitkitcore.IBtChannel
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOptions
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.services.BlocktankService
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlocktankRepoTest : BaseUnitTest() {

    private val coreService: CoreService = mock()
    private val blocktankService: BlocktankService = mock()
    private val lightningService: LightningService = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val cacheStore: CacheStore = mock()
    private val lightningRepo: LightningRepo = mock()
    private val cacheData = MutableStateFlow(AppCacheData())

    private lateinit var sut: BlocktankRepo

    private val testOrder1 = mock<IBtOrder> { on { id } doReturn "order1" }

    @Before
    fun setUp() {
        cacheData.value = AppCacheData()
        whenever(cacheStore.data).thenReturn(cacheData)
        whenever { cacheStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            cacheData.value = transform(cacheData.value)
        }
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(coreService.blocktank).thenReturn(blocktankService)
        whenever { coreService.isAddressUsed(any()) }.thenReturn(false)
        whenever(lightningService.nodeId).thenReturn("node-id")
        whenever { lightningService.sign(any()) }.thenReturn("signature")

        whenever { blocktankService.info(refresh = false) }.thenReturn(mock())
        whenever { blocktankService.info(refresh = true) }.thenReturn(mock())

        whenever { blocktankService.orders(refresh = false) }.thenReturn(emptyList())

        whenever { blocktankService.cjitEntries(refresh = false) }.thenReturn(emptyList())
        whenever { blocktankService.cjitEntries(refresh = true) }.thenReturn(emptyList())
        whenever { lightningRepo.revealReceiveAddresses(any(), any()) }.thenReturn(Result.success(Unit))
    }

    private fun createSut(): BlocktankRepo {
        return BlocktankRepo(
            bgDispatcher = testDispatcher,
            coreService = coreService,
            lightningService = lightningService,
            currencyRepo = currencyRepo,
            cacheStore = cacheStore,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `createOrder submits the order without a refund address`() = test {
        val order = mock<IBtOrder>()
        whenever(blocktankService.newOrder(any(), any(), any())).thenReturn(order)
        sut = createSut()

        val result = sut.createOrder(spendingBalanceSats = 50_000u, receivingBalanceSats = 100_000u)

        assertEquals(order, result.getOrThrow())
        val options = argumentCaptor<CreateOrderOptions>()
        verify(blocktankService).newOrder(any(), any(), options.capture())
        assertNull(options.firstValue.refundOnchainAddress)
        verify(lightningRepo, never()).newAddressInfoForType(any())
        verify(lightningRepo, never()).addressInfoForType(any(), any())
        verify(lightningRepo, never()).revealReceiveAddresses(any(), any())
        assertNull(cacheData.value.blocktankRefundAddress)
    }

    @Test
    fun `estimateOrderFee never allocates a refund address`() = test {
        val estimate = mock<IBtEstimateFeeResponse2>()
        whenever(blocktankService.estimateFee(any(), any(), any())).thenReturn(estimate)
        sut = createSut()

        repeat(10) {
            assertEquals(
                estimate,
                sut.estimateOrderFee(spendingBalanceSats = 50_000u, receivingBalanceSats = 100_000u)
                    .getOrThrow(),
            )
        }

        verify(lightningRepo, never()).newAddressInfoForType(any())
        verify(lightningRepo, never()).addressInfoForType(any(), any())
        verify(lightningRepo, never()).revealReceiveAddresses(any(), any())
        assertNull(cacheData.value.blocktankRefundAddress)
    }

    @Test
    fun `refreshInfo updates state first from cache then server`() = test {
        sut = createSut()

        val cachedInfo = mock<IBtInfo>()
        val serverInfo = mock<IBtInfo>()
        whenever { coreService.blocktank.info(refresh = false) }.thenReturn(cachedInfo)
        whenever { coreService.blocktank.info(refresh = true) }.thenReturn(serverInfo)

        sut.blocktankState.test {
            awaitItem() // Skip initial state

            sut.refreshInfo()

            val cachedState = awaitItem()
            assertEquals(cachedInfo, cachedState.info)

            val serverState = awaitItem()
            assertEquals(serverInfo, serverState.info)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadCachedOrders updates state from cache`() = test {
        sut = createSut()

        val cachedOrders = listOf<IBtOrder>(mock())
        whenever { coreService.blocktank.orders(refresh = false) }.thenReturn(cachedOrders)

        sut.blocktankState.test {
            awaitItem() // Skip initial state

            sut.loadCachedOrders()

            val cachedState = awaitItem()
            assertEquals(cachedOrders, cachedState.orders)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadCachedOrders filters paid orders based on cacheStore data`() = test {
        sut = createSut()
        val expectedOrders = listOf(testOrder1)
        whenever { coreService.blocktank.orders(refresh = false) }.thenReturn(expectedOrders)
        val orderId = "order1"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(paidOrders = mapOf(orderId to "txId"))))

        sut.blocktankState.test {
            awaitItem() // Skip initial state

            sut.loadCachedOrders()

            val state = awaitItem()
            assertEquals(expectedOrders, state.orders)
            assertEquals(1, state.paidOrders.size)
            assertEquals(orderId, state.paidOrders.first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openChannel updates state after open`() = test {
        whenever { coreService.blocktank.orders(refresh = false) }.thenReturn(listOf(testOrder1))
        sut = createSut()
        val orderId = "order1"
        val updatedOrder = mock<IBtOrder>().apply { whenever(id).thenReturn(orderId) }
        whenever { coreService.blocktank.open(orderId) }.thenReturn(updatedOrder)

        sut.blocktankState.test {
            awaitItem() // Skip initial state

            val result = sut.openChannel(orderId)
            verify(coreService.blocktank).open(orderId)

            assertTrue(result.isSuccess)
            assertEquals(updatedOrder, result.getOrThrow())

            // // Verify state was updated
            val state = awaitItem()
            assertEquals(updatedOrder, state.orders.first { it.id == orderId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getOrder returns order from state after refresh`() = test {
        sut = createSut()

        whenever { coreService.blocktank.orders(refresh = false) }.thenReturn(listOf(testOrder1))
        val result = sut.getOrder(testOrder1.id, refresh = true)

        assertTrue(result.isSuccess)
        assertEquals(testOrder1, result.getOrThrow())
    }

    @Test
    fun `fetchOrders caches an order missing from the local cache and replaces a stale one`() = test {
        val staleOrder = mock<IBtOrder> { on { id } doReturn "order1" }
        val freshOrder = mock<IBtOrder> { on { id } doReturn "order1" }
        val uncachedOrder = mock<IBtOrder> { on { id } doReturn "order2" }
        val orderIds = listOf("order1", "order2")
        whenever(blocktankService.orders(refresh = false)).thenReturn(listOf(staleOrder))
        whenever(blocktankService.orders(orderIds = orderIds, refresh = false))
            .thenReturn(listOf(freshOrder, uncachedOrder))
        sut = createSut()
        sut.loadCachedOrders()

        val result = sut.fetchOrders(orderIds)

        assertEquals(listOf(freshOrder, uncachedOrder), result.getOrThrow())
        assertEquals(listOf(freshOrder, uncachedOrder), sut.blocktankState.value.orders)
        assertEquals(uncachedOrder, sut.getOrder("order2").getOrThrow())
    }

    @Test
    fun `fetchOrders returns failure and keeps cached orders when server throws`() = test {
        whenever(blocktankService.orders(refresh = false)).thenReturn(listOf(testOrder1))
        whenever(blocktankService.orders(orderIds = listOf("order2"), refresh = false))
            .thenThrow(RuntimeException("Network error"))
        sut = createSut()
        sut.loadCachedOrders()

        val result = sut.fetchOrders(listOf("order2"))

        assertTrue(result.isFailure)
        assertEquals(listOf(testOrder1), sut.blocktankState.value.orders)
    }

    @Test
    fun `getOrder returns null for non-existent order`() = test {
        sut = createSut()
        val result = sut.getOrder("nonexistent")

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `loadCachedOrders returns failure when cache read throws`() {
        sut = createSut()
        whenever { coreService.blocktank.orders(refresh = false) }.thenThrow(RuntimeException("Network error"))
        test {
            val result = sut.loadCachedOrders()
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `createCjit refreshes max channel size before checking amount`() = test {
        sut = createSut()
        val staleInfo = btInfo(maxChannelSizeSat = 1_000_000u)
        val freshInfo = btInfo(maxChannelSizeSat = 50_000u)
        whenever(coreService.blocktank.info(refresh = false)).thenReturn(staleInfo)
        whenever(coreService.blocktank.info(refresh = true)).thenReturn(staleInfo, freshInfo)
        whenever(lightningService.nodeId).thenReturn("node-id")

        sut.refreshInfo()
        val result = sut.createCjit(amountSats = 100_000u)

        assertIs<ServiceError.ChannelSizeExceedsMaximum>(result.exceptionOrNull())
        verify(coreService.blocktank, times(3)).info(refresh = true)
    }

    @Test
    fun `createCjit uses cached max channel size when fresh info refresh fails`() = test {
        sut = createSut()
        val cachedInfo = btInfo(maxChannelSizeSat = 1_000_000u)
        whenever(coreService.blocktank.info(refresh = false)).thenReturn(cachedInfo)
        whenever(coreService.blocktank.info(refresh = true)).thenReturn(cachedInfo)
            .thenThrow(RuntimeException("Network error"))
        whenever(lightningService.nodeId).thenReturn("node-id")

        sut.refreshInfo()
        val result = sut.createCjit(amountSats = 1_000_001uL)

        assertIs<ServiceError.ChannelSizeExceedsMaximum>(result.exceptionOrNull())
        verify(coreService.blocktank, times(3)).info(refresh = true)
    }

    @Test
    fun `toCjitError maps node capacity limit to node capacity error`() {
        val error = RuntimeException("Node capacity is above our capacity limit.")

        val result = error.toCjitError()

        assertIs<ServiceError.NodeCapacityUnavailable>(result)
    }

    @Test
    fun `toCjitError does not map generic channel size field error to max channel size error`() {
        val error = RuntimeException("channelSizeSat must be above minimum")

        val result = error.toCjitError()

        assertEquals(error, result)
    }

    @Test
    fun `getOrder returns failure when refresh fails`() {
        sut = createSut()
        whenever { coreService.blocktank.orders(refresh = false) }.thenThrow(RuntimeException("Network error"))
        test {
            val result = sut.getOrder(testOrder1.id, refresh = true)
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `getCjitEntry returns null when channel has no funding txo`() = test {
        sut = createSut()
        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(null)

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry does not match a stale unpaid CJIT entry without an opened channel`() = test {
        sut = createSut()
        // A leftover CJIT entry that was never paid: same size & LSP as a transfer-flow channel order,
        // but it never opened a channel. It must not be mistaken for the freshly opened channel.
        val staleEntry = mock<IcJitEntry>()
        whenever(staleEntry.channel).thenReturn(null)
        whenever(staleEntry.state).thenReturn(CJitStateEnum.CREATED)
        seedCjitEntries(staleEntry)

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = "channel-order-funding-tx", vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry matches the entry whose channel funding tx matches`() = test {
        sut = createSut()
        val fundingTxId = "cjit-funding-tx"
        val matchingChannel = mock<IBtChannel>()
        whenever(matchingChannel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val otherChannel = mock<IBtChannel>()
        whenever(otherChannel.fundingTx).thenReturn(FundingTx(id = "other-funding-tx", vout = 0u))
        val matchingEntry = mock<IcJitEntry>()
        whenever(matchingEntry.channel).thenReturn(matchingChannel)
        val otherEntry = mock<IcJitEntry>()
        whenever(otherEntry.channel).thenReturn(otherChannel)
        seedCjitEntries(otherEntry, matchingEntry)

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        assertEquals(matchingEntry, sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry returns null when no CJIT channel funding tx matches`() = test {
        sut = createSut()
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = "cjit-funding-tx", vout = 0u))
        val entry = mock<IcJitEntry>()
        whenever(entry.channel).thenReturn(channel)
        seedCjitEntries(entry)

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = "different-funding-tx", vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry does not match a CJIT entry with the same funding txid but a different vout`() = test {
        sut = createSut()
        // A batched funding tx can hold several channel outputs sharing one txid; only vout distinguishes them.
        val sharedTxId = "shared-funding-tx"
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = sharedTxId, vout = 0u))
        val entry = mock<IcJitEntry>()
        whenever(entry.channel).thenReturn(channel)
        seedCjitEntries(entry)

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = sharedTxId, vout = 1u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    private fun btInfo(maxChannelSizeSat: ULong): IBtInfo {
        val options = mock<IBtInfoOptions>()
        whenever(options.maxChannelSizeSat).thenReturn(maxChannelSizeSat)
        return mock<IBtInfo>().also {
            whenever(it.options).thenReturn(options)
        }
    }

    private suspend fun seedCjitEntries(vararg entries: IcJitEntry) {
        sut.restoreFromBackup(
            BlocktankBackupV1(createdAt = 0L, orders = emptyList(), cjitEntries = entries.toList()),
        )
    }
}
