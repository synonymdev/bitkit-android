package to.bitkit.env

import org.lightningdevkit.ldknode.PeerDetails

data class LnPeer(
    val nodeId: String,
    val host: String,
    val port: String,
) {
    constructor(
        nodeId: String,
        address: String,
    ) : this(
        nodeId,
        address.substringBefore(":"),
        address.substringAfter(":"),
    )

    val address get() = "$host:$port"
    override fun toString() = "$nodeId@${address}"

    companion object {
        fun PeerDetails.toLnPeer() = LnPeer(
            nodeId = nodeId,
            address = address,
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
