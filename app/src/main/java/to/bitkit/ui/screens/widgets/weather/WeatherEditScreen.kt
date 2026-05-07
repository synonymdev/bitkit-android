package to.bitkit.ui.screens.widgets.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun WeatherEditScreen(
    weatherViewModel: WeatherViewModel,
    onBack: () -> Unit,
    navigatePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customPreferences by weatherViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentWeather by weatherViewModel.currentWeather.collectAsStateWithLifecycle()

    WeatherEditContent(
        onBack = onBack,
        weatherPreferences = customPreferences,
        weather = currentWeather,
        onSelectOption = { weatherViewModel.selectOption(it) },
        onClickReset = { weatherViewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
        modifier = modifier
    )
}

@Composable
fun WeatherEditContent(
    onBack: () -> Unit,
    weather: WeatherModel?,
    onSelectOption: (WeatherDataOption) -> Unit,
    onClickReset: () -> Unit,
    onClickPreview: () -> Unit,
    weatherPreferences: WeatherPreferences,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        noBackground = true,
        modifier = modifier
            .background(Colors.Gray7)
            .testTag("weather_edit_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__weather__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("WidgetEditScrollView")
        ) {
            VerticalSpacer(16.dp)

            Caption13Up(
                text = stringResource(R.string.widgets__widget__display),
                color = Colors.White64,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("display_section_header")
            )

            WeatherEditOptionRow(
                label = stringResource(R.string.widgets__weather__current_fee),
                value = rememberWeatherOptionValue(WeatherDataOption.CURRENT_FEE_FIAT, weather),
                isSelected = weatherPreferences.selectedOption == WeatherDataOption.CURRENT_FEE_FIAT,
                onClick = { onSelectOption(WeatherDataOption.CURRENT_FEE_FIAT) },
                testTagPrefix = "current_fee_fiat",
            )

            WeatherEditOptionRow(
                label = stringResource(R.string.widgets__weather__current_fee),
                value = rememberWeatherOptionValue(WeatherDataOption.CURRENT_FEE_SATS, weather),
                isSelected = weatherPreferences.selectedOption == WeatherDataOption.CURRENT_FEE_SATS,
                onClick = { onSelectOption(WeatherDataOption.CURRENT_FEE_SATS) },
                testTagPrefix = "current_fee_sats",
            )

            WeatherEditOptionRow(
                label = stringResource(R.string.widgets__weather__next_block),
                value = rememberWeatherOptionValue(WeatherDataOption.NEXT_BLOCK_INCLUSION, weather),
                isSelected = weatherPreferences.selectedOption == WeatherDataOption.NEXT_BLOCK_INCLUSION,
                onClick = { onSelectOption(WeatherDataOption.NEXT_BLOCK_INCLUSION) },
                testTagPrefix = "next_block",
            )

            FillHeight()

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("buttons_row")
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__reset),
                    enabled = weatherPreferences != WeatherPreferences(),
                    fullWidth = false,
                    onClick = onClickReset,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetEditReset")
                )

                PrimaryButton(
                    text = stringResource(R.string.common__preview),
                    enabled = weatherPreferences.selectedOption != null,
                    fullWidth = false,
                    onClick = onClickPreview,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetEditPreview")
                )
            }
        }
    }
}

@Composable
private fun rememberWeatherOptionValue(
    option: WeatherDataOption,
    weather: WeatherModel?,
): String = when (option) {
    WeatherDataOption.CURRENT_FEE_FIAT -> weather?.currentFee.orEmpty()
    WeatherDataOption.CURRENT_FEE_SATS -> rememberMoneyText(
        sats = weather?.currentFeeSats ?: 0L,
        unit = PrimaryDisplay.BITCOIN,
        showSymbol = true,
    ).orEmpty().stripAccentTags()
    WeatherDataOption.NEXT_BLOCK_INCLUSION -> weather?.nextBlockFee.orEmpty()
}

private fun String.stripAccentTags(): String =
    replace("<accent>", "").replace("</accent>", "")

@Composable
private fun WeatherEditOptionRow(
    label: String,
    value: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
                .testTag("${testTagPrefix}_setting_row")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_value_column")
            ) {
                Caption13Up(
                    text = label,
                    color = Colors.White64,
                    modifier = Modifier.testTag("${testTagPrefix}_label")
                )

                WeatherFeeValueText(
                    text = value,
                    color = Colors.Green,
                    modifier = Modifier.testTag("${testTagPrefix}_value")
                )
            }

            IconButton(
                onClick = onClick,
                modifier = Modifier.testTag("${testTagPrefix}_toggle_button")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isSelected) Colors.Brand else Colors.Gray3,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("${testTagPrefix}_toggle_icon")
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.testTag("${testTagPrefix}_divider")
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WeatherEditContent(
            onBack = {},
            onSelectOption = {},
            onClickReset = {},
            onClickPreview = {},
            weatherPreferences = WeatherPreferences(),
            weather = WeatherModel(
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
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSelectedSats() {
    AppThemeSurface {
        WeatherEditContent(
            onBack = {},
            onSelectOption = {},
            onClickReset = {},
            onClickPreview = {},
            weatherPreferences = WeatherPreferences(selectedOption = WeatherDataOption.CURRENT_FEE_SATS),
            weather = WeatherModel(
                condition = FeeCondition.AVERAGE,
                title = R.string.widgets__weather__condition__average__title,
                shortTitle = R.string.widgets__weather__condition__average__short_title,
                description = R.string.widgets__weather__condition__average__description,
                currentFee = "$ 1.20",
                currentFeeSats = 1200L,
                currentFeeSatsFormatted = "1,200 ₿",
                nextBlockFee = "12 ₿/vByte",
                icon = FeeCondition.AVERAGE.icon,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNoneSelected() {
    AppThemeSurface {
        WeatherEditContent(
            onBack = {},
            onSelectOption = {},
            onClickReset = {},
            onClickPreview = {},
            weatherPreferences = WeatherPreferences(selectedOption = null),
            weather = WeatherModel(
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
        )
    }
}
