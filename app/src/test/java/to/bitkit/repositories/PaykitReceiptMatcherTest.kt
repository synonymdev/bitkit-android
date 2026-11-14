package to.bitkit.repositories

import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import org.junit.Test
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Network
import org.mockito.kotlin.mock
import to.bitkit.ext.toHex
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaykitReceiptMatcherTest {
    companion object {
        /** Synthetic buyer identity. */
        const val BUYER = "pubky7don8zi885feihpjsyx7t53srod6z1n4xjiyaaxucpqarm6sh85o"

        /** Second buyer identity for ambiguous claims. */
        const val OTHER_BUYER = "pubkya3mduedw686dysw8ndr5c1dyry5h8k6i8hzbbmx9gf9k43zq4s9o"

        /** Regtest receiving address embedded in a Market request. */
        const val ADDRESS = "bcrt1qfn50lqawrce0evh66qrnlt8j447lwmeyqp5gmd"

        /** Regtest on-chain endpoint identifier. */
        const val ONCHAIN_METHOD = "btc-regtest-p2wpkh"

        /** Lightning endpoint identifier. */
        const val BOLT11_METHOD = "btc-lightning-bolt11"

        /** Invoice sentinel accepted by the test parser. */
        const val INVOICE = "lnbcrt1example"

        /** Preimage a payer's Lightning proof reveals. */
        val PREIMAGE = "11".repeat(32)

        /** Payment hash of [PREIMAGE]. */
        val HASH: String = MessageDigest.getInstance("SHA-256").digest(ByteArray(32) { 0x11 }).toHex()

        /** On-chain transaction id. */
        val TXID = "cd".repeat(32)

        /** Request id of the fixture request. */
        const val REQUEST_ID = "4f1c2a3b-1d2e-4f5a-8b6c-7d8e9f0a1b2c"

        fun proof(
            type: String,
            data: String,
            endpoint: String,
            eventId: String = "event-1",
        ) = mock<PaymentProofRecord> {
            val json = mock<PrivateJsonObject> { on { exportText() }.thenReturn("""{"data":"$data","type":"$type"}""") }
            on { proof }.thenReturn(json)
            on { paymentEndpointIdentifier }.thenReturn(endpoint)
            on { billingPeriod }.thenReturn(null)
            on { this.eventId }.thenReturn(eventId)
        }

        fun payeeRequest(
            counterparty: String = BUYER,
            requestId: String = REQUEST_ID,
            role: PaymentRequestLocalRole = PaymentRequestLocalRole.PAYEE,
            proofs: List<PaymentProofRecord> = emptyList(),
            endpoints: Map<String, String>? = null,
            proposedAt: String? = null,
        ): PaymentRequestRecord {
            val reference = mock<PaymentReference> { on { exportText() }.thenReturn("order-42") }
            val terms = PaymentRequestTerms(
                amount = PaymentRequestAmount("0.00015", "btc"), paymentReference = reference,
                proposalExpiresAt = null, recurrence = null,
                acceptedPaymentEndpointIdentifiers = listOf(ONCHAIN_METHOD, BOLT11_METHOD),
                paymentEndpoints = endpoints, requiredAppId = "bitkit", conversion = null,
                paymentDeadline = null, metadata = mock(),
            )
            return mock {
                on { this.counterparty }.thenReturn(counterparty)
                on { paymentRequestId }.thenReturn(requestId)
                on { localRole }.thenReturn(role)
                on { state }.thenReturn(PaymentRequestLifecycleState.PROOF_SUBMITTED)
                on { invalidReason }.thenReturn(null)
                on { this.terms }.thenReturn(terms)
                on { paymentProofs }.thenReturn(proofs)
                on { proposalRecordedAt }.thenReturn(proposedAt)
            }
        }

        fun payload(value: String) = "{\"value\":\"$value\"}"
    }

    private fun match(
        vararg records: PaymentRequestRecord,
        lightning: List<PaykitRecordedReceive> = emptyList(),
        onchain: List<PaykitRecordedReceive> = emptyList(),
        usdt: List<PaykitVerifiedUsdtReceive> = emptyList(),
    ) = PaykitReceiptMatcher.candidates(
        records = records.toList(),
        lightningReceives = lightning.associateBy { it.paymentId },
        onchainReceives = onchain,
        usdtReceives = usdt,
        network = Network.REGTEST,
    ) { value ->
        require(value == INVOICE)
        mock<Bolt11Invoice> { on { paymentHash() }.thenReturn(HASH) }
    }

    @Test
    fun `lightning proof with a recorded receive receipts what arrived`() {
        val request = payeeRequest(proofs = listOf(proof("bitcoin-bolt11-preimage", PREIMAGE, BOLT11_METHOD)))
        val partial = bitcoinReceive(HASH, PaykitReceiptRail.Lightning, 1_000uL)

        val candidate = match(request, lightning = listOf(partial)).single()

        assertEquals(REQUEST_ID, candidate.paymentRequestId)
        assertEquals("order-42", candidate.paymentReference)
        assertEquals(BOLT11_METHOD, candidate.paymentEndpointIdentifier)
        assertEquals("0.00001", candidate.receive.amount.value)
        assertEquals("""{"payment_hash":"$HASH"}""", candidate.metadataJson)
    }

    @Test
    fun `a proof without a recorded receive issues nothing`() {
        val request = payeeRequest(proofs = listOf(proof("bitcoin-bolt11-preimage", PREIMAGE, BOLT11_METHOD)))

        assertTrue(match(request).isEmpty())
    }

    @Test
    fun `a bare txid proof from another paykit app is matched to the recorded receive`() {
        val txidProof = mock<PaymentProofRecord> {
            val json = mock<PrivateJsonObject> { on { exportText() }.thenReturn("""{"txid":"$TXID"}""") }
            on { proof }.thenReturn(json)
            on { paymentEndpointIdentifier }.thenReturn(ONCHAIN_METHOD)
            on { billingPeriod }.thenReturn(null)
            on { eventId }.thenReturn("event-1")
        }
        val request = payeeRequest(proofs = listOf(txidProof))

        val receive = bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 10_000uL)

        val candidate = match(request, onchain = listOf(receive)).single()

        assertEquals("""{"txid":"$TXID"}""", candidate.metadataJson)
    }

    @Test
    fun `requests the wallet pays are never receipted`() {
        val request = payeeRequest(
            role = PaymentRequestLocalRole.PAYER,
            proofs = listOf(proof("bitcoin-onchain-txid", TXID, ONCHAIN_METHOD)),
        )

        assertTrue(
            match(request, onchain = listOf(bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 15_000uL))).isEmpty()
        )
    }

    @Test
    fun `market request receipts an overpayment to its own address without a proof`() {
        val request = payeeRequest(endpoints = mapOf(ONCHAIN_METHOD to payload(ADDRESS)))
        val overpaid = bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 20_000uL, address = ADDRESS)

        val candidate = match(request, onchain = listOf(overpaid)).single()

        assertEquals(ONCHAIN_METHOD, candidate.paymentEndpointIdentifier)
        assertEquals("0.0002", candidate.receive.amount.value)
        assertEquals("""{"txid":"$TXID"}""", candidate.metadataJson)
    }

    @Test
    fun `market request receipts a payment to its own invoice`() {
        val request = payeeRequest(endpoints = mapOf(BOLT11_METHOD to payload(INVOICE)))

        val candidate = match(request, lightning = listOf(bitcoinReceive(HASH, PaykitReceiptRail.Lightning, 15_000uL)))
            .single()

        assertEquals(BOLT11_METHOD, candidate.paymentEndpointIdentifier)
    }

    @Test
    fun `a payment two requests claim is left alone`() {
        val claim = proof("bitcoin-onchain-txid", TXID, ONCHAIN_METHOD)
        val first = payeeRequest(proofs = listOf(claim))
        val second = payeeRequest(
            counterparty = OTHER_BUYER,
            requestId = "5f1c2a3b-1d2e-4f5a-8b6c-7d8e9f0a1b2c",
            proofs = listOf(claim),
        )

        assertTrue(
            match(first, second, onchain = listOf(bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 1uL))).isEmpty()
        )
    }

    @Test
    fun `a receive recorded well before the request was proposed is not receipted`() {
        val request = payeeRequest(
            proofs = listOf(proof("bitcoin-onchain-txid", TXID, ONCHAIN_METHOD)),
            proposedAt = "2026-11-14T12:00:00.123456789+00:00",
        )
        val unreadable = payeeRequest(
            proofs = listOf(proof("bitcoin-onchain-txid", TXID, ONCHAIN_METHOD)),
            proposedAt = "yesterday",
        )
        val earlier = bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 1_000uL, receivedAt = 1_763_100_000uL)
        val later = bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 1_000uL, receivedAt = 1_794_657_600uL)

        assertTrue(match(request, onchain = listOf(earlier)).isEmpty())
        assertEquals(1, match(request, onchain = listOf(later)).size)
        assertTrue(match(unreadable, onchain = listOf(later)).isEmpty())
    }

    @Test
    fun `a claim on a payment the wallet attributes to another contact is ignored`() {
        val claim = proof("bitcoin-onchain-txid", TXID, ONCHAIN_METHOD)
        val payer = payeeRequest(proofs = listOf(claim))
        val claimant = payeeRequest(
            counterparty = OTHER_BUYER,
            requestId = "5f1c2a3b-1d2e-4f5a-8b6c-7d8e9f0a1b2c",
            proofs = listOf(claim),
        )
        val receive = bitcoinReceive(TXID, PaykitReceiptRail.Onchain, 1_000uL, contact = BUYER)

        val candidate = match(payer, claimant, onchain = listOf(receive)).single()

        assertEquals(BUYER, candidate.counterparty)
    }

    @Test
    fun `usdt receipt names the proof's transaction and keys status by transfer`() {
        val usdtProof = mock<PaymentProofRecord> {
            val json = mock<PrivateJsonObject> {
                on { exportText() }.thenReturn("""{"type":"erc20-transfer-eip712","transaction_hash":"0xABC"}""")
            }
            on { proof }.thenReturn(json)
            on { paymentEndpointIdentifier }.thenReturn("usdt-arbitrum")
            on { billingPeriod }.thenReturn(null)
            on { eventId }.thenReturn("usdt-event")
        }
        val request = payeeRequest(proofs = listOf(usdtProof))
        val usdt = PaykitVerifiedUsdtReceive(
            requestId = PaykitPaymentRequestId(REQUEST_ID, BUYER),
            proofEventId = "usdt-event",
            transferId = "transfer-1",
            amount = PaykitAmount(PaykitAsset.USDT, 9_000_000uL),
        )

        val candidate = match(request, usdt = listOf(usdt)).single()

        assertEquals("0xabc", candidate.receive.paymentId)
        assertEquals("transfer-1", candidate.receive.statusKey)
        assertEquals("9", candidate.receive.amount.value)
        assertEquals("usdt", candidate.receive.amount.asset.code)
    }

    @Test
    fun `receipt id is a stable uuid v4 per request and payment`() {
        val id = paykitReceiptId(BUYER, REQUEST_ID, null, HASH)

        assertEquals(id, paykitReceiptId(BUYER, REQUEST_ID, null, HASH))
        assertNotEquals(id, paykitReceiptId(BUYER, REQUEST_ID, null, TXID))
        assertEquals(4, UUID.fromString(id).version())
        assertEquals(2, UUID.fromString(id).variant())
    }
}
