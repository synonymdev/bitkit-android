package to.bitkit.viewmodels

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.QuickPayConversionError
import to.bitkit.repositories.QuickPayPayRequest
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.repositories.QuickPaySession
import to.bitkit.repositories.QuickPaySessionEvent
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickPayViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val lightningRepo: LightningRepo = mock()
    private val quickPayRepo: QuickPayRepo = mock()
    private val events = MutableSharedFlow<QuickPaySessionEvent>(extraBufferCapacity = 8)

    private lateinit var sut: QuickPayViewModel

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("error")
        whenever(context.getString(R.string.wallet__send_quickpay__currency_conversion)).thenReturn("conversion")
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)),
        )
        whenever(quickPayRepo.attach(any())).thenReturn(events)
        sut = QuickPayViewModel(
            context = context,
            lightningRepo = lightningRepo,
            quickPayRepo = quickPayRepo,
        )
    }

    @Test
    fun `success event maps to ui success`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        events.emit(QuickPaySessionEvent.Success(paymentHash = "hash1", amountWithFee = 501L))
        advanceUntilIdle()

        val success = assertIs<QuickPayResult.Success>(sut.uiState.value.result)
        assertEquals("hash1", success.paymentHash)
        assertEquals(501L, success.amountWithFee)
    }

    @Test
    fun `pending event maps to ui pending`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        events.emit(
            QuickPaySessionEvent.Pending(
                paymentHash = "hash1",
                amount = 500L,
                paymentRequest = "lnbcrt1test",
            ),
        )
        advanceUntilIdle()

        val pending = assertIs<QuickPayResult.Pending>(sut.uiState.value.result)
        assertEquals("hash1", pending.paymentHash)
    }

    @Test
    fun `pay forwards to repo`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        val data = QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test")

        sut.pay(session, data)

        verify(quickPayRepo).pay(
            session,
            QuickPayPayRequest.Bolt11(bolt11 = "lnbcrt1test", amountSats = 500u),
        )
        verify(quickPayRepo, never()).noteTerminal(any(), any(), any(), any(), any())
    }

    @Test
    fun `pay ignores re-entry after a result`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        events.emit(QuickPaySessionEvent.FallBackToConfirm)
        advanceUntilIdle()

        sut.pay(session, QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        sut.pay(session, QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))

        verify(quickPayRepo, never()).pay(any(), any())
    }

    @Test
    fun `conversion failure uses currency conversion message`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        events.emit(QuickPaySessionEvent.Error(QuickPayConversionError(), null))
        advanceUntilIdle()

        val error = assertIs<QuickPayResult.Error>(sut.uiState.value.result)
        assertEquals("conversion", error.failure.message)
    }

    @Test
    fun `stale detach does not detach a newer session`() = test {
        val old = QuickPaySession()
        val next = QuickPaySession()
        sut.attach(old)
        sut.attach(next)
        sut.detach(old)

        verify(quickPayRepo, times(1)).detach(old)
        verify(quickPayRepo, never()).detach(next)
    }

    @Test
    fun `viewmodel has no settlement methods on the repo besides noteTerminal from events`() = test {
        val session = QuickPaySession()
        sut.attach(session)
        sut.pay(session, QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        verify(quickPayRepo, never()).noteTerminal(any(), any(), any(), any(), any())
    }
}
