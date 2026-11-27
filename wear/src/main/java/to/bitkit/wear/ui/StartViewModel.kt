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

/** Picks the first screen while the splash screen shows. */
@HiltViewModel
class StartViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StartUiState())
    val uiState: StateFlow<StartUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val route = when {
                !walletRepo.isSupported -> Routes.UNSUPPORTED
                walletRepo.hasWallet() -> Routes.HOME
                else -> Routes.ONBOARDING
            }
            _uiState.update { it.copy(startRoute = route) }
        }
    }
}

@Immutable
data class StartUiState(
    val startRoute: String? = null,
)
