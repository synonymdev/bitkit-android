package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun MoneyDisplay(
    sats: Long,
    onClick: (() -> Unit)? = null,
) {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        val displayText = "<accent>₿</accent> ${sats.formatToModernDisplay()}"
        Display(text = displayText.withAccent(accentColor = Colors.White64))
        return
    }

    val currency = currencyViewModel ?: return
    val currencies = LocalCurrencies.current

    currency.convert(sats)?.let { converted ->
        val displayText = if (currencies.primaryDisplay == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            "<accent>${btcComponents.symbol}</accent> ${btcComponents.value}"
        } else {
            "<accent>${converted.symbol}</accent> ${converted.formatted}"
        }

        Display(
            text = displayText.withAccent(accentColor = Colors.White64),
            modifier = Modifier.clickableAlpha(onClick = onClick)
        )
    }
}

@Composable
fun MoneySSB(sats: Long) {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        val displayText = "<accent>₿</accent> ${sats.formatToModernDisplay()}"
        BodySSB(text = displayText.withAccent(accentColor = Colors.White64))
        return
    }

    val currency = currencyViewModel ?: return
    val currencies = LocalCurrencies.current

    currency.convert(sats)?.let { converted ->
        val displayText = if (currencies.primaryDisplay == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            "<accent>${btcComponents.symbol}</accent> ${btcComponents.value}"
        } else {
            "<accent>${converted.symbol}</accent> ${converted.formatted}"
        }

        BodySSB(text = displayText.withAccent(accentColor = Colors.White64))
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
        return
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
