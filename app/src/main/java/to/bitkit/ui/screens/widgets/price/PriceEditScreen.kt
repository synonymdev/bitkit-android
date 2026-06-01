package to.bitkit.ui.screens.widgets.price

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.ext.label
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PriceEditScreen(
    viewModel: PriceViewModel,
    onBack: () -> Unit,
    navigatePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customPreferences by viewModel.customPreferences.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    PriceEditContent(
        onBack = onBack,
        preferences = customPreferences,
        onClickReset = { viewModel.resetCustomPreferences() },
        onClickPreview = navigatePreview,
        onSelectTradingPair = { viewModel.selectTradingPair(pair = it) },
        onSelectPeriod = { viewModel.setPeriod(period = it) },
        isLoading = isLoading,
        modifier = modifier
    )
}

@Composable
fun PriceEditContent(
    onBack: () -> Unit,
    onClickReset: () -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onSelectTradingPair: (TradingPair) -> Unit,
    onClickPreview: () -> Unit,
    preferences: PricePreferences,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val selectedPair = preferences.enabledPairs.firstOrNull() ?: TradingPair.BTC_USD

    ScreenColumn(
        modifier = modifier.testTag("price_edit_screen")
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

                Caption13Up(
                    text = stringResource(R.string.appwidget__price__currency),
                    color = Colors.White64,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                for (pair in TradingPair.entries) {
                    SelectableRow(
                        label = pair.displayName,
                        isSelected = pair == selectedPair,
                        onClick = { onSelectTradingPair(pair) },
                        testTagPrefix = pair.displayName,
                    )
                }

                VerticalSpacer(16.dp)

                Caption13Up(
                    text = stringResource(R.string.appwidget__price__timeframe),
                    color = Colors.White64,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                for (period in GraphPeriod.entries) {
                    SelectableRow(
                        label = period.label(),
                        isSelected = period == preferences.period,
                        onClick = { onSelectPeriod(period) },
                        testTagPrefix = period.value,
                    )
                }
            }

            Column {
                AppTopBar(
                    titleText = stringResource(R.string.widgets__price__name),
                    onBackClick = onBack,
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                Color.Transparent,
                            ),
                            tileMode = TileMode.Decal,
                        ),
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
private fun SelectableRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTagPrefix: String,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp)
                .testTag("${testTagPrefix}_setting_row")
        ) {
            BodySSB(
                text = label,
                color = if (isSelected) Colors.White else Colors.White64,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}_label")
            )
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = if (isSelected) Colors.Brand else Colors.Gray3,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("${testTagPrefix}_toggle_icon")
            )
        }

        HorizontalDivider(
            modifier = Modifier.testTag("${testTagPrefix}_divider")
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PriceEditContent(
            onBack = {},
            onClickReset = {},
            onSelectPeriod = {},
            onSelectTradingPair = {},
            onClickPreview = {},
            preferences = PricePreferences(),
            isLoading = false,
        )
    }
}
