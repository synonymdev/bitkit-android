package to.bitkit.viewmodels

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.quickPaySpendDayKey
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickPayViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val lightningRepo: LightningRepo = mock()
    private val pendingPaymentRepo: PendingPaymentRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val cacheStore: CacheStore = mock()
    private val settingsStore: SettingsStore = mock()

    private lateinit var nodeEvents: MutableSharedFlow<Event>
    private val settingsData = MutableStateFlow(
        SettingsData(isQuickPayEnabled = true, quickPayAmount = 5, quickPayDailyLimitMultiplier = 5),
    )

    private lateinit var sut: QuickPayViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        nodeEvents = MutableSharedFlow(replay = 1, extraBufferCapacity = 8)
        whenever(context.getString(any())).thenReturn("error")
        whenever(context.getString(R.string.wallet__send_quickpay__currency_conversion)).thenReturn("conversion")
        whenever(lightningRepo.lightningState).thenReturn(
            MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running)),
        )
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer { invocation ->
            val sats = invocation.getArgument<Long>(0)
            val usd = 5.0 * sats.toDouble() / 1000.0
            ConvertedAmount(
                value = BigDecimal.valueOf(usd),
                formatted = usd.toString(),
                symbol = "$",
                currency = "USD",
                flag = "",
                sats = sats,
                locale = Locale.US,
            )
        }
        whenever { cacheStore.tryReserveQuickPaySpendCents(any(), any(), any()) }.thenReturn(true)
        whenever { cacheStore.rememberQuickPayReservation(any(), any(), any()) }.thenReturn(Unit)
        whenever { cacheStore.clearQuickPayReservation(any()) }.thenReturn(Unit)
        whenever { cacheStore.releaseQuickPayReservation(any()) }.thenReturn(Unit)
        whenever { cacheStore.releaseQuickPaySpendCents(any(), any()) }.thenReturn(Unit)
        sut = QuickPayViewModel(
            context = context,
            lightningRepo = lightningRepo,
            pendingPaymentRepo = pendingPaymentRepo,
            currencyRepo = currencyRepo,
            cacheStore = cacheStore,
            settingsStore = settingsStore,
        )
    }

    @Test
    fun `happy path reserves before payInvoice and clears reservation on success`() = test {
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.thenReturn(Result.success("hash1"))
        nodeEvents.emit(
            Event.PaymentSuccessful(
                paymentId = "pid",
                paymentHash = "hash1",
                paymentPreimage = "preimage",
                feePaidMsat = 1_000uL,
            ),
        )

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        val order = inOrder(cacheStore, lightningRepo)
        order.verify(cacheStore).tryReserveQuickPaySpendCents(
            amountCents = 250L,
            dayKey = quickPaySpendDayKey(),
            dailyCapCents = 2_500L,
        )
        order.verify(lightningRepo).payInvoice(bolt11 = "lnbcrt1test", sats = null)
        order.verify(cacheStore).rememberQuickPayReservation(
            paymentHash = "hash1",
            amountCents = 250L,
            dayKey = quickPaySpendDayKey(),
        )
        order.verify(cacheStore).clearQuickPayReservation("hash1")
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

        val order = inOrder(cacheStore, pendingPaymentRepo)
        order.verify(cacheStore).rememberQuickPayReservation(
            paymentHash = "hash1",
            amountCents = 250L,
            dayKey = quickPaySpendDayKey(),
        )
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

        verify(cacheStore).releaseQuickPaySpendCents(250L, quickPaySpendDayKey())
        verify(cacheStore, never()).rememberQuickPayReservation(any(), any(), any())
        verify(pendingPaymentRepo, never()).track(any())
        assertIs<QuickPayResult.Error>(sut.uiState.value.result)
    }

    @Test
    fun `payment failed after submit releases hash keyed reservation`() = test {
        whenever { lightningRepo.payInvoice(any(), anyOrNull()) }.thenReturn(Result.success("hash1"))
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = "pid",
                paymentHash = "hash1",
                reason = PaymentFailureReason.ROUTE_NOT_FOUND,
            ),
        )

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        verify(cacheStore).rememberQuickPayReservation("hash1", 250L, quickPaySpendDayKey())
        verify(cacheStore).releaseQuickPayReservation("hash1")
        assertIs<QuickPayResult.Error>(sut.uiState.value.result)
    }

    @Test
    fun `reserve failure emits FallBackToConfirm`() = test {
        whenever { cacheStore.tryReserveQuickPaySpendCents(any(), any(), any()) }.thenReturn(false)

        sut.payNow(QuickPayData.Bolt11(sats = 500u, bolt11 = "lnbcrt1test"))
        advanceUntilIdle()

        assertEquals(QuickPayResult.FallBackToConfirm, sut.uiState.value.result)
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
        assertNull(sut.uiState.value.result.takeIf { it is QuickPayResult.Error })
    }
}
