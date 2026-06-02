package to.bitkit.appwidget.config

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.appwidget.AppWidgetDataRepository
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomeBlocksPreferences
import to.bitkit.appwidget.model.HomeHeadlinePreferences
import to.bitkit.appwidget.model.HomePricePreferences
import to.bitkit.appwidget.model.HomeWeatherPreferences
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.BlocksWidgetField
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.models.widget.toArticleModel
import to.bitkit.models.widget.toggleField
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
@Suppress("TooManyFunctions")
class AppWidgetConfigViewModel @Inject constructor(
    private val preferencesStore: AppWidgetPreferencesStore,
    private val dataRepository: AppWidgetDataRepository,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "AppWidgetConfigViewModel"
    }

    private val _uiState = MutableStateFlow(AppWidgetConfigUiState())
    val uiState: StateFlow<AppWidgetConfigUiState> = _uiState.asStateFlow()

    fun init(appWidgetId: Int, type: AppWidgetType) {
        viewModelScope.launch {
            val entry = preferencesStore.getEntry(appWidgetId)
            val data = preferencesStore.data.first()
            val previewArticle = data.cachedArticles.randomOrNull()?.toArticleModel() ?: DEFAULT_PREVIEW_ARTICLE
            val previewWeather = data.cachedWeather?.toPreviewWeather() ?: DEFAULT_PREVIEW_WEATHER

            _uiState.update {
                it.copy(
                    appWidgetId = appWidgetId,
                    type = type,
                    pricePreferences = entry?.pricePreferences?.toInApp() ?: PricePreferences(),
                    headlinePreferences = entry?.headlinePreferences?.toInApp() ?: HeadlinePreferences(),
                    blocksPreferences = entry?.blocksPreferences?.toInApp() ?: BlocksPreferences(),
                    weatherPreferences = entry?.weatherPreferences?.toInApp() ?: WeatherPreferences(),
                    previewArticle = previewArticle,
                    previewWeather = previewWeather,
                )
            }

            if (type == AppWidgetType.WEATHER && data.cachedWeather == null) {
                dataRepository.fetchWeather()
                    .onSuccess { fetched ->
                        preferencesStore.cacheWeather(fetched)
                        _uiState.update { it.copy(previewWeather = fetched.toPreviewWeather()) }
                    }
                    .onFailure { Logger.warn("Failed to fetch weather for config preview", it, context = TAG) }
            }
        }
    }

    fun selectWeatherOption(option: WeatherDataOption) {
        _uiState.update {
            val current = it.weatherPreferences.selectedOption
            val next = if (current == option) null else option
            it.copy(weatherPreferences = it.weatherPreferences.copy(selectedOption = next))
        }
    }

    fun selectPricePair(pair: TradingPair) {
        _uiState.update {
            it.copy(pricePreferences = it.pricePreferences.copy(enabledPairs = persistentListOf(pair)))
        }
    }

    fun selectPricePeriod(period: GraphPeriod) {
        _uiState.update {
            it.copy(pricePreferences = it.pricePreferences.copy(period = period))
        }
    }

    fun toggleShowTime() {
        _uiState.update {
            it.copy(
                headlinePreferences = it.headlinePreferences.copy(showTime = !it.headlinePreferences.showTime),
            )
        }
    }

    fun toggleShowSource() {
        _uiState.update {
            it.copy(
                headlinePreferences = it.headlinePreferences.copy(showSource = !it.headlinePreferences.showSource),
            )
        }
    }

    fun toggleBlockField(field: BlocksWidgetField) {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.toggleField(field))
        }
    }

    fun resetPreferences() {
        _uiState.update {
            when (it.type) {
                AppWidgetType.PRICE -> it.copy(pricePreferences = PricePreferences())
                AppWidgetType.HEADLINES -> it.copy(headlinePreferences = HeadlinePreferences())
                AppWidgetType.BLOCKS -> it.copy(blocksPreferences = BlocksPreferences())
                AppWidgetType.FACTS -> it
                AppWidgetType.WEATHER -> it.copy(weatherPreferences = WeatherPreferences())
            }
        }
    }

    fun saveAndFinish(onComplete: suspend () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isSaving = true) }

            when (state.type) {
                AppWidgetType.PRICE -> savePrice(state)
                AppWidgetType.HEADLINES -> saveHeadlines(state)
                AppWidgetType.BLOCKS -> saveBlocks(state)
                AppWidgetType.FACTS -> Unit
                AppWidgetType.WEATHER -> saveWeather(state)
            }

            onComplete()
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private suspend fun savePrice(state: AppWidgetConfigUiState) {
        val appWidgetId = state.appWidgetId
        val pricePreferences = state.pricePreferences
        preferencesStore.registerWidget(appWidgetId, AppWidgetType.PRICE)
        preferencesStore.updateEntry(appWidgetId) { entry ->
            entry.copy(pricePreferences = pricePreferences.toHome())
        }
        val period = pricePreferences.period ?: GraphPeriod.ONE_DAY
        dataRepository.fetchPriceData(period)
            .onSuccess { preferencesStore.cachePriceData(period, it) }
            .onFailure { Logger.warn("Failed to fetch initial price data", it, context = TAG) }
    }

    private suspend fun saveHeadlines(state: AppWidgetConfigUiState) {
        val appWidgetId = state.appWidgetId
        val headlinePreferences = state.headlinePreferences
        preferencesStore.registerWidget(appWidgetId, AppWidgetType.HEADLINES)
        preferencesStore.updateEntry(appWidgetId) { entry ->
            entry.copy(headlinePreferences = headlinePreferences.toHome())
        }
        dataRepository.fetchArticles()
            .onSuccess { preferencesStore.cacheArticlesAndRotate(it) }
            .onFailure { Logger.warn("Failed to fetch initial articles", it, context = TAG) }
    }

    private suspend fun saveBlocks(state: AppWidgetConfigUiState) {
        val appWidgetId = state.appWidgetId
        val blocksPreferences = state.blocksPreferences
        preferencesStore.registerWidget(appWidgetId, AppWidgetType.BLOCKS)
        preferencesStore.updateEntry(appWidgetId) { entry ->
            entry.copy(blocksPreferences = blocksPreferences.toHome())
        }
        dataRepository.fetchBlock()
            .onSuccess { preferencesStore.cacheBlock(it) }
            .onFailure { Logger.warn("Failed to fetch initial block", it, context = TAG) }
    }

    private suspend fun saveWeather(state: AppWidgetConfigUiState) {
        val appWidgetId = state.appWidgetId
        val weatherPreferences = state.weatherPreferences
        preferencesStore.registerWidget(appWidgetId, AppWidgetType.WEATHER)
        preferencesStore.updateEntry(appWidgetId) { entry ->
            entry.copy(weatherPreferences = weatherPreferences.toHome())
        }
        dataRepository.fetchWeather()
            .onSuccess { preferencesStore.cacheWeather(it) }
            .onFailure { Logger.warn("Failed to fetch initial weather", it, context = TAG) }
    }

    private fun WeatherDTO.toPreviewWeather() = toWeatherModel(
        currentFee = currencyRepo.formatSatsAsFiatWithSymbol(avgFeeSats, withSpace = true) ?: currentFee,
    )
}

