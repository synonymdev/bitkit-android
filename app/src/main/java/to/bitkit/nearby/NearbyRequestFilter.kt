package to.bitkit.nearby

import to.bitkit.nfc.hasRequiredParam
import to.bitkit.nfc.scannedPaymentRequest

/**
 * A BOLT11 invoice by its bech32 shape: a mainnet, testnet, signet or regtest prefix the decoder reads as BOLT11
 * (`lnbc`, `lntb`, `lntbs`, `lnbcrt`), then the `1` separator and bech32 data. A Lightning address or LNURL never
 * matches, since `@`, `.` and `:` are not bech32 characters and `lnurl` is not one of the prefixes.
 */
private val BOLT11_INVOICE = Regex("^ln(bc|tb)[a-z0-9]*1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+$")

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
        // No Bitkit flow fulfils a required (`req-`) parameter, and the decoder ignores one next to an address.
        hasRequiredParam(lowercase) -> false
        lowercase.startsWith("bitcoin:?") -> false
        // The app decodes a unified URI's Lightning request, and decoding an LNURL contacts its server.
        // Every Lightning value is checked, since the decoder keeps the last one.
        lowercase.startsWith("bitcoin:") -> lightningParams(lowercase).all(BOLT11_INVOICE::matches)

        lowercase.startsWith("ethereum:") -> true
        lowercase.startsWith("lightning:") -> BOLT11_INVOICE.matches(lowercase.removePrefix("lightning:"))

        else -> false
    }
}

private fun lightningParams(uri: String): List<String> = uri.substringAfter('?', missingDelimiterValue = "")
    .split('&')
    .filter { it.startsWith("lightning=") }
    .map { it.substringAfter('=') }

/**
 * The payment request to hand to the scanner for [request] read over Bluetooth, or null when it is refused.
 * The request is rewritten before the check, so a wrapped value such as `bitcoin:?lightning=<LNURL-auth>`
 * is judged by what the scanner will actually receive.
 */
fun nearbyPaymentRequest(request: String): String? =
    scannedPaymentRequest(request).takeIf(::isNearbyPaymentRequest)
