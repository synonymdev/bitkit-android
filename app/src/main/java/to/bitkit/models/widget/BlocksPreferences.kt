package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class BlocksPreferences(
    val showBlock: Boolean = true,
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showTransactions: Boolean = false,
    val showSize: Boolean = false,
    val showSource: Boolean = false,
)
