package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

/**
 * deprecated: Use BtOrderState2 instead.
 */
@Serializable
@Suppress("EnumEntryName")
enum class BtOrderState {
    created,
    expired, // No payment made, order expired.
    open,
    closed,
}
