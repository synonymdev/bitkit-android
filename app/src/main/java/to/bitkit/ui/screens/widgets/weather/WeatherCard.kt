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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySB
import to.bitkit.ui.components.Subtitle
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("weather_card_condition_icon"),
                        tint = Color.Unspecified
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    BodyMSB(
                        text = stringResource(R.string.widgets__weather__name),
                        modifier = Modifier.testTag("weather_card_widget_title_text")
                    )
                }
            }

            if (preferences.showTitle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weather_card_title_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(weatherModel.title).uppercase(),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            lineHeight = 34.sp,
                            letterSpacing = 0.sp,
                            fontFamily = InterFontFamily,
                            color = Colors.White,
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    Text(
                        text = weatherModel.icon,
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 100.sp,
                            lineHeight = 80.sp,
                            letterSpacing = 0.sp,
                            fontFamily = InterFontFamily,
                            color = Colors.White,
                        ),
                    )
                }
            }

            if (preferences.showDescription) {
                Subtitle(
                    text = stringResource(weatherModel.description),
                    color = Colors.White,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("weather_card_description_text")
                )
            }

            if (preferences.showCurrentFee) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("weather_card_current_fee_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = stringResource(R.string.widgets__weather__current_fee),
                        color = Colors.White64,
                        modifier = Modifier.testTag("weather_card_current_fee_label")
                    )
                    BodySB(
                        text = weatherModel.currentFee,
                        color = Colors.White,
                        modifier = Modifier.testTag("weather_card_current_fee_value"),
                    )
                }
            }

            if (preferences.showNextBlockFee) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weather_card_next_block_row"),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BodySB(
                        text = stringResource(R.string.widgets__weather__next_block),
                        color = Colors.White64,
                        modifier = Modifier.testTag("weather_card_next_block_fee_label")
                    )
                    BodySB(
                        text = weatherModel.nextBlockFee,
                        color = Colors.White,
                        modifier = Modifier.testTag("weather_card_next_block_fee_value"),
                    )
                }
            }

        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WeatherCardPreview() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WeatherCard(
                showWidgetTitle = true,
                weatherModel = WeatherModel(
                    title = R.string.widgets__weather__condition__good__title,
                    description = R.string.widgets__weather__condition__good__description,
                    currentFee = "15 sat/vB",
                    nextBlockFee = "12 sat/vB",
                    icon = FeeCondition.GOOD.icon
                ),
                preferences = WeatherPreferences(
                    showTitle = true,
                    showDescription = true,
                    showCurrentFee = true,
                    showNextBlockFee = true
                )
            )

            WeatherCard(
                showWidgetTitle = false,
                weatherModel = WeatherModel(
                    title = R.string.widgets__weather__condition__average__title,
                    description = R.string.widgets__weather__condition__average__description,
                    currentFee = "45 sat/vB",
                    nextBlockFee = "50 sat/vB",
                    icon = FeeCondition.AVERAGE.icon
                ),
                preferences = WeatherPreferences(
                    showTitle = true,
                    showDescription = true,
                    showCurrentFee = true,
                    showNextBlockFee = false
                )
            )

            WeatherCard(
                showWidgetTitle = false,
                weatherModel = WeatherModel(
                    title = R.string.widgets__weather__condition__poor__title,
                    description = R.string.widgets__weather__condition__poor__description,
                    currentFee = "45 sat/vB",
                    nextBlockFee = "50 sat/vB",
                    icon = FeeCondition.POOR.icon
                ),
                preferences = WeatherPreferences(
                    showTitle = true,
                    showDescription = true,
                    showCurrentFee = true,
                    showNextBlockFee = false
                )
            )
        }
    }
}
