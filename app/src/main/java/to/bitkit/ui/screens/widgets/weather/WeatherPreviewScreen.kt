package to.bitkit.ui.screens.widgets.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.components.WidgetSizeCarousel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun WeatherPreviewScreen(
    modifier: Modifier = Modifier,
    weatherViewModel: WeatherViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val customWeatherPreferences by weatherViewModel.customPreferences.collectAsStateWithLifecycle()
    val weather by weatherViewModel.currentWeather.collectAsStateWithLifecycle()
    val isWeatherWidgetEnabled by weatherViewModel.isWeatherWidgetEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        weatherViewModel.refreshOnDisplay()
    }

    WeatherPreviewContent(
        onBack = onBack,
        isWeatherWidgetEnabled = isWeatherWidgetEnabled,
        weatherPreferences = customWeatherPreferences,
        weatherModel = weather,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            weatherViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            weatherViewModel.savePreferences()
            onClose()
        },
        modifier = modifier
    )
}

@Composable
fun WeatherPreviewContent(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isWeatherWidgetEnabled: Boolean,
    weatherPreferences: WeatherPreferences,
    weatherModel: WeatherModel?,
) {
    ScreenColumn(
        noBackground = true,
        modifier = modifier
            .background(Colors.Gray7)
            .testTag("weather_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__weather__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.widgets__weather__description),
                color = Colors.White64,
                modifier = Modifier.testTag("widget_description")
            )

            VerticalSpacer(16.dp)

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__settings),
                value = SettingsButtonValue.StringValue(
                    if (weatherPreferences == WeatherPreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    },
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("WidgetEdit")
            )

            weatherModel?.let { model ->
                WidgetSizeCarousel(
                    smallContent = {
                        WeatherCardSmall(
                            weatherModel = model,
                            preferences = weatherPreferences,
                            modifier = Modifier.testTag("weather_card_small")
                        )
                    },
                    wideContent = {
                        WeatherCard(
                            weatherModel = model,
                            preferences = weatherPreferences,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("weather_card_wide")
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weather_preview_carousel")
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    top = 22.dp,
                )
                .fillMaxWidth()
                .testTag("buttons_row")
        ) {
            if (isWeatherWidgetEnabled) {
                SecondaryButton(
                    text = stringResource(R.string.common__delete),
                    fullWidth = false,
                    onClick = onClickDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetDelete")
                )
            }

            PrimaryButton(
                text = stringResource(R.string.widgets__widget__save),
                fullWidth = false,
                onClick = onClickSave,
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetSave")
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WeatherPreviewContent(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            weatherPreferences = WeatherPreferences(),
            weatherModel = WeatherModel(
                condition = FeeCondition.GOOD,
                title = R.string.widgets__weather__condition__good__title,
                shortTitle = R.string.widgets__weather__condition__good__short_title,
                description = R.string.widgets__weather__condition__good__description,
                currentFee = "$ 0.52",
                currentFeeSats = 520L,
                currentFeeSatsFormatted = "520 ₿",
                nextBlockFee = "6 ₿/vByte",
                icon = "☀️",
            ),
            isWeatherWidgetEnabled = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        WeatherPreviewContent(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            weatherPreferences = WeatherPreferences(selectedOption = WeatherDataOption.NEXT_BLOCK_INCLUSION),
            weatherModel = WeatherModel(
                condition = FeeCondition.POOR,
                title = R.string.widgets__weather__condition__poor__title,
                shortTitle = R.string.widgets__weather__condition__poor__short_title,
                description = R.string.widgets__weather__condition__poor__description,
                currentFee = "$ 4.50",
                currentFeeSats = 4500L,
                currentFeeSatsFormatted = "4,500 ₿",
                nextBlockFee = "45 ₿/vByte",
                icon = "⛈️",
            ),
            isWeatherWidgetEnabled = true,
        )
    }
}
