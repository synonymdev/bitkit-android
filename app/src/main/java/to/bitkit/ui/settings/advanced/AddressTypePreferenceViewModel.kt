package to.bitkit.ui.settings.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AddressType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class AddressTypePreferenceViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val walletRepo: WalletRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddressTypePreferenceUiState())
    val uiState: StateFlow<AddressTypePreferenceUiState> = _uiState.asStateFlow()

    init {
        collectState()
    }

    private fun collectState() {
        viewModelScope.launch {
            settingsStore.data.collect { settings ->
                val availableAddressTypes = listOfNotNull(
                    AddressType.P2TR.takeIf { settings.isDevModeEnabled },
                    AddressType.P2WPKH,
                    AddressType.P2SH,
                    AddressType.P2PKH,
                )
                _uiState.update {
                    AddressTypePreferenceUiState(
                        selectedAddressType = settings.addressType,
                        isDevModeEnabled = settings.isDevModeEnabled,
                        availableAddressTypes = availableAddressTypes,
                    )
                }
            }
        }
    }

    fun setAddressType(addressType: AddressType) {
        viewModelScope.launch {
            settingsStore.update { it.copy(addressType = addressType) }
            // Refresh wallet to generate new address with preferred type
            walletRepo.refreshBip21(force = true)
        }
    }
}

data class AddressTypePreferenceUiState(
    val availableAddressTypes: List<AddressType> = emptyList(),
    val selectedAddressType: AddressType = AddressType.P2WPKH,
    val isDevModeEnabled: Boolean = false,
)
