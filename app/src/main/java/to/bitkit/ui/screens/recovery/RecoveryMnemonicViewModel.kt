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
                val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name).orEmpty()
                val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name).orEmpty()

                if (mnemonic.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                        )
                    }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.security__mnemonic_load_error),
                        description = context.getString(R.string.security__mnemonic_load_error),
                    )
                    return@launch
                }

                val mnemonicWords = mnemonic.split(" ").filter { it.isNotBlank() }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        mnemonicWords = mnemonicWords,
                        passphrase = passphrase,
                    )
                }
            }.onFailure { e ->
                Logger.error("Failed to load mnemonic", e, context = TAG)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                    )
                }
                ToastEventBus.send(e)
            }
        }
    }

    private companion object {
        const val TAG = "RecoveryMnemonicViewModel"
    }
}

data class RecoveryMnemonicUiState(
    val isLoading: Boolean = true,
    val mnemonicWords: List<String> = emptyList(),
    val passphrase: String = "",
)
