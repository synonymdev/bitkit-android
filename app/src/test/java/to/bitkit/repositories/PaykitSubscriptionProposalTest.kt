package to.bitkit.repositories

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitPaymentRequestRecurrenceTerms
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaykitSubscriptionProposalTest {
    @Test
    fun `transport limit includes app ids endpoints and public icon`() {
        val emptyWire = """
            {"version":1,"kind":"paykit.payment_request","app_id":"bitkit",
            "event_id":"00000000-0000-0000-0000-000000000000","payment_request_id":"00000000-0000-0000-0000-000000000000",
            "request":{"amount":{"value":"0.001","asset":"btc"},"payment_reference":"bitkit-00000000-0000-0000-0000-000000000000",
            "proposal_expires_at":"2027-01-22T08:00:00.000Z",
            "recurrence":{"every":1,"unit":"month","starts_at":"2027-01-15T08:00:00.000Z","anchor":"2027-01-15T08:00:00.000Z","ends_at":null},
            "accepted_payment_endpoint_identifiers":["btc-regtest-p2wpkh","btc-lightning-bolt11","btc-lightning-lnurl"],"required_app_id":"bitkit",
            "metadata":{"note":"Support","subscription":{"benefits":[],"description":"",
            "icon_uri":"pubky://${"x".repeat(122)}","version":1}}}}
        """.trimIndent().lines().joinToString("")
        assertEquals(840, emptyWire.encodeToByteArray().size)
        val empty = terms("", PaykitSubscriptionProposal.reservedIconUri)
        assertEquals(emptyWire.encodeToByteArray().size, PaykitSubscriptionProposal.encodedSize(empty))
        val full = terms("a".repeat(160), PaykitSubscriptionProposal.reservedIconUri)
        assertEquals(1000, PaykitSubscriptionProposal.encodedSize(full))
        PaykitSubscriptionProposal.validate(full)
        val oversized = terms("a".repeat(161), PaykitSubscriptionProposal.reservedIconUri)
        assertEquals(1001, PaykitSubscriptionProposal.encodedSize(oversized))
        assertFailsWith<PaykitPaymentRequestError.SubscriptionTooLong> {
            PaykitSubscriptionProposal.validate(oversized)
        }
    }

    @Test
    fun `size counts UTF8 and JSON escaping rather than characters`() {
        val base = PaykitSubscriptionProposal.encodedSize(terms(""))
        assertEquals(base + 4, PaykitSubscriptionProposal.encodedSize(terms("💜")))
        assertEquals(base + 6, PaykitSubscriptionProposal.encodedSize(terms("\"\n\\")))
        assertEquals(base + 19, PaykitSubscriptionProposal.encodedSize(terms("https://example.com")))
    }

    private fun terms(description: String, iconUri: String? = null) = PaykitPaymentRequestProposalTerms(
        amountValue = "0.001",
        paymentReference = "bitkit-00000000-0000-0000-0000-000000000000",
        proposalExpiresAt = "2027-01-22T08:00:00.000Z",
        recurrence = PaykitPaymentRequestRecurrenceTerms(
            every = 1u,
            unit = "month",
            startsAt = "2027-01-15T08:00:00.000Z",
            anchor = "2027-01-15T08:00:00.000Z",
        ),
        acceptedPaymentEndpointIdentifiers = listOf(
            "btc-regtest-p2wpkh",
            "btc-lightning-bolt11",
            "btc-lightning-lnurl",
        ),
        metadataJson = buildJsonObject {
            put("note", "Support")
            put(
                "subscription",
                buildJsonObject {
                    put("version", 1)
                    put("description", description)
                    put("benefits", buildJsonArray { })
                    iconUri?.let { put("icon_uri", it) }
                },
            )
        }.toString(),
    )
}
