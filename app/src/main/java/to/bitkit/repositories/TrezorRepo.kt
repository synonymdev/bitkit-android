package to.bitkit.repositories

import android.content.Context
import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeParams
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TransactionHistoryResult
import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorCoinType
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorSignedMessageResponse
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.TrezorTransportType
import com.synonym.bitkitcore.WalletParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import to.bitkit.data.TrezorStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.models.toCoreNetwork
import to.bitkit.services.TrezorDebugLog
import to.bitkit.services.TrezorService
import to.bitkit.services.TrezorTransport
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

@Suppress("TooManyFunctions")
@Singleton
class TrezorRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trezorService: TrezorService,
    private val trezorTransport: TrezorTransport,
    private val trezorStore: TrezorStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "TrezorRepo"
        private const val DEFAULT_ADDRESS_PATH = "m/84'/0'/0'/0/0"
        private const val DEFAULT_ACCOUNT_PATH = "m/84'/0'/0'"
    }

    private val _state = MutableStateFlow(TrezorState())
    val state = _state.asStateFlow()

    /**
     * Flow indicating when a pairing code needs to be entered.
     * UI should show a dialog when this emits true.
     */
    val needsPairingCode = trezorTransport.needsPairingCode

    /**
     * Submit the pairing code entered by the user.
     */
    fun submitPairingCode(code: String) {
        trezorTransport.submitPairingCode(code)
    }

    /**
     * Cancel pairing code entry.
     */
    fun cancelPairingCode() {
        trezorTransport.cancelPairingCode()
    }

    suspend fun initialize(walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val credentialPath = "${Env.bitkitCoreStoragePath(walletIndex)}/trezor-credentials.json"
            Logger.debug("Initializing Trezor with credential path: '$credentialPath'", context = TAG)
            trezorService.initialize(credentialPath)
            val known = loadKnownDevices()
            _state.update { it.copy(isInitialized = true, knownDevices = known, error = null) }
        }.onFailure { e ->
            Logger.error("Trezor init failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun scan(): Result<List<TrezorDeviceInfo>> = withContext(ioDispatcher) {
        runCatching {
            _state.update { it.copy(isScanning = true, error = null) }
            val devices = trezorService.scan()
            val knownIds = _state.value.knownDevices.map { it.id }.toSet()
            val nearby = devices.filter { it.id !in knownIds }
            _state.update { it.copy(isScanning = false, nearbyDevices = nearby) }
            devices
        }.onFailure { e ->
            Logger.error("Trezor scan failed", e, context = TAG)
            _state.update { it.copy(isScanning = false, error = e.message) }
        }
    }

    suspend fun listDevices(): Result<List<TrezorDeviceInfo>> = withContext(ioDispatcher) {
        runCatching {
            val devices = trezorService.listDevices()
            val knownIds = _state.value.knownDevices.map { it.id }.toSet()
            val nearby = devices.filter { it.id !in knownIds }
            _state.update { it.copy(nearbyDevices = nearby) }
            devices
        }.onFailure { e ->
            Logger.error("Trezor listDevices failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun connect(deviceId: String): Result<TrezorFeatures> = withContext(ioDispatcher) {
        runCatching {
            _state.update { it.copy(isConnecting = true, error = null) }
            TrezorDebugLog.log("CONNECT", "connect() called for deviceId=$deviceId")
            val features = connectWithThpRetry(deviceId)
            TrezorDebugLog.log("CONNECT", "connect() succeeded: label=${features.label}, model=${features.model}")
            val deviceInfo = _state.value.nearbyDevices.find { it.id == deviceId }
                ?: _state.value.knownDevices.find { it.id == deviceId }?.let { known ->
                    TrezorDeviceInfo(
                        id = known.id,
                        transportType = when (known.transportType) {
                            "bluetooth" -> TrezorTransportType.BLUETOOTH
                            else -> TrezorTransportType.USB
                        },
                        name = known.name,
                        path = known.path,
                        label = known.label,
                        model = known.model,
                        isBootloader = false,
                    )
                }
            if (deviceInfo != null) {
                addOrUpdateKnownDevice(deviceInfo, features)
            }
            _state.update {
                it.copy(
                    isConnecting = false,
                    connectedDevice = features,
                    connectedDeviceId = deviceId,
                    nearbyDevices = it.nearbyDevices.filter { d -> d.id != deviceId },
                )
            }
            features
        }.onFailure { e ->
            Logger.error("Trezor connect failed", e, context = TAG)
            _state.update { it.copy(isConnecting = false, error = e.message) }
        }
    }

    suspend fun getAddress(
        path: String = DEFAULT_ADDRESS_PATH,
        showOnTrezor: Boolean = false,
        scriptType: TrezorScriptType? = TrezorScriptType.SPEND_WITNESS,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<TrezorAddressResponse> = withContext(ioDispatcher) {
        runCatching {
            ensureConnected()
            val response = trezorService.getAddress(
                path = path,
                coin = coin,
                showOnTrezor = showOnTrezor,
                scriptType = scriptType,
            )
            _state.update { it.copy(lastAddress = response, error = null) }
            response
        }.onFailure { e ->
            Logger.error("Trezor getAddress failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun getPublicKey(
        path: String = DEFAULT_ACCOUNT_PATH,
        showOnTrezor: Boolean = false,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<TrezorPublicKeyResponse> = withContext(ioDispatcher) {
        runCatching {
            ensureConnected()
            val response = trezorService.getPublicKey(
                path = path,
                coin = coin,
                showOnTrezor = showOnTrezor,
            )
            _state.update { it.copy(lastPublicKey = response, error = null) }
            response
        }.onFailure { e ->
            Logger.error("Trezor getPublicKey failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun getTransactionHistory(
        extendedKey: String,
        network: BitkitCoreNetwork = Env.network.toCoreNetwork(),
        scriptType: AccountType? = null,
    ): Result<TransactionHistoryResult> = withContext(ioDispatcher) {
        runCatching {
            trezorService.getTransactionHistory(
                extendedKey = extendedKey,
                electrumUrl = electrumUrlForNetwork(network),
                network = network,
                scriptType = scriptType,
            )
        }.onFailure {
            Logger.error("Failed to get Trezor transaction history", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    suspend fun getAccountInfo(
        extendedKey: String,
        network: BitkitCoreNetwork = Env.network.toCoreNetwork(),
        scriptType: AccountType? = null,
    ): Result<AccountInfoResult> = withContext(ioDispatcher) {
        runCatching {
            trezorService.getAccountInfo(
                extendedKey = extendedKey,
                electrumUrl = electrumUrlForNetwork(network),
                network = network,
                scriptType = scriptType,
            )
        }.onFailure { e ->
            Logger.error("Trezor getAccountInfo failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun getAddressInfo(
        address: String,
        network: BitkitCoreNetwork = Env.network.toCoreNetwork(),
    ): Result<SingleAddressInfoResult> = withContext(ioDispatcher) {
        runCatching {
            trezorService.getAddressInfo(
                address = address,
                electrumUrl = electrumUrlForNetwork(network),
                network = network,
            )
        }.onFailure { e ->
            Logger.error("Trezor getAddressInfo failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    @Suppress("LongParameterList")
    suspend fun composeTransaction(
        extendedKey: String,
        outputs: List<ComposeOutput>,
        feeRates: List<Float>,
        network: BitkitCoreNetwork,
        accountType: AccountType?,
        coinSelection: CoinSelection,
    ): Result<List<ComposeResult>> = withContext(ioDispatcher) {
        runCatching {
            val fingerprint = trezorService.getDeviceFingerprint()
            val params = ComposeParams(
                wallet = WalletParams(
                    extendedKey = extendedKey,
                    electrumUrl = electrumUrlForNetwork(network),
                    fingerprint = fingerprint,
                    network = network,
                    accountType = accountType,
                ),
                outputs = outputs,
                feeRates = feeRates,
                coinSelection = coinSelection,
            )
            trezorService.composeTransaction(params)
        }.onFailure {
            Logger.error("Trezor composeTransaction failed", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    suspend fun signTxFromPsbt(
        psbtBase64: String,
        network: TrezorCoinType?,
    ): Result<TrezorSignedTx> = withContext(ioDispatcher) {
        runCatching {
            ensureConnected()
            val response = trezorService.signTxFromPsbt(psbtBase64, network)
            _state.update { it.copy(error = null) }
            response
        }.onFailure {
            Logger.error("Trezor signTxFromPsbt failed", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    suspend fun broadcastRawTx(
        serializedTx: String,
        network: BitkitCoreNetwork,
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            trezorService.broadcastRawTx(
                serializedTx = serializedTx,
                electrumUrl = electrumUrlForNetwork(network),
            )
        }.onFailure {
            Logger.error("Trezor broadcastRawTx failed", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            TrezorDebugLog.log("DISCONNECT", "disconnect() called, connectedDeviceId=${_state.value.connectedDeviceId}")
            runCatching { trezorService.disconnect() }
            _state.update {
                it.copy(connectedDevice = null, connectedDeviceId = null, lastAddress = null, lastPublicKey = null)
            }
            TrezorDebugLog.log("DISCONNECT", "disconnect() complete (credentials NOT cleared)")
        }.onFailure { e ->
            TrezorDebugLog.log("DISCONNECT", "FAILED: ${e.message}")
            Logger.error("Trezor disconnect failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun signMessage(
        path: String = DEFAULT_ADDRESS_PATH,
        message: String,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<TrezorSignedMessageResponse> = withContext(ioDispatcher) {
        runCatching {
            ensureConnected()
            val response = trezorService.signMessage(
                path = path,
                message = message,
                coin = coin,
            )
            _state.update { it.copy(error = null) }
            response
        }.onFailure { e ->
            Logger.error("Trezor signMessage failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun verifyMessage(
        address: String,
        signature: String,
        message: String,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<Boolean> = withContext(ioDispatcher) {
        runCatching {
            ensureConnected()
            val result = trezorService.verifyMessage(
                address = address,
                signature = signature,
                message = message,
                coin = coin,
            )
            _state.update { it.copy(error = null) }
            result
        }.onFailure { e ->
            Logger.error("Trezor verifyMessage failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    fun hasKnownDevices(): Boolean = _state.value.knownDevices.isNotEmpty()

    suspend fun autoReconnect(walletIndex: Int = 0): Result<TrezorFeatures> = withContext(ioDispatcher) {
        val knownDevices = _state.value.knownDevices.ifEmpty { loadKnownDevices() }
        if (knownDevices.isEmpty()) {
            return@withContext Result.failure(AppError("No known devices"))
        }

        _state.update { it.copy(isAutoReconnecting = true, error = null) }
        runCatching {
            if (!_state.value.isInitialized) {
                initialize(walletIndex).getOrThrow()
            }
            if (trezorService.isConnected()) {
                _state.value.connectedDevice ?: error("Connected but no features")
            } else {
                val scannedDevices = scan().getOrThrow()
                val knownIds = knownDevices.map { it.id }.toSet()
                val usbDevice = scannedDevices.find {
                    it.transportType == TrezorTransportType.USB && it.id in knownIds
                }
                val idMatch = knownDevices.firstNotNullOfOrNull { known ->
                    scannedDevices.find { it.id == known.id }
                }
                val match = idMatch ?: usbDevice ?: error("No known device found nearby")
                connect(match.id).getOrThrow()
            }
        }.onSuccess {
            _state.update { it.copy(isAutoReconnecting = false) }
        }.onFailure { e ->
            Logger.error("Auto-reconnect failed", e, context = TAG)
            _state.update { it.copy(isAutoReconnecting = false, error = e.message) }
        }
    }

    suspend fun connectKnownDevice(deviceId: String): Result<TrezorFeatures> = withContext(ioDispatcher) {
        if (_state.value.isConnecting) {
            return@withContext Result.failure(AppError("Connection already in progress"))
        }
        runCatching {
            _state.update { it.copy(isConnecting = true, error = null) }
            TrezorDebugLog.log("RECONNECT", "=== connectKnownDevice START ===")
            TrezorDebugLog.log("RECONNECT", "deviceId=$deviceId")
            TrezorDebugLog.log("RECONNECT", "isInitialized=${_state.value.isInitialized}")
            if (!_state.value.isInitialized) {
                TrezorDebugLog.log("RECONNECT", "Initializing...")
                initialize().getOrThrow()
                TrezorDebugLog.log("RECONNECT", "Initialized OK")
            }
            TrezorDebugLog.log("RECONNECT", "Scanning for devices...")
            val scannedDevices = trezorService.scan()
            TrezorDebugLog.log(
                "RECONNECT",
                "Scan found ${scannedDevices.size} devices: ${scannedDevices.map { it.id }}",
            )
            val exactMatch = scannedDevices.find { it.id == deviceId }
            val knownIds = _state.value.knownDevices.map { it.id }.toSet()
            val usbDevice = scannedDevices.find {
                it.transportType == TrezorTransportType.USB && it.id in knownIds
            }
            val device = if (exactMatch?.transportType == TrezorTransportType.BLUETOOTH && usbDevice != null) {
                TrezorDebugLog.log("RECONNECT", "Preferring USB over BLE")
                usbDevice
            } else {
                exactMatch ?: error("Device not found nearby — is it powered on?")
            }
            TrezorDebugLog.log("RECONNECT", "Found matching device: id=${device.id}, name=${device.name}")
            TrezorDebugLog.log("RECONNECT", "Calling connectWithThpRetry...")
            val features = connectWithThpRetry(device.id)
            TrezorDebugLog.log("RECONNECT", "Connected! label=${features.label}, model=${features.model}")
            addOrUpdateKnownDevice(device, features)
            _state.update {
                it.copy(isConnecting = false, connectedDevice = features, connectedDeviceId = device.id)
            }
            TrezorDebugLog.log("RECONNECT", "=== connectKnownDevice SUCCESS ===")
            features
        }.onFailure { e ->
            TrezorDebugLog.log("RECONNECT", "FAILED: ${e.message}")
            Logger.error("Connect known device failed", e, context = TAG)
            _state.update { it.copy(isConnecting = false, error = e.message) }
        }
    }

    suspend fun forgetDevice(deviceId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            TrezorDebugLog.log("FORGET", "forgetDevice called for: $deviceId")
            if (_state.value.connectedDeviceId == deviceId) {
                runCatching { trezorService.disconnect() }
                _state.update { it.copy(connectedDevice = null, connectedDeviceId = null) }
            }
            TrezorDebugLog.log("FORGET", "Clearing credentials...")
            trezorTransport.clearDeviceCredential(deviceId)
            runCatching { trezorService.clearCredentials(deviceId) }
            val updated = _state.value.knownDevices.filter { it.id != deviceId }
            saveKnownDevices(updated)
            _state.update { it.copy(knownDevices = updated) }
            TrezorDebugLog.log("FORGET", "Device forgotten successfully")
            Logger.info("Forgot device: '$deviceId'", context = TAG)
        }.onFailure { e ->
            TrezorDebugLog.log("FORGET", "FAILED: ${e.message}")
            Logger.error("Forget device failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun observeExternalDisconnects(scope: CoroutineScope) {
        trezorTransport.externalDisconnect.onEach { path ->
            val currentId = _state.value.connectedDeviceId ?: return@onEach
            val knownDevice = _state.value.knownDevices.find { it.path == path }
            if (knownDevice?.id == currentId || path.contains(currentId)) {
                Logger.warn("External disconnect detected for '$currentId'", context = TAG)
                _state.update {
                    it.copy(connectedDevice = null, connectedDeviceId = null, error = "Device disconnected")
                }
            }
        }.launchIn(scope)
    }

    private suspend fun addOrUpdateKnownDevice(deviceInfo: TrezorDeviceInfo, features: TrezorFeatures) {
        val existing = _state.value.knownDevices
        val known = KnownDevice(
            id = deviceInfo.id,
            name = deviceInfo.name,
            path = deviceInfo.path,
            transportType = when (deviceInfo.transportType) {
                TrezorTransportType.BLUETOOTH -> "bluetooth"
                TrezorTransportType.USB -> "usb"
            },
            label = features.label ?: deviceInfo.label,
            model = features.model ?: deviceInfo.model,
            lastConnectedAt = System.currentTimeMillis(),
        )
        val updated = existing.filter { it.id != known.id } + known
        saveKnownDevices(updated)
        _state.update { it.copy(knownDevices = updated) }
    }

    private suspend fun loadKnownDevices(): List<KnownDevice> = runCatching {
        trezorStore.loadKnownDevices()
    }.onFailure {
        Logger.error("Failed to load known devices", it, context = TAG)
    }.getOrDefault(emptyList())

    private suspend fun saveKnownDevices(devices: List<KnownDevice>) {
        runCatching {
            trezorStore.saveKnownDevices(devices)
        }.onFailure { Logger.error("Failed to save known devices", it, context = TAG) }
    }

    private fun electrumUrlForNetwork(network: BitkitCoreNetwork): String = Env.electrumUrlForNetwork(network)

    private suspend fun ensureConnected() {
        if (trezorService.isConnected()) return
        val deviceId = _state.value.connectedDeviceId
            ?: _state.value.knownDevices.firstOrNull()?.id
            ?: error("No device to reconnect")
        if (!_state.value.isInitialized) {
            initialize().getOrThrow()
        }
        val devices = trezorService.scan()
        val device = devices.find { it.id == deviceId }
            ?: error("Device not found during reconnect")
        val features = connectWithThpRetry(device.id)
        _state.update { it.copy(connectedDevice = features, connectedDeviceId = deviceId) }
    }

    suspend fun clearCredentials(deviceId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            trezorService.clearCredentials(deviceId)
            _state.update { it.copy(error = null) }
        }.onFailure { e ->
            Logger.error("Trezor clearCredentials failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    private suspend fun connectWithThpRetry(deviceId: String): TrezorFeatures {
        TrezorDebugLog.log("THPRetry", "First connect attempt for: $deviceId")
        logCredentialFileState(deviceId, "BEFORE 1st attempt")
        return runCatching {
            trezorService.connect(deviceId)
        }.onSuccess {
            logCredentialFileState(deviceId, "AFTER 1st attempt (success)")
            TrezorDebugLog.log("THPRetry", "First attempt succeeded")
        }.getOrElse { e ->
            logCredentialFileState(deviceId, "AFTER 1st attempt (failed)")
            TrezorDebugLog.log("THPRetry", "First attempt failed: ${e.message}")
            if (!isRetryableError(e)) {
                TrezorDebugLog.log("THPRetry", "Error not retryable, throwing")
                throw e
            }
            TrezorDebugLog.log("THPRetry", "Error is retryable, attempting second connect...")
            Logger.warn("Connection failed for $deviceId, retrying", e, context = TAG)
            logCredentialFileState(deviceId, "BEFORE 2nd attempt")
            val result = trezorService.connect(deviceId)
            logCredentialFileState(deviceId, "AFTER 2nd attempt (success)")
            TrezorDebugLog.log("THPRetry", "Second attempt succeeded")
            result
        }
    }

    private fun logCredentialFileState(deviceId: String, label: String) {
        val sanitizedId = deviceId.replace(":", "_").replace("/", "_")
        val credDir = File(context.filesDir, "trezor-thp-credentials")
        val credFile = File(credDir, "$sanitizedId.json")
        val exists = credFile.exists()
        val size = if (exists) credFile.length() else 0
        TrezorDebugLog.log("CRED", "$label: file=$sanitizedId.json exists=$exists size=$size")
    }

    private fun isRetryableError(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return "thp" in msg || "session" in msg || "timeout" in msg || "disconnect" in msg
    }
}

@Stable
data class TrezorState(
    val isInitialized: Boolean = false,
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isAutoReconnecting: Boolean = false,
    val knownDevices: List<KnownDevice> = emptyList(),
    val nearbyDevices: List<TrezorDeviceInfo> = emptyList(),
    val connectedDevice: TrezorFeatures? = null,
    val connectedDeviceId: String? = null,
    val lastAddress: TrezorAddressResponse? = null,
    val lastPublicKey: TrezorPublicKeyResponse? = null,
    val error: String? = null,
)

@Serializable
data class KnownDevice(
    val id: String,
    val name: String?,
    val path: String,
    val transportType: String,
    val label: String?,
    val model: String?,
    val lastConnectedAt: Long,
)
