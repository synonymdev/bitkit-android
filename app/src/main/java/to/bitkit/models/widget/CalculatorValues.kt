package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.models.BitcoinDisplayUnit

@Serializable
data class CalculatorValues(
    val btcValue: String = "10000",
    val fiatValue: String = "",
    val satsValue: Long? = null,
    val displayUnit: BitcoinDisplayUnit? = null,
)
