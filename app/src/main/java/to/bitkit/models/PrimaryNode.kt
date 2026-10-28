package to.bitkit.models

import androidx.compose.runtime.Immutable
import org.bouncycastle.asn1.sec.SECNamedCurves
import to.bitkit.ext.fromHex
import to.bitkit.utils.AppError
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

@Immutable
data class PrimaryNode(
    val nodeId: String,
    val address: String? = null,
) {
    val uri: String get() = address?.let { "$nodeId@$it" } ?: nodeId

    companion object {
        private val publicKeyPattern = Regex("^(02|03)[0-9a-fA-F]{64}$")
        private val hostnamePattern = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?$")
        private val curve = SECNamedCurves.getByName("secp256k1").curve

        fun parse(input: String): PrimaryNode {
            val value = input.trim()
                .replaceFirst(Regex("^(?:lightning:/{0,2}|node://)", RegexOption.IGNORE_CASE), "")
            val parts = value.split('@')
            if (parts.size !in 1..2 || value.any(Char::isWhitespace)) throw PrimaryNodeError.InvalidInput()
            val nodeId = parts.first().lowercase(Locale.ROOT)
            if (!publicKeyPattern.matches(nodeId) || !validPublicKey(nodeId)) throw PrimaryNodeError.InvalidInput()
            val address = parts.getOrNull(1)?.let(::parseAddress)
            return PrimaryNode(nodeId, address)
        }

        private fun validPublicKey(value: String): Boolean = runCatching {
            val point = curve.decodePoint(value.fromHex())
            point.isValid && !point.isInfinity
        }.getOrDefault(false)

        private fun parseAddress(value: String): String {
            if (value.startsWith("iroh:", ignoreCase = true)) return IrohPrimaryEndpoint.parse(value).address
            val endpoint = runCatching { URI("tcp://$value") }.getOrNull()
                ?: throw PrimaryNodeError.InvalidInput()
            val host = endpoint.host ?: throw PrimaryNodeError.InvalidInput()
            if (endpoint.port !in 1..65535 || endpoint.userInfo != null ||
                !endpoint.path.isNullOrEmpty() || endpoint.query != null || endpoint.fragment != null ||
                (!host.startsWith('[') && !hostnamePattern.matches(host))
            ) {
                throw PrimaryNodeError.InvalidInput()
            }
            return "${host.lowercase(Locale.ROOT)}:${endpoint.port}"
        }
    }
}

data class IrohPrimaryEndpoint(val endpointId: String, val relayUrl: String) {
    val address: String get() = "iroh:$endpointId?relay=${URLEncoder.encode(relayUrl, "UTF-8")}"

    companion object {
        private val addressPattern = Regex("^iroh:([0-9a-f]{64})\\?relay=([^&#]+)$", RegexOption.IGNORE_CASE)

        fun parse(value: String): IrohPrimaryEndpoint {
            val match = addressPattern.matchEntire(value) ?: throw PrimaryNodeError.InvalidInput()
            val relay = runCatching { URLDecoder.decode(match.groupValues[2], "UTF-8") }
                .getOrElse { throw PrimaryNodeError.InvalidInput() }
            val uri = runCatching { URI(relay) }.getOrElse { throw PrimaryNodeError.InvalidInput() }
            val validPort = uri.port == -1 || uri.port in 1..65535
            val cleanOrigin = uri.userInfo == null && uri.query == null && uri.fragment == null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || !cleanOrigin || uri.path !in listOf("", "/") ||
                !validPort || relay.any(Char::isWhitespace)
            ) {
                throw PrimaryNodeError.InvalidInput()
            }
            return IrohPrimaryEndpoint(match.groupValues[1].lowercase(Locale.ROOT), relay)
        }
    }
}

sealed class PrimaryNodeError(message: String) : AppError(message) {
    class InvalidInput : PrimaryNodeError(
        "Enter a valid Lightning node public key, TCP URI, or Iroh URI with an HTTPS relay.",
    )
    class AddressUnknown : PrimaryNodeError("No address is known for this node. A full node URI is required.")
    class ConnectionInUse : PrimaryNodeError("The current primary connection must be closed before changing nodes.")
}
