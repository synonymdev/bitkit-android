package to.bitkit.repositories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import to.bitkit.services.PaykitPaymentRequestProposalTerms

internal object PaykitSubscriptionProposal {
    /** Maximum plaintext size accepted by Paykit's pubky-noise transport. */
    const val MAX_MESSAGE_BYTES = 1000

    /** Longest JPEG avatar URI produced by Bitkit's staging profile namespace. */
    val reservedIconUri = "pubky://" + "x".repeat(122)

    fun validate(terms: PaykitPaymentRequestProposalTerms) {
        if (encodedSize(terms) > MAX_MESSAGE_BYTES) throw PaykitPaymentRequestError.SubscriptionTooLong
    }

    fun encodedSize(terms: PaykitPaymentRequestProposalTerms): Int {
        val recurrence = requireNotNull(terms.recurrence)
        val uuid = "00000000-0000-0000-0000-000000000000"
        val wire = buildJsonObject {
            put("version", 1)
            put("kind", "paykit.payment_request")
            put("event_id", uuid)
            put("payment_request_id", uuid)
            putJsonObject("request") {
                putJsonObject("amount") {
                    put("value", terms.amountValue)
                    put("asset", "btc")
                }
                put("payment_reference", terms.paymentReference)
                put("proposal_expires_at", terms.proposalExpiresAt)
                putJsonObject("recurrence") {
                    put("every", recurrence.every.toLong())
                    put("unit", recurrence.unit)
                    put("starts_at", recurrence.startsAt)
                    put("anchor", recurrence.anchor)
                    put("ends_at", recurrence.endsAt?.let(::JsonPrimitive) ?: JsonNull)
                }
                putJsonArray("accepted_payment_endpoint_identifiers") {
                    terms.acceptedPaymentEndpointIdentifiers.forEach { add(JsonPrimitive(it)) }
                }
                put("metadata", Json.parseToJsonElement(terms.metadataJson))
            }
        }
        return wire.toString().encodeToByteArray().size
    }
}
