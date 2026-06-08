package to.bitkit.ui.screens.widgets.calculator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.WidgetSize
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCardSmall
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.screens.widgets.components.WidgetSizeCarousel
import to.bitkit.ui.screens.widgets.components.widgetSheetContent
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Insets

@Composable
fun CalculatorPreviewScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalculatorViewModel = hiltViewModel(),
) {
    val isCalculatorWidgetEnabled by viewModel.isCalculatorWidgetEnabled.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val draftSize by viewModel.draftSize.collectAsStateWithLifecycle()

    CalculatorPreviewContent(
        onBack = onBack,
        isCalculatorWidgetEnabled = isCalculatorWidgetEnabled,
        uiState = uiState,
        onClickDelete = {
            viewModel.removeWidget(onComplete = onClose)
        },
        onClickSave = {
            viewModel.saveWidget(onComplete = onClose)
        },
        initialSize = draftSize,
        onSizeSelected = viewModel::setSize,
        modifier = modifier
    )
}

@Composable
fun CalculatorPreviewContent(
    onBack: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isCalculatorWidgetEnabled: Boolean,
    modifier: Modifier = Modifier,
    uiState: CalculatorUiState = CalculatorUiState(),
    initialSize: WidgetSize = WidgetSize.SMALL,
    onSizeSelected: (WidgetSize) -> Unit = {},
) {
    Column(
        modifier = modifier
            .widgetSheetContent()
            .testTag("calculator_preview_screen")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.widgets__calculator__name),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.widgets__calculator__description).replace(
                    "{fiatSymbol}",
                    uiState.currencySymbol,
                ),
                color = Colors.White64,
                modifier = Modifier.testTag("widget_description")
            )

            VerticalSpacer(16.dp)

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            WidgetSizeCarousel(
                smallContent = {
                    CalculatorCardSmall(
                        btcPrimaryDisplayUnit = uiState.displayUnit,
                        btcValue = uiState.btcValue,
                        fiatSymbol = uiState.currencySymbol,
                        fiatValue = uiState.fiatValue,
                        modifier = Modifier
                            .size(WidgetCardDimens.COMPACT_CARD_SIZE)
                            .testTag("calculator_card_small")
                    )
                },
                wideContent = {
                    CalculatorCard(
                        btcPrimaryDisplayUnit = uiState.displayUnit,
                        btcValue = uiState.btcValue,
                        fiatSymbol = uiState.currencySymbol,
                        fiatName = uiState.selectedCurrency,
                        fiatValue = uiState.fiatValue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("calculator_card_wide")
                    )
                },
                initialSize = initialSize,
                onSizeSelected = onSizeSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calculator_preview_carousel")
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = Insets.Bottom + 16.dp,
                    top = 16.dp,
                )
                .fillMaxWidth()
                .testTag("buttons_row")
        ) {
            if (isCalculatorWidgetEnabled) {
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

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        CalculatorPreviewContent(
            onBack = {},
            onClickDelete = {},
            onClickSave = {},
            isCalculatorWidgetEnabled = false,
            uiState = CalculatorUiState(
                btcValue = "10000",
                fiatValue = "6.25",
                displayUnit = BitcoinDisplayUnit.MODERN,
                currencySymbol = "$",
                selectedCurrency = "USD",
            ),
        )
    }
}
