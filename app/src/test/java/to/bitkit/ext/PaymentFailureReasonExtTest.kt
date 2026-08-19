package to.bitkit.ext

import android.content.Context
import org.junit.Test
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.SendFailureDetails
import to.bitkit.utils.LdkError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaymentFailureReasonExtTest {
    private val context = mock<Context>()

    @Test
    fun `routing failures use generic payment copy in send context`() {
        val generic = "Generic route message"
        whenever(context.getString(R.string.wallet__payment_route_not_found)).thenReturn(generic)

        assertEquals(generic, PaymentFailureReason.ROUTE_NOT_FOUND.toUserMessage(context))
        assertEquals(generic, PaymentFailureReason.ROUTE_NOT_FOUND.toSendFailureDetails(context).message)
    }

    @Test
    fun `unmapped reasons fall back to generic payment failure copy`() {
        val message = "Generic payment failed"
        whenever(context.getString(R.string.wallet__payment_failed_description)).thenReturn(message)

        assertEquals(message, PaymentFailureReason.UNEXPECTED_ERROR.toUserMessage(context))
        assertEquals(message, (null as PaymentFailureReason?).toUserMessage(context))
    }

    @Test
    fun `send failure messages fall back for blank and internal exception messages`() {
        val message = "Generic payment failed"
        whenever(context.getString(R.string.wallet__payment_failed_description)).thenReturn(message)

        assertEquals(message, Exception("  ").toSendFailureMessage(context))
        assertEquals(
            message,
            LdkError(NodeException.DuplicatePayment("Duplicate payment.")).toSendFailureMessage(context),
        )
    }

    @Test
    fun `payment failure reason compact failure types use lower camel case`() {
        assertEquals("routeNotFound", PaymentFailureReason.ROUTE_NOT_FOUND.toCompactFailureType())
    }

    @Test
    fun `compact failure types use android ldk error classes`() {
        assertEquals(
            "DuplicatePayment",
            LdkError(NodeException.DuplicatePayment("Duplicate payment.")).toCompactFailureType(),
        )
        assertEquals(
            "InvalidCustomTlvs",
            LdkError(NodeException.InvalidCustomTlvs("Invalid custom TLVs")).toCompactFailureType(),
        )
    }

    @Test
    fun `compact failure types fall back to the exception class name`() {
        assertEquals("IllegalStateException", IllegalStateException().toCompactFailureType())
    }

    @Test
    fun `compact failure types ignore sentence punctuation`() {
        assertNotEquals("Unknown", Exception("Payment sending failed.").toCompactFailureType())
    }

    @Test
    fun `routing cache reset is gated to one routing failure retry attempt`() {
        val routingFailure = SendFailureDetails(
            message = "Route not found",
            failureType = "routeNotFound",
            resetRoutingCachesOnRetry = true,
        )
        val genericFailure = routingFailure.copy(resetRoutingCachesOnRetry = false)

        assertTrue(routingFailure.shouldResetRoutingCaches(routingCacheResetAttempted = false))
        assertFalse(routingFailure.shouldResetRoutingCaches(routingCacheResetAttempted = true))
        assertFalse(genericFailure.shouldResetRoutingCaches(routingCacheResetAttempted = false))
    }
}
