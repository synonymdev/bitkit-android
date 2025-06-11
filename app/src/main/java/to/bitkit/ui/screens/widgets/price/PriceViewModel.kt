package to.bitkit.ui.screens.widgets.price

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.PricePreferences
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PriceViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    val pricePreferences: StateFlow<PricePreferences> = widgetsRepo.widgetsDataFlow
        .map { it.pricePreferences }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = PricePreferences()
        )

    val isPriceWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.PRICE }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    val showWidgetTitles: StateFlow<Boolean> = widgetsRepo.showWidgetTitles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = true
        )

    val currentPrice: StateFlow<PriceDTO?> = widgetsRepo.priceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = null
        )

    private val _customPreferences = MutableStateFlow(PricePreferences())
    val customPreferences: StateFlow<PricePreferences> = _customPreferences.asStateFlow()

    private val _allPeriodsUsd = MutableStateFlow(listOf<PriceWidgetData>())
    val allPeriodsUsd: StateFlow<List<PriceWidgetData>> = _allPeriodsUsd.asStateFlow()
    private val _allPrices = MutableStateFlow(listOf<PriceDTO>())

    private val _previewPrice: MutableStateFlow<PriceDTO?> = MutableStateFlow(null)
    val previewPrice = _previewPrice.asStateFlow()


    init {
        initializeCustomPreferences()
        collectAllPeriodPrices()
    }

    fun setPeriod(period: GraphPeriod) {
        _customPreferences.update { preferences ->
            preferences.copy(period = period)
        }
        _previewPrice.update { _allPrices.value.firstOrNull { it.widgets.firstOrNull()?.period == period } }
    }

    fun toggleTradingPair(pair: TradingPair) {
        if (pair in _customPreferences.value.enabledPairs) {
            _customPreferences.update { it.copy(enabledPairs = it.enabledPairs - pair) }
        } else {
            _customPreferences.update { it.copy(enabledPairs = it.enabledPairs + pair) }
        }
    }

    fun toggleShowSource() {
        _customPreferences.update { preferences ->
            preferences.copy(showSource = !preferences.showSource)
        }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = PricePreferences()
    }

    fun savePreferences() {
        viewModelScope.launch {
            widgetsRepo.updatePricePreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.PRICE)
            widgetsRepo.refreshWidget(WidgetType.PRICE)
            _previewPrice.update { null }
        }
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.PRICE)
        }
    }

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            pricePreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    private fun collectAllPeriodPrices() {
        viewModelScope.launch {
            widgetsRepo.fetchAllPeriods().onSuccess { data ->
                _allPrices.update { data }
                _allPeriodsUsd.update { data.map { priceDTO -> priceDTO.widgets.first() } }
            }.onFailure {
                Logger.warn("collectAllPeriodPrices error. Trying again in 1 second", context = TAG)
                delay(1.seconds)
                collectAllPeriodPrices()
            }
        }
    }

    companion object {
        private const val TAG = "PriceViewModel"
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}
