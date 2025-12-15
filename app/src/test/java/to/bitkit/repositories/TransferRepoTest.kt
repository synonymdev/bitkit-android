package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.FundingTx
import com.synonym.bitkitcore.IBtChannel
import com.synonym.bitkitcore.IBtOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.LightningBalance
import org.lightningdevkit.ldknode.OutPoint
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.TransferType
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TransferRepoTest : BaseUnitTest() {
    private lateinit var sut: TransferRepo

    private val transferDao = mock<TransferDao>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val clock = mock<Clock>()

    companion object Fixtures {
        private const val ID_ORDER = "test-order-id"
        private const val ID_CHANNEL = "test-channel-id"
        private const val ID_TRANSFER = "test-transfer-id"
        private val fundingTxo = OutPoint(txid = "test-funding-tx-id", vout = 0u)
    }

    @Before
    fun setUp() {
        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(emptyList()))

        sut = TransferRepo(
            bgDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            transferDao = transferDao,
            clock = clock,
        )
    }

    // MARK: - createTransfer

    @Test
    fun `createTransfer creates TO_SPENDING transfer - LSP flow with lspOrderId, no channelId yet`() = test {
        setupClockNowMock()
        whenever(transferDao.insert(any())).thenReturn(Unit)

        val result = sut.createTransfer(
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = null,
            fundingTxId = null,
            lspOrderId = ID_ORDER,
        )

        assertTrue(result.isSuccess)
        val transferId = result.getOrThrow()
        assertNotNull(transferId)
        verify(transferDao).insert(any())
    }

    @Test
    fun `createTransfer creates COOP_CLOSE transfer`() = test {
        setupClockNowMock()
        whenever(transferDao.insert(any())).thenReturn(Unit)

        val result = sut.createTransfer(
            type = TransferType.COOP_CLOSE,
            amountSats = 75000L,
            channelId = ID_CHANNEL,
            fundingTxId = fundingTxo.txid,
            lspOrderId = null,
        )

        assertTrue(result.isSuccess)
        verify(transferDao).insert(any())
    }

    @Test
    fun `createTransfer creates FORCE_CLOSE transfer`() = test {
        setupClockNowMock()
        whenever(transferDao.insert(any())).thenReturn(Unit)

        val result = sut.createTransfer(
            type = TransferType.FORCE_CLOSE,
            amountSats = 75000L,
            channelId = ID_CHANNEL,
            fundingTxId = fundingTxo.txid,
            lspOrderId = null,
        )

        assertTrue(result.isSuccess)
        verify(transferDao).insert(any())
    }

    @Test
    fun `createTransfer creates MANUAL_SETUP transfer - channelId and fundingTxId known immediately`() = test {
        setupClockNowMock()
        whenever(transferDao.insert(any())).thenReturn(Unit)

        val result = sut.createTransfer(
            type = TransferType.MANUAL_SETUP,
            amountSats = 100000L,
            channelId = ID_CHANNEL, // Available immediately from ChannelPending event
            fundingTxId = fundingTxo.txid, // Available immediately from ChannelPending event
            lspOrderId = null, // No LSP order for manual channels
        )

        assertTrue(result.isSuccess)
        verify(transferDao).insert(any())
    }

    @Test
    fun `createTransfer handles database insertion failure`() = test {
        setupClockNowMock()
        val exception = Exception("Database error")
        whenever(transferDao.insert(any())).thenAnswer { throw exception }

        val result = sut.createTransfer(
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
        )

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun `createTransfer generates unique IDs for each transfer`() = test {
        setupClockNowMock()
        val capturedTransfers = mutableListOf<TransferEntity>()
        whenever(transferDao.insert(any())).thenAnswer { invocation ->
            capturedTransfers.add(invocation.getArgument(0))
        }

        val result1 = sut.createTransfer(TransferType.TO_SPENDING, 50000L)
        val result2 = sut.createTransfer(TransferType.TO_SAVINGS, 75000L)

        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)

        val id1 = result1.getOrThrow()
        val id2 = result2.getOrThrow()

        // IDs should be different
        assertTrue(id1 != id2)
    }

    // MARK: - markSettled

    @Test
    fun `markSettled successfully marks transfer as settled`() = test {
        val settledAt = setupClockNowMock()
        whenever(transferDao.markSettled(any(), any())).thenReturn(Unit)

        val result = sut.markSettled(ID_TRANSFER)

        assertTrue(result.isSuccess)
        verify(transferDao).markSettled(eq(ID_TRANSFER), eq(settledAt))
    }

    @Test
    fun `markSettled handles database update failure`() = test {
        setupClockNowMock()
        val exception = Exception("Database error")
        whenever(transferDao.markSettled(any(), any())).thenAnswer { throw exception }

        val result = sut.markSettled(ID_TRANSFER)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // MARK: - syncTransferStates

    @Test
    fun `syncTransferStates returns early when no active transfers`() = test {
        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(emptyList()))

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(lightningRepo, never()).getChannels()
    }

    @Test
    fun `syncTransferStates settles TO_SPENDING transfer when channel is ready`() = test {
        val settledAt = setupClockNowMock()
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        val channelDetails = createChannelDetails().copy(
            channelId = ID_CHANNEL,
            isChannelReady = true,
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channelDetails))
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(mock()))
        whenever(transferDao.markSettled(any(), any())).thenReturn(Unit)

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao).markSettled(eq(ID_TRANSFER), eq(settledAt))
    }

    @Test
    fun `syncTransferStates does not settle TO_SPENDING transfer when channel is not ready`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        val channelDetails = createChannelDetails().copy(
            channelId = ID_CHANNEL,
            isChannelReady = false,
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenReturn(listOf(channelDetails))
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(mock()))

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao, never()).markSettled(any(), any())
    }

    @Test
    fun `syncTransferStates does not settle TO_SPENDING transfer when channel not found`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(mock()))

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao, never()).markSettled(any(), any())
    }

    @Test
    fun `syncTransferStates settles TO_SAVINGS transfer when balance is swept`() = test {
        val settledAt = setupClockNowMock()
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SAVINGS,
            amountSats = 75000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        val balances = BalanceDetails(
            totalOnchainBalanceSats = 0u,
            spendableOnchainBalanceSats = 0u,
            totalAnchorChannelsReserveSats = 0u,
            totalLightningBalanceSats = 0u,
            lightningBalances = emptyList(),
            pendingBalancesFromChannelClosures = emptyList(),
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balances))
        whenever(transferDao.markSettled(any(), any())).thenReturn(Unit)

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao).markSettled(eq(ID_TRANSFER), eq(settledAt))
    }

    @Test
    fun `syncTransferStates does not settle TO_SAVINGS transfer when balance still exists`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SAVINGS,
            amountSats = 75000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        val lightningBalance = LightningBalance.ClaimableOnChannelClose(
            channelId = ID_CHANNEL,
            counterpartyNodeId = "node123",
            amountSatoshis = 75000u,
            transactionFeeSatoshis = 0u,
            outboundPaymentHtlcRoundedMsat = 0u,
            outboundForwardedHtlcRoundedMsat = 0u,
            inboundClaimingHtlcRoundedMsat = 0u,
            inboundHtlcRoundedMsat = 0u,
        )

        val balances = BalanceDetails(
            totalOnchainBalanceSats = 0u,
            spendableOnchainBalanceSats = 0u,
            totalAnchorChannelsReserveSats = 0u,
            totalLightningBalanceSats = 75000u,
            lightningBalances = listOf(lightningBalance),
            pendingBalancesFromChannelClosures = emptyList(),
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balances))

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao, never()).markSettled(any(), any())
    }

    @Test
    fun `syncTransferStates settles TO_SAVINGS transfer when balances is null`() = test {
        val settledAt = setupClockNowMock()
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SAVINGS,
            amountSats = 75000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.failure(Exception("Error")))
        whenever(transferDao.markSettled(any(), any())).thenReturn(Unit)

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao).markSettled(eq(ID_TRANSFER), eq(settledAt))
    }

    @Test
    fun `syncTransferStates handles mixed transfer types correctly`() = test {
        val settledAt = setupClockNowMock()
        val toSpendingTransfer = TransferEntity(
            id = "spending-transfer",
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = "spending-channel",
            isSettled = false,
            createdAt = 1000L,
        )

        val toSavingsTransfer = TransferEntity(
            id = "savings-transfer",
            type = TransferType.TO_SAVINGS,
            amountSats = 75000L,
            channelId = "savings-channel",
            isSettled = false,
            createdAt = 2000L,
        )

        val readyChannel = createChannelDetails().copy(
            channelId = "spending-channel",
            isChannelReady = true,
        )

        val balances = BalanceDetails(
            totalOnchainBalanceSats = 0u,
            spendableOnchainBalanceSats = 0u,
            totalAnchorChannelsReserveSats = 0u,
            totalLightningBalanceSats = 0u,
            lightningBalances = emptyList(),
            pendingBalancesFromChannelClosures = emptyList(),
        )

        whenever(transferDao.getActiveTransfers()).thenReturn(
            flowOf(listOf(toSpendingTransfer, toSavingsTransfer))
        )
        whenever(lightningRepo.getChannels()).thenReturn(listOf(readyChannel))
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balances))
        whenever(transferDao.markSettled(any(), any())).thenReturn(Unit)

        val result = sut.syncTransferStates()

        assertTrue(result.isSuccess)
        verify(transferDao).markSettled(eq("spending-transfer"), eq(settledAt))
        verify(transferDao).markSettled(eq("savings-transfer"), eq(settledAt))
    }

    @Test
    fun `syncTransferStates handles failure gracefully`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = ID_CHANNEL,
            isSettled = false,
            createdAt = 1000L,
        )

        val exception = Exception("Lightning repo error")
        whenever(transferDao.getActiveTransfers()).thenReturn(flowOf(listOf(transfer)))
        whenever(lightningRepo.getChannels()).thenAnswer { throw exception }

        val result = sut.syncTransferStates()

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    // MARK: - resolveChannelIdForTransfer

    @Test
    fun `resolveChannelIdForTransfer returns null for LSP transfer when order not yet available`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = null, // LSP flow - not known yet
            fundingTxId = null,
            lspOrderId = ID_ORDER,
            isSettled = false,
            createdAt = 1000L,
        )

        whenever(blocktankRepo.getOrder(ID_ORDER, refresh = false))
            .thenReturn(Result.success(null)) // Order not found yet

        val result = sut.resolveChannelIdForTransfer(transfer, emptyList())

        assertNull(result)
    }

    @Test
    fun `resolveChannelIdForTransfer returns channelId directly for manual channel - no resolution needed`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.MANUAL_SETUP,
            amountSats = 100000L,
            channelId = ID_CHANNEL, // Already set in manual flow
            fundingTxId = fundingTxo.txid,
            lspOrderId = null,
            isSettled = false,
            createdAt = 1000L,
        )

        val result = sut.resolveChannelIdForTransfer(transfer, emptyList())

        assertEquals(ID_CHANNEL, result)
    }

    @Test
    fun `resolveChannelIdForTransfer finds channel via LSP order funding tx for settlement check`() = test {
        val channelMock = mock<IBtChannel> {
            on { fundingTx } doReturn FundingTx(id = fundingTxo.txid, vout = fundingTxo.vout.toULong())
        }

        val order = mock<IBtOrder>()
        whenever(order.channel).thenReturn(channelMock)

        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = null, // LSP flow - not set initially
            fundingTxId = null, // LSP flow - not set initially
            lspOrderId = ID_ORDER,
            isSettled = false,
            createdAt = 1000L,
        )

        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(fundingTxo)
        whenever(channelDetails.channelId).thenReturn(ID_CHANNEL)

        whenever(blocktankRepo.getOrder(ID_ORDER, refresh = false))
            .thenReturn(Result.success(order))

        val result = sut.resolveChannelIdForTransfer(transfer, listOf(channelDetails))

        assertEquals(ID_CHANNEL, result)
    }

    @Test
    fun `resolveChannelIdForTransfer returns null when LSP order not found`() = test {
        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = null,
            fundingTxId = null,
            lspOrderId = ID_ORDER,
            isSettled = false,
            createdAt = 1000L,
        )

        whenever(blocktankRepo.getOrder(ID_ORDER, refresh = false))
            .thenReturn(Result.success(null))

        val result = sut.resolveChannelIdForTransfer(transfer, emptyList())

        assertNull(result)
    }

    @Test
    fun `resolveChannelIdForTransfer returns null when LSP order has no funding tx`() = test {
        val channelMock = mock<IBtChannel>()
        whenever(channelMock.fundingTx).thenReturn(null)

        val order = mock<IBtOrder>()
        whenever(order.channel).thenReturn(channelMock)

        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = null,
            fundingTxId = null,
            lspOrderId = ID_ORDER,
            isSettled = false,
            createdAt = 1000L,
        )

        whenever(blocktankRepo.getOrder(ID_ORDER, refresh = false))
            .thenReturn(Result.success(order))

        val result = sut.resolveChannelIdForTransfer(transfer, emptyList())

        assertNull(result)
    }

    @Test
    fun `resolveChannelIdForTransfer returns null when funding tx does not match any channel`() = test {
        val fundingTx = FundingTx(id = fundingTxo.txid, vout = fundingTxo.vout.toULong())

        val channelMock = mock<IBtChannel>()
        whenever(channelMock.fundingTx).thenReturn(fundingTx)

        val order = mock<IBtOrder>()
        whenever(order.channel).thenReturn(channelMock)

        val transfer = TransferEntity(
            id = ID_TRANSFER,
            type = TransferType.TO_SPENDING,
            amountSats = 50000L,
            channelId = null,
            fundingTxId = null,
            lspOrderId = ID_ORDER,
            isSettled = false,
            createdAt = 1000L,
        )

        val differentFundingTxo = OutPoint(txid = "different-funding-tx-id", vout = 0u)
        val channelDetails = mock<ChannelDetails>()
        whenever(channelDetails.fundingTxo).thenReturn(differentFundingTxo)

        whenever(blocktankRepo.getOrder(ID_ORDER, refresh = false))
            .thenReturn(Result.success(order))

        val result = sut.resolveChannelIdForTransfer(transfer, listOf(channelDetails))

        assertNull(result)
    }

    // MARK: - activeTransfers

    @Test
    fun `activeTransfers emits transfers from dao`() = test {
        val transfers = listOf(
            TransferEntity(
                id = "transfer1",
                type = TransferType.TO_SPENDING,
                amountSats = 50000L,
                isSettled = false,
                createdAt = 1000L,
            ),
            TransferEntity(
                id = "transfer2",
                type = TransferType.TO_SAVINGS,
                amountSats = 75000L,
                isSettled = false,
                createdAt = 2000L,
            ),
        )

        val transfersFlow = MutableStateFlow(transfers)
        whenever(transferDao.getActiveTransfers()).thenReturn(transfersFlow)

        // Create a new instance to use the updated flow
        val testSut = TransferRepo(
            bgDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            transferDao = transferDao,
            clock = clock,
        )

        testSut.activeTransfers.test {
            assertEquals(transfers, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // MARK: - Helpers

    private fun setupClockNowMock(): Long = Clock.System.now()
        .also { whenever(clock.now()).thenReturn(it) }
        .epochSeconds
}
