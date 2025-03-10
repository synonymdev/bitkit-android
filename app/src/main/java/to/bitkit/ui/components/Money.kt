package to.bitkit.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
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

    var isPressed by remember { mutableStateOf(false) }

    currency.convert(sats)?.let { converted ->
        val displayText = if (currencies.primaryDisplay == PrimaryDisplay.BITCOIN) {
            val btcComponents = converted.bitcoinDisplay(currencies.displayUnit)
            "<accent>${btcComponents.symbol}</accent> ${btcComponents.value}"
        } else {
            "<accent>${converted.symbol}</accent> ${converted.formatted}"
        }

        Display(
            text = displayText.withAccent(accentColor = Colors.White64),
            modifier = Modifier
                .graphicsLayer { this.alpha = if (isPressed) 0.5f else 1f }
                .then(
                    if (onClick != null) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = true
                                    tryAwaitRelease()
                                    isPressed = false
                                },
                                onTap = { onClick() }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
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
