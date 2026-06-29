package to.bitkit.ui.screens.wallets

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.HwWallet
import to.bitkit.models.Toast
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class HwWalletViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hwWalletRepo: HwWalletRepo,
) : ViewModel() {

    val wallets: StateFlow<ImmutableList<HwWallet>> = hwWalletRepo.wallets
    val walletsLoaded: StateFlow<Boolean> = hwWalletRepo.walletsLoaded

    private val _uiState = MutableStateFlow(HwWalletDetailUiState())
    val uiState: StateFlow<HwWalletDetailUiState> = _uiState.asStateFlow()

    fun onRemoveClick(wallet: HwWallet) = _uiState.update { it.copy(isPendingRemoval = wallet) }

    fun onDismissRemoveDialog() = _uiState.update { it.copy(isPendingRemoval = null) }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPendingRemoval = null) }
            hwWalletRepo.removeDevice(deviceId).onFailure {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.common__error),
                    description = context.getString(R.string.hardware__remove_error),
                )
            }
        }
    }
}

@Immutable
data class HwWalletDetailUiState(
    val isPendingRemoval: HwWallet? = null,
)
