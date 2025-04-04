package to.bitkit.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.Colors
import kotlin.math.abs

@Composable
fun NumberPadTextField(
    input: String,
//    onSatsChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBitcoinSymbol: Boolean = true,
) {
    val (rates, _, _, _, displayUnit, primaryDisplay) = LocalCurrencies.current
    val currency = currencyViewModel ?: return


    val satoshis by remember {
        derivedStateOf {
            val result = if (primaryDisplay == PrimaryDisplay.FIAT) {
                currency.convertFiatToSats(fiatAmount = input.toDoubleOrNull() ?: 0.0 ).toString()
            } else {
                input
            }
            result
        }
    }

    val convertedAmount by remember {
        derivedStateOf {
            if (rates.isNotEmpty()) currency.convert(sats = satoshis.toLongOrNull() ?: 0L) else null
        }
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
            value = whole //TODO FORMAT FIAT
        }

        if (input.contains(".")) {
            placeholder = fraction //TODO GET FRACTIONAL LENGTH

            // truncate to 2 decimals for fiat
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
        satoshis = satoshis.toIntOrNull() ?: 0,
        converted = convertedAmount
    )
}

@Composable
fun MoneyAmount(
    modifier: Modifier = Modifier,
    value: String,
    converted: ConvertedAmount?,
    unit: PrimaryDisplay,
    placeholder: String,
    showPlaceholder: Boolean,
    satoshis: Int,
    onPress: () -> Unit = {},
    style: TextStyle = TextStyle.Default,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onPress)
            .semantics { contentDescription = value },
        horizontalAlignment = Alignment.Start
    ) {
//        if (showConversion && !reverse) {
//            Money(
//                sats = satoshis,
//                showSymbol = true,
//                unitType = PrimaryDisplay.FIAT,
//                modifier = Modifier.padding(bottom = 16.dp),
//                converted = converted
//            )
//        }
//
//        if (showConversion && reverse) {
//            Money(
//                sats = satoshis,
//                color = Colors.White50,
//                showSymbol = true,
//                unitType = PrimaryDisplay.FIAT,
//                converted = converted
//            )
//        }
        MoneySSB(sats = satoshis.toLong())

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

@Composable
fun Money(
    sats: Int,
    converted: ConvertedAmount?,
    unitType: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    unit: PrimaryDisplay? = null,
    showSymbol: Boolean = false,
    symbolColor: Color? = null,
    color: Color? = null,
    sign: String? = null,
    shouldRoundUp: Boolean = false,
    modifier: Modifier = Modifier,
    testID: String? = null,
) {
    val nextUnit = remember { PrimaryDisplay.FIAT } // Replace with actual state management

    val absSats = abs(sats)
    val actualUnit = unit ?: if (unitType == PrimaryDisplay.FIAT) nextUnit else PrimaryDisplay.BITCOIN
    val actualShowSymbol = showSymbol || actualUnit == PrimaryDisplay.FIAT

    // Replace with actual display value calculation
    val displayValues = remember(absSats, shouldRoundUp) {  //TODO IMPLEMENT
        DisplayValues(
            bitcoinFormatted = absSats.toString(),
            fiatFormatted = converted?.formatted.orEmpty(),
            fiatWhole = converted?.value.toString().orEmpty(),
            fiatSymbol = converted?.symbol.orEmpty()
        )
    }

    val symbol = @Composable {
        Text(
            text = if (actualUnit == PrimaryDisplay.BITCOIN) "₿" else displayValues.fiatSymbol,
            color = symbolColor ?: color ?: Colors.White50,
        )
    }

//    val text = remember(actualUnit, denomination, decimalLength, shouldHide) {
//        when {
////            shouldHide -> if (size == TextSize.Display) " • • • • • • • • •" else " • • • • •"
//            actualUnit == PrimaryDisplay.FIAT -> displayValues.fiatFormatted
//            }
//            actualUnit == PrimaryDisplay.BITCOIN && denomination == BitcoinDisplayUnit.CLASSIC -> {
//                if (decimalLength == DecimalLength.Long) {
//                    "%.8f".format(displayValues.bitcoinFormatted.toDouble())
//                } else {
//                    "%.5f".format(displayValues.bitcoinFormatted.toDouble())
//                }
//            }
//            else -> displayValues.bitcoinFormatted
//        }
//    }

    val text = remember { displayValues.bitcoinFormatted }

    Row(
        modifier = modifier
            .then(testID?.let { Modifier.testTag(it) } ?: Modifier)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (sign != null) {
            Text(
                text = sign,
                color = color ?: Colors.White50,
                modifier = Modifier.padding(end = 3.dp),
            )
        }
        if (actualShowSymbol) {
            symbol()
        }
        Text(
            text = text,
            color = color ?: Colors.Black50,
        )
    }
}

data class DisplayValues(
    val bitcoinFormatted: String,
    val fiatFormatted: String,
    val fiatWhole: String,
    val fiatSymbol: String
)
