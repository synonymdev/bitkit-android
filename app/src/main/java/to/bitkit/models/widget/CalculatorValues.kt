package to.bitkit.models.widget

import kotlinx.serialization.Serializable

@Serializable
data class CalculatorValues(
    val btcValue: String = "10000",
    val fiatValue: String = "",
)
