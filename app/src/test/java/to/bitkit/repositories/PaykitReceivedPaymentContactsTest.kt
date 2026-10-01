package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.ValidationResult
import com.synonym.bitkitcore.validateBitcoinAddress
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import org.junit.Test
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Currency
import org.lightningdevkit.ldknode.Network
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaykitReceivedPaymentContactsTest {
    companion object {
        /** Synthetic payer identity shared by request fixtures. */
        const val BUYER = "pubky7don8zi885feihpjsyx7t53srod6z1n4xjiyaaxucpqarm6sh85o"

        /** Second payer identity for ambiguous attribution checks. */
        const val OTHER_BUYER = "pubkya3mduedw686dysw8ndr5c1dyry5h8k6i8hzbbmx9gf9k43zq4s9o"

        /** Valid regtest receiving address used by request fixtures. */
        const val ADDRESS = "bcrt1qfn50lqawrce0evh66qrnlt8j447lwmeyqp5gmd"

        /** Distinct regtest address for unrelated-output checks. */
        const val OTHER_ADDRESS = "bcrt1qpsps9chsjnnd3veems9phzlvw42em682rsj8hh"

        /** Mainnet address sentinel accepted by the mocked decoder. */
        const val MAINNET_ADDRESS = "bc1qexample"

        /** Testnet address sentinel accepted by the mocked decoder. */
        const val TESTNET_ADDRESS = "tb1qexample"

        /** Network-ambiguous prefixes reported as testnet by the mocked decoder. */
        val LEGACY_ADDRESSES = listOf("mlegacy", "nlegacy", "2legacy")

        /** Regtest on-chain endpoint identifier for address fixtures. */
        const val METHOD = "btc-regtest-p2wpkh"

        /** Endpoint identifier for the invoice fixtures. */
        const val BOLT11_METHOD = "btc-lightning-bolt11"

        /** Regtest invoice sentinel accepted by the test invoice parser. */
        const val INVOICE = "lnbcrt1example"

        /** Mainnet invoice sentinel for wrong-network checks. */
        const val MAINNET_INVOICE = "lnbc1example"

        /** Payment hash returned by the test invoice parser. */
        val HASH = "ab".repeat(32)

        fun payload(value: String) = "{\"value\":\"$value\"}"

        @Suppress("LongParameterList")
        fun receivedRequest(
            counterparty: String = BUYER,
            role: PaymentRequestLocalRole? = PaymentRequestLocalRole.PAYEE,
            state: PaymentRequestLifecycleState = PaymentRequestLifecycleState.PROOF_SUBMITTED,
            invalidReason: String? = null,
            asset: String = "btc",
            endpoints: Map<String, String>? = mapOf(METHOD to payload(ADDRESS)),
            accepted: List<String> = listOf(METHOD, BOLT11_METHOD),
            terms: PaymentRequestTerms? = PaymentRequestTerms(
                amount = PaymentRequestAmount("0.00015", asset), paymentReference = mock(),
                proposalExpiresAt = null, recurrence = null, acceptedPaymentEndpointIdentifiers = accepted,
                paymentEndpoints = endpoints, requiredAppId = "marketplace", conversion = null,
                paymentDeadline = null, metadata = mock(),
            ),
        ): PaymentRequestRecord = mock {
            on { this.counterparty }.thenReturn(counterparty)
            on { this.localRole }.thenReturn(role)
            on { this.state }.thenReturn(state)
            on { this.invalidReason }.thenReturn(invalidReason)
            on { this.terms }.thenReturn(terms)
            on { proposalAppId }.thenReturn("marketplace")
        }
    }

    @Test
    fun `shared app immutable address attributes received payment`() = withDecoder {
        val contacts = index(receivedRequest())
        assertEquals(setOf(BUYER), contacts.contactsForAddresses(listOf(ADDRESS)))
        assertTrue(contacts.contactsForAddresses(listOf(OTHER_ADDRESS)).isEmpty())
    }

    @Test
    fun `terminal request states retain exact destination attribution`() = withDecoder {
        for (state in PaymentRequestLifecycleState.entries.filterNot {
            it == PaymentRequestLifecycleState.INVALID_CONFLICT || it == PaymentRequestLifecycleState.UNKNOWN
        }) {
            assertEquals(setOf(BUYER), index(receivedRequest(state = state)).contactsForAddresses(listOf(ADDRESS)))
        }
    }

    @Test
    fun `payer unknown role and invalid records cannot attribute`() = withDecoder {
        val invalidRecords = listOf(
            receivedRequest(role = PaymentRequestLocalRole.PAYER),
            receivedRequest(role = null),
            receivedRequest(state = PaymentRequestLifecycleState.INVALID_CONFLICT),
            receivedRequest(state = PaymentRequestLifecycleState.UNKNOWN),
            receivedRequest(invalidReason = "conflict"),
            receivedRequest(counterparty = "invalid"),
            receivedRequest(counterparty = BUYER + "excess"),
            receivedRequest(asset = "usd"),
            receivedRequest(terms = null),
        )
        assertTrue(index(*invalidRecords.toTypedArray()).contactsForAddresses(listOf(ADDRESS)).isEmpty())
    }

    @Test
    fun `missing unaccepted malformed and wrong network endpoints cannot attribute`() = withDecoder {
        val records = listOf(
            receivedRequest(endpoints = null),
            receivedRequest(endpoints = emptyMap()),
            receivedRequest(accepted = emptyList()),
            receivedRequest(endpoints = mapOf(METHOD to "not json")),
            receivedRequest(endpoints = mapOf(METHOD to "{\"value\":\"\"}")),
            receivedRequest(endpoints = mapOf(METHOD to payload("malformed"))),
            receivedRequest(endpoints = mapOf("btc-bitcoin-p2wpkh" to payload(ADDRESS))),
            receivedRequest(endpoints = mapOf(METHOD to payload(MAINNET_ADDRESS))),
            receivedRequest(endpoints = mapOf(METHOD to payload(TESTNET_ADDRESS))),
        )
        assertTrue(
            index(*records.toTypedArray())
                .contactsForAddresses(listOf(ADDRESS, MAINNET_ADDRESS, TESTNET_ADDRESS)).isEmpty(),
        )
    }

    @Test
    fun `signet accepts testnet address encoding only with a signet endpoint`() = withDecoder {
        val identifier = "btc-signet-p2wpkh"
        val record = receivedRequest(
            accepted = listOf(identifier),
            endpoints = mapOf(identifier to payload(TESTNET_ADDRESS)),
        )
        val contacts = PaykitReceivedPaymentContacts.from(listOf(record), Network.SIGNET)
        assertEquals(setOf(BUYER), contacts.contactsForAddresses(listOf(TESTNET_ADDRESS)))
        assertTrue(index(record).contactsForAddresses(listOf(TESTNET_ADDRESS)).isEmpty())
    }

    @Test
    fun `regtest accepts network ambiguous legacy address encodings`() = withDecoder {
        for (address in LEGACY_ADDRESSES) {
            val identifier = if (address.startsWith("2")) "btc-regtest-p2sh" else "btc-regtest-p2pkh"
            val record = receivedRequest(
                accepted = listOf(identifier),
                endpoints = mapOf(identifier to payload(address)),
            )
            assertEquals(setOf(BUYER), index(record).contactsForAddresses(listOf(address)))
        }
    }

    @Test
    fun `normalized duplicate counterparties remain unique`() = withDecoder {
        val contacts = index(receivedRequest(), receivedRequest(counterparty = BUYER.removePrefix("pubky").uppercase()))
        assertEquals(setOf(BUYER), contacts.contactsForAddresses(listOf(ADDRESS, ADDRESS)))
    }

    @Test
    fun `same destination shared by different counterparties stays ambiguous`() = withDecoder {
        val contacts = index(receivedRequest(), receivedRequest(counterparty = OTHER_BUYER))
        assertEquals(setOf(BUYER, OTHER_BUYER), contacts.contactsForAddresses(listOf(ADDRESS)))
    }

    @Test
    fun `all output addresses contribute to ambiguity`() = withDecoder {
        val contacts = index(
            receivedRequest(),
            receivedRequest(counterparty = OTHER_BUYER, endpoints = mapOf(METHOD to payload(OTHER_ADDRESS))),
        )
        assertEquals(setOf(BUYER, OTHER_BUYER), contacts.contactsForAddresses(listOf(ADDRESS, OTHER_ADDRESS)))
    }

    @Test
    fun `validated expired bolt11 matches exact payment hash`() = withDecoder {
        val contacts = index(receivedRequest(endpoints = mapOf(BOLT11_METHOD to payload(INVOICE))))
        assertEquals(setOf(BUYER), contacts.contactsForPaymentHash(HASH.uppercase()))
        assertTrue(contacts.contactsForPaymentHash("cd".repeat(32)).isEmpty())
    }

    @Test
    fun `malformed or wrong network bolt11 cannot attribute`() = withDecoder {
        val contacts = index(
            receivedRequest(endpoints = mapOf(BOLT11_METHOD to payload("invalid-invoice"))),
            receivedRequest(endpoints = mapOf(BOLT11_METHOD to payload(MAINNET_INVOICE))),
        )
        assertTrue(contacts.contactsForPaymentHash(HASH).isEmpty())
    }

    @Test
    fun `proof hash alone cannot attribute without immutable request endpoints`() = withDecoder {
        val record = receivedRequest(endpoints = null)
        val proof = mock<PrivateJsonObject> {
            on { exportText() }.thenReturn("{\"payment_hash\":\"$HASH\",\"txid\":\"$HASH\"}")
        }
        val proofRecord = mock<PaymentProofRecord> { on { this.proof }.thenReturn(proof) }
        whenever(record.paymentProofs).thenReturn(listOf(proofRecord))

        assertTrue(index(record).contactsForPaymentHash(HASH).isEmpty())
        assertTrue(index(record).contactsForAddresses(listOf(ADDRESS)).isEmpty())
    }

    @Test
    fun `same invoice hash across contacts stays ambiguous`() = withDecoder {
        val contacts = index(
            receivedRequest(endpoints = mapOf(BOLT11_METHOD to payload(INVOICE))),
            receivedRequest(counterparty = OTHER_BUYER, endpoints = mapOf(BOLT11_METHOD to payload(INVOICE))),
        )
        assertEquals(setOf(BUYER, OTHER_BUYER), contacts.contactsForPaymentHash(HASH))
    }

    private fun index(vararg records: PaymentRequestRecord) =
        PaykitReceivedPaymentContacts.from(records.toList(), Network.REGTEST) { value ->
            require(value == INVOICE || value == MAINNET_INVOICE)
            mock<Bolt11Invoice> {
                on { currency() }.thenReturn(if (value == INVOICE) Currency.REGTEST else Currency.BITCOIN)
                on { paymentHash() }.thenReturn(HASH)
            }
        }

    private fun withDecoder(block: () -> Unit) {
        mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
            for (address in listOf(ADDRESS, OTHER_ADDRESS, MAINNET_ADDRESS, TESTNET_ADDRESS) + LEGACY_ADDRESSES) {
                val network = when (address) {
                    MAINNET_ADDRESS -> NetworkType.BITCOIN
                    TESTNET_ADDRESS, in LEGACY_ADDRESSES -> NetworkType.TESTNET
                    else -> NetworkType.REGTEST
                }
                native.`when`<ValidationResult> { validateBitcoinAddress(address) }.thenReturn(
                    ValidationResult(address, network, AddressType.UNKNOWN),
                )
            }
            block()
        }
    }
}
