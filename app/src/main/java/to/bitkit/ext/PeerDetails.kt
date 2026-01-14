package to.bitkit.ext

import com.synonym.bitkitcore.ILspNode
import org.lightningdevkit.ldknode.PeerDetails

val PeerDetails.host get() = address.substringBefore(":")

val PeerDetails.port get() = address.substringAfter(":")

val PeerDetails.uri get() = "$nodeId@$address"

/** Creates a [PeerDetails] object from a URI string.*/
fun PeerDetails.Companion.of(uri: String): PeerDetails {
    val parts = uri.split("@")
    require(parts.size == 2) { "Invalid uri format, expected: '<nodeId>@<host>:<port>', got: '$uri'" }

    val nodeId = parts[0]

    val addressParts = parts[1].split(":")
    require(addressParts.size == 2) { "Invalid uri format, expected: '<nodeId>@<host>:<port>', got: '$uri'" }

    val host = addressParts[0]
    val port = addressParts[1]
    val address = "$host:$port"

    return PeerDetails(
        nodeId = nodeId,
        address = address,
        isConnected = false,
        isPersisted = false,
    )
}

/** Creates a [PeerDetails] object from a node ID, host, and port.*/
fun PeerDetails.Companion.of(nodeId: String, host: String, port: String) = PeerDetails(
    nodeId = nodeId,
    address = "$host:$port",
    isConnected = false,
    isPersisted = false,
)

fun ILspNode.toPeerDetails(): PeerDetails? {
    val connectionString = connectionStrings.firstOrNull() ?: return null

    // Connection string can be either "host:port" or "nodeId@host:port"
    // Extract just the "host:port" part
    val address = if (connectionString.contains("@")) {
        connectionString.substringAfter("@")
    } else {
        connectionString
    }

    return PeerDetails(
        nodeId = pubkey,
        address = address,
        isConnected = false,
        isPersisted = false,
    )
}

fun List<ILspNode>.toPeerDetailsList(): List<PeerDetails> = mapNotNull { it.toPeerDetails() }
