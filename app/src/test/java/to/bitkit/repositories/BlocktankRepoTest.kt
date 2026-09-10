package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.CreateOrderOptions
import com.synonym.bitkitcore.FundingTx
import com.synonym.bitkitcore.IBtChannel
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOptions
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.OutPoint
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.AppCacheData
import to.bitkit.data.BlocktankRefundAddress
import to.bitkit.data.CacheStore
import to.bitkit.models.BlocktankBackupV1
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.BlocktankService
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        whenever { coreService.isGeoBlocked() }.thenReturn(false)
        whenever { coreService.isAddressUsed(any()) }.thenReturn(false)
        whenever(lightningService.nodeId).thenReturn("node-id")
        whenever { lightningService.sign(any()) }.thenReturn("signature")

        whenever { blocktankService.info(refresh = false) }.thenReturn(mock())
        whenever { blocktankService.info(refresh = true) }.thenReturn(mock())

        whenever { blocktankService.orders(refresh = false) }.thenReturn(emptyList())
        whenever { blocktankService.orders(refresh = true) }.thenReturn(emptyList())

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
            enablePolling = false,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `createOrder sends a persisted native SegWit refund address`() = test {
        val addressInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        val order = mock<IBtOrder>()
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(Result.success(addressInfo))
        whenever(blocktankService.newOrder(any(), any(), any())).thenReturn(order)
        sut = createSut()

        val result = sut.createOrder(spendingBalanceSats = 50_000u, receivingBalanceSats = 100_000u)

        assertEquals(order, result.getOrThrow())
        assertEquals(BlocktankRefundAddress(addressInfo.address, 0), cacheData.value.blocktankRefundAddress)
        val options = argumentCaptor<CreateOrderOptions>()
        verify(blocktankService).newOrder(any(), any(), options.capture())
        assertEquals(addressInfo.address, options.firstValue.refundOnchainAddress)
        verify(lightningRepo).newAddressInfoForType(AddressType.P2WPKH)
        verify(coreService).isAddressUsed(addressInfo.address)
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
    fun `failed and repeated orders reuse one unused persisted refund address across repo instances`() = test {
        val addressInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        val order = mock<IBtOrder>()
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(Result.success(addressInfo))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(Result.success(addressInfo))
        whenever(blocktankService.newOrder(any(), any(), any()))
            .thenThrow(RuntimeException("backend unavailable"))
            .thenReturn(order)
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)
        sut = createSut()
        repeat(3) { assertEquals(order, sut.createOrder(50_000u).getOrThrow()) }

        verify(lightningRepo, times(1)).newAddressInfoForType(AddressType.P2WPKH)
        verify(lightningRepo, times(3)).addressInfoForType(AddressType.P2WPKH, 0)
        verify(lightningRepo, times(3)).revealReceiveAddresses(0, AddressType.P2WPKH)
        verify(coreService, times(4)).isAddressUsed(addressInfo.address)
    }

    @Test
    fun `recorded refund payment skips used candidates and rotates to the first unused address`() = test {
        val oldInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        val firstUsedInfo = AddressDerivationInfo(address = "bcrt1qrefund1", index = 1)
        val secondUsedInfo = AddressDerivationInfo(address = "bcrt1qrefund2", index = 2)
        val unusedInfo = AddressDerivationInfo(address = "bcrt1qrefund3", index = 3)
        val order = mock<IBtOrder>()
        cacheData.value = AppCacheData(blocktankRefundAddress = BlocktankRefundAddress(oldInfo.address, 0))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(Result.success(oldInfo))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 3)).thenReturn(Result.success(unusedInfo))
        whenever(coreService.isAddressUsed(oldInfo.address)).thenReturn(true)
        whenever(coreService.isAddressUsed(firstUsedInfo.address)).thenReturn(true)
        whenever(coreService.isAddressUsed(secondUsedInfo.address)).thenReturn(true)
        whenever(coreService.isAddressUsed(unusedInfo.address)).thenReturn(false)
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH))
            .thenReturn(Result.success(firstUsedInfo))
            .thenReturn(Result.success(secondUsedInfo))
            .thenReturn(Result.success(unusedInfo))
        whenever(blocktankService.newOrder(any(), any(), any())).thenReturn(order)
        sut = createSut()

        assertEquals(order, sut.createOrder(50_000u).getOrThrow())
        assertEquals(order, sut.createOrder(50_000u).getOrThrow())

        verify(lightningRepo, times(3)).newAddressInfoForType(AddressType.P2WPKH)
        verify(cacheStore, times(1)).update(any())
        assertEquals(BlocktankRefundAddress(unusedInfo.address, 3), cacheData.value.blocktankRefundAddress)
        val options = argumentCaptor<CreateOrderOptions>()
        verify(blocktankService, times(2)).newOrder(any(), any(), options.capture())
        assertEquals(listOf(unusedInfo.address, unusedInfo.address), options.allValues.map { it.refundOnchainAddress })
    }

    @Test
    fun `concurrent orders allocate one shared refund address`() = test {
        val allocationStarted = CompletableDeferred<Unit>()
        val allowAllocation = CompletableDeferred<Unit>()
        val addressInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        val order = mock<IBtOrder>()
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).doSuspendableAnswer {
            allocationStarted.complete(Unit)
            allowAllocation.await()
            Result.success(addressInfo)
        }
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(Result.success(addressInfo))
        whenever(blocktankService.newOrder(any(), any(), any())).thenReturn(order)
        sut = createSut()

        val first = async { sut.createOrder(50_000u) }
        allocationStarted.await()
        val second = async { sut.createOrder(50_000u) }
        allowAllocation.complete(Unit)

        assertEquals(order, first.await().getOrThrow())
        assertEquals(order, second.await().getOrThrow())
        verify(lightningRepo, times(1)).newAddressInfoForType(AddressType.P2WPKH)
    }

    @Test
    fun `refund address allocation failure blocks order submission`() = test {
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH))
            .thenReturn(Result.failure(RuntimeException("node persistence failed")))
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `refund address allocation stops after twenty used candidates`() = test {
        val oldInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        val candidates = (1..20).map {
            AddressDerivationInfo(address = "bcrt1qrefund$it", index = it)
        }
        cacheData.value = AppCacheData(blocktankRefundAddress = BlocktankRefundAddress(oldInfo.address, 0))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(Result.success(oldInfo))
        whenever(coreService.isAddressUsed(oldInfo.address)).thenReturn(true)
        candidates.forEach { whenever(coreService.isAddressUsed(it.address)).thenReturn(true) }
        candidates.fold(
            initial = whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)),
        ) { stubbing, candidate -> stubbing.thenReturn(Result.success(candidate)) }
        sut = createSut()

        val error = assertIs<AppError>(sut.createOrder(50_000u).exceptionOrNull())

        assertEquals("Failed to allocate an unused Blocktank refund address", error.message)
        verify(lightningRepo, times(20)).newAddressInfoForType(AddressType.P2WPKH)
        verify(cacheStore, never()).update(any())
        assertEquals(BlocktankRefundAddress(oldInfo.address, 0), cacheData.value.blocktankRefundAddress)
        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `refund address candidate usage lookup failure preserves cached pointer and blocks submission`() = test {
        val oldInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        val candidate = AddressDerivationInfo(address = "bcrt1qrefund1", index = 1)
        cacheData.value = AppCacheData(blocktankRefundAddress = BlocktankRefundAddress(oldInfo.address, 0))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(Result.success(oldInfo))
        whenever(coreService.isAddressUsed(oldInfo.address)).thenReturn(true)
        whenever(coreService.isAddressUsed(candidate.address)).thenThrow(RuntimeException("activity lookup failed"))
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(Result.success(candidate))
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(lightningRepo, times(1)).newAddressInfoForType(AddressType.P2WPKH)
        verify(cacheStore, never()).update(any())
        assertEquals(BlocktankRefundAddress(oldInfo.address, 0), cacheData.value.blocktankRefundAddress)
        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `refund address allocation rejects a nonadvancing candidate index`() = test {
        val oldInfo = AddressDerivationInfo(address = "bcrt1qrefund4", index = 4)
        val repeatedIndexCandidate = AddressDerivationInfo(address = "bcrt1qrefund-repeated", index = 4)
        cacheData.value = AppCacheData(blocktankRefundAddress = BlocktankRefundAddress(oldInfo.address, 4))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 4)).thenReturn(Result.success(oldInfo))
        whenever(coreService.isAddressUsed(oldInfo.address)).thenReturn(true)
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH))
            .thenReturn(Result.success(repeatedIndexCandidate))
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(lightningRepo, times(1)).newAddressInfoForType(AddressType.P2WPKH)
        verify(coreService, never()).isAddressUsed(repeatedIndexCandidate.address)
        verify(cacheStore, never()).update(any())
        assertEquals(BlocktankRefundAddress(oldInfo.address, 4), cacheData.value.blocktankRefundAddress)
        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `refund address allocation cancellation blocks order submission and propagates`() = test {
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).doSuspendableAnswer {
            throw CancellationException("cancelled")
        }
        sut = createSut()

        assertFailsWith<CancellationException> { sut.createOrder(50_000u) }

        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `cancellation after refund cache persistence blocks order submission`() = test {
        val addressInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(Result.success(addressInfo))
        whenever { cacheStore.update(any()) }.doSuspendableAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            cacheData.value = transform(cacheData.value)
            currentCoroutineContext().cancel()
        }
        sut = createSut()

        val result = async { sut.createOrder(50_000u) }

        assertFailsWith<CancellationException> { result.await() }
        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `cached refund lookup failure blocks order submission`() = test {
        cacheData.value = AppCacheData(
            blocktankRefundAddress = BlocktankRefundAddress("bcrt1qrefund0", 0),
        )
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0))
            .thenReturn(Result.failure(RuntimeException("lookup failed")))
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `cached refund reveal failure blocks order submission`() = test {
        val addressInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        cacheData.value = AppCacheData(blocktankRefundAddress = BlocktankRefundAddress(addressInfo.address, 0))
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(Result.success(addressInfo))
        whenever(lightningRepo.revealReceiveAddresses(0, AddressType.P2WPKH))
            .thenReturn(Result.failure(RuntimeException("reveal failed")))
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `refund cache write failure blocks order submission`() = test {
        val addressInfo = AddressDerivationInfo(address = "bcrt1qrefund0", index = 0)
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(Result.success(addressInfo))
        whenever { cacheStore.update(any()) }.thenThrow(RuntimeException("cache write failed"))
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `invalid cached refund index blocks order submission`() = test {
        cacheData.value = AppCacheData(
            blocktankRefundAddress = BlocktankRefundAddress("bcrt1qrefund", Int.MAX_VALUE.toLong() + 1),
        )
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(lightningRepo, never()).addressInfoForType(any(), any())
        verify(blocktankService, never()).newOrder(any(), any(), any())
    }

    @Test
    fun `cached refund ownership mismatch blocks order submission`() = test {
        cacheData.value = AppCacheData(
            blocktankRefundAddress = BlocktankRefundAddress("bcrt1qwrongwallet", 0),
        )
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 0)).thenReturn(
            Result.success(AddressDerivationInfo(address = "bcrt1qactivewallet", index = 0)),
        )
        sut = createSut()

        assertTrue(sut.createOrder(50_000u).isFailure)

        verify(lightningRepo, never()).revealReceiveAddresses(any(), any())
        verify(blocktankService, never()).newOrder(any(), any(), any())
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
    fun `createCjit refreshes max channel size before checking amount`() = test {
        sut = createSut()
        val staleInfo = btInfo(maxChannelSizeSat = 1_000_000u)
        val freshInfo = btInfo(maxChannelSizeSat = 50_000u)
        whenever(coreService.blocktank.info(refresh = false)).thenReturn(staleInfo)
        whenever(coreService.blocktank.info(refresh = true)).thenReturn(staleInfo, freshInfo)
        whenever(coreService.isGeoBlocked()).thenReturn(false)
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
        whenever(coreService.isGeoBlocked()).thenReturn(false)
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

    @Test
    fun `getCjitEntry refreshes when the cache is empty`() = test {
        sut = createSut()
        // Cold start / right after createCjit: cjitEntries hasn't loaded yet, so an empty cache must not be
        // treated as a terminal no-match — the server refresh has to surface the matching entry.
        val fundingTxId = "cjit-funding-tx"
        val channel = mock<IBtChannel>()
        whenever(channel.fundingTx).thenReturn(FundingTx(id = fundingTxId, vout = 0u))
        val entry = mock<IcJitEntry>()
        whenever(entry.channel).thenReturn(channel)
        whenever(coreService.blocktank.cjitEntries(refresh = true)).thenReturn(listOf(entry))

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(OutPoint(txid = fundingTxId, vout = 0u))

        assertEquals(entry, sut.getCjitEntry(channelDetails))
    }

    private fun pendingCjitEntry(): IcJitEntry = mock<IcJitEntry>().apply {
        whenever(channel).thenReturn(null)
        whenever(state).thenReturn(CJitStateEnum.CREATED)
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
