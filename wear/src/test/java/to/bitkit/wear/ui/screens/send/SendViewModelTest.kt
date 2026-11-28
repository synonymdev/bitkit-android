package to.bitkit.wear.ui.screens.send

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import breez_sdk_spark.PrepareSendPaymentResponse
import com.synonym.bitkitcore.LnurlPayData
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.PreparedSend
import to.bitkit.wear.models.SendError
import to.bitkit.wear.models.SendRequest
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.PaymentsRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.test.BaseUnitTest
import to.bitkit.wear.ui.Routes
import to.bitkit.wear.ui.screens.home.DisplayAmount
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SendViewModelTest : BaseUnitTest() {
    private val paymentsRepo = mock<PaymentsRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Ready, 100_000uL, hasSynced = true))

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(currencyRepo.rates).thenReturn(MutableStateFlow(emptyList<FxRate>()))
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings()))
    }

    private fun createSut() = SendViewModel(
        SavedStateHandle(mapOf(Routes.SEND_INPUT to INPUT)),
        paymentsRepo,
        walletRepo,
        currencyRepo,
        settingsStore,
    )

    @Test
    fun `the input is read only once the wallet is ready`() = test {
        walletState.update { it.copy(status = WalletStatus.Connecting) }
        stubInvoice(prepared())

        val sut = createSut()

        assertEquals(SendStep.Loading, sut.uiState.value.step)
        verify(paymentsRepo, never()).parse(any())

        walletState.update { it.copy(status = WalletStatus.Ready) }

        verify(paymentsRepo).parse(INPUT)
        assertIs<SendStep.Review>(sut.uiState.value.step)
    }

    @Test
    fun `an invoice with an amount goes straight to review without a small fee`() = test {
        stubInvoice(prepared(amountSats = 21_000uL, feeSats = 3uL))

        val sut = createSut()

        val step = assertIs<SendStep.Review>(sut.uiState.value.step)
        assertEquals(DisplayAmount("₿", "21 000"), step.amount)
        assertNull(step.fee)
        assertEquals("Coffee", step.payee)
        assertTrue(sut.uiState.value.isHoldEnabled)
    }

    @Test
    fun `a fee of at least one percent shows on review`() = test {
        stubInvoice(prepared(amountSats = 1_000uL, feeSats = 10uL))

        val sut = createSut()

        val step = assertIs<SendStep.Review>(sut.uiState.value.step)
        assertEquals(DisplayAmount("₿", "10"), step.fee)
    }

    @Test
    fun `nothing is sent before the hold completes`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.success(payment(PaymentState.Completed)))

        val sut = createSut()
        verify(paymentsRepo, never()).send(any())

        sut.onHoldComplete()

        verify(paymentsRepo).send(prepared)
    }

    @Test
    fun `the quickpay hook sends without the hold`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.skipConfirmation(prepared)).thenReturn(true)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.success(payment(PaymentState.Completed)))

        createSut()

        verify(paymentsRepo).send(prepared)
    }

    @Test
    fun `offline disables the hold and a completed hold sends nothing`() = test {
        stubInvoice(prepared())
        val sut = createSut()

        walletState.update { it.copy(status = WalletStatus.Offline) }

        assertFalse(sut.uiState.value.isHoldEnabled)
        assertTrue(sut.uiState.value.isOffline)
        sut.onHoldComplete()
        verify(paymentsRepo, never()).send(any())
        assertIs<SendStep.Review>(sut.uiState.value.step)
    }

    @Test
    fun `a sent payment shows the check and then closes the flow`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.success(payment(PaymentState.Completed)))
        val sut = createSut()

        sut.effects.test {
            sut.onHoldComplete()
            assertEquals(SendStep.Done(SendResult.Sent, DisplayAmount("₿", "21 000")), sut.uiState.value.step)
            expectNoEvents()

            testScheduler.advanceTimeBy(SendViewModel.SENT_CLOSE_DELAY + 1.seconds)
            assertEquals(SendEffect.Close, awaitItem())
        }
    }

    @Test
    fun `a payment that did not settle shows as pending`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.success(payment(PaymentState.Pending)))
        val sut = createSut()

        sut.onHoldComplete()

        assertEquals(SendResult.Pending, assertIs<SendStep.Done>(sut.uiState.value.step).result)
    }

    @Test
    fun `an unknown outcome never shows as a failure`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.failure(SendError.Unknown()))
        val sut = createSut()

        sut.onHoldComplete()

        assertEquals(SendResult.Unknown, assertIs<SendStep.Done>(sut.uiState.value.step).result)
    }

    @Test
    fun `a failed send shows its reason`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.failure(SendError.Failed()))
        val sut = createSut()

        sut.onHoldComplete()

        assertEquals(SendStep.Failed(SendFailure.Failed), sut.uiState.value.step)
    }

    @Test
    fun `a slow send says it is waiting for the network`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()

        sut.onHoldComplete()
        assertEquals(SendStep.Sending(isWaiting = false), sut.uiState.value.step)
        assertTrue(sut.uiState.value.isBusy)

        testScheduler.advanceTimeBy(SendViewModel.WAITING_HINT_DELAY + 1.seconds)
        assertEquals(SendStep.Sending(isWaiting = true), sut.uiState.value.step)
    }

    @Test
    fun `an own invoice is refused before the hold`() = test {
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.failure(SendError.OwnInvoice()))

        val sut = createSut()

        assertEquals(SendStep.Failed(SendFailure.OwnInvoice), sut.uiState.value.step)
        verify(paymentsRepo, never()).prepare(any(), anyOrNull())
    }

    @Test
    fun `a short balance is refused before the hold`() = test {
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.success(INVOICE))
        whenever(paymentsRepo.prepare(INVOICE, null))
            .thenReturn(Result.failure(SendError.InsufficientBalance(shortfallSats = 500uL)))

        val sut = createSut()

        assertEquals(SendStep.Failed(SendFailure.Insufficient), sut.uiState.value.step)
    }

    @Test
    fun `an amountless invoice asks for the amount and prices the typed one`() = test {
        val request = SendRequest.Invoice(BOLT11, amountSats = null, description = null)
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.success(request))
        whenever(paymentsRepo.prepare(request, 2_100uL)).thenReturn(Result.success(prepared(amountSats = 2_100uL)))
        val sut = createSut()

        assertEquals(SendStep.Amount(), sut.uiState.value.step)
        verify(paymentsRepo, never()).prepare(any(), anyOrNull())

        sut.onAmountChange("2a1 00")
        assertEquals("2100", sut.uiState.value.amountText)
        sut.onAmountConfirm()

        verify(paymentsRepo).prepare(request, 2_100uL)
        assertIs<SendStep.Review>(sut.uiState.value.step)
    }

    @Test
    fun `an amount outside the lnurl range is refused without pricing`() = test {
        val request = SendRequest.Lnurl(lnurl(), minSats = 10uL, maxSats = 1_000uL, payee = "satoshi@pay.example")
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.success(request))
        val sut = createSut()
        assertEquals(SendStep.Amount(minSats = 10uL, maxSats = 1_000uL), sut.uiState.value.step)

        sut.onAmountChange("5000")
        sut.onAmountConfirm()

        assertEquals(SendStep.Amount(minSats = 10uL, maxSats = 1_000uL, isOutOfRange = true), sut.uiState.value.step)
        verify(paymentsRepo, never()).prepare(any(), anyOrNull())

        sut.onAmountChange("500")
        assertFalse(assertIs<SendStep.Amount>(sut.uiState.value.step).isOutOfRange)
    }

    private suspend fun stubInvoice(prepared: PreparedSend) {
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.success(INVOICE))
        whenever(paymentsRepo.prepare(INVOICE, null)).thenReturn(Result.success(prepared))
    }

    private fun prepared(amountSats: ULong = 21_000uL, feeSats: ULong = 0uL) = PreparedSend(
        amountSats = amountSats,
        feeSats = feeSats,
        payee = null,
        response = mock<PrepareSendPaymentResponse>(),
        paymentHash = "ab12",
        transferKey = null,
    )

    private fun payment(state: PaymentState) = WatchPayment(
        id = "send",
        isSent = true,
        amountSats = 21_000uL,
        feeSats = 0uL,
        timestampSecs = 1_800_000_000uL,
        state = state,
    )

    private fun lnurl() = LnurlPayData(
        uri = "lnurl1abc",
        callback = "https://pay.example/lnurlp/satoshi/callback",
        minSendable = 10_000uL,
        maxSendable = 1_000_000uL,
        metadataStr = "[]",
        commentAllowed = null,
        allowsNostr = false,
        nostrPubkey = null,
    )

    private companion object {
        const val BOLT11 = "lnbcrt1invoice"
        const val INPUT = "lightning:$BOLT11"
        val INVOICE = SendRequest.Invoice(BOLT11, amountSats = 21_000uL, description = "Coffee")
    }
}
