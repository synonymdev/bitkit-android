package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
@Suppress("EnumEntryName")
enum class BtPaymentState2 {
    /**
     * Ready to receive payments.
     */
    created,

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

    /**
     * Payments not possible anymore.
     */
    canceled,
}
