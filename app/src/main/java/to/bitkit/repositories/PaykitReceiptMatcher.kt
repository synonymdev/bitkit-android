package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Network
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PubkyPublicKeyFormat
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A payment this wallet recorded as received, which a receipt can name. */
internal data class PaykitRecordedReceive(
    /** Lowercase payment hash for Lightning, transaction id for on-chain and USDT. */
    val paymentId: String,
    val rail: PaykitReceiptRail,
    val amount: PaykitAmount,
    /** The receiving address of an on-chain receive. */
    val address: String? = null,
    /** What the activity row and received sheet know the payment by: its hash, txid or USDT transfer id. */
    val statusKey: String = paymentId,
    /** When the wallet recorded the receive, in seconds since the epoch. */
    val receivedAt: ULong? = null,
    /** The contact the wallet attributed the receive to. */
    val contact: String? = null,
)

enum class PaykitReceiptRail { Lightning, Onchain, Usdt }

/** A request the wallet issued that a recorded receive paid, ready to receipt. */
internal data class PaykitReceiptCandidate(
    val counterparty: String,
    val paymentRequestId: String,
    val paymentReference: String,
    val billingPeriod: BillingPeriod?,
    val paymentEndpointIdentifier: String,
    val receive: PaykitRecordedReceive,
) {
    /** Stable across launches, so a catch-up or retry finds the receipt it issued before. */
    val receiptId: String
        get() = paykitReceiptId(counterparty, paymentRequestId, billingPeriod?.startsAt, receive.paymentId)

    /** The paid payment's hash or transaction id, for the Market to match. */
    val metadataJson: String
        get() = when (receive.rail) {
            PaykitReceiptRail.Lightning -> """{"payment_hash":"${receive.paymentId}"}"""
            PaykitReceiptRail.Onchain, PaykitReceiptRail.Usdt -> """{"txid":"${receive.paymentId}"}"""
        }
}

/** A USDT payment the payee verified on chain for one of its requests. */
internal data class PaykitVerifiedUsdtReceive(
    val requestId: PaykitPaymentRequestId,
    val proofEventId: String,
    val transferId: String,
    val amount: PaykitAmount,
)

/**
 * Pairs the requests this wallet issued with the payments it recorded for them: the payer's proof names the request
 * and the payment, or the request's own invoice or address received it. A proof alone is only the payer's claim, so
 * nothing is receipted until the money is recorded here, after the request was proposed and, when the wallet knows
 * the payer, from that counterparty; a payment two requests still claim is left alone.
 */
internal object PaykitReceiptMatcher {
    fun candidates(
        records: List<PaymentRequestRecord>,
        lightningReceives: Map<String, PaykitRecordedReceive>,
        onchainReceives: List<PaykitRecordedReceive>,
        usdtReceives: List<PaykitVerifiedUsdtReceive>,
        network: Network,
        parseInvoice: (String) -> Bolt11Invoice = Bolt11Invoice::fromStr,
    ): List<PaykitReceiptCandidate> {
        val onchainByTxid = onchainReceives.associateBy { it.paymentId }
        val found = mutableListOf<PaykitReceiptCandidate>()
        for (record in records.filter(::isReceiptable)) {
            val reference = record.terms?.paymentReference?.exportText() ?: continue
            fun candidate(endpoint: String, period: BillingPeriod?, receive: PaykitRecordedReceive) =
                PaykitReceiptCandidate(
                    record.counterparty,
                    record.paymentRequestId,
                    reference,
                    period,
                    endpoint,
                    receive
                )

            found += record.paymentProofs.mapNotNull { proof ->
                val receive = proof.bitcoinPaymentId()?.let { (rail, id) ->
                    if (rail == PaykitReceiptRail.Lightning) lightningReceives[id] else onchainByTxid[id]
                }
                receive?.takeIf { record.couldBePaidBy(it) }
                    ?.let { candidate(proof.paymentEndpointIdentifier, proof.billingPeriod, it) }
            }
            if (record.terms?.recurrence == null) {
                found += embeddedEndpointReceives(record, lightningReceives, onchainReceives, network, parseInvoice)
                    .filter { (_, receive) -> record.couldBePaidBy(receive) }
                    .map { (endpoint, receive) -> candidate(endpoint, null, receive) }
            }
            found += usdtReceives.filter { it.matches(record) }.mapNotNull { usdt ->
                val proof = record.paymentProofs.firstOrNull { it.eventId == usdt.proofEventId }
                val hash = proof?.proofValue("transaction_hash")?.lowercase() ?: return@mapNotNull null
                val receive = PaykitRecordedReceive(
                    paymentId = hash,
                    rail = PaykitReceiptRail.Usdt,
                    amount = usdt.amount,
                    statusKey = usdt.transferId,
                )
                candidate(proof.paymentEndpointIdentifier, proof.billingPeriod, receive)
            }
        }
        return found
            .distinctBy {
                listOf(
                    it.counterparty,
                    it.paymentRequestId,
                    it.billingPeriod?.startsAt,
                    it.receive.paymentId
                )
            }
            .groupBy { it.receive.paymentId }
            .values
            .mapNotNull { it.singleOrNull() }
    }

