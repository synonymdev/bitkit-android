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

        fun parseUri(uriString: String): Result<LnPeer> {
            val uriComponents = uriString.split("@")
            val nodeId = uriComponents[0]

            if (uriComponents.size != 2) {
                return Result.failure(Exception("Invalid peer uri"))
            }

            val address = uriComponents[1].split(":")

            if (address.size < 2) {
                return Result.failure(Exception("Invalid peer uri"))
            }

            val ip = address[0]
            val port = address[1]

            return Result.success(
                LnPeer(
                    nodeId = nodeId,
                    host = ip,
                    port = port,
                )
            )
        }
    }
}
