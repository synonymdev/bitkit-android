package to.bitkit.wear.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.repositories.SettingsSyncRepo
import to.bitkit.wear.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
    private val settingsSyncRepo: SettingsSyncRepo,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.data.map { it.phoneSyncedAtMs }.distinctUntilChanged().collect { syncedAtMs ->
                _uiState.update { it.copy(phoneSyncedAtMs = syncedAtMs) }
            }
        }
    }

    /** Imports the phone's settings now, from the row that shows when they were last imported. */
    fun onSyncClick() {
        if (_uiState.value.isSyncing) return
        _uiState.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            settingsSyncRepo.sync()
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    fun onRemoveClick() {
        _uiState.update { it.copy(isRemoveDialogVisible = true, isRemoveFailed = false) }
    }

    fun onRemoveDismiss() {
        _uiState.update { it.copy(isRemoveDialogVisible = false) }
    }

    /**
     * Removes the wallet. Home leaves for onboarding once the wallet's state says it is gone, so the removal reaches
     * the wearer even when this page was swiped away meanwhile.
     */
    fun onRemoveConfirm() {
        if (_uiState.value.isRemoving) return
        _uiState.update { it.copy(isRemoveDialogVisible = false, isRemoving = true, isRemoveFailed = false) }
        viewModelScope.launch {
            walletRepo.wipe().onFailure { _uiState.update { it.copy(isRemoveFailed = true) } }
            _uiState.update { it.copy(isRemoving = false) }
        }
    }
}

@Immutable
data class SettingsUiState(
    val isRemoveDialogVisible: Boolean = false,
    val isRemoving: Boolean = false,
    val isRemoveFailed: Boolean = false,
    val isSyncing: Boolean = false,
    /** When the phone's settings were last imported, in epoch milliseconds, or null when there is no import. */
    val phoneSyncedAtMs: Long? = null,
)
