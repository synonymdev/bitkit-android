package to.bitkit.repositories

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeParams
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.EventListener
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
import com.synonym.bitkitcore.WalletSelection
import com.synonym.bitkitcore.WatcherEvent
import com.synonym.bitkitcore.WatcherParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import to.bitkit.data.HwWalletStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMs
import to.bitkit.models.ALL_ADDRESS_TYPES
import to.bitkit.models.TransportType
import to.bitkit.models.toAccountDerivationPath
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toSettingsString
import to.bitkit.models.toTrezorCoinType
import to.bitkit.services.TrezorDebugLog
import to.bitkit.services.TrezorService
import to.bitkit.services.TrezorTransport
import to.bitkit.services.TrezorUiHandler
import to.bitkit.services.TrezorWalletMode
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

@OptIn(ExperimentalTime::class)
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
@Singleton
class TrezorRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trezorService: TrezorService,
    private val trezorTransport: TrezorTransport,
    private val trezorUiHandler: TrezorUiHandler,
    private val hwWalletStore: HwWalletStore,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "TrezorRepo"
        private const val WATCHER_TAG = "WATCHER"
        private const val DEFAULT_ADDRESS_PATH = "m/84'/0'/0'/0/0"
        private const val DEFAULT_ACCOUNT_PATH = "m/84'/0'/0'"
        private const val WALLET_MODE_RECONNECT_DELAY_MS = 1_000L
        private const val TRANSPORT_RESTORED_MAX_ATTEMPTS = 4
        private val TRANSPORT_RESTORED_RECONNECT_DELAY = 2.seconds
    }

    private val _state = MutableStateFlow(TrezorState())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var transportReconnectJob: Job? = null

    init {
        observeExternalDisconnects()
        observeTransportRestored()
    }

    private val _watcherEvents = MutableSharedFlow<Pair<String, WatcherEvent>>(extraBufferCapacity = 64)
    val watcherEvents: SharedFlow<Pair<String, WatcherEvent>> = _watcherEvents.asSharedFlow()

    private val eventBridge: EventListener = object : EventListener {
        override fun onEvent(watcherId: String, event: WatcherEvent) {
            TrezorDebugLog.log(WATCHER_TAG, "[$watcherId] ${event::class.simpleName}")
            _watcherEvents.tryEmit(watcherId to event)
        }
    }

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

    val needsPinEntry = trezorUiHandler.needsPinEntry

    fun submitPin(pin: String) {
        trezorUiHandler.submitPin(pin)
    }

    fun cancelPin() {
        trezorUiHandler.cancelPin()
    }

    val walletMode = trezorUiHandler.walletMode

    /**
     * Reset to the standard wallet and clear any selected passphrase, without
     * reconnecting. Call this when the user explicitly picks a device from a
     * list ([connect]/[connectKnownDevice]) so a passphrase or on-device
     * selection left over from a previously connected device isn't silently
     * applied to the newly selected one.
     *
     * Silent reconnects ([autoReconnect]/[ensureConnected]) deliberately skip
     * this, so a dropped link reopens the same hidden wallet the user was using.
     */
    fun resetWalletSelection() {
        trezorUiHandler.setWalletMode(TrezorWalletMode.STANDARD)
    }

    /**
     * Switch between the standard wallet and a passphrase (hidden) wallet.
     *
     * The Trezor caches the passphrase for the whole session, so switching
     * requires a fresh session: this sets the desired mode, then disconnects
     * and reconnects. The new mode takes effect on the next wallet operation.
     */
    suspend fun setWalletMode(
        mode: TrezorWalletMode,
        passphrase: String = "",
    ): Result<TrezorFeatures> = withContext(ioDispatcher) {
        runCatching {
            val deviceId = _state.value.connectedDeviceId
                ?: throw AppError("No connected Trezor")
            TrezorDebugLog.log("WALLET_MODE", "Switching to $mode, resetting session for $deviceId")
            // Reset the session via disconnect/reconnect. disconnect() resets the
            // UI handler's wallet mode to standard, so set the desired mode AFTER
            // the disconnect and right before reconnecting.
            runCatching { disconnect() }
            // Reconnect by id WITHOUT a scan: scan() clears the discovered-device
            // cache and a scan right after a disconnect usually finds nothing,
            // whereas the cached handle (and direct address resolution) still work.
            delay(WALLET_MODE_RECONNECT_DELAY_MS)
            // Record the selection on the handler: THP reads it via
            // currentSelection() to bind the passphrase at session creation,
            // while non-THP devices re-request it mid-operation and are answered
            // from the same value. connect() then derives the wallet from it.
            trezorUiHandler.setWalletMode(mode, passphrase)
            connect(deviceId).getOrThrow()
        }
    }

    suspend fun initialize(walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val credentialPath = "${Env.bitkitCoreStoragePath(walletIndex)}/trezor-credentials.json"
            Logger.debug("Initializing Trezor with credential path: '$credentialPath'", context = TAG)
            trezorService.initialize(credentialPath)
            val known = loadKnownDevices()
            _state.update { it.copy(isInitialized = true, knownDevices = known.toImmutableList(), error = null) }
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
            _state.update { it.copy(isScanning = false, nearbyDevices = nearby.toImmutableList()) }
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
            _state.update { it.copy(nearbyDevices = nearby.toImmutableList()) }
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
            val features = connectWithThpRetry(deviceId, trezorUiHandler.currentSelection())
            TrezorDebugLog.log("CONNECT", "connect() succeeded: label=${features.label}, model=${features.model}")
            val deviceInfo = _state.value.nearbyDevices.find { it.id == deviceId }
                ?: _state.value.knownDevices.find { it.id == deviceId }?.let { known ->
                    TrezorDeviceInfo(
                        id = known.id,
                        transportType = known.transportType.toCoreTransportType(),
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
                    connected = ConnectedTrezorDevice(id = deviceId, features = features),
                    nearbyDevices = it.nearbyDevices.filter { d -> d.id != deviceId }.toImmutableList(),
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
        TrezorDebugLog.log("DISCONNECT", "disconnect() called, connectedDeviceId=${_state.value.connectedDeviceId}")
        val result = runCatching { trezorService.disconnect() }
        // Mirror the core: trezorService.disconnect() resets the session
        // passphrase to the standard wallet, so reset the UI handler's wallet
        // mode too. This keeps the THP path, the legacy PassphraseRequest
        // callback, and the displayed mode consistent on the next (re)connect —
        // a hidden wallet must be re-selected explicitly after an explicit
        // disconnect. (A transient external disconnect does not call this and so
        // retains the selection, matching the core's behaviour.)
        trezorUiHandler.setWalletMode(TrezorWalletMode.STANDARD)
        _state.update {
            it.copy(connected = null, lastAddress = null, lastPublicKey = null)
        }
        result.onSuccess {
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

    suspend fun autoReconnect(
        walletIndex: Int = 0,
        preferredTransport: TransportType? = null,
    ): Result<TrezorFeatures> = withContext(ioDispatcher) {
        if (isConnectInProgress()) {
            // A live handshake looks like a stale session (transport connected,
            // features pending), so resetting here would drop the session the
            // user is entering their PIN or pairing code into.
            return@withContext Result.failure(AppError("Connect already in progress"))
        }
        val knownDevices = _state.value.knownDevices.ifEmpty { loadKnownDevices() }
        if (knownDevices.isEmpty()) {
            return@withContext Result.failure(AppError("No known devices"))
        }

        _state.update { it.copy(isAutoReconnecting = true, error = null) }
        runCatching {
            if (!_state.value.isInitialized) {
                initialize(walletIndex).getOrThrow()
            }
            val cachedFeatures = if (trezorService.isConnected()) _state.value.connectedDevice else null
            if (cachedFeatures != null) {
                cachedFeatures
            } else {
                if (trezorService.isConnected()) {
                    // The transport dropped underneath the session (e.g. bluetooth was
                    // toggled), so reset it before a fresh scan and connect.
                    runCatching { trezorService.disconnect() }
                }
                val scannedDevices = scan().getOrThrow()
                val knownIds = knownDevices.map { it.id }.toSet()
                val usbDevice = scannedDevices.find {
                    it.transportType == TrezorTransportType.USB && it.id in knownIds
                }
                val idMatch = knownDevices.firstNotNullOfOrNull { known ->
                    scannedDevices.find { it.id == known.id }
                }
                // Prefer the transport that just came back, so e.g. a USB replug does
                // not reconnect over BLE when the same device is known on both.
                val preferredMatch = preferredTransport?.let { preferred ->
                    scannedDevices.find {
                        it.id in knownIds && it.transportType.toTransportType() == preferred
                    }
                }
                val match = preferredMatch ?: idMatch ?: usbDevice
                    ?: throw AppError("No known device found nearby")
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
            // Honor the transport the user selected — connect to exactly the
            // entry they tapped instead of overriding Bluetooth with USB.
            val device = scannedDevices.find { it.id == deviceId }
                ?: throw AppError("Device not found nearby — is it powered on?")
            TrezorDebugLog.log("RECONNECT", "Found matching device: id=${device.id}, name=${device.name}")
            TrezorDebugLog.log("RECONNECT", "Calling connectWithThpRetry...")
            val features = connectWithThpRetry(device.id, trezorUiHandler.currentSelection())
            TrezorDebugLog.log("RECONNECT", "Connected! label=${features.label}, model=${features.model}")
            addOrUpdateKnownDevice(device, features)
            _state.update {
                it.copy(isConnecting = false, connected = ConnectedTrezorDevice(id = device.id, features = features))
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
            val disconnectResult = if (_state.value.connectedDeviceId == deviceId) {
                runCatching { trezorService.disconnect() }.also {
                    // Clear any cached host passphrase so it can't be reused
                    // against a different device on a later connect.
                    trezorUiHandler.setWalletMode(TrezorWalletMode.STANDARD)
                    _state.update { it.copy(connected = null) }
                }
            } else {
                Result.success(Unit)
            }
            TrezorDebugLog.log("FORGET", "Clearing credentials...")
            trezorTransport.clearDeviceCredential(deviceId)
            val clearCredentialsResult = runCatching { trezorService.clearCredentials(deviceId) }
            val updated = _state.value.knownDevices.filter { it.id != deviceId }
            saveKnownDevices(updated)
            _state.update { it.copy(knownDevices = updated.toImmutableList()) }
            disconnectResult.getOrThrow()
            clearCredentialsResult.getOrThrow()
            TrezorDebugLog.log("FORGET", "Device forgotten successfully")
            Logger.info("Forgot device: '$deviceId'", context = TAG)
        }.onFailure { e ->
            TrezorDebugLog.log("FORGET", "FAILED: ${e.message}")
            Logger.error("Forget device failed", e, context = TAG)
            _state.update { it.copy(error = e.message) }
        }
    }

    suspend fun startWatcher(
        watcherId: String,
        extendedKey: String,
        network: BitkitCoreNetwork,
        gapLimit: UInt = 20u,
        accountType: AccountType? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val params = WatcherParams(
                watcherId = watcherId,
                extendedKey = extendedKey,
                electrumUrl = electrumUrlForNetwork(network),
                network = network,
                accountType = accountType,
                gapLimit = gapLimit,
            )
            trezorService.startWatcher(params, eventBridge)
            TrezorDebugLog.log(WATCHER_TAG, "Started watcher '$watcherId' for '${extendedKey.take(12)}...'")
            Logger.info("Started watcher '$watcherId'", context = TAG)
        }.onFailure {
            Logger.error("Start watcher failed", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    suspend fun stopWatcher(watcherId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            trezorService.stopWatcher(watcherId)
            TrezorDebugLog.log(WATCHER_TAG, "Stopped watcher '$watcherId'")
            Logger.info("Stopped watcher '$watcherId'", context = TAG)
        }.onFailure {
            Logger.error("Stop watcher failed", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    fun stopWatcherOnCleared(watcherId: String) {
        scope.launch { stopWatcher(watcherId) }
    }

    suspend fun stopAllWatchers(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            trezorService.stopAllWatchers()
            TrezorDebugLog.log(WATCHER_TAG, "Stopped all watchers")
        }.onFailure {
            Logger.error("Stop all watchers failed", it, context = TAG)
            _state.update { s -> s.copy(error = it.message) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun observeExternalDisconnects() {
        trezorTransport.externalDisconnect.onEach { path ->
            val currentId = _state.value.connectedDeviceId ?: return@onEach
            val knownDevice = _state.value.knownDevices.find { it.path == path }
            if (knownDevice?.id == currentId || path.contains(currentId)) {
                Logger.warn("External disconnect detected for '$currentId'", context = TAG)
                _state.update {
                    it.copy(connected = null, error = "Device disconnected")
                }
            }
        }.launchIn(scope)
    }

    /**
     * Silently reconnects to a known device when its transport comes back: stored THP
     * credentials make the connect prompt-free, so the link indicator recovers on its
     * own after Bluetooth is re-enabled or the device is plugged back in.
     */
    private fun observeTransportRestored() {
        trezorTransport.transportRestored.onEach {
            launchTransportReconnect(it)
        }.launchIn(scope)
    }

    /**
     * Triggers the silent reconnect for transport events delivered through UI intents,
     * e.g. the USB attach intent the OS app picker routes to the activity (attach is
     * not broadcast to receivers, unlike detach).
     */
    fun onTransportRestored(transportType: TransportType) = launchTransportReconnect(transportType)

    /**
     * Serializes reconnect triggers into one in-flight retry loop. A Trezor
     * re-enumerates USB during its unlock flow, so a single replug delivers several
     * attach intents; letting each spawn its own loop staggers connect attempts for
     * many seconds, and every attempt restarts the device's PIN entry.
     */
    private fun launchTransportReconnect(transportType: TransportType) {
        if (transportReconnectJob?.isActive == true) return
        transportReconnectJob = scope.launch { retryAutoReconnect(transportType) }
    }

    /**
     * A device is often not discoverable right after its transport returns (a BLE
     * Trezor takes a few seconds to advertise again), so retry the silent reconnect
     * with growing delays instead of giving up on the first empty scan.
     */
    private suspend fun retryAutoReconnect(transportType: TransportType) {
        repeat(TRANSPORT_RESTORED_MAX_ATTEMPTS) { attempt ->
            if (_state.value.connected != null || isConnectInProgress()) return
            delay(TRANSPORT_RESTORED_RECONNECT_DELAY * (attempt + 1))
            // A connect may have started while this attempt was waiting.
            if (_state.value.connected != null || isConnectInProgress()) return
            Logger.info("Attempting auto-reconnect after transport restored, attempt '${attempt + 1}'", context = TAG)
            if (autoReconnect(preferredTransport = transportType).isSuccess) return
        }
    }

    private fun isConnectInProgress(): Boolean = run {
        val current = _state.value
        current.isConnecting ||
            current.isAutoReconnecting ||
            needsPinEntry.value ||
            needsPairingCode.value
    }

    private suspend fun addOrUpdateKnownDevice(deviceInfo: TrezorDeviceInfo, features: TrezorFeatures) {
        val existing = _state.value.knownDevices
        val previous = existing.find { it.id == deviceInfo.id }
        val known = KnownDevice(
            id = deviceInfo.id,
            name = deviceInfo.name,
            path = deviceInfo.path,
            transportType = deviceInfo.transportType.toTransportType(),
            label = features.label ?: deviceInfo.label,
            model = features.model ?: deviceInfo.model,
            lastConnectedAt = clock.nowMs(),
            xpubs = fetchAccountXpubs().ifEmpty { previous?.xpubs ?: emptyMap() },
        )
        val updated = existing.filter { it.id != known.id } + known
        saveKnownDevices(updated)
        _state.update { it.copy(knownDevices = updated.toImmutableList()) }
    }

    /**
     * Reads account-level extended public keys for every supported address type so a
     * watch-only balance can be tracked later without the device present. Failures are
     * swallowed per type: a missing xpub just means that address type is not watched.
     */
    private suspend fun fetchAccountXpubs(): Map<String, String> {
        val coin = Env.network.toTrezorCoinType()
        return ALL_ADDRESS_TYPES.mapNotNull { addressType ->
            runCatching {
                val xpub = trezorService.getPublicKey(
                    path = addressType.toAccountDerivationPath(network = Env.network),
                    coin = coin,
                    showOnTrezor = false,
                ).xpub
                addressType.toSettingsString() to xpub
            }.onFailure {
                Logger.warn("Could not read xpub for '${addressType.toSettingsString()}'", it, context = TAG)
            }.getOrNull()
        }.toMap()
    }

    private suspend fun loadKnownDevices(): List<KnownDevice> = runCatching {
        hwWalletStore.loadKnownDevices()
    }.onFailure {
        Logger.error("Failed to load known devices", it, context = TAG)
    }.getOrDefault(emptyList())

    private suspend fun saveKnownDevices(devices: List<KnownDevice>) {
        runCatching {
            hwWalletStore.saveKnownDevices(devices)
        }.onFailure { Logger.error("Failed to save known devices", it, context = TAG) }
    }

    private fun electrumUrlForNetwork(network: BitkitCoreNetwork): String = Env.electrumUrlForNetwork(network)

    private suspend fun ensureConnected() {
        if (trezorService.isConnected()) return
        val deviceId = _state.value.connectedDeviceId
            ?: _state.value.knownDevices.firstOrNull()?.id
            ?: throw AppError("No device to reconnect")
        if (!_state.value.isInitialized) {
            initialize().getOrThrow()
        }
        val devices = trezorService.scan()
        val device = devices.find { it.id == deviceId }
            ?: throw AppError("Device not found during reconnect")
        val features = connectWithThpRetry(device.id, trezorUiHandler.currentSelection())
        _state.update { it.copy(connected = ConnectedTrezorDevice(id = deviceId, features = features)) }
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

    private suspend fun connectWithThpRetry(
        deviceId: String,
        selection: WalletSelection,
    ): TrezorFeatures {
        TrezorDebugLog.log("THPRetry", "First connect attempt for: $deviceId")
        logCredentialFileState(deviceId, "BEFORE 1st attempt")
        return runCatching {
            trezorService.connect(deviceId, selection)
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
            val result = trezorService.connect(deviceId, selection)
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
        // A rejected session (wrong passphrase, or the user cancelling on-device
        // passphrase entry) is a definitive failure, not a transient THP/transport
        // hiccup. Retrying it just re-prompts the device and risks wedging the
        // connection, so don't treat ThpCreateNewSession rejections as retryable.
        if ("rejected" in msg) return false
        return "thp" in msg || "session" in msg || "timeout" in msg || "disconnect" in msg
    }
}

@Stable
data class TrezorState(
    val isInitialized: Boolean = false,
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isAutoReconnecting: Boolean = false,
    val knownDevices: ImmutableList<KnownDevice> = persistentListOf(),
    val nearbyDevices: ImmutableList<TrezorDeviceInfo> = persistentListOf(),
    val connected: ConnectedTrezorDevice? = null,
    val lastAddress: TrezorAddressResponse? = null,
    val lastPublicKey: TrezorPublicKeyResponse? = null,
    val error: String? = null,
) {
    val connectedDevice: TrezorFeatures?
        get() = connected?.features

    val connectedDeviceId: String?
        get() = connected?.id
}

@Stable
data class ConnectedTrezorDevice(
    val id: String,
    val features: TrezorFeatures,
)

@Serializable
@Immutable
data class KnownDevice(
    val id: String,
    val name: String?,
    val path: String,
    val transportType: TransportType,
    val label: String?,
    val model: String?,
    val lastConnectedAt: Long,
    /** Account-level extended public keys per address type (key = [AddressType.toSettingsString]). */
    val xpubs: Map<String, String> = emptyMap(),
)

private fun TrezorTransportType.toTransportType(): TransportType = when (this) {
    TrezorTransportType.BLUETOOTH -> TransportType.BLUETOOTH
    TrezorTransportType.USB -> TransportType.USB
}

private fun TransportType.toCoreTransportType(): TrezorTransportType = when (this) {
    TransportType.BLUETOOTH -> TrezorTransportType.BLUETOOTH
    TransportType.USB -> TrezorTransportType.USB
}
