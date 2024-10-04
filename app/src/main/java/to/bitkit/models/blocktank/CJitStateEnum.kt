package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
@Suppress("EnumEntryName")
enum class CJitStateEnum {
    /**
     * Invoice is ready to receive funds.
     */
    created,

    /**
     * Channel opened and funds pushed to client.
     */
    completed,

    /**
     * Invoice expired.
     */
    expired,

    /**
     * Channel open failed.
     */
    failed,
}
