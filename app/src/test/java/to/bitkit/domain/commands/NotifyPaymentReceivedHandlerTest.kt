package to.bitkit.domain.commands

import android.content.Context
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.TransactionDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
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
        whenever(context.getString(R.string.notification__received__title)).thenReturn("Payment Received")
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
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen(any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowSheet)
        assertEquals(NewTransactionSheetType.LIGHTNING, paymentResult.sheet.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, paymentResult.sheet.direction)
        assertEquals("hash123", paymentResult.sheet.paymentHashOrTxId)
        assertEquals(1000L, paymentResult.sheet.sats)
        verify(activityRepo).markActivityAsSeen("paymentId123")
    }

    @Test
    fun `lightning payment returns ShowNotification when includeNotification is true`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen(any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Lightning(
            event = event,
            includeNotification = true,
        )

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowNotification)
        assertEquals(NewTransactionSheetType.LIGHTNING, paymentResult.sheet.type)
        assertEquals("hash123", paymentResult.sheet.paymentHashOrTxId)
        assertNotNull(paymentResult.notification)
        assertEquals("Payment Received", paymentResult.notification.title)
    }

    @Test
    fun `onchain payment returns ShowSheet when shouldShowReceivedSheet returns true`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 5000L
        }
        val event = mock<Event.OnchainTransactionReceived> {
            on { txid } doReturn "txid456"
            on { this.details } doReturn details
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Onchain(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowSheet)
        assertEquals(NewTransactionSheetType.ONCHAIN, paymentResult.sheet.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, paymentResult.sheet.direction)
        assertEquals("txid456", paymentResult.sheet.paymentHashOrTxId)
        assertEquals(5000L, paymentResult.sheet.sats)
        verify(activityRepo).markOnchainActivityAsSeen("txid456")
    }

    @Test
    fun `onchain payment returns Skip when shouldShowReceivedSheet is false`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 5000L
        }
        val event = mock<Event.OnchainTransactionReceived> {
            on { txid } doReturn "txid456"
            on { this.details } doReturn details
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Onchain(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
    }

    @Test
    fun `onchain payment calls methods in correct order`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 7500L
        }
        val event = mock<Event.OnchainTransactionReceived> {
            on { txid } doReturn "txid789"
            on { this.details } doReturn details
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Onchain(event = event)

        sut(command)

        inOrder(activityRepo) {
            verify(activityRepo).handleOnchainTransactionReceived("txid789", details)
            verify(activityRepo).shouldShowReceivedSheet("txid789", 7500uL)
            verify(activityRepo).markOnchainActivityAsSeen("txid789")
        }
    }

    @Test
    fun `onchain payment does not mark as seen when shouldShowReceivedSheet returns false`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 5000L
        }
        val event = mock<Event.OnchainTransactionReceived> {
            on { txid } doReturn "txid456"
            on { this.details } doReturn details
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Onchain(event = event)

        sut(command)

        verify(activityRepo, never()).markOnchainActivityAsSeen(any())
    }

    @Test
    fun `lightning payment does not call onchain-specific methods`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen(any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        sut(command)

        verify(activityRepo, never()).handleOnchainTransactionReceived(any(), any())
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
        verify(activityRepo, never()).markOnchainActivityAsSeen(any())
    }

    @Test
    fun `lightning payment returns Skip when already seen`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen("paymentId123")).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).markActivityAsSeen(any())
    }

    @Test
    fun `lightning payment returns Skip when paymentId is null`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn null
        }
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
    }
}
