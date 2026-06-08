package to.bitkit.models.widget

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class WeatherDataOption {
    CURRENT_FEE_FIAT,
    CURRENT_FEE_SATS,
    NEXT_BLOCK_INCLUSION,
}

@Immutable
@Serializable
data class WeatherPreferences(
    val selectedOption: WeatherDataOption? = WeatherDataOption.CURRENT_FEE_FIAT,
)
