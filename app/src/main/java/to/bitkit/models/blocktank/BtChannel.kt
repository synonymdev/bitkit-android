package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class BtChannel(
    val state: BtOpenChannelState,
    val lspNodePubkey: String,
    val clientNodePubkey: String,
    val announceChannel: Boolean,
    val fundingTx: FundingTx,

    /**
     * deprecated: Use close.txId instead.
     */
    val closingTxId: String? = null,

    /**
     * Only available if state === closed. Channels before Oct 2023 may have default values assigned that do not represent reality.
     */
    val close: BtChannelClose? = null,

    /**
     * deprecated: Available as soon as the channel is confirmed.
     */
    val shortChannelId: String? = null,
) {
    @Serializable
    data class FundingTx(
        val id: String,
        val vout: Int,
    )
}
