package to.bitkit.wear.ui.screens.activity

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.repositories.PaymentsRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityViewModelTest : BaseUnitTest() {
    private val paymentsRepo = mock<PaymentsRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore> { on { data } doReturn flowOf(WatchSettings()) }
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Connecting))
    private val paymentChanges = MutableSharedFlow<Unit>()

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(paymentsRepo.paymentChanges).thenReturn(paymentChanges)
    }

    private fun createSut() = ActivityViewModel(paymentsRepo, walletRepo, settingsStore) { NOW }

    @Test
    fun `the list is read once spark is connected`() = test {
        whenever(paymentsRepo.recentPayments()).thenReturn(Result.success(listOf(payment("a"))))
        val sut = createSut()

        verify(paymentsRepo, never()).recentPayments()
        assertFalse(sut.uiState.value.isLoaded)

        walletState.update { it.copy(status = WalletStatus.Ready) }

        assertTrue(sut.uiState.value.isLoaded)
        assertEquals(listOf(payment("a")), sut.uiState.value.payments)
        assertEquals(NOW, sut.uiState.value.nowSecs)
    }

    @Test
    fun `a payment or a sync reads the list again`() = test {
        walletState.update { it.copy(status = WalletStatus.Ready) }
        whenever(paymentsRepo.recentPayments()).thenReturn(Result.success(listOf(payment("a"))))
        val sut = createSut()
        whenever(paymentsRepo.recentPayments()).thenReturn(Result.success(listOf(payment("b"), payment("a"))))

        paymentChanges.emit(Unit)

        verify(paymentsRepo, times(2)).recentPayments()
        assertEquals(listOf("b", "a"), sut.uiState.value.payments.map { it.id })
        assertEquals(payment("b"), sut.uiState.value.payment("b"))
    }

    @Test
    fun `the last list stays when spark cannot be read`() = test {
        walletState.update { it.copy(status = WalletStatus.Ready) }
        whenever(paymentsRepo.recentPayments()).thenReturn(Result.success(listOf(payment("a"))))
        val sut = createSut()
        whenever(paymentsRepo.recentPayments()).thenReturn(Result.failure(SparkError.NotConnected()))

        walletState.update { it.copy(status = WalletStatus.Paused) }
        paymentChanges.emit(Unit)

        assertEquals(listOf(payment("a")), sut.uiState.value.payments)
    }

    @Test
    fun `an empty wallet loads an empty list`() = test {
        walletState.update { it.copy(status = WalletStatus.Offline) }
        whenever(paymentsRepo.recentPayments()).thenReturn(Result.success(emptyList()))

        val sut = createSut()

        assertTrue(sut.uiState.value.isLoaded)
        assertTrue(sut.uiState.value.payments.isEmpty())
    }

    private fun payment(id: String) = WatchPayment(
        id = id,
        isSent = true,
        amountSats = 1_000uL,
        feeSats = 1uL,
        timestampSecs = 1_700_000_000uL,
        state = PaymentState.Completed,
    )

    private companion object {
        const val NOW = 1_800_000_000L
    }
}
