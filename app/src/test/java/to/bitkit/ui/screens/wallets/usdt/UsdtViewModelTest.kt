package to.bitkit.ui.screens.wallets.usdt

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.repositories.parseUsdtPaymentRequest
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.SanityWarning
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsdtViewModelTest : BaseUnitTest() {
    private val repo: UsdtRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val settings = MutableStateFlow(SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = true))
    private val quote = UsdtQuote(
        id = "payment",
        recipient = "0x1111111111111111111111111111111111111111",
        destination = UsdtDestination.ARBITRUM,
        bridgeProvider = null,
        amount = 1_000_000u,
        receivedAmount = 1_000_000u,
        maximumFee = 20u,
        expiresAt = 100u,
    )

    @Test
    fun `recipient errors prevent advancing until a valid address is parsed`() = test {
        val viewModel = createViewModel()
        val parser = Mockito.mockStatic(Class.forName("to.bitkit.repositories.UsdtRepoKt"))
        parser.`when`<Result<UsdtPaymentRequest>> { parseUsdtPaymentRequest("invalid", UsdtDestination.ARBITRUM) }
            .thenReturn(Result.failure(UsdtException.InvalidAddress()))
        for (destination in listOf(UsdtDestination.ARBITRUM, UsdtDestination.POLYGON)) {
            parser.`when`<Result<UsdtPaymentRequest>> { parseUsdtPaymentRequest("valid request", destination) }
                .thenReturn(Result.success(UsdtPaymentRequest(quote.recipient, 1_500_000u, 42161u)))
        }
        try {
            var request: UsdtPaymentRequest? = null
            viewModel.validateRecipient("invalid", UsdtDestination.ARBITRUM) { request = it }
            assertNull(request)
            assertEquals(R.string.usdt__error_address, viewModel.state.value.error)
            viewModel.validateRecipient("valid request", UsdtDestination.POLYGON) { request = it }
            assertNull(request)
            assertEquals(R.string.usdt__error_address, viewModel.state.value.error)
            viewModel.validateRecipient("valid request", UsdtDestination.ARBITRUM) { request = it }
            assertEquals(quote.recipient, request?.recipient)
            assertEquals(1_500_000uL, request?.amount)
            verify(repo, never()).quote(any(), any(), any(), any())
            verify(repo, never()).send(any(), any())
            assertNull(viewModel.state.value.error)
        } finally { parser.close() }
    }

    @Test
    fun `payment authentication cannot be skipped or reused after cancellation`() = test {
        val viewModel = createViewModel()
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        assertTrue(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), any())

        viewModel.cancelAuthentication()
        viewModel.authenticationVerified()
        verify(repo, never()).send(any(), any())

        viewModel.confirm().join()
        viewModel.authenticationVerified()
        viewModel.authenticationVerified()
        viewModel.confirm().join()
        verify(repo, times(1)).send(eq(quote), any())
        assertTrue(viewModel.state.value.submitted)
        assertTrue(viewModel.state.value.busy)
        viewModel.waitForTransfer()
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `failed submission clears the quote and never reports success`() = test {
        val viewModel = createViewModel()
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        whenever(repo.send(eq(quote), any())).thenReturn(Result.failure(UsdtException.QuoteExpired()))
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        assertFalse(viewModel.state.value.authenticationRequired)
        assertFalse(viewModel.state.value.submitted)
        assertFalse(viewModel.state.value.busy)
        assertNull(viewModel.state.value.quote)
        assertEquals(R.string.usdt__error_expired, viewModel.state.value.error)
        viewModel.confirm().join()
        verify(repo, times(1)).send(eq(quote), any())
    }

    @Test
    fun `amount warnings require acknowledgment before payment authentication`() = test {
        val viewModel = createViewModel()
        val large = quote.copy(amount = 200_000_000u, receivedAmount = 200_000_000u)
        settings.value = settings.value.copy(enableSendAmountWarning = true)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(large))
        viewModel.quote(large.recipient, "200", large.destination).join()
        viewModel.confirm().join()
        assertEquals(SanityWarning.VALUE_OVER_100_USD, viewModel.state.value.warning)
        assertFalse(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), any())
        viewModel.acceptWarning()
        assertNull(viewModel.state.value.warning)
        assertTrue(viewModel.state.value.authenticationRequired)
        viewModel.edit()
        assertTrue(viewModel.state.value.confirmedWarnings.isEmpty())
    }

    @Test
    fun `editing while settings load cannot authorize a replacement quote`() = test {
        val viewModel = createViewModel()
        val delayed = kotlinx.coroutines.flow.MutableSharedFlow<SettingsData>()
        whenever(settingsStore.data).thenReturn(delayed)
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        val confirmation = viewModel.confirm()
        assertTrue(viewModel.state.value.busy)
        viewModel.edit()
        val replacement = quote.copy(id = "replacement", amount = 2_000_000u)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(replacement))
        viewModel.quote(replacement.recipient, "2", replacement.destination).join()
        delayed.emit(SettingsData(isPinEnabled = false))
        confirmation.join()
        verify(repo, never()).send(any(), any())
        assertEquals("replacement", viewModel.state.value.quote?.id)
    }

    @Test
    fun `settings failures keep balances private and block confirmation until settings recover`() = test {
        var failing = true
        val viewModel = createViewModel(
            flow {
                if (failing) throw IOException("settings unavailable")
                emit(settings.value)
            }
        )
        val collector = backgroundScope.launch { viewModel.settings.collect {} }
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        assertTrue(viewModel.settings.value.hideBalance)
        assertEquals(R.string.usdt__error_storage, viewModel.state.value.error)
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), any())

        failing = false
        viewModel.confirm().join()
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), any())
        collector.cancel()
    }

    @Test
    fun `payment settings never authorize a refund on read failure or cancellation`() = test {
        val viewModel = createViewModel()
        whenever(settingsStore.data).thenReturn(flow { throw IOException("settings unavailable") })
        var refundAuthorized = false
        viewModel.readPaymentSettings { refundAuthorized = true }
        assertFalse(refundAuthorized)
        assertEquals(R.string.usdt__error_storage, viewModel.state.value.error)

        viewModel.edit()
        whenever(settingsStore.data).thenReturn(flow { throw CancellationException() })
        assertFailsWith<CancellationException> {
            viewModel.readPaymentSettings { refundAuthorized = true }
        }
        assertFalse(refundAuthorized)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `cancelled execution wait keeps the submitted payment and releases the swipe`() = test {
        val viewModel = createViewModel()
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        whenever(repo.waitForTransfer(quote.id)).thenThrow(CancellationException())
        assertFailsWith<CancellationException> { viewModel.waitForTransfer() }
        assertTrue(viewModel.state.value.submitted)
        assertFalse(viewModel.state.value.busy)
        assertEquals(quote, viewModel.state.value.quote)
        assertNull(viewModel.state.value.error)
    }

    private suspend fun createViewModel(settingsFlow: Flow<SettingsData> = settings): UsdtViewModel {
        whenever(repo.state).thenReturn(MutableStateFlow(UsdtWalletState()))
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(quote))
        whenever(repo.send(eq(quote), any())).thenReturn(Result.success(mock<UsdtTransfer>()))
        whenever(repo.refresh(false)).thenReturn(Result.success(Unit))
        whenever(repo.waitForTransfer(quote.id)).thenReturn(Result.success(Unit))
        return UsdtViewModel(repo, settingsStore)
    }
}
