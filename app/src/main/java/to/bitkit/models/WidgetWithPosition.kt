package to.bitkit.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class WidgetWithPosition(
    val type: WidgetType,
    val position: Int = 0,
    val size: WidgetSize = WidgetSize.WIDE,
)

fun WidgetWithPosition.effectiveSize(): WidgetSize =
    if (type == WidgetType.SUGGESTIONS) WidgetSize.WIDE else size
