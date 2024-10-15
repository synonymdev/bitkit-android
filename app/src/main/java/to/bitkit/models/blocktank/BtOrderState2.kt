package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
@Suppress("EnumEntryName")
enum class BtOrderState2 {
    /**
     * Order created. Ready to receive payment.
     */
    created,

    /**
     * Order expired.
     */
    expired,

    /**
     * Order successfully executed.
     */
    executed,

    /**
     * Order paid and ready to open channel.
     */
    paid,
}
