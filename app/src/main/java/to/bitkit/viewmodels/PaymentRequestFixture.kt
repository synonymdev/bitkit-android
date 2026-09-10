package to.bitkit.viewmodels

import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import kotlin.time.Clock

/** Seeded payment request data for debug UI captures, installed via `bitkit://dev-fixture/payment-request`. */
data class PaymentRequestFixture(
    val contacts: List<PubkyProfile>,
    val targets: List<PaykitPaymentRequestTarget>,
    val pending: List<PaykitPaymentRequest>,
    val history: List<PaykitPaymentRequest>,
) {
    fun created(draft: PaykitPaymentRequestDraft, target: PaykitPaymentRequestTarget): PaykitPaymentRequest {
        val now = Clock.System.now()
        return PaykitPaymentRequest(
            paymentRequestId = "fixture-${now.toEpochMilliseconds()}",
            counterparty = target.publicKey,
            counterpartyReceiverPath = target.receiverPath,
            amountValue = draft.amountSats.toString(),
            amountSats = draft.amountSats,
            note = draft.note.ifBlank { null },
            createdAt = now,
            expiresAt = draft.expiresAt,
            acceptedPaymentEndpointIdentifiers = pending.firstOrNull()?.acceptedPaymentEndpointIdentifiers.orEmpty(),
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
            direction = PaykitPaymentRequestDirection.Outgoing,
        )
    }
}

/** What a `bitkit://dev-fixture/payment-request` link asks for: seed the fixture or clear it. */
sealed interface PaymentRequestFixtureLink {
    data class Seed(val fixture: PaymentRequestFixture) : PaymentRequestFixtureLink
    data object Clear : PaymentRequestFixtureLink
}
