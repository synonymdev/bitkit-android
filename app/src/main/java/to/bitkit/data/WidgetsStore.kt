package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.serializers.WidgetsSerializer
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.WidgetsBackupV1
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetsDataStore: DataStore<WidgetsData> by dataStore(
    fileName = "widgets.json",
    serializer = WidgetsSerializer,
)

@Suppress("TooManyFunctions")
@Singleton
class WidgetsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.widgetsDataStore

    val data: Flow<WidgetsData> = store.data
    val articlesFlow: Flow<List<ArticleDTO>> = data.map { it.articles }
    val factsFlow: Flow<List<String>> = data.map { it.facts }
    val blocksFlow: Flow<BlockDTO?> = data.map { it.block }
    val weatherFlow: Flow<WeatherDTO?> = data.map { it.weather }
    val priceFlow: Flow<PriceDTO?> = data.map { it.price }

    suspend fun restoreFromBackup(payload: WidgetsBackupV1) =
        runCatching {
            val data = payload.widgets
            store.updateData { data }
        }.onSuccess {
            Logger.debug("Restored widgets", TAG)
        }

    suspend fun updateCalculatorValues(calculatorValues: CalculatorValues) {
        store.updateData {
            it.copy(calculatorValues = calculatorValues)
        }
    }

    suspend fun updateArticles(articles: List<ArticleDTO>) {
        store.updateData {
            it.copy(articles = articles)
        }
    }

    suspend fun updateHeadlinePreferences(preferences: HeadlinePreferences) {
        store.updateData {
            it.copy(headlinePreferences = preferences)
        }
    }

    suspend fun updateFactsPreferences(preferences: FactsPreferences) {
        store.updateData {
            it.copy(factsPreferences = preferences)
        }
    }

    suspend fun updateBlocksPreferences(preferences: BlocksPreferences) {
        store.updateData {
            it.copy(blocksPreferences = preferences)
        }
    }

    suspend fun updateWeatherPreferences(preferences: WeatherPreferences) {
        store.updateData {
            it.copy(weatherPreferences = preferences)
        }
    }

    suspend fun updatePricePreferences(preferences: PricePreferences) {
        store.updateData { currentStore ->
            currentStore.copy(
                pricePreferences = preferences.copy(
                    enabledPairs = preferences.enabledPairs.sortedBy { tradingPair ->
                        tradingPair.position
                    }
                )
            )
        }
    }

    suspend fun updateFacts(facts: List<String>) {
        store.updateData {
            it.copy(facts = facts)
        }
    }

    suspend fun updateBlock(block: BlockDTO) {
        store.updateData {
            it.copy(block = block)
        }
    }

    suspend fun updateWeather(weather: WeatherDTO) {
        store.updateData {
            it.copy(weather = weather)
        }
    }

    suspend fun updatePrice(price: PriceDTO) {
        store.updateData {
            it.copy(price = price)
        }
    }

    suspend fun reset() {
        store.updateData { WidgetsData() }
        Logger.info("Deleted all widgets data.")
    }

    suspend fun addWidget(type: WidgetType) {
        if (store.data.first().widgets.map { it.type }.contains(type)) return

        store.updateData { data ->
            data.copy(widgets = (data.widgets + WidgetWithPosition(type = type)).sortedBy { it.position })
        }
    }

    suspend fun deleteWidget(type: WidgetType) {
        if (!store.data.first().widgets.map { it.type }.contains(type)) return

        store.updateData { data ->
            data.copy(widgets = data.widgets.filterNot { it.type == type })
        }
    }

    suspend fun updateWidgets(widgets: List<WidgetWithPosition>) {
        store.updateData {
            it.copy(widgets = widgets)
        }
    }

    companion object {
        private const val TAG = "WidgetsStore"
    }
}

@Serializable
data class WidgetsData(
    val widgets: List<WidgetWithPosition> = listOf(
        WidgetWithPosition(type = WidgetType.PRICE, position = 0),
        WidgetWithPosition(type = WidgetType.BLOCK, position = 1),
        WidgetWithPosition(type = WidgetType.NEWS, position = 2),
    ),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val factsPreferences: FactsPreferences = FactsPreferences(),
    val blocksPreferences: BlocksPreferences = BlocksPreferences(),
    val weatherPreferences: WeatherPreferences = WeatherPreferences(),
    val pricePreferences: PricePreferences = PricePreferences(),
    val calculatorValues: CalculatorValues = CalculatorValues(),
    val articles: List<ArticleDTO> = emptyList(),
    val facts: List<String> = emptyList(),
    val block: BlockDTO? = null,
    val weather: WeatherDTO? = null,
    val price: PriceDTO? = null,
)
