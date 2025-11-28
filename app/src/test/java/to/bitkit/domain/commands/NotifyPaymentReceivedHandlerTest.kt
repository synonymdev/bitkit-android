package to.bitkit.domain.commands

import android.content.Context
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotifyPaymentReceivedHandlerTest : BaseUnitTest() {

    private val context: Context = mock()
    private val activityRepo: ActivityRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val settingsStore: SettingsStore = mock()

    private lateinit var sut: NotifyPaymentReceivedHandler

    @Before
    fun setUp() {
        whenever(context.getString(R.string.notification_received_title)).thenReturn("Payment Received")
        whenever(context.getString(any(), any())).thenReturn("Received amount")
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(showNotificationDetails = true)))
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("0.10"),
                    formatted = "0.10",
                    symbol = "$",
                    currency = "USD",
                    flag = "\uD83C\uDDFA\uD83C\uDDF8",
                    sats = 100L
                )
            )
        )

        sut = NotifyPaymentReceivedHandler(
            context = context,
            ioDispatcher = testDispatcher,
            activityRepo = activityRepo,
            currencyRepo = currencyRepo,
            settingsStore = settingsStore,
        )
    }

    @Test
    fun `lightning payment returns ShowSheet`() = test {
        val command = NotifyPaymentReceived.Command.Lightning(sats = 1000uL, paymentHashOrTxId = "hash123")

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowSheet)
        val showResult = paymentResult as NotifyPaymentReceived.Result.ShowSheet
        assertEquals(NewTransactionSheetType.LIGHTNING, showResult.details.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, showResult.details.direction)
        assertEquals("hash123", showResult.details.paymentHashOrTxId)
        assertEquals(1000L, showResult.details.sats)
    }

    @Test
    fun `lightning payment returns ShowNotification when includeNotification is true`() = test {
        val command = NotifyPaymentReceived.Command.Lightning(
            sats = 1000uL,
            paymentHashOrTxId = "hash123",
            includeNotification = true,
        )

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowNotification)
        val showResult = paymentResult as NotifyPaymentReceived.Result.ShowNotification
        assertEquals(NewTransactionSheetType.LIGHTNING, showResult.details.type)
        assertEquals("hash123", showResult.details.paymentHashOrTxId)
        assertNotNull(showResult.notification)
        assertEquals("Payment Received", showResult.notification.title)
    }

    @Test
    fun `onchain payment returns ShowSheet when shouldShowPaymentReceived returns true`() = test {
        whenever(activityRepo.shouldShowPaymentReceived(any(), any())).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Onchain(sats = 5000uL, paymentHashOrTxId = "txid456")

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowSheet)
        val showResult = paymentResult as NotifyPaymentReceived.Result.ShowSheet
        assertEquals(NewTransactionSheetType.ONCHAIN, showResult.details.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, showResult.details.direction)
        assertEquals("txid456", showResult.details.paymentHashOrTxId)
        assertEquals(5000L, showResult.details.sats)
    }

    @Test
    fun `onchain payment returns Skip when shouldShowPaymentReceived is false`() = test {
        whenever(activityRepo.shouldShowPaymentReceived(any(), any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Onchain(sats = 5000uL, paymentHashOrTxId = "txid456")

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
    }

    @Test
    fun `onchain payment calls shouldShowPaymentReceived with correct parameters`() = test {
        whenever(activityRepo.shouldShowPaymentReceived(any(), any())).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Onchain(sats = 7500uL, paymentHashOrTxId = "txid789")

        sut(command)

        verify(activityRepo).shouldShowPaymentReceived("txid789", 7500uL)
    }

    @Test
    fun `lightning payment does not call shouldShowPaymentReceived`() = test {
        val command = NotifyPaymentReceived.Command.Lightning(sats = 1000uL, paymentHashOrTxId = "hash123")

        sut(command)

        verify(activityRepo, never()).shouldShowPaymentReceived(any(), any())
    }
}
