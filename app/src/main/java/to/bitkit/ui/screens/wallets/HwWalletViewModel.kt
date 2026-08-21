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
import to.bitkit.repositories.HwBackupDataUnreadableError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.HwWalletRepo.Companion.DEVICE_LABEL_MAX_LENGTH
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

    private var renameSessionId = 0L

    // Reset on open rather than on dismiss, so a back press, a failed removal or another wallet picked
    // from the list all start from the default rather than from the last choice.
    fun onRemoveClick(wallet: HwWallet) = _uiState.update {
        it.copy(isPendingRemoval = wallet, keepBackupDataOnRemoval = true)
    }

    fun onKeepBackupDataChange(value: Boolean) = _uiState.update { it.copy(keepBackupDataOnRemoval = value) }

    fun onDismissRemoveDialog() = _uiState.update { it.copy(isPendingRemoval = null) }

    fun onRenameClick(wallet: HwWallet) {
        renameSessionId += 1
        _uiState.update {
            it.copy(
                isPendingRename = wallet,
                labelInput = wallet.name.take(DEVICE_LABEL_MAX_LENGTH),
                isSavingLabel = false,
                isRenameSaved = false,
            )
        }
    }

    fun onDismissRenameSheet() {
        renameSessionId += 1
        _uiState.update {
            it.copy(
                isPendingRename = null,
                labelInput = "",
                isSavingLabel = false,
                isRenameSaved = false,
            )
        }
    }

    fun onLabelChange(value: String) = _uiState.update { it.copy(labelInput = value.take(DEVICE_LABEL_MAX_LENGTH)) }

    fun saveDeviceLabel() {
        val state = _uiState.value
        val wallet = state.isPendingRename ?: return
        if (state.labelInput.trim().isEmpty() || state.isSavingLabel) return
        val sessionId = renameSessionId

        viewModelScope.launch {
            _uiState.update {
                if (it.matchesRenameSession(wallet.id, sessionId)) it.copy(isSavingLabel = true) else it
            }
            hwWalletRepo.setDeviceLabel(wallet.id, state.labelInput)
                .onSuccess {
                    _uiState.update {
                        if (it.matchesRenameSession(wallet.id, sessionId)) {
                            it.copy(
                                isSavingLabel = false,
                                isRenameSaved = true,
                            )
                        } else {
                            it
                        }
                    }
                }
                .onFailure {
                    var shouldSendToast = false
                    _uiState.update {
                        if (it.matchesRenameSession(wallet.id, sessionId)) {
                            shouldSendToast = true
                            it.copy(isSavingLabel = false)
                        } else {
                            it
                        }
                    }
                    if (shouldSendToast) {
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = context.getString(R.string.hardware__rename_error),
                        )
                    }
                }
        }
    }

    private fun HwWalletDetailUiState.matchesRenameSession(walletId: String, sessionId: Long) =
        renameSessionId == sessionId && isPendingRename?.id == walletId

    fun removeDevice(walletId: String) {
        // Read before the update below clears the pending state this choice was made in.
        val keepBackupData = _uiState.value.keepBackupDataOnRemoval

        viewModelScope.launch {
            _uiState.update { it.copy(isPendingRemoval = null) }
            hwWalletRepo.removeDevice(walletId, keepBackupData).onFailure {
                // The wallet is untouched when its backup data could not be read, so say what failed
                // and point at the way through instead of asking for a retry that repeats it.
                val description = when (it) {
                    is HwBackupDataUnreadableError -> R.string.hardware__remove_keep_error
                    else -> R.string.hardware__remove_error
                }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.common__error),
                    description = context.getString(description),
                )
            }
        }
    }
}

@Immutable
data class HwWalletDetailUiState(
    val isPendingRemoval: HwWallet? = null,
    val keepBackupDataOnRemoval: Boolean = true,
    val isPendingRename: HwWallet? = null,
    val labelInput: String = "",
    val isSavingLabel: Boolean = false,
    val isRenameSaved: Boolean = false,
)
