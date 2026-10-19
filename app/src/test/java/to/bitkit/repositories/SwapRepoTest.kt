package to.bitkit.repositories

import com.synonym.bitkitcore.BoltzNetwork
import com.synonym.bitkitcore.BoltzSwap
import com.synonym.bitkitcore.BoltzSwapStatus
import com.synonym.bitkitcore.BoltzSwapType
import com.synonym.bitkitcore.PubkySendTerms
import com.synonym.bitkitcore.ReverseSwapResponse
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.BalanceState
import to.bitkit.services.BoltzService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SwapRepoTest : BaseUnitTest() {
    companion object {
        private const val ADDRESS = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        private const val INVOICE = "lnbcrt1sendswap"
        private const val PAYMENT_HASH = "payment-hash"
        private const val PAIR_HASH = "pair-hash"
        private const val SPENDING_SAT = 10_000_000uL
        private val TERMS = PubkySendTerms(
            pairHash = PAIR_HASH,
            minimumLockupSat = 1_000uL,
            maximumLockupSat = 5_000_000uL,
            baseFeeSat = 100uL,
            feePpm = 5_000uL,
            lockupFeeSat = 300uL,
            claimFeeSat = 360uL,
        )

        /** Terms of a mainnet provider at its 5 sat/vB fee rate floor. */
        private val MAINNET_TERMS = PubkySendTerms(
            pairHash = PAIR_HASH,
            minimumLockupSat = 13_950uL,
            maximumLockupSat = 1_000_000uL,
            baseFeeSat = 1_000uL,
            feePpm = 2_000uL,
            lockupFeeSat = 1_395uL,
            claimFeeSat = 900uL,
        )
        private val QUOTE = SwapSendQuote(
            recipientSat = 100_000uL,
            lockupSat = 100_360uL,
            invoiceSat = 101_261uL,
            serviceFeeSat = 601uL,
            networkFeeSat = 660uL,
            pairHash = PAIR_HASH,
        )
    }

    private val boltzService = mock<BoltzService>()
    private val pubkySwapRepo = mock<PubkySwapRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val clock = mock<Clock>()
    private val balanceState = MutableStateFlow(BalanceState(maxSendLightningSats = SPENDING_SAT))
    private var invoiceAmountMsat: ULong? = QUOTE.invoiceSat * 1_000uL
    private lateinit var sut: SwapRepo

    @Before
    fun setUp() {
        whenever { pubkySwapRepo.isAvailable() }.thenReturn(true)
        whenever { pubkySwapRepo.ensureConfigured() }.thenReturn(Result.success(Unit))
        whenever { boltzService.sendTerms() }.thenReturn(TERMS)
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(clock.now()).thenReturn(Instant.fromEpochSeconds(1_000))
        sut = SwapRepo(
            ioDispatcher = testDispatcher,
            boltzService = boltzService,
            pubkySwapRepo = pubkySwapRepo,
            walletRepo = walletRepo,
            invoiceAmountReader = { invoiceAmountMsat },
            invoiceHashReader = { bolt11 -> PAYMENT_HASH.takeIf { bolt11 == INVOICE } },
            clock = clock,
        )
    }

    @Test
    fun `quote prices the lockup like the provider`() = test {
        assertEquals(QUOTE, sut.quote(QUOTE.recipientSat).getOrThrow())
        assertEquals(1_261uL, QUOTE.totalFeeSat)
    }

    @Test
    fun `quote rejects amounts whose fees exceed the policy`() = test {
        assertEquals(SwapAmountError.TooSmall, sut.quote(1_689uL).exceptionOrNull())
        assertEquals(2_460uL, sut.quote(1_690uL).getOrThrow().invoiceSat)
    }

    @Test
    fun `quote rejects amounts at or below dust`() = test {
        whenever { boltzService.sendTerms() }.thenReturn(
            TERMS.copy(minimumLockupSat = 1uL, baseFeeSat = 0uL, lockupFeeSat = 0uL)
        )

        assertEquals(SwapAmountError.TooSmall, sut.quote(546uL).exceptionOrNull())
    }

    @Test
    fun `quote rejects invoices above the policy maximum`() = test {
        assertEquals(SwapAmountError.TooLarge, sut.quote(994_268uL).exceptionOrNull())
    }

    @Test
    fun `quote rejects invoices the spending balance cannot cover`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = 200_000uL)

        assertEquals(SwapAmountError.InsufficientSpending, sut.quote(196_258uL).exceptionOrNull())
    }

    @Test
    fun `sendLimits returns the exact payable range`() = test {
        val limits = sut.sendLimits().getOrThrow()

        assertEquals(SwapSendLimits(minRecipientSat = 1_690uL, maxRecipientSat = 994_267uL), limits)
        assertEquals(1_000_000uL, sut.quote(994_267uL).getOrThrow().invoiceSat)
    }

    @Test
    fun `sendLimits keeps a routing reserve of the spending balance`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = 200_000uL)

        val limits = sut.sendLimits().getOrThrow()

        assertEquals(196_257uL, limits.maxRecipientSat)
        assertEquals(198_000uL, sut.quote(196_257uL).getOrThrow().invoiceSat)
    }

    @Test
    fun `sendLimits says how much spending the smallest swap needs`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = 2_483uL)

        assertEquals(
            SwapSendBlockedError.InsufficientSpending(minRecipientSat = 1_690uL, requiredSpendingSat = 2_484uL),
            sut.sendLimits().exceptionOrNull(),
        )
        balanceState.value = BalanceState(maxSendLightningSats = 2_484uL)
        assertEquals(SwapSendLimits(1_690uL, 1_690uL), sut.sendLimits().getOrThrow())
    }

    @Test
    fun `sendLimits pays small amounts with mainnet provider terms`() = test {
        whenever { boltzService.sendTerms() }.thenReturn(MAINNET_TERMS)
        balanceState.value = BalanceState(maxSendLightningSats = 22_550uL)

        assertEquals(
            SwapSendLimits(minRecipientSat = 13_050uL, maxRecipientSat = 18_991uL),
            sut.sendLimits().getOrThrow(),
        )
        assertEquals(16_372uL, sut.quote(13_050uL).getOrThrow().invoiceSat)
        assertEquals(SwapAmountError.TooSmall, sut.quote(10_000uL).exceptionOrNull())
    }

    @Test
    fun `terms with fees above the policy are unavailable`() = test {
        whenever { boltzService.sendTerms() }.thenReturn(TERMS.copy(feePpm = 2_000_000uL))

        assertIs<SwapUnavailableError>(sut.quote(QUOTE.recipientSat).exceptionOrNull())
        assertEquals(SwapSendBlockedError.NoPayableAmount, sut.sendLimits().exceptionOrNull())
    }

    @Test
    fun `quote without a provider does not read terms`() = test {
        whenever { pubkySwapRepo.isAvailable() }.thenReturn(false)

        assertIs<SwapUnavailableError>(sut.quote(QUOTE.recipientSat).exceptionOrNull())
        verify(boltzService, never()).sendTerms()
    }

    @Test
    fun `terms are reused for quotes within their lifetime`() = test {
        sut.quote(QUOTE.recipientSat)
        sut.sendLimits()

        verify(boltzService, times(1)).sendTerms()
    }

    @Test
    fun `createSendSwap creates a swap that matches the quote`() = test {
        val swap = sendSwap()
        whenever { boltzService.createSendSwap(any(), any(), any(), any()) }.thenReturn(swap)

        assertEquals(swap, sut.createSendSwap(ADDRESS, QUOTE).getOrThrow())
        verify(boltzService).createSendSwap(
            invoiceSat = QUOTE.invoiceSat,
            recipientSat = QUOTE.recipientSat,
            claimAddress = ADDRESS,
            pairHash = PAIR_HASH,
        )
    }

    @Test
    fun `createSendSwap refuses a quote whose terms changed`() = test {
        sut.quote(QUOTE.recipientSat)
        whenever { boltzService.sendTerms() }.thenReturn(TERMS.copy(baseFeeSat = 200uL))

        val error = sut.createSendSwap(ADDRESS, QUOTE).exceptionOrNull()

        assertIs<SwapQuoteExpiredError>(error)
        assertEquals(QUOTE.copy(serviceFeeSat = 701uL, invoiceSat = 101_361uL), error.quote)
        verify(boltzService, never()).createSendSwap(any(), any(), any(), any())
    }

    @Test
    fun `createSendSwap uses a reissued offer whose amounts are unchanged`() = test {
        whenever { boltzService.sendTerms() }.thenReturn(TERMS.copy(pairHash = "reissued"))
        whenever { boltzService.createSendSwap(any(), any(), any(), any()) }.thenReturn(sendSwap())

        assertEquals(sendSwap(), sut.createSendSwap(ADDRESS, QUOTE).getOrThrow())
        verify(boltzService).createSendSwap(
            invoiceSat = QUOTE.invoiceSat,
            recipientSat = QUOTE.recipientSat,
            claimAddress = ADDRESS,
            pairHash = "reissued",
        )
    }

    @Test
    fun `createSendSwap rejects a swap with a different lockup`() = test {
        whenever { boltzService.createSendSwap(any(), any(), any(), any()) }
            .thenReturn(sendSwap(onchainAmountSat = QUOTE.lockupSat - 1uL))

        assertIs<SwapQuoteMismatchError>(sut.createSendSwap(ADDRESS, QUOTE).exceptionOrNull())
    }

    @Test
    fun `createSendSwap rejects a swap with a different invoice amount`() = test {
        invoiceAmountMsat = QUOTE.invoiceSat * 1_000uL + 1uL
        whenever { boltzService.createSendSwap(any(), any(), any(), any()) }.thenReturn(sendSwap())

        assertIs<SwapQuoteMismatchError>(sut.createSendSwap(ADDRESS, QUOTE).exceptionOrNull())
    }

    @Test
    fun `createSendSwap rejects an invoice without an amount`() = test {
        invoiceAmountMsat = null
        whenever { boltzService.createSendSwap(any(), any(), any(), any()) }.thenReturn(sendSwap())

        assertIs<SwapQuoteMismatchError>(sut.createSendSwap(ADDRESS, QUOTE).exceptionOrNull())
    }

    @Test
    fun `paySendSwap pays the hold invoice of the swap`() = test {
        whenever { pubkySwapRepo.payHoldInvoice(any(), any()) }
            .thenReturn(Result.success(HoldInvoicePayment.AlreadyStarted))

        assertEquals(HoldInvoicePayment.AlreadyStarted, sut.paySendSwap(sendSwap()).getOrThrow())
        verify(pubkySwapRepo).payHoldInvoice(swapId = "send-swap", bolt11 = INVOICE)
    }

    @Test
    fun `findSendPayment finds what the swap of a payment sent to its address`() = test {
        whenever { boltzService.listSwaps() }.thenReturn(
            listOf(storedSwap(invoice = "lnbcrt1other", recipientAmountSat = 50_000uL), storedSwap()),
        )

        assertEquals(
            SwapSendPayment(recipientSat = 100_000uL, swapFeeSat = 1_261uL),
            sut.findSendPayment(PAYMENT_HASH),
        )
        assertNull(sut.findSendPayment("other-payment-hash"))
    }

    @Test
    fun `findSendPayment is null for a swap to savings`() = test {
        whenever { boltzService.listSwaps() }.thenReturn(listOf(storedSwap(recipientAmountSat = null)))

        assertNull(sut.findSendPayment(PAYMENT_HASH))
    }

    @Test
    fun `findSendPayment is null when swaps cannot be read`() = test {
        whenever(boltzService.listSwaps()).thenAnswer { throw AppError("database unavailable") }

        assertNull(sut.findSendPayment(PAYMENT_HASH))
    }

    private fun sendSwap(onchainAmountSat: ULong = QUOTE.lockupSat) = ReverseSwapResponse(
        id = "send-swap",
        invoice = INVOICE,
        lockupAddress = "bcrt1qlockup",
        onchainAmountSat = onchainAmountSat,
        timeoutBlockHeight = 800uL,
    )

    private fun storedSwap(invoice: String = INVOICE, recipientAmountSat: ULong? = QUOTE.recipientSat) = BoltzSwap(
        id = "send-swap",
        swapType = BoltzSwapType.REVERSE,
        status = BoltzSwapStatus.TransactionConfirmed,
        network = BoltzNetwork.REGTEST,
        swapIndex = 0uL,
        amountSat = QUOTE.invoiceSat,
        onchainAmountSat = QUOTE.lockupSat,
        recipientAmountSat = recipientAmountSat,
        invoice = invoice,
        lockupAddress = "bcrt1qlockup",
        onchainAddress = ADDRESS,
        timeoutBlockHeight = 800uL,
        createdAt = 0uL,
        claimTxId = null,
        refundTxId = null,
    )
}
