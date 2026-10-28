package to.bitkit.ui.screens.wallets.receive

import android.content.Context
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PeerDetails
import org.lightningdevkit.ldknode.PrimaryReceiveQuote
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.TxOutput
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PrimaryReceiveViewModelTest : BaseUnitTest() {
    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val walletRepo = mock<WalletRepo>()
    private val walletState = MutableStateFlow(WalletState(onchainAddress = "address"))
    private val nodeEvents = MutableSharedFlow<Event>()
    private val lightningState = MutableStateFlow(
        LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            peers = persistentListOf(PeerDetails("primary-a", "host:9735", true, true)),
        )
    )
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochSeconds(1_000)
    }
    private val settings = MutableStateFlow(SettingsData(primaryNodeUri = "primary-a"))
    private lateinit var sut: PrimaryReceiveViewModel

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("Receive error")
        whenever(settingsStore.data).thenReturn(settings)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        sut = PrimaryReceiveViewModel(context, lightningRepo, settingsStore, walletRepo, clock)
    }

    @Test
    fun `quote does not create an invoice until approved and approval binds amount and fee`() = test {
        whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.success(quote()))
        whenever(lightningRepo.createPrimaryInvoice(10_000uL, "Lunch", 125_500uL))
            .thenReturn(Result.success("invoice"))
        whenever(lightningRepo.decodePrimaryReceiveInvoice("invoice")).thenReturn(Result.success(invoice()))
        var quoted = false
        var created: ULong? = null

        sut.requestQuote(10_000uL, "Lunch") { quoted = true }
        advanceUntilIdle()

        assertTrue(quoted)
        assertNull(sut.uiState.value.invoice)
        verify(lightningRepo, never()).createPrimaryInvoice(any(), any(), any())
        sut.confirm { created = it }
        runCurrent()

        assertEquals(10_000uL, created)
        assertEquals("invoice", sut.uiState.value.invoice)
        verify(lightningRepo).createPrimaryInvoice(10_000uL, "Lunch", 125_500uL)
    }

    @Test
    fun `changing primary invalidates approval before invoice creation`() = test {
        whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.success(quote()))
        sut.requestQuote(10_000uL, "") {}
        advanceUntilIdle()
        settings.value = settings.value.copy(primaryNodeUri = "primary-b")

        sut.confirm {}
        advanceUntilIdle()

        assertNotNull(sut.uiState.value.error)
        assertNull(sut.uiState.value.invoice)
        verify(lightningRepo, never()).createPrimaryInvoice(any(), any(), any())
    }

    @Test
    fun `declined quotes explain the reason without creating an invoice or changing the Bitcoin address`() = test {
        val cases = listOf(
            NodeException.LiquiditySourceInsufficientFunds("provider text") to
                R.string.wallet_primary__receive_insufficient_funds,
            NodeException.LiquidityRequestRejected("provider text") to R.string.wallet_primary__receive_rejected,
            NodeException.LiquidityRequestTimedOut("provider text") to R.string.wallet_primary__receive_timed_out,
            NodeException.LiquidityReceiveAmountTooSmall("provider text") to
                R.string.wallet_primary__receive_amount_too_small,
        )
        cases.forEach { (error, resource) ->
            val localized = "Localized receive message $resource"
            whenever(context.getString(resource)).thenReturn(localized)
            whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.failure(AppError(LdkError(error))))
            var quoted = false

            sut.requestQuote(10_000uL, "") { quoted = true }
            advanceUntilIdle()
            sut.confirm {}
            advanceUntilIdle()

            assertFalse(quoted)
            assertFalse(sut.uiState.value.isLoading)
            assertEquals(localized, sut.uiState.value.error)
            assertNull(sut.uiState.value.invoice)
            assertEquals("address", walletState.value.onchainAddress)
        }
        verify(lightningRepo, never()).createPrimaryInvoice(any(), any(), any())
    }

    @Test
    fun `quote retry clears funding error and requires explicit invoice approval`() = test {
        whenever(context.getString(R.string.wallet_primary__receive_insufficient_funds))
            .thenReturn("Primary needs funds")
        whenever(lightningRepo.quotePrimaryReceive(10_000uL))
            .thenReturn(Result.failure(NodeException.LiquiditySourceInsufficientFunds("provider text")))
        sut.requestQuote(10_000uL, "") {}
        advanceUntilIdle()
        assertEquals("Primary needs funds", sut.uiState.value.error)

        whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.success(quote()))
        var quoted = false
        sut.requestQuote(10_000uL, "") { quoted = true }
        advanceUntilIdle()

        assertTrue(quoted)
        assertNull(sut.uiState.value.error)
        assertNotNull(sut.uiState.value.offer)
        assertNull(sut.uiState.value.invoice)
        verify(lightningRepo, never()).createPrimaryInvoice(any(), any(), any())
    }

    @Test
    fun `mismatched and nonpositive net quotes cannot be approved`() = test {
        for (invalidQuote in listOf(quote().copy(amountMsat = 1uL), quote().copy(feeMsat = 10_000_000uL))) {
            whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.success(invalidQuote))
            var quoted = false

            sut.requestQuote(10_000uL, "") { quoted = true }
            advanceUntilIdle()
            sut.confirm {}
            advanceUntilIdle()

            assertFalse(quoted)
            assertNull(sut.uiState.value.offer)
            assertEquals("Receive error", sut.uiState.value.error)
        }
        verify(lightningRepo, never()).createPrimaryInvoice(any(), any(), any())
    }

    @Test
    fun `clearing a pending quote prevents its result reopening confirmation`() = test {
        val response = CompletableDeferred<Result<PrimaryReceiveQuote>>()
        whenever(lightningRepo.quotePrimaryReceive(10_000uL)).doSuspendableAnswer { response.await() }
        var quoted = false
        sut.requestQuote(10_000uL, "") { quoted = true }
        runCurrent()

        sut.clear()
        response.complete(Result.success(quote()))
        advanceUntilIdle()

        assertFalse(quoted)
        assertEquals(PrimaryReceiveUiState(), sut.uiState.value)
    }

    @Test
    fun `failed invoice creation requires a refreshed quote before another approval`() = test {
        whenever(context.getString(R.string.wallet_primary__receive_insufficient_funds))
            .thenReturn("Primary needs funds")
        whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.success(quote()))
        whenever(lightningRepo.createPrimaryInvoice(10_000uL, "", 125_500uL))
            .thenReturn(Result.failure(AppError(NodeException.LiquiditySourceInsufficientFunds("provider text"))))
        sut.requestQuote(10_000uL, "") {}
        advanceUntilIdle()
        sut.confirm {}
        advanceUntilIdle()

        assertEquals("Primary needs funds", sut.uiState.value.error)
        assertNull(sut.uiState.value.invoice)
        sut.confirm {}
        advanceUntilIdle()
        verify(lightningRepo, times(1)).createPrimaryInvoice(any(), any(), any())

        whenever(lightningRepo.quotePrimaryReceive(10_000uL))
            .thenReturn(Result.success(quote().copy(feeMsat = 250_000uL)))
        sut.refreshQuote()
        advanceUntilIdle()
        assertNull(sut.uiState.value.error)
        assertEquals(250_000uL, sut.uiState.value.offer?.maxFeeMsat)
        verify(lightningRepo, times(2)).quotePrimaryReceive(10_000uL)

        whenever(lightningRepo.createPrimaryInvoice(10_000uL, "", 250_000uL))
            .thenReturn(Result.success("invoice"))
        whenever(lightningRepo.decodePrimaryReceiveInvoice("invoice")).thenReturn(Result.success(invoice()))
        var created = false
        sut.confirm { created = true }
        runCurrent()

        assertTrue(created)
        assertEquals("invoice", sut.uiState.value.invoice)
        verify(lightningRepo).createPrimaryInvoice(10_000uL, "", 250_000uL)
    }

    @Test
    fun `invoice expires without requesting or authorizing a replacement`() = test {
        createInvoice()
        assertEquals("invoice", sut.uiState.value.invoice)
        advanceTimeBy(600_000)
        runCurrent()
        assertNull(sut.uiState.value.invoice)
        assertNull(sut.uiState.value.offer)
        verify(lightningRepo, times(1)).createPrimaryInvoice(any(), any(), any())
    }

    @Test
    fun `matching payment clears the invoice but unrelated payment preserves it`() = test {
        createInvoice()
        nodeEvents.emit(Event.PaymentReceived("other", "other", 10_000_000uL, emptyList()))
        assertEquals("invoice", sut.uiState.value.invoice)
        nodeEvents.emit(Event.PaymentReceived("0102", "0102", 10_000_000uL, emptyList()))
        assertNull(sut.uiState.value.invoice)
    }

    @Test
    fun `matching onchain fallback payment clears the merged request`() = test {
        createInvoice()
        val output = mock<TxOutput>()
        whenever(output.scriptpubkeyAddress).thenReturn("address")
        val details = mock<TransactionDetails>()
        whenever(details.outputs).thenReturn(listOf(output))
        nodeEvents.emit(Event.OnchainTransactionReceived("txid", details))
        assertNull(sut.uiState.value.invoice)
    }

    @Test
    fun `provider change and disconnection invalidate active requests`() = test {
        createInvoice()
        settings.value = settings.value.copy(primaryNodeUri = "primary-b")
        assertNull(sut.uiState.value.invoice)
        settings.value = settings.value.copy(primaryNodeUri = "primary-a")
        createInvoice()
        lightningState.value = lightningState.value.copy(peers = persistentListOf())
        assertNull(sut.uiState.value.invoice)
    }

    @Test
    fun `changed fallback address invalidates active request`() = test {
        createInvoice()
        walletState.value = walletState.value.copy(onchainAddress = "replacement")
        assertNull(sut.uiState.value.invoice)
    }

    private suspend fun createInvoice() {
        whenever(lightningRepo.quotePrimaryReceive(10_000uL)).thenReturn(Result.success(quote()))
        whenever(lightningRepo.createPrimaryInvoice(10_000uL, "", 125_500uL))
            .thenReturn(Result.success("invoice"))
        whenever(lightningRepo.decodePrimaryReceiveInvoice("invoice")).thenReturn(Result.success(invoice()))
        sut.requestQuote(10_000uL, "") {}
        sut.confirm {}
    }

    private fun invoice() = LightningInvoice(
        bolt11 = "invoice",
        paymentHash = byteArrayOf(1, 2),
        amountSatoshis = 10_000uL,
        timestampSeconds = 1_000uL,
        expirySeconds = 600uL,
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )

    @Test
    fun `fees and minimum received preserve fractional satoshis`() {
        val offer = PrimaryReceiveOffer(10_000uL, 125_500uL, "", "primary")
        assertEquals("125.5", formatPrimaryReceiveMsat(offer.maxFeeMsat, Locale.US))
        assertEquals("9,874.5", formatPrimaryReceiveMsat(offer.minimumReceiveMsat, Locale.US))
        assertEquals("125,5", formatPrimaryReceiveMsat(offer.maxFeeMsat, Locale.GERMANY))
    }

    private fun quote() = PrimaryReceiveQuote(amountMsat = 10_000_000uL, feeMsat = 125_500uL, fundingSats = 20_000uL)
}
