package to.bitkit.nfc

import java.math.BigDecimal

private val URI_SCHEMES = listOf("bitcoin:", "lightning:", "ethereum:", "lnurlp:", "lnurlw:", "lnurlc:")
private val LIGHTNING_PREFIXES = listOf("lnbc", "lntb", "lntbs", "lnbcrt", "lnsb", "lno1", "lnurl")

/**
 * Turns a receive request into the URI written to an NFC tag.
 *
 * A tag needs a scheme for other wallets (and Android's tag dispatch) to recognise the request,
 * so a bare BOLT11 invoice, offer or LNURL gets `lightning:` and a bare address gets `bitcoin:`.
 * Requests that already carry a payment scheme are returned unchanged.
 */
fun nfcPaymentUri(request: String?): String? {
    val trimmed = request?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val lowercase = trimmed.lowercase()
    return when {
        URI_SCHEMES.any { lowercase.startsWith(it) } -> trimmed
        LIGHTNING_PREFIXES.any { lowercase.startsWith(it) } -> "lightning:$trimmed"
        else -> "bitcoin:$trimmed"
    }
}

private const val BITCOIN_WITHOUT_ADDRESS = "bitcoin:?"
private const val REQUIRED_PARAM_PREFIX = "req-"

/**
 * Keys BIP 321 ("Query Keys", "Invalid URIs") forbids repeating; `pop` and `req-pop` count as the same key.
 * Unknown optional keys and payment instructions may repeat.
 */
private val SINGLETON_KEYS = setOf("amount", "label", "message", "pop")

/**
 * Turns a payment request read from a tag or a nearby device into one the scanner decodes.
 *
 * A BIP 321 URI may leave the address out and carry only a Lightning request
 * (`bitcoin:?lightning=<invoice>`, used by Numo and planned by Square). The scanner rejects a
 * `bitcoin:` URI without an address, so that form becomes `lightning:<invoice>`. A URI with a
 * required (`req-`) parameter is kept, since BIP 321 forbids paying it without handling that
 * parameter and the rewrite would drop it. So is a URI that repeats one of [SINGLETON_KEYS], which BIP 321
 * makes invalid; one with more than one `lightning` value, which Bitkit does not support, since the rewrite would
 * have to pick one; and one whose `amount` the invoice does not carry: the invoice alone could not ask for it.
 * Anything else is returned trimmed and unchanged.
 */
fun scannedPaymentRequest(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith(BITCOIN_WITHOUT_ADDRESS, ignoreCase = true)) return trimmed
    val pairs = trimmed.substring(BITCOIN_WITHOUT_ADDRESS.length).split('&')
    // Checked on every key, since BIP 321 allows a required key with an empty or no value.
    if (pairs.any { it.substringBefore('=').lowercase().startsWith(REQUIRED_PARAM_PREFIX) }) return trimmed
    val keys = pairs.map { it.substringBefore('=').lowercase().removePrefix(REQUIRED_PARAM_PREFIX) }
    val singletons = keys.filter { it in SINGLETON_KEYS }
    if (singletons.size != singletons.toSet().size) return trimmed
    if (keys.count { it == "lightning" } > 1) return trimmed
    val params = pairs.mapNotNull {
        val key = it.substringBefore('=', missingDelimiterValue = "")
        val value = it.substringAfter('=', missingDelimiterValue = "")
        if (key.isEmpty() || value.isEmpty()) null else key.lowercase() to value
    }.toMap()
    // A BOLT12 offer (`lno`) is left as is: the scanner cannot pay one yet.
    val request = params["lightning"] ?: return trimmed
    val amount = params["amount"]
    if (amount != null && !isBolt11Amount(request, amount)) return trimmed
    return "lightning:$request"
}

/** BIP 321 amount syntax: decimal BTC, with no sign, exponent or unit. */
private val BIP321_AMOUNT = Regex("^(\\d+(\\.\\d*)?|\\.\\d+)$")
private val BOLT11_HRP = Regex("^ln[a-z]+?(\\d+)([munp]?)$")
private val BTC_PER_UNIT = mapOf(
    "" to BigDecimal.ONE,
    "m" to BigDecimal("0.001"),
    "u" to BigDecimal("0.000001"),
    "n" to BigDecimal("0.000000001"),
    "p" to BigDecimal("0.000000000001"),
)

/** Whether [invoice] is a BOLT11 invoice for exactly [amountBtc], read from its human-readable part. */
private fun isBolt11Amount(invoice: String, amountBtc: String): Boolean {
    val hrp = invoice.lowercase().substringBeforeLast('1', missingDelimiterValue = "")
    val match = BOLT11_HRP.matchEntire(hrp) ?: return false
    val (digits, unit) = match.destructured
    val invoiceBtc = BigDecimal(digits).multiply(BTC_PER_UNIT.getValue(unit))
    if (!BIP321_AMOUNT.matches(amountBtc)) return false
    val requested = amountBtc.toBigDecimal()
    return invoiceBtc.compareTo(requested) == 0
}
