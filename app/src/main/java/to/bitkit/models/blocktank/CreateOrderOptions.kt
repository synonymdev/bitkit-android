package to.bitkit.models.blocktank

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrderOptions(
    /**
     * Initial number of satoshi the client wants to provide on their channel side. The client pays this balance
     * to the LSP. The LSP will push the balance to the LSP on channel creation. Defaults to 0.
     */
    val clientBalanceSat: Int,

    /**
     * Node id the client wants to receive the channel from. The id must come from the node list provided by `getInfo`.
     * If not provided, a random node will be chosen.
     */
    val lspNodeId: String? = null,

    /**
     * deprecated: Use `source` field instead.
     */
    val couponCode: String,

    /**
     * What created this order. Example: 'bitkit', 'widget'.
     */
    val source: String? = null,

    /**
     * User entered discount code.
     */
    val discountCode: String? = null,

    /**
     * If the channel opened should be a zeroConf channel, aka. turboChannel
     */
    val zeroConf: Boolean,

    /**
     * If the onchain payment should be accepted without any block confirmations.
     */
    val zeroConfPayment: Boolean? = null,

    /**
     * Allow the peer to have zero channel reserve (dust limit).
     */
    val zeroReserve: Boolean,

    /**
     * Node that should be waken up via a push notification as soon as
     * the payment is confirmed.
     * Ownership of the node must be proven with a signature.
     */
    val wakeToOpen: WakeToOpen? = null,

    /**
     * Optional id of the node that the channel should be opened to. If supplied
     * performs a channel limit check on the node at order creation to prevent
     * any surprises when the channel is actually getting opened.
     */
    val nodeId: String? = null,

    /**
     * User entered refund onchain address.
     */
    val refundOnchainAddress: String? = null,
) {
    @Serializable
    data class WakeToOpen(
        /** Node id of the node to wake up. */
        val nodeId: String,

        /** Timestamp that has been used to sign the open proof. */
        val timestamp: Instant,

        /** Signature `channelOpen-${ISO-timestamp}` created by the private key of the node. */
        val signature: String,
    )

    companion object {
        fun initWithDefaultsinitWithDefaults() = CreateOrderOptions(
            clientBalanceSat = 0,
            lspNodeId = null,
            couponCode = "",
            zeroConf = false,
            zeroReserve = false,
            zeroConfPayment = null,
            wakeToOpen = null
        )
    }
}
