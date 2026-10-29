package to.bitkit.models

import androidx.compose.runtime.Immutable
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.env.Env
import to.bitkit.ext.fromHex
import to.bitkit.utils.AppError
import java.net.URI
import java.util.Locale

/**
 * The Lightning node that serves this wallet: it opens a channel just in time for the first receive,
 * grows it by splicing, and is the wallet's only Lightning peer.
 */
@Immutable
data class PrimaryNode(
    val nodeId: String,
    val address: String? = null,
) {
    val uri: String get() = address?.let { "$nodeId@$it" } ?: nodeId

    companion object {
        private val publicKeyPattern = Regex("^(02|03)[0-9a-fA-F]{64}$")
        private val hostnamePattern = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?$")
        private val uriPrefix = Regex("^(?:lightning:/{0,2}|node://)", RegexOption.IGNORE_CASE)
        private val curve = SECNamedCurves.getByName("secp256k1").curve

        /** Parses a `pubkey` or `pubkey@host:port` node URI, as pasted or scanned. */
        fun parse(input: String): PrimaryNode {
            val value = input.trim().replaceFirst(uriPrefix, "")
            val parts = value.split('@')
            if (parts.size !in 1..2 || value.any(Char::isWhitespace)) throw PrimaryNodeError.InvalidInput()
            val nodeId = parts.first().lowercase(Locale.ROOT)
            if (!publicKeyPattern.matches(nodeId) || !isValidPublicKey(nodeId)) throw PrimaryNodeError.InvalidInput()
            return PrimaryNode(nodeId, parts.getOrNull(1)?.let(::parseAddress))
        }

        fun parseOrNull(input: String): PrimaryNode? = runCatching { parse(input) }.getOrNull()

        /** The primary the wallet uses: the paired one, else the network's default. */
        fun effective(savedUri: String): PrimaryNode? = parseOrNull(savedUri.ifBlank { Env.defaultPrimaryNodeUri })

        private fun isValidPublicKey(value: String): Boolean = runCatching {
            val point = curve.decodePoint(value.fromHex())
            point.isValid && !point.isInfinity
        }.getOrDefault(false)

        private fun parseAddress(value: String): String {
            val endpoint = runCatching { URI("tcp://$value") }.getOrNull() ?: throw PrimaryNodeError.InvalidInput()
            val host = endpoint.host ?: throw PrimaryNodeError.InvalidInput()
            val isClean = endpoint.userInfo == null && endpoint.path.isNullOrEmpty() &&
                endpoint.query == null && endpoint.fragment == null
            if (endpoint.port !in 1..MAX_VALID_PORT || !isClean ||
                (!host.startsWith('[') && !hostnamePattern.matches(host))
            ) {
                throw PrimaryNodeError.InvalidInput()
            }
            return "${host.lowercase(Locale.ROOT)}:${endpoint.port}"
        }
    }
}

fun PrimaryNode.toPeerDetails(): PeerDetails? = address?.let {
    PeerDetails(nodeId = nodeId, address = it, isConnected = false, isPersisted = true)
}

sealed class PrimaryNodeError(message: String) : AppError(message) {
    class InvalidInput : PrimaryNodeError("Enter a valid Lightning node public key or publickey@host:port URI.")
    class AddressUnknown : PrimaryNodeError("No address is known for this node. A full node URI is required.")
    class ConnectionInUse : PrimaryNodeError("Close the channel with the current primary node before changing it.")
    class NotConfigured : PrimaryNodeError("No primary node is set.")
}
