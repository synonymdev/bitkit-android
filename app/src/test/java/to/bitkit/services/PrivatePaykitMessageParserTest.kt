package to.bitkit.services

import com.synonym.paykit.FfiPaymentEndpoint
import com.synonym.paykit.FfiPrivatePaymentList
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivatePaykitMessageParserTest {
    @Test
    fun `parsePrivatePaymentListJson keeps current Paykit parser result`() {
        val parsed = FfiPrivatePaymentList(
            paymentEndpoints = listOf(
                FfiPaymentEndpoint(
                    paymentEndpointIdentifier = "bolt11-invoice-0",
                    paymentEndpointPayload = "lnbcrt1current",
                ),
            ),
        )

        val result = PrivatePaykitMessageParser.parsePrivatePaymentListJson("{}") { parsed }

        assertEquals(parsed, result)
    }

    @Test
    fun `parsePrivatePaymentListJson falls back to legacy private payments envelope`() {
        val legacyJson = """
            {
              "version": 1,
              "kind": "paykit.private_payments",
              "reference": "550e8400-e29b-41d4-a716-446655440000",
              "entries": {
                "bolt11-invoice-0": "lnbcrt1legacy",
                "bip21-p2wpkh-0": "bcrt1qlegacy"
              }
            }
        """.trimIndent()

        val result = PrivatePaykitMessageParser.parsePrivatePaymentListJson(legacyJson) {
            throw IllegalArgumentException("unsupported legacy envelope")
        }

        assertEquals(
            listOf(
                FfiPaymentEndpoint(
                    paymentEndpointIdentifier = "bolt11-invoice-0",
                    paymentEndpointPayload = "lnbcrt1legacy",
                ),
                FfiPaymentEndpoint(
                    paymentEndpointIdentifier = "bip21-p2wpkh-0",
                    paymentEndpointPayload = "bcrt1qlegacy",
                ),
            ),
            result?.paymentEndpoints,
        )
    }

    @Test
    fun `parsePrivatePaymentListJson ignores unknown legacy envelopes`() {
        val result = PrivatePaykitMessageParser.parsePrivatePaymentListJson("""{"kind":"other"}""") {
            throw IllegalArgumentException("unsupported envelope")
        }

        assertNull(result)
    }
}
