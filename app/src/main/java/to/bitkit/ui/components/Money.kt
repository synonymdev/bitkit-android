package to.bitkit.ui.components

import androidx.compose.material3.MaterialTheme
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
fun MoneySSB(
    sats: Long,
    reversed: Boolean = false,
) {
    rememberMoneyText(sats = sats, reversed = reversed)?.let { text ->
        BodySSB(text = text.withAccent(accentColor = Colors.White64))
    }
}

@Composable
fun MoneyCaptionB(
    sats: Long,
    color: Color = MaterialTheme.colorScheme.primary,
    symbol: Boolean = false,
    symbolColor: Color = Colors.White64,
    modifier: Modifier = Modifier,
) {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        val previewText = sats.formatToModernDisplay().let { if (symbol) "<accent>₿</accent> $it" else it }
        CaptionB(text = previewText.withAccent(accentColor = symbolColor), color = color)
        return
    }

    val currency = currencyViewModel ?: return
    val currencies = LocalCurrencies.current

    val displayText = remember(currencies, sats, symbol) {
        currency.convert(sats)?.let { converted ->
            val btc = converted.bitcoinDisplay(currencies.displayUnit)
            if (symbol) {
                "<accent>${btc.symbol}</accent> ${btc.value}"
            } else {
                btc.value
            }
        }
    }

    displayText?.let { text ->
        CaptionB(
            text = text.withAccent(accentColor = symbolColor),
            color = color,
            modifier = modifier,
        )
    }
}


/**
 * Generates a formatted representation of a monetary value based on the provided amount in satoshis
 * and the current currency display settings. Can be either in bitcoin or fiat.
 *
 * @param sats The amount in satoshis to be formatted and displayed.
 * @param reversed If true, swaps the primary and secondary display. Defaults to false.
 * @return A formatted string representation of the monetary value, or null if it cannot be generated.
 */
@Composable
fun rememberMoneyText(
    sats: Long,
    reversed: Boolean = false,
): String? {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        return "<accent>₿</accent> ${sats.formatToModernDisplay()}"
    }

    val currency = currencyViewModel ?: return null
    val currencies = LocalCurrencies.current

    return remember(currencies, sats, reversed) {
        val converted = currency.convert(sats) ?: return@remember null

        val secondaryDisplay = when (currencies.primaryDisplay) {
            PrimaryDisplay.BITCOIN -> PrimaryDisplay.FIAT
            PrimaryDisplay.FIAT -> PrimaryDisplay.BITCOIN
        }

        val primary = if (reversed) secondaryDisplay else currencies.primaryDisplay

        if (primary == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            "<accent>${btcComponents.symbol}</accent> ${btcComponents.value}"
        } else {
            "<accent>${converted.symbol}</accent> ${converted.formatted}"
        }
    }
}
