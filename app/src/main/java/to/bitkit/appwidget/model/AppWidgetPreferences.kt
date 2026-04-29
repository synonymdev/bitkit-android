package to.bitkit.appwidget.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.TradingPair

enum class AppWidgetType {
    PRICE,
    HEADLINES,
}

@Stable
@Serializable
data class AppWidgetEntry(
    val appWidgetId: Int,
    val type: AppWidgetType,
    val pricePreferences: HomePricePreferences = HomePricePreferences(),
    val headlinePreferences: HomeHeadlinePreferences = HomeHeadlinePreferences(),
)

@Stable
@Serializable
data class HomePricePreferences(
    val enabledPairs: List<TradingPair> = listOf(TradingPair.BTC_USD),
    val period: GraphPeriod = GraphPeriod.ONE_DAY,
)

@Stable
@Serializable
data class HomeHeadlinePreferences(
    val showTime: Boolean = true,
    val showSource: Boolean = true,
)

@Stable
@Serializable
data class AppWidgetData(
    val entries: List<AppWidgetEntry> = emptyList(),
    val cachedPrices: Map<GraphPeriod, PriceDTO> = emptyMap(),
    val cachedArticles: List<ArticleDTO> = emptyList(),
)
