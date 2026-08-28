package to.bitkit.repositories

import android.content.Context
import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.AddressType
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
import com.synonym.bitkitcore.TrezorException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.async.appScope
import to.bitkit.data.HwWalletStore
import to.bitkit.data.PendingNameUpdate
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.isTrezorDeviceBusy
import to.bitkit.ext.isTrezorFirmwareError
import to.bitkit.ext.isTrezorUserCancellation
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toTransportType
import to.bitkit.models.ALL_ADDRESS_TYPES
import to.bitkit.models.HwWalletId
import to.bitkit.models.KnownDevice
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
import to.bitkit.utils.TrezorErrorPresenter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
    private val settingsStore: SettingsStore,
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
        private val CONNECT_ATTEMPT_POLL_INTERVAL = 250.milliseconds
        private val CONNECT_ATTEMPT_MAX_WAIT = 28.seconds
        private const val MAX_XPUB_FETCH_ATTEMPTS = 3
        private val XPUB_FETCH_RETRY_DELAY = 300.milliseconds
        private val TRANSIENT_FAILURE_MARKERS = listOf(
            "TransportError",
            "ConnectionError",
            "DeviceDisconnected",
            "Timeout",
            "IoError",
            "SessionError",
        )
    }

    private val _state = MutableStateFlow(TrezorState())
    val state = _state.asStateFlow()

    private val scope = appScope(ioDispatcher, TAG)
    private var isSetup = CompletableDeferred<Unit>()
    private val setupMutex = Mutex()

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

    /** Identity of the active pairing-code request; changes for every transport callback. */
    val pairingCodeRequestId = trezorTransport.pairingCodeRequestId

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

    suspend fun resetState() = withContext(ioDispatcher) {
        resetSetup()
        transportReconnectJob?.cancel()
        transportReconnectJob = null

        val knownDevices = (_state.value.knownDevices + hwWalletStore.loadKnownDevices())
            .distinctBy { it.id }

        if (_state.value.connected != null) {
            runSuspendCatching { disconnect().getOrThrow() }
        }

        knownDevices.forEach { device ->
            runCatching { trezorTransport.clearDeviceCredential(device.id) }
                .onFailure { Logger.warn("Failed to clear transport credential for '${device.id}'", it, context = TAG) }
            runCatching { trezorService.clearCredentials(device.id) }
                .onFailure { Logger.warn("Failed to clear Trezor credentials for '${device.id}'", it, context = TAG) }
        }

        trezorUiHandler.setWalletMode(TrezorWalletMode.STANDARD)
        hwWalletStore.reset()
        _state.update {
            it.copy(
                isScanning = false,
                isConnecting = false,
                isAutoReconnecting = false,
                knownDevices = persistentListOf(),
                nearbyDevices = persistentListOf(),
                connected = null,
                lastAddress = null,
                lastPublicKey = null,
                error = null,
            )
        }
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
        runSuspendCatching {
            val deviceId = _state.value.connectedDeviceId()
                ?: throw AppError("No connected Trezor")
            connectWithWalletMode(deviceId, mode, passphrase).getOrThrow()
        }
    }

    /**
     * Opens [deviceId] with an explicit wallet selection, whether or not a session is live. A
     * passphrase is bound when the session is created, so an existing one is torn down first; with
     * none, the device is reconnected from its stored entry. Reopening a hidden wallet after the
     * app was restarted, or retrying once a wrong passphrase closed the session, both start here.
     */
    suspend fun connectWithWalletMode(
        deviceId: String,
        mode: TrezorWalletMode,
        passphrase: String = "",
    ): Result<TrezorFeatures> = withContext(ioDispatcher) {
        runSuspendCatching {
            val hadSession = _state.value.connectedDeviceId() != null
            TrezorDebugLog.log("WALLET_MODE", "Opening $mode session for $deviceId, hadSession=$hadSession")
            if (hadSession) {
                runSuspendCatching { disconnect() }
                delay(WALLET_MODE_RECONNECT_DELAY_MS)
            }
            // Record the selection on the handler: THP reads it via currentSelection() to bind the
            // passphrase at session creation, while non-THP devices re-request it mid-operation and
            // are answered from the same value. Set it last, since disconnect() resets it.
            trezorUiHandler.setWalletMode(mode, passphrase)
            if (hadSession) {
                // Reconnect by id WITHOUT a scan: scan() clears the discovered-device cache and a
                // scan right after a disconnect usually finds nothing, whereas the cached handle
                // (and direct address resolution) still work.
                connect(deviceId).getOrThrow()
            } else {
                // Nothing cached to reconnect to, so take the known-device path with its scan and
                // bluetooth retries.
                connectKnownDevice(deviceId, forceSession = true).getOrThrow()
            }
        }
    }

    suspend fun initialize(walletIndex: Int = 0): Result<Unit> = withContext(ioDispatcher) {
        setupMutex.withLock {
            if (isSetup.isCancelled) {
                isSetup = CompletableDeferred()
            }
            if (isSetup.isCompleted) {
                isSetup.await()
                return@withLock Result.success(Unit)
            }

            val setup = isSetup
            runSuspendCatching {
                val credentialPath = "${Env.bitkitCoreStoragePath(walletIndex)}/trezor-credentials.json"
                Logger.debug("Initializing Trezor with credential path: '$credentialPath'", context = TAG)
                trezorService.initialize(credentialPath)
                val known = loadKnownDevices()
                _state.update { it.copy(knownDevices = known.toImmutableList(), error = null) }
                setup.complete(Unit)
                Unit
            }.onFailure { e ->
                setup.completeExceptionally(e)
                if (isSetup === setup) {
                    isSetup = CompletableDeferred()
                }
                Logger.error("Trezor init failed", e, context = TAG)
                _state.update { it.copy(error = trezorErrorMessage(e)) }
            }
        }
    }

    suspend fun scan(includeBluetooth: Boolean = true): Result<List<TrezorDeviceInfo>> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            _state.update { it.copy(isScanning = true, error = null) }
            val devices = trezorService.scan(includeBluetooth = includeBluetooth)
            val knownIds = _state.value.knownDevices.map { it.id }.toSet()
            val nearby = devices.filter { it.id !in knownIds }
            _state.update { it.copy(isScanning = false, nearbyDevices = nearby.toImmutableList()) }
            devices
        }.onFailure { e ->
            Logger.error("Trezor scan failed", e, context = TAG)
            _state.update { it.copy(isScanning = false, error = trezorErrorMessage(e)) }
        }
    }

    suspend fun listDevices(): Result<List<TrezorDeviceInfo>> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            val devices = trezorService.listDevices()
            val knownIds = _state.value.knownDevices.map { it.id }.toSet()
            val nearby = devices.filter { it.id !in knownIds }
            _state.update { it.copy(nearbyDevices = nearby.toImmutableList()) }
            devices
        }.onFailure { e ->
            Logger.error("Trezor listDevices failed", e, context = TAG)
            _state.update { it.copy(error = trezorErrorMessage(e)) }
        }
    }

    suspend fun connect(
        deviceId: String,
        requestUsbPermission: Boolean = true,
    ): Result<TrezorFeatures> = withContext(ioDispatcher) {
        var startedConnecting = false
        try {
            runSuspendCatching {
                awaitSetup()
                startedConnecting = true
                _state.update { it.copy(isConnecting = true, error = null) }
                TrezorDebugLog.log("CONNECT", "connect() called for deviceId=$deviceId")
                val features = connectWithThpRetry(
                    deviceId = deviceId,
                    selection = trezorUiHandler.currentSelection(),
                    requestUsbPermission = requestUsbPermission,
                )
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
                val known = deviceInfo?.let { addOrUpdateKnownDevice(it, features) }
                _state.update {
                    it.copy(
                        connected = ConnectedTrezorDevice(
                            id = deviceId,
                            features = features,
                            walletId = known?.walletId?.takeIf { id -> id.isNotBlank() },
                        ),
                        nearbyDevices = it.nearbyDevices.filter { d -> d.id != deviceId }.toImmutableList(),
                    )
                }
                features
            }.onFailure { e ->
                Logger.error("Trezor connect failed", e, context = TAG)
                _state.update { it.copy(error = trezorErrorMessage(e)) }
            }
        } finally {
            if (startedConnecting) {
                _state.update { it.copy(isConnecting = false) }
            }
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
            _state.update { it.copy(error = trezorErrorMessage(e)) }
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
            _state.update { it.copy(error = trezorErrorMessage(e)) }
        }
    }

    suspend fun getTransactionHistory(
        extendedKey: String,
        network: BitkitCoreNetwork = Env.network.toCoreNetwork(),
        scriptType: AccountType? = null,
    ): Result<TransactionHistoryResult> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            trezorService.getTransactionHistory(
                extendedKey = extendedKey,
                electrumUrl = currentElectrumUrl(),
                network = network,
                scriptType = scriptType,
            )
        }.onFailure {
            Logger.error("Failed to get Trezor transaction history", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    suspend fun getAccountInfo(
        extendedKey: String,
        network: BitkitCoreNetwork = Env.network.toCoreNetwork(),
        scriptType: AccountType? = null,
    ): Result<AccountInfoResult> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            trezorService.getAccountInfo(
                extendedKey = extendedKey,
                electrumUrl = currentElectrumUrl(),
                network = network,
                scriptType = scriptType,
            )
        }.onFailure { e ->
            Logger.error("Trezor getAccountInfo failed", e, context = TAG)
            _state.update { it.copy(error = trezorErrorMessage(e)) }
        }
    }

    suspend fun getAddressInfo(
        address: String,
        network: BitkitCoreNetwork = Env.network.toCoreNetwork(),
    ): Result<SingleAddressInfoResult> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            trezorService.getAddressInfo(
                address = address,
                electrumUrl = currentElectrumUrl(),
                network = network,
            )
        }.onFailure { e ->
            Logger.error("Trezor getAddressInfo failed", e, context = TAG)
            _state.update { it.copy(error = trezorErrorMessage(e)) }
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
            awaitSetup()
            ensureConnected()
            val fingerprint = trezorService.getDeviceFingerprint()
            composeTransaction(
                extendedKey = extendedKey,
                outputs = outputs,
                feeRates = feeRates,
                network = network,
                accountType = accountType,
                coinSelection = coinSelection,
                fingerprint = fingerprint,
            )
        }.onFailure {
            Logger.error("Trezor composeTransaction failed", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    /** Composes from a public account key without opening a hardware-device session. */
    @Suppress("LongParameterList")
    suspend fun composeTransactionOffline(
        extendedKey: String,
        outputs: List<ComposeOutput>,
        feeRates: List<Float>,
        network: BitkitCoreNetwork,
        accountType: AccountType?,
        coinSelection: CoinSelection,
    ): Result<List<ComposeResult>> = withContext(ioDispatcher) {
        runSuspendCatching {
            awaitSetup()
            composeTransaction(
                extendedKey = extendedKey,
                outputs = outputs,
                feeRates = feeRates,
                network = network,
                accountType = accountType,
                coinSelection = coinSelection,
                fingerprint = null,
            )
        }.onFailure {
            Logger.error("Trezor offline composeTransaction failed", it, context = TAG)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun composeTransaction(
        extendedKey: String,
        outputs: List<ComposeOutput>,
        feeRates: List<Float>,
        network: BitkitCoreNetwork,
        accountType: AccountType?,
        coinSelection: CoinSelection,
        fingerprint: String?,
    ): List<ComposeResult> = trezorService.composeTransaction(
        ComposeParams(
            wallet = WalletParams(
                extendedKey = extendedKey,
                electrumUrl = currentElectrumUrl(),
                fingerprint = fingerprint,
                network = network,
                accountType = accountType,
            ),
            outputs = outputs,
            feeRates = feeRates,
            coinSelection = coinSelection,
        )
    )

    suspend fun signTxFromPsbt(
        psbtBase64: String,
        network: TrezorCoinType?,
    ): Result<TrezorSignedTx> = withContext(ioDispatcher) {
        runSuspendCatching {
            ensureConnected()
            val response = trezorService.signTxFromPsbt(psbtBase64, network)
            _state.update { it.copy(error = null) }
            response
        }.onFailure {
            Logger.error("Trezor signTxFromPsbt failed", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    suspend fun broadcastRawTx(
        serializedTx: String,
    ): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            awaitSetup()
            trezorService.broadcastRawTx(
                serializedTx = serializedTx,
                electrumUrl = currentElectrumUrl(),
            )
        }.onFailure {
            Logger.error("Trezor broadcastRawTx failed", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    suspend fun disconnect(): Result<Unit> = withContext(ioDispatcher) {
        val deviceId = _state.value.connectedDeviceId()
        TrezorDebugLog.log("DISCONNECT", "disconnect() called, connectedDeviceId=$deviceId")
        val result = runCatching {
            trezorService.disconnect()
            deviceId?.let { disconnectTransportDevice(it) }
            Unit
        }
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
            _state.update { it.copy(error = trezorErrorMessage(e)) }
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
            _state.update { it.copy(error = trezorErrorMessage(e)) }
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
            _state.update { it.copy(error = trezorErrorMessage(e)) }
        }
    }

    fun hasKnownDevices(): Boolean = _state.value.knownDevices.isNotEmpty()

    suspend fun hasKnownDevice(deviceId: String): Boolean = withContext(ioDispatcher) {
        _state.value.knownDevices.any { it.matches(deviceId) } ||
            loadKnownDevices().any { it.matches(deviceId) }
    }

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
        try {
            runSuspendCatching {
                awaitSetup(walletIndex)
                val cachedFeatures = if (trezorService.isConnected()) _state.value.connectedDevice() else null
                if (cachedFeatures != null) {
                    cachedFeatures
                } else {
                    if (trezorService.isConnected()) {
                        // The transport dropped underneath the session (e.g. bluetooth was
                        // toggled), so reset it before a fresh scan and connect.
                        runCatching { trezorService.disconnect() }
                    }
                    val scannedDevices = scan().getOrThrow().filter { it.canAutoReconnect() }
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
                    connect(match.id, requestUsbPermission = false).getOrThrow()
                }
            }.onFailure { e ->
                Logger.error("Auto-reconnect failed", e, context = TAG)
                _state.update { it.copy(error = trezorErrorMessage(e)) }
            }
        } finally {
            _state.update { it.copy(isAutoReconnecting = false) }
        }
    }

    private fun TrezorDeviceInfo.canAutoReconnect(): Boolean {
        if (transportType.toTransportType() != TransportType.USB) return true
        if (trezorTransport.hasUsbPermission(path)) return true
        Logger.info("Skipped USB auto-reconnect without permission for '$path'", context = TAG)
        return false
    }

    suspend fun connectKnownDevice(
        deviceId: String,
        forceSession: Boolean = false,
        allowBleFallback: Boolean = true,
    ): Result<TrezorFeatures> = withContext(ioDispatcher) {
        if (isConnectInProgress()) {
            return@withContext Result.failure(AppError("Connection already in progress"))
        }
        var startedConnecting = false
        try {
            runSuspendCatching {
                startedConnecting = true
                _state.update { it.copy(isConnecting = true, error = null) }
                Logger.debug("Started known-device reconnect for '$deviceId'", context = TAG)
                Logger.debug("Awaiting setup for reconnect", context = TAG)
                awaitSetup()
                Logger.debug("Completed setup for reconnect", context = TAG)
                if (forceSession) {
                    Logger.debug("Closing stale session before reconnect for '$deviceId'", context = TAG)
                    disconnectStaleSession(deviceId)
                }
                Logger.debug("Scanning for reconnect devices", context = TAG)
                val knownDevices = (_state.value.knownDevices + loadKnownDevices()).distinctBy { it.id }
                val knownDevice = knownDevices.find { it.matches(deviceId) }
                val device = resolveKnownReconnectDevice(deviceId, knownDevice, allowBleFallback)
                Logger.debug("Found reconnect device '${device.id}'", context = TAG)
                Logger.debug("Calling THP reconnect for '${device.id}'", context = TAG)
                val features = connectWithThpRetry(device.id, trezorUiHandler.currentSelection())
                Logger.debug("Connected known device '${device.id}'", context = TAG)
                val known = addOrUpdateKnownDevice(device, features)
                _state.update {
                    it.copy(
                        connected = ConnectedTrezorDevice(
                            id = device.id,
                            features = features,
                            walletId = known.walletId.takeIf { id -> id.isNotBlank() },
                        )
                    )
                }
                Logger.info("Reconnected known device '${device.id}'", context = TAG)
                features
            }.onFailure { e ->
                Logger.error("Connect known device failed", e, context = TAG)
                _state.update { it.copy(error = trezorErrorMessage(e)) }
                if (!forceSession) {
                    disconnectStaleSession(deviceId)
                }
            }
        } finally {
            if (startedConnecting) {
                _state.update { it.copy(isConnecting = false) }
            }
        }
    }

    suspend fun ensureConnected(deviceId: String): Result<TrezorFeatures> = withContext(ioDispatcher) {
        val result = awaitConnectedOrNull(deviceId)?.let { refreshLockedFeatures(deviceId, it) } ?: run {
            if (isKnownBluetoothDevice(deviceId)) {
                reconnectKnownBluetoothDevice(deviceId)
            } else {
                connectKnownDevice(deviceId, forceSession = true)
            }
        }
        result.requireUnlocked()
    }

    private suspend fun refreshLockedFeatures(
        deviceId: String,
        features: TrezorFeatures,
    ): Result<TrezorFeatures> {
        if (features.pinProtection != true || features.unlocked != false) return Result.success(features)
        return runSuspendCatching { trezorService.refreshFeatures() }.onSuccess { refreshed ->
            _state.update { state ->
                val connected = state.connected
                    ?.takeIf { it.id == deviceId }
                    ?.copy(features = refreshed)
                    ?: ConnectedTrezorDevice(id = deviceId, features = refreshed)
                state.copy(connected = connected)
            }
        }
    }

    private fun Result<TrezorFeatures>.requireUnlocked(): Result<TrezorFeatures> = fold(
        onSuccess = {
            if (it.pinProtection == true && it.unlocked == false) {
                Result.failure(TrezorException.DeviceBusy())
            } else {
                Result.success(it)
            }
        },
        onFailure = { Result.failure(it) },
    )

    /**
     * BLE Trezors often need a few seconds to advertise again after unlock, so retry
     * with growing delays (same cadence as [retryAutoReconnect]) instead of failing on
     * the first empty scan or a premature direct-address connect.
     */
    private suspend fun reconnectKnownBluetoothDevice(deviceId: String): Result<TrezorFeatures> {
        var lastFailure: Throwable? = null
        repeat(TRANSPORT_RESTORED_MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(TRANSPORT_RESTORED_RECONNECT_DELAY * attempt)
            }
            awaitConnectedOrNull(deviceId)?.let { return Result.success(it) }
            val allowBleFallback = attempt == TRANSPORT_RESTORED_MAX_ATTEMPTS - 1
            val result = connectKnownDevice(
                deviceId = deviceId,
                forceSession = attempt == 0,
                allowBleFallback = allowBleFallback,
            )
            if (result.isSuccess) return result
            val failure = result.exceptionOrNull()
            if (failure?.isTrezorUserCancellation() == true) {
                return Result.failure(failure)
            }
            if (failure?.isTrezorDeviceBusy() == true) {
                return Result.failure(failure)
            }
            lastFailure = failure
        }
        return Result.failure(lastFailure ?: AppError("Failed to connect"))
    }

    suspend fun isKnownBluetoothDevice(deviceId: String): Boolean = withContext(ioDispatcher) {
        (_state.value.knownDevices + loadKnownDevices()).distinctBy { it.id }
            .any { it.matches(deviceId) && it.transportType == TransportType.BLUETOOTH }
    }

    fun deriveWalletId(xpubs: Map<String, String>): String? =
        deriveHardwareWalletId(xpubs)?.takeIf { it.isNotBlank() }

    private suspend fun connectedFeatures(deviceId: String): TrezorFeatures? {
        val current = _state.value.connected
        return if (current?.id == deviceId && trezorService.isConnected()) current.features else null
    }

    private suspend fun awaitConnectedOrNull(deviceId: String): TrezorFeatures? {
        connectedFeatures(deviceId)?.let { return it }
        if (isConnectInProgress()) {
            awaitInFlightConnect(deviceId)
            connectedFeatures(deviceId)?.let { return it }
        }
        return null
    }

    private suspend fun awaitInFlightConnect(deviceId: String) {
        transportReconnectJob?.takeIf { it.isActive }?.join()
        waitForConnectAttempt(deviceId)
    }

    private suspend fun waitForConnectAttempt(deviceId: String) {
        runCatching {
            withTimeout(CONNECT_ATTEMPT_MAX_WAIT) {
                while (true) {
                    if (connectedFeatures(deviceId) != null) return@withTimeout
                    if (!isConnectInProgress()) return@withTimeout
                    delay(CONNECT_ATTEMPT_POLL_INTERVAL)
                }
            }
        }.onFailure {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
        }
    }

    private suspend fun resolveKnownReconnectDevice(
        deviceId: String,
        knownDevice: KnownDevice?,
        allowBleFallback: Boolean = true,
    ): TrezorDeviceInfo = findKnownDeviceInScan(deviceId, knownDevice, allowBleFallback)

    private suspend fun findKnownDeviceInScan(
        deviceId: String,
        knownDevice: KnownDevice?,
        allowBleFallback: Boolean,
    ): TrezorDeviceInfo {
        val scannedDevices = trezorService.scan()
        Logger.debug(
            "Found '${scannedDevices.size}' reconnect devices '${scannedDevices.map { it.id }}'",
            context = TAG,
        )
        scannedDevices.find { it.id == deviceId }?.let { return it }
        if (allowBleFallback) {
            knownDevice?.takeIf { it.transportType == TransportType.BLUETOOTH }?.toDeviceInfo()?.let { return it }
        }
        throw AppError("Device not found nearby — is it powered on?")
    }

    /**
     * Forgets a paired entry. [walletKey] scopes the removal to a single passphrase identity;
     * without it every wallet watched on that physical device is forgotten. Transport and session
     * credentials are only cleared once no identity of the device remains, so removing one hidden
     * wallet does not unpair the device for the others.
     */
    suspend fun forgetDevice(
        deviceId: String,
        walletKey: String? = null,
        pendingName: PendingNameUpdate? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            TrezorDebugLog.log("FORGET", "forgetDevice called for: $deviceId")
            // The store is the source of truth here: labels are written straight to it, so a
            // cached entry taking precedence would rewrite the wallets left behind without theirs.
            val stored = loadKnownDevices()
            val storedEntries = stored.map { it.id to it.walletKey }.toSet()
            val knownDevices = stored + _state.value.knownDevices.filter { (it.id to it.walletKey) !in storedEntries }
            // Scoped to the identity, not to the transport it was reached over: removing it in one
            // write keeps repeated calls, a lagging read and a concurrent connect from leaving a
            // sibling entry of the same wallet behind.
            val isForgotten: (KnownDevice) -> Boolean = when (walletKey) {
                null -> { entry -> entry.id == deviceId }
                else -> { entry -> entry.walletKey == walletKey }
            }
            val forgotten = knownDevices.filter(isForgotten)
            val updated = knownDevices.filterNot(isForgotten)
            val keepsDevice = updated.any { it.id == deviceId }

            // Only the session of what is being forgotten may be torn down: a device can hold
            // another identity open, and that wallet is still paired and still signing.
            val connectedWalletId = _state.value.connectedWalletId()
            val sessionIsForgotten = !keepsDevice ||
                connectedWalletId == null ||
                forgotten.any { it.walletId == connectedWalletId }
            val disconnectResult = if (_state.value.connectedDeviceId() == deviceId && sessionIsForgotten) {
                try {
                    runSuspendCatching {
                        trezorService.disconnect()
                        disconnectTransportDevice(deviceId)
                    }
                } finally {
                    // Clear any cached host passphrase so it can't be reused against a different
                    // device on a later connect. In a finally so a cancelled disconnect, which now
                    // propagates instead of being swallowed, still clears it.
                    trezorUiHandler.setWalletMode(TrezorWalletMode.STANDARD)
                    _state.update { it.copy(connected = null) }
                }
            } else {
                Result.success(Unit)
            }
            val clearCredentialsResult = if (!keepsDevice) {
                TrezorDebugLog.log("FORGET", "Clearing credentials...")
                trezorTransport.clearDeviceCredential(deviceId)
                runSuspendCatching { trezorService.clearCredentials(deviceId) }
            } else {
                TrezorDebugLog.log("FORGET", "Keeping credentials, another wallet still uses $deviceId")
                Result.success(Unit)
            }
            saveKnownDevices(updated, pendingName)
            _state.update { it.copy(knownDevices = updated.toImmutableList()) }
            clearCredentialsResult.getOrThrow()
            disconnectResult.onFailure {
                TrezorDebugLog.log("FORGET", "Ignored disconnect failure: ${it.message}")
                Logger.warn("Ignored disconnect failure while forgetting device '$deviceId'", it, context = TAG)
            }
            TrezorDebugLog.log("FORGET", "Device forgotten successfully")
            Logger.info("Forgot device: '$deviceId'", context = TAG)
        }.onFailure { e ->
            TrezorDebugLog.log("FORGET", "FAILED: ${e.message}")
            Logger.error("Forget device failed", e, context = TAG)
            _state.update { it.copy(error = trezorErrorMessage(e)) }
        }
    }

    suspend fun startWatcher(
        watcherId: String,
        extendedKey: String,
        network: BitkitCoreNetwork,
        gapLimit: UInt = 20u,
        accountType: AccountType? = null,
        electrumUrl: String = electrumUrlForNetwork(network),
        walletId: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            val params = WatcherParams(
                watcherId = watcherId,
                walletId = walletId,
                extendedKey = extendedKey,
                electrumUrl = electrumUrl,
                network = network,
                accountType = accountType,
                gapLimit = gapLimit,
            )
            trezorService.startWatcher(params, eventBridge)
            TrezorDebugLog.log(WATCHER_TAG, "Started watcher '$watcherId'")
        }.onFailure {
            Logger.error("Start watcher failed", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    suspend fun stopWatcher(watcherId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            trezorService.stopWatcher(watcherId)
            TrezorDebugLog.log(WATCHER_TAG, "Stopped watcher '$watcherId'")
        }.onFailure {
            Logger.error("Stop watcher failed", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    fun stopWatcherOnCleared(watcherId: String) {
        scope.launch { stopWatcher(watcherId) }
    }

    suspend fun stopAllWatchers(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            awaitSetup()
            trezorService.stopAllWatchers()
            TrezorDebugLog.log(WATCHER_TAG, "Stopped all watchers")
        }.onFailure {
            Logger.error("Stop all watchers failed", it, context = TAG)
            _state.update { s -> s.copy(error = trezorErrorMessage(it)) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun observeExternalDisconnects() {
        trezorTransport.externalDisconnect.onEach { path ->
            val currentId = _state.value.connectedDeviceId() ?: return@onEach
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

    fun onAppForegrounded() {
        scope.launch {
            if (_state.value.connected != null || isConnectInProgress()) return@launch
            val knownDevices = _state.value.knownDevices.ifEmpty { loadKnownDevices() }
            if (knownDevices.none { it.transportType == TransportType.BLUETOOTH }) return@launch

            Logger.info("Attempting bluetooth auto-reconnect after app foregrounded", context = TAG)
            launchTransportReconnect(TransportType.BLUETOOTH)
        }
    }

    /** Pre-connects one known BLE Trezor before the transfer sign screen asks for it. */
    fun warmUpKnownDevice(deviceId: String) {
        scope.launch {
            if (connectedFeatures(deviceId) != null) return@launch
            if (isConnectInProgress()) return@launch
            if (!hasKnownDevice(deviceId)) return@launch
            if (!isKnownBluetoothDevice(deviceId)) return@launch

            Logger.info("Warming up known bluetooth device '$deviceId'", context = TAG)
            ensureConnected(deviceId).onFailure {
                Logger.debug("Warm up connect failed for '$deviceId'", context = TAG)
            }
        }
    }

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
            val result = autoReconnect(preferredTransport = transportType)
            if (result.isSuccess) return
            // A busy device needs the user to unlock it; retrying won't help and
            // would keep the connect-in-progress state blocking user actions.
            if (result.exceptionOrNull()?.isTrezorDeviceBusy() == true) return
        }
    }

    private fun isConnectInProgress(): Boolean = run {
        val current = _state.value
        current.isConnecting ||
            current.isAutoReconnecting ||
            needsPinEntry.value ||
            needsPairingCode.value
    }

    private suspend fun addOrUpdateKnownDevice(deviceInfo: TrezorDeviceInfo, features: TrezorFeatures): KnownDevice {
        val stored = hwWalletStore.loadKnownDevices()
        val storedEntries = stored.map { it.id to it.walletKey }.toSet()
        val knownDevices = stored + _state.value.knownDevices.filter { (it.id to it.walletKey) !in storedEntries }
        val fetchResult = fetchAccountXpubs()
        val selection = trezorUiHandler.currentSelection()
        // A passphrase wallet is a separate identity on the same physical device, so the transport
        // id alone no longer identifies an entry: matching by it would overwrite another identity
        // or blend two identities' xpubs into one record. Shared key material is the identity, so
        // match on it; only an entry stored before any xpub was captured has no identity to
        // conflict with and can be adopted by this connect.
        val candidates = knownDevices.filter { it.id == deviceInfo.id }
        val previous = candidates.firstOrNull {
            it.xpubs.values.intersect(fetchResult.xpubs.values.toSet()).isNotEmpty()
        } ?: candidates.singleOrNull()?.takeIf { it.xpubs.isEmpty() }
        val xpubs = previous?.xpubs.orEmpty() + fetchResult.xpubs
        val retryableGaps = fetchResult.transientFailures.filterKeys { addressType ->
            xpubs[addressType.toSettingsString()] == null
        }
        // Saving a device with retryable xpub gaps would start a watch-only wallet
        // under an id that changes once the missing type is read on a later connect,
        // so fail the connect instead, preserving the real cause (busy, disconnect, ...).
        retryableGaps.values.firstOrNull()?.let { cause ->
            throw AppError(cause)
        }
        if (xpubs.isEmpty()) {
            throw AppError("Could not read any account keys from your Trezor. Reconnect and try again.")
        }
        // Labels are set for the wallet, not for the transport it happens to be reached over, so a
        // wallet showing up on a new path (a fresh usb/bluetooth handle, or a restarted bridge)
        // must keep the name the user gave it instead of falling back to the device's own.
        val identityKey = walletKey(xpubs, deviceInfo.id)
        val named = previous ?: knownDevices.firstOrNull { it.walletKey == identityKey }
        val resolvedWalletId = previous?.walletId?.takeIf { it.isNotBlank() }
            ?: knownDevices.findHardwareWalletId(xpubs, fallback = deviceInfo.id)
        val pendingName = pendingNameFor(resolvedWalletId)
        val customLabel = named?.customLabel ?: pendingName
        val known = KnownDevice(
            id = deviceInfo.id,
            name = deviceInfo.name,
            path = deviceInfo.path,
            transportType = deviceInfo.transportType.toTransportType(),
            label = features.label ?: deviceInfo.label,
            model = features.model ?: deviceInfo.model,
            lastConnectedAt = clock.nowMs(),
            xpubs = xpubs,
            customLabel = customLabel,
            walletId = resolvedWalletId,
            passphraseProtected = selection.isPassphraseProtected(previous),
            trezorDeviceId = features.deviceId ?: previous?.trezorDeviceId,
        )
        val updated = knownDevices.filterNot { it.isReplacedBy(known, refreshed = previous) } + known
        // The pending name is consumed in the same write as the entry that adopted it, so the name
        // lives in exactly one place: leaving it pending would resurrect it once the user clears the
        // entry's own label, and dropping it separately would lose it if saving the entry failed.
        saveKnownDevices(
            updated,
            pendingName = pendingName?.let { PendingNameUpdate(resolvedWalletId, name = null) },
        )
        _state.update { it.copy(knownDevices = updated.toImmutableList()) }
        return known
    }

    /**
     * The selection that derived a device's keys is authoritative, so a wallet wrongly marked hidden is
     * corrected the next time it is opened rather than staying gated behind a passphrase forever.
     * On-device entry cannot say which wallet was opened, so it keeps what the entry already knew and
     * assumes hidden only for one it has never seen.
     */
    private fun WalletSelection.isPassphraseProtected(previous: KnownDevice?): Boolean = when (this) {
        WalletSelection.Standard -> false
        is WalletSelection.Hidden -> true
        WalletSelection.OnDevice -> previous?.passphraseProtected ?: true
    }

    /**
     * The name a wallet identity carries while it has no device entry: restored from a backup, or kept
     * when the wallet was removed. Adopted the first time the identity is paired again.
     */
    private suspend fun pendingNameFor(walletId: String): String? = walletId
        .takeIf { it.isNotBlank() }
        // Only a name: failing to read one must not stop the device being paired.
        ?.let { runSuspendCatching { hwWalletStore.loadPendingNames()[it] }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    /**
     * Reads account-level extended public keys for every supported address type so a
     * watch-only balance can be tracked later without the device present. Permanent
     * rejections (e.g. unsupported address type) are skipped; transient transport
     * failures are tracked so [addOrUpdateKnownDevice] can block a partial save.
     */
    private suspend fun fetchAccountXpubs(): AccountXpubFetchResult {
        val coin = Env.network.toTrezorCoinType()
        val xpubs = mutableMapOf<String, String>()
        val transientFailures = mutableMapOf<AddressType, Throwable>()
        for (addressType in ALL_ADDRESS_TYPES) {
            val result = fetchXpubForAddressType(addressType, coin)
            result.xpub?.let { xpubs[addressType.toSettingsString()] = it }
            result.transientError?.let { transientFailures[addressType] = it }
        }
        return AccountXpubFetchResult(xpubs = xpubs, transientFailures = transientFailures)
    }

    private suspend fun fetchXpubForAddressType(
        addressType: AddressType,
        coin: TrezorCoinType,
    ): XpubFetchResult {
        val path = addressType.toAccountDerivationPath(network = Env.network)
        val settingsKey = addressType.toSettingsString()
        var lastError: Throwable? = null
        for (attempt in 1..MAX_XPUB_FETCH_ATTEMPTS) {
            val result = runSuspendCatching {
                trezorService.getPublicKey(
                    path = path,
                    coin = coin,
                    showOnTrezor = false,
                ).xpub
            }
            if (result.isSuccess) {
                return XpubFetchResult(xpub = result.getOrThrow())
            }
            lastError = result.exceptionOrNull() ?: return XpubFetchResult()
            Logger.warn(
                "Could not read xpub for '$settingsKey' (attempt $attempt/$MAX_XPUB_FETCH_ATTEMPTS)",
                lastError,
                context = TAG,
            )
            if (!isTransientTransportFailure(lastError) || attempt == MAX_XPUB_FETCH_ATTEMPTS) {
                break
            }
            delay(XPUB_FETCH_RETRY_DELAY)
        }
        val transientError = lastError?.takeIf { isTransientTransportFailure(it) }
        return XpubFetchResult(transientError = transientError)
    }

    private suspend fun loadKnownDevices(): List<KnownDevice> = runCatching {
        val devices = hwWalletStore.loadKnownDevices()
        val migrated = devices.withHardwareWalletIds()
        if (migrated != devices) {
            hwWalletStore.saveKnownDevices(migrated)
        }
        migrated
    }.onFailure {
        Logger.error("Failed to load known devices", it, context = TAG)
    }.getOrDefault(emptyList())

    private suspend fun saveKnownDevices(devices: List<KnownDevice>, pendingName: PendingNameUpdate? = null) {
        runSuspendCatching {
            hwWalletStore.saveKnownDevices(devices, pendingName)
        }.onFailure { Logger.error("Failed to save known devices", it, context = TAG) }
    }

    private fun electrumUrlForNetwork(network: BitkitCoreNetwork): String = Env.electrumUrlForNetwork(network)

    private suspend fun currentElectrumUrl(): String = settingsStore.data.first().electrumServer

    private suspend fun ensureConnected() {
        if (trezorService.isConnected()) return
        val deviceId = _state.value.connectedDeviceId()
            ?: _state.value.knownDevices.firstOrNull()?.id
            ?: throw AppError("No device to reconnect")
        awaitSetup()
        val knownDevices = (_state.value.knownDevices + loadKnownDevices()).distinctBy { it.id }
        val knownDevice = knownDevices.find { it.matches(deviceId) }
        val device = findKnownDeviceInScan(
            deviceId = deviceId,
            knownDevice = knownDevice,
            allowBleFallback = true,
        )
        val features = connectWithThpRetry(device.id, trezorUiHandler.currentSelection())
        _state.update { state ->
            val connected = state.connected
                ?.takeIf { it.id == deviceId }
                ?.copy(features = features)
                ?: ConnectedTrezorDevice(id = deviceId, features = features)
            state.copy(connected = connected)
        }
    }

    private suspend fun awaitSetup(walletIndex: Int = 0) {
        initialize(walletIndex).getOrThrow()
        isSetup.await()
    }

    private suspend fun resetSetup() {
        setupMutex.withLock {
            isSetup.cancel()
            isSetup = CompletableDeferred()
        }
    }

    suspend fun clearCredentials(deviceId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            trezorService.clearCredentials(deviceId)
            _state.update { it.copy(error = null) }
        }.onFailure { e ->
            Logger.error("Trezor clearCredentials failed", e, context = TAG)
            _state.update { it.copy(error = trezorErrorMessage(e)) }
        }
    }

    private suspend fun connectWithThpRetry(
        deviceId: String,
        selection: WalletSelection,
        requestUsbPermission: Boolean = true,
    ): TrezorFeatures {
        TrezorDebugLog.log("THPRetry", "First connect attempt for: $deviceId")
        logCredentialFileState(deviceId, "BEFORE 1st attempt")
        return runCatching {
            connectDevice(deviceId, selection, requestUsbPermission)
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
            TrezorDebugLog.log("THPRetry", "Error is retryable, resetting the session before reconnecting...")
            Logger.warn("Failed to connect to '$deviceId', retrying", e, context = TAG)
            disconnectStaleSession(deviceId)
            logCredentialFileState(deviceId, "BEFORE 2nd attempt")
            val result = runSuspendCatching {
                connectDevice(deviceId, selection, requestUsbPermission)
            }.onFailure {
                disconnectStaleSession(deviceId)
            }.getOrThrow()
            logCredentialFileState(deviceId, "AFTER 2nd attempt (success)")
            TrezorDebugLog.log("THPRetry", "Second attempt succeeded")
            result
        }
    }

    suspend fun disconnectStaleSession(deviceId: String): Result<Unit> = withContext(NonCancellable) {
        withContext(ioDispatcher) {
            val connectedId = _state.value.connected?.id
            if (connectedId != null && connectedId != deviceId) {
                return@withContext Result.success(Unit)
            }
            val result = runSuspendCatching {
                try {
                    trezorService.disconnect()
                } finally {
                    disconnectTransportDevice(deviceId)
                }
            }
                .onFailure {
                    Logger.warn("Failed to disconnect stale Trezor session for '$deviceId'", it, context = TAG)
                }
            _state.update { it.copy(connected = null) }
            result
        }
    }

    private suspend fun disconnectTransportDevice(deviceId: String) {
        val knownDevice = (_state.value.knownDevices + loadKnownDevices())
            .distinctBy { it.id }
            .find { it.matches(deviceId) }
        trezorTransport.disconnectDevice(knownDevice?.path ?: deviceId)
    }

    private suspend fun connectDevice(
        deviceId: String,
        selection: WalletSelection,
        requestUsbPermission: Boolean,
    ) = if (requestUsbPermission) {
        trezorService.connect(deviceId, selection)
    } else {
        trezorService.connect(deviceId, selection, requestUsbPermission = false)
    }

    private fun logCredentialFileState(deviceId: String, label: String) {
        val sanitizedId = deviceId.replace(":", "_").replace("/", "_")
        val credDir = File(context.filesDir, "trezor-thp-credentials")
        val credFile = File(credDir, "$sanitizedId.json")
        val exists = credFile.exists()
        val size = if (exists) credFile.length() else 0
        TrezorDebugLog.log("CRED", "$label: file=$sanitizedId.json exists=$exists size=$size")
    }

    private fun trezorErrorMessage(error: Throwable): String? =
        if (error.isTrezorDeviceBusy() || error.isTrezorFirmwareError()) {
            TrezorErrorPresenter.userMessage(context, error)
        } else {
            error.message
        }

    private fun isTransientTransportFailure(error: Throwable): Boolean {
        if (error.isTrezorDeviceBusy()) return true
        // Typed variants first: several TrezorException variants carry a blank
        // message, so the string markers below would never match them.
        if (generateSequence(error) { it.cause }.any { it.isTransientTrezorException() }) return true
        val text = buildString {
            append(error.message.orEmpty())
            error.cause?.message?.let { append(it) }
        }
        return TRANSIENT_FAILURE_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) }
    }

    private fun Throwable.isTransientTrezorException(): Boolean = when (this) {
        is TrezorException.TransportException,
        is TrezorException.ConnectionException,
        is TrezorException.DeviceDisconnected,
        is TrezorException.Timeout,
        is TrezorException.IoException,
        is TrezorException.SessionException,
        -> true

        else -> false
    }

    private fun isRetryableError(e: Throwable): Boolean {
        if (e.isTrezorDeviceBusy()) return false
        val msg = e.message?.lowercase().orEmpty()
        // A rejected session (wrong passphrase, or the user cancelling on-device
        // passphrase entry) is a definitive failure, not a transient THP/transport
        // hiccup. Retrying it just re-prompts the device and risks wedging the
        // connection, so don't treat ThpCreateNewSession rejections as retryable.
        if ("rejected" in msg) return false
        // Typed variants: Timeout and DeviceDisconnected carry blank messages and
        // SessionException a details-only one, so the string markers below can
        // miss them. Session rejections are already excluded by the guard above.
        val hasRetryableTyped = generateSequence(e) { it.cause }.any {
            it is TrezorException.Timeout ||
                it is TrezorException.DeviceDisconnected ||
                it is TrezorException.SessionException
        }
        if (hasRetryableTyped) return true
        return "thp" in msg || "session" in msg || "timeout" in msg || "disconnect" in msg
    }

    private data class AccountXpubFetchResult(
        val xpubs: Map<String, String>,
        val transientFailures: Map<AddressType, Throwable>,
    )

    private data class XpubFetchResult(
        val xpub: String? = null,
        val transientError: Throwable? = null,
    )
}

@Stable
data class TrezorState(
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
    fun connectedDevice(): TrezorFeatures? = connected?.features

    fun connectedDeviceId(): String? = connected?.id

    fun connectedWalletId(): String? = connected?.walletId
}

@Stable
data class ConnectedTrezorDevice(
    val id: String,
    val features: TrezorFeatures,
    /** Identity the live session was opened for; a device can hold several passphrase wallets. */
    val walletId: String? = null,
)

private fun KnownDevice.matches(deviceId: String) = id == deviceId || path == deviceId

/**
 * Whether a stored entry gives way to the one just read. That covers the identity it holds and the
 * entry this connect refreshed, since reading a previously rejected address type changes the
 * walletKey and matching on the new key alone would leave the old entry behind as a duplicate.
 * Wallets of a seed the device no longer carries go too: nothing would ever supersede them by key
 * material. An unknown device id proves nothing, so those entries are left alone.
 */
private fun KnownDevice.isReplacedBy(known: KnownDevice, refreshed: KnownDevice?): Boolean {
    if (id != known.id) return false
    if (walletKey == known.walletKey) return true
    if (refreshed != null && walletKey == refreshed.walletKey) return true
    return known.trezorDeviceId != null && trezorDeviceId != null && trezorDeviceId != known.trezorDeviceId
}

private val KnownDevice.walletKey: String
    get() = walletKey(xpubs, id)

private fun walletKey(xpubs: Map<String, String>, fallback: String): String =
    xpubs.values.sorted().joinToString().ifEmpty { fallback }

private fun deriveHardwareWalletId(xpubs: Map<String, String>): String? =
    if (xpubs.isEmpty()) {
        null
    } else {
        runCatching { HwWalletId.derive(xpubs) }.getOrNull()
    }

private fun List<KnownDevice>.findHardwareWalletId(xpubs: Map<String, String>, fallback: String): String {
    val walletKey = walletKey(xpubs, fallback)
    return firstOrNull { it.walletKey == walletKey }?.walletId?.takeIf { it.isNotBlank() }
        ?: deriveHardwareWalletId(xpubs).orEmpty()
}

private fun List<KnownDevice>.withHardwareWalletIds(): List<KnownDevice> {
    val existingByWallet = filter { it.walletId.isNotBlank() }
        .associate { it.walletKey to it.walletId }
    val generatedByWallet = mutableMapOf<String, String>()

    return map {
        val walletId = existingByWallet[it.walletKey]
            ?: generatedByWallet.getOrPut(it.walletKey) {
                deriveHardwareWalletId(it.xpubs).orEmpty()
            }
        if (it.walletId == walletId) it else it.copy(walletId = walletId)
    }
}

private fun KnownDevice.toDeviceInfo() = TrezorDeviceInfo(
    id = id,
    transportType = transportType.toCoreTransportType(),
    name = name,
    path = path,
    label = label,
    model = model,
    isBootloader = false,
)

private fun TransportType.toCoreTransportType(): TrezorTransportType = when (this) {
    TransportType.BLUETOOTH -> TrezorTransportType.BLUETOOTH
    TransportType.USB -> TrezorTransportType.USB
}
