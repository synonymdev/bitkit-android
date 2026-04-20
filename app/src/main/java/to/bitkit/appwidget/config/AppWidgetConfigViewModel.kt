package to.bitkit.appwidget.config

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.appwidget.AppWidgetDataRepository
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomePricePreferences
import to.bitkit.appwidget.ui.price.PriceGlanceWidget
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.PricePreferences
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
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

    fun resetPreferences() {
        _uiState.update { it.copy(pricePreferences = PricePreferences()) }
    }

    fun saveAndFinish(context: Context, onComplete: () -> Unit) {
        viewModelScope.launch {
            val appWidgetId = _uiState.value.appWidgetId
            val pricePreferences = _uiState.value.pricePreferences
            _uiState.update { it.copy(isSaving = true) }
            preferencesStore.registerWidget(appWidgetId, AppWidgetType.PRICE)
            preferencesStore.updateEntry(appWidgetId) { entry ->
                entry.copy(pricePreferences = pricePreferences.toHome())
            }
            dataRepository.fetchPriceData(pricePreferences.period ?: GraphPeriod.ONE_DAY)
                .onSuccess { preferencesStore.cachePriceData(it) }
                .onFailure { Logger.warn("Failed to fetch initial price data", e = it, context = TAG) }
            PriceGlanceWidget().updateAll(context)
            _uiState.update { it.copy(isSaving = false) }
            onComplete()
        }
    }
}

data class AppWidgetConfigUiState(
    val appWidgetId: Int = -1,
    val type: AppWidgetType = AppWidgetType.PRICE,
    val pricePreferences: PricePreferences = PricePreferences(),
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
