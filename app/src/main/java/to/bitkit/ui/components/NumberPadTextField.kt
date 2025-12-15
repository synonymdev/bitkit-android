package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.USD_SYMBOL
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AmountInputUiState
import to.bitkit.viewmodels.AmountInputViewModel

/**
 * Amount view to be used with [NumberPad]
 */
@Composable
fun NumberPadTextField(
    viewModel: AmountInputViewModel,
    modifier: Modifier = Modifier,
    showSecondaryField: Boolean = true,
    uiState: State<AmountInputUiState> = viewModel.uiState.collectAsStateWithLifecycle(),
    currencies: CurrencyState = LocalCurrencies.current,
    onClick: (() -> Unit)? = { viewModel.switchUnit(currencies) },
) {
    MoneyAmount(
        modifier = modifier.then(Modifier.clickableAlpha(onClick = onClick)),
        value = uiState.value.text,
        unit = currencies.primaryDisplay,
        placeholder = viewModel.getPlaceholder(currencies),
        showPlaceholder = true,
        satoshis = uiState.value.sats,
        currencySymbol = currencies.currencySymbol,
        showSecondaryField = showSecondaryField,
    )
}

@Composable
private fun MoneyAmount(
    value: String,
    unit: PrimaryDisplay,
    placeholder: String,
    satoshis: Long,
    modifier: Modifier = Modifier,
    currencySymbol: String = BITCOIN_SYMBOL,
    showPlaceholder: Boolean = true,
    showSecondaryField: Boolean = true,
    valueStyle: SpanStyle = SpanStyle(color = Colors.White),
    placeholderStyle: SpanStyle = SpanStyle(color = Colors.White50),
) {
    Column(
        modifier = modifier.semantics { contentDescription = value },
        horizontalAlignment = Alignment.Start
    ) {
        if (showSecondaryField) {
            MoneySSB(sats = satoshis, unit = unit.not(), color = Colors.White64, showSymbol = true)
            VerticalSpacer(12.dp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Display(
                text = if (unit == PrimaryDisplay.BITCOIN) BITCOIN_SYMBOL else currencySymbol,
                color = Colors.White64,
                modifier = Modifier.padding(end = 6.dp)
            )
            Display(
                text = buildAnnotatedString {
                    if (value != placeholder) {
                        withStyle(valueStyle) {
                            append(value)
                        }
                    }
                    if (placeholder.isNotEmpty() && showPlaceholder) {
                        withStyle(placeholderStyle) {
                            append(placeholder)
                        }
                    }
                }
            )
        }
    }
}

@Preview()
@Composable
private fun PreviewFiatEmpty() {
    AppThemeSurface {
        MoneyAmount(
            value = "",
            unit = PrimaryDisplay.FIAT,
            placeholder = ".00",
            satoshis = 0,
            currencySymbol = USD_SYMBOL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview()
@Composable
private fun PreviewFiatPartial() {
    AppThemeSurface {
        MoneyAmount(
            value = "125.",
            unit = PrimaryDisplay.FIAT,
            placeholder = "00",
            satoshis = 1_250_000,
            currencySymbol = USD_SYMBOL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview()
@Composable
private fun PreviewFiatValue() {
    AppThemeSurface {
        MoneyAmount(
            value = "125.50",
            unit = PrimaryDisplay.FIAT,
            placeholder = "",
            satoshis = 1_250_000,
            currencySymbol = USD_SYMBOL,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview()
@Composable
private fun PreviewClassicEmpty() {
    AppThemeSurface {
        MoneyAmount(
            value = "",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "0.00000000",
            satoshis = 0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview()
@Composable
private fun PreviewClassicValue() {
    AppThemeSurface {
        MoneyAmount(
            value = "0.0025",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "0000",
            satoshis = 1_250_000,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview()
@Composable
private fun PreviewModernEmpty() {
    AppThemeSurface {
        MoneyAmount(
            value = "",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "0",
            satoshis = 0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview()
@Composable
private fun PreviewModernValue() {
    AppThemeSurface {
        MoneyAmount(
            value = 1_250_000L.formatToModernDisplay(),
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "",
            satoshis = 1_250_000,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
