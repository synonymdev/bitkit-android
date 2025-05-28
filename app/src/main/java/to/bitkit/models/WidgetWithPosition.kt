package to.bitkit.models

import kotlinx.serialization.Serializable

@Serializable
data class WidgetWithPosition(
    val type: WidgetType,
    val position: Int,
)
