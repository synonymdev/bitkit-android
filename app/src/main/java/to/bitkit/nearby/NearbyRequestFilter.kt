package to.bitkit.nearby

private val LIGHTNING_INVOICE_PREFIXES = listOf("lnbc", "lntb", "lnbcrt", "lnsb", "lno1")

/**
 * Whether [request], read from a nearby device over Bluetooth, is a payment request Bitkit serves:
 * a BIP21 `bitcoin:` URI, a `lightning:` BOLT11 invoice or BOLT12 offer, or a USDT `ethereum:` request.
 *
 * Any device in range can answer a scan, so everything else the scanner understands (Pubky auth,
 * LNURL-auth/withdraw/channel, wallet connection setups, contact keys) is refused for this source.
 */
fun isNearbyPaymentRequest(request: String): Boolean {
    val lowercase = request.trim().lowercase()
    return when {
        lowercase.startsWith("bitcoin:") || lowercase.startsWith("ethereum:") -> true
        lowercase.startsWith("lightning:") -> {
            val invoice = lowercase.removePrefix("lightning:")
            LIGHTNING_INVOICE_PREFIXES.any { invoice.startsWith(it) }
        }

        else -> false
    }
}
