package to.bitkit.repositories

import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PaymentDeadline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitSdkService

internal object PaykitSubscriptionProposal {
    /** Longest JPEG avatar URI produced by Bitkit's staging profile namespace. */
    val reservedIconUri = "pubky://" + "x".repeat(122)

    fun validate(terms: PaykitPaymentRequestProposalTerms) {
        if (encodedSize(terms) > PaykitSdkService.MAX_MESSAGE_BYTES) throw PaykitPaymentRequestError.SubscriptionTooLong
    }

    fun encodedSize(terms: PaykitPaymentRequestProposalTerms): Int {
        val recurrence = requireNotNull(terms.recurrence)
        val uuid = "00000000-0000-0000-0000-000000000000"
        val wire = buildJsonObject {
            put("version", 1)
            put("kind", "paykit.payment_request")
            put("app_id", "bitkit")
            put("event_id", uuid)
            put("payment_request_id", uuid)
            putJsonObject("request") {
                putJsonObject("amount") {
                    put("value", terms.amountValue)
                    put("asset", terms.amountAsset)
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
                put("required_app_id", "bitkit")
                putConversion(terms.conversion)
                terms.paymentDeadline?.let { deadline ->
                    putJsonObject("payment_deadline") {
                        when (deadline) {
                            is PaymentDeadline.At -> {
                                put("type", "at")
                                put("timestamp", deadline.timestamp)
                            }
                            is PaymentDeadline.PeriodStart -> {
                                put("type", "period_start")
                                put("seconds", Json.parseToJsonElement(deadline.seconds.toString()))
                            }
                        }
                    }
                }
                put("metadata", Json.parseToJsonElement(terms.metadataJson))
            }
        }
        return wire.toString().encodeToByteArray().size
    }
    private fun JsonObjectBuilder.putConversion(conversion: PaymentConversion?) {
        conversion?.let { conversion ->
            putJsonObject("conversion") {
                when (conversion) {
                    is PaymentConversion.Fixed -> {
                        put("type", "fixed")
                        putJsonArray("rates") {
                            conversion.rates.forEach { rate ->
                                add(
                                    buildJsonObject {
                                        put("asset", rate.asset)
                                        put("value", rate.value)
                                    }
                                )
                            }
                        }
                    }
                    PaymentConversion.PerPeriod -> put("type", "per_period")
                }
            }
        }
    }
}
