package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("TooManyFunctions", "LongParameterList")
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
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())
    private val widgetJobs = ConcurrentHashMap<WidgetType, Job>()

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
        observeWidgetStateChanges()
    }

    private fun observeWidgetStateChanges() {
        repoScope.launch {
            widgetsDataFlow
                .map { it.widgets.map { widget -> widget.type }.toSet() }
                .distinctUntilChanged()
                .collect { enabledWidgetTypes ->
                    updateWidgetJobs(enabledWidgetTypes)
                }
        }
    }

    private fun updateWidgetJobs(enabledWidgetTypes: Set<WidgetType>) {
        val widgetTypesWithServices = WidgetType.entries.filter {
            it != WidgetType.CALCULATOR && it != WidgetType.SUGGESTIONS
        }

        widgetTypesWithServices.forEach { widgetType ->
            val isEnabled = widgetType in enabledWidgetTypes
            val hasRunningJob = widgetJobs.containsKey(widgetType) &&
                widgetJobs[widgetType]?.isActive == true

            when {
                isEnabled && !hasRunningJob -> startWidgetRefresh(widgetType)
                !isEnabled && hasRunningJob -> stopWidgetRefresh(widgetType)
            }
        }
    }

    private fun startWidgetRefresh(widgetType: WidgetType) {
        stopWidgetRefresh(widgetType)

        val job = when (widgetType) {
            WidgetType.NEWS -> repoScope.launch {
                while (isActive) {
                    updateWidget(newsService) { widgetsStore.updateArticles(it) }
                    delay(newsService.refreshInterval)
                }
            }

            WidgetType.FACTS -> repoScope.launch {
                while (isActive) {
                    updateWidget(factsService) { widgetsStore.updateFacts(it) }
                    delay(factsService.refreshInterval)
                }
            }

            WidgetType.BLOCK -> repoScope.launch {
                while (isActive) {
                    updateWidget(blocksService) { widgetsStore.updateBlock(it) }
                    delay(blocksService.refreshInterval)
                }
            }

            WidgetType.WEATHER -> repoScope.launch {
                while (isActive) {
                    updateWidget(weatherService) { widgetsStore.updateWeather(it) }
                    delay(weatherService.refreshInterval)
                }
            }

            WidgetType.PRICE -> repoScope.launch {
                while (isActive) {
                    updateWidget(priceService) { widgetsStore.updatePrice(it) }
                    delay(priceService.refreshInterval)
                }
            }

            WidgetType.CALCULATOR,
            WidgetType.SUGGESTIONS,
            -> throw NotImplementedError("Widget doesn't need a service")
        }

        widgetJobs[widgetType] = job
    }

    private fun stopWidgetRefresh(widgetType: WidgetType) {
        widgetJobs[widgetType]?.cancel()
        widgetJobs.remove(widgetType)
        Logger.verbose("Stopped refresh coroutine for $widgetType", context = TAG)
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

    private suspend fun <T> updateWidget(
        service: WidgetService<T>,
        updateStore: suspend (T) -> Unit,
    ) {
        val widgetType = service.widgetType
        _refreshStates.update { it + (widgetType to true) }

        service.fetchData()
            .onSuccess { data ->
                updateStore(data)
                Logger.verbose("Updated $widgetType widget successfully", context = TAG)
            }
            .onFailure { e ->
                Logger.verbose("Failed to update $widgetType widget", e, context = TAG)
            }

        _refreshStates.update { it + (widgetType to false) }
    }

    suspend fun refreshEnabledWidgets() = withContext(bgDispatcher) {
        coroutineScope {
            widgetsDataFlow.first().widgets
                .filter { it.type != WidgetType.CALCULATOR && it.type != WidgetType.SUGGESTIONS }
                .forEach { launch { refreshWidget(it.type) } }
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

            WidgetType.CALCULATOR,
            WidgetType.SUGGESTIONS,
            -> throw NotImplementedError("Widget doesn't need a service")

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
