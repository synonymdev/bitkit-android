package to.bitkit.wear.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
)
