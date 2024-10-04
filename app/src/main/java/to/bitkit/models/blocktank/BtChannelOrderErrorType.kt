package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
enum class BtChannelOrderErrorType {
    /**
     * Order is not in the right state to open a channel. Should be `order.state`=`created` and `order.payment.state`=`paid`.
     */
    WRONG_ORDER_STATE,

    /**
     * Could not establish connection to peer.
     */
    PEER_NOT_REACHABLE,

    /**
     * Peer rejected channel open request.
     */
    CHANNEL_REJECTED_BY_DESTINATION,

    /**
     * LSP rejected channel open request.
     */
    CHANNEL_REJECTED_BY_LSP,

    /**
     * Blocktank service is temporarily unavailable.
     */
    BLOCKTANK_NOT_READY,
}
