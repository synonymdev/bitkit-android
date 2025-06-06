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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun WeatherEditScreen(
    weatherViewModel: WeatherViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigatePreview: () -> Unit
) {
    val customPreferences by weatherViewModel.customPreferences.collectAsStateWithLifecycle()
    val currentWeather by weatherViewModel.currentWeather.collectAsStateWithLifecycle()

    WeatherEditContent(
        onClose = onClose,
        onBack = onBack,
        weatherPreferences = customPreferences,
        onClickShowTitle = { weatherViewModel.toggleShowTitle() },
        onClickShowDescription = { weatherViewModel.toggleShowDescription() },
        onClickShowCurrentFee = { weatherViewModel.toggleShowCurrentFee() },
        onClickShowNextBlockFee = { weatherViewModel.toggleShowNextBlockFee() },
        onClickReset = { weatherViewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
        weather = currentWeather
    )
}

@Composable
fun WeatherEditContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    weather: WeatherModel?,
    onClickShowTitle: () -> Unit,
    onClickShowDescription: () -> Unit,
    onClickShowCurrentFee: () -> Unit,
    onClickShowNextBlockFee: () -> Unit,
    onClickReset: () -> Unit,
    onClickPreview: () -> Unit,
    weatherPreferences: WeatherPreferences,
) {
    ScreenColumn(
        modifier = Modifier.testTag("weather_edit_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__edit),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag("main_content")
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            BodyM(
                text = stringResource(R.string.widgets__widget__edit_description).replace(
                    "{name}",
                    stringResource(R.string.widgets__weather__name)
                ),
                color = Colors.White64,
                modifier = Modifier.testTag("edit_description")
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("title_setting_row")
            ) {
                Text(
                    text = weather?.title?.let { stringResource(it) }.orEmpty(),
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
                    modifier = Modifier.weight(1f).testTag("title_text"),
                )

                weather?.icon?.let {
                    Text(
                        text = it,
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


                IconButton(
                    onClick = onClickShowTitle,
                    modifier = Modifier.testTag("title_toggle_button")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = if (weatherPreferences.showTitle) Colors.Brand else Colors.White50,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("title_toggle_icon"),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.testTag("title_divider")
            )

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("description_setting_row")
            ) {
                BodyM(
                    text =weather?.description?.let { stringResource(it) }.orEmpty(),
                    color = Colors.White,
                    modifier = Modifier.weight(1f).testTag("description_text")
                )

                IconButton(
                    onClick = onClickShowDescription,
                    modifier = Modifier.testTag("description_toggle_button")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = if (weatherPreferences.showDescription) Colors.Brand else Colors.White50,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("description_toggle_icon"),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.testTag("description_divider")
            )

            // Current fee toggle
            WeatherEditOptionRow(
                label = stringResource(R.string.widgets__weather__current_fee),
                value = weather?.currentFee.orEmpty(),
                isEnabled = weatherPreferences.showCurrentFee,
                onClick = onClickShowCurrentFee,
                testTagPrefix = "current_fee"
            )

            // Next block fee toggle
            WeatherEditOptionRow(
                label = stringResource(R.string.widgets__weather__next_block),
                value = weather?.nextBlockFee.orEmpty(),
                isEnabled = weatherPreferences.showNextBlockFee,
                onClick = onClickShowNextBlockFee,
                testTagPrefix = "next_block_fee"
            )
        }

        Row(
            modifier = Modifier
                .padding(vertical = 21.dp, horizontal = 16.dp)
                .fillMaxWidth()
                .testTag("buttons_row"),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                modifier = Modifier
                    .weight(1f)
                    .testTag("reset_button"),
                enabled = weatherPreferences != WeatherPreferences(),
                fullWidth = false,
                onClick = onClickReset
            )

            PrimaryButton(
                text = stringResource(R.string.common__preview),
                enabled = weatherPreferences.run {
                    showTitle || showDescription || showCurrentFee || showNextBlockFee
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("preview_button"),
                fullWidth = false,
                onClick = onClickPreview
            )
        }
    }
}

@Composable
private fun WeatherEditOptionRow(
    label: String,
    value: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    testTagPrefix: String
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 21.dp)
                .fillMaxWidth()
                .testTag("${testTagPrefix}_setting_row")
        ) {
            BodySSB(
                text = label,
                color = Colors.White64,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_label")
            )

            if (value.isNotEmpty()) {
                BodySSB(
                    text = value,
                    color = Colors.White,
                    modifier = Modifier.testTag("${testTagPrefix}_text")
                )
            }

            IconButton(
                onClick = onClick,
                modifier = Modifier.testTag("${testTagPrefix}_toggle_button")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isEnabled) Colors.Brand else Colors.White50,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("${testTagPrefix}_toggle_icon"),
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
            onClose = {},
            onBack = {},
            onClickShowTitle = {},
            onClickShowDescription = {},
            onClickShowCurrentFee = {},
            onClickShowNextBlockFee = {},
            onClickReset = {},
            onClickPreview = {},
            weatherPreferences = WeatherPreferences(),
            weather = WeatherModel(
                title = R.string.widgets__weather__condition__good__title,
                description = R.string.widgets__weather__condition__good__description,
                currentFee = "15 sat/vB",
                nextBlockFee = "12 sat/vB",
                icon = FeeCondition.GOOD.icon
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithSomeOptionsEnabled() {
    AppThemeSurface {
        WeatherEditContent(
            onClose = {},
            onBack = {},
            onClickShowTitle = {},
            onClickShowDescription = {},
            onClickShowCurrentFee = {},
            onClickShowNextBlockFee = {},
            onClickReset = {},
            onClickPreview = {},
            weatherPreferences = WeatherPreferences(
                showTitle = true,
                showDescription = true,
                showCurrentFee = true,
                showNextBlockFee = false
            ),
            weather = WeatherModel(
                title = R.string.widgets__weather__condition__average__title,
                description = R.string.widgets__weather__condition__average__description,
                currentFee = "45 sat/vB",
                nextBlockFee = "50 sat/vB",
                icon = FeeCondition.AVERAGE.icon
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithAllDisabled() {
    AppThemeSurface {
        WeatherEditContent(
            onClose = {},
            onBack = {},
            onClickShowTitle = {},
            onClickShowDescription = {},
            onClickShowCurrentFee = {},
            onClickShowNextBlockFee = {},
            onClickReset = {},
            onClickPreview = {},
            weatherPreferences = WeatherPreferences(
                showTitle = false,
                showDescription = false,
                showCurrentFee = false,
                showNextBlockFee = false
            ),
            weather = WeatherModel(
                title = R.string.widgets__weather__condition__poor__title,
                description = R.string.widgets__weather__condition__poor__description,
                currentFee = "45 sat/vB",
                nextBlockFee = "50 sat/vB",
                icon = FeeCondition.POOR.icon
            ),
        )
    }
}
