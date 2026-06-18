package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.FundingTx
import com.synonym.bitkitcore.IBtChannel
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlocktankRepoTest : BaseUnitTest() {

    private val coreService: CoreService = mock()
    private val lightningService: LightningService = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val cacheStore: CacheStore = mock()
    private val lightningRepo: LightningRepo = mock()

    private lateinit var sut: BlocktankRepo

    private val testOrder1 = mock<IBtOrder> { on { id } doReturn "order1" }

    @Before
    fun setUp() {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(coreService.blocktank).thenReturn(mock())

        wheneverBlocking { coreService.blocktank.info(refresh = false) }.thenReturn(mock())
        wheneverBlocking { coreService.blocktank.info(refresh = true) }.thenReturn(mock())

        wheneverBlocking { coreService.blocktank.orders(refresh = false) }.thenReturn(emptyList())
        wheneverBlocking { coreService.blocktank.orders(refresh = true) }.thenReturn(emptyList())

        wheneverBlocking { coreService.blocktank.cjitEntries(refresh = false) }.thenReturn(emptyList())
        wheneverBlocking { coreService.blocktank.cjitEntries(refresh = true) }.thenReturn(emptyList())
    }

    private fun createSut(): BlocktankRepo {
        return BlocktankRepo(
            bgDispatcher = testDispatcher,
            coreService = coreService,
            lightningService = lightningService,
            currencyRepo = currencyRepo,
            cacheStore = cacheStore,
            enablePolling = false,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `refreshInfo updates state first from cache then server`() = test {
        sut = createSut()

        val cachedInfo = mock<IBtInfo>()
        val serverInfo = mock<IBtInfo>()
        wheneverBlocking { coreService.blocktank.info(refresh = false) }.thenReturn(cachedInfo)
        wheneverBlocking { coreService.blocktank.info(refresh = true) }.thenReturn(serverInfo)

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
    fun `refreshOrders updates state first from cache then server`() = test {
        sut = createSut()

        val cachedOrders = listOf<IBtOrder>(mock())
        val serverOrders = listOf<IBtOrder>(mock())
        wheneverBlocking { coreService.blocktank.orders(refresh = false) }.thenReturn(cachedOrders)
        wheneverBlocking { coreService.blocktank.orders(refresh = true) }.thenReturn(serverOrders)

        sut.blocktankState.test {
            awaitItem() // Skip initial state

            sut.refreshOrders()

            val cachedState = awaitItem()
            assertEquals(cachedOrders, cachedState.orders)

            val serverState = awaitItem()
            assertEquals(serverOrders, serverState.orders)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshOrders filters paid orders based on cacheStore data`() = test {
        sut = createSut()
        val expectedOrders = listOf(testOrder1)
        wheneverBlocking { coreService.blocktank.orders(refresh = true) }.thenReturn(expectedOrders)
        val orderId = "order1"
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData(paidOrders = mapOf(orderId to "txId"))))

        sut.blocktankState.test {
            awaitItem() // Skip initial state

            sut.refreshOrders()

            val state = awaitItem()
            assertEquals(expectedOrders, state.orders)
            assertEquals(1, state.paidOrders.size)
            assertEquals(orderId, state.paidOrders.first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openChannel updates state after open`() = test {
        wheneverBlocking { coreService.blocktank.orders(refresh = true) }.thenReturn(listOf(testOrder1))
        sut = createSut()
        val orderId = "order1"
        val updatedOrder = mock<IBtOrder>().apply { whenever(id).thenReturn(orderId) }
        wheneverBlocking { coreService.blocktank.open(orderId) }.thenReturn(updatedOrder)

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

        wheneverBlocking { coreService.blocktank.orders(refresh = true) }.thenReturn(listOf(testOrder1))
        val result = sut.getOrder(testOrder1.id, refresh = true)

        assertTrue(result.isSuccess)
        assertEquals(testOrder1, result.getOrThrow())
    }

    @Test
    fun `getOrder returns null for non-existent order`() = test {
        sut = createSut()
        val result = sut.getOrder("nonexistent")

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `refreshOrders returns failure when server throws`() {
        sut = createSut()
        whenever { coreService.blocktank.orders(refresh = true) }.thenThrow(RuntimeException("Network error"))
        test {
            val result = sut.refreshOrders()
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `getOrder returns failure when refresh fails`() {
        sut = createSut()
        whenever { coreService.blocktank.orders(refresh = true) }.thenThrow(RuntimeException("Network error"))
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
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(staleEntry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = "channel-order-funding-tx", vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry matches the entry whose channel funding tx matches`() = test {
        sut = createSut()
        seedCjitEntries(pendingCjitEntry())
        val fundingTxId = "cjit-funding-tx"
        val matchingChannel = mock<IBtChannel>()
        whenever(matchingChannel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val otherChannel = mock<IBtChannel>()
        whenever(otherChannel.fundingTx).thenReturn(FundingTx(id = "other-funding-tx", vout = 0u))
        val matchingEntry = mock<IcJitEntry>()
        whenever(matchingEntry.channel).thenReturn(matchingChannel)
        val otherEntry = mock<IcJitEntry>()
        whenever(otherEntry.channel).thenReturn(otherChannel)
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(otherEntry, matchingEntry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        assertEquals(matchingEntry, sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry returns null when no CJIT channel funding tx matches`() = test {
        sut = createSut()
        seedCjitEntries(pendingCjitEntry())
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = "cjit-funding-tx", vout = 0u))
        val entry = mock<IcJitEntry>()
        whenever(entry.channel).thenReturn(channel)
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(entry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = "different-funding-tx", vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry does not match a CJIT entry with the same funding txid but a different vout`() = test {
        sut = createSut()
        seedCjitEntries(pendingCjitEntry())
        // A batched funding tx can hold several channel outputs sharing one txid; only vout distinguishes them.
        val sharedTxId = "shared-funding-tx"
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = sharedTxId, vout = 0u))
        val entry = mock<IcJitEntry>()
        whenever(entry.channel).thenReturn(channel)
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(entry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = sharedTxId, vout = 1u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry does not refresh when no cached CJIT entry is awaiting a channel`() = test {
        sut = createSut()
        // Only an already-associated entry is cached (none awaiting a channel), so this ChannelReady cannot be a
        // CJIT: the server must not be hit even though a refresh would return a matching entry.
        val associatedChannel = mock<IBtChannel>()
        whenever(associatedChannel.fundingTx).thenReturn(FundingTx(id = "other-funding-tx", vout = 0u))
        val associatedEntry = mock<IcJitEntry>()
        whenever(associatedEntry.channel).thenReturn(associatedChannel)
        seedCjitEntries(associatedEntry)

        val fundingTxId = "channel-order-funding-tx"
        val matchingChannel = mock<IBtChannel>()
        whenever(matchingChannel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val matchingEntry = mock<IcJitEntry>()
        whenever(matchingEntry.channel).thenReturn(matchingChannel)
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(matchingEntry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry does not refresh for an expired CJIT entry awaiting no channel`() = test {
        sut = createSut()
        // An expired entry has no channel but can never open one, so it must not trigger a server refresh
        // even though a refresh would surface a matching entry.
        val expiredEntry = mock<IcJitEntry>()
        whenever(expiredEntry.channel).thenReturn(null)
        whenever(expiredEntry.state).thenReturn(CJitStateEnum.EXPIRED)
        seedCjitEntries(expiredEntry)

        val fundingTxId = "channel-order-funding-tx"
        val matchingChannel = mock<IBtChannel>()
        whenever(matchingChannel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val matchingEntry = mock<IcJitEntry>()
        whenever(matchingEntry.channel).thenReturn(matchingChannel)
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(matchingEntry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry returns cached entry without refreshing when already associated`() = test {
        sut = createSut()
        val fundingTxId = "cached-funding-tx"
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val cachedEntry = mock<IcJitEntry>()
        whenever(cachedEntry.channel).thenReturn(channel)
        seedCjitEntries(cachedEntry)

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        // A server refresh would return no entries (setUp default), so a non-null result can only come
        // from the cached state short-circuit, proving the server is not hit when the entry is already known.
        assertEquals(cachedEntry, sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry returns null when a pending CJIT is awaiting but refresh fails`() = test {
        sut = createSut()
        seedCjitEntries(pendingCjitEntry())
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenThrow(RuntimeException("Network error"))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = "missing-funding-tx", vout = 0u))

        assertNull(sut.getCjitEntry(channelDetails))
    }

    @Test
    fun `getCjitEntry retries the refresh and matches after a transient failure`() = test {
        sut = createSut()
        seedCjitEntries(pendingCjitEntry())
        val fundingTxId = "cjit-funding-tx"
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val entry = mock<IcJitEntry>()
        whenever(entry.channel).thenReturn(channel)
        whenever(coreService.blocktank.cjitEntries(refresh = true))
            .thenThrow(RuntimeException("transient"))
            .thenReturn(listOf(entry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        assertEquals(entry, sut.getCjitEntry(channelDetails))
    }

    private fun pendingCjitEntry(): IcJitEntry = mock<IcJitEntry>().apply {
        whenever(channel).thenReturn(null)
        whenever(state).thenReturn(CJitStateEnum.CREATED)
    }

    private suspend fun seedCjitEntries(vararg entries: IcJitEntry) {
        sut.restoreFromBackup(
            BlocktankBackupV1(createdAt = 0L, orders = emptyList(), cjitEntries = entries.toList()),
        )
    }
}
