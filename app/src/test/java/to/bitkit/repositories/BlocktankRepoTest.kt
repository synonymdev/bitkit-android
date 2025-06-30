package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
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

        wheneverBlocking { coreService.blocktank.cjitOrders(refresh = false) }.thenReturn(emptyList())
        wheneverBlocking { coreService.blocktank.cjitOrders(refresh = true) }.thenReturn(emptyList())
    }

    private fun createSut(): BlocktankRepo {
        return BlocktankRepo(
            bgDispatcher = testDispatcher,
            coreService = coreService,
            lightningService = lightningService,
            currencyRepo = currencyRepo,
            cacheStore = cacheStore,
            enablePolling = false,
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
}
