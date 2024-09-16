package to.bitkit.env

import org.lightningdevkit.ldknode.PeerDetails

data class LnPeer(
    val nodeId: String,
    val host: String,
    val port: String,
    val isConnected: Boolean = false,
    val isPersisted: Boolean = false,
) {
    constructor(
        nodeId: String,
        address: String,
        isConnected: Boolean = false,
        isPersisted: Boolean = false,
    ) : this(
        nodeId,
        address.substringBefore(":"),
        address.substringAfter(":"),
        isConnected,
        isPersisted,
    )

    val address get() = "$host:$port"
    override fun toString() = "$nodeId@${address}"

    companion object {
        fun PeerDetails.toLnPeer() = LnPeer(
            nodeId = nodeId,
            address = address,
            isConnected = isConnected,
            isPersisted = isPersisted,
        )
    }
}

internal object LnPeers {
    private const val HOST = "10.0.2.2"

    val remote = LnPeer(
        nodeId = "033f4d3032ce7f54224f4bd9747b50b7cd72074a859758e40e1ca46ffa79a34324",
        host = HOST,
        port = "9737",
    )
    val local = LnPeer(
        nodeId = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87",
        host = HOST,
        port = "9738",
    )
}
