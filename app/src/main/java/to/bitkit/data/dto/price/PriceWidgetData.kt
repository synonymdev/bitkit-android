package to.bitkit.data.dto.price

import kotlinx.serialization.Serializable

@Serializable
data class PriceWidgetData(
    val pair: TradingPair,
    val period: GraphPeriod,
    val change: Change,
    val price: String,
    val pastValues: List<Double>
)
