package to.bitkit.appwidget.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.WeatherDataOption

enum class AppWidgetType {
    PRICE,
    HEADLINES,
    BLOCKS,
    FACTS,
    WEATHER,
}

@Stable
@Serializable
data class AppWidgetEntry(
    val appWidgetId: Int,
    val type: AppWidgetType,
    val pricePreferences: HomePricePreferences = HomePricePreferences(),
    val headlinePreferences: HomeHeadlinePreferences = HomeHeadlinePreferences(),
    val blocksPreferences: HomeBlocksPreferences = HomeBlocksPreferences(),
    val weatherPreferences: HomeWeatherPreferences = HomeWeatherPreferences(),
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
data class HomeBlocksPreferences(
    val showBlock: Boolean = true,
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showTransactions: Boolean = true,
    val showSize: Boolean = false,
    val showFees: Boolean = false,
)

@Stable
@Serializable
data class HomeWeatherPreferences(
    val selectedOption: WeatherDataOption? = WeatherDataOption.CURRENT_FEE_FIAT,
)

@Stable
@Serializable
data class AppWidgetData(
    val entries: List<AppWidgetEntry> = emptyList(),
    val cachedPrices: Map<GraphPeriod, PriceDTO> = emptyMap(),
    val cachedArticles: List<ArticleDTO> = emptyList(),
    val articleRotationTick: Int = 0,
    val cachedBlock: BlockDTO? = null,
    val cachedFacts: List<String> = emptyList(),
    val factsRotationTick: Int = 0,
    val cachedWeather: WeatherDTO? = null,
)
