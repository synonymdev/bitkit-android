package to.bitkit.models.blocktank

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BtOrder(
    val id: String,

    /**
     * deprecated: Will be removed in future releases. Use state2 instead.
     */
    val state: BtOrderState,

    /**
     * State of the order.
     */
    val state2: BtOrderState2,

    /**
     * Fees in satoshi to pay before the channel open is possible.
     */
    val feeSat: ULong,

    /**
     * Requested funds on the LSP side.
     */
    val lspBalanceSat: Long,

    /**
     * Requested funds on the client side.
     */
    val clientBalanceSat: Long,

    /**
     * If this the channel should be a turbo channel.
     */
    val zeroConf: Boolean,

    /**
     * If the LSP allows the client to have a minimal channel reserve.
     */
    val zeroReserve: Boolean,

    /**
     * Node id to notify in case the node is offline and the order is ready to open the channel.
     */
    val wakeToOpenNodeId: String? = null,

    /**
     * Weeks how long Blocktank guarantees to keep the channel open.
     */
    val channelExpiryWeeks: Int,

    /**
     * How long Blocktank guarantees to keep the channel open.
     */
    val channelExpiresAt: Instant,

    /**
     * How long the order with its proposed feeSat is valid for.
     */
    val orderExpiresAt: Instant,

    /**
     * Channel in case the order has been executed.
     */
    val channel: BtChannel? = null,

    /**
     * Node id of the LSP that will/has opened the channel.
     */
    val lspNode: LspNode,

    /**
     * LNURL to open the channel.
     */
    val lnurl: String? = null,

    /**
     * Payment object showing invoices and the status of the payment.
     */
    val payment: BtPayment,

    /**
     * deprecated: Use the `source` field.
     */
    val couponCode: String? = null,

    /**
     * Source what created this order. Example: bitkit, widget.
     */
    val source: String? = null,

    /**
     * Discount if discount was given.
     */
    val discount: Discount? = null,
    val updatedAt: String,
    val createdAt: String,
)
