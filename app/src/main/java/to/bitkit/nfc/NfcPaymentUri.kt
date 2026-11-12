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
