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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import okhttp3.internal.toLongOrDefault
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.CurrencyViewModel
import java.math.BigDecimal


@Composable
fun NumberPadTextField(
    input: String,
    displayUnit: BitcoinDisplayUnit,
    primaryDisplay: PrimaryDisplay,
    modifier: Modifier = Modifier,
) {

    val isPreview = LocalInspectionMode.current
    if (isPreview) {
        return MoneyAmount(
            modifier = modifier,
            value = input,
            unit = primaryDisplay,
            placeholder = "",
            showPlaceholder = true,
            satoshis = 0,
            currencySymbol = if (primaryDisplay == PrimaryDisplay.BITCOIN) BITCOIN_SYMBOL else "$"
        )
    }

    val currency = currencyViewModel ?: return

    val satoshis = if (primaryDisplay == PrimaryDisplay.FIAT) {
        currency.convertFiatToSats(fiatAmount = input.replace(",", "").toDoubleOrNull() ?: 0.0)
            ?.toString() ?: "0"
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
fun AmountInputHandler(
    input: String,
    primaryDisplay: PrimaryDisplay,
    displayUnit: BitcoinDisplayUnit,
    onInputChanged: (String) -> Unit,
    onAmountCalculated: (String) -> Unit,
    currencyVM: CurrencyViewModel
) {
    var lastDisplay by rememberSaveable { mutableStateOf(primaryDisplay) }
    LaunchedEffect(primaryDisplay) {
        if (primaryDisplay == lastDisplay) return@LaunchedEffect
        lastDisplay = primaryDisplay
        val newInput = when (primaryDisplay) {
            PrimaryDisplay.BITCOIN -> { //Convert fiat to sats
                val amountLong = currencyVM.convertFiatToSats(input.replace(",", "").toDoubleOrNull() ?: 0.0) ?: 0
                if (amountLong > 0.0) amountLong.toString() else ""
            }

            PrimaryDisplay.FIAT -> { //Convert sats to fiat
                val convertedAmount = currencyVM.convert(input.toLongOrDefault(0L))
                if ((convertedAmount?.value
                        ?: BigDecimal(0)) > BigDecimal(0)
                ) convertedAmount?.formatted.toString() else ""
            }
        }
        onInputChanged(newInput)
    }

    LaunchedEffect(input) {
        val sats = when (primaryDisplay) {
            PrimaryDisplay.BITCOIN -> {
                if (displayUnit == BitcoinDisplayUnit.MODERN) input else (input.toLongOrDefault(0L) * 100_000_000).toString()
            }

            PrimaryDisplay.FIAT -> {
                val convertedAmount = currencyVM.convertFiatToSats(input.replace(",", "").toDoubleOrNull() ?: 0.0) ?: 0L
                convertedAmount.toString()
            }
        }
        onAmountCalculated(sats)
    }
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


@Preview(name = "FIAT - Empty", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountFiatEmpty() {
    AppThemeSurface {
        MoneyAmount(
            value = "",
            unit = PrimaryDisplay.FIAT,
            placeholder = ".00",
            showPlaceholder = true,
            satoshis = 0,
            currencySymbol = "$"
        )
    }
}

@Preview(name = "FIAT - With Value", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountFiatWithValue() {
    AppThemeSurface {
        MoneyAmount(
            value = "125.50",
            unit = PrimaryDisplay.FIAT,
            placeholder = "",
            showPlaceholder = true,
            satoshis = 12550000000,
            currencySymbol = "$"
        )
    }
}

@Preview(name = "BITCOIN - Modern Empty", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountBitcoinModernEmpty() {
    AppThemeSurface {
        MoneyAmount(
            value = "",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = ".00000000",
            showPlaceholder = true,
            satoshis = 0,
            currencySymbol = "₿"
        )
    }
}

@Preview(name = "BITCOIN - Modern With Value", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountBitcoinModernWithValue() {
    AppThemeSurface {
        MoneyAmount(
            value = "1.25",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "00000",
            showPlaceholder = true,
            satoshis = 125000000,
            currencySymbol = "₿"
        )
    }
}

@Preview(name = "BITCOIN - Classic Empty", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountBitcoinClassicEmpty() {
    AppThemeSurface {
        MoneyAmount(
            value = "",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = ".00000000",
            showPlaceholder = true,
            satoshis = 0,
            currencySymbol = "₿"
        )
    }
}

@Preview(name = "BITCOIN - Classic With Value", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountBitcoinClassicWithValue() {
    AppThemeSurface {
        MoneyAmount(
            value = "125000000",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "",
            showPlaceholder = true,
            satoshis = 125000000,
            currencySymbol = "₿"
        )
    }
}

@Preview(name = "FIAT - Partial Input", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountFiatPartial() {
    AppThemeSurface {
        MoneyAmount(
            value = "125.",
            unit = PrimaryDisplay.FIAT,
            placeholder = "00",
            showPlaceholder = true,
            satoshis = 12500000000,
            currencySymbol = "$"
        )
    }
}

@Preview(name = "BITCOIN - Partial Input", group = "MoneyAmount", showBackground = true)
@Composable
fun PreviewMoneyAmountBitcoinPartial() {
    AppThemeSurface {
        MoneyAmount(
            value = "1.25",
            unit = PrimaryDisplay.BITCOIN,
            placeholder = "00000",
            showPlaceholder = true,
            satoshis = 125000000,
            currencySymbol = "₿"
        )
    }
}
