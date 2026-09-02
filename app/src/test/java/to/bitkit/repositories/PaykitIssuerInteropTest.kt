package to.bitkit.repositories

import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PaykitIssuerInteropTest {
    companion object {
        private val NOW = Instant.parse("2026-09-02T12:00:00Z")
        private val PAYMENT_REFERENCE = mock<PaymentReference> {
            on { exportText() } doReturn "marketplace-order-713"
        }
        private val METADATA = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"order":"713"}"""
        }
    }

    @Test
    fun `request fixtures match issuer contract`() {
        val fixtures = loadFixtures()
        assertEquals(1, fixtures.schemaVersion)

        fixtures.requestFixtures.forEach { fixture ->
            val record = paymentRequestRecord(
                asset = fixture.asset,
                endpointIdentifiers = fixture.acceptedPaymentEndpointIdentifiers,
            )
            val request = record.toPaykitPaymentRequest(
                expectedRole = PaymentRequestLocalRole.PAYER,
                now = NOW,
                network = fixture.network.ldkNetwork,
            )

            assertEquals(fixture.accepted, request != null, fixture.name)
            assertEquals(
                fixture.expectedIdentifiers,
                request?.acceptedPaymentEndpointIdentifiers.orEmpty(),
                fixture.name,
            )
        }
    }

    @Test
    fun `endpoint fixtures match issuer contract`() {
        loadFixtures().endpointFixtures.forEach { fixture ->
            val endpoint = PublicPaykitRepo.parseEndpoint(
                methodId = fixture.identifier,
                endpointData = fixture.payload,
                network = Network.REGTEST,
            )

            assertEquals(fixture.accepted, endpoint != null, fixture.name)
            assertEquals(fixture.expectedValue, endpoint?.value, fixture.name)
            assertEquals(fixture.expectedMin, endpoint?.min, fixture.name)
            assertEquals(fixture.expectedMax, endpoint?.max, fixture.name)
        }
    }

    @Test
    fun `request fixtures cover every network and chain independent lightning identifiers`() {
        val acceptedFixtures = loadFixtures().requestFixtures.filter { it.accepted }

        FixtureNetwork.entries.forEach { network ->
            assertTrue(
                acceptedFixtures.any {
                    it.network == network && it.expectedIdentifiers == listOf("btc-lightning-bolt11")
                },
                "Missing Bolt11 fixture for '${network.serializedName}'",
            )
            assertTrue(
                acceptedFixtures.any {
                    it.network == network && it.expectedIdentifiers == listOf("btc-lightning-lnurl")
                },
                "Missing LNURL fixture for '${network.serializedName}'",
            )
            assertTrue(
                acceptedFixtures.any {
                    it.network == network &&
                        it.expectedIdentifiers == listOf("btc-${network.serializedName}-p2wpkh")
                },
                "Missing on-chain fixture for '${network.serializedName}'",
            )
        }
    }

    private fun loadFixtures(): IssuerInteropFixtures {
        val resource = requireNotNull(javaClass.classLoader?.getResource("paykit-issuer-interoperability.json")) {
            "Missing Paykit issuer interoperability fixtures"
        }
        return Json.decodeFromString(resource.readText())
    }

    private fun paymentRequestRecord(
        asset: String,
        endpointIdentifiers: List<String>,
    ) = PaymentRequestRecord(
        counterparty = "pubkyissuerfixture",
        counterpartyReceiverPath = "bitkit/server",
        paymentRequestId = "71300000-0000-4000-8000-000000000001",
        localRole = PaymentRequestLocalRole.PAYER,
        state = PaymentRequestLifecycleState.PROPOSED,
        proposalStreamItemId = 1uL,
        proposalOutboundMessageId = null,
        proposalOutboundStatus = null,
        proposalEventId = "71300000-0000-4000-8000-000000000002",
        terms = PaymentRequestTerms(
            amount = PaymentRequestAmount(value = "0.001", asset = asset),
            paymentReference = PAYMENT_REFERENCE,
            proposalExpiresAt = null,
            recurrence = null,
            acceptedPaymentEndpointIdentifiers = endpointIdentifiers,
            metadata = METADATA,
        ),
        acceptedEventId = null,
        acceptedOutboundStatus = null,
        rejectedEventId = null,
        rejectedOutboundStatus = null,
        canceledEventId = null,
        canceledOutboundStatus = null,
        paymentProofs = emptyList(),
        lastStreamItemId = 1uL,
        lastOutboundMessageId = null,
        lastOutboundStatus = null,
        lastEventAt = NOW.toString(),
        invalidReason = null,
    )
}

@Serializable
private data class IssuerInteropFixtures(
    val schemaVersion: Int,
    val requestFixtures: List<RequestFixture>,
    val endpointFixtures: List<EndpointFixture>,
)

@Serializable
private data class RequestFixture(
    val name: String,
    val network: FixtureNetwork,
    val asset: String,
    val acceptedPaymentEndpointIdentifiers: List<String>,
    val accepted: Boolean,
    val expectedIdentifiers: List<String>,
)

@Serializable
private data class EndpointFixture(
    val name: String,
    val identifier: String,
    val payload: String,
    val accepted: Boolean,
    val expectedValue: String? = null,
    val expectedMin: String? = null,
    val expectedMax: String? = null,
)

@Serializable
private enum class FixtureNetwork(
    val ldkNetwork: Network,
    val serializedName: String,
) {
    @SerialName("bitcoin")
    Bitcoin(Network.BITCOIN, "bitcoin"),

    @SerialName("testnet")
    Testnet(Network.TESTNET, "testnet"),

    @SerialName("signet")
    Signet(Network.SIGNET, "signet"),

    @SerialName("regtest")
    Regtest(Network.REGTEST, "regtest"),
}
