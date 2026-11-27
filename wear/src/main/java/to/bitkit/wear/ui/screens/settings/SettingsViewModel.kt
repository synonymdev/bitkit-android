package to.bitkit.wear.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()

    fun onRemoveClick() {
        _uiState.update { it.copy(isRemoveDialogVisible = true, isRemoveFailed = false) }
    }

    fun onRemoveDismiss() {
        _uiState.update { it.copy(isRemoveDialogVisible = false) }
    }

    fun onRemoveConfirm() {
        if (_uiState.value.isRemoving) return
        _uiState.update { it.copy(isRemoveDialogVisible = false, isRemoving = true, isRemoveFailed = false) }
        viewModelScope.launch {
            walletRepo.wipe()
                .onSuccess { _effects.emit(SettingsEffect.WalletRemoved) }
                .onFailure { _uiState.update { it.copy(isRemoveFailed = true) } }
            _uiState.update { it.copy(isRemoving = false) }
        }
    }
}

@Immutable
data class SettingsUiState(
    val isRemoveDialogVisible: Boolean = false,
    val isRemoving: Boolean = false,
    val isRemoveFailed: Boolean = false,
)

sealed interface SettingsEffect {
    data object WalletRemoved : SettingsEffect
}
