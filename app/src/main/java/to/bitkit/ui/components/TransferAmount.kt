package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun TransferAmount(
    defaultValue: Long = 0,
    primaryDisplay: PrimaryDisplay,
    overrideSats: Long? = null,
    onSatsChange: (Long) -> Unit,
) {
    val currency = currencyViewModel ?: return

    var satsAmount by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = if (defaultValue > 0) defaultValue.toString() else ""
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var fiatAmount by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val text = if (primaryDisplay == PrimaryDisplay.FIAT) "0" else ""
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }

    val satsFocus = remember { FocusRequester() }
    val fiatFocus = remember { FocusRequester() }

    // Compute sats value
    val sats = satsAmount.text.toLongOrNull() ?: 0

    // Handle overrideSats changes
    LaunchedEffect(overrideSats) {
        overrideSats?.let { exactSats ->
            satsAmount = satsAmount.copy(exactSats.toString(), TextRange(exactSats.toString().length))
            onSatsChange(exactSats)

            // Update fiat amount if needed
            currency.convert(exactSats)?.let { converted ->
                fiatAmount = satsAmount.copy(converted.formatted, TextRange(converted.formatted.length))
            }
        }
    }

    // Handle primaryDisplay changes
    LaunchedEffect(primaryDisplay) {
        if (primaryDisplay == PrimaryDisplay.BITCOIN) {
            satsFocus.requestFocus()
        } else {
            fiatFocus.requestFocus()
            // Reset fiat amount to empty string if sats are 0
            if (sats == 0L) {
                fiatAmount = fiatAmount.copy("", TextRange(0))
            }
        }
    }

    // Initial setup
    LaunchedEffect(Unit) {
        if (primaryDisplay == PrimaryDisplay.BITCOIN) {
            satsFocus.requestFocus()
        } else {
            fiatFocus.requestFocus()
        }
        // Initialize fiat amount if we have a default sats value
        if (sats > 0) {
            currency.convert(sats)?.let { converted ->
                fiatAmount = fiatAmount.copy(converted.formatted, TextRange(converted.formatted.length))
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        val currencies = LocalCurrencies.current
        val displayUnit = currencies.displayUnit

        // Hidden Bitcoin TextField
        TextField(
            value = satsAmount,
            onValueChange = { newValue ->
                if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                    val filtered = newValue.text.filter {
                        if (displayUnit == BitcoinDisplayUnit.MODERN) it.isDigit()
                        else it.isDigit() || it == '.'
                    }

                    val newSatsAmount = if (displayUnit == BitcoinDisplayUnit.CLASSIC) {
                        // Limit to 8 decimal places for classic
                        val components = filtered.split(".")
                        if (components.size == 2 && components[1].length > 8) {
                            "${components[0]}.${components[1].take(8)}"
                        } else {
                            filtered
                        }
                    } else {
                        filtered
                    }

                    satsAmount = TextFieldValue(newSatsAmount, TextRange(newSatsAmount.length))
                    onSatsChange(newSatsAmount.toLongOrNull() ?: 0)

                    // Update fiat amount
                    currency.convert(newSatsAmount.toLongOrNull() ?: 0)?.let { converted ->
                        fiatAmount = TextFieldValue(converted.formatted, TextRange(converted.formatted.length))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .focusRequester(satsFocus)
                .alpha(0f)
                .size(1.dp)
        )

        // Hidden Fiat TextField
        TextField(
            value = fiatAmount,
            onValueChange = { newValue ->
                if (primaryDisplay == PrimaryDisplay.FIAT) {
                    // Allow one decimal point for fiat
                    val filtered = newValue.text.filter { it.isDigit() || it == '.' }
                    val newFiatAmount = if (filtered.split(".").size > 2) {
                        filtered.dropLast(1)
                    } else {
                        // Limit to 2 decimal places for fiat
                        val components = filtered.split(".")
                        if (components.size == 2 && components[1].length > 2) {
                            "${components[0]}.${components[1].take(2)}"
                        } else {
                            filtered
                        }
                    }

                    fiatAmount = TextFieldValue(newFiatAmount, TextRange(newFiatAmount.length))

                    newFiatAmount.toDoubleOrNull()?.let { fiatDouble ->
                        currency.convertFiatToSats(fiatAmount = fiatDouble)?.let { convertedSats ->
                            val satsString = convertedSats.toString()
                            satsAmount = TextFieldValue(satsString, TextRange(satsString.length))
                            onSatsChange(convertedSats)
                        }
                    } ?: run {
                        satsAmount = TextFieldValue("", TextRange(0))
                        onSatsChange(0)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .focusRequester(fiatFocus)
                .alpha(0f)
                .size(1.dp)
        )

        // Visible balance display
        currency.convert(sats)?.let { converted ->
            val displayText = if (primaryDisplay == PrimaryDisplay.BITCOIN) {
                val btcComponents = converted.bitcoinDisplay(displayUnit)
                "<accent>${btcComponents.symbol}</accent> ${btcComponents.value}"
            } else {
                "<accent>${converted.symbol}</accent> ${fiatAmount.text.ifEmpty { "0" }}"
            }

            Display(
                text = displayText.withAccent(accentColor = Colors.White64),
                modifier = Modifier
                    .clickableAlpha { currency.togglePrimaryDisplay() }
            )
        }
    }
}
