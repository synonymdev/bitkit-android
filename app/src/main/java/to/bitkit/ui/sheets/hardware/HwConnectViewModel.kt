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
import to.bitkit.ext.isTrezorDeviceBusy
import to.bitkit.models.Toast
import to.bitkit.repositories.HwPassphraseAlreadyAddedError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.HwWalletRepo.Companion.DEVICE_LABEL_MAX_LENGTH
import to.bitkit.repositories.resolveHwWalletName
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.TrezorErrorPresenter
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Backs the Connect Hardware bottom-sheet flow (Intro -> Searching -> Found -> Paired). Drives
 * device discovery, connection and the Bitkit-side funds label through [HwWalletRepo], emitting
 * [HwConnectEffect]s that the sheet collects to navigate its inner [HardwareRoute] graph. The
 * one-time pairing code, when the device requests it during connect, is surfaced inline by
 * navigating to [HardwareRoute.PairCode].
 *
 * From the paired step the user can add the passphrase (hidden) wallets of the same device, each
 * becoming its own watched identity with its own label and balance.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class HwConnectViewModel @Inject constructor(
    private val hwWalletRepo: HwWalletRepo,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    companion object {
        private const val TAG = "HwConnectViewModel"

        /** Delay between scan attempts while searching for a nearby device. */
        private val SCAN_INTERVAL = 2.seconds

        /** Prefix used by Android USB attach intents for [android.hardware.usb.UsbDevice.deviceName]. */
        private const val USB_DEVICE_PATH_PREFIX = "/dev/"
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
        scanUsbBeforeConnect = deviceId.startsWith(USB_DEVICE_PATH_PREFIX)
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
                    .onFailure { error ->
                        onConnectFailed(resolvedDeviceId, resolvedDeviceModel, error)
                        return@launch
                    }
            }
            hwWalletRepo.connect(resolvedDeviceId)
                .onSuccess { onConnected(resolvedDeviceId, it) }
                .onFailure { error -> onConnectFailed(resolvedDeviceId, resolvedDeviceModel, error) }
            connectJob = null
        }
    }

    private fun onConnectFailed(deviceId: String, deviceModel: String, error: Throwable) {
        _uiState.update {
            it.copy(
                isConnecting = false,
                foundDeviceId = deviceId,
                deviceModel = deviceModel,
                errorMessage = if (error.isTrezorDeviceBusy()) {
                    TrezorErrorPresenter.userMessage(context, error)
                } else {
                    context.getString(R.string.hardware__connect_error)
                },
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

    fun onLabelChange(value: String) {
        // Once the user types, the field is theirs: a wallet emission arriving late (the store
        // publishes a newly watched identity asynchronously) must not overwrite what they entered.
        labelInitialized = true
        _uiState.update { it.copy(labelInput = value.take(DEVICE_LABEL_MAX_LENGTH)) }
    }

    fun onPassphraseClick() {
        // Each identity is labelled on its own paired step, so persist the one being left before
        // the next passphrase wallet takes over the field.
        val state = _uiState.value
        state.pairedWalletId?.let { walletId ->
            viewModelScope.launch { persistLabel(walletId, state.labelInput) }
        }
        _uiState.update { it.copy(passphraseInput = "", errorMessage = null) }
        setEffect(HwConnectEffect.NavigateToPassphrase)
    }

    fun onPassphraseChange(value: String) = _uiState.update { it.copy(passphraseInput = value) }

    /** Leaves the passphrase step without keeping what was typed. */
    fun onPassphraseBack() = _uiState.update { it.copy(passphraseInput = "") }

    /**
     * Opens the hidden wallet the entered passphrase unlocks and watches it as its own identity.
     * The passphrase is dropped from state as soon as the device answers: it lives in the Trezor
     * session, never in Bitkit.
     */
    fun onPassphraseSubmit() {
        val state = _uiState.value
        val deviceId = state.pairedDeviceId ?: return
        if (state.passphraseInput.isEmpty() || connectJob?.isActive == true) return

        connectJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingPassphrase = true, errorMessage = null) }
            hwWalletRepo.connectWithPassphrase(deviceId = deviceId, passphrase = state.passphraseInput)
                .onSuccess { onPassphraseWalletAdded(it) }
                .onFailure { onPassphraseFailed(it) }
            connectJob = null
        }
    }

    private suspend fun persistLabel(walletId: String, label: String) {
        hwWalletRepo.setDeviceLabel(walletId, label)
            .onFailure {
                Logger.error("Failed to label hardware wallet '$walletId'", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.common__error),
                    description = context.getString(R.string.hardware__rename_error),
                )
            }
    }

    private fun onPassphraseWalletAdded(walletId: String) {
        // Prefill from the new identity right away: the wallet list may have settled while it was
        // being persisted, and waiting for another emission would leave the label field empty.
        val wallet = hwWalletRepo.wallets.value.firstOrNull { it.id == walletId }
        val name = wallet?.name ?: _uiState.value.deviceName
        // Fall back to the device name until the new wallet shows up, and let that emission
        // refine the prefill; once it is resolved the field is the user's to edit.
        labelInitialized = wallet != null
        _uiState.update {
            it.copy(
                isSubmittingPassphrase = false,
                passphraseInput = "",
                pairedWalletId = walletId,
                deviceName = name,
                balanceSats = wallet?.balanceSats ?: 0uL,
                labelInput = name,
            )
        }
        setEffect(HwConnectEffect.NavigateToPassphrasePaired)
    }

    private suspend fun onPassphraseFailed(error: Throwable) {
        _uiState.update { it.copy(isSubmittingPassphrase = false, passphraseInput = "") }
        val description = when (error) {
            is HwPassphraseAlreadyAddedError -> context.getString(R.string.hardware__passphrase_duplicate)
            else if error.isTrezorDeviceBusy() -> TrezorErrorPresenter.userMessage(context, error)
            else -> context.getString(R.string.hardware__passphrase_error)
        }
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.common__error),
            description = description,
        )
    }

    fun onFinishClick() {
        val state = _uiState.value
        if (state.pairedDeviceId == null) {
            setEffect(HwConnectEffect.Dismiss)
            return
        }
        // The wallet list can still be catching up with the identity that was just paired, so fall
        // back to the one the session opened rather than dropping the name the user typed.
        val walletId = state.pairedWalletId ?: hwWalletRepo.deviceState.value.connectedWalletId()
        val label = state.labelInput
        viewModelScope.launch {
            if (walletId != null) {
                persistLabel(walletId, label)
            } else {
                Logger.warn("Finished pairing '${state.pairedDeviceId}' before its identity resolved", context = TAG)
            }
            // The device is paired either way, so finish the flow instead of dropping out of it.
            setEffect(HwConnectEffect.Finish)
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
                // Unpaired devices come first; a device that is already paired is only offered so
                // its passphrase wallets can be added, since discovery skips known devices.
                val device = hwWalletRepo.deviceState.value.nearbyDevices.firstOrNull()
                    ?: scanResult.getOrNull().orEmpty().firstOrNull { hwWalletRepo.hasKnownDevice(it.id) }
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
        // The device may hold several identities, so take the one this session opened rather than
        // any wallet sharing its transport id, and show the name it was already saved under.
        val walletId = hwWalletRepo.deviceState.value.connectedWalletId()
        val wallet = walletId?.let { id -> hwWalletRepo.wallets.value.firstOrNull { it.id == id } }
        val name = wallet?.name ?: resolveHwWalletName(label = features.label, model = features.model)
        labelInitialized = wallet != null
        _uiState.update {
            it.copy(
                isConnecting = false,
                pairedDeviceId = deviceId,
                pairedWalletId = walletId,
                deviceName = name,
                balanceSats = wallet?.balanceSats ?: it.balanceSats,
                labelInput = name,
                errorMessage = null,
            )
        }
        setEffect(HwConnectEffect.NavigateToPaired)
    }

    private fun observePairingCode() {
        viewModelScope.launch {
            hwWalletRepo.pairingCodeRequestId.collect { requestId ->
                if (requestId != null) setEffect(HwConnectEffect.NavigateToPairCode(requestId))
            }
        }
    }

    private fun observeConnectedWallet() {
        viewModelScope.launch {
            hwWalletRepo.wallets.collect { wallets ->
                val state = _uiState.value
                val deviceId = state.pairedDeviceId ?: return@collect
                // A device can hold several passphrase wallets, so sharing a transport id proves
                // nothing about which one is being paired.
                val pairedWalletId = state.pairedWalletId
                val wallet = if (pairedWalletId != null) {
                    // The store publishes a newly watched identity asynchronously: wait for it
                    // rather than falling back to another wallet and reporting its name, balance
                    // and label as this one's.
                    wallets.firstOrNull { it.id == pairedWalletId } ?: return@collect
                } else {
                    wallets.firstOrNull { deviceId in it.deviceIds && it.isConnected }
                        ?: wallets.firstOrNull { deviceId in it.deviceIds }
                        ?: return@collect
                }
                _uiState.update {
                    it.copy(
                        pairedWalletId = wallet.id,
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
    /** Identity paired on [pairedDeviceId]; resolved once its watch-only wallet is known. */
    val pairedWalletId: String? = null,
    /** Held only until the device answers; the passphrase is never persisted or logged. */
    val passphraseInput: String = "",
    val isSubmittingPassphrase: Boolean = false,
    val deviceName: String = "",
    val deviceModel: String = "",
    val balanceSats: ULong = 0uL,
    val labelInput: String = "",
    val errorMessage: String? = null,
)

sealed interface HwConnectEffect {
    data object NavigateToSearching : HwConnectEffect
    data class NavigateToFound(val deviceId: String, val deviceModel: String) : HwConnectEffect
    data class NavigateToPairCode(val requestId: Long) : HwConnectEffect
    data object NavigateToPaired : HwConnectEffect
    data object NavigateToPassphrase : HwConnectEffect
    data object NavigateToPassphrasePaired : HwConnectEffect
    data object Dismiss : HwConnectEffect
    data object Finish : HwConnectEffect
}
