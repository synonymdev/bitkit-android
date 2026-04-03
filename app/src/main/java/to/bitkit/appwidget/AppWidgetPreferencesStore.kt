package to.bitkit.appwidget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.appwidget.model.AppWidgetData
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.serializers.AppWidgetDataSerializer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appWidgetDataStore: DataStore<AppWidgetData> by dataStore(
    fileName = "appwidget_data.json",
    serializer = AppWidgetDataSerializer,
)

@Suppress("TooManyFunctions")
@Singleton
class AppWidgetPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        @Volatile
        private var instance: AppWidgetPreferencesStore? = null

        fun getInstance(context: Context): AppWidgetPreferencesStore =
            instance ?: synchronized(this) {
                instance ?: AppWidgetPreferencesStore(context.applicationContext).also {
                    instance = it
                }
            }
    }

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

    fun hasWidgetsOfType(type: AppWidgetType): Flow<Boolean> =
        data.map { it.entries.any { entry -> entry.type == type } }

    suspend fun cacheBlockData(block: BlockDTO) {
        store.updateData { it.copy(cachedBlock = block) }
    }

    suspend fun cacheWeatherData(weather: WeatherDTO) {
        store.updateData { it.copy(cachedWeather = weather) }
    }

    suspend fun cachePriceData(price: PriceDTO) {
        store.updateData { it.copy(cachedPrice = price) }
    }

    suspend fun cacheArticles(articles: List<ArticleDTO>) {
        store.updateData { it.copy(cachedArticles = articles) }
    }

    suspend fun cacheFacts(facts: List<String>) {
        store.updateData { it.copy(cachedFacts = facts) }
    }
}
