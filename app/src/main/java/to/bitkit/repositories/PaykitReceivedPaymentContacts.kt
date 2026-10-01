package to.bitkit.repositories

import com.synonym.bitkitcore.validateBitcoinAddress
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Currency
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.toLdkNetwork

@ConsistentCopyVisibility
internal data class PaykitReceivedPaymentContacts private constructor(
    private val addresses: Map<String, Set<String>>,
    private val paymentHashes: Map<String, Set<String>>,
) {
    companion object {
        val Empty = PaykitReceivedPaymentContacts(emptyMap(), emptyMap())

        fun from(
            records: List<PaymentRequestRecord>,
            network: Network,
            parseInvoice: (String) -> Bolt11Invoice = Bolt11Invoice::fromStr,
        ): PaykitReceivedPaymentContacts {
            val addresses = mutableMapOf<String, MutableSet<String>>()
            val hashes = mutableMapOf<String, MutableSet<String>>()
            for (record in records) {
                val contact = validCounterparty(record) ?: continue
                val terms = checkNotNull(record.terms)
                // Use immutable request destinations for attribution, not proofs or current lists.
                val acceptedEndpoints = terms.paymentEndpoints.orEmpty()
                    .filterKeys(terms.acceptedPaymentEndpointIdentifiers::contains)
                for ((identifier, payload) in acceptedEndpoints) {
                    val endpoint = PublicPaykitRepo.parseEndpoint(identifier, payload, network) ?: continue
                    val value = endpoint.value
                    runCatching {
                        when {
                            endpoint.methodId.isOnchain && isValidAddress(value, network) -> {
                                addresses.getOrPut(value) { mutableSetOf() }.add(contact)
                            }
                            endpoint.methodId == MethodId.Bolt11 -> {
                                val invoice = parseInvoice(value)
                                if (invoice.currency() == currency(network)) {
                                    hashes.getOrPut(invoice.paymentHash()) { mutableSetOf() }.add(contact)
                                }
                            }
                        }
                    }
                }
            }
            return if (addresses.isEmpty() && hashes.isEmpty()) {
                Empty
            } else {
                PaykitReceivedPaymentContacts(addresses, hashes)
            }
        }

        private fun validCounterparty(record: PaymentRequestRecord): String? {
            if (record.localRole != PaymentRequestLocalRole.PAYEE || record.invalidReason != null) return null
            if (record.state == PaymentRequestLifecycleState.INVALID_CONFLICT ||
                record.state == PaymentRequestLifecycleState.UNKNOWN
            ) {
                return null
            }
            if (record.terms?.amount?.asset != PaykitIssuerInterop.BITCOIN_ASSET) return null
            if (record.counterparty.trim().length > PubkyPublicKeyFormat.maximumInputLength) return null
            return PubkyPublicKeyFormat.normalized(record.counterparty)
        }

        private fun isValidAddress(value: String, network: Network): Boolean {
            val addressNetwork = validateBitcoinAddress(value).network.toLdkNetwork()
            if (addressNetwork == network) return true
            if (addressNetwork != Network.TESTNET) return false
            return network == Network.SIGNET ||
                (network == Network.REGTEST && value.firstOrNull() in listOf('2', 'm', 'n'))
        }

        private fun currency(network: Network): Currency = when (network) {
            Network.BITCOIN -> Currency.BITCOIN
            Network.TESTNET -> Currency.BITCOIN_TESTNET
            Network.SIGNET -> Currency.SIGNET
            Network.REGTEST -> Currency.REGTEST
        }
    }

    fun contactsForAddresses(values: Collection<String>): Set<String> =
        values.flatMapTo(mutableSetOf()) { addresses[it].orEmpty() }

    fun contactsForPaymentHash(value: String): Set<String> = paymentHashes[value.lowercase()].orEmpty()
}
