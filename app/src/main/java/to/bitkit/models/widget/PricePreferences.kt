package to.bitkit.models.widget

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair

@Serializable
data class PricePreferences(
    val pairs: List<TradingPair> = listOf(),
    val period: GraphPeriod? = GraphPeriod.ONE_DAY,
    val showSource: Boolean = false
)
