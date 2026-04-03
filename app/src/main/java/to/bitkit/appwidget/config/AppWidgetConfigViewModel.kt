package to.bitkit.appwidget.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomeBlocksPreferences
import to.bitkit.appwidget.model.HomeFactsPreferences
import to.bitkit.appwidget.model.HomeHeadlinesPreferences
import to.bitkit.appwidget.model.HomePricePreferences
import to.bitkit.appwidget.model.HomeWeatherPreferences
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.models.widget.toBlockModel
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class AppWidgetConfigViewModel @Inject constructor(
    private val preferencesStore: AppWidgetPreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppWidgetConfigUiState())
    val uiState: StateFlow<AppWidgetConfigUiState> = _uiState.asStateFlow()

    fun init(appWidgetId: Int, type: AppWidgetType) {
        viewModelScope.launch {
            val entry = preferencesStore.getEntry(appWidgetId)
            val data = preferencesStore.data.first()

            _uiState.update {
                it.copy(
                    appWidgetId = appWidgetId,
                    type = type,
                    blocksPreferences = entry?.blocksPreferences?.toInApp() ?: BlocksPreferences(),
                    pricePreferences = entry?.pricePreferences?.toInApp() ?: PricePreferences(),
                    weatherPreferences = entry?.weatherPreferences?.toInApp() ?: WeatherPreferences(),
                    headlinePreferences = entry?.headlinesPreferences?.toInApp()
                        ?: HeadlinePreferences(),
                    factsPreferences = entry?.factsPreferences?.toInApp() ?: FactsPreferences(),
                    blockModel = data.cachedBlock?.toBlockModel(),
                    currentFact = data.cachedFacts.randomOrNull() ?: "",
                )
            }
        }
    }

    fun toggleBlocksShow(field: BlocksField) {
        _uiState.update {
            val p = it.blocksPreferences
            it.copy(
                blocksPreferences = when (field) {
                    BlocksField.BLOCK -> p.copy(showBlock = !p.showBlock)
                    BlocksField.TIME -> p.copy(showTime = !p.showTime)
                    BlocksField.DATE -> p.copy(showDate = !p.showDate)
                    BlocksField.TRANSACTIONS -> p.copy(showTransactions = !p.showTransactions)
                    BlocksField.SIZE -> p.copy(showSize = !p.showSize)
                    BlocksField.SOURCE -> p.copy(showSource = !p.showSource)
                },
            )
        }
    }

    fun toggleWeatherShow(field: WeatherField) {
        _uiState.update {
            val p = it.weatherPreferences
            it.copy(
                weatherPreferences = when (field) {
                    WeatherField.TITLE -> p.copy(showTitle = !p.showTitle)
                    WeatherField.DESCRIPTION -> p.copy(showDescription = !p.showDescription)
                    WeatherField.CURRENT_FEE -> p.copy(showCurrentFee = !p.showCurrentFee)
                    WeatherField.NEXT_BLOCK -> p.copy(showNextBlockFee = !p.showNextBlockFee)
                },
            )
        }
    }

    fun toggleHeadlineTime() {
        _uiState.update {
            it.copy(headlinePreferences = it.headlinePreferences.copy(showTime = !it.headlinePreferences.showTime))
        }
    }

    fun toggleHeadlineSource() {
        _uiState.update {
            it.copy(headlinePreferences = it.headlinePreferences.copy(showSource = !it.headlinePreferences.showSource))
        }
    }

    fun toggleFactsSource() {
        _uiState.update {
            it.copy(factsPreferences = it.factsPreferences.copy(showSource = !it.factsPreferences.showSource))
        }
    }

    fun togglePricePair(pair: TradingPair) {
        _uiState.update {
            val current = it.pricePreferences.enabledPairs.toMutableList()
            if (pair in current) {
                if (current.size > 1) current.remove(pair)
            } else {
                current.add(pair)
            }
            it.copy(pricePreferences = it.pricePreferences.copy(enabledPairs = current.sortedBy { p -> p.position }))
        }
    }

    fun selectPricePeriod(period: GraphPeriod) {
        _uiState.update {
            it.copy(pricePreferences = it.pricePreferences.copy(period = period))
        }
    }

    fun togglePriceSource() {
        _uiState.update {
            it.copy(pricePreferences = it.pricePreferences.copy(showSource = !it.pricePreferences.showSource))
        }
    }

    fun resetPreferences() {
        _uiState.update {
            when (it.type) {
                AppWidgetType.BLOCKS -> it.copy(blocksPreferences = BlocksPreferences())
                AppWidgetType.PRICE -> it.copy(pricePreferences = PricePreferences())
                AppWidgetType.WEATHER -> it.copy(weatherPreferences = WeatherPreferences())
                AppWidgetType.HEADLINES -> it.copy(headlinePreferences = HeadlinePreferences())
                AppWidgetType.FACTS -> it.copy(factsPreferences = FactsPreferences())
            }
        }
    }

    fun saveAndFinish(onComplete: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            preferencesStore.registerWidget(state.appWidgetId, state.type)
            preferencesStore.updateEntry(state.appWidgetId) { entry ->
                entry.copy(
                    blocksPreferences = state.blocksPreferences.toHome(),
                    pricePreferences = state.pricePreferences.toHome(),
                    weatherPreferences = state.weatherPreferences.toHome(),
                    headlinesPreferences = state.headlinePreferences.toHome(),
                    factsPreferences = state.factsPreferences.toHome(),
                )
            }
            onComplete()
        }
    }
}

