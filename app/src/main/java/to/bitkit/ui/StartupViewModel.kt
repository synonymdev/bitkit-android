package to.bitkit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.data.keychain.Keychain
import javax.inject.Inject

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val keychain: Keychain,
) : ViewModel() {
    private val walletExists: Boolean get() = keychain.exists(Keychain.Key.BIP39_MNEMONIC.name)

    var uiState by mutableStateOf(StartupUiState(walletExists))
        private set
}

data class StartupUiState(
    val hasWallet: Boolean = false,
)
