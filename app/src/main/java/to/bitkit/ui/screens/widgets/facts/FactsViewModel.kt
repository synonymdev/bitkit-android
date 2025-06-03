package to.bitkit.ui.screens.widgets.facts

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
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class FactsViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    // MARK: - Public StateFlows

    val factsPreferences: StateFlow<FactsPreferences> = widgetsRepo.widgetsDataFlow
        .map { it.factsPreferences }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = FactsPreferences()
        )

    val isFactsWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.FACTS }
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

    val currentFact: StateFlow<String> =
        widgetsRepo.factsFlow.map { facts -> facts.randomOrNull() ?: DEFAULT_FACT }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = DEFAULT_FACT
        )

    // MARK: - Custom Preferences (for settings UI)

    private val _customPreferences = MutableStateFlow(FactsPreferences())
    val customPreferences: StateFlow<FactsPreferences> = _customPreferences.asStateFlow()

    init {
        initializeCustomPreferences()
    }

    // MARK: - Public Methods


    fun toggleShowSource() {
        _customPreferences.update { preferences ->
            preferences.copy(showSource = !preferences.showSource)
        }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = FactsPreferences()
    }

    fun savePreferences() {
        viewModelScope.launch {
            widgetsRepo.updateFactsPreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.FACTS)
        }
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.FACTS)
        }
    }

    // MARK: - Private Methods

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            factsPreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L

        private const val DEFAULT_FACT = "If you lose your keys, you lose your coins."
    }
}
