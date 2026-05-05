package to.bitkit.ui.screens.widgets.blocks

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.WeatherDTO
import java.text.NumberFormat
import java.util.Locale

@Immutable
data class WeatherModel(
    val condition: FeeCondition,
    @StringRes val title: Int,
    @StringRes val shortTitle: Int,
    @StringRes val description: Int,
    val currentFee: String,
    val currentFeeSats: Long,
    val currentFeeSatsFormatted: String,
    val nextBlockFee: String,
    val icon: String,
)

fun WeatherDTO.toWeatherModel(locale: Locale = Locale.getDefault()): WeatherModel {
    val title = when (condition) {
        FeeCondition.GOOD -> R.string.widgets__weather__condition__good__title
        FeeCondition.AVERAGE -> R.string.widgets__weather__condition__average__title
        FeeCondition.POOR -> R.string.widgets__weather__condition__poor__title
    }
    val shortTitle = when (condition) {
        FeeCondition.GOOD -> R.string.widgets__weather__condition__good__short_title
        FeeCondition.AVERAGE -> R.string.widgets__weather__condition__average__short_title
        FeeCondition.POOR -> R.string.widgets__weather__condition__poor__short_title
    }
    val description = when (condition) {
        FeeCondition.GOOD -> R.string.widgets__weather__condition__good__description
        FeeCondition.AVERAGE -> R.string.widgets__weather__condition__average__description
        FeeCondition.POOR -> R.string.widgets__weather__condition__poor__description
    }

    return WeatherModel(
        condition = condition,
        title = title,
        shortTitle = shortTitle,
        description = description,
        currentFee = currentFee,
        currentFeeSats = avgFeeSats,
        currentFeeSatsFormatted = "${NumberFormat.getInstance(locale).format(avgFeeSats)} ₿",
        nextBlockFee = "$nextBlockFee ₿/vByte",
        icon = condition.icon,
    )
}
