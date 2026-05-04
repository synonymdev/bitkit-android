package to.bitkit.ui.screens.widgets.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.ext.spaceToNewline
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun WeatherPreviewScreen(
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
    )
}

@Composable
fun WeatherPreviewContent(
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isWeatherWidgetEnabled: Boolean,
    weatherPreferences: WeatherPreferences,
    weatherModel: WeatherModel?,
) {
    ScreenColumn(
        modifier = Modifier.testTag("weather_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag("main_content")
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("header_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Headline(
                    text = AnnotatedString(stringResource(R.string.widgets__weather__name).spaceToNewline()),
                    modifier = Modifier.testTag("widget_title"),
                )
                Icon(
                    painter = painterResource(R.drawable.widget_cloud),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("widget_icon")
                )
            }

            BodyM(
                text = stringResource(R.string.widgets__weather__description),
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("widget_description")
            )

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__edit),
                value = SettingsButtonValue.StringValue(
                    if (weatherPreferences == WeatherPreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    }
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("WidgetEdit")
            )

            Spacer(modifier = Modifier.weight(1f))

            Text13Up(
                stringResource(R.string.common__preview),
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("preview_label")
            )

            weatherModel?.let { model ->
                WeatherCard(
                    weatherModel = model,
                    preferences = weatherPreferences,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weather_card")
                )
            }
        }

        Row(
            modifier = Modifier
                .padding(vertical = 21.dp, horizontal = 16.dp)
                .fillMaxWidth()
                .testTag("buttons_row"),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isWeatherWidgetEnabled) {
                SecondaryButton(
                    text = stringResource(R.string.common__delete),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetDelete"),
                    fullWidth = false,
                    onClick = onClickDelete
                )
            }

            PrimaryButton(
                text = stringResource(R.string.common__save),
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetSave"),
                fullWidth = false,
                onClick = onClickSave
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
                description = R.string.widgets__weather__condition__good__description,
                currentFee = "$ 0.52",
                currentFeeSats = 520L,
                nextBlockFee = "6 ₿/vByte",
                icon = "☀️"
            ),
            isWeatherWidgetEnabled = false
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
                description = R.string.widgets__weather__condition__poor__description,
                currentFee = "$ 4.50",
                currentFeeSats = 4500L,
                nextBlockFee = "45 ₿/vByte",
                icon = "⛈️"
            ),
            isWeatherWidgetEnabled = true
        )
    }
}
