package to.bitkit.ui.screens.widgets.price

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Composable
fun PriceEditScreen(
    viewModel: PriceViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigatePreview: () -> Unit
) {
    val customPreferences by viewModel.customPreferences.collectAsStateWithLifecycle()
    val currentPrice by viewModel.currentPrice.collectAsStateWithLifecycle()
    val allPeriodsUsd by viewModel.allPeriodsUsd.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    PriceEditContent(
        onClose = onClose,
        onBack = onBack,
        preferences = customPreferences,
        onClickReset = { viewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
        allPeriodsUsd = allPeriodsUsd,
        priceModel = currentPrice ?: PriceDTO(
            widgets = listOf()
        ),
        onClickTradingPair = { pair ->
            viewModel.toggleTradingPair(pair = pair)
        },
        onClickGraph = { period ->
            viewModel.setPeriod(period = period)
        },
        isLoading = isLoading
    )
}

@Composable
fun PriceEditContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    priceModel: PriceDTO,
    allPeriodsUsd: List<PriceWidgetData>,
    onClickReset: () -> Unit,
    onClickGraph: (GraphPeriod) -> Unit,
    onClickTradingPair: (TradingPair) -> Unit,
    onClickPreview: () -> Unit,
    preferences: PricePreferences,
    isLoading: Boolean,
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
                    stringResource(R.string.widgets__price__name)
                ),
                color = Colors.White64,
                modifier = Modifier.testTag("edit_description")
            )

            Spacer(modifier = Modifier.height(32.dp))

            priceModel.widgets.map { data ->
                PriceEditOptionRow(
                    label = data.pair.displayName,
                    value = data.price,
                    isEnabled = data.pair in preferences.enabledPairs,
                    onClick = {
                        onClickTradingPair(data.pair)
                    },
                    testTagPrefix = data.pair.displayName
                )
            }

            allPeriodsUsd.map { priceData ->
                PriceChartOptionRow(
                    widgetData = priceData,
                    isEnabled = priceData.period == preferences.period,
                    onClick = onClickGraph,
                    testTagPrefix = priceData.period.name
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
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                modifier = Modifier
                    .weight(1f)
                    .testTag("reset_button"),
                enabled = preferences != PricePreferences(),
                fullWidth = false,
                onClick = onClickReset
            )

            PrimaryButton(
                text = stringResource(R.string.common__preview),
                modifier = Modifier
                    .weight(1f)
                    .testTag("preview_button"),
                fullWidth = false,
                isLoading = isLoading,
                onClick = onClickPreview
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

@Composable
private fun PriceChartOptionRow(
    widgetData: PriceWidgetData,
    isEnabled: Boolean,
    onClick: (GraphPeriod) -> Unit,
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
            ChartComponent(
                widgetData = widgetData,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = { onClick(widgetData.period) },
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
