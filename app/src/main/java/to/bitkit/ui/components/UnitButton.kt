package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun UnitButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    color: Color = Colors.Brand,
    primaryDisplay: PrimaryDisplay = LocalCurrencies.current.primaryDisplay,
) {
    val currency = currencyViewModel
    val currencies = LocalCurrencies.current
    val text = if (primaryDisplay == PrimaryDisplay.BITCOIN) "Bitcoin" else currencies.selectedCurrency

    NumberPadActionButton(
        text = text,
        color = color,
        onClick = {
            currency?.togglePrimaryDisplay()
            onClick()
        },
        icon = R.drawable.ic_transfer,
        modifier = modifier,
    )
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            UnitButton(primaryDisplay = PrimaryDisplay.BITCOIN)
            UnitButton(primaryDisplay = PrimaryDisplay.FIAT)
        }
    }
}
