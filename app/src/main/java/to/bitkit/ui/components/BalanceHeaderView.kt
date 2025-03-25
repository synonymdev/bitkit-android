package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun BalanceHeaderView(
    sats: Long,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    showBitcoinSymbol: Boolean = true,
) {
    val currency = currencyViewModel ?: return
    val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats) else null

    converted?.let { converted ->
        val btcComponents = converted.bitcoinDisplay(displayUnit)

        if (primaryDisplay == PrimaryDisplay.BITCOIN) {
            BalanceHeader(
                modifier = modifier,
                smallRowPrefix = prefix,
                smallRowText = "${converted.symbol} ${converted.formatted}",
                largeRowPrefix = prefix,
                largeRowText = btcComponents.value,
                largeRowSymbol = btcComponents.symbol,
                showSymbol = showBitcoinSymbol,
                onClick = { currency.togglePrimaryDisplay() }
            )
        } else {
            BalanceHeader(
                modifier = modifier,
                smallRowPrefix = prefix,
                smallRowText = "${btcComponents.symbol} ${btcComponents.value}",
                largeRowPrefix = prefix,
                largeRowText = converted.formatted,
                largeRowSymbol = converted.symbol,
                showSymbol = true,
                onClick = { currency.togglePrimaryDisplay() }
            )
        }
    }
}

@Composable
fun BalanceHeader(
    smallRowPrefix: String? = null,
    smallRowText: String,
    largeRowPrefix: String? = null,
    largeRowText: String,
    largeRowSymbol: String,
    showSymbol: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
        modifier = modifier.clickableAlpha { onClick() }
    ) {
        SmallRow(
            prefix = smallRowPrefix,
            text = smallRowText
        )

        Spacer(modifier = Modifier.height(16.dp))

        LargeRow(
            prefix = largeRowPrefix,
            text = largeRowText,
            symbol = largeRowSymbol,
            showSymbol = showSymbol
        )
    }
}

@Composable
fun LargeRow(prefix: String?, text: String, symbol: String, showSymbol: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix != null) {
            Display(
                text = prefix,
                color = Colors.White64,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        if (showSymbol) {
            Display(
                text = symbol,
                color = Colors.White64,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Display(text = text)
    }
}

@Composable
private fun SmallRow(prefix: String?, text: String) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (prefix != null) {
            Caption13Up(
                text = prefix,
                color = Colors.White64,
            )
        }
        Caption13Up(
            text = text,
            color = Colors.White64,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BalanceHeader(
            smallRowPrefix = "$",
            smallRowText = "27.36",
            largeRowPrefix = "₿",
            largeRowText = "136 825",
            largeRowSymbol = "sats",
            showSymbol = false,
            modifier = Modifier.fillMaxWidth(),
            onClick = {}
        )
    }
}
