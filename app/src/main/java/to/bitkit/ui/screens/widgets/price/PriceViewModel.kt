package to.bitkit.ui.screens.widgets.price

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.PricePreferences
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class PriceViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    // MARK: - Public StateFlows

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

    val currentPrice: StateFlow<PriceDTO?> = widgetsRepo.priceFlow.map { price ->
        price//TODO TO MODEL
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
        initialValue = null
    )

    // MARK: - Custom Preferences (for settings UI)

    private val _customPreferences = MutableStateFlow(PricePreferences())
    val customPreferences: StateFlow<PricePreferences> = _customPreferences.asStateFlow()

    init {
        initializeCustomPreferences()
    }

    // MARK: - Public Methods

    fun setPeriod(period: GraphPeriod) {
        _customPreferences.update { preferences ->
            preferences.copy(period = period)
        }
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
        }
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.PRICE)
        }
    }

    // MARK: - Private Methods

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            pricePreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}
