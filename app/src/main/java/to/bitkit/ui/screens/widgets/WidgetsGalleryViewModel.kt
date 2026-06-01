package to.bitkit.ui.screens.widgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.toArticleModel
import to.bitkit.models.widget.toBlockModel
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel
import javax.inject.Inject

@HiltViewModel
class WidgetsGalleryViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {
    private val _currentPrice = MutableStateFlow<PriceDTO?>(null)
    val currentPrice: StateFlow<PriceDTO?> = _currentPrice.asStateFlow()

    val currentWeather: StateFlow<WeatherModel?> = combine(
        widgetsRepo.weatherFlow,
        currencyRepo.currencyState,
    ) { weather, _ ->
        weather?.toWeatherModel(
            currentFee = currencyRepo.formatSatsAsFiatWithSymbol(weather.avgFeeSats, withSpace = true)
                ?: weather.currentFee,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
        initialValue = null,
    )

    val currentBlock: StateFlow<BlockModel?> = widgetsRepo.blocksFlow
        .map { it?.toBlockModel() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = null,
        )

    val currentArticle: StateFlow<ArticleModel?> = widgetsRepo.articlesFlow
        .map { articles -> articles.map { it.toArticleModel() }.randomOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = null,
        )

    val currentFact: StateFlow<String?> = widgetsRepo.factsFlow
        .map { it.randomOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = null,
        )

    fun refreshOnDisplay() {
        viewModelScope.launch {
            coroutineScope {
                launch { refreshPriceOnDisplay() }
                launch { widgetsRepo.refreshWidget(WidgetType.WEATHER) }
                launch { widgetsRepo.refreshWidget(WidgetType.BLOCK) }
                launch { widgetsRepo.refreshWidget(WidgetType.NEWS) }
                launch { widgetsRepo.refreshWidget(WidgetType.FACTS) }
            }
        }
    }

    private suspend fun refreshPriceOnDisplay() {
        setCachedPriceIfAvailable()

        widgetsRepo.fetchPriceData(
            pairs = listOf(DEFAULT_PAIR),
            period = DEFAULT_PERIOD,
        ).onSuccess { price ->
            _currentPrice.update { price }
        }
    }

    private fun setCachedPriceIfAvailable() {
        val cachedPrice = widgetsRepo.priceFlow.value
            ?.takeIf { it.hasDefaultGalleryPrice() }

        _currentPrice.update { it ?: cachedPrice }
    }

    private fun PriceDTO.hasDefaultGalleryPrice() = widgets.any {
        it.pair == DEFAULT_PAIR && it.period == DEFAULT_PERIOD
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
        private val DEFAULT_PAIR = TradingPair.BTC_USD
        private val DEFAULT_PERIOD = GraphPeriod.ONE_DAY
    }
}
