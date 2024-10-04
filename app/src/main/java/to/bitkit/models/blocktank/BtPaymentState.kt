package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

/**
 * deprecated: Use BtPaymentState2 instead.
 */
@Serializable
@Suppress("EnumEntryName")
enum class BtPaymentState {
    /**
     * Ready to receive payments
     */
    created,

    /**
     * Partially paid order will just be displayed as `created`.
     */
    partiallyPaid, // Onchain partially paid.

    /**
     * Order is paid.
     */
    paid,

    /**
     * Order is refunded.
     */
    refunded,

    /**
     * Onchain refunds can't be done automatically. `refundAvailable` is displayed in this case.
     */
    refundAvailable, // Onchain refund available
}
