package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class WeatherPreferences(
    val showTitle: Boolean = true,
    val showSubTitle: Boolean = false,
    val showCurrentFee: Boolean = false,
    val showNextBlockFee: Boolean = false,
)
