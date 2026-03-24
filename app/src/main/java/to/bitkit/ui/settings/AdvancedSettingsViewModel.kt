package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.ext.filterOpen
import to.bitkit.models.addressTypeInfo
import to.bitkit.models.toAddressType
import to.bitkit.repositories.LightningRepo
import javax.inject.Inject

private const val NODE_ID_PREFIX_LENGTH = 5

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    val selectedAddressTypeName = settingsStore.data
        .map { it.selectedAddressType.toAddressType()?.addressTypeInfo()?.shortName ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openChannelCount = lightningRepo.lightningState
        .map { it.channels.filterOpen().size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val truncatedNodeId = lightningRepo.lightningState
        .map { it.nodeId.take(NODE_ID_PREFIX_LENGTH).ifEmpty { "" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun resetSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }
}
