package to.bitkit.nearby

import com.synonym.bitkitcore.validateBitcoinAddress
import to.bitkit.env.Env
import to.bitkit.models.toLdkNetwork
import to.bitkit.nfc.hasRequiredParam
import to.bitkit.nfc.scannedPaymentRequest
import to.bitkit.utils.NetworkValidationHelper

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GENERATOR = intArrayOf(0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3)
private const val BECH32_CHECKSUM_LENGTH = 6
private val BOLT11_NETWORKS = listOf("lnbcrt", "lntbs", "lnbc", "lntb")

/**
 * Whether [value] is a BOLT11 invoice: a mainnet, testnet, signet or regtest prefix the decoder reads as BOLT11, an
 * optional amount, the `1` separator and bech32 data whose checksum holds. A Lightning address or LNURL never
 * matches, and neither does a string that only looks like an invoice, which would fail to decode.
 */
internal fun isBolt11Invoice(value: String): Boolean {
    val lowercase = value.lowercase()
    val separator = lowercase.lastIndexOf('1')
    if (separator < 0) return false
    val hrp = lowercase.substring(0, separator)
    val network = BOLT11_NETWORKS.firstOrNull { hrp.startsWith(it) } ?: return false
    val amountAndUnit = hrp.removePrefix(network)
    val hasUnit = amountAndUnit.isNotEmpty() && amountAndUnit.last() in "munp"
    val amount = if (hasUnit) amountAndUnit.dropLast(1) else amountAndUnit
    if (!amount.all { it in '0'..'9' }) return false
    val data = lowercase.substring(separator + 1).map { BECH32_CHARSET.indexOf(it) }
    if (data.size < BECH32_CHECKSUM_LENGTH || data.any { it < 0 }) return false
    val values = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + data
    var checksum = 1
    for (value5 in values) {
        val top = checksum ushr 25
        checksum = (checksum and 0x1FFFFFF) shl 5 xor value5
        BECH32_GENERATOR.forEachIndexed { bit, generator ->
            if ((top shr bit) and 1 == 1) checksum = checksum xor generator
        }
    }
    return checksum == 1
}

private val BECH32_ADDRESS_PREFIXES = listOf("bc1", "tb1", "bcrt1")

/**
 * Whether a `bitcoin:` URI names a valid address for this wallet's network, checked as the scanner does, so it can
 * decode the request instead of resetting Send and closing on an error. Like the decoder, a bech32 address is
 * validated in lowercase and a base58 one in its own case, so [uri] keeps its case.
 */
private fun hasPayableAddress(uri: String): Boolean {
    val address = uri.substringAfter(':').substringBefore('?')
    if (address.isEmpty()) return false
    val lowercase = address.lowercase()
    val normalized = if (BECH32_ADDRESS_PREFIXES.any(lowercase::startsWith)) lowercase else address
    val network = runCatching { validateBitcoinAddress(normalized).network }.getOrNull() ?: return false
    return !NetworkValidationHelper.isNetworkMismatch(network.toLdkNetwork(), Env.network)
}

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
    val trimmed = request.trim()
    val lowercase = trimmed.lowercase()
    return when {
        // No Bitkit flow fulfils a required (`req-`) parameter, and the decoder ignores one next to an address.
        hasRequiredParam(lowercase) -> false
        // The app decodes a unified URI's Lightning request, and decoding an LNURL contacts its server.
        // Every Lightning value is checked, since the decoder keeps the last one.
        lowercase.startsWith("bitcoin:") ->
            hasPayableAddress(trimmed) && lightningParams(lowercase).all(::isBolt11Invoice)

        lowercase.startsWith("ethereum:") -> true
        lowercase.startsWith("lightning:") -> isBolt11Invoice(lowercase.removePrefix("lightning:"))

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
