package to.bitkit.models.widget

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair

@Stable
@Serializable
data class PricePreferences(
    @Stable val enabledPairs: List<TradingPair> = listOf(
        TradingPair.BTC_USD
    ),
    val period: GraphPeriod? = GraphPeriod.ONE_DAY,
)
