package to.bitkit.ui.screens.recovery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.keychain.Keychain
import to.bitkit.domain.models.Secret
import to.bitkit.domain.models.splitWords
import to.bitkit.domain.models.wipeAll
import to.bitkit.models.Toast
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class RecoveryMnemonicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryMnemonicUiState())
    val uiState: StateFlow<RecoveryMnemonicUiState> = _uiState.asStateFlow()

    init {
        loadMnemonic()
    }

    private fun loadMnemonic() {
        viewModelScope.launch {
            runCatching {
                val mnemonicSecret = keychain.loadSecret(Keychain.Key.BIP39_MNEMONIC.name)
                val passphraseSecret = keychain.loadSecret(Keychain.Key.BIP39_PASSPHRASE.name)

                if (mnemonicSecret == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.security__mnemonic_load_error),
                        description = context.getString(R.string.security__mnemonic_load_error),
                    )
                    return@launch
                }

                val mnemonicWordSecrets = mnemonicSecret.splitWords()
                mnemonicSecret.wipe()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        mnemonicWords = mnemonicWordSecrets,
                        passphrase = passphraseSecret,
                    )
                }
            }.onFailure { e ->
                Logger.error("Failed to load mnemonic", e, context = TAG)
                _uiState.update { it.copy(isLoading = false) }
                ToastEventBus.send(e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.mnemonicWords.wipeAll()
        _uiState.value.passphrase?.wipe()
    }

    private companion object {
        const val TAG = "RecoveryMnemonicViewModel"
    }
}

data class RecoveryMnemonicUiState(
    val isLoading: Boolean = true,
    val mnemonicWords: List<Secret> = emptyList(),
    val passphrase: Secret? = null,
)