    private fun PaymentRequestRecord.couldBePaidBy(receive: PaykitRecordedReceive): Boolean {
        if (receive.contact != null && !PubkyPublicKeyFormat.matches(receive.contact, counterparty)) return false
        val receivedAt = receive.receivedAt ?: return true
        val recordedAt = proposalRecordedAt ?: return true
        val proposedAt = runCatching { Instant.parse(recordedAt) }.getOrNull() ?: return false
        return Instant.fromEpochSeconds(receivedAt.toLong()) >= proposedAt - RECEIVE_TIME_TOLERANCE
    }

    private fun isReceiptable(record: PaymentRequestRecord): Boolean =
        record.localRole == PaymentRequestLocalRole.PAYEE &&
            record.invalidReason == null &&
            record.state != PaymentRequestLifecycleState.INVALID_CONFLICT &&
            record.state != PaymentRequestLifecycleState.UNKNOWN

    private fun embeddedEndpointReceives(
        record: PaymentRequestRecord,
        lightningReceives: Map<String, PaykitRecordedReceive>,
        onchainReceives: List<PaykitRecordedReceive>,
        network: Network,
        parseInvoice: (String) -> Bolt11Invoice,
    ): List<Pair<String, PaykitRecordedReceive>> {
        val terms = record.terms ?: return emptyList()
        return terms.paymentEndpoints.orEmpty()
            .filterKeys(terms.acceptedPaymentEndpointIdentifiers::contains)
            .flatMap { (identifier, payload) ->
                val endpoint = PublicPaykitRepo.parseEndpoint(identifier, payload, network)
                    ?: return@flatMap emptyList()
                when {
                    endpoint.methodId == MethodId.Bolt11 -> listOfNotNull(
                        runCatching { parseInvoice(endpoint.value).paymentHash().lowercase() }.getOrNull()
                            ?.let(lightningReceives::get)
                    )
                    endpoint.methodId.isOnchain -> onchainReceives.filter {
                        it.address.equals(endpoint.value, ignoreCase = true)
                    }
                    else -> emptyList()
                }.map { identifier to it }
            }
    }

    private fun PaykitVerifiedUsdtReceive.matches(record: PaymentRequestRecord): Boolean =
        requestId.paymentRequestId == record.paymentRequestId && requestId.counterparty == record.counterparty

    private fun PaymentProofRecord.proofValue(key: String): String? = runCatching {
        Json.parseToJsonElement(proof.exportText()).jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun PaymentProofRecord.bitcoinPaymentId(): Pair<PaykitReceiptRail, String>? {
        proofValue("txid")?.lowercase()?.takeIf { runCatching { it.fromHex() }.getOrNull()?.size == HASH_BYTES }?.let {
            return PaykitReceiptRail.Onchain to it
        }
        val data = proofValue("data")?.lowercase() ?: return null
        val bytes = runCatching { data.fromHex() }.getOrNull()?.takeIf { it.size == HASH_BYTES } ?: return null
        return when (proofValue("type")) {
            PaykitPaymentProofKind.Lightning.type ->
                PaykitReceiptRail.Lightning to MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
            PaykitPaymentProofKind.Onchain.type -> PaykitReceiptRail.Onchain to data
            else -> null
        }
    }

    /** Byte length of a Lightning preimage and of a Bitcoin transaction id. */
    private const val HASH_BYTES = 32

    /** How far a receive's recorded time may precede its request's proposal, for block-time and clock skew. */
    private val RECEIVE_TIME_TOLERANCE = 2.hours
}

internal fun bitcoinReceive(
    paymentId: String,
    rail: PaykitReceiptRail,
    sats: ULong,
    address: String? = null,
    receivedAt: ULong? = null,
    contact: String? = null,
) = PaykitRecordedReceive(
    paymentId = paymentId.lowercase(),
    rail = rail,
    amount = PaykitAmount(PaykitAsset.BTC, sats),
    address = address,
    receivedAt = receivedAt,
    contact = contact,
)

/** A UUID v4 derived from the receipt's request and payment, as Paykit requires a v4 Receipt ID. */
internal fun paykitReceiptId(
    counterparty: String,
    paymentRequestId: String,
    billingPeriodStartsAt: String?,
    paymentId: String,
): String {
    val seed = listOf("bitkit-receipt", counterparty, paymentRequestId, billingPeriodStartsAt.orEmpty(), paymentId)
        .joinToString("|")
    val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).copyOf(UUID_BYTES)
    bytes[VERSION_BYTE] = (bytes[VERSION_BYTE].toInt() and 0x0f or 0x40).toByte()
    bytes[VARIANT_BYTE] = (bytes[VARIANT_BYTE].toInt() and 0x3f or 0x80).toByte()
    val hex = bytes.toHex()
    return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.drop(20)}"
        .also { UUID.fromString(it) }
}

/** Bytes in a UUID. */
private const val UUID_BYTES = 16

/** Byte holding the UUID version nibble. */
private const val VERSION_BYTE = 6

/** Byte holding the UUID variant bits. */
private const val VARIANT_BYTE = 8
