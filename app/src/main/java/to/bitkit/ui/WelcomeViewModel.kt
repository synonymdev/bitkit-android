package to.bitkit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.generateEntropyMnemonic
import to.bitkit.data.keychain.Keychain
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val keychain: Keychain,
) : ViewModel() {
    var uiState by mutableStateOf(WelcomeUiState())
        private set

    fun createWallet(bip39Passphrase: String) {
        viewModelScope.launch {
            val mnemonic = generateEntropyMnemonic()
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, mnemonic)
            if (bip39Passphrase.isNotBlank()) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            uiState = uiState.copy(isWalletSubmitted = true)
        }
    }

    fun restoreWallet(bip39Passphrase: String, bip39Mnemonic: String) {
        viewModelScope.launch {
            keychain.saveString(Keychain.Key.BIP39_MNEMONIC.name, bip39Mnemonic)
            if (bip39Passphrase.isNotBlank()) {
                keychain.saveString(Keychain.Key.BIP39_PASSPHRASE.name, bip39Passphrase)
            }
            uiState = uiState.copy(isWalletSubmitted = true)
        }
    }
}

data class WelcomeUiState(
    val isWalletSubmitted: Boolean = false,
)
