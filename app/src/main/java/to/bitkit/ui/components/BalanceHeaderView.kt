package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.util.clickableAlpha

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

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .clickableAlpha { currency.togglePrimaryDisplay() }
    ) {
        converted?.let { converted ->
            if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                Column {
                    SmallRow(
                        prefix = prefix,
                        text = "${converted.symbol} ${converted.formatted}"
                    )

                    // large row
                    val btcComponents = converted.bitcoinDisplay(displayUnit)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(62.dp)
                    ) {
                        if (prefix != null) {
                            Display(
                                text = prefix,
                                modifier = Modifier
                                    .alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                        }
                        if (showBitcoinSymbol) {
                            Display(
                                text = btcComponents.symbol,
                                modifier = Modifier
                                    .alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                        }
                        Display(text = btcComponents.value)
                    }
                }
            } else {
                Column {
                    val btcComponents = converted.bitcoinDisplay(displayUnit)
                    SmallRow(
                        prefix = prefix,
                        text = "${btcComponents.symbol} ${btcComponents.value}"
                    )

                    // large row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(62.dp)
                    ) {
                        if (prefix != null) {
                            Display(
                                text = prefix,
                                modifier = Modifier
                                    .alpha(0.6f)
                                    .padding(end = 8.dp)
                            )
                        }
                        Display(
                            text = converted.symbol,
                            modifier = Modifier
                                .alpha(0.6f)
                                .padding(end = 8.dp)
                        )
                        Display(text = converted.formatted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallRow(prefix: String?, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(24.dp)
            .padding(bottom = 4.dp)
    ) {
        if (prefix != null) {
            Caption13Up(
                text = prefix,
                modifier = Modifier.alpha(0.6f)
            )
        }
        Caption13Up(
            text = text,
            modifier = Modifier.alpha(0.6f)
        )
    }
}
