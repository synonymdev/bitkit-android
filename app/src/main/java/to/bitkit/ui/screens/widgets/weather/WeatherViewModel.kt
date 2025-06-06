package to.bitkit.ui.screens.widgets.weather

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
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel
import javax.inject.Inject

@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
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

    val showWidgetTitles: StateFlow<Boolean> = widgetsRepo.showWidgetTitles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = true
        )

    val currentWeather: StateFlow<WeatherModel?> = widgetsRepo.weatherFlow.map { weather ->
        weather?.toWeatherModel()
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

    init {
        initializeCustomPreferences()
    }

    // MARK: - Public Methods

    fun toggleShowTitle() {
        _customPreferences.update { preferences ->
            preferences.copy(showTitle = !preferences.showTitle)
        }
    }

    fun toggleShowDescription() {
        _customPreferences.update { preferences ->
            preferences.copy(showDescription = !preferences.showDescription)
        }
    }

    fun toggleShowCurrentFee() {
        _customPreferences.update { preferences ->
            preferences.copy(showCurrentFee = !preferences.showCurrentFee)
        }
    }

    fun toggleShowNextBlockFee() {
        _customPreferences.update { preferences ->
            preferences.copy(showNextBlockFee = !preferences.showNextBlockFee)
        }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = WeatherPreferences()
    }

    fun savePreferences() {
        viewModelScope.launch {
            widgetsRepo.updateWeatherPreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.WEATHER)
        }
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.WEATHER)
        }
    }

    fun refreshWeather() {
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
