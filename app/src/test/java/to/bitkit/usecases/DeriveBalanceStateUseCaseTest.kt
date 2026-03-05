package to.bitkit.usecases

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.BalanceSource
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.LightningBalance
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.entities.TransferEntity
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.TransferRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeriveBalanceStateUseCaseTest : BaseUnitTest() {

    private val lightningRepo: LightningRepo = mock()
    private val transferRepo: TransferRepo = mock()
    private val settingsStore: SettingsStore = mock()

    private lateinit var sut: DeriveBalanceStateUseCase

    @Before
    fun setUp() {
        runBlocking {
            whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
            whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
            whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
            wheneverBlocking { lightningRepo.listSpendableOutputs() }.thenReturn(Result.success(emptyList()))
            wheneverBlocking { lightningRepo.getChannelFundableBalance() }.thenReturn(0uL)
            wheneverBlocking {
                lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
            }.thenReturn(Result.success(1000uL))
            wheneverBlocking {
                lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull())
            }.thenReturn(Result.success(1000uL))
        }
        sut = DeriveBalanceStateUseCase(
            bgDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            transferRepo = transferRepo,
            settingsStore = settingsStore,
        )
    }

    @Test
    fun `should calculate LSP order transfer to spending using transfer amount`() = test {
        val balance = newBalanceDetails()
        val amountSats = 50_000uL
        val transfers = listOf(
            newTransferEntity(
                type = TransferType.TO_SPENDING,
                amountSats = amountSats.toLong(),
                lspOrderId = "lsp-order-id",
                channelId = null
            )
        )

        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(transfers))

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(amountSats, balanceState.balanceInTransferToSpending)
        assertEquals(
            balance.totalOnchainBalanceSats,
            balanceState.totalOnchainSats,
            "Onchain balance unchanged - LDK already reflects sent payment to LSP"
        )
        assertEquals(
            balance.totalLightningBalanceSats,
            balanceState.totalLightningSats,
            "Lightning balance unchanged - channel not open yet"
        )
    }

    @Test
    fun `should subtract estimated fee from channelFundableBalance`() = test {
        val fundableBalance = 75_000uL
        val fee = 2_000uL
        val balance = BalanceDetails(
            totalOnchainBalanceSats = 100_000u,
            spendableOnchainBalanceSats = 0u,
            totalAnchorChannelsReserveSats = 10_000u,
            totalLightningBalanceSats = 50_000u,
            lightningBalances = emptyList(),
            pendingBalancesFromChannelClosures = emptyList(),
        )
        whenever(lightningRepo.getChannelFundableBalance()).thenReturn(fundableBalance)
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        wheneverBlocking {
            lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }.thenReturn(Result.success(fee))

        val result = sut()

        assertTrue(result.isSuccess)
        assertEquals(fundableBalance - fee, result.getOrThrow().channelFundableBalance)
    }

    @Test
    fun `should use fallback fee for channelFundableBalance when fee estimation fails`() = test {
        val fundableBalance = 100_000uL
        val expectedFallback = (fundableBalance.toDouble() * DeriveBalanceStateUseCase.FALLBACK_FEE_PERCENT).toULong()
        val balance = BalanceDetails(
            totalOnchainBalanceSats = 100_000u,
            spendableOnchainBalanceSats = 0u,
            totalAnchorChannelsReserveSats = 10_000u,
            totalLightningBalanceSats = 50_000u,
            lightningBalances = emptyList(),
            pendingBalancesFromChannelClosures = emptyList(),
        )
        whenever(lightningRepo.getChannelFundableBalance()).thenReturn(fundableBalance)
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        wheneverBlocking {
            lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        }.thenReturn(Result.failure(Exception("Fee estimation failed")))

        val result = sut()

        assertTrue(result.isSuccess)
        assertEquals(fundableBalance - expectedFallback, result.getOrThrow().channelFundableBalance)
    }

    @Test
    fun `should return zero channelFundableBalance when fundable balance is zero`() = test {
        val balance = BalanceDetails(
            totalOnchainBalanceSats = 100_000u,
            spendableOnchainBalanceSats = 0u,
            totalAnchorChannelsReserveSats = 10_000u,
            totalLightningBalanceSats = 50_000u,
            lightningBalances = emptyList(),
            pendingBalancesFromChannelClosures = emptyList(),
        )
        whenever(lightningRepo.getChannelFundableBalance()).thenReturn(0uL)
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))

        val result = sut()

        assertTrue(result.isSuccess)
        assertEquals(0uL, result.getOrThrow().channelFundableBalance)
    }

    @Test
    fun `should calculate manual channel transfer to spending using channel balance`() = test {
        val channelId = "manual-channel-id"
        val amountSats = 30_000uL
        val channelBalance = newChannelBalance(channelId, amountSats)

        val balance = newBalanceDetails().copy(lightningBalances = listOf(channelBalance))
        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))

        val channel = mock<ChannelDetails> {
            on { this.channelId } doReturn channelId
            on { isChannelReady } doReturn false
        }

        val transfers = listOf(
            newTransferEntity(
                type = TransferType.MANUAL_SETUP,
                amountSats = amountSats.toLong(),
                channelId = channelId,
                lspOrderId = null
            )
        )

        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(transfers))

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(amountSats, balanceState.balanceInTransferToSpending)
        assertEquals(
            balance.totalOnchainBalanceSats,
            balanceState.totalOnchainSats,
            "Onchain balance unchanged - funding tx already spent UTXO"
        )
        assertEquals(
            balance.totalLightningBalanceSats - amountSats,
            balanceState.totalLightningSats,
            "Lightning balance reduced - pending channel not ready"
        )
    }

    @Test
    fun `should not count manual channel as pending when ready`() = test {
        newBalanceDetails()
        val channelId = "ready-channel-id"
        val channel = mock<ChannelDetails> {
            on { this.channelId } doReturn channelId
            on { isChannelReady } doReturn true
        }

        val transfers = listOf(
            newTransferEntity(
                type = TransferType.MANUAL_SETUP,
                amountSats = 30_000L,
                channelId = channelId,
                lspOrderId = null
            )
        )

        whenever(lightningRepo.getChannels()).thenReturn(listOf(channel))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(transfers))

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(0uL, balanceState.balanceInTransferToSpending)
    }

    @Test
    fun `should count closing channel balance for transfer to savings`() = test {
        val channelId = "closing-channel-id"
        val amountSats = 40_000uL
        val closingChannelBalance = newClosingChannelBalance(channelId, amountSats)

        val balance = newBalanceDetails().copy(lightningBalances = listOf(closingChannelBalance))
        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))

        val transfers = listOf(
            newTransferEntity(
                type = TransferType.FORCE_CLOSE,
                amountSats = amountSats.toLong(),
                channelId = channelId,
                lspOrderId = null
            )
        )

        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(transfers))
        wheneverBlocking { transferRepo.resolveChannelIdForTransfer(any(), any()) }.thenReturn(channelId)

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(amountSats, balanceState.balanceInTransferToSavings)
        assertEquals(
            balance.totalOnchainBalanceSats,
            balanceState.totalOnchainSats,
            "Onchain balance unchanged - closing balance not yet confirmed/swept"
        )
        assertEquals(
            balance.totalLightningBalanceSats - amountSats,
            balanceState.totalLightningSats,
            "Lightning balance reduced - channel closing balance"
        )
    }

    @Test
    fun `should subtract coop close balance from lightning without showing transfer in progress`() = test {
        val channelId = "closing-channel-id"
        val amountSats = 40_000uL
        val closingChannelBalance = newClosingChannelBalance(channelId, amountSats)

        val balance = newBalanceDetails().copy(
            lightningBalances = listOf(closingChannelBalance),
            totalLightningBalanceSats = amountSats,
        )
        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))

        val transfers = listOf(
            newTransferEntity(
                type = TransferType.COOP_CLOSE,
                amountSats = amountSats.toLong(),
                channelId = channelId,
                lspOrderId = null
            )
        )

        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(transfers))
        wheneverBlocking { transferRepo.resolveChannelIdForTransfer(any(), any()) }.thenReturn(channelId)

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(0uL, balanceState.balanceInTransferToSavings, "No transfer in progress for coop close")
        assertEquals(
            0uL,
            balanceState.totalLightningSats,
            "Lightning balance reduced - coop close balance subtracted"
        )
    }

    @Test
    fun `should calculate zero max send onchain when spendable balance is zero`() = test {
        val balance = newBalanceDetails().copy(totalOnchainBalanceSats = 50_000u)
        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(0u, balanceState.maxSendOnchainSats)
    }

    @Test
    fun `should calculate max send onchain with successful fee estimation`() = test {
        val spendableAmount = 100_000uL
        val feeAmount = 2_000uL
        val expectedMaxSend = spendableAmount - feeAmount // 98_000

        val balance = newBalanceDetails().copy(spendableOnchainBalanceSats = spendableAmount)

        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        wheneverBlocking {
            lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull())
        }.thenReturn(Result.success(feeAmount))

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(expectedMaxSend, balanceState.maxSendOnchainSats)
    }

    @Test
    fun `should calculate fallback max send onchain when fee estimation fails`() = test {
        val spendableAmount = 100_000uL
        val expectedFallback = (spendableAmount.toDouble() * DeriveBalanceStateUseCase.FALLBACK_FEE_PERCENT).toULong()
        val expectedMaxSend = spendableAmount - expectedFallback

        val balance = newBalanceDetails().copy(spendableOnchainBalanceSats = spendableAmount)

        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        wheneverBlocking {
            lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull())
        }.thenReturn(Result.failure(Exception("Fee estimation failed")))

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(expectedMaxSend, balanceState.maxSendOnchainSats)
    }

    @Test
    fun `should return zero max send when fee exceeds spendable balance`() = test {
        val spendableAmount = 100_000uL
        val excessiveFee = 150_000uL // Fee exceeds balance

        val balance = newBalanceDetails().copy(spendableOnchainBalanceSats = spendableAmount)

        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))
        whenever(lightningRepo.getChannels()).thenReturn(emptyList())
        wheneverBlocking {
            lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull())
        }.thenReturn(Result.success(excessiveFee))

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(0u, balanceState.maxSendOnchainSats)
    }

    @Test
    fun `should count both spending and savings transfers correctly`() = test {
        val toSpending = 30_000uL
        val toSavings = 20_000uL
        val spendingChannelId = "spending-channel-id"
        val savingsChannelId = "savings-channel-id"
        val balance = newBalanceDetails().copy(
            totalLightningBalanceSats = 50_000u,
            lightningBalances = listOf(
                newChannelBalance(spendingChannelId, toSpending),
                newClosingChannelBalance(savingsChannelId, toSavings)
            ),
        )
        wheneverBlocking { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balance))
        val spendingChannel = mock<ChannelDetails> {
            on { channelId } doReturn spendingChannelId
            on { isChannelReady } doReturn false
        }
        val transfers = listOf(
            newTransferEntity(
                type = TransferType.MANUAL_SETUP,
                amountSats = toSpending.toLong(),
                channelId = spendingChannelId,
                lspOrderId = null
            ),
            newTransferEntity(
                type = TransferType.FORCE_CLOSE,
                amountSats = toSavings.toLong(),
                channelId = savingsChannelId,
                lspOrderId = null
            )
        )
        whenever(lightningRepo.getChannels()).thenReturn(listOf(spendingChannel))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(transfers))
        wheneverBlocking { transferRepo.resolveChannelIdForTransfer(any(), any()) }.thenAnswer { invocation ->
            val transfer = invocation.getArgument<TransferEntity>(0)
            transfer.channelId
        }

        val result = sut()

        assertTrue(result.isSuccess)
        val balanceState = result.getOrThrow()
        assertEquals(toSpending, balanceState.balanceInTransferToSpending)
        assertEquals(toSavings, balanceState.balanceInTransferToSavings)
        assertEquals(
            balance.totalOnchainBalanceSats,
            balanceState.totalOnchainSats,
            "Onchain unchanged - closing balance not yet confirmed/swept, manual channel already reflected"
        )
        assertEquals(
            balance.totalLightningBalanceSats - toSpending - toSavings,
            balanceState.totalLightningSats,
            "Lightning reduced by manual channel (30k) + transfer to savings (20k)"
        )
    }

    private suspend fun newBalanceDetails() = BalanceDetails(
        totalOnchainBalanceSats = 100_000u,
        spendableOnchainBalanceSats = 0u,
        totalAnchorChannelsReserveSats = 10_000u,
        totalLightningBalanceSats = 50_000u,
        lightningBalances = emptyList(),
        pendingBalancesFromChannelClosures = emptyList(),
    ).also {
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(it))
    }

    private fun newChannelBalance(id: String, sats: ULong = 30000u) = LightningBalance.ClaimableOnChannelClose(
        channelId = id,
        counterpartyNodeId = "node-id",
        amountSatoshis = sats,
        transactionFeeSatoshis = 0u,
        outboundPaymentHtlcRoundedMsat = 0u,
        outboundForwardedHtlcRoundedMsat = 0u,
        inboundClaimingHtlcRoundedMsat = 0u,
        inboundHtlcRoundedMsat = 0u,
    )

    private fun newClosingChannelBalance(id: String, sats: ULong) = LightningBalance.ClaimableAwaitingConfirmations(
        channelId = id,
        counterpartyNodeId = "node-id",
        amountSatoshis = sats,
        confirmationHeight = 344u,
        source = BalanceSource.COOP_CLOSE,
    )

    private fun newTransferEntity(
        type: TransferType,
        amountSats: Long,
        channelId: String? = null,
        lspOrderId: String? = null,
    ) = TransferEntity(
        id = "test-transfer-${System.currentTimeMillis()}",
        type = type,
        amountSats = amountSats,
        channelId = channelId,
        fundingTxId = null,
        lspOrderId = lspOrderId,
        isSettled = false,
        createdAt = System.currentTimeMillis(),
    )
}
