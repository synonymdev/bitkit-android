package to.bitkit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    rememberMoneyText(sats)?.let { text ->
        Display(
            text = text.withAccent(accentColor = Colors.White64),
            modifier = Modifier.clickableAlpha(onClick = onClick)
        )
    }
}

@Composable
fun MoneySSB(sats: Long) {
    rememberMoneyText(sats)?.let { text ->
        BodySSB(text = text.withAccent(accentColor = Colors.White64))
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

    val displayText = remember(currencies, sats) {
        currency.convert(sats)?.let { converted ->
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            btcComponents.value
        }
    }

    displayText?.let { text ->
        CaptionB(
            text = text,
            color = color,
        )
    }
}

/**
 * Generates a formatted representation of a monetary value based on the provided amount in satoshis
 * and the current currency display settings. Can be either in bitcoin or fiat.
 *
 * @param sats The amount in satoshis to be formatted and displayed.
 * @return A formatted string representation of the monetary value, or null if it cannot be generated.
 */
@Composable
fun rememberMoneyText(
    sats: Long,
): String? {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        return "<accent>₿</accent> ${sats.formatToModernDisplay()}"
    }

    val currency = currencyViewModel ?: return null
    val currencies = LocalCurrencies.current

    return remember(currencies, sats) {
        val converted = currency.convert(sats) ?: return@remember null

        if (currencies.primaryDisplay == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            "<accent>${btcComponents.symbol}</accent> ${btcComponents.value}"
        } else {
            "<accent>${converted.symbol}</accent> ${converted.formatted}"
        }
    }
}
