package to.bitkit.viewmodels

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import to.bitkit.data.QuickPaySpendReservation
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.QuickPayConversionError
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickPayViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val lightningRepo: LightningRepo = mock()
    private val pendingPaymentRepo: PendingPaymentRepo = mock()
    private val quickPayRepo: QuickPayRepo = mock()

    private lateinit var nodeEvents: MutableSharedFlow<Event>
    private val reserved = QuickPaySpendReservation(amountCents = 250L, dayKey = "2026-08-15")

    private lateinit var sut: QuickPayViewModel

    @Before
    fun setUp() {
        nodeEvents = MutableSharedFlow(replay = 0, extraBufferCapacity = 0)
        whenever(context.getString(any())).thenReturn("error")
        whenever(context.getString(R.string.wallet__send_quickpay__currency_conversion)).thenReturn("conversion")
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)),
        )
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever { quickPayRepo.tryReserve(any()) }.thenReturn(Result.success(reserved))
        whenever { quickPayRepo.remember(any(), any()) }.thenReturn(Result.success(Unit))
        whenever { quickPayRepo.clear(any()) }.thenReturn(Result.success(Unit))
        whenever { quickPayRepo.release(any()) }.thenReturn(Result.success(Unit))
        whenever { quickPayRepo.releaseUnbound(any()) }.thenReturn(Result.success(Unit))
        sut = QuickPayViewModel(
            context = context,
            lightningRepo = lightningRepo,
            pendingPaymentRepo = pendingPaymentRepo,
            quickPayRepo = quickPayRepo,
        )
    }

    @Test
    fun `happy path reserves before payInvoice and clears reservation on success`() = test {
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.thenReturn(Result.success("hash1"))

        launch { sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test")) }
        nodeEvents.emit(
            Event.PaymentSuccessful(
                paymentId = "pid",
                paymentHash = "hash1",
                paymentPreimage = "preimage",
                feePaidMsat = 1_000uL,
            ),
        )
        advanceUntilIdle()

        val order = inOrder(quickPayRepo, lightningRepo)
        order.verify(quickPayRepo).tryReserve(500u)
        order.verify(lightningRepo).payInvoice(bolt11 = "lnbcrt1test", sats = null)
        order.verify(quickPayRepo).remember("hash1", reserved)
        order.verify(quickPayRepo).clear("hash1")
        verify(pendingPaymentRepo, never()).track(any())
        val success = assertIs<QuickPayResult.Success>(sut.uiState.value.result)
        assertEquals("hash1", success.paymentHash)
        assertEquals(501L, success.amountWithFee)
    }

    @Test
    fun `timeout remembers reservation before tracking pending`() = test {
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.thenReturn(Result.success("hash1"))

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()
        advanceTimeBy(LightningRepo.SEND_LN_TIMEOUT.inWholeMilliseconds + 1)
        advanceUntilIdle()

        val order = inOrder(quickPayRepo, pendingPaymentRepo)
        order.verify(quickPayRepo).remember("hash1", reserved)
        order.verify(pendingPaymentRepo).track("hash1")
        val pending = assertIs<QuickPayResult.Pending>(sut.uiState.value.result)
        assertEquals("hash1", pending.paymentHash)
    }

    @Test
    fun `immediate payInvoice failure releases spend without remembering`() = test {
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }
            .thenReturn(Result.failure(IllegalStateException("send failed")))

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        verify(quickPayRepo).releaseUnbound(reserved)
        verify(quickPayRepo, never()).remember(any(), any())
        verify(pendingPaymentRepo, never()).track(any())
        assertIs<QuickPayResult.Error>(sut.uiState.value.result)
    }

    @Test
    fun `payment failed after submit releases hash keyed reservation`() = test {
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.thenReturn(Result.success("hash1"))

        launch { sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test")) }
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = "pid",
                paymentHash = "hash1",
                reason = PaymentFailureReason.ROUTE_NOT_FOUND,
            ),
        )
        advanceUntilIdle()

        verify(quickPayRepo).remember("hash1", reserved)
        verify(quickPayRepo).release("hash1")
        assertIs<QuickPayResult.Error>(sut.uiState.value.result)
    }

    @Test
    fun `reserve failure emits FallBackToConfirm`() = test {
        whenever { quickPayRepo.tryReserve(any()) }.thenReturn(Result.success(null))

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        assertEquals(QuickPayResult.FallBackToConfirm, sut.uiState.value.result)
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
        assertNull(sut.uiState.value.result.takeIf { it is QuickPayResult.Error })
    }

    @Test
    fun `fast fail during remember is collected and released`() = test {
        val allowRemember = CompletableDeferred<Unit>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.thenReturn(Result.success("hash1"))
        whenever { quickPayRepo.remember(any(), any()) }.doSuspendableAnswer {
            allowRemember.await()
            Result.success(Unit)
        }

        launch { sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test")) }
        runCurrent()
        assertTrue(nodeEvents.subscriptionCount.value > 0)
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = "pid",
                paymentHash = "hash1",
                reason = PaymentFailureReason.ROUTE_NOT_FOUND,
            ),
        )
        allowRemember.complete(Unit)
        advanceUntilIdle()

        verify(quickPayRepo).release("hash1")
        verify(pendingPaymentRepo, never()).track(any())
        assertIs<QuickPayResult.Error>(sut.uiState.value.result)
    }

    @Test
    fun `pay ignores re-entry while in flight`() = test {
        val allowPay = CompletableDeferred<Unit>()
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.doSuspendableAnswer {
            allowPay.await()
            Result.success("hash1")
        }
        val data = QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test")

        sut.pay(data)
        sut.pay(data)
        verify(quickPayRepo, times(1)).tryReserve(any())
        verify(lightningRepo, times(1)).payInvoice(any(), anyOrNull())

        allowPay.complete(Unit)
        nodeEvents.emit(
            Event.PaymentSuccessful(
                paymentId = "pid",
                paymentHash = "hash1",
                paymentPreimage = "preimage",
                feePaidMsat = 1_000uL,
            ),
        )
        advanceUntilIdle()
    }

    @Test
    fun `conversion failure uses currency conversion message`() = test {
        whenever { quickPayRepo.tryReserve(any()) }.thenReturn(Result.failure(QuickPayConversionError()))

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        val error = assertIs<QuickPayResult.Error>(sut.uiState.value.result)
        assertEquals("conversion", error.failure.message)
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `non conversion reserve failure is not the currency string`() = test {
        whenever { quickPayRepo.tryReserve(any()) }.thenReturn(Result.failure(IllegalStateException("disk")))

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        val error = assertIs<QuickPayResult.Error>(sut.uiState.value.result)
        assertEquals("disk", error.failure.message)
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
    }
}
