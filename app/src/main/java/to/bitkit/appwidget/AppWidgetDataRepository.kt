package to.bitkit.appwidget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.widgets.BlocksService
import to.bitkit.data.widgets.FactsService
import to.bitkit.data.widgets.NewsService
import to.bitkit.data.widgets.PriceService
import to.bitkit.data.widgets.WeatherService
import to.bitkit.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppWidgetDataRepository @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val priceService: PriceService,
    private val blocksService: BlocksService,
    private val weatherService: WeatherService,
    private val newsService: NewsService,
    private val factsService: FactsService,
) {
    suspend fun fetchBlockData(): Result<BlockDTO> = withContext(ioDispatcher) {
        blocksService.fetchData()
    }

    suspend fun fetchPriceData(period: GraphPeriod = GraphPeriod.ONE_DAY): Result<PriceDTO> =
        withContext(ioDispatcher) {
            priceService.fetchData(period)
        }

    suspend fun fetchWeatherData(): Result<WeatherDTO> = withContext(ioDispatcher) {
        weatherService.fetchData()
    }

    suspend fun fetchHeadlines(): Result<List<ArticleDTO>> = withContext(ioDispatcher) {
        newsService.fetchData()
    }

    suspend fun fetchFacts(): Result<List<String>> = withContext(ioDispatcher) {
        factsService.fetchData()
    }
}
