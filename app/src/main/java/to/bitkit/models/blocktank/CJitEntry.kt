package to.bitkit.models.blocktank

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CJitEntry(
    /**
     * Id of this CJitEntry.
     */
    val id: String,

    /**
     * State of this entry.
     */
    val state: CJitStateEnum,

    /**
     * Fee in satoshi to open this channel.
     */
    val feeSat: Int,

    /**
     * Requested channel size in satoshi.
     */
    val channelSizeSat: Int,

    /**
     * Number of weeks before Blocktank might close the channel.
     */
    val channelExpiryWeeks: Int,

    /**
     * Channel open error if the channel open failed.
     */
    val channelOpenError: String? = null,

    /**
     * Node id of the node to open the channel to.
     */
    val nodeId: String,

    /**
     * Invoice to be paid for the channel open.
     */
    val invoice: BtBolt11Invoice,

    /**
     * Opened channel.
     */
    val channel: BtChannel? = null,

    /**
     * LSP node the channel is opened from. The client needs to establish a peer connection to it before the channel open.
     */
    val lspNode: LspNode,

    /**
     * deprecated: Use `source` instead.
     */
    val couponCode: String? = null,

    /**
     * Source that created this CJit. Example: 'bitkit', 'widget'.
     */
    val source: String? = null,

    /**
     * Discount if available.
     */
    val discount: Discount? = null,

    /**
     * Date when this CJit offer expires.
     */
    val expiresAt: Instant,
    val updatedAt: Instant,
    val createdAt: Instant,
)
