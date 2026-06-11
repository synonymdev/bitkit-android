package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.ui.screens.widgets.calculator.calculatorBtcValueToSats

@Serializable
data class CalculatorValues(
    val btcValue: String = "10000",
    val fiatValue: String = "",
    val satsValue: Long? = null,
    val displayUnit: BitcoinDisplayUnit? = null,
)

internal fun CalculatorValues.resolveCalculatorSatsValue(): Long {
    satsValue?.let { return it }
    if (btcValue.isEmpty()) return 0L
    return calculatorBtcValueToSats(
        btcValue = btcValue,
        displayUnit = displayUnit ?: inferLegacyCalculatorDisplayUnit(btcValue),
    )
}

private fun inferLegacyCalculatorDisplayUnit(btcValue: String): BitcoinDisplayUnit {
    val normalizedValue = btcValue.removeSpaces()
    return if (normalizedValue.any { it == '.' || it == ',' }) {
        BitcoinDisplayUnit.CLASSIC
    } else {
        BitcoinDisplayUnit.MODERN
    }
}
