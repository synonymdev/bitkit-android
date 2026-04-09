package to.bitkit.appwidget.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomePricePreferences
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.PricePreferences
import javax.inject.Inject

@HiltViewModel
class AppWidgetConfigViewModel @Inject constructor(
    private val preferencesStore: AppWidgetPreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppWidgetConfigUiState())
    val uiState: StateFlow<AppWidgetConfigUiState> = _uiState.asStateFlow()

    fun init(appWidgetId: Int, type: AppWidgetType) {
        viewModelScope.launch {
            val entry = preferencesStore.getEntry(appWidgetId)

            _uiState.update {
                it.copy(
                    appWidgetId = appWidgetId,
                    type = type,
                    pricePreferences = entry?.pricePreferences?.toInApp() ?: PricePreferences(),
                )
            }
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
        _uiState.update { it.copy(pricePreferences = PricePreferences()) }
    }

    fun saveAndFinish(onComplete: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            preferencesStore.registerWidget(state.appWidgetId, state.type)
            preferencesStore.updateEntry(state.appWidgetId) { entry ->
                entry.copy(pricePreferences = state.pricePreferences.toHome())
            }
            onComplete()
        }
    }
}

data class AppWidgetConfigUiState(
    val appWidgetId: Int = -1,
    val type: AppWidgetType = AppWidgetType.PRICE,
    val pricePreferences: PricePreferences = PricePreferences(),
)

private fun HomePricePreferences.toInApp() = PricePreferences(
    enabledPairs = enabledPairs,
    period = period,
    showSource = showSource,
)

private fun PricePreferences.toHome() = HomePricePreferences(
    enabledPairs = enabledPairs,
    period = period ?: GraphPeriod.ONE_DAY,
    showSource = showSource,
)
