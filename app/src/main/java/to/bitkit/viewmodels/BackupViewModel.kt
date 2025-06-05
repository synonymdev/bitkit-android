package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.Toast
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.BackupContract.SideEffect
import to.bitkit.viewmodels.BackupContract.UiState
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val keychain: Keychain,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private fun setEffect(effect: SideEffect) = viewModelScope.launch { _effects.emit(effect) }

    fun loadMnemonicData() {
        viewModelScope.launch {
            try {
                val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)!! // NPE handled with UI toast
                val bip39Passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name) ?: ""

                _uiState.update {
                    it.copy(
                        bip39Mnemonic = mnemonic,
                        bip39Passphrase = bip39Passphrase,
                    )
                }
            } catch (e: Throwable) {
                Logger.error("Error loading mnemonic", e)
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.security__mnemonic_error),
                    description = context.getString(R.string.security__mnemonic_error_description),
                )
            }
        }
    }

    fun onRevealMnemonic() {
        viewModelScope.launch {
            delay(200) // Small delay for better UX
            _uiState.update { it.copy(showMnemonic = true) }
        }
    }

    fun onShowMnemonicContinue() {
        val state = _uiState.value
        if (state.bip39Passphrase.isNotEmpty()) {
            setEffect(SideEffect.NavigateToShowPassphrase)
        } else {
            setEffect(SideEffect.NavigateToConfirmMnemonic)
        }
    }

    fun onShowPassphraseContinue() {
        setEffect(SideEffect.NavigateToConfirmMnemonic)
    }

    fun onConfirmMnemonicContinue() {
        val state = _uiState.value
        if (state.bip39Passphrase.isNotEmpty()) {
            setEffect(SideEffect.NavigateToConfirmPassphrase)
        } else {
            setEffect(SideEffect.NavigateToWarning)
        }
    }

    fun onPassphraseInput(passphrase: String) {
        _uiState.update { it.copy(enteredPassphrase = passphrase) }
    }

    fun onConfirmPassphraseContinue() {
        setEffect(SideEffect.NavigateToWarning)
    }

    fun onWarningContinue() {
        setEffect(SideEffect.NavigateToSuccess)
    }

    fun onSuccessContinue() {
        viewModelScope.launch {
            settingsStore.update { it.copy(backupVerified = true) }
            setEffect(SideEffect.NavigateToMultipleDevices)
        }
    }

    fun onMultipleDevicesContinue() {
        // TODO: get from actual repository state
        val lastBackupTimeMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(lastBackupTimeMs = lastBackupTimeMs)
        }
        setEffect(SideEffect.NavigateToMetadata)
    }

    fun onMetadataClose() {
        setEffect(SideEffect.DismissSheet)
    }

    fun resetState() {
        _uiState.update { UiState() }
    }
}

interface BackupContract {
    companion object {
        private val PLACEHOLDER_MNEMONIC = List(24) { "secret" }.joinToString(" ")
    }

    data class UiState(
        val bip39Mnemonic: String = PLACEHOLDER_MNEMONIC,
        val bip39Passphrase: String = "",
        val showMnemonic: Boolean = false,
        val enteredPassphrase: String = "",
        val lastBackupTimeMs: Long = System.currentTimeMillis(),
    )

    sealed interface SideEffect {
        data object NavigateToShowPassphrase : SideEffect
        data object NavigateToConfirmMnemonic : SideEffect
        data object NavigateToConfirmPassphrase : SideEffect
        data object NavigateToWarning : SideEffect
        data object NavigateToSuccess : SideEffect
        data object NavigateToMultipleDevices : SideEffect
        data object NavigateToMetadata : SideEffect
        data object DismissSheet : SideEffect
    }
}
