package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AddressType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _isRescanning = MutableStateFlow(false)

    val uiState: StateFlow<AdvancedSettingsUiState> = combine(
        _isRescanning,
        walletRepo.walletState.map { it.addressType },
        settingsStore.data.map { it.isDevModeEnabled },
    ) { isRescanning, addressType, devMode ->
        AdvancedSettingsUiState(
            isRescanning = isRescanning,
            addressType = addressType,
            currentNetwork = Env.network,
            isDevModeEnabled = devMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AdvancedSettingsUiState()
    )

    fun rescanAddresses() {
        if (_isRescanning.value) return

        viewModelScope.launch {
            try {
                _isRescanning.value = true
                // TODO: Implement actual rescan functionality
                delay(1500)
            } catch (_: Throwable) {
            } finally {
                _isRescanning.value = false
            }
        }
    }

    fun resetSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }
}

data class AdvancedSettingsUiState(
    val isRescanning: Boolean = false,
    val addressType: AddressType = AddressType.P2WPKH,
    val currentNetwork: Network = Env.network,
    val isDevModeEnabled: Boolean = false,
)
