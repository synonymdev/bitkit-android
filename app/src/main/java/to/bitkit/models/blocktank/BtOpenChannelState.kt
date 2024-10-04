package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
@Suppress("EnumEntryName")
enum class BtOpenChannelState {
    /**
     * Channel is waiting for the required confirmations.
     */
    opening,

    /**
     * Channel is ready to transact.
     */
    open,

    /**
     * Channel has been closed with at least 1 onchain confirmation.
     */
    closed,
}
