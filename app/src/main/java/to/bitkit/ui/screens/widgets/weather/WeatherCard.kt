package to.bitkit.ui.screens.widgets.weather

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Subtitle
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun WeatherCard(
    modifier: Modifier = Modifier,
    weatherModel: WeatherModel,
    preferences: WeatherPreferences,
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.Gray6)
            .testTag("weather_card")
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("weather_card_content")
            ) {
                WeatherTitleBlock(
                    titleRes = weatherModel.title,
                    descriptionRes = weatherModel.description,
                    showDescription = true,
                )
                WeatherFeeBlock(
                    weatherModel = weatherModel,
                    selectedOption = preferences.selectedOption,
                )
            }

            WeatherEmoji(
                icon = weatherModel.icon,
                fontSize = LARGE_EMOJI_SIZE,
            )
        }
    }
}

@Composable
fun WeatherCardSmall(
    modifier: Modifier = Modifier,
    weatherModel: WeatherModel,
    preferences: WeatherPreferences,
) {
    Box(
        modifier = modifier
            .size(WidgetCardDimens.COMPACT_CARD_SIZE)
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.Gray6)
            .testTag("weather_card_small")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("weather_card_small_header")
            ) {
                WeatherEmoji(
                    icon = weatherModel.icon,
                    fontSize = SMALL_EMOJI_SIZE,
                )
                Subtitle(
                    text = stringResource(weatherModel.shortTitle),
                    color = Colors.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("weather_card_small_title")
                )
            }

            WeatherFeeBlock(
                weatherModel = weatherModel,
                selectedOption = preferences.selectedOption,
            )
        }
    }
}

@Composable
private fun WeatherTitleBlock(
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    showDescription: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weather_card_title_block")
    ) {
        Subtitle(
            text = stringResource(titleRes),
            color = Colors.White,
            modifier = Modifier.testTag("weather_card_title")
        )
        if (showDescription) {
            BodyS(
                text = stringResource(descriptionRes),
                color = Colors.White80,
                modifier = Modifier.testTag("weather_card_description")
            )
        }
    }
}

@Composable
private fun WeatherFeeBlock(
    weatherModel: WeatherModel,
    selectedOption: WeatherDataOption?,
) {
    selectedOption ?: return

    val (labelRes, value, testTagPrefix) = when (selectedOption) {
        WeatherDataOption.CURRENT_FEE_FIAT ->
            Triple(R.string.widgets__weather__current_fee, weatherModel.currentFee, "current_fee_fiat")
        WeatherDataOption.CURRENT_FEE_SATS ->
            Triple(
                R.string.widgets__weather__current_fee,
                weatherModel.currentFeeSatsFormatted,
                "current_fee_sats",
            )
        WeatherDataOption.NEXT_BLOCK_INCLUSION ->
            Triple(R.string.widgets__weather__next_block, weatherModel.nextBlockFee, "next_block")
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weather_card_${testTagPrefix}_block")
    ) {
        Caption13Up(
            text = stringResource(labelRes),
            color = Colors.White64,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("weather_card_${testTagPrefix}_label")
        )
        WeatherFeeValueText(
            text = value,
            color = weatherModel.condition.feeColor(),
            modifier = Modifier.testTag("weather_card_${testTagPrefix}_value")
        )
    }
}

@Composable
private fun WeatherEmoji(
    icon: String,
    fontSize: TextUnit,
) {
    Text(
        text = icon,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = Colors.White,
        ),
        modifier = Modifier.testTag("weather_card_icon")
    )
}

private fun FeeCondition.feeColor(): Color = when (this) {
    FeeCondition.GOOD -> Colors.Green
    FeeCondition.AVERAGE -> Colors.Yellow
    FeeCondition.POOR -> Colors.Red
}

private val LARGE_EMOJI_SIZE = 82.sp
private val SMALL_EMOJI_SIZE = 60.sp

@Preview(showBackground = true)
@Composable
private fun PreviewLarge() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            WeatherCard(
                weatherModel = WeatherModel(
                    condition = FeeCondition.GOOD,
                    title = R.string.widgets__weather__condition__good__title,
                    shortTitle = R.string.widgets__weather__condition__good__short_title,
                    description = R.string.widgets__weather__condition__good__description,
                    currentFee = "$ 0.52",
                    currentFeeSats = 520L,
                    currentFeeSatsFormatted = "520 ₿",
                    nextBlockFee = "6 ₿/vByte",
                    icon = FeeCondition.GOOD.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                modifier = Modifier.fillMaxWidth()
            )

            WeatherCard(
                weatherModel = WeatherModel(
                    condition = FeeCondition.AVERAGE,
                    title = R.string.widgets__weather__condition__average__title,
                    shortTitle = R.string.widgets__weather__condition__average__short_title,
                    description = R.string.widgets__weather__condition__average__description,
                    currentFee = "$ 1.27",
                    currentFeeSats = 1270L,
                    currentFeeSatsFormatted = "1,270 ₿",
                    nextBlockFee = "12 ₿/vByte",
                    icon = FeeCondition.AVERAGE.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
                modifier = Modifier.fillMaxWidth()
            )

            WeatherCard(
                weatherModel = WeatherModel(
                    condition = FeeCondition.POOR,
                    title = R.string.widgets__weather__condition__poor__title,
                    shortTitle = R.string.widgets__weather__condition__poor__short_title,
                    description = R.string.widgets__weather__condition__poor__description,
                    currentFee = "$ 4.50",
                    currentFeeSats = 4500L,
                    currentFeeSatsFormatted = "4,500 ₿",
                    nextBlockFee = "45 ₿/vByte",
                    icon = FeeCondition.POOR.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            WeatherCardSmall(
                weatherModel = WeatherModel(
                    condition = FeeCondition.GOOD,
                    title = R.string.widgets__weather__condition__good__title,
                    shortTitle = R.string.widgets__weather__condition__good__short_title,
                    description = R.string.widgets__weather__condition__good__description,
                    currentFee = "$ 0.52",
                    currentFeeSats = 520L,
                    currentFeeSatsFormatted = "520 ₿",
                    nextBlockFee = "6 ₿/vByte",
                    icon = FeeCondition.GOOD.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
            )
            WeatherCardSmall(
                weatherModel = WeatherModel(
                    condition = FeeCondition.AVERAGE,
                    title = R.string.widgets__weather__condition__average__title,
                    shortTitle = R.string.widgets__weather__condition__average__short_title,
                    description = R.string.widgets__weather__condition__average__description,
                    currentFee = "$ 1.27",
                    currentFeeSats = 1270L,
                    currentFeeSatsFormatted = "1,270 ₿",
                    nextBlockFee = "12 ₿/vByte",
                    icon = FeeCondition.AVERAGE.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLargeNoSelection() {
    AppThemeSurface {
        WeatherCard(
            weatherModel = WeatherModel(
                condition = FeeCondition.GOOD,
                title = R.string.widgets__weather__condition__good__title,
                shortTitle = R.string.widgets__weather__condition__good__short_title,
                description = R.string.widgets__weather__condition__good__description,
                currentFee = "$ 0.52",
                currentFeeSats = 520L,
                currentFeeSatsFormatted = "520 ₿",
                nextBlockFee = "6 ₿/vByte",
                icon = FeeCondition.GOOD.icon,
            ),
            preferences = WeatherPreferences(selectedOption = null),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        )
    }
}
