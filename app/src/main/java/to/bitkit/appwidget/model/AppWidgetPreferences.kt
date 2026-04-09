package to.bitkit.appwidget.model

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.TradingPair

enum class AppWidgetType {
    PRICE,
}

@Serializable
data class AppWidgetEntry(
    val appWidgetId: Int,
    val type: AppWidgetType,
    val pricePreferences: HomePricePreferences = HomePricePreferences(),
)

@Serializable
data class HomePricePreferences(
    val enabledPairs: List<TradingPair> = listOf(TradingPair.BTC_USD),
    val period: GraphPeriod = GraphPeriod.ONE_DAY,
    val showSource: Boolean = false,
)

@Serializable
data class AppWidgetData(
    val entries: List<AppWidgetEntry> = emptyList(),
    val cachedPrice: PriceDTO? = null,
)
