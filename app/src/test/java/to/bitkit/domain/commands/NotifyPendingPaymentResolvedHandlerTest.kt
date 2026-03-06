package to.bitkit.domain.commands

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotifyPendingPaymentResolvedHandlerTest : BaseUnitTest() {

    private val context: Context = mock()
    private val pendingPaymentRepo: PendingPaymentRepo = mock()

    private lateinit var sut: NotifyPendingPaymentResolvedHandler

    @Before
    fun setUp() {
        whenever(context.getString(R.string.wallet__toast_payment_sent_title))
            .thenReturn("Payment Sent")
        whenever(context.getString(R.string.wallet__toast_payment_sent_description))
            .thenReturn("Your pending payment was completed successfully.")
        whenever(context.getString(R.string.wallet__toast_payment_failed_title))
            .thenReturn("Payment Failed")
        whenever(context.getString(R.string.wallet__toast_payment_failed_description))
            .thenReturn("Your instant payment failed. Please try again.")

        sut = NotifyPendingPaymentResolvedHandler(
            context = context,
            ioDispatcher = testDispatcher,
            pendingPaymentRepo = pendingPaymentRepo,
        )
    }

    @Test
    fun `success command returns ShowNotification when pending`() = test {
        whenever(pendingPaymentRepo.isPending(any())).thenReturn(true)
        val command = NotifyPendingPaymentResolved.Command.Success(paymentHash = "hash123")

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPendingPaymentResolved.Result.ShowNotification)
        assertEquals("Payment Sent", paymentResult.notification.title)
        assertEquals("Your pending payment was completed successfully.", paymentResult.notification.body)
    }

    @Test
    fun `failure command returns ShowNotification when pending`() = test {
        whenever(pendingPaymentRepo.isPending(any())).thenReturn(true)
        val command = NotifyPendingPaymentResolved.Command.Failure(
            paymentHash = "hash456",
            reason = PaymentFailureReason.ROUTE_NOT_FOUND,
        )

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPendingPaymentResolved.Result.ShowNotification)
        assertEquals("Payment Failed", paymentResult.notification.title)
        assertEquals("Your instant payment failed. Please try again.", paymentResult.notification.body)
    }

    @Test
    fun `success command returns Skip when not pending`() = test {
        whenever(pendingPaymentRepo.isPending(any())).thenReturn(false)
        val command = NotifyPendingPaymentResolved.Command.Success(paymentHash = "hash789")

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPendingPaymentResolved.Result.Skip)
    }

    @Test
    fun `failure command returns Skip when not pending`() = test {
        whenever(pendingPaymentRepo.isPending(any())).thenReturn(false)
        val command = NotifyPendingPaymentResolved.Command.Failure(
            paymentHash = "hash000",
            reason = PaymentFailureReason.RETRIES_EXHAUSTED,
        )

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPendingPaymentResolved.Result.Skip)
    }
}
