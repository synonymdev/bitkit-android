package to.bitkit.ui.components

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
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel

// TODO: add fiat support
@Composable
fun NumberPadTextField(
    sats: Long,
    modifier: Modifier = Modifier,
) {
    val currency = currencyViewModel ?: return
    val (rates, _, _, _, displayUnit, _) = LocalCurrencies.current
    val converted: ConvertedAmount? = if (rates.isNotEmpty()) currency.convert(sats = sats) else null

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier
    ) {
        converted?.let { converted ->
            val btcComponents = converted.bitcoinDisplay(displayUnit)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(62.dp)
            ) {
                Display(
                    text = btcComponents.symbol,
                    modifier = Modifier
                        .alpha(0.6f)
                        .padding(end = 8.dp)
                )
                Display(text = btcComponents.value)
            }
        }
    }
}
