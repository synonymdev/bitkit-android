package to.bitkit.data.dto.price

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

@Stable
@Serializable
data class PriceDTO(
    @Stable val widgets: List<PriceWidgetData>,
)
