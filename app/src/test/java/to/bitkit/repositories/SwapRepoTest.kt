package to.bitkit.repositories

import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwapEvent
import com.synonym.bitkitcore.ReverseSwapResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.BalanceState
import to.bitkit.services.BoltzService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwapRepoTest : BaseUnitTest() {
    private val boltzService = mock<BoltzService>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()

    private val boltzEvents = MutableSharedFlow<BoltzSwapEvent>(extraBufferCapacity = 8)
    private val balanceState = MutableStateFlow(BalanceState(maxSendLightningSats = SPENDABLE_LN))

    private lateinit var sut: SwapRepo

    @Before
    fun setUp() {
        whenever(boltzService.events).thenReturn(boltzEvents)
        whenever { boltzService.isSwapEnabled() }.thenReturn(true)
        whenever { boltzService.reverseLimits(anyOrNull()) }.thenReturn(reverseLimits())
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(lightningRepo.canSend(any())).thenReturn(true)

        sut = SwapRepo(
            boltzService = boltzService,
            lightningRepo = lightningRepo,
            walletRepo = walletRepo,
            ioDispatcher = testDispatcher,
        )
    }

    // region quoteForRecipientAmount

    @Test
    fun `quote grosses fees up so the recipient receives the requested amount`() = test {
        val quote = assertNotNull(sut.quoteForRecipientAmount(RECIPIENT_SAT).getOrNull())

        assertEquals(RECIPIENT_SAT, quote.recipientSat)
        assertEquals(SWAP_MINER_FEE, quote.networkFeeSat)
        assertEquals(quote.invoiceSat - RECIPIENT_SAT, quote.serviceFeeSat)
        assertTrue(quote.invoiceSat > RECIPIENT_SAT)
        assertEquals(RECIPIENT_SAT.toLong(), received(quote.invoiceSat.toLong()))
    }

    @Test
    fun `quote is the smallest invoice amount that still covers the recipient`() = test {
        val quote = assertNotNull(sut.quoteForRecipientAmount(RECIPIENT_SAT).getOrNull())

        // Boltz rounds its percentage fee up, so one sat less must fall short.
        assertTrue(received(quote.invoiceSat.toLong() - 1) < RECIPIENT_SAT.toLong())
    }

    @Test
    fun `quote covers the recipient across a range of amounts`() = test {
        for (recipient in listOf(60_000uL, 60_001uL, 99_999uL, 123_457uL)) {
            val quote = assertNotNull(sut.quoteForRecipientAmount(recipient).getOrNull())
            assertTrue(
                received(quote.invoiceSat.toLong()) >= recipient.toLong(),
                "recipient '$recipient' shorted by invoice '${quote.invoiceSat}'",
            )
        }
    }

    @Test
    fun `quote fails below the swap minimum`() = test {
        assertTrue(sut.quoteForRecipientAmount(SWAP_MIN - 10_000uL).isFailure)
    }

    @Test
    fun `quote fails above the swap maximum`() = test {
        assertTrue(sut.quoteForRecipientAmount(SWAP_MAX + 1uL).isFailure)
    }

    @Test
    fun `quote fails when the spending balance cannot cover the grossed up amount`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = RECIPIENT_SAT)

        assertTrue(sut.quoteForRecipientAmount(RECIPIENT_SAT).isFailure)
    }

    @Test
    fun `quote fails when Lightning cannot route the payment`() = test {
        whenever(lightningRepo.canSend(any())).thenReturn(false)

        assertTrue(sut.quoteForRecipientAmount(RECIPIENT_SAT).isFailure)
    }

    @Test
    fun `quote skips the network when swaps are disabled`() = test {
        whenever(boltzService.isSwapEnabled()).thenReturn(false)

        assertTrue(sut.quoteForRecipientAmount(RECIPIENT_SAT).isFailure)
        verify(boltzService, never()).reverseLimits(anyOrNull())
    }

    @Test
    fun `quote fails when the limits fetch fails`() = test {
        whenever(boltzService.reverseLimits(anyOrNull())).thenAnswer { throw AppError(BOLTZ_ERROR) }

        assertTrue(sut.quoteForRecipientAmount(RECIPIENT_SAT).isFailure)
    }

    @Test
    fun `pair terms are fetched once and reused for later quotes`() = test {
        sut.quoteForRecipientAmount(RECIPIENT_SAT)
        sut.quoteForRecipientAmount(RECIPIENT_SAT + 1uL)

        verify(boltzService).reverseLimits(anyOrNull())
    }

    // endregion

    // region maxRecipientSats

    @Test
    fun `maxRecipientSats is what the spendable balance delivers after fees`() = test {
        val spendable = 200_000uL
        balanceState.value = BalanceState(maxSendLightningSats = spendable)

        val max = assertNotNull(sut.maxRecipientSats().getOrNull())

        val sendable = spendable - spendable / 100uL // 1% routing fee reserve
        assertEquals(received(sendable.toLong()).toULong(), max)
    }

    @Test
    fun `maxRecipientSats is capped by the swap maximum`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SWAP_MAX * 10uL)

        val max = assertNotNull(sut.maxRecipientSats().getOrNull())

        assertEquals(received(SWAP_MAX.toLong()).toULong(), max)
    }

    @Test
    fun `maxRecipientSats fails when swaps are disabled`() = test {
        whenever(boltzService.isSwapEnabled()).thenReturn(false)

        assertTrue(sut.maxRecipientSats().isFailure)
    }

    // endregion

    // region payToAddress

    @Test
    fun `payToAddress claims to the recipient address and reports the claim txid`() = test {
        stubSwapHappyPath()

        var result: Result<SendSwapReceipt>? = null
        backgroundScope.launch { result = sut.payToAddress(ADDRESS, RECIPIENT_SAT) }
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = CLAIM_TXID))
        advanceUntilIdle()

        assertEquals(SendSwapReceipt(paymentHash = PAYMENT_ID, claimTxId = CLAIM_TXID), result?.getOrNull())
        verify(boltzService).createReverseSwap(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `payToAddress pays an invoice grossed up to cover the recipient amount`() = test {
        stubSwapHappyPath()

        backgroundScope.launch { sut.payToAddress(ADDRESS, RECIPIENT_SAT) }
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = CLAIM_TXID))
        advanceUntilIdle()

        val expectedInvoice = assertNotNull(sut.quoteForRecipientAmount(RECIPIENT_SAT).getOrNull()).invoiceSat
        verify(boltzService).createReverseSwap(eq(expectedInvoice), eq(ADDRESS), anyOrNull(), anyOrNull())
    }

    @Test
    fun `payToAddress still reports the paid invoice when the claim does not land in time`() = test {
        stubSwapHappyPath()

        var result: Result<SendSwapReceipt>? = null
        backgroundScope.launch { result = sut.payToAddress(ADDRESS, RECIPIENT_SAT) }
        runCurrent()
        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertEquals(SendSwapReceipt(paymentHash = PAYMENT_ID, claimTxId = null), result?.getOrNull())
    }

    @Test
    fun `payToAddress fails without paying when Boltz would lock less than the recipient needs`() = test {
        stubSwapHappyPath(onchainAmountSat = RECIPIENT_SAT - 1uL)

        val result = sut.payToAddress(ADDRESS, RECIPIENT_SAT)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `payToAddress fails without creating a swap when no quote covers the amount`() = test {
        whenever(boltzService.isSwapEnabled()).thenReturn(false)

        val result = sut.payToAddress(ADDRESS, RECIPIENT_SAT)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        verify(boltzService, never()).createReverseSwap(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `payToAddress fails when the swap reports an error`() = test {
        stubSwapHappyPath()

        var result: Result<SendSwapReceipt>? = null
        backgroundScope.launch { result = sut.payToAddress(ADDRESS, RECIPIENT_SAT) }
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Error(swapId = SWAP_ID, message = BOLTZ_ERROR))
        advanceUntilIdle()

        assertEquals(BOLTZ_ERROR, result?.exceptionOrNull()?.message)
    }

    @Test
    fun `payToAddress fails when the invoice payment fails`() = test {
        stubSwapHappyPath()
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.failure(AppError(PAY_ERROR)))

        val result = sut.payToAddress(ADDRESS, RECIPIENT_SAT)
        advanceUntilIdle()

        assertEquals(PAY_ERROR, result.exceptionOrNull()?.message)
    }

    // endregion

    private suspend fun stubSwapHappyPath(onchainAmountSat: ULong = RECIPIENT_SAT + SWAP_MINER_FEE) {
        whenever(boltzService.createReverseSwap(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(reverseSwapResponse(onchainAmountSat))
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.success(PAYMENT_ID))
    }

    /** Mirrors the forward pricing Boltz applies: a ceiled percentage fee plus the miner fees. */
    private fun received(invoiceSat: Long): Long =
        invoiceSat - ceil(SWAP_FEE_PERCENT / 100.0 * invoiceSat).toLong() - SWAP_MINER_FEE.toLong()

    private fun reverseLimits() = BoltzPairInfo(
        hash = "hash",
        rate = 1.0,
        minimalSat = SWAP_MIN,
        maximalSat = SWAP_MAX,
        feePercentage = SWAP_FEE_PERCENT,
        minerFeesSat = SWAP_MINER_FEE,
    )

    private fun reverseSwapResponse(onchainAmountSat: ULong) = ReverseSwapResponse(
        id = SWAP_ID,
        invoice = INVOICE,
        lockupAddress = "bc1qlockup",
        onchainAmountSat = onchainAmountSat,
        timeoutBlockHeight = 1uL,
    )

    private companion object {
        const val ADDRESS = "bc1qrecipient"
        const val INVOICE = "lnbc1invoice"
        const val SWAP_ID = "swap-1"
        const val CLAIM_TXID = "claim-txid"
        const val PAYMENT_ID = "payment-id"
        const val BOLTZ_ERROR = "boltz down"
        const val PAY_ERROR = "route not found"

        const val RECIPIENT_SAT = 100_000uL
        const val SPENDABLE_LN = 500_000uL
        const val SWAP_MIN = 50_000uL
        const val SWAP_MAX = 250_000uL
        const val SWAP_FEE_PERCENT = 0.25
        const val SWAP_MINER_FEE = 320uL
    }
}
