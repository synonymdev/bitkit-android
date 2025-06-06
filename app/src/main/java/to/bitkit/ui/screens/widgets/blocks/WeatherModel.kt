package to.bitkit.ui.screens.widgets.blocks

import androidx.annotation.StringRes
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.WeatherDTO

data class WeatherModel(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val currentFee: String,
    val nextBlockFee: String,
    val icon: String,
)

fun WeatherDTO.toWeatherModel(): WeatherModel {

    val title = when(condition) {
        FeeCondition.GOOD -> R.string.widgets__weather__condition__good__title
        FeeCondition.AVERAGE -> R.string.widgets__weather__condition__average__title
        FeeCondition.POOR -> R.string.widgets__weather__condition__poor__title
    }
    val description = when(condition) {
        FeeCondition.GOOD -> R.string.widgets__weather__condition__good__description
        FeeCondition.AVERAGE -> R.string.widgets__weather__condition__average__description
        FeeCondition.POOR -> R.string.widgets__weather__condition__poor__description
    }

    return WeatherModel(
        title = title,
        description = description,
        currentFee = currentFee,
        nextBlockFee = "$nextBlockFee ₿/vByte",
        icon = condition.icon,
    )
}
