package to.bitkit.ui.screens.wallets.usdt

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.nowTimestamp
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.UsdtSelfSendError
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.repositories.parseUsdtPaymentRequest
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SanityWarning
import to.bitkit.viewmodels.SendUiState
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsdtViewModelTest : BaseUnitTest() {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `initial subscription prepares before authorization and cancellation requires explicit retry`() = test {
        val viewModel = createViewModel()
        val app = mock<AppViewModel>()
        whenever(app.takeInitialUsdtSubscriptionAuthorization()).thenReturn(true, false)
        val uiState = SendUiState(usdtRecipient = quote.recipient, paykitAmount = "1")
        val ready = CompletableDeferred<Result<UsdtQuote>>()
        whenever(repo.quote(any(), any(), any(), any())).doSuspendableAnswer { ready.await() }
        var automaticPayment = false
        val preparation = launch { preparePayment(viewModel, app, uiState) { automaticPayment = it } }
        advanceUntilIdle()
        assertTrue(automaticPayment)
        assertFalse(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), anyOrNull(), any())
        ready.complete(Result.success(quote))
        preparation.join()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.authenticationRequired)
        viewModel.cancelAuthentication()
        preparePayment(viewModel, app, uiState) { automaticPayment = it }
        assertFalse(automaticPayment)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.authenticationRequired)
        verify(repo, times(1)).quote(any(), any(), any(), any())
        verify(repo, never()).send(any(), anyOrNull(), any())

        viewModel.confirm().join()
        assertTrue(viewModel.state.value.authenticationRequired)
        viewModel.authenticationVerified()
        advanceUntilIdle()
        preparePayment(viewModel, app, uiState)
        viewModel.authenticationVerified()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.submitted)
        verify(repo, times(1)).send(any(), anyOrNull(), any())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `manual initial subscription preparation never shows automatic progress or authorizes`() = test {
        val viewModel = createViewModel()
        val app = mock<AppViewModel>()
        whenever(app.takeInitialUsdtSubscriptionAuthorization()).thenReturn(false)
        val uiState = SendUiState(
            usdtRecipient = quote.recipient,
            paykitAmount = "1",
            isInitialSubscriptionPayment = true,
        )
        val ready = CompletableDeferred<Result<UsdtQuote>>()
        whenever(repo.quote(any(), any(), any(), any())).doSuspendableAnswer { ready.await() }
        var automaticPayment = true
        val preparation = launch { preparePayment(viewModel, app, uiState) { automaticPayment = it } }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.busy)
        assertFalse(automaticPayment)
        ready.complete(Result.success(quote))
        preparation.join()
        advanceUntilIdle()
        assertEquals(quote, viewModel.state.value.quote)
        assertFalse(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `failed initial subscription quote cannot authorize automatically after recovery`() = test {
        for (error in listOf(UsdtException.InsufficientBalance(), UsdtException.QuoteExpired())) {
            val viewModel = createViewModel()
            val app = mock<AppViewModel>()
            whenever(app.takeInitialUsdtSubscriptionAuthorization()).thenReturn(true, false)
            val uiState = SendUiState(usdtRecipient = quote.recipient, paykitAmount = "1")
            whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.failure(error), Result.success(quote))
            preparePayment(viewModel, app, uiState)
            advanceUntilIdle()
            assertNull(viewModel.state.value.quote)
            assertFalse(viewModel.state.value.authenticationRequired)
            preparePayment(viewModel, app, uiState)
            advanceUntilIdle()
            assertEquals(quote, viewModel.state.value.quote)
            assertFalse(viewModel.state.value.authenticationRequired)
            verify(repo, never()).send(any(), anyOrNull(), any())
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelled initial subscription preparation cannot authorize when quote finishes`() = test {
        val viewModel = createViewModel()
        val app = mock<AppViewModel>()
        whenever(app.takeInitialUsdtSubscriptionAuthorization()).thenReturn(true, false)
        val uiState = SendUiState(usdtRecipient = quote.recipient, paykitAmount = "1")
        val ready = CompletableDeferred<Result<UsdtQuote>>()
        whenever(repo.quote(any(), any(), any(), any())).doSuspendableAnswer { ready.await() }
        val preparation = launch { preparePayment(viewModel, app, uiState) }
        advanceUntilIdle()
        preparation.cancelAndJoin()
        ready.complete(Result.success(quote))
        advanceUntilIdle()
        preparePayment(viewModel, app, uiState)
        advanceUntilIdle()
        assertEquals(quote, viewModel.state.value.quote)
        assertFalse(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `settled payment rejects replacement quotes until explicitly edited`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        viewModel.waitForTransfer()
        viewModel.quote(quote.recipient, "2", quote.destination).join()
        viewModel.quoteMaximum(quote.recipient, quote.destination) {
            error("Submitted payment must stay terminal")
        }.join()
        viewModel.confirm().join()
        assertTrue(viewModel.state.value.submitted)
        assertEquals(quote, viewModel.state.value.quote)
        verify(repo, times(1)).quote(any(), any(), any(), any())
        verify(repo, never()).quoteMaximum(any(), any())
        verify(repo, times(1)).send(any(), anyOrNull(), any())
        viewModel.edit()
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        verify(repo, times(2)).quote(any(), any(), any(), any())
    }

    @Test
    fun `detached Paykit handler cannot fall back to raw send after authentication`() = test {
        val viewModel = createViewModel()
        viewModel.sendPayment = { error("Detached handler must not run") }
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        viewModel.sendPayment = null
        viewModel.authenticationVerified()
        assertEquals(R.string.wallet__payment_request_unavailable, viewModel.state.value.error)
        assertFalse(viewModel.state.value.submitted)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `detached Paykit handler cannot fall back while payment settings load`() = test {
        val viewModel = createViewModel()
        val delayed = MutableSharedFlow<SettingsData>()
        whenever(settingsStore.data).thenReturn(delayed)
        viewModel.sendPayment = { error("Detached handler must not run") }
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        val confirmation = viewModel.confirm()
        viewModel.sendPayment = null
        delayed.emit(SettingsData(isPinEnabled = false))
        confirmation.join()
        assertEquals(R.string.wallet__payment_request_unavailable, viewModel.state.value.error)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `fee collection failures offer an actionable message without classifying other rejections`() {
        assertEquals(
            R.string.usdt__error_fee_collection,
            UsdtException.TransactionRejected("Paymaster fee collection failed").messageResource(),
        )
        assertEquals(
            R.string.usdt__error_rejected,
            UsdtException.TransactionRejected("Provider rejected the request").messageResource(),
        )
        assertEquals(R.string.usdt__error_balance, UsdtException.InsufficientBalance().messageResource())
        assertEquals(R.string.usdt__error_self_send, UsdtSelfSendError().messageResource())
        assertEquals(R.string.usdt__error_address, UsdtException.InvalidAddress().messageResource())
    }

    @Test
    fun `bridge cost rejection prevents payment review and keeps the amount editable`() = test {
        val viewModel = createViewModel()
        whenever(repo.quote(eq(quote.recipient), eq("3.5"), eq(UsdtDestination.TRON), any()))
            .thenReturn(Result.failure(UsdtException.BridgeAmountTooSmall()))
        viewModel.quote(quote.recipient, "3.5", UsdtDestination.TRON).join()
        assertEquals(R.string.usdt__error_bridge_amount, viewModel.state.value.error)
        assertNull(viewModel.state.value.quote)
        assertFalse(viewModel.state.value.busy)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `bridge rejections retain provider limits and clear when editing`() = test {
        val cases = listOf(
            Triple(UsdtException.BridgeAmountBelowMinimum("200"), R.string.usdt__deposit_minimum, "200".usdCents()),
            Triple(UsdtException.BridgeAmountBelowMinimum(null), R.string.usdt__error_bridge_minimum, null),
            Triple(UsdtException.BridgeAmountAboveMaximum("10000"), R.string.usdt__deposit_maximum, "10000".usdCents()),
            Triple(UsdtException.BridgeAmountAboveMaximum(null), R.string.usdt__error_bridge_maximum, null),
            Triple(UsdtException.BridgeLiquidityUnavailable(), R.string.usdt__error_bridge_liquidity, null),
            Triple(UsdtException.InvalidAmount(), R.string.usdt__error_amount, null),
        )
        val viewModel = createViewModel()
        for ((error, resource, limit) in cases) {
            whenever(repo.quote(eq(quote.recipient), eq("1"), eq(UsdtDestination.SOLANA), any()))
                .thenReturn(Result.failure(error))
            viewModel.quote(quote.recipient, "1", UsdtDestination.SOLANA).join()
            assertEquals(resource, viewModel.state.value.error, error.javaClass.simpleName)
            assertEquals(limit, viewModel.state.value.errorAmount)
            assertNull(viewModel.state.value.quote)
            viewModel.edit()
            assertNull(viewModel.state.value.error)
            assertNull(viewModel.state.value.errorAmount)
        }
        whenever(repo.quoteMaximum(eq(quote.recipient), eq(UsdtDestination.SOLANA)))
            .thenReturn(Result.failure(UsdtException.BridgeLiquidityUnavailable()))
        viewModel.quoteMaximum(quote.recipient, UsdtDestination.SOLANA) {}.join()
        assertEquals(R.string.usdt__error_bridge_liquidity, viewModel.state.value.error)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    private val repo: UsdtRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val walletState = MutableStateFlow(UsdtWalletState())
    private var recoveryGeneration = 0L
    private val settings = MutableStateFlow(SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = true))
    private val quote = UsdtQuote(
        id = "payment",
        recipient = "0x1111111111111111111111111111111111111111",
        destination = UsdtDestination.ARBITRUM,
        bridgeProvider = null,
        amount = 1_000_000u,
        receivedAmount = 1_000_000u,
        maximumFee = 20u,
        expiresAt = nowTimestamp().epochSecond.toULong() + 120u,
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
            assertEquals(42161uL, request?.chainId)
            assertNull(viewModel.state.value.error)
            viewModel.validateRecipient("valid request", UsdtDestination.ARBITRUM) { request = it }
            assertEquals(quote.recipient, request?.recipient)
            assertEquals(1_500_000uL, request?.amount)
            verify(repo, never()).quote(any(), any(), any(), any())
            verify(repo, never()).send(any(), anyOrNull(), any())
            assertNull(viewModel.state.value.error)
        } finally { parser.close() }
    }

    @Test
    fun `returning to unchanged payment review reuses its approved quote`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        val fresh = quote.copy(expiresAt = nowTimestamp().epochSecond.toULong() + 120u)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(fresh))

        viewModel.quote(fresh.recipient, "1", fresh.destination).join()
        viewModel.edit()
        viewModel.quote(fresh.recipient, "1", fresh.destination).join()

        assertEquals(fresh, viewModel.state.value.quote)
        verify(repo, times(1)).quote(any(), any(), any(), any())
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `closing send discards its quote before another payment flow`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        viewModel.isSendPresented = true
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.edit()
        viewModel.isSendPresented = false
        viewModel.isSendPresented = true
        viewModel.quote(quote.recipient, "1", quote.destination).join()

        verify(repo, times(2)).quote(any(), any(), any(), any())
    }

    @Test
    fun `changed amount recipient or network requires another quote`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        val fresh = quote.copy(expiresAt = nowTimestamp().epochSecond.toULong() + 120u)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(fresh))
        viewModel.quote(fresh.recipient, "1", fresh.destination).join()
        viewModel.edit()
        viewModel.quote(fresh.recipient, "2", fresh.destination).join()
        viewModel.edit()
        viewModel.quote("other recipient", "2", fresh.destination).join()
        viewModel.edit()
        viewModel.quote(fresh.recipient, "2", UsdtDestination.POLYGON).join()

        verify(repo, times(4)).quote(any(), any(), any(), any())
    }

    @Test
    fun `changing wallets prevents reuse for the same recipient amount and network`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        val fresh = quote.copy(expiresAt = nowTimestamp().epochSecond.toULong() + 120u)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(fresh))
        viewModel.quote(fresh.recipient, "1", fresh.destination).join()
        viewModel.edit()
        walletState.value = UsdtWalletState(address = "another wallet")
        viewModel.quote(fresh.recipient, "1", fresh.destination).join()

        verify(repo, times(2)).quote(any(), any(), any(), any())
    }

    @Test
    fun `expiring quote is replaced and a failed refresh never displays stale fees`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        val expiring = quote.copy(expiresAt = nowTimestamp().epochSecond.toULong() + 4u)
        whenever(repo.quote(any(), any(), any(), any()))
            .thenReturn(Result.success(expiring), Result.failure(UsdtException.RateLimited()), Result.success(quote))
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.edit()
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        assertNull(viewModel.state.value.quote)
        assertFalse(viewModel.state.value.busy)
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        assertEquals(quote, viewModel.state.value.quote)
        verify(repo, times(3)).quote(any(), any(), any(), any())
    }

    @Test
    fun `submission failure invalidates a reusable quote before retry`() = test {
        val viewModel = createViewModel(walletState = UsdtWalletState(address = "sender"))
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        val fresh = quote.copy(expiresAt = nowTimestamp().epochSecond.toULong() + 120u)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(fresh))
        whenever(repo.send(eq(fresh), anyOrNull(), any()))
            .thenReturn(Result.failure(UsdtException.SubmissionDeadlineExpired()))
        viewModel.quote(fresh.recipient, "1", fresh.destination).join()
        viewModel.confirm().join()
        assertNull(viewModel.state.value.quote)
        viewModel.quote(fresh.recipient, "1", fresh.destination).join()
        verify(repo, times(2)).quote(any(), any(), any(), any())
        verify(repo).send(eq(fresh), anyOrNull(), any())
    }

    @Test
    fun `maximum amount reuses its funded quote only until submission`() = test {
        val viewModel = createViewModel()
        val maximum = quote.copy(amount = 900_000u, expiresAt = nowTimestamp().epochSecond.toULong() + 3600u)
        whenever(repo.quoteMaximum(quote.recipient, quote.destination)).thenReturn(Result.success(maximum))
        Mockito.mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { core ->
            core.`when`<String> { usdtFormatAmount(maximum.amount) }.thenReturn("0.9")
            var amount: String? = null

            viewModel.quoteMaximum(quote.recipient, quote.destination) { amount = it }.join()

            assertEquals("0.9", amount)
            assertFalse(viewModel.state.value.busy)
            assertFalse(viewModel.state.value.preparingMaximum)
            assertNull(viewModel.state.value.quote)
            viewModel.quote(quote.recipient, "0.9", quote.destination).join()
            assertEquals(maximum, viewModel.state.value.quote)
            verify(repo, never()).quote(any(), any(), any(), any())
            verify(repo, never()).send(any(), anyOrNull(), any())

            settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
            whenever(repo.send(eq(maximum), anyOrNull(), any())).thenReturn(Result.success(mock<UsdtTransfer>()))
            viewModel.confirm().join()
            verify(repo).send(eq(maximum), anyOrNull(), any())
            viewModel.waitForTransfer()
            viewModel.edit()
            viewModel.quote(quote.recipient, "0.9", quote.destination).join()
            verify(repo).quote(eq(quote.recipient), eq("0.9"), eq(quote.destination), any())
        }
    }

    @Test
    fun `maximum quote failure preserves the entered amount and permits retry`() = test {
        val viewModel = createViewModel()
        whenever(repo.quoteMaximum(quote.recipient, quote.destination))
            .thenReturn(Result.failure(UsdtException.InsufficientBalance()))
        var amount = "0.5"

        viewModel.quoteMaximum(quote.recipient, quote.destination) { amount = it }.join()

        assertEquals("0.5", amount)
        assertEquals(R.string.usdt__error_balance, viewModel.state.value.error)
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.preparingMaximum)
        assertNull(viewModel.state.value.quote)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `payment authentication cannot be skipped or reused after cancellation`() = test {
        val viewModel = createViewModel()
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        assertTrue(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), anyOrNull(), any())

        viewModel.cancelAuthentication()
        viewModel.authenticationVerified()
        verify(repo, never()).send(any(), anyOrNull(), any())

        viewModel.confirm().join()
        viewModel.authenticationVerified()
        viewModel.authenticationVerified()
        viewModel.confirm().join()
        verify(repo, times(1)).send(eq(quote), anyOrNull(), any())
        assertTrue(viewModel.state.value.submitted)
        assertTrue(viewModel.state.value.busy)
        viewModel.waitForTransfer()
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `failed submission clears the quote and never reports success`() = test {
        val viewModel = createViewModel()
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        whenever(repo.send(eq(quote), anyOrNull(), any()))
            .thenReturn(Result.failure(UsdtException.SubmissionDeadlineExpired()))
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        assertFalse(viewModel.state.value.authenticationRequired)
        assertFalse(viewModel.state.value.submitted)
        assertFalse(viewModel.state.value.busy)
        assertNull(viewModel.state.value.quote)
        assertEquals(R.string.wallet__payment_request_expired, viewModel.state.value.error)
        viewModel.confirm().join()
        verify(repo, times(1)).send(eq(quote), anyOrNull(), any())
    }

    @Test
    fun `durably recorded send failure retains the payment and prevents resubmission`() = test {
        // A previously acknowledged payment must not clear this payment's failed backup.
        recoveryGeneration = 4L
        val retained: UsdtTransfer = mock()
        whenever(retained.id).thenReturn(quote.id)
        whenever(retained.status).thenReturn(UsdtTransferStatus.PENDING)
        val viewModel = createViewModel(walletState = UsdtWalletState(transfers = persistentListOf(retained)))
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        whenever(repo.send(eq(quote), anyOrNull(), any()))
            .thenReturn(Result.failure(UsdtException.BackupUnavailable()))
        viewModel.quote(quote.recipient, "1", quote.destination).join()

        viewModel.confirm().join()

        assertTrue(viewModel.state.value.submitted)
        assertFalse(viewModel.state.value.busy)
        assertEquals(quote, viewModel.state.value.quote)
        assertEquals(R.string.usdt__error_backup, viewModel.state.value.error)
        viewModel.waitForTransfer()
        assertFalse(viewModel.state.value.busy)
        viewModel.refresh()
        assertEquals(R.string.usdt__error_backup, viewModel.state.value.error)
        whenever(repo.retryTransfer(quote.id)).thenReturn(Result.failure(UsdtException.BackupUnavailable()))
        viewModel.retryRecovery().join()
        assertEquals(R.string.usdt__error_backup, viewModel.state.value.error)
        assertTrue(viewModel.state.value.canRetryRecovery)
        whenever(repo.retryTransfer(quote.id)).thenReturn(Result.failure(UsdtException.RateLimited()))
        viewModel.retryRecovery().join()
        assertEquals(R.string.usdt__error_rate_limit, viewModel.state.value.error)
        assertTrue(viewModel.state.value.canRetryRecovery)
        whenever(repo.retryTransfer(quote.id)).thenReturn(Result.success(Unit))
        viewModel.retryRecovery().join()
        assertEquals(R.string.usdt__error_rate_limit, viewModel.state.value.error)
        assertTrue(viewModel.state.value.canRetryRecovery)
        viewModel.refresh()
        assertTrue(viewModel.state.value.canRetryRecovery)
        recoveryGeneration++
        viewModel.retryRecovery().join()
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.canRetryRecovery)
        assertEquals(quote, viewModel.state.value.quote)
        viewModel.confirm().join()
        verify(repo, times(1)).quote(any(), any(), any(), any())
        verify(repo, times(4)).retryTransfer(quote.id)
        verify(repo, times(1)).send(eq(quote), anyOrNull(), any())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `closing during recovery retry keeps its failure out of a new send form`() = test {
        val retained: UsdtTransfer = mock()
        whenever(retained.id).thenReturn(quote.id)
        whenever(retained.status).thenReturn(UsdtTransferStatus.PENDING)
        val viewModel = createViewModel(walletState = UsdtWalletState(transfers = persistentListOf(retained)))
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        whenever(repo.send(eq(quote), anyOrNull(), any()))
            .thenReturn(Result.failure(UsdtException.BackupUnavailable()))
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        val completion = CompletableDeferred<Result<Unit>>()
        whenever(repo.retryTransfer(quote.id)).doSuspendableAnswer { completion.await() }

        val retry = viewModel.retryRecovery()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.retryingRecovery)
        viewModel.edit()
        val newForm = viewModel.state.value
        completion.complete(Result.failure(UsdtException.BackupUnavailable()))
        retry.join()

        assertEquals(newForm, viewModel.state.value)
        verify(repo, times(1)).retryTransfer(quote.id)
        verify(repo, times(1)).send(eq(quote), anyOrNull(), any())
    }

    @Test
    fun `recovery by another observer clears the latest retry failure without a new payment`() = test {
        val retained: UsdtTransfer = mock()
        whenever(retained.id).thenReturn(quote.id)
        whenever(retained.status).thenReturn(UsdtTransferStatus.PENDING)
        val viewModel = createViewModel(walletState = UsdtWalletState(transfers = persistentListOf(retained)))
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        whenever(repo.send(eq(quote), anyOrNull(), any()))
            .thenReturn(Result.failure(UsdtException.BackupUnavailable()))
        viewModel.quote(quote.recipient, "1", quote.destination).join()
        viewModel.confirm().join()
        assertEquals(R.string.usdt__error_backup, viewModel.state.value.error)
        whenever(repo.retryTransfer(quote.id)).thenReturn(Result.failure(UsdtException.RateLimited()))
        viewModel.retryRecovery().join()
        assertEquals(R.string.usdt__error_rate_limit, viewModel.state.value.error)
        assertTrue(viewModel.state.value.canRetryRecovery)

        recoveryGeneration++
        viewModel.refresh()

        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.canRetryRecovery)
        assertEquals(quote, viewModel.state.value.quote)
        viewModel.confirm().join()
        verify(repo, times(1)).send(eq(quote), anyOrNull(), any())
        verify(repo, times(1)).retryTransfer(quote.id)
    }

    @Test
    fun `submission feedback follows the retained status and disappears on confirmation`() {
        val state = UsdtSendState(
            error = R.string.usdt__error_backup,
            submitted = true,
            submissionErrorStatus = UsdtTransferStatus.PENDING,
        )
        assertEquals(R.string.usdt__error_backup, state.submissionError(UsdtTransferStatus.PENDING))
        assertNull(state.submissionError(UsdtTransferStatus.CONFIRMED))
        assertNull(state.submissionError(UsdtTransferStatus.FAILED))
        assertNull(state.submissionError(null))
    }

    @Test
    fun `deducted bridge costs require the high fee warning`() = test {
        val model = createViewModel(walletState = UsdtWalletState(balance = 100_000_000u))
        val bridge = quote.copy(amount = 5_000_000u, receivedAmount = 2_000_000u, maximumFee = 100u)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(bridge))
        model.quote(bridge.recipient, "5", bridge.destination).join()
        model.confirm().join()
        assertEquals(SanityWarning.FEE_OVER_HALF_VALUE, model.state.value.warning)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `sending over half the balance shows the usdt balance warning`() = test {
        val model = createViewModel(walletState = UsdtWalletState(balance = 1_500_000u))
        model.quote(quote.recipient, "1", quote.destination).join()
        model.confirm().join()
        assertEquals(SanityWarning.OVER_HALF_USDT_BALANCE, model.state.value.warning)
        verify(repo, never()).send(any(), anyOrNull(), any())
    }

    @Test
    fun `recorded payment that exceeds its deadline retains recovery with an expiry error`() = test {
        val retained: UsdtTransfer = mock {
            on { id }.thenReturn(quote.id)
            on { status }.thenReturn(UsdtTransferStatus.PENDING)
        }
        val model = createViewModel(walletState = UsdtWalletState(transfers = persistentListOf(retained)))
        settings.value = settings.value.copy(isPinForPaymentsEnabled = false)
        whenever(repo.send(eq(quote), anyOrNull(), any()))
            .thenReturn(Result.failure(UsdtException.SubmissionDeadlineExpired()))
        model.quote(quote.recipient, "1", quote.destination).join()
        model.confirm().join()
        assertTrue(model.state.value.submitted)
        assertEquals(quote, model.state.value.quote)
        assertEquals(R.string.wallet__payment_request_expired, model.state.value.error)
        assertEquals(
            R.string.wallet__payment_request_expired,
            model.state.value.submissionError(UsdtTransferStatus.PENDING)
        )
        assertEquals(
            R.string.wallet__payment_request_expired,
            model.state.value.submissionError(UsdtTransferStatus.FAILED)
        )
        for (status in UsdtTransferStatus.entries.filterNot {
            it == UsdtTransferStatus.PENDING || it == UsdtTransferStatus.FAILED
        }) {
            assertNull(model.state.value.submissionError(status))
        }
        assertNull(model.state.value.submissionError(null))
        model.confirm().join()
        verify(repo, times(1)).send(eq(quote), anyOrNull(), any())
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
        verify(repo, never()).send(any(), anyOrNull(), any())
        viewModel.acceptWarning()
        assertNull(viewModel.state.value.warning)
        assertTrue(viewModel.state.value.authenticationRequired)
        viewModel.edit()
        assertTrue(viewModel.state.value.confirmedWarnings.isEmpty())
    }

    @Test
    fun `editing while settings load cannot authorize a replacement quote`() = test {
        val viewModel = createViewModel()
        val delayed = MutableSharedFlow<SettingsData>()
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
        verify(repo, never()).send(any(), anyOrNull(), any())
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
        verify(repo, never()).send(any(), anyOrNull(), any())

        failing = false
        viewModel.confirm().join()
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.authenticationRequired)
        verify(repo, never()).send(any(), anyOrNull(), any())
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

    private suspend fun createViewModel(
        settingsFlow: Flow<SettingsData> = settings,
        walletState: UsdtWalletState = UsdtWalletState(),
    ): UsdtViewModel {
        this.walletState.value = walletState
        whenever(repo.state).thenReturn(this.walletState)
        whenever(repo.recoveryGeneration).thenAnswer { recoveryGeneration }
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(repo.quote(any(), any(), any(), any())).thenReturn(Result.success(quote))
        whenever(repo.send(eq(quote), anyOrNull(), any())).thenReturn(Result.success(mock<UsdtTransfer>()))
        whenever(repo.refresh(false)).thenReturn(Result.success(Unit))
        whenever(repo.waitForTransfer(quote.id)).thenReturn(Result.success(Unit))
        return UsdtViewModel(repo, settingsStore)
    }
}
