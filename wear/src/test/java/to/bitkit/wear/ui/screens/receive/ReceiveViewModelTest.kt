package to.bitkit.wear.ui.screens.receive

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.ReceiveError
import to.bitkit.wear.models.ReceiveInvoice
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.repositories.PaymentsRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.test.BaseUnitTest
import to.bitkit.wear.ui.screens.home.DisplayAmount
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReceiveViewModelTest : BaseUnitTest() {
    private val paymentsRepo = mock<PaymentsRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore> { on { data } doReturn flowOf(WatchSettings()) }
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Ready, 0uL, hasSynced = true))

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
    }

    private val created = mutableListOf<ReceiveViewModel>()

    private fun createSut() = ReceiveViewModel(paymentsRepo, walletRepo, settingsStore) { NOW }.also { created += it }

    /** An unpaid invoice is replaced forever, so the view models stop before the test drains its scheduler. */
    override fun onTestEnd() = created.forEach { it.viewModelScope.cancel() }

    @Test
    fun `shows the uppercased invoice once the wallet is ready`() = test {
        walletState.update { it.copy(status = WalletStatus.Connecting) }
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }

        val sut = createSut()

        assertTrue(sut.uiState.value.isLoading)
        verify(paymentsRepo, never()).createInvoice()

        walletState.update { it.copy(status = WalletStatus.Ready) }

        assertEquals(BOLT11.uppercase(), sut.uiState.value.qrContent)
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `a paid invoice turns into the check with its amount`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).thenReturn(payment(21_000uL))

        val sut = createSut()

        assertNull(sut.uiState.value.qrContent)
        assertEquals(DisplayAmount("₿", "21 000"), sut.uiState.value.paidAmount)
    }

    @Test
    fun `a new invoice replaces the paid one on refresh`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).thenReturn(payment(21_000uL))
        val sut = createSut()
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }

        sut.onRefreshClick()

        assertNull(sut.uiState.value.paidAmount)
        assertEquals(BOLT11.uppercase(), sut.uiState.value.qrContent)
        verify(paymentsRepo, times(2)).createInvoice()
    }

    @Test
    fun `an invoice is replaced shortly before it expires`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        createSut()
        verify(paymentsRepo, times(1)).createInvoice()

        testScheduler.advanceTimeBy(LIFETIME.seconds - ReceiveViewModel.EXPIRY_MARGIN - 1.seconds)
        verify(paymentsRepo, times(1)).createInvoice()

        testScheduler.advanceTimeBy(2.seconds)
        verify(paymentsRepo, times(2)).createInvoice()
    }

    @Test
    fun `the invoice stays on screen until its replacement exists`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        walletState.update { it.copy(status = WalletStatus.Offline) }
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice("lnbcrt1next")))

        testScheduler.advanceTimeBy(LIFETIME.seconds - ReceiveViewModel.EXPIRY_MARGIN + 1.seconds)
        assertEquals(BOLT11.uppercase(), sut.uiState.value.qrContent)

        walletState.update { it.copy(status = WalletStatus.Ready) }
        assertEquals("LNBCRT1NEXT", sut.uiState.value.qrContent)
    }

    @Test
    fun `a payment of the replaced invoice still shows the check, until that invoice expires`() = test {
        val paid = CompletableDeferred<WatchPayment>()
        val first = invoice()
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(first))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer {
            if (it.arguments[0] == first) paid.await() else awaitCancellation()
        }
        val sut = createSut()
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice("lnbcrt1next", hash = "cd34")))

        testScheduler.advanceTimeBy(LIFETIME.seconds - ReceiveViewModel.EXPIRY_MARGIN + 30.seconds)
        assertEquals("LNBCRT1NEXT", sut.uiState.value.qrContent)

        paid.complete(payment(21_000uL))

        assertEquals(DisplayAmount("₿", "21 000"), sut.uiState.value.paidAmount)
        assertNull(sut.uiState.value.qrContent)
    }

    @Test
    fun `a payment that arrives while the replacement is being made shows the check`() = test {
        val paid = CompletableDeferred<WatchPayment>()
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { paid.await() }
        val sut = createSut()
        walletState.update { it.copy(status = WalletStatus.Offline) }

        testScheduler.advanceTimeBy(LIFETIME.seconds - ReceiveViewModel.EXPIRY_MARGIN + 10.seconds)
        paid.complete(payment(21_000uL))

        assertEquals(DisplayAmount("₿", "21 000"), sut.uiState.value.paidAmount)
    }

    @Test
    fun `a replaced invoice is no longer watched once it expired`() = test {
        var watched = 0
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer {
            watched++
            try {
                awaitCancellation()
            } finally {
                watched--
            }
        }
        createSut()
        whenever(paymentsRepo.createInvoice())
            .thenReturn(Result.success(invoice("lnbcrt1next", lifetime = 2 * LIFETIME)))

        testScheduler.advanceTimeBy(LIFETIME.seconds - 1.seconds)
        assertEquals(2, watched)

        testScheduler.advanceTimeBy(2.seconds)
        assertEquals(1, watched)
    }

    @Test
    fun `a replacement that fails is tried again while the invoice on screen is valid`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.failure(ReceiveError.NotReady()))

        testScheduler.advanceTimeBy(LIFETIME.seconds - ReceiveViewModel.EXPIRY_MARGIN + 1.seconds)
        assertEquals(BOLT11.uppercase(), sut.uiState.value.qrContent)
        assertFalse(sut.uiState.value.isError)

        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice("lnbcrt1next")))
        testScheduler.advanceTimeBy(ReceiveViewModel.RETRY_DELAY)

        assertEquals("LNBCRT1NEXT", sut.uiState.value.qrContent)
    }

    @Test
    fun `a replacement that keeps failing shows the error only once the invoice is about to expire`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.failure(ReceiveError.NotReady()))

        testScheduler.advanceTimeBy(LIFETIME.seconds - 1.seconds)
        assertEquals(BOLT11.uppercase(), sut.uiState.value.qrContent)

        testScheduler.advanceTimeBy(2.seconds)
        assertTrue(sut.uiState.value.isError)
        assertNull(sut.uiState.value.qrContent)
    }

    @Test
    fun `an invoice that expires before the wallet is ready again leaves the screen`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        val sut = createSut()
        walletState.update { it.copy(status = WalletStatus.Offline) }

        testScheduler.advanceTimeBy(LIFETIME.seconds + 1.seconds)

        assertNull(sut.uiState.value.qrContent)
        assertTrue(sut.uiState.value.isLoading)
    }

    @Test
    fun `an invoice the watch's clock takes for expired still shows for a moment`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice(lifetime = 0)))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        createSut()

        testScheduler.advanceTimeBy(ReceiveViewModel.MIN_LIFETIME - 1.seconds)
        verify(paymentsRepo, times(1)).createInvoice()

        testScheduler.advanceTimeBy(2.seconds)
        verify(paymentsRepo, times(2)).createInvoice()
    }

    @Test
    fun `a failed invoice shows the error until retried`() = test {
        whenever(paymentsRepo.createInvoice()).thenReturn(Result.failure(ReceiveError.NotReady()))
        val sut = createSut()

        assertTrue(sut.uiState.value.isError)
        assertNull(sut.uiState.value.qrContent)

        whenever(paymentsRepo.createInvoice()).thenReturn(Result.success(invoice()))
        whenever(paymentsRepo.awaitPaid(any())).doSuspendableAnswer { awaitCancellation() }
        sut.onRefreshClick()

        assertFalse(sut.uiState.value.isError)
        assertEquals(BOLT11.uppercase(), sut.uiState.value.qrContent)
    }

    private fun invoice(bolt11: String = BOLT11, lifetime: Long = LIFETIME, hash: String = PAYMENT_HASH) =
        ReceiveInvoice(bolt11, hash, (NOW + lifetime).toULong())

    private fun payment(amountSats: ULong) = WatchPayment(
        id = "receipt",
        isSent = false,
        amountSats = amountSats,
        feeSats = 0uL,
        timestampSecs = NOW.toULong(),
        state = PaymentState.Completed,
    )

    private companion object {
        const val BOLT11 = "lnbcrt1invoice"
        const val PAYMENT_HASH = "ab12"
        const val NOW = 1_800_000_000L
        const val LIFETIME = 3_600L
    }
}
