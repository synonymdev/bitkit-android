package to.bitkit.models.widget

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class BlocksPreferences(
    val showBlock: Boolean = true,
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showTransactions: Boolean = true,
    val showSize: Boolean = false,
    val showFees: Boolean = false,
    val showSource: Boolean = false,
)
