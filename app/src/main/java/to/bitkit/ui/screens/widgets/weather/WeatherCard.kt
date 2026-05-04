package to.bitkit.ui.screens.widgets.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun WeatherCard(
    modifier: Modifier = Modifier,
    showWidgetTitle: Boolean,
    weatherModel: WeatherModel,
    preferences: WeatherPreferences,
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (showWidgetTitle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("weather_card_widget_title_row")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.widget_cloud),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("weather_card_condition_icon")
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__weather__name),
                        modifier = Modifier.testTag("weather_card_widget_title_text")
                    )
                }
            }

            preferences.selectedOption?.let { option ->
                val (labelRes, value, testTagPrefix) = when (option) {
                    WeatherDataOption.CURRENT_FEE_FIAT ->
                        Triple(R.string.widgets__weather__current_fee, weatherModel.currentFee, "current_fee_fiat")
                    WeatherDataOption.CURRENT_FEE_SATS ->
                        Triple(
                            R.string.widgets__weather__current_fee,
                            rememberMoneyText(
                                sats = weatherModel.currentFeeSats,
                                unit = PrimaryDisplay.BITCOIN,
                                showSymbol = true,
                            ).orEmpty().stripAccentTags(),
                            "current_fee_sats",
                        )
                    WeatherDataOption.NEXT_BLOCK_INCLUSION ->
                        Triple(R.string.widgets__weather__next_block, weatherModel.nextBlockFee, "next_block")
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weather_card_${testTagPrefix}_column")
                ) {
                    Caption13Up(
                        text = stringResource(labelRes),
                        color = Colors.White64,
                        modifier = Modifier.testTag("weather_card_${testTagPrefix}_label")
                    )
                    Text(
                        text = value,
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            lineHeight = 30.sp,
                            letterSpacing = (-1).sp,
                            fontFamily = InterFontFamily,
                            color = Colors.Green,
                        ),
                        modifier = Modifier.testTag("weather_card_${testTagPrefix}_value")
                    )
                }
            }
        }
    }
}

private fun String.stripAccentTags(): String =
    replace("<accent>", "").replace("</accent>", "")

@Preview(showBackground = true)
@Composable
private fun WeatherCardPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            WeatherCard(
                showWidgetTitle = true,
                weatherModel = WeatherModel(
                    title = R.string.widgets__weather__condition__good__title,
                    description = R.string.widgets__weather__condition__good__description,
                    currentFee = "$ 0.52",
                    currentFeeSats = 520L,
                    nextBlockFee = "6 ₿/vByte",
                    icon = FeeCondition.GOOD.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_FIAT),
            )

            WeatherCard(
                showWidgetTitle = false,
                weatherModel = WeatherModel(
                    title = R.string.widgets__weather__condition__average__title,
                    description = R.string.widgets__weather__condition__average__description,
                    currentFee = "$ 1.20",
                    currentFeeSats = 1200L,
                    nextBlockFee = "12 ₿/vByte",
                    icon = FeeCondition.AVERAGE.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_SATS),
            )

            WeatherCard(
                showWidgetTitle = true,
                weatherModel = WeatherModel(
                    title = R.string.widgets__weather__condition__poor__title,
                    description = R.string.widgets__weather__condition__poor__description,
                    currentFee = "$ 4.50",
                    currentFeeSats = 4500L,
                    nextBlockFee = "45 ₿/vByte",
                    icon = FeeCondition.POOR.icon,
                ),
                preferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION),
            )
        }
    }
}
