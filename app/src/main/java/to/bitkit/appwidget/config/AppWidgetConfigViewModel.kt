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
import to.bitkit.appwidget.AppWidgetDataRepository
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomeBlocksPreferences
import to.bitkit.appwidget.model.HomeHeadlinePreferences
import to.bitkit.appwidget.model.HomePricePreferences
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.toArticleModel
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
@Suppress("TooManyFunctions")
class AppWidgetConfigViewModel @Inject constructor(
    private val preferencesStore: AppWidgetPreferencesStore,
    private val dataRepository: AppWidgetDataRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "AppWidgetConfigViewModel"
    }

    private val _uiState = MutableStateFlow(AppWidgetConfigUiState())
    val uiState: StateFlow<AppWidgetConfigUiState> = _uiState.asStateFlow()

    fun init(appWidgetId: Int, type: AppWidgetType) {
        viewModelScope.launch {
            val entry = preferencesStore.getEntry(appWidgetId)
            val cachedArticles = preferencesStore.data.first().cachedArticles
            val previewArticle = cachedArticles.randomOrNull()?.toArticleModel() ?: DEFAULT_PREVIEW_ARTICLE

            _uiState.update {
                it.copy(
                    appWidgetId = appWidgetId,
                    type = type,
                    pricePreferences = entry?.pricePreferences?.toInApp() ?: PricePreferences(),
                    headlinePreferences = entry?.headlinePreferences?.toInApp() ?: HeadlinePreferences(),
                    blocksPreferences = entry?.blocksPreferences?.toInApp() ?: BlocksPreferences(),
                    previewArticle = previewArticle,
                )
            }
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

    fun toggleBlockShowBlock() {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.copy(showBlock = !it.blocksPreferences.showBlock))
        }
    }

    fun toggleBlockShowTime() {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.copy(showTime = !it.blocksPreferences.showTime))
        }
    }

    fun toggleBlockShowDate() {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.copy(showDate = !it.blocksPreferences.showDate))
        }
    }

    fun toggleBlockShowTransactions() {
        _uiState.update {
            it.copy(
                blocksPreferences = it.blocksPreferences.copy(
                    showTransactions = !it.blocksPreferences.showTransactions,
                ),
            )
        }
    }

    fun toggleBlockShowSize() {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.copy(showSize = !it.blocksPreferences.showSize))
        }
    }

    fun toggleBlockShowFees() {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.copy(showFees = !it.blocksPreferences.showFees))
        }
    }

    fun toggleBlockShowSource() {
        _uiState.update {
            it.copy(blocksPreferences = it.blocksPreferences.copy(showSource = !it.blocksPreferences.showSource))
        }
    }

    fun resetPreferences() {
        _uiState.update {
            when (it.type) {
                AppWidgetType.PRICE -> it.copy(pricePreferences = PricePreferences())
                AppWidgetType.HEADLINES -> it.copy(headlinePreferences = HeadlinePreferences())
                AppWidgetType.BLOCKS -> it.copy(blocksPreferences = BlocksPreferences())
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
}

private val DEFAULT_PREVIEW_ARTICLE = ArticleModel(
    title = "How Bitcoin changed El Salvador in more ways",
    timeAgo = "21 minutes ago",
    publisher = "bitcoinmagazine.com",
    link = "",
)

@Stable
data class AppWidgetConfigUiState(
    val appWidgetId: Int = -1,
    val type: AppWidgetType = AppWidgetType.PRICE,
    val pricePreferences: PricePreferences = PricePreferences(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val blocksPreferences: BlocksPreferences = BlocksPreferences(),
    val previewArticle: ArticleModel = DEFAULT_PREVIEW_ARTICLE,
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
    showSource = showSource,
)

private fun BlocksPreferences.toHome() = HomeBlocksPreferences(
    showBlock = showBlock,
    showTime = showTime,
    showDate = showDate,
    showTransactions = showTransactions,
    showSize = showSize,
    showFees = showFees,
    showSource = showSource,
)
