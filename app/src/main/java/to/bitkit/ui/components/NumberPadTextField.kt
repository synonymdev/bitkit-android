package to.bitkit.ui.components

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.Colors

@Composable
fun NumberPadTextField(
    input: String,
//    onSatsChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBitcoinSymbol: Boolean = true,
) {
    val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val currency = currencyViewModel ?: return

    val satoshis = if (primaryDisplay == PrimaryDisplay.FIAT) {
        currency.convertFiatToSats(fiatAmount = input.toDoubleOrNull() ?: 0.0).toString()
    } else {
        input
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
            //todo replace the integer part with the whole formated one
            value = whole //TODO FORMAT FIAT
        }

        if (input.contains(".")) {
            placeholder = fraction //TODO GET FRACTIONAL LENGTH

            if (primaryDisplay == PrimaryDisplay.FIAT) {
                value = "$whole.$fraction" //TODO FORMAT MONEY
            }
        } else {
            if (displayUnit == BitcoinDisplayUnit.MODERN && primaryDisplay == PrimaryDisplay.BITCOIN) {
                value = input //TODO FORMAT SATOSHI
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
    onPress: () -> Unit = {},
    style: TextStyle = TextStyle.Default,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onPress)
            .semantics { contentDescription = value },
        horizontalAlignment = Alignment.Start
    ) {

        MoneySSB(sats = satoshis, reversed = true)

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (unit == PrimaryDisplay.BITCOIN) "₿" else "$",
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
