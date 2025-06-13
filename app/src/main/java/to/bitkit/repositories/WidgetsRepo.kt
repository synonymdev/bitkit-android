package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.widgets.BlocksService
import to.bitkit.data.widgets.FactsService
import to.bitkit.data.widgets.NewsService
import to.bitkit.data.widgets.PriceService
import to.bitkit.data.widgets.WeatherService
import to.bitkit.data.widgets.WidgetService
import to.bitkit.di.BgDispatcher
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetsRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val newsService: NewsService,
    private val factsService: FactsService,
    private val blocksService: BlocksService,
    private val weatherService: WeatherService,
    private val priceService: PriceService,
    private val widgetsStore: WidgetsStore,
    private val settingsStore: SettingsStore,
) {
    //TODO Only refresh in loop widgets displayed in the Home
    //TODO Perform a refresh when the preview screen is displayed
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    val widgetsDataFlow = widgetsStore.data
    val showWidgetTitles = settingsStore.data.map { it.showWidgetTitles }

    val articlesFlow = widgetsStore.articlesFlow
    val factsFlow = widgetsStore.factsFlow
    val blocksFlow = widgetsStore.blocksFlow
    val weatherFlow = widgetsStore.weatherFlow
    val priceFlow = widgetsStore.priceFlow

    private val _refreshStates = MutableStateFlow(
        WidgetType.entries.associateWith { false }
    )
    val refreshStates: StateFlow<Map<WidgetType, Boolean>> = _refreshStates.asStateFlow()

    init {
        startPeriodicUpdates()
    }

    suspend fun addWidget(type: WidgetType) = withContext(bgDispatcher) { widgetsStore.addWidget(type) }

    suspend fun deleteWidget(type: WidgetType) = withContext(bgDispatcher) { widgetsStore.deleteWidget(type) }

    suspend fun updateWidgets(widgets: List<WidgetWithPosition>) = withContext(bgDispatcher) {
        widgetsStore.updateWidgets(widgets)
    }

    suspend fun updateHeadlinePreferences(preferences: HeadlinePreferences) = withContext(bgDispatcher) {
        widgetsStore.updateHeadlinePreferences(preferences)
    }

    suspend fun updateFactsPreferences(preferences: FactsPreferences) = withContext(bgDispatcher) {
        widgetsStore.updateFactsPreferences(preferences)
    }

    suspend fun updateBlocksPreferences(preferences: BlocksPreferences) = withContext(bgDispatcher) {
        widgetsStore.updateBlocksPreferences(preferences)
    }

    suspend fun updateWeatherPreferences(preferences: WeatherPreferences) = withContext(bgDispatcher) {
        widgetsStore.updateWeatherPreferences(preferences)
    }

    suspend fun updatePricePreferences(preferences: PricePreferences) = withContext(bgDispatcher) {
        widgetsStore.updatePricePreferences(preferences)
    }

    suspend fun fetchAllPeriods() = withContext(bgDispatcher) { priceService.fetchAllPeriods() }

    /**
     * Start periodic updates for all widgets
     */
    private fun startPeriodicUpdates() {
        startPeriodicUpdate(newsService) { articles ->
            widgetsStore.updateArticles(articles)
        }
        startPeriodicUpdate(factsService) { facts ->
            widgetsStore.updateFacts(facts)
        }
        startPeriodicUpdate(blocksService) { block ->
            widgetsStore.updateBlock(block)
        }
        startPeriodicUpdate(weatherService) { weather ->
            widgetsStore.updateWeather(weather)
        }
        startPeriodicUpdate(priceService) { price ->
            widgetsStore.updatePrice(price)
        }
    }

    /**
     * Generic method to start periodic updates for any widget service
     */
    private fun <T> startPeriodicUpdate(
        service: WidgetService<T>,
        updateStore: suspend (T) -> Unit
    ) {
        repoScope.launch {
            while (true) {
                updateWidget(service, updateStore)
                delay(service.refreshInterval)
            }
        }
    }

    /**
     * Update a specific widget type
     */
    private suspend fun <T> updateWidget(
        service: WidgetService<T>,
        updateStore: suspend (T) -> Unit
    ) {
        val widgetType = service.widgetType
        _refreshStates.update { it + (widgetType to true) }

        service.fetchData()
            .onSuccess { data ->
                updateStore(data)
                Logger.debug("Updated $widgetType widget successfully")
            }
            .onFailure { error ->
                Logger.warn(e = error, msg = "Failed to update $widgetType widget", context = TAG)
            }

        _refreshStates.update { it + (widgetType to false) }
    }

    /**
     * Manually refresh all widgets
     */
    suspend fun refreshAllWidgets(): Result<Unit> = runCatching {
        updateWidget(newsService) { articles ->
            widgetsStore.updateArticles(articles)
        }
        updateWidget(factsService) { facts ->
            widgetsStore.updateFacts(facts)
        }
        updateWidget(blocksService) { block ->
            widgetsStore.updateBlock(block)
        }
        updateWidget(weatherService) { weather ->
            widgetsStore.updateWeather(weather)
        }
        updateWidget(priceService) { price ->
            widgetsStore.updatePrice(price)
        }
    }

    suspend fun refreshEnabledWidgets() = withContext(bgDispatcher) {
        widgetsDataFlow.first().widgets.forEach {
            refreshWidget(it.type)
        }
    }

    /**
     * Manually refresh specific widget
     */
    suspend fun refreshWidget(widgetType: WidgetType): Result<Unit> = runCatching {
        when (widgetType) {
            WidgetType.NEWS -> updateWidget(newsService) { articles ->
                widgetsStore.updateArticles(articles)
            }

            WidgetType.WEATHER -> updateWidget(weatherService) { weather ->
                widgetsStore.updateWeather(weather)
            }

            WidgetType.PRICE -> updateWidget(priceService) { price ->
                widgetsStore.updatePrice(price)
            }

            WidgetType.BLOCK -> updateWidget(blocksService) { block ->
                widgetsStore.updateBlock(block)
            }

            WidgetType.CALCULATOR -> {
                throw NotImplementedError("Calculator widget doesn't need a service")
            }

            WidgetType.FACTS -> updateWidget(factsService) { facts ->
                widgetsStore.updateFacts(facts)
            }
        }
    }

    suspend fun updateCalculatorValues(calculatorValues: CalculatorValues) = withContext(bgDispatcher) {
        widgetsStore.updateCalculatorValues(calculatorValues)
    }

    companion object {
        private const val TAG = "WidgetsRepo"
    }
}
