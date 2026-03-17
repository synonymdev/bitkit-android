package to.bitkit.models.widget

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class WeatherPreferences(
    val showTitle: Boolean = true,
    val showDescription: Boolean = false,
    val showCurrentFee: Boolean = false,
    val showNextBlockFee: Boolean = false,
)
