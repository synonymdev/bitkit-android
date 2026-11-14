package to.bitkit.nfc

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
private val LIGHTNING_PARAMS = listOf("lightning", "lno")

/**
 * Turns a payment request read from a tag or a nearby device into one the scanner decodes.
 *
 * A BIP 321 URI may leave the address out and carry only a Lightning request
 * (`bitcoin:?lightning=<invoice>`, used by Numo and planned by Square). The scanner rejects a
 * `bitcoin:` URI without an address, so that form becomes `lightning:<invoice>`. Anything else is
 * returned trimmed and unchanged.
 */
fun scannedPaymentRequest(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith(BITCOIN_WITHOUT_ADDRESS, ignoreCase = true)) return trimmed
    val params = trimmed.substring(BITCOIN_WITHOUT_ADDRESS.length).split('&').mapNotNull {
        val key = it.substringBefore('=', missingDelimiterValue = "")
        val value = it.substringAfter('=', missingDelimiterValue = "")
        if (key.isEmpty() || value.isEmpty()) null else key.lowercase() to value
    }.toMap()
    val request = LIGHTNING_PARAMS.firstNotNullOfOrNull { params[it] } ?: return trimmed
    return "lightning:$request"
}
