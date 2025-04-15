package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.internal.toLongOrDefault
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.formatCurrency

@Composable
fun NumberPadTextField(
    input: String,
    modifier: Modifier = Modifier,
) {
    val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val currency = currencyViewModel ?: return

    val satoshis = if (primaryDisplay == PrimaryDisplay.FIAT) {
        currency.convertFiatToSats(fiatAmount = input.replace(",", "").toDoubleOrNull() ?: 0.0).toString()
    } else {
        input.removeSpaces()
    }

    var placeholder: String by remember { mutableStateOf("0") }
    var placeholderFractional: String by remember { mutableStateOf("") }
    var value: String by remember { mutableStateOf("") }

    if (displayUnit == BitcoinDisplayUnit.CLASSIC) {
        placeholderFractional = "00000000"
    }

    if (primaryDisplay == PrimaryDisplay.FIAT) {
        placeholderFractional = "00"
    }

    if (placeholderFractional.isNotEmpty()) {
        placeholder = "0.$placeholderFractional"
    }

    if (input.isNotEmpty()) {
        val whole = input.split(".").firstOrNull().orEmpty().removeSpaces()
        val fraction = input.split(".").getOrNull(1).orEmpty().removeSpaces()

        if (primaryDisplay == PrimaryDisplay.FIAT) {
            value = whole
        }

        if (input.contains(".")) {
            placeholder = ""
            if (placeholderFractional.length >= fraction.length) {
                placeholder = placeholderFractional.drop(fraction.length)
            }

            if (primaryDisplay == PrimaryDisplay.FIAT) {
                value = "$whole.$fraction"
            }
        } else {
            if (displayUnit == BitcoinDisplayUnit.MODERN && primaryDisplay == PrimaryDisplay.BITCOIN) {
                value = input.toLongOrDefault(0L).formatToModernDisplay()
                placeholder = ""
            } else {
                placeholder = ".$placeholderFractional"
            }
        }
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
    style: TextStyle = TextStyle.Default,
) {
    Column(
        modifier = modifier.semantics { contentDescription = value },
        horizontalAlignment = Alignment.Start
    ) {

        MoneySSB(sats = satoshis, reversed = true)

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (unit == PrimaryDisplay.BITCOIN) BITCOIN_SYMBOL else currencySymbol,
                style = style.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 44.sp,
                    letterSpacing = (-1).sp
                ),
                color = Colors.White,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = if (value != placeholder) value else "",
                color = Colors.White,
                style = style.copy(
                    fontSize = 44.sp,
                    letterSpacing = (-1).sp
                )
            )
            Text(
                text = placeholder,
                color = if (showPlaceholder) Colors.White50 else Colors.White,
                style = style.copy(
                    fontSize = 44.sp,
                    letterSpacing = (-1).sp
                )
            )
        }
    }
}
