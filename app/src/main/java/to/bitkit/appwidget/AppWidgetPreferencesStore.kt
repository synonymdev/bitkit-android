package to.bitkit.appwidget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.appwidget.model.AppWidgetData
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.serializers.AppWidgetDataSerializer
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appWidgetDataStore: DataStore<AppWidgetData> by dataStore(
    fileName = "appwidget_data.json",
    serializer = AppWidgetDataSerializer,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppWidgetEntryPoint {
    fun appWidgetPreferencesStore(): AppWidgetPreferencesStore
    fun appWidgetDataRepository(): AppWidgetDataRepository
    fun widgetsRepo(): WidgetsRepo
    fun currencyRepo(): CurrencyRepo
}

@Singleton
@Suppress("TooManyFunctions")
class AppWidgetPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.appWidgetDataStore

    val data: Flow<AppWidgetData> = store.data

    suspend fun registerWidget(appWidgetId: Int, type: AppWidgetType) {
        store.updateData { data ->
            if (data.entries.any { it.appWidgetId == appWidgetId }) return@updateData data
            data.copy(entries = data.entries + AppWidgetEntry(appWidgetId = appWidgetId, type = type))
        }
    }

    suspend fun unregisterWidget(appWidgetId: Int) {
        store.updateData { data ->
            data.copy(entries = data.entries.filter { it.appWidgetId != appWidgetId })
        }
    }

    suspend fun getEntry(appWidgetId: Int): AppWidgetEntry? =
        store.data.first().entries.find { it.appWidgetId == appWidgetId }

    suspend fun updateEntry(appWidgetId: Int, transform: (AppWidgetEntry) -> AppWidgetEntry) {
        store.updateData { data ->
            data.copy(
                entries = data.entries.map {
                    if (it.appWidgetId == appWidgetId) transform(it) else it
                },
            )
        }
    }

    suspend fun getActiveWidgetTypes(): Set<AppWidgetType> =
        store.data.first().entries.map { it.type }.toSet()

    suspend fun getActivePricePeriods(): Set<GraphPeriod> =
        store.data.first().entries
            .filter { it.type == AppWidgetType.PRICE }
            .map { it.pricePreferences.period }
            .toSet()

    fun hasWidgetsOfType(type: AppWidgetType): Flow<Boolean> =
        data.map { it.entries.any { entry -> entry.type == type } }

    suspend fun cachePriceData(period: GraphPeriod, price: PriceDTO) {
        store.updateData { it.copy(cachedPrices = it.cachedPrices + (period to price)) }
    }

    suspend fun cacheArticlesAndRotate(articles: List<ArticleDTO>) {
        if (articles.isEmpty()) return
        store.updateData {
            it.copy(
                cachedArticles = articles,
                articleRotationTick = it.articleRotationTick + 1,
            )
        }
    }

    suspend fun cacheBlock(block: BlockDTO) {
        store.updateData { it.copy(cachedBlock = block) }
    }

    suspend fun cacheFacts(facts: List<String>) {
        store.updateData { it.copy(cachedFacts = facts) }
    }

    suspend fun bumpFactsRotationTick() {
        store.updateData { it.copy(factsRotationTick = it.factsRotationTick + 1) }
    }

    suspend fun cacheWeather(weather: WeatherDTO) {
        store.updateData { it.copy(cachedWeather = weather) }
    }
}
