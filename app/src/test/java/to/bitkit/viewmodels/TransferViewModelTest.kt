package to.bitkit.viewmodels

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwapEvent
import com.synonym.bitkitcore.ReverseSwapResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.BalanceState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.BoltzService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.idleArkadeRepo
import to.bitkit.utils.AppError
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class TransferViewModelTest : BaseUnitTest() {
    private lateinit var sut: TransferViewModel

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val transferRepo = mock<TransferRepo>()
    private val clock = mock<Clock>()
    private val boltzService = mock<BoltzService>()
    private val arkadeRepo = idleArkadeRepo()

    private val balanceState = MutableStateFlow(BalanceState())
    private val boltzEvents = MutableSharedFlow<BoltzSwapEvent>(extraBufferCapacity = 8)
    private val nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 8)

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("")
        val nodeStatus = mock<NodeStatus>()
        whenever(nodeStatus.isRunning).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(nodeStatus = nodeStatus)))
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(boltzService.events).thenReturn(boltzEvents)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever(boltzService.isSwapSupported).thenReturn(true)
        whenever { boltzService.isSwapEnabled() }.thenReturn(true)

        sut = TransferViewModel(
            context = context,
            lightningRepo = lightningRepo,
            walletRepo = walletRepo,
            transferRepo = transferRepo,
            boltzService = boltzService,
            clock = clock,
            arkadeRepo = arkadeRepo,
        )
    }

    @Test
    fun `transfer to savings with only arkade doesn't wait for a lightning peer`() = test {
        sut.onTransferToSavingsConfirm(channels = emptyList(), mode = SavingsTransferMode.CLOSE)

        assertFalse(sut.hasChannelsToClose())
        assertTrue(sut.closeSelectedChannels().isEmpty())
        verify(lightningRepo, never()).awaitPeerConnected(any())
    }

    @Test
    fun `loadSavingsSwapQuote defaults to max transferable within limits and spendable balance`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        val state = sut.savingsSwapState.value
        val expectedMax = SPENDABLE_LN - SPENDABLE_LN / 100uL // 1% routing fee reserve
        assertEquals(SWAP_MIN, state.minSat)
        assertEquals(expectedMax, state.maxSat)
        val quote = assertNotNull(state.quote)
        assertEquals(expectedMax, quote.amountSat)
        assertEquals(SWAP_MINER_FEE, quote.networkFeeSat)
        val expectedSwapFee = (expectedMax.toLong() * SWAP_FEE_PERCENT / 100.0).roundToLong().toULong()
        assertEquals(expectedSwapFee, quote.swapFeeSat)
        assertEquals(expectedMax - expectedSwapFee - SWAP_MINER_FEE, quote.receiveSat)
    }

    @Test
    fun `loadSavingsSwapQuote falls back to close when below the swap minimum`() = test {
        whenever(context.getString(R.string.lightning__savings_confirm__amount_too_low)).thenReturn(TOO_LOW)
        balanceState.value = BalanceState(maxSendLightningSats = SWAP_MIN - 1uL)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        val state = sut.savingsSwapState.value
        assertNull(state.quote)
        assertEquals(0uL, state.maxSat)

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)

        sut.startSavingsSwap()
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(TOO_LOW), sut.savingsSwapResult.value)
    }

    @Test
    fun `loadSavingsSwapQuote falls back to close when the limits fetch fails`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenAnswer { throw AppError(BOLTZ_ERROR) }

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        assertNull(sut.savingsSwapState.value.quote)
        assertFalse(sut.savingsSwapState.value.isLoading)

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `loadSavingsSwapQuote skips the network when swaps are unsupported`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.isSwapSupported).thenReturn(false)

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        assertEquals(SavingsSwapUiState(), sut.savingsSwapState.value)
        verify(boltzService, never()).reverseLimits(anyOrNull())

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `loadSavingsSwapQuote skips the network when swaps are disabled in dev settings`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.isSwapEnabled()).thenReturn(false)

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        assertEquals(SavingsSwapUiState(), sut.savingsSwapState.value)
        verify(boltzService, never()).reverseLimits(anyOrNull())

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `onTransferToSavingsConfirm swaps when a quote is ready and closes when the user opts out`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.SWAP, sut.savingsTransferMode.value)

        sut.onTransferToSavingsConfirm(emptyList(), SavingsTransferMode.CLOSE)
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `onTransferToSavingsConfirm clears the outcome of an earlier swap`() = test {
        stubSavingsSwapHappyPath()
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.failure(AppError(PAY_ERROR)))
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()
        sut.startSavingsSwap()
        advanceUntilIdle()
        assertEquals(SavingsSwapResult.Failure(PAY_ERROR), sut.savingsSwapResult.value)

        sut.onTransferToSavingsConfirm(emptyList())

        assertNull(sut.savingsSwapResult.value)
    }

    @Test
    fun `onSwapAmountChange clamps to the allowed range and reprices`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.onSwapAmountChange(SWAP_MIN - 10_000uL)

        assertEquals(SWAP_MIN, sut.savingsSwapState.value.quote?.amountSat)
    }

    @Test
    fun `startSavingsSwap succeeds when the claim event arrives`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = TXID))
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap fails when the swap reports an error event`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Error(swapId = SWAP_ID, message = BOLTZ_ERROR))
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(BOLTZ_ERROR), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap returns pending when the claim does not arrive in time`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Pending, sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap fails when the invoice payment fails`() = test {
        stubSavingsSwapHappyPath()
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.failure(AppError(PAY_ERROR)))
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(PAY_ERROR), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap ignores a second start while a swap is in flight`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = TXID))
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)
        verify(boltzService).createReverseSwap(any(), any(), anyOrNull(), anyOrNull())
        verify(lightningRepo).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `startSavingsSwap fails when the paid invoice reports a lightning routing failure`() = test {
        stubSavingsSwapHappyPath()
        whenever(context.getString(R.string.wallet__payment_route_not_found))
            .thenReturn(ROUTE_NOT_FOUND_MSG)
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        // payInvoice returns as soon as the HTLC is dispatched; the routing failure lands later.
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = PAYMENT_ID,
                paymentHash = PAYMENT_ID,
                reason = PaymentFailureReason.ROUTE_NOT_FOUND,
            ),
        )
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(ROUTE_NOT_FOUND_MSG), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap ignores a restart once an outcome is already delivered`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = TXID))
        advanceUntilIdle()
        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)

        // A configuration change re-fires startSavingsSwap; the delivered outcome must block a rerun.
        sut.startSavingsSwap()
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)
        verify(boltzService).createReverseSwap(any(), any(), anyOrNull(), anyOrNull())
        verify(lightningRepo).payInvoice(any(), anyOrNull())
    }

    private suspend fun stubSavingsSwapHappyPath() {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(CLAIM_ADDRESS))
        whenever(boltzService.createReverseSwap(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(reverseSwapResponse())
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.success(PAYMENT_ID))
    }

    private fun reverseLimits() = BoltzPairInfo(
        hash = "hash",
        rate = 1.0,
        minimalSat = SWAP_MIN,
        maximalSat = SWAP_MAX,
        feePercentage = SWAP_FEE_PERCENT,
        minerFeesSat = SWAP_MINER_FEE,
    )

    private fun reverseSwapResponse() = ReverseSwapResponse(
        id = SWAP_ID,
        invoice = "lnbc1invoice",
        lockupAddress = "bcrt1qlockup",
        onchainAmountSat = SWAP_MIN,
        timeoutBlockHeight = 800uL,
    )

    private companion object {
        const val TXID = "tx-abc"
        const val SPENDABLE_LN = 150_000uL
        const val REQUESTED_SAT = 200_000uL
        const val SWAP_MIN = 25_000uL
        const val SWAP_MAX = 1_000_000uL
        const val SWAP_FEE_PERCENT = 0.5
        const val SWAP_MINER_FEE = 300uL
        const val SWAP_ID = "swap1"
        const val CLAIM_ADDRESS = "bcrt1qclaim"
        const val TOO_LOW = "Amount is too low"
        const val PAY_ERROR = "no route found"
        const val BOLTZ_ERROR = "boltz unavailable"
        const val PAYMENT_ID = "paymentId"
        const val ROUTE_NOT_FOUND_MSG = "No route to destination"
    }
}
