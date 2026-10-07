package to.bitkit.ui.screens.paymentrequests

import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.Test
import to.bitkit.R
import to.bitkit.repositories.PaykitPaymentProofKind
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDirection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentRequestPresentationTest {
    @Test
    fun `unpaid request amounts are not signed as money movement`() {
        val unpaidStates = listOf(
            PaymentRequestLifecycleState.ACCEPTED,
            PaymentRequestLifecycleState.REJECTED,
            PaymentRequestLifecycleState.CANCELED,
            PaymentRequestLifecycleState.PROPOSAL_EXPIRED,
            PaymentRequestLifecycleState.RECOVERY_REQUIRED,
        )

        unpaidStates.forEach { lifecycleState ->
            val request = paymentRequest(lifecycleState = lifecycleState)

            assertFalse(request.hasPaymentEvidence)
            assertEquals(
                "",
                request.amountPrefix(isOutgoingPayment = false, showSignedAmount = request.hasPaymentEvidence),
            )
            assertEquals("", request.detailsAmountPrefix())
            assertEquals("2,500 sats", request.detailsAmountText("2,500 sats"))
        }
    }

    @Test
    fun `paid request amounts preserve their movement direction`() {
        val incoming = paymentRequest(
            lifecycleState = PaymentRequestLifecycleState.PROOF_SUBMITTED,
            direction = PaykitPaymentRequestDirection.Incoming,
        )
        val outgoing = incoming.copy(direction = PaykitPaymentRequestDirection.Outgoing)

        assertTrue(incoming.hasPaymentEvidence)
        assertEquals("-", incoming.amountPrefix(isOutgoingPayment = false, showSignedAmount = true))
        assertEquals("-", incoming.detailsAmountPrefix())
        assertEquals("- 2,500 sats", incoming.detailsAmountText("2,500 sats"))
        assertEquals("+", outgoing.amountPrefix(isOutgoingPayment = false, showSignedAmount = true))
        assertEquals("+", outgoing.detailsAmountPrefix())
        assertEquals("+ 2,500 sats", outgoing.detailsAmountText("2,500 sats"))
    }

    @Test
    fun `live pending incoming request shows waiting status`() {
        val request = paymentRequest(lifecycleState = PaymentRequestLifecycleState.PROPOSED)

        assertEquals(
            R.string.wallet__payment_request_waiting,
            proposedPaymentRequestStatusRes(request = request, isPending = true),
        )
        assertEquals(
            R.string.wallet__payment_request_status_unavailable,
            proposedPaymentRequestStatusRes(request = request, isPending = false),
        )
    }

    private fun paymentRequest(
        lifecycleState: PaymentRequestLifecycleState,
        direction: PaykitPaymentRequestDirection = PaykitPaymentRequestDirection.Incoming,
    ) = PaykitPaymentRequest(
        paymentRequestId = "request-id",
        counterparty = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
        amount = to.bitkit.models.PaykitAmount(to.bitkit.models.PaykitAsset.BTC, 2_500uL),
        paymentReference = "fixture-reference",
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf("lightning-bolt11"),
        direction = direction,
        lifecycleState = lifecycleState,
        paymentProofKind = PaykitPaymentProofKind.Lightning.takeIf {
            lifecycleState == PaymentRequestLifecycleState.PROOF_SUBMITTED
        },
    )
}
