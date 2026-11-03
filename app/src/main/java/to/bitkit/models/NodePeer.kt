package to.bitkit.models

import com.synonym.bitkitcore.ILspNode
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.ext.ellipsisMiddle

data class NodePeer(
    val peerDetails: PeerDetails,
    val lspNode: ILspNode? = null,
)

fun NodePeer.alias(): String =
    lspNode?.alias
        ?: peerDetails.nodeId.ellipsisMiddle(16)
