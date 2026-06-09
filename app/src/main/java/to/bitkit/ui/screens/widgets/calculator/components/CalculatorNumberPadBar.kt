package to.bitkit.ui.screens.widgets.calculator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.MoneyType
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NavBarSpacer
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.screens.widgets.calculator.calculatorDecimalSeparator
import kotlin.time.Duration.Companion.milliseconds

private val ERROR_DELAY = 500.milliseconds

/**
 * Screen-bottom number pad for the home calculator widget, shown while an input is active. Edits the
 * active value via [onBtcChange]/[onFiatChange], reusing the same key-handling as the inline editor.
 * Mirrors iOS `CalculatorNumberPadBar`.
 */
@Composable
fun CalculatorNumberPadBar(
    activeInput: MoneyType,
    btcValue: String,
    fiatValue: String,
    btcPrimaryDisplayUnit: BitcoinDisplayUnit,
    onBtcChange: (String) -> Unit,
    onFiatChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var errorKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(errorKey) {
        if (errorKey == null) return@LaunchedEffect
        delay(ERROR_DELAY)
        errorKey = null
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        NumberPad(
            onPress = { key ->
                val currentValue = currentInputValue(
                    input = activeInput,
                    btcValue = btcValue,
                    fiatValue = fiatValue,
                )
                val nextValue = nextInputValue(
                    input = activeInput,
                    key = key,
                    btcValue = btcValue,
                    btcPrimaryDisplayUnit = btcPrimaryDisplayUnit,
                    fiatValue = fiatValue,
                )

                if (nextValue == currentValue && key != KEY_DELETE) {
                    errorKey = key
                    return@NumberPad
                }
                errorKey = null

                when (activeInput) {
                    MoneyType.BITCOIN -> onBtcChange(nextValue)
                    MoneyType.FIAT -> onFiatChange(nextValue)
                }
            },
            type = when (activeInput) {
                MoneyType.BITCOIN if btcPrimaryDisplayUnit.isModern() -> NumberPadType.INTEGER
                else -> NumberPadType.DECIMAL
            },
            decimalSeparator = calculatorDecimalSeparator(),
            errorKey = errorKey,
            includeNavigationBarsPadding = true,
            onDeleteLongPress = {
                errorKey = null
                when (activeInput) {
                    MoneyType.BITCOIN -> onBtcChange("")
                    MoneyType.FIAT -> onFiatChange("")
                }
            },
            modifier = Modifier.testTag("CalculatorNumberPad")
        )
        NavBarSpacer(modifier = Modifier.background(MaterialTheme.colorScheme.background))
    }
}
