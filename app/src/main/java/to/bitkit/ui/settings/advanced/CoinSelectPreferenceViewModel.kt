package to.bitkit.ui.settings.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.models.CoinSelectionPreference
import javax.inject.Inject

@HiltViewModel
class CoinSelectPreferenceViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoinSelectPreferenceUiState())
    val uiState: StateFlow<CoinSelectPreferenceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.data.map { settings ->
                CoinSelectPreferenceUiState(
                    isAutoPilot = settings.coinSelectAuto,
                    coinSelectionPreference = settings.coinSelectPreference,
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun setAutoMode(value: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(coinSelectAuto = value) }
            // TODO: Integrate with wallet coin selection: set coinSelectPreference=default(which?)
        }
    }

    fun setCoinSelectionPreference(preference: CoinSelectionPreference) {
        viewModelScope.launch {
            settingsStore.update { it.copy(coinSelectPreference = preference) }
            // TODO: Integrate with wallet coin selection: set coinSelectPreference=preference
        }
    }
}

data class CoinSelectPreferenceUiState(
    val isAutoPilot: Boolean = false,
    val coinSelectionPreference: CoinSelectionPreference = CoinSelectionPreference.SmallestFirst,
)
