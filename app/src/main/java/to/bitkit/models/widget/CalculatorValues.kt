package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class CalculatorValues(
    val btcValue: String = "",
    val fiatValue: String = "",
)
