package to.bitkit.ui.screens.transfer.components

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.handleNumberPadPress
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.CurrencyUiState

@Composable
fun TransferNumberPad(
    value: String,
    maxAmount: Long,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
    onError: (() -> Unit)? = null
) {
    var errorKey by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val conversionUnit = remember { "BTC" } // Replace with actual state
    val numberPadSettings = getNumberPadSettings()

    val handlePress: (String) -> Unit = remember(value, maxAmount) {
        { key: String ->
            val newValue = handleNumberPadPress(
                key = key,
                current = value,
                maxLength = numberPadSettings.maxLength,
                maxDecimals = numberPadSettings.maxDecimals,
            )

            val amount = convertToSats(newValue, conversionUnit)

            if (amount <= maxAmount) {
                onChange(newValue)
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                errorKey = key
                onError?.invoke()
                coroutineScope.launch {
                    delay(500)
                    errorKey = null
                }
            }
        }
    }
    NumberPad(
        type = numberPadSettings.type,
        onPress = { key -> handlePress(key) },
        errorKey = errorKey,
        modifier = modifier
            .height(380.dp)
    )
}

private fun convertToSats(value: String, unit: String): Long {
    // TODO Implement
    return value.toDoubleOrNull()?.toLong() ?: 0L
}

@Preview
@Composable
private fun TransferNumberPadPreview() {
    AppThemeSurface {
        TransferNumberPad(
            value = "0",
            maxAmount = 100_000,
            onChange = {},
            modifier = Modifier.height(380.dp)
        )
    }
}

@Composable
fun getNumberPadSettings(): NumberPadSettings {
    val currencies: CurrencyUiState = LocalCurrencies.current
    val isBtc = currencies.primaryDisplay == PrimaryDisplay.BITCOIN
    val isModern = currencies.displayUnit == BitcoinDisplayUnit.MODERN
    val isClassic = currencies.displayUnit == BitcoinDisplayUnit.CLASSIC

    val maxLength = if (isModern && isBtc) 10 else 20
    val maxDecimals = if (isClassic && isBtc) 8 else 2
    val type = if (isModern && isBtc) NumberPadType.INTEGER else NumberPadType.DECIMAL

    return NumberPadSettings(
        maxLength = maxLength,
        maxDecimals = maxDecimals,
        type = type,
    )
}

data class NumberPadSettings(
    val maxLength: Int,
    val maxDecimals: Int,
    val type: NumberPadType,
)
