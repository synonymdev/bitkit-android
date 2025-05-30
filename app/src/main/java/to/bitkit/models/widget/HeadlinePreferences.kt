package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class HeadlinePreferences(
    val showTime: Boolean = true,
    val showSource: Boolean = true
)
