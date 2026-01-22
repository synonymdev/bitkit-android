package to.bitkit.ui.screens.widgets.price

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PriceEditScreen(
    viewModel: PriceViewModel,
    onBack: () -> Unit,
    navigatePreview: () -> Unit,
) {
    val customPreferences by viewModel.customPreferences.collectAsStateWithLifecycle()
    val currentPrice by viewModel.currentPrice.collectAsStateWithLifecycle()
    val allPeriodsUsd by viewModel.allPeriodsUsd.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    PriceEditContent(
        onBack = onBack,
        preferences = customPreferences,
        onClickReset = { viewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
        allPeriodsUsd = allPeriodsUsd,
        priceModel = currentPrice ?: PriceDTO(
            widgets = listOf(),
            source = ""
        ),
        onClickTradingPair = { pair ->
            viewModel.toggleTradingPair(pair = pair)
        },
        onClickGraph = { period ->
            viewModel.setPeriod(period = period)
        },
        isLoading = isLoading,
        onClickSource = {
            viewModel.toggleShowSource()
        }
    )
}

@Composable
fun PriceEditContent(
    onBack: () -> Unit,
    priceModel: PriceDTO,
    allPeriodsUsd: List<PriceWidgetData>,
    onClickReset: () -> Unit,
    onClickGraph: (GraphPeriod) -> Unit,
    onClickTradingPair: (TradingPair) -> Unit,
    onClickPreview: () -> Unit,
    onClickSource: () -> Unit,
    preferences: PricePreferences,
    isLoading: Boolean,
) {
    ScreenColumn(
        modifier = Modifier.testTag("weather_edit_screen")
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("WidgetEditScrollView")
            ) {
                VerticalSpacer(82.dp)

                BodyM(
                    text = stringResource(R.string.widgets__widget__edit_description).replace(
                        "{name}",
                        stringResource(R.string.widgets__price__name)
                    ),
                    color = Colors.White64,
                    modifier = Modifier.testTag("edit_description")
                )

                VerticalSpacer(32.dp)

                priceModel.widgets.map { data ->
                    PriceEditOptionRow(
                        label = data.pair.displayName,
                        value = data.price,
                        isEnabled = data.pair in preferences.enabledPairs,
                        onClick = {
                            onClickTradingPair(data.pair)
                        },
                        testTagPrefix = data.pair.displayName,
                    )
                }

                allPeriodsUsd.map { priceData ->
                    PriceChartOptionRow(
                        widgetData = priceData,
                        isEnabled = priceData.period == preferences.period,
                        onClick = onClickGraph,
                        testTagPrefix = priceData.period.value,
                    )
                }

                PriceEditOptionRow(
                    label = stringResource(R.string.widgets__widget__source),
                    value = priceModel.source,
                    isEnabled = preferences.showSource,
                    onClick = onClickSource,
                    testTagPrefix = "showSource",
                )
            }

            Column {
                AppTopBar(
                    titleText = stringResource(R.string.widgets__widget__edit),
                    onBackClick = onBack,
                    actions = { DrawerNavIcon() },
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                Color.Transparent
                            ),
                            tileMode = TileMode.Decal
                        )
                    )
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .testTag("buttons_row")
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                enabled = preferences != PricePreferences(),
                fullWidth = false,
                onClick = onClickReset,
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetEditReset")
            )

            PrimaryButton(
                text = stringResource(R.string.common__preview),
                fullWidth = false,
                isLoading = isLoading,
                onClick = onClickPreview,
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetEditPreview")
            )
        }
    }
}

@Composable
private fun PriceEditOptionRow(
    label: String,
    value: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
    testTagPrefix: String,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 16.dp)
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
                modifier = Modifier.testTag("WidgetEditField-$testTagPrefix")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isEnabled) Colors.Brand else Colors.White50,
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

@Composable
private fun PriceChartOptionRow(
    widgetData: PriceWidgetData,
    isEnabled: Boolean,
    onClick: (GraphPeriod) -> Unit,
    testTagPrefix: String,
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
            ChartComponent(
                widgetData = widgetData,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = { onClick(widgetData.period) },
                modifier = Modifier.testTag("WidgetEditField-$testTagPrefix")
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

@Suppress("MagicNumber")
@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PriceEditContent(
            onBack = {},
            priceModel = PriceDTO(
                widgets = listOf(
                    PriceWidgetData(
                        pair = TradingPair.BTC_USD,
                        period = GraphPeriod.ONE_DAY,
                        change = Change(isPositive = true, formatted = "+2.5%"),
                        price = "$97,500",
                        pastValues = listOf(95000.0, 96000.0, 95500.0, 97000.0, 97500.0)
                    ),
                    PriceWidgetData(
                        pair = TradingPair.BTC_EUR,
                        period = GraphPeriod.ONE_DAY,
                        change = Change(isPositive = true, formatted = "+2.3%"),
                        price = "€89,000",
                        pastValues = listOf(87000.0, 88000.0, 87500.0, 88500.0, 89000.0)
                    )
                ),
                source = "Kraken"
            ),
            allPeriodsUsd = listOf(
                PriceWidgetData(
                    pair = TradingPair.BTC_USD,
                    period = GraphPeriod.ONE_DAY,
                    change = Change(isPositive = true, formatted = "+2.5%"),
                    price = "$97,500",
                    pastValues = listOf(95000.0, 96000.0, 95500.0, 97000.0, 97500.0)
                ),
                PriceWidgetData(
                    pair = TradingPair.BTC_USD,
                    period = GraphPeriod.ONE_WEEK,
                    change = Change(isPositive = true, formatted = "+5.0%"),
                    price = "$97,500",
                    pastValues = listOf(93000.0, 94000.0, 95000.0, 96000.0, 97500.0)
                )
            ),
            onClickReset = {},
            onClickGraph = {},
            onClickTradingPair = {},
            onClickPreview = {},
            onClickSource = {},
            preferences = PricePreferences(),
            isLoading = false
        )
    }
}
