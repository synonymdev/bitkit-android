package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
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

@Composable
fun MoneyCaptionB(
    sats: Long,
    color: Color,
) {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        CaptionB(text = sats.formatToModernDisplay(), color = color)
    }

    val currency = currencyViewModel ?: return
    val currencies = LocalCurrencies.current

    currency.convert(sats)?.let { converted ->
        val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
        CaptionB(
            text = btcComponents.value,
            color = color,
        )
    }
}
