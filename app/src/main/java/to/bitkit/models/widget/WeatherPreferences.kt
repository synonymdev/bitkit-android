package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class WeatherPreferences(
    val showTitle: Boolean = true,
    val showDescription: Boolean = false,
    val showCurrentFee: Boolean = false,
    val showNextBlockFee: Boolean = false,
)
