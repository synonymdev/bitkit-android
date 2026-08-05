package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.data.SpendingBackend
import to.bitkit.env.Env
import to.bitkit.ext.filterOpen
import to.bitkit.models.ElectrumServer
import to.bitkit.models.addressTypeInfo
import to.bitkit.models.toAddressType
import to.bitkit.repositories.BarkRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.usecases.CanSwitchSpendingBackendUseCase
import to.bitkit.usecases.SpendingBackendSwitchState
import javax.inject.Inject

private const val NODE_ID_PREFIX_LENGTH = 5
private const val ELECTRUM_HOST_PREFIX_LENGTH = 5

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    private val barkRepo: BarkRepo,
    private val walletRepo: WalletRepo,
    private val canSwitchSpendingBackend: CanSwitchSpendingBackendUseCase,
    watchOnlyAccountRepo: WatchOnlyAccountRepo,
) : ViewModel() {

    val selectedAddressTypeName = settingsStore.data
        .map { it.selectedAddressType.toAddressType()?.addressTypeInfo()?.shortName ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val openChannelCount = lightningRepo.lightningState
        .map { it.channels.filterOpen().size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val watchOnlyAccountCount = watchOnlyAccountRepo.currentWalletAccountCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val truncatedNodeId = lightningRepo.lightningState
        .map { it.nodeId.take(NODE_ID_PREFIX_LENGTH).ifEmpty { "" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val electrumHost = settingsStore.data
        .map {
            if (it.electrumServer == Env.electrumServerUrl) {
                ""
            } else {
                val host = ElectrumServer.parse(it.electrumServer).host
                    .substringAfter("://")
                if (host.length > ELECTRUM_HOST_PREFIX_LENGTH) {
                    "${host.take(ELECTRUM_HOST_PREFIX_LENGTH)}..."
                } else {
                    host
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val coinSelectAuto = settingsStore.data
        .map { it.coinSelectAuto }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun resetSuggestions() {
        viewModelScope.launch {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }

    // region spending backend

    /** Hidden entirely on networks without a hosted Ark server, where bark cannot run at all. */
    val isArkSupported = Env.isArkSupported

    val isArkEnabled = settingsStore.data
        .map { it.spendingBackend == SpendingBackend.BARK }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _spendingBackendDialog = MutableStateFlow<SpendingBackendDialog?>(null)
    val spendingBackendDialog: StateFlow<SpendingBackendDialog?> = _spendingBackendDialog.asStateFlow()

    fun onArkToggleClick() {
        viewModelScope.launch {
            val target = if (isArkEnabled.value) SpendingBackend.LDK else SpendingBackend.BARK
            _spendingBackendDialog.update {
                when (val switchState = canSwitchSpendingBackend(target)) {
                    is SpendingBackendSwitchState.Allowed -> SpendingBackendDialog.Confirm(target)
                    is SpendingBackendSwitchState.Blocked -> SpendingBackendDialog.Blocked(switchState)
                }
            }
        }
    }

    fun confirmSpendingBackendSwitch() {
        val dialog = _spendingBackendDialog.value as? SpendingBackendDialog.Confirm ?: return
        viewModelScope.launch {
            settingsStore.update { it.copy(spendingBackend = dialog.target) }
            when (dialog.target) {
                SpendingBackend.BARK -> barkRepo.startIfEnabled()
                SpendingBackend.LDK -> barkRepo.stop()
            }
            walletRepo.syncBalances()
            _spendingBackendDialog.update { null }
        }
    }

    fun dismissSpendingBackendDialog() = run { _spendingBackendDialog.update { null } }

    // endregion
}

sealed interface SpendingBackendDialog {
    data class Confirm(val target: SpendingBackend) : SpendingBackendDialog
    data class Blocked(val reason: SpendingBackendSwitchState.Blocked) : SpendingBackendDialog
}
