package to.bitkit.ui.settings.advanced

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.models.networkUiText
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class BitcoinNetworkSelectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BitcoinNetworkSelectionUiState())
    val uiState: StateFlow<BitcoinNetworkSelectionUiState> = _uiState.asStateFlow()

    init {
        collectState()
    }

    private fun collectState() {
        viewModelScope.launch {
            settingsStore.data.map { it.selectedNetwork }.distinctUntilChanged().collect { network ->
                _uiState.update {
                    it.copy(
                        selectedNetwork = network,
                    )
                }
            }
        }
    }

    fun selectNetwork(network: Network) {
        val currentNetwork = _uiState.value.selectedNetwork
        if (network == currentNetwork) return

        _uiState.update { it.copy(selectedNetwork = network, isLoading = true) }

        viewModelScope.launch {
            lightningRepo.restartWithNetworkChange(network)
                .onSuccess {
                    // TODO CLEAN ACTIVITY'S AND UPDATE STATE. CHECK ActivityListViewModel.removeAllActivities
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = "Network Switched",
                        description = "Successfully switched to ${network.networkUiText()}",
                    )
                }
                .onFailure { error ->
                    _uiState.update { it.copy(selectedNetwork = currentNetwork) }

                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Error Switching Networks",
                        description = "Please try again.",
                    )
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}

data class BitcoinNetworkSelectionUiState(
    val selectedNetwork: Network? = null,
    val availableNetworks: List<Network> = Env.availableNetworks,
    val isLoading: Boolean = false,
)
