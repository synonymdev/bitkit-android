package to.bitkit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WidgetSize {
    @SerialName("small")
    SMALL,

    @SerialName("wide")
    WIDE;

    companion object {
        fun default(type: WidgetType): WidgetSize = when (type) {
            WidgetType.PRICE,
            WidgetType.NEWS,
            WidgetType.SUGGESTIONS,
            -> WIDE

            else -> SMALL
        }
    }
}
