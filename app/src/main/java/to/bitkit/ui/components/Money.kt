package to.bitkit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.STUB_RATE
import to.bitkit.models.asBtc
import to.bitkit.models.formatCurrency
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import java.math.BigDecimal

@Composable
fun MoneyDisplay(
    sats: Long,
    onClick: (() -> Unit)? = null,
) {
    rememberMoneyText(sats)?.let { text ->
        Display(
            text = text.withAccent(accentColor = Colors.White64),
            modifier = Modifier
                .clickableAlpha(onClick = onClick)
                .testTag("MoneyText")
        )
    }
}

@Composable
fun MoneySSB(
    sats: Long,
    modifier: Modifier = Modifier,
    unit: PrimaryDisplay = LocalCurrencies.current.primaryDisplay,
    color: Color = MaterialTheme.colorScheme.primary,
    accent: Color = Colors.White64,
    showSymbol: Boolean = false,
) {
    rememberMoneyText(sats = sats, unit = unit, showSymbol = showSymbol)?.let { text ->
        BodySSB(
            text = text.withAccent(accentColor = accent),
            color = color,
            modifier = modifier.testTag("MoneyText")
        )
    }
}

@Composable
fun MoneyMSB(
    sats: Long,
    modifier: Modifier = Modifier,
    unit: PrimaryDisplay = LocalCurrencies.current.primaryDisplay,
    color: Color = MaterialTheme.colorScheme.primary,
    accent: Color = Colors.White64,
) {
    rememberMoneyText(sats = sats, unit = unit)?.let { text ->
        BodyMSB(
            text = text.withAccent(accentColor = accent),
            color = color,
            modifier = modifier.testTag("MoneyText")
        )
    }
}

@Composable
fun MoneyCaptionB(
    sats: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    symbol: Boolean = false,
    symbolColor: Color = Colors.White64,
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
            modifier = modifier.testTag("MoneyText")
        )
    }
}

@Composable
fun rememberMoneyText(
    sats: Long,
    reversed: Boolean = false,
    currencies: CurrencyState = LocalCurrencies.current,
    unit: PrimaryDisplay = if (reversed) currencies.primaryDisplay.not() else currencies.primaryDisplay,
    showSymbol: Boolean = unit == PrimaryDisplay.FIAT,
): String? {
    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        val symbol = if (unit == PrimaryDisplay.BITCOIN) BITCOIN_SYMBOL else "$"
        return buildString {
            if (showSymbol) append("<accent>$symbol</accent> ")
            if (unit == PrimaryDisplay.BITCOIN) {
                append(sats.formatToModernDisplay())
            } else {
                val fiatValue = sats.asBtc().multiply(BigDecimal.valueOf(STUB_RATE))
                append(fiatValue.formatCurrency() ?: "0.00")
            }
        }
    }

    val currency = currencyViewModel ?: return null

    return remember(currencies, sats, unit) {
        val converted = currency.convert(sats) ?: return@remember null

        if (unit == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            buildString {
                if (showSymbol) append("<accent>${btcComponents.symbol}</accent> ")
                append(btcComponents.value)
            }
        } else {
            buildString {
                if (showSymbol) append("<accent>${converted.symbol}</accent> ")
                append(converted.formatted)
            }
        }
    }
}
