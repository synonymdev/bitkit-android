package to.bitkit.appwidget.model

import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.TradingPair

enum class AppWidgetType {
    BLOCKS,
    PRICE,
    WEATHER,
    HEADLINES,
    FACTS,
}

@Serializable
data class AppWidgetEntry(
    val appWidgetId: Int,
    val type: AppWidgetType,
    val blocksPreferences: HomeBlocksPreferences = HomeBlocksPreferences(),
    val pricePreferences: HomePricePreferences = HomePricePreferences(),
    val weatherPreferences: HomeWeatherPreferences = HomeWeatherPreferences(),
    val headlinesPreferences: HomeHeadlinesPreferences = HomeHeadlinesPreferences(),
    val factsPreferences: HomeFactsPreferences = HomeFactsPreferences(),
)

@Serializable
data class HomeBlocksPreferences(
    val showBlock: Boolean = true,
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showTransactions: Boolean = false,
    val showSize: Boolean = false,
    val showSource: Boolean = false,
)

@Serializable
data class HomePricePreferences(
    val enabledPairs: List<TradingPair> = listOf(TradingPair.BTC_USD),
    val period: GraphPeriod = GraphPeriod.ONE_DAY,
    val showSource: Boolean = false,
)

@Serializable
data class HomeWeatherPreferences(
    val showTitle: Boolean = true,
    val showDescription: Boolean = false,
    val showCurrentFee: Boolean = false,
    val showNextBlockFee: Boolean = false,
)

@Serializable
data class HomeHeadlinesPreferences(
    val showTime: Boolean = true,
    val showSource: Boolean = true,
)

@Serializable
data class HomeFactsPreferences(
    val showSource: Boolean = false,
)

@Serializable
data class AppWidgetData(
    val entries: List<AppWidgetEntry> = emptyList(),
    val cachedBlock: BlockDTO? = null,
    val cachedPrice: PriceDTO? = null,
    val cachedWeather: WeatherDTO? = null,
    val cachedArticles: List<ArticleDTO> = emptyList(),
    val cachedFacts: List<String> = emptyList(),
)
