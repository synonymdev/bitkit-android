package to.bitkit.models

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
