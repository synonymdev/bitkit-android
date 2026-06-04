package to.bitkit.ui.screens.widgets.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.WidgetSize
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.WidgetSizeDraft
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel
import javax.inject.Inject

@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo,
    private val currencyRepo: CurrencyRepo,
) : ViewModel() {

    // MARK: - Public StateFlows

    val weatherPreferences: StateFlow<WeatherPreferences> = widgetsRepo.widgetsDataFlow
        .map { it.weatherPreferences }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = WeatherPreferences()
        )

    val isWeatherWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.WEATHER }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

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
        initialValue = null
    )

    val isRefreshing: StateFlow<Boolean> = widgetsRepo.refreshStates
        .map { it[WidgetType.WEATHER] ?: false }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    // MARK: - Custom Preferences (for settings UI)

    private val _customPreferences = MutableStateFlow(WeatherPreferences())
    val customPreferences: StateFlow<WeatherPreferences> = _customPreferences.asStateFlow()

    private val sizeDraft = WidgetSizeDraft(viewModelScope, WidgetType.WEATHER, widgetsRepo.widgetsDataFlow)
    val draftSize: StateFlow<WidgetSize> = sizeDraft.size

    fun setSize(size: WidgetSize) = sizeDraft.set(size)

    init {
        initializeCustomPreferences()
    }

    // MARK: - Public Methods

    fun selectOption(option: WeatherDataOption) {
        _customPreferences.update { preferences ->
            val next = if (preferences.selectedOption == option) null else option
            preferences.copy(selectedOption = next)
        }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = WeatherPreferences()
    }

    fun savePreferences(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.updateWeatherPreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.WEATHER, sizeDraft.current)
            onComplete()
        }
    }

    fun removeWidget(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.WEATHER)
            onComplete()
        }
    }

    fun refreshWeather() {
        viewModelScope.launch {
            widgetsRepo.refreshWidget(WidgetType.WEATHER)
        }
    }

    fun refreshOnDisplay() {
        viewModelScope.launch {
            widgetsRepo.refreshWidget(WidgetType.WEATHER)
        }
    }

    // MARK: - Private Methods

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            weatherPreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}
