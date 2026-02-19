package to.bitkit.ui.settings.advanced

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AddressType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.Toast
import to.bitkit.models.toAddressType
import to.bitkit.models.toSettingsString
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class AddressTypePreferenceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddressTypePreferenceUiState())
    val uiState: StateFlow<AddressTypePreferenceUiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch(bgDispatcher) {
            settingsStore.data.first().let { settings ->
                val selected = settings.selectedAddressType.toAddressType() ?: AddressType.P2WPKH
                val monitored = settings.addressTypesToMonitor.toSet()
                _uiState.update {
                    it.copy(
                        selectedAddressType = selected,
                        monitoredTypes = monitored,
                        showMonitoredTypes = settings.isDevModeEnabled,
                    )
                }
            }
        }
    }

    fun updateAddressType(addressType: AddressType) {
        if (_uiState.value.isLoading) return
        if (_uiState.value.selectedAddressType == addressType) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.settings__addr_type__applying),
                autoHide = false,
            )

            val currentMonitored = _uiState.value.monitoredTypes.toMutableSet()
            currentMonitored.add(addressType.toSettingsString())
            val result = lightningRepo.updateAddressType(
                selectedType = addressType.toSettingsString(),
                monitoredTypes = currentMonitored.toList(),
            ).onSuccess {
                walletRepo.refreshReceiveAddressAfterTypeChange()
            }

            _uiState.update { it.copy(isLoading = false) }
            loadState()

            if (result.isSuccess) {
                ToastEventBus.send(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.settings__addr_type__settings_updated),
                )
            } else {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.common__error),
                    description = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun setMonitoring(addressType: AddressType, enabled: Boolean) {
        if (_uiState.value.isLoading) return

        val isMonitored = addressType.toSettingsString() in _uiState.value.monitoredTypes
        if (isMonitored == enabled) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.settings__addr_type__applying),
                autoHide = false,
            )

            val repoResult = lightningRepo.setMonitoring(addressType, enabled)

            _uiState.update { it.copy(isLoading = false) }
            loadState()

            if (repoResult.isSuccess) {
                ToastEventBus.send(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.settings__addr_type__settings_updated),
                )
            } else {
                val ex = repoResult.exceptionOrNull()?.message
                val msg = when {
                    ex?.contains("has balance") == true ->
                        context.getString(R.string.settings__addr_type__disabled_has_balance)
                    ex?.contains("verify") == true ->
                        context.getString(R.string.settings__addr_type__disabled_verify_failed)
                    ex?.contains("Native SegWit or Taproot") == true ->
                        context.getString(R.string.settings__addr_type__disabled_native_required)
                    ex?.contains("currently selected") == true ->
                        context.getString(R.string.settings__addr_type__disabled_currently_selected)
                    else -> ex
                }
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.common__error),
                    description = msg,
                )
            }
        }
    }
}

data class AddressTypePreferenceUiState(
    val selectedAddressType: AddressType = AddressType.P2WPKH,
    val monitoredTypes: Set<String> = setOf("nativeSegwit"),
    val showMonitoredTypes: Boolean = false,
    val isLoading: Boolean = false,
)
