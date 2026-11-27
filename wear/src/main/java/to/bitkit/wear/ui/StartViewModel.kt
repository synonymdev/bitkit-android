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

/** Picks the first screen while the splash screen shows, after the wallet storage is recovered. */
@HiltViewModel
class StartViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StartUiState())
    val uiState: StateFlow<StartUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(startRoute = startRoute()) }
        }
    }

    private suspend fun startRoute(): String {
        if (!walletRepo.isSupported) return Routes.UNSUPPORTED
        walletRepo.recover()
        return when {
            walletRepo.isRemovedNoticePending() -> Routes.WALLET_REMOVED
            walletRepo.hasWallet() -> Routes.HOME
            else -> Routes.ONBOARDING
        }
    }

    /** Tells the wallet whether the app is on screen, so it reconnects after the watch unlocks. */
    fun onForegroundChange(isForeground: Boolean) {
        viewModelScope.launch { walletRepo.setForeground(isForeground) }
    }
}

@Immutable
data class StartUiState(
    val startRoute: String? = null,
)
