package to.bitkit.models.blocktank

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BtChannelClose(
    /**
     * Transaction id of the closing transaction. Only available if state === OpenChannelOrderState.CLOSED.
     */
    val txId: String,

    /**
     * Which method has been used to close this channel?
     */
    val type: CloseType,

    /**
     * Who closed this channel?
     */
    val initiator: ChannelInitiator,

    /**
     * When Blocktank registered the channel close.
     */
    val registeredAt: Instant,
)

@Suppress("EnumEntryName")
@Serializable
enum class CloseType {
    cooperative, force, breach,
}

@Suppress("EnumEntryName")
@Serializable
enum class ChannelInitiator {
    lsp, client,
}
