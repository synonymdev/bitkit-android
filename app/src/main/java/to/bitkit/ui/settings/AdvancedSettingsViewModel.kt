package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import javax.inject.Inject

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    fun resetSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }
}
