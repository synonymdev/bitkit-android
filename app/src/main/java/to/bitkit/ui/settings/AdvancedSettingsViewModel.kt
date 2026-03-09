package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.models.addressTypeInfo
import to.bitkit.models.toAddressType
import javax.inject.Inject

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val isDevModeEnabled = settingsStore.data.map { it.isDevModeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedAddressTypeName = settingsStore.data
        .map { it.selectedAddressType.toAddressType()?.addressTypeInfo()?.shortName ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun resetSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }
}
