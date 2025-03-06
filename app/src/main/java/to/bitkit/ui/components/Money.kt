package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel

@Composable
fun MoneySSB(sats: Long) {
    val currency = currencyViewModel ?: return
    val currencies = LocalCurrencies.current

    currency.convert(sats)?.let { converted ->
        if (currencies.primaryDisplay == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            BodySSB(text = "${btcComponents.symbol} ${btcComponents.value}")
        } else {
            BodySSB(text = "${converted.symbol} ${converted.formatted}")
        }
    }
}
