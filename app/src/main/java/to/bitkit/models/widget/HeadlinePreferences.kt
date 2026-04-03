package to.bitkit.models.widget

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class HeadlinePreferences(
    val showTime: Boolean = true,
    val showSource: Boolean = true
)