data class AppWidgetConfigUiState(
    val appWidgetId: Int = -1,
    val type: AppWidgetType = AppWidgetType.BLOCKS,
    val blocksPreferences: BlocksPreferences = BlocksPreferences(),
    val pricePreferences: PricePreferences = PricePreferences(),
    val weatherPreferences: WeatherPreferences = WeatherPreferences(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val factsPreferences: FactsPreferences = FactsPreferences(),
    val blockModel: BlockModel? = null,
    val currentFact: String = "",
)

enum class BlocksField { BLOCK, TIME, DATE, TRANSACTIONS, SIZE, SOURCE }
enum class WeatherField { TITLE, DESCRIPTION, CURRENT_FEE, NEXT_BLOCK }

private fun HomeBlocksPreferences.toInApp() = BlocksPreferences(
    showBlock = showBlock,
    showTime = showTime,
    showDate = showDate,
    showTransactions = showTransactions,
    showSize = showSize,
    showSource = showSource,
)

private fun HomePricePreferences.toInApp() = PricePreferences(
    enabledPairs = enabledPairs,
    period = period,
    showSource = showSource,
)

private fun HomeWeatherPreferences.toInApp() = WeatherPreferences(
    showTitle = showTitle,
    showDescription = showDescription,
    showCurrentFee = showCurrentFee,
    showNextBlockFee = showNextBlockFee,
)

private fun HomeHeadlinesPreferences.toInApp() = HeadlinePreferences(
    showTime = showTime,
    showSource = showSource,
)

private fun HomeFactsPreferences.toInApp() = FactsPreferences(
    showSource = showSource,
)

private fun BlocksPreferences.toHome() = HomeBlocksPreferences(
    showBlock = showBlock,
    showTime = showTime,
    showDate = showDate,
    showTransactions = showTransactions,
    showSize = showSize,
    showSource = showSource,
)

private fun PricePreferences.toHome() = HomePricePreferences(
    enabledPairs = enabledPairs,
    period = period ?: GraphPeriod.ONE_DAY,
    showSource = showSource,
)

private fun WeatherPreferences.toHome() = HomeWeatherPreferences(
    showTitle = showTitle,
    showDescription = showDescription,
    showCurrentFee = showCurrentFee,
    showNextBlockFee = showNextBlockFee,
)

private fun HeadlinePreferences.toHome() = HomeHeadlinesPreferences(
    showTime = showTime,
    showSource = showSource,
)

private fun FactsPreferences.toHome() = HomeFactsPreferences(
    showSource = showSource,
)
