package to.bitkit.data.dto.price

import kotlinx.serialization.Serializable

@Serializable
data class PriceDTO(
    val widgets: List<PriceWidgetData>
)
