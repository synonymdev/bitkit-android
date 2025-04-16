package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import okhttp3.internal.toLongOrDefault
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.Colors


@Composable
fun NumberPadTextField(
    input: String,
    displayUnit: BitcoinDisplayUnit,
    primaryDisplay: PrimaryDisplay,
    modifier: Modifier = Modifier,
) {
    val currency = currencyViewModel ?: return

    val satoshis = if (primaryDisplay == PrimaryDisplay.FIAT) {
        currency.convertFiatToSats(fiatAmount = input.replace(",", "").toDoubleOrNull() ?: 0.0)
            .toString()
    } else {
        input.removeSpaces()
    }

    var placeholder: String by remember { mutableStateOf("0") }
    var placeholderFractional: String by remember { mutableStateOf("") }
    var value: String by remember { mutableStateOf("") }

    LaunchedEffect(displayUnit, primaryDisplay) {
        placeholderFractional = when {
            displayUnit == BitcoinDisplayUnit.CLASSIC -> "00000000"
            primaryDisplay == PrimaryDisplay.FIAT -> "00"
            else -> ""
        }

        placeholder = if (placeholderFractional.isNotEmpty()) {
            if (input.contains(".") || primaryDisplay == PrimaryDisplay.FIAT) {
                "0.$placeholderFractional"
            } else {
                ".$placeholderFractional"
            }
        } else {
            "0"
        }

        value = ""
    }

    if (input.isNotEmpty()) {
        val parts = input.split(".")
        val whole = parts.firstOrNull().orEmpty().removeSpaces()
        val fraction = parts.getOrNull(1).orEmpty().removeSpaces()

        value = when {
            primaryDisplay == PrimaryDisplay.FIAT -> {
                if (input.contains(".")) {
                    "$whole.$fraction"
                } else {
                    whole
                }
            }

            displayUnit == BitcoinDisplayUnit.MODERN && primaryDisplay == PrimaryDisplay.BITCOIN -> {
                input.toLongOrDefault(0L).formatToModernDisplay()
            }

            else -> {
                whole
            }
        }

        placeholder = when {
            input.contains(".") -> {
                if (fraction.length < placeholderFractional.length) {
                    placeholderFractional.drop(fraction.length)
                } else {
                    ""
                }
            }

            displayUnit == BitcoinDisplayUnit.MODERN && primaryDisplay == PrimaryDisplay.BITCOIN -> ""
            else -> if (placeholderFractional.isNotEmpty()) ".$placeholderFractional" else ""
        }
    } else {
        value = ""
    }

    MoneyAmount(
        modifier = modifier,
        value = value,
        unit = primaryDisplay,
        placeholder = placeholder,
        showPlaceholder = true,
        satoshis = satoshis.toLongOrNull() ?: 0,
        currencySymbol = currency.getCurrencySymbol()
    )
}

@Composable
fun MoneyAmount(
    modifier: Modifier = Modifier,
    value: String,
    unit: PrimaryDisplay,
    placeholder: String,
    showPlaceholder: Boolean,
    satoshis: Long,
    currencySymbol: String,
) {
    Column(
        modifier = modifier.semantics { contentDescription = value },
        horizontalAlignment = Alignment.Start
    ) {

        MoneySSB(sats = satoshis, reversed = true)

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Display(
                text = if (unit == PrimaryDisplay.BITCOIN) BITCOIN_SYMBOL else currencySymbol,
                color = Colors.White64,
                modifier = Modifier.padding(end = 6.dp)
            )

            Display(
                text = if (value != placeholder) value else "",
                color = Colors.White,
            )

            Display(
                text = placeholder,
                color = if (showPlaceholder) Colors.White50 else Colors.White,
            )
        }
    }
}
