package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import to.bitkit.data.widgets.NewsService
import to.bitkit.data.widgets.WidgetService
import to.bitkit.di.BgDispatcher
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.map
import kotlin.time.Duration.Companion.minutes

@Singleton
class WidgetsRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val newsService: NewsService,
    private val widgetsStore: WidgetsStore,
    private val settingsStore: SettingsStore,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    val widgetsDataFlow = widgetsStore.data
    val articlesFlow = widgetsStore.articlesFlow
    val showWidgetTitles = settingsStore.data.map { it.showWidgetTitles }
    val showWidgets = settingsStore.data.map { it.showWidgets }
    val widgetsWithPosition = widgetsStore.data.map { it.widgets }

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

    /**
     * Start periodic updates for all widgets
     */
    private fun startPeriodicUpdates() {
        startPeriodicUpdate(newsService) { articles ->
            widgetsStore.updateArticles(articles)
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
        listOf(
            updateWidget(newsService) { articles ->
                widgetsStore.updateArticles(articles)
            },
        )
    }

    /**
     * Manually refresh specific widget
     */
    suspend fun refreshWidget(widgetType: WidgetType): Result<Unit> = runCatching {
        when (widgetType) {
            WidgetType.NEWS -> updateWidget(newsService) { articles ->
                widgetsStore.updateArticles(articles)
            }

            WidgetType.WEATHER -> {
                // TODO: Implement when PriceService is ready
                throw NotImplementedError("Weather widget not implemented yet")
            }

            WidgetType.PRICE -> {
                // TODO: Implement when PriceService is ready
                throw NotImplementedError("Price widget not implemented yet")
            }

            WidgetType.BLOCK -> {
                // TODO: Implement when BlockService is ready
                throw NotImplementedError("Block widget not implemented yet")
            }

            WidgetType.CALCULATOR -> {
                // TODO: Implement when CalculatorService is ready
                throw NotImplementedError("Calculator widget not implemented yet")
            }

            WidgetType.FACTS -> {
                // TODO: Implement when FactsService is ready
                throw NotImplementedError("Facts widget not implemented yet")
            }
        }
    }

    /**
     * Get refresh state for a specific widget type
     */
    fun getRefreshState(widgetType: WidgetType): Flow<Boolean> {
        return refreshStates.map { it[widgetType] ?: false }
    }

    /**
     * Check if a widget type is currently supported
     */
    fun isWidgetSupported(widgetType: WidgetType): Boolean {
        return when (widgetType) {
            WidgetType.NEWS, WidgetType.WEATHER -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "WidgetsRepo"
    }
}
