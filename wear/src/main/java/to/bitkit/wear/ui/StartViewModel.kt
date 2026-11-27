package to.bitkit.wear.ui

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

/**
 * Picks the first screen while the splash screen and then the loading ring show, after the wallet storage is
 * recovered. When the recovery fails, as when a wipe that did not complete cannot be finished, no screen is picked
 * and the wearer retries.
 */
@HiltViewModel
class StartViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StartUiState())
    val uiState: StateFlow<StartUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun onRetryClick() {
        if (_uiState.value.isLoading) return
        load()
    }

    /** Reads the lock state when the app's window regains focus, which is how an unlock shows. */
    fun onFocusGain() {
        viewModelScope.launch { walletRepo.refreshLock() }
    }

    private fun load() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val route = startRoute()
            _uiState.update { it.copy(startRoute = route, isRecoveryFailed = route == null, isLoading = false) }
        }
    }

    private suspend fun startRoute(): String? {
        if (!walletRepo.isSupported) return Routes.UNSUPPORTED
        if (walletRepo.recover().isFailure) return null
        return when {
            walletRepo.isRemovedNoticePending() -> Routes.WALLET_REMOVED
            walletRepo.hasWallet() -> Routes.HOME
            else -> Routes.ONBOARDING
        }
    }
}

@Immutable
data class StartUiState(
    val startRoute: String? = null,
    val isRecoveryFailed: Boolean = false,
    val isLoading: Boolean = true,
) {
    /** Whether the first screen is still being picked: the splash screen stays for a moment, then the loading ring. */
    val isStarting: Boolean get() = startRoute == null && !isRecoveryFailed
}
