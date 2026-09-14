package to.bitkit.repositories

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitPaymentRequestRecurrenceTerms
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaykitSubscriptionProposalTest {
    @Test
    fun `transport limit includes envelope endpoints and public icon`() {
        val empty = terms("", PaykitSubscriptionProposal.reservedIconUri)
        val available = PaykitSubscriptionProposal.MAX_MESSAGE_BYTES - PaykitSubscriptionProposal.encodedSize(empty)
        assertTrue(available > 0)
        val full = terms("a".repeat(available), PaykitSubscriptionProposal.reservedIconUri)
        assertEquals(1000, PaykitSubscriptionProposal.encodedSize(full))
        PaykitSubscriptionProposal.validate(full)
        assertFailsWith<PaykitPaymentRequestError.SubscriptionTooLong> {
            val oversized = terms("a".repeat(available + 1), PaykitSubscriptionProposal.reservedIconUri)
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
        acceptedPaymentEndpointIdentifiers = listOf("bitcoin:regtest", "lightning:bolt11", "lightning:lnurl"),
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
