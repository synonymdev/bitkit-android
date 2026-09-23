package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.models.OfflineReceiveDevSettings
import to.bitkit.services.offline.OfflineReceiveSettings
import to.bitkit.services.offline.OfflineReceiveSettingsSource
import javax.inject.Inject

/** Backs the "Offline receive (experimental)" dev settings section. Changes apply on the next node build. */
@HiltViewModel
class OfflineReceiveDevSettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    settingsSource: OfflineReceiveSettingsSource,
) : ViewModel() {

    val devSettings: StateFlow<OfflineReceiveDevSettings> = settingsStore.offlineReceiveDevSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OfflineReceiveDevSettings())

    val effectiveSettings: StateFlow<OfflineReceiveSettings> = settingsSource.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OfflineReceiveSettings())

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.setOfflineReceiveEnabled(value) }
    }

    fun setSettlementNodeId(value: String) {
        viewModelScope.launch { settingsStore.setOfflineReceiveSettlementNodeId(value) }
    }

    fun setWitnessNodeIds(value: String) {
        viewModelScope.launch { settingsStore.setOfflineReceiveWitnessNodeIds(value.split(',')) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
