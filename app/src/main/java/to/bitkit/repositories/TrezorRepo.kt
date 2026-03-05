package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorCoinType
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorPrecomposeParams
import com.synonym.bitkitcore.TrezorPrecomposedInput
import com.synonym.bitkitcore.TrezorPrecomposedOutput
import com.synonym.bitkitcore.TrezorPrecomposedResult
import com.synonym.bitkitcore.TrezorPrevTx
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorSignTxParams
import com.synonym.bitkitcore.TrezorSignedMessageResponse
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.TrezorTxInput
import com.synonym.bitkitcore.TrezorTxOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.env.Env
import to.bitkit.models.toTrezorCoinType
import to.bitkit.services.TrezorDebugLog
import to.bitkit.services.TrezorService
import to.bitkit.services.TrezorTransport
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

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

@Suppress("TooManyFunctions")
@Singleton
class TrezorRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trezorService: TrezorService,
    private val trezorTransport: TrezorTransport,
) {
    companion object {
        private const val TAG = "TrezorRepo"
        private const val KEY_KNOWN_DEVICES = "known_devices"
    }

    private val prefs by lazy {
        context.getSharedPreferences("trezor_device", Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun initialize(walletIndex: Int = 0): Result<Unit> = runCatching {
        val credentialPath = "${Env.bitkitCoreStoragePath(walletIndex)}/trezor-credentials.json"
        Logger.debug("Initializing Trezor with credential path: $credentialPath", context = TAG)
        trezorService.initialize(credentialPath)
        val known = loadKnownDevices()
        _state.update { it.copy(isInitialized = true, knownDevices = known, error = null) }
    }.onFailure { e ->
        Logger.error("Trezor init failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    suspend fun scan(): Result<List<TrezorDeviceInfo>> = runCatching {
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

    suspend fun listDevices(): Result<List<TrezorDeviceInfo>> = runCatching {
        val devices = trezorService.listDevices()
        val knownIds = _state.value.knownDevices.map { it.id }.toSet()
        val nearby = devices.filter { it.id !in knownIds }
        _state.update { it.copy(nearbyDevices = nearby) }
        devices
    }.onFailure { e ->
        Logger.error("Trezor listDevices failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    suspend fun connect(deviceId: String): Result<TrezorFeatures> = runCatching {
        _state.update { it.copy(isConnecting = true, error = null) }
        TrezorDebugLog.log("CONNECT", "connect() called for deviceId=$deviceId")
        val features = connectWithThpRetry(deviceId)
        TrezorDebugLog.log("CONNECT", "connect() succeeded: label=${features.label}, model=${features.model}")
        val deviceInfo = _state.value.nearbyDevices.find { it.id == deviceId }
            ?: _state.value.knownDevices.find { it.id == deviceId }?.let { known ->
                TrezorDeviceInfo(
                    id = known.id,
                    transportType = when (known.transportType) {
                        "bluetooth" -> com.synonym.bitkitcore.TrezorTransportType.BLUETOOTH
                        else -> com.synonym.bitkitcore.TrezorTransportType.USB
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

    suspend fun getAddress(
        path: String = "m/84'/0'/0'/0/0",
        showOnTrezor: Boolean = false,
        scriptType: TrezorScriptType? = TrezorScriptType.SPEND_WITNESS,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<TrezorAddressResponse> = runCatching {
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

    suspend fun getPublicKey(
        path: String = "m/84'/0'/0'",
        showOnTrezor: Boolean = false,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<TrezorPublicKeyResponse> = runCatching {
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

    suspend fun getAccountInfo(
        extendedKey: String,
        network: TrezorCoinType = Env.network.toTrezorCoinType(),
    ): Result<AccountInfoResult> = runCatching {
        trezorService.getAccountInfo(
            extendedKey = extendedKey,
            electrumUrl = electrumUrlForNetwork(network),
            network = keyFormatNetwork(network),
        )
    }.onFailure { e ->
        Logger.error("Trezor getAccountInfo failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    suspend fun getAddressInfo(
        address: String,
        network: TrezorCoinType = Env.network.toTrezorCoinType(),
    ): Result<SingleAddressInfoResult> = runCatching {
        trezorService.getAddressInfo(
            address = address,
            electrumUrl = electrumUrlForNetwork(network),
            network = keyFormatNetwork(network),
        )
    }.onFailure { e ->
        Logger.error("Trezor getAddressInfo failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    suspend fun precomposeTransaction(
        params: TrezorPrecomposeParams,
    ): Result<List<TrezorPrecomposedResult>> = runCatching {
        trezorService.precomposeTransaction(params = params)
    }.onFailure {
        Logger.error("Trezor precomposeTransaction failed", it, context = TAG)
        _state.update { s -> s.copy(error = it.message) }
    }

    suspend fun convertToSignParams(
        inputs: List<TrezorPrecomposedInput>,
        outputs: List<TrezorPrecomposedOutput>,
        coin: TrezorCoinType?,
    ): Result<TrezorSignTxParams> = runCatching {
        trezorService.precomposedToSignParams(
            inputs = inputs,
            outputs = outputs,
            coin = coin,
        )
    }.onFailure {
        Logger.error("Trezor convertToSignParams failed", it, context = TAG)
        _state.update { s -> s.copy(error = it.message) }
    }

    suspend fun fetchPrevTxs(
        txids: List<String>,
        network: TrezorCoinType,
    ): Result<List<TrezorPrevTx>> = runCatching {
        trezorService.fetchPrevTxs(
            txids = txids,
            electrumUrl = electrumUrlForNetwork(network),
        )
    }.onFailure {
        Logger.error("Trezor fetchPrevTxs failed", it, context = TAG)
        _state.update { s -> s.copy(error = it.message) }
    }

    suspend fun broadcastRawTx(
        serializedTx: String,
        network: TrezorCoinType,
    ): Result<String> = runCatching {
        trezorService.broadcastRawTx(
            serializedTx = serializedTx,
            electrumUrl = electrumUrlForNetwork(network),
        )
    }.onFailure {
        Logger.error("Trezor broadcastRawTx failed", it, context = TAG)
        _state.update { s -> s.copy(error = it.message) }
    }

    suspend fun signTxWithParams(params: TrezorSignTxParams): Result<TrezorSignedTx> = runCatching {
        ensureConnected()
        val response = trezorService.signTxWithParams(params)
        _state.update { it.copy(error = null) }
        response
    }.onFailure {
        Logger.error("Trezor signTxWithParams failed", it, context = TAG)
        _state.update { s -> s.copy(error = it.message) }
    }

    fun coinStringForNetwork(network: TrezorCoinType): String = when (network) {
        TrezorCoinType.BITCOIN -> "Bitcoin"
        TrezorCoinType.TESTNET -> "Testnet"
        TrezorCoinType.REGTEST -> "Regtest"
        TrezorCoinType.SIGNET -> "Testnet"
    }

    suspend fun disconnect(): Result<Unit> = runCatching {
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

    suspend fun signMessage(
        path: String = "m/84'/0'/0'/0/0",
        message: String,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<TrezorSignedMessageResponse> = runCatching {
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

    suspend fun verifyMessage(
        address: String,
        signature: String,
        message: String,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
    ): Result<Boolean> = runCatching {
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

    fun hasKnownDevices(): Boolean = _state.value.knownDevices.isNotEmpty()

    suspend fun autoReconnect(walletIndex: Int = 0): Result<TrezorFeatures> {
        val knownDevices = _state.value.knownDevices.ifEmpty { loadKnownDevices() }
        if (knownDevices.isEmpty()) {
            return Result.failure(IllegalStateException("No known devices"))
        }

        _state.update { it.copy(isAutoReconnecting = true, error = null) }
        return runCatching {
            if (!_state.value.isInitialized) {
                initialize(walletIndex).getOrThrow()
            }
            if (trezorService.isConnected()) {
                _state.value.connectedDevice ?: error("Connected but no features")
            } else {
                val scannedDevices = scan().getOrThrow()
                val match = knownDevices.firstNotNullOfOrNull { known ->
                    scannedDevices.find { it.id == known.id }
                } ?: error("No known device found nearby")
                connect(match.id).getOrThrow()
            }
        }.onSuccess {
            _state.update { it.copy(isAutoReconnecting = false) }
        }.onFailure { e ->
            Logger.error("Auto-reconnect failed", e, context = TAG)
            _state.update { it.copy(isAutoReconnecting = false, error = e.message) }
        }
    }

    suspend fun connectKnownDevice(deviceId: String): Result<TrezorFeatures> = runCatching {
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
        TrezorDebugLog.log("RECONNECT", "Scan found ${scannedDevices.size} devices: ${scannedDevices.map { it.id }}")
        val device = scannedDevices.find { it.id == deviceId }
            ?: error("Device not found nearby — is it powered on?")
        TrezorDebugLog.log("RECONNECT", "Found matching device: id=${device.id}, name=${device.name}")
        TrezorDebugLog.log("RECONNECT", "Calling connectWithThpRetry...")
        val features = connectWithThpRetry(device.id)
        TrezorDebugLog.log("RECONNECT", "Connected! label=${features.label}, model=${features.model}")
        addOrUpdateKnownDevice(device, features)
        _state.update {
            it.copy(isConnecting = false, connectedDevice = features, connectedDeviceId = deviceId)
        }
        TrezorDebugLog.log("RECONNECT", "=== connectKnownDevice SUCCESS ===")
        features
    }.onFailure { e ->
        TrezorDebugLog.log("RECONNECT", "FAILED: ${e.message}")
        Logger.error("Connect known device failed", e, context = TAG)
        _state.update { it.copy(isConnecting = false, error = e.message) }
    }

    suspend fun forgetDevice(deviceId: String): Result<Unit> = runCatching {
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
        Logger.info("Forgot device: $deviceId", context = TAG)
    }.onFailure { e ->
        TrezorDebugLog.log("FORGET", "FAILED: ${e.message}")
        Logger.error("Forget device failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun observeExternalDisconnects(scope: CoroutineScope) {
        trezorTransport.externalDisconnect.onEach { path ->
            val currentId = _state.value.connectedDeviceId ?: return@onEach
            val knownDevice = _state.value.knownDevices.find { it.path == path }
            if (knownDevice?.id == currentId || path.contains(currentId)) {
                Logger.warn("External disconnect detected for $currentId", context = TAG)
                _state.update {
                    it.copy(connectedDevice = null, connectedDeviceId = null, error = "Device disconnected")
                }
            }
        }.launchIn(scope)
    }

    private fun addOrUpdateKnownDevice(deviceInfo: TrezorDeviceInfo, features: TrezorFeatures) {
        val existing = _state.value.knownDevices
        val known = KnownDevice(
            id = deviceInfo.id,
            name = deviceInfo.name,
            path = deviceInfo.path,
            transportType = when (deviceInfo.transportType) {
                com.synonym.bitkitcore.TrezorTransportType.BLUETOOTH -> "bluetooth"
                com.synonym.bitkitcore.TrezorTransportType.USB -> "usb"
            },
            label = features.label ?: deviceInfo.label,
            model = features.model ?: deviceInfo.model,
            lastConnectedAt = System.currentTimeMillis(),
        )
        val updated = existing.filter { it.id != known.id } + known
        saveKnownDevices(updated)
        _state.update { it.copy(knownDevices = updated) }
    }

    private fun loadKnownDevices(): List<KnownDevice> = runCatching {
        val str = prefs.getString(KEY_KNOWN_DEVICES, null) ?: return emptyList()
        json.decodeFromString<List<KnownDevice>>(str)
    }.onFailure {
        Logger.error("Failed to load known devices", it, context = TAG)
    }.getOrDefault(emptyList())

    private fun saveKnownDevices(devices: List<KnownDevice>) {
        runCatching {
            prefs.edit().putString(KEY_KNOWN_DEVICES, json.encodeToString(devices)).commit()
        }.onFailure { Logger.error("Failed to save known devices", it, context = TAG) }
    }

    private fun keyFormatNetwork(network: TrezorCoinType): TrezorCoinType = when (network) {
        TrezorCoinType.REGTEST -> TrezorCoinType.TESTNET
        else -> network
    }

    private fun electrumUrlForNetwork(network: TrezorCoinType): String = when (network) {
        TrezorCoinType.BITCOIN -> "ssl://bitkit.to:9999"
        TrezorCoinType.TESTNET -> "ssl://electrum.blockstream.info:60002"
        TrezorCoinType.REGTEST -> "ssl://electrs.bitkit.stag0.blocktank.to:9999"
        TrezorCoinType.SIGNET -> "ssl://electrum.blockstream.info:60002"
    }

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

    suspend fun signTx(
        inputs: List<TrezorTxInput>,
        outputs: List<TrezorTxOutput>,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
        lockTime: UInt? = null,
        version: UInt? = null,
    ): Result<TrezorSignedTx> = runCatching {
        ensureConnected()
        val response = trezorService.signTx(
            inputs = inputs,
            outputs = outputs,
            coin = coin,
            lockTime = lockTime,
            version = version,
        )
        _state.update { it.copy(error = null) }
        response
    }.onFailure { e ->
        Logger.error("Trezor signTx failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    suspend fun clearCredentials(deviceId: String): Result<Unit> = runCatching {
        trezorService.clearCredentials(deviceId)
        _state.update { it.copy(error = null) }
    }.onFailure { e ->
        Logger.error("Trezor clearCredentials failed", e, context = TAG)
        _state.update { it.copy(error = e.message) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun connectWithThpRetry(deviceId: String): TrezorFeatures {
        TrezorDebugLog.log("THPRetry", "First connect attempt for: $deviceId")
        logCredentialFileState(deviceId, "BEFORE 1st attempt")
        return try {
            val result = trezorService.connect(deviceId)
            logCredentialFileState(deviceId, "AFTER 1st attempt (success)")
            TrezorDebugLog.log("THPRetry", "First attempt succeeded")
            result
        } catch (e: Exception) {
            logCredentialFileState(deviceId, "AFTER 1st attempt (failed)")
            TrezorDebugLog.log("THPRetry", "First attempt failed: ${e.message}")
            if (!isRetryableError(e)) {
                TrezorDebugLog.log("THPRetry", "Error not retryable, throwing")
                throw e
            }
            TrezorDebugLog.log("THPRetry", "Error is retryable, attempting second connect...")
            Logger.warn("Connection failed for $deviceId, retrying: ${e.message}", context = TAG)
            logCredentialFileState(deviceId, "BEFORE 2nd attempt")
            val result = trezorService.connect(deviceId)
            logCredentialFileState(deviceId, "AFTER 2nd attempt (success)")
            TrezorDebugLog.log("THPRetry", "Second attempt succeeded")
            result
        }
    }

    private fun logCredentialFileState(deviceId: String, label: String) {
        val sanitizedId = deviceId.replace(":", "_").replace("/", "_")
        val credDir = java.io.File(context.filesDir, "trezor-thp-credentials")
        val credFile = java.io.File(credDir, "$sanitizedId.json")
        val exists = credFile.exists()
        val size = if (exists) credFile.length() else 0
        TrezorDebugLog.log("CRED", "$label: file=$sanitizedId.json exists=$exists size=$size")
    }

    private fun isRetryableError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return "thp" in msg || "session" in msg || "timeout" in msg || "disconnect" in msg
    }
}

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
