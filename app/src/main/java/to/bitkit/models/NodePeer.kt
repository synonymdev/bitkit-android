package to.bitkit.models

import com.synonym.bitkitcore.ILspNode
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.ext.ellipsisMiddle

data class NodePeer(
    val peerDetails: PeerDetails,
    val lspNode: ILspNode? = null,
    val name: String? = null,
)

fun NodePeer.alias(): String =
    lspNode?.alias
        ?: name
        ?: peerDetails.nodeId.ellipsisMiddle(16)
