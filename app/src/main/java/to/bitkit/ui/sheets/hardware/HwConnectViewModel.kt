package to.bitkit.ui.sheets.hardware

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.TrezorFeatures
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.HwWalletRepo.Companion.DEVICE_LABEL_MAX_LENGTH
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
    @ApplicationContext private val context: Context,
) : ViewModel() {
    companion object {
        /** Delay between scan attempts while searching for a nearby device. */
        private val SCAN_INTERVAL = 2.seconds
    }

    private val _uiState = MutableStateFlow(HwConnectUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<HwConnectEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var searchJob: Job? = null
    private var connectJob: Job? = null
    private var labelInitialized = false
    private var includeBluetoothInScan = true
    private var scanUsbBeforeConnect = false

    init {
        observePairingCode()
        observeConnectedWallet()
    }

    fun onIntroContinue(includeBluetooth: Boolean = true) {
        includeBluetoothInScan = includeBluetooth
        _uiState.update { it.copy(errorMessage = null) }
        setEffect(HwConnectEffect.NavigateToSearching)
        startSearching()
    }

    fun setBluetoothScanningEnabled() {
        includeBluetoothInScan = true
    }

    fun onFoundRoute(deviceId: String?, deviceModel: String) {
        if (deviceId == null) return
        searchJob?.cancel()
        searchJob = null
        _uiState.update {
            it.copy(
                isSearching = false,
                foundDeviceId = deviceId,
                deviceModel = deviceModel.ifBlank { resolveHwWalletName(label = null, model = null) },
                errorMessage = null,
            )
        }
        scanUsbBeforeConnect = true
    }

    fun onConnectClick(deviceIdOverride: String? = null) {
        val state = _uiState.value
        val deviceId = deviceIdOverride ?: state.foundDeviceId ?: return
        if (connectJob?.isActive == true) return
        val shouldScanUsbBeforeConnect = scanUsbBeforeConnect
        searchJob?.cancel()
        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
        connectJob = viewModelScope.launch {
            var resolvedDeviceId = deviceId
            var resolvedDeviceModel = state.deviceModel
            if (shouldScanUsbBeforeConnect) {
                hwWalletRepo.scan(includeBluetooth = false)
                    .onSuccess { devices ->
                        devices.firstOrNull { it.id == deviceId || it.path == deviceId }?.let { device ->
                            resolvedDeviceId = device.id
                            resolvedDeviceModel = resolveHwWalletName(label = null, model = device.model)
                            _uiState.update {
                                it.copy(
                                    foundDeviceId = resolvedDeviceId,
                                    deviceModel = resolvedDeviceModel,
                                )
                            }
                        }
                    }
                    .onFailure {
                        onConnectFailed(resolvedDeviceId, resolvedDeviceModel)
                        return@launch
                    }
            }
            hwWalletRepo.connect(resolvedDeviceId)
                .onSuccess { onConnected(resolvedDeviceId, it) }
                .onFailure { onConnectFailed(resolvedDeviceId, resolvedDeviceModel) }
            connectJob = null
        }
    }

    private fun onConnectFailed(deviceId: String, deviceModel: String) {
        _uiState.update {
            it.copy(
                isConnecting = false,
                foundDeviceId = deviceId,
                deviceModel = deviceModel,
                errorMessage = context.getString(R.string.hardware__connect_error),
            )
        }
        setEffect(
            HwConnectEffect.NavigateToFound(
                deviceId = deviceId,
                deviceModel = deviceModel,
            )
        )
        connectJob = null
    }

    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        hwWalletRepo.cancelPairingCode()
        _uiState.update { it.copy(isConnecting = false) }
    }

    fun onLabelChange(value: String) = _uiState.update { it.copy(labelInput = value.take(DEVICE_LABEL_MAX_LENGTH)) }

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
        connectJob?.cancel()
        connectJob = null
        hwWalletRepo.cancelPairingCode()
        labelInitialized = false
        includeBluetoothInScan = true
        scanUsbBeforeConnect = false
        _uiState.update { HwConnectUiState() }
    }

    private fun startSearching() {
        if (searchJob?.isActive == true) return
        scanUsbBeforeConnect = false
        _uiState.update { it.copy(isSearching = true, errorMessage = null) }
        searchJob = viewModelScope.launch {
            while (isActive) {
                val scanResult = hwWalletRepo.scan(includeBluetooth = includeBluetoothInScan)
                if (scanResult.isFailure) {
                    _uiState.update {
                        it.copy(errorMessage = context.getString(R.string.hardware__search_error))
                    }
                    delay(SCAN_INTERVAL)
                    continue
                }
                _uiState.update { it.copy(errorMessage = null) }
                val device = hwWalletRepo.deviceState.value.nearbyDevices.firstOrNull()
                if (device != null) {
                    val deviceModel = resolveHwWalletName(label = null, model = device.model)
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            foundDeviceId = device.id,
                            deviceModel = deviceModel,
                            errorMessage = null,
                        )
                    }
                    setEffect(HwConnectEffect.NavigateToFound(device.id, deviceModel))
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
                errorMessage = null,
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
    val errorMessage: String? = null,
)

sealed interface HwConnectEffect {
    data object NavigateToSearching : HwConnectEffect
    data class NavigateToFound(val deviceId: String, val deviceModel: String) : HwConnectEffect
    data object NavigateToPairCode : HwConnectEffect
    data object NavigateToPaired : HwConnectEffect
    data object Dismiss : HwConnectEffect
}
