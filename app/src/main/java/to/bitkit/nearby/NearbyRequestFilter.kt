package to.bitkit.nearby

import to.bitkit.nfc.scannedPaymentRequest

private val LIGHTNING_INVOICE_PREFIXES = listOf("lnbc", "lntb", "lnbcrt", "lnsb")

/**
 * Whether [request], read from a nearby device over Bluetooth, is a payment request Bitkit serves:
 * a BIP21 `bitcoin:` URI with an address, a `lightning:` BOLT11 invoice, or a USDT `ethereum:` request.
 *
 * Any device in range can answer a scan, so everything else the scanner understands (Pubky auth,
 * LNURL-auth/withdraw/channel, wallet connection setups, contact keys) is refused for this source.
 * So is what the scanner cannot pay, such as a BOLT12 offer or a `bitcoin:` URI left without an address,
 * since accepting it would close the scanner on an error, and a `bitcoin:` URI whose Lightning request is not a
 * BOLT11 invoice, since decoding an LNURL there would contact a server no one chose.
 */
fun isNearbyPaymentRequest(request: String): Boolean {
    val lowercase = request.trim().lowercase()
    return when {
        lowercase.startsWith("bitcoin:?") -> false
        // The app decodes a unified URI's Lightning request, and decoding an LNURL contacts its server.
        lowercase.startsWith("bitcoin:") -> lightningParam(lowercase)
            ?.let { invoice -> LIGHTNING_INVOICE_PREFIXES.any { invoice.startsWith(it) } } ?: true

        lowercase.startsWith("ethereum:") -> true
        lowercase.startsWith("lightning:") -> {
            val invoice = lowercase.removePrefix("lightning:")
            LIGHTNING_INVOICE_PREFIXES.any { invoice.startsWith(it) }
        }

        else -> false
    }
}

private fun lightningParam(uri: String): String? = uri.substringAfter('?', missingDelimiterValue = "")
    .split('&')
    .firstOrNull { it.startsWith("lightning=") }
    ?.substringAfter('=')

/**
 * The payment request to hand to the scanner for [request] read over Bluetooth, or null when it is refused.
 * The request is rewritten before the check, so a wrapped value such as `bitcoin:?lightning=<LNURL-auth>`
 * is judged by what the scanner will actually receive.
 */
fun nearbyPaymentRequest(request: String): String? =
    scannedPaymentRequest(request).takeIf(::isNearbyPaymentRequest)
