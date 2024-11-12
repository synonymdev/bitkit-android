package to.bitkit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val keychain: Keychain,
) : ViewModel() {
    var uiState by mutableStateOf(AppUiState())
        private set

    init {
        viewModelScope.launch {
            keychain.observeExists(Keychain.Key.BIP39_MNEMONIC).collect { walletExists ->
                uiState = uiState.copy(walletExists = walletExists)
            }
        }
    }

    var showNewTransaction by mutableStateOf(false)
        private set

    var newTransaction by mutableStateOf(
        NewTransactionSheetDetails(
            NewTransactionSheetType.LIGHTNING,
            NewTransactionSheetDirection.RECEIVED,
            0
        )
    )

    fun showNewTransactionSheet(details: NewTransactionSheetDetails) {
        newTransaction = details
        showNewTransaction = true
    }

    fun hideNewTransactionSheet() {
        showNewTransaction = false
    }
}

data class AppUiState(
    val walletExists: Boolean? = null,
)