private val DEFAULT_PREVIEW_ARTICLE = ArticleModel(
    title = "How Bitcoin changed El Salvador in more ways",
    timeAgo = "21 minutes ago",
    publisher = "bitcoinmagazine.com",
    link = "",
)

private val DEFAULT_PREVIEW_WEATHER = WeatherModel(
    condition = FeeCondition.GOOD,
    title = R.string.widgets__weather__condition__good__title,
    shortTitle = R.string.widgets__weather__condition__good__short_title,
    description = R.string.widgets__weather__condition__good__description,
    currentFee = "$ 0.52",
    currentFeeSats = 520L,
    currentFeeSatsFormatted = "520 ₿",
    nextBlockFee = "6 ₿/vByte",
    icon = FeeCondition.GOOD.icon,
)

@Stable
data class AppWidgetConfigUiState(
    val appWidgetId: Int = -1,
    val type: AppWidgetType = AppWidgetType.PRICE,
    val pricePreferences: PricePreferences = PricePreferences(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val blocksPreferences: BlocksPreferences = BlocksPreferences(),
    val weatherPreferences: WeatherPreferences = WeatherPreferences(),
    val previewArticle: ArticleModel = DEFAULT_PREVIEW_ARTICLE,
    val previewWeather: WeatherModel = DEFAULT_PREVIEW_WEATHER,
    val isSaving: Boolean = false,
)

private fun HomePricePreferences.toInApp() = PricePreferences(
    enabledPairs = enabledPairs,
    period = period,
)

private fun PricePreferences.toHome() = HomePricePreferences(
    enabledPairs = enabledPairs,
    period = period ?: GraphPeriod.ONE_DAY,
)

private fun HomeHeadlinePreferences.toInApp() = HeadlinePreferences(
    showTime = showTime,
    showSource = showSource,
)

private fun HeadlinePreferences.toHome() = HomeHeadlinePreferences(
    showTime = showTime,
    showSource = showSource,
)

private fun HomeBlocksPreferences.toInApp() = BlocksPreferences(
    showBlock = showBlock,
    showTime = showTime,
    showDate = showDate,
    showTransactions = showTransactions,
    showSize = showSize,
    showFees = showFees,
)

private fun BlocksPreferences.toHome() = HomeBlocksPreferences(
    showBlock = showBlock,
    showTime = showTime,
    showDate = showDate,
    showTransactions = showTransactions,
    showSize = showSize,
    showFees = showFees,
)

private fun HomeWeatherPreferences.toInApp() = WeatherPreferences(selectedOption = selectedOption)

private fun WeatherPreferences.toHome() = HomeWeatherPreferences(selectedOption = selectedOption)
