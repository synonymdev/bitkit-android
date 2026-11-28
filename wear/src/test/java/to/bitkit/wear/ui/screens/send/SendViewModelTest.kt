package to.bitkit.wear.ui.screens.send

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import breez_sdk_spark.PrepareSendPaymentResponse
import com.synonym.bitkitcore.LnurlPayData
import kotlinx.coroutines.CompletableDeferred
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.AmbientMonitor
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.BitcoinDisplayUnit
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
import to.bitkit.wear.security.FakeForegroundMonitor
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
    private val foregroundMonitor = FakeForegroundMonitor()
    private val ambientMonitor = AmbientMonitor()
    private var elapsedMs = 1_000_000L

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(currencyRepo.rates).thenReturn(MutableStateFlow(emptyList<FxRate>()))
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings()))
        whenever { paymentsRepo.skipConfirmation(any()) }.thenReturn(false)
    }

    private fun createSut(
        savedState: SavedStateHandle = SavedStateHandle(mapOf(Routes.SEND_INPUT to INPUT)),
    ) = SendViewModel(
        savedState,
        paymentsRepo,
        walletRepo,
        currencyRepo,
        settingsStore,
        foregroundMonitor,
        ambientMonitor,
    ) { elapsedMs }

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
    fun `quickpay sends without the review or the hold and ends on the same result`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        val sent = CompletableDeferred<Result<WatchPayment>>()
        whenever(paymentsRepo.skipConfirmation(prepared)).thenReturn(true)
        whenever(paymentsRepo.send(prepared)).doSuspendableAnswer { sent.await() }

        val sut = createSut()

        assertIs<SendStep.Sending>(sut.uiState.value.step)
        assertFalse(sut.uiState.value.isHoldEnabled)
        verify(paymentsRepo, times(1)).skipConfirmation(prepared)

        sent.complete(Result.success(payment(PaymentState.Completed)))

        val done = assertIs<SendStep.Done>(sut.uiState.value.step)
        assertEquals(SendResult.Sent, done.result)
        verify(paymentsRepo, times(1)).send(prepared)
    }

    @Test
    fun `quickpay still applies to a link that opened the app and waited a moment for the wallet`() = test {
        for (status in listOf(WalletStatus.Idle, WalletStatus.Paused, WalletStatus.Connecting)) {
            walletState.update { it.copy(status = status) }
            val prepared = prepared()
            stubInvoice(prepared)
            whenever(paymentsRepo.skipConfirmation(prepared)).thenReturn(true)
            whenever(paymentsRepo.send(prepared)).doSuspendableAnswer { awaitCancellation() }

            val sut = createSut()
            walletState.update { it.copy(status = WalletStatus.Connecting) }
            elapsedMs += SendViewModel.QUICKPAY_WINDOW.inWholeMilliseconds
            walletState.update { it.copy(status = WalletStatus.Ready) }

            assertIs<SendStep.Sending>(sut.uiState.value.step, "$status")
        }
    }

    @Test
    fun `a link that waited long for the wallet is reviewed and held, never paid under quickpay`() = test {
        for (status in listOf(WalletStatus.Offline, WalletStatus.Error, WalletStatus.Connecting)) {
            walletState.update { it.copy(status = status) }
            stubInvoice(prepared())
            whenever(paymentsRepo.skipConfirmation(any())).thenReturn(true)

            val sut = createSut()
            elapsedMs += SendViewModel.QUICKPAY_WINDOW.inWholeMilliseconds + 1
            walletState.update { it.copy(status = WalletStatus.Ready) }

            assertIs<SendStep.Review>(sut.uiState.value.step, "$status")
            assertTrue(sut.uiState.value.isHoldEnabled)
        }
        verify(paymentsRepo, never()).skipConfirmation(any())
        verify(paymentsRepo, never()).send(any())
    }

    @Test
    fun `a flow rebuilt for a link that already had one is reviewed and held, never paid under quickpay`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.skipConfirmation(prepared)).thenReturn(true)
        whenever(paymentsRepo.send(prepared)).doSuspendableAnswer { awaitCancellation() }
        // The first flow ends on the review, as when the watch dimmed, and the wearer leaves without paying.
        ambientMonitor.set(true)
        val savedState = SavedStateHandle(mapOf(Routes.SEND_INPUT to INPUT))
        assertIs<SendStep.Review>(createSut(savedState).uiState.value.step)
        ambientMonitor.set(false)

        // The process is killed and the back stack restored: a new flow on what the first one saved.
        val restored = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) })
        val sut = createSut(restored)

        assertIs<SendStep.Review>(sut.uiState.value.step)
        assertTrue(sut.uiState.value.isHoldEnabled)
        verify(paymentsRepo, never()).skipConfirmation(any())
        verify(paymentsRepo, never()).send(any())

        sut.onHoldComplete()

        verify(paymentsRepo).send(prepared)
    }

    @Test
    fun `a link whose wait crossed a lock, a pause or ambient mode is reviewed and held`() = test {
        val interruptions = listOf<Pair<String, () -> Unit>>(
            "lock" to {
                walletState.update { it.copy(status = WalletStatus.Locked) }
                walletState.update { it.copy(status = WalletStatus.Connecting) }
            },
            "pause" to {
                foregroundMonitor.isForeground.update { false }
                foregroundMonitor.isForeground.update { true }
            },
            "wallet pause" to {
                walletState.update { it.copy(status = WalletStatus.Paused) }
                walletState.update { it.copy(status = WalletStatus.Connecting) }
            },
            "ambient" to {
                ambientMonitor.set(true)
                ambientMonitor.set(false)
            },
        )
        for ((name, interrupt) in interruptions) {
            walletState.update { it.copy(status = WalletStatus.Connecting) }
            stubInvoice(prepared())
            whenever(paymentsRepo.skipConfirmation(any())).thenReturn(true)

            val sut = createSut()
            interrupt()
            walletState.update { it.copy(status = WalletStatus.Ready) }

            assertIs<SendStep.Review>(sut.uiState.value.step, name)
        }
        verify(paymentsRepo, never()).skipConfirmation(any())
        verify(paymentsRepo, never()).send(any())
    }

    @Test
    fun `a link that arrives on a locked watch is reviewed and held`() = test {
        walletState.update { it.copy(status = WalletStatus.Locked) }
        stubInvoice(prepared())
        whenever(paymentsRepo.skipConfirmation(any())).thenReturn(true)

        val sut = createSut()
        walletState.update { it.copy(status = WalletStatus.Ready) }

        assertIs<SendStep.Review>(sut.uiState.value.step)
        verify(paymentsRepo, never()).skipConfirmation(any())
    }

    @Test
    fun `a wearer who looked away while quickpay was decided is asked to hold`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.skipConfirmation(prepared)).doSuspendableAnswer {
            ambientMonitor.set(true)
            true
        }

        val sut = createSut()

        assertIs<SendStep.Review>(sut.uiState.value.step)
        verify(paymentsRepo, never()).send(any())
    }

    @Test
    fun `a quickpay send that fails shows the failure like any other`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.skipConfirmation(prepared)).thenReturn(true)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.failure(SendError.Failed()))

        val sut = createSut()

        assertEquals(SendStep.Failed(SendFailure.Failed), sut.uiState.value.step)
    }

    @Test
    fun `without quickpay the send waits on review, written in the phone's unit`() = test {
        whenever(settingsStore.data)
            .thenReturn(flowOf(WatchSettings(displayUnit = BitcoinDisplayUnit.CLASSIC)))
        val prepared = prepared(amountSats = 21_000uL)
        stubInvoice(prepared)
        whenever(paymentsRepo.skipConfirmation(prepared)).thenReturn(false)

        val sut = createSut()

        val step = assertIs<SendStep.Review>(sut.uiState.value.step)
        assertEquals("0.00021000", step.amount.text)
        verify(paymentsRepo, never()).send(any())
    }

    @Test
    fun `a request that arrives offline ends on the offline error without being read`() = test {
        walletState.update { it.copy(status = WalletStatus.Offline) }
        stubInvoice(prepared())

        val sut = createSut()

        assertEquals(SendStep.Offline, sut.uiState.value.step)
        assertFalse(sut.uiState.value.isHoldEnabled)
        verify(paymentsRepo, never()).parse(any())
    }

    @Test
    fun `going offline on review ends on the offline error and a completed hold sends nothing`() = test {
        stubInvoice(prepared())
        val sut = createSut()
        assertIs<SendStep.Review>(sut.uiState.value.step)

        walletState.update { it.copy(status = WalletStatus.Offline) }

        assertEquals(SendStep.Offline, sut.uiState.value.step)
        assertFalse(sut.uiState.value.isHoldEnabled)
        sut.onHoldComplete()
        verify(paymentsRepo, never()).send(any())
    }

    @Test
    fun `going offline while pricing drops the request`() = test {
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.success(INVOICE))
        whenever(paymentsRepo.prepare(INVOICE, null)).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        assertEquals(SendStep.Loading, sut.uiState.value.step)

        walletState.update { it.copy(status = WalletStatus.Offline) }

        assertEquals(SendStep.Offline, sut.uiState.value.step)
    }

    @Test
    fun `the network coming back does not bring an offline request back`() = test {
        walletState.update { it.copy(status = WalletStatus.Offline) }
        stubInvoice(prepared())
        val sut = createSut()

        sut.effects.test {
            walletState.update { it.copy(status = WalletStatus.Ready) }

            assertEquals(SendStep.Offline, sut.uiState.value.step)
            verify(paymentsRepo, never()).parse(any())
            verify(paymentsRepo, never()).send(any())
            sut.onHoldComplete()
            sut.onCloseClick()
            assertEquals(SendEffect.Close, awaitItem())
        }
        verify(paymentsRepo, never()).send(any())
    }

    @Test
    fun `going offline once the send started keeps its outcome`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        sut.onHoldComplete()

        walletState.update { it.copy(status = WalletStatus.Offline) }

        assertIs<SendStep.Sending>(sut.uiState.value.step)
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
    fun `a sent payment shows the amount spark paid`() = test {
        val prepared = prepared(amountSats = 5_000uL)
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.success(payment(PaymentState.Completed)))
        val sut = createSut()

        sut.onHoldComplete()

        assertEquals(SendStep.Done(SendResult.Sent, DisplayAmount("₿", "21 000")), sut.uiState.value.step)
    }

    @Test
    fun `a payment already under way shows as pending, never as a failure`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.failure(SendError.InFlight()))
        val sut = createSut()

        sut.effects.test {
            sut.onHoldComplete()
            assertEquals(SendResult.Pending, assertIs<SendStep.Done>(sut.uiState.value.step).result)

            testScheduler.advanceTimeBy(SendViewModel.PENDING_CLOSE_DELAY + 1.seconds)
            assertEquals(SendEffect.Close, awaitItem())
        }
    }

    @Test
    fun `a wallet busy with another send is a failure, never pending`() = test {
        val prepared = prepared()
        stubInvoice(prepared)
        whenever(paymentsRepo.send(prepared)).thenReturn(Result.failure(SendError.Busy()))
        val sut = createSut()

        sut.onHoldComplete()

        assertEquals(SendStep.Failed(SendFailure.Busy), sut.uiState.value.step)
    }

    @Test
    fun `a request found under way before the review shows as pending and closes`() = test {
        whenever(paymentsRepo.parse(INPUT)).thenReturn(Result.success(INVOICE))
        whenever(paymentsRepo.prepare(INVOICE, null)).thenReturn(Result.failure(SendError.InFlight()))

        val sut = createSut()

        sut.effects.test {
            assertEquals(SendResult.Pending, assertIs<SendStep.Done>(sut.uiState.value.step).result)
            testScheduler.advanceTimeBy(SendViewModel.PENDING_CLOSE_DELAY + 1.seconds)
            assertEquals(SendEffect.Close, awaitItem())
        }
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
        expiresAtSecs = 1_800_003_600uL,
        sparkPayee = null,
        transferKey = "transfer",
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
