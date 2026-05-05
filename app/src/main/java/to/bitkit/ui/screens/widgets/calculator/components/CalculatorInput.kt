package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun CalculatorInput(
    value: String,
    onValueChange: (String) -> Unit,
    currencySymbol: String,
    currencyName: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val displayCurrencySymbol = currencySymbol.toCalculatorDisplaySymbol()

    TextInput(
        value = value,
        singleLine = true,
        onValueChange = onValueChange,
        leadingIcon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(color = Colors.Gray6, shape = CircleShape)
                    .size(32.dp)
            ) {
                BodyMSB(displayCurrencySymbol, color = Colors.Brand)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
        ),
        suffix = { CaptionB(currencyName.uppercase(), color = Colors.Gray1) },
        colors = AppTextFieldDefaults.noIndicatorColors.copy(
            focusedContainerColor = Colors.Black,
            unfocusedContainerColor = Colors.Black
        ),
        visualTransformation = visualTransformation,
        modifier = modifier
    )
}

internal fun sanitizeIntegerInput(raw: String): String =
    raw.filter { it.isDigit() }

internal fun sanitizeDecimalInput(raw: String, locale: Locale = Locale.getDefault()): String {
    val localDecimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val normalized = if (localDecimal == ',') raw.replace(',', '.') else raw
    val filtered = normalized.filter { it.isDigit() || it == '.' }
    val dotIndex = filtered.indexOf('.')
    if (dotIndex == -1) return filtered
    return filtered.substring(0, dotIndex + 1) +
        filtered.substring(dotIndex + 1).replace(".", "")
}

internal fun String.toCalculatorDisplaySymbol(): String {
    val symbol = trim()
    return if (symbol.length >= 3) {
        symbol.take(1)
    } else {
        symbol
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            CalculatorInput(
                value = "100000",
                onValueChange = {},
                currencySymbol = "₿",
                currencyName = "BITCOIN",
                modifier = Modifier.fillMaxWidth()
            )
            CalculatorInput(
                value = "4.55",
                onValueChange = {},
                currencySymbol = "$",
                currencyName = "USD",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
