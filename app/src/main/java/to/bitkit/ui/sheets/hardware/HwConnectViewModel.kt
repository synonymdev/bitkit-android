package to.bitkit.ui.sheets.hardware

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.TrezorFeatures
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.resolveHwWalletName
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Backs the Connect Hardware bottom-sheet flow (Intro -> Searching -> Found -> Paired). Drives
 * device discovery, connection and the Bitkit-side funds label through [HwWalletRepo], emitting
 * [HwConnectEffect]s that the sheet collects to navigate its inner [HardwareRoute] graph. The
 * one-time pairing code, when the device requests it during connect, is surfaced inline by
 * navigating to [HardwareRoute.PairCode].
 */
@HiltViewModel
class HwConnectViewModel @Inject constructor(
    private val hwWalletRepo: HwWalletRepo,
) : ViewModel() {
    companion object {
        private val SCAN_INTERVAL = 2.seconds
    }

    private val _uiState = MutableStateFlow(HwConnectUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<HwConnectEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var searchJob: Job? = null
    private var labelInitialized = false

    init {
        observePairingCode()
        observeConnectedWallet()
    }

    fun onIntroContinue() {
        setEffect(HwConnectEffect.NavigateToSearching)
        startSearching()
    }

    fun onConnectClick() {
        val deviceId = _uiState.value.foundDeviceId ?: return
        searchJob?.cancel()
        _uiState.update { it.copy(isConnecting = true) }
        viewModelScope.launch {
            hwWalletRepo.connect(deviceId)
                .onSuccess { onConnected(deviceId, it) }
                .onFailure { _uiState.update { state -> state.copy(isConnecting = false) } }
        }
    }

    fun onLabelChange(value: String) = _uiState.update { it.copy(labelInput = value) }

    fun onFinishClick() {
        val deviceId = _uiState.value.pairedDeviceId
        if (deviceId == null) {
            setEffect(HwConnectEffect.Dismiss)
            return
        }
        viewModelScope.launch {
            hwWalletRepo.setDeviceLabel(deviceId, _uiState.value.labelInput)
            setEffect(HwConnectEffect.Dismiss)
        }
    }

    fun resetState() {
        searchJob?.cancel()
        searchJob = null
        labelInitialized = false
        _uiState.update { HwConnectUiState() }
    }

    private fun startSearching() {
        if (searchJob?.isActive == true) return
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            while (isActive) {
                hwWalletRepo.scan()
                val device = hwWalletRepo.deviceState.value.nearbyDevices.firstOrNull()
                if (device != null) {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            foundDeviceId = device.id,
                            deviceModel = resolveHwWalletName(label = null, model = device.model),
                        )
                    }
                    setEffect(HwConnectEffect.NavigateToFound)
                    return@launch
                }
                delay(SCAN_INTERVAL)
            }
        }
    }

    private fun onConnected(deviceId: String, features: TrezorFeatures) {
        val name = resolveHwWalletName(label = features.label, model = features.model)
        _uiState.update {
            it.copy(
                isConnecting = false,
                pairedDeviceId = deviceId,
                deviceName = name,
                labelInput = if (labelInitialized) it.labelInput else name,
            )
        }
        labelInitialized = true
        setEffect(HwConnectEffect.NavigateToPaired)
    }

    private fun observePairingCode() {
        viewModelScope.launch {
            hwWalletRepo.needsPairingCode.collect { needsCode ->
                if (needsCode) setEffect(HwConnectEffect.NavigateToPairCode)
            }
        }
    }

    private fun observeConnectedWallet() {
        viewModelScope.launch {
            hwWalletRepo.wallets.collect { wallets ->
                val deviceId = _uiState.value.pairedDeviceId ?: return@collect
                val wallet = wallets.firstOrNull { deviceId == it.id || deviceId in it.deviceIds } ?: return@collect
                _uiState.update {
                    it.copy(
                        deviceName = wallet.name,
                        balanceSats = wallet.balanceSats,
                        labelInput = if (labelInitialized) it.labelInput else wallet.name,
                    )
                }
                labelInitialized = true
            }
        }
    }

    private fun setEffect(effect: HwConnectEffect) = viewModelScope.launch { _effects.emit(effect) }
}

@Immutable
data class HwConnectUiState(
    val isSearching: Boolean = false,
    val isConnecting: Boolean = false,
    val foundDeviceId: String? = null,
    val pairedDeviceId: String? = null,
    val deviceName: String = "",
    val deviceModel: String = "",
    val balanceSats: ULong = 0uL,
    val labelInput: String = "",
)

sealed interface HwConnectEffect {
    data object NavigateToSearching : HwConnectEffect
    data object NavigateToFound : HwConnectEffect
    data object NavigateToPairCode : HwConnectEffect
    data object NavigateToPaired : HwConnectEffect
    data object Dismiss : HwConnectEffect
}
