package to.bitkit.repositories

import android.content.Context
import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CompletedTransaction
import com.synonym.bitkitcore.JadeDeviceInfo
import com.synonym.bitkitcore.JadeException
import com.synonym.bitkitcore.JadeState
import com.synonym.bitkitcore.JadeTransportKind
import com.synonym.bitkitcore.JadeVersionInfo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.isJadeDeviceBusy
import to.bitkit.ext.isJadeUserCancellation
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HwConnectedDevice
import to.bitkit.models.HwDeviceState
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwNearbyDevice
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.deriveHardwareWalletId
import to.bitkit.models.findHardwareWalletId
import to.bitkit.models.isReplacedBy
import to.bitkit.models.matches
import to.bitkit.models.toJadeNetwork
import to.bitkit.models.walletKey
import to.bitkit.models.withHardwareWalletIds
import to.bitkit.services.JadeService
import to.bitkit.services.JadeTransport
import to.bitkit.utils.AppError
import to.bitkit.utils.HwErrorPresenter
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Device sessions for Blockstream Jade wallets. Owns discovery, connect and unlock, the persisted
 * entries of paired Jades, and the two device operations Bitkit needs (address verification and
 * PSBT signing). Watchers, compose and broadcast are vendor neutral and stay in [TrezorRepo].
 *
 * A Jade locks on every power cycle and unlocks with a PIN entered on the device, which needs the
 * pinserver round trip that bitkit-core performs. Silent reconnects never unlock: only the user's own
 * action (pairing, verifying an address, signing) puts the PIN screen on the device.
 */
@OptIn(ExperimentalTime::class)
@Suppress("TooManyFunctions", "LargeClass")
@Singleton
class JadeRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jadeService: JadeService,
    private val jadeTransport: JadeTransport,
    private val hwWalletStore: HwWalletStore,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "JadeRepo"
        private const val TRANSPORT_RESTORED_MAX_ATTEMPTS = 4
        private val TRANSPORT_RESTORED_RECONNECT_DELAY = 2.seconds
        private val CONNECT_ATTEMPT_POLL_INTERVAL = 250.milliseconds
        private val CONNECT_ATTEMPT_MAX_WAIT = 28.seconds

        /**
         * How long the app may sit in the background before an open Bluetooth link is released. A
         * process killed with the link open leaves the Jade holding a dead connection and, as seen on
         * hardware, drops the bond; closing the link cleanly first avoids that. The delay keeps a
         * brief switch to another app during a PIN or signing prompt from cancelling it.
         */
        private val BACKGROUND_RELEASE_DELAY = 30.seconds
        private val ALL_ACCOUNT_TYPES = listOf(
            AccountType.LEGACY,
            AccountType.WRAPPED_SEGWIT,
            AccountType.NATIVE_SEGWIT,
            AccountType.TAPROOT,
        )
    }

    private val _state = MutableStateFlow(JadeRepoState())
    val state = _state.asStateFlow()

    private val scope = appScope(ioDispatcher, TAG)
    private var isSetup = CompletableDeferred<Unit>()
    private val setupMutex = Mutex()

    @Volatile
    private var transportReconnectJob: Job? = null

    @Volatile
    private var backgroundReleaseJob: Job? = null

    init {
        observeExternalDisconnects()
        observeTransportRestored()
    }

    suspend fun initialize(): Result<Unit> = withContext(ioDispatcher) {
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
                jadeService.initialize()
                val known = loadKnownDevices()
                _state.update { it.copy(knownDevices = known.toImmutableList(), error = null) }
                setup.complete(Unit)
                Unit
            }.onFailure {
                setup.completeExceptionally(it)
                if (isSetup === setup) {
                    isSetup = CompletableDeferred()
                }
                Logger.error("Jade init failed", it, context = TAG)
                _state.update { s -> s.copy(error = errorMessage(it)) }
            }
        }
    }

    suspend fun resetState() = withContext(ioDispatcher) {
        setupMutex.withLock {
            isSetup.cancel()
            isSetup = CompletableDeferred()
        }
        transportReconnectJob?.cancel()
        transportReconnectJob = null
        if (_state.value.connected != null) {
            runSuspendCatching { disconnect().getOrThrow() }
        }
        _state.update { JadeRepoState() }
    }

    /**
     * Discovers nearby Jades. Core refuses to scan while a session is open, since a Bluetooth scan
     * drops the open link, so the devices of the last scan are reused instead of failing the search.
     */
    suspend fun scan(includeBluetooth: Boolean = true): Result<List<JadeDeviceInfo>> = withContext(ioDispatcher) {
        runSuspendCatching {
            awaitSetup()
            _state.update { it.copy(isScanning = true, error = null) }
            val devices = if (jadeService.isConnected()) {
                jadeService.listDevices()
            } else {
                jadeService.scan(includeBluetooth = includeBluetooth)
            }
            val known = _state.value.knownDevices
            val nearby = devices.filterNot { known.any { entry -> entry.isSameDevice(it) } }
            _state.update { it.copy(isScanning = false, nearbyDevices = nearby.toImmutableList()) }
            devices
        }.onFailure {
            Logger.error("Jade scan failed", it, context = TAG)
            _state.update { s -> s.copy(isScanning = false, error = errorMessage(it)) }
        }
    }

    /** Pairs a discovered device: connects, unlocks with the on-device PIN, reads its accounts and stores it. */
    suspend fun connect(
        path: String,
        requestUsbPermission: Boolean = true,
    ): Result<ConnectedJadeDevice> = withContext(ioDispatcher) {
        var startedConnecting = false
        try {
            runSuspendCatching {
                awaitSetup()
                startedConnecting = true
                _state.update { it.copy(isConnecting = true, error = null) }
                val device = resolveDevice(path)
                val connected = connectDevice(device, requestUsbPermission = requestUsbPermission, unlock = true)
                _state.update {
                    it.copy(nearbyDevices = it.nearbyDevices.filter { d -> d.path != path }.toImmutableList())
                }
                connected
            }.onFailure {
                Logger.error("Jade connect failed", it, context = TAG)
                _state.update { s -> s.copy(error = errorMessage(it)) }
            }
        } finally {
            if (startedConnecting) {
                _state.update { it.copy(isConnecting = false) }
            }
        }
    }

    /**
     * Reconnects a paired Jade. Bluetooth entries reconnect by their stored address; a USB entry is
     * found among the plugged-in Jades and confirmed by its hardware id, since its path changes on
     * every replug. With [unlock] off the session stays locked, which is what silent reconnects want.
     */
    suspend fun connectKnownDevice(
        deviceId: String,
        forceSession: Boolean = false,
        unlock: Boolean = true,
        requestUsbPermission: Boolean = true,
    ): Result<ConnectedJadeDevice> = withContext(ioDispatcher) {
        if (isConnectInProgress()) {
            return@withContext Result.failure(AppError("Connection already in progress"))
        }
        connectKnownDeviceUnguarded(deviceId, forceSession, unlock, requestUsbPermission)
    }

    private suspend fun connectKnownDeviceUnguarded(
        deviceId: String,
        forceSession: Boolean,
        unlock: Boolean,
        requestUsbPermission: Boolean,
    ): Result<ConnectedJadeDevice> {
        var startedConnecting = false
        return try {
            runSuspendCatching {
                startedConnecting = true
                _state.update { it.copy(isConnecting = true, error = null) }
                awaitSetup()
                if (forceSession) disconnectStaleSession(deviceId)
                val entry = knownDevice(deviceId) ?: throw AppError("Unknown Jade '$deviceId'")
                val device = findKnownDeviceNearby(entry, requestUsbPermission = requestUsbPermission)
                val connected = connectDevice(
                    device = device,
                    requestUsbPermission = requestUsbPermission,
                    unlock = unlock,
                    expected = entry,
                )
                Logger.info("Reconnected known Jade '${entry.id}'", context = TAG)
                connected
            }.onFailure {
                Logger.error("Jade reconnect failed", it, context = TAG)
                _state.update { s -> s.copy(error = errorMessage(it)) }
                if (!forceSession) disconnectStaleSession(deviceId)
            }
        } finally {
            if (startedConnecting) {
                _state.update { it.copy(isConnecting = false) }
            }
        }
    }

    /** A live, unlocked session for [deviceId]: reuses the current one, else reconnects and unlocks. */
    suspend fun ensureConnected(deviceId: String): Result<ConnectedJadeDevice> = withContext(ioDispatcher) {
        runSuspendCatching {
            awaitSetup()
            val current = awaitConnectedOrNull(deviceId)
                ?: return@runSuspendCatching connectKnownDevice(deviceId, forceSession = true).getOrThrow()
            if (!current.isLocked) return@runSuspendCatching current
            val version = unlockConnected()
            current.copy(versionInfo = version).also { unlocked ->
                _state.update { it.copy(connected = unlocked) }
            }
        }
    }

    /** Silent reconnect after a transport came back; never asks for the PIN. */
    suspend fun autoReconnect(preferredTransport: TransportType? = null): Result<ConnectedJadeDevice> =
        withContext(ioDispatcher) {
            if (isConnectInProgress()) {
                return@withContext Result.failure(AppError("Connect already in progress"))
            }
            val knownDevices = _state.value.knownDevices.ifEmpty { loadKnownDevices() }
            if (knownDevices.isEmpty()) {
                return@withContext Result.failure(AppError("No known devices"))
            }
            _state.update { it.copy(isAutoReconnecting = true, error = null) }
            try {
                runSuspendCatching {
                    awaitSetup()
                    _state.value.connected?.takeIf { jadeService.isConnected() }?.let { return@runSuspendCatching it }
                    if (jadeService.isConnected()) runSuspendCatching { jadeService.disconnect() }
                    val ordered = knownDevices.sortedByDescending { it.transportType == preferredTransport }
                    val entry = ordered.firstOrNull { it.transportType != TransportType.USB || hasPluggedInJade() }
                        ?: throw AppError("No known device found nearby")
                    connectKnownDeviceUnguarded(
                        deviceId = entry.id,
                        forceSession = false,
                        unlock = false,
                        requestUsbPermission = false,
                    ).getOrThrow()
                }.onFailure {
                    Logger.error("Jade auto-reconnect failed", it, context = TAG)
                    _state.update { s -> s.copy(error = errorMessage(it)) }
                }
            } finally {
                _state.update { it.copy(isAutoReconnecting = false) }
            }
        }

    suspend fun verifyAddress(
        addressType: HwFundingAddressType,
        derivationPath: String,
        expectedAddress: String,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            jadeService.verifyAddress(
                network = Env.network.toJadeNetwork(),
                variant = addressType.jadeVariant,
                derivationPath = derivationPath,
                expectedAddress = expectedAddress,
            )
        }.onFailure {
            Logger.error("Jade verifyAddress failed", it, context = TAG)
            _state.update { s -> s.copy(error = errorMessage(it)) }
        }
    }

    /** Signs on the device and completes the PSBT into a broadcastable transaction. */
    suspend fun signPsbt(psbtBase64: String): Result<CompletedTransaction> = withContext(ioDispatcher) {
        runSuspendCatching {
            val signed = jadeService.signPsbt(network = Env.network.toJadeNetwork(), psbtBase64 = psbtBase64)
            jadeService.finalizePsbt(originalPsbt = psbtBase64, signedPsbt = signed)
        }.onFailure {
            Logger.error("Jade signPsbt failed", it, context = TAG)
            _state.update { s -> s.copy(error = errorMessage(it)) }
        }
    }

    suspend fun getMasterFingerprint(): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching { jadeService.getMasterFingerprint(Env.network.toJadeNetwork()) }
            .onFailure { Logger.error("Jade getMasterFingerprint failed", it, context = TAG) }
    }

    suspend fun disconnect(): Result<Unit> = withContext(ioDispatcher) {
        val connected = _state.value.connected
        runSuspendCatching {
            try {
                jadeService.disconnect()
            } finally {
                connected?.let { jadeTransport.disconnectDevice(it.path) }
            }
            Unit
        }.also {
            _state.update { it.copy(connected = null) }
        }.onFailure {
            Logger.error("Jade disconnect failed", it, context = TAG)
            _state.update { s -> s.copy(error = errorMessage(it)) }
        }
    }

    suspend fun disconnectStaleSession(deviceId: String): Result<Unit> = withContext(NonCancellable) {
        withContext(ioDispatcher) {
            val connected = _state.value.connected
            if (connected != null && !connected.matches(deviceId)) {
                return@withContext Result.success(Unit)
            }
            val result = runSuspendCatching {
                try {
                    jadeService.disconnect()
                } finally {
                    val path = connected?.path ?: knownDevice(deviceId)?.path ?: deviceId
                    jadeTransport.disconnectDevice(path)
                }
                Unit
            }.onFailure {
                Logger.warn("Failed to disconnect stale Jade session for '$deviceId'", it, context = TAG)
            }
            _state.update { it.copy(connected = null) }
            result
        }
    }

    /**
     * Forgets a paired entry. [walletKey] scopes the removal to one wallet identity; the same Jade
     * paired over both transports is stored once per transport and both entries go together.
     */
    suspend fun forgetDevice(
        deviceId: String,
        walletKey: String? = null,
        pendingName: PendingNameUpdate? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val stored = loadKnownDevices()
            val storedEntries = stored.map { it.id to it.walletKey }.toSet()
            val knownDevices = stored + _state.value.knownDevices.filter { (it.id to it.walletKey) !in storedEntries }
            val isForgotten: (KnownDevice) -> Boolean = when (walletKey) {
                null -> { entry -> entry.id == deviceId }
                else -> { entry -> entry.walletKey == walletKey }
            }
            val updated = knownDevices.filterNot(isForgotten)
            val connected = _state.value.connected
            val forgetsSession = connected != null && knownDevices.filter(isForgotten).any { it.id == connected.id }
            val disconnectResult = if (forgetsSession) {
                disconnect()
            } else {
                Result.success(Unit)
            }
            saveKnownDevices(updated, pendingName)
            _state.update { it.copy(knownDevices = updated.toImmutableList()) }
            disconnectResult.onFailure {
                Logger.warn("Ignored disconnect failure while forgetting Jade '$deviceId'", it, context = TAG)
            }
            Logger.info("Forgot Jade '$deviceId'", context = TAG)
        }.onFailure {
            Logger.error("Forget Jade failed", it, context = TAG)
            _state.update { s -> s.copy(error = errorMessage(it)) }
        }
    }

    suspend fun hasKnownDevice(deviceId: String): Boolean = withContext(ioDispatcher) {
        knownDevice(deviceId) != null
    }

    /** Whether [deviceId] names a known USB entry or any USB Jade is paired: USB paths change on replug. */
    suspend fun hasKnownUsbDevice(deviceId: String): Boolean = withContext(ioDispatcher) {
        val known = knownDevices()
        known.any { it.matches(deviceId) } || known.any { it.transportType == TransportType.USB }
    }

    suspend fun isKnownBluetoothDevice(deviceId: String): Boolean = withContext(ioDispatcher) {
        knownDevice(deviceId)?.transportType == TransportType.BLUETOOTH
    }

    fun deriveWalletId(xpubs: Map<String, String>): String? =
        deriveHardwareWalletId(xpubs, HwWalletVendor.BLOCKSTREAM)?.takeIf { it.isNotBlank() }

    fun onTransportRestored(transportType: TransportType) = launchTransportReconnect(transportType)

    /** Releases an open Bluetooth link after [BACKGROUND_RELEASE_DELAY] unless the app comes back first. */
    fun onAppBackgrounded() {
        if (backgroundReleaseJob?.isActive == true) return
        backgroundReleaseJob = scope.launch {
            delay(BACKGROUND_RELEASE_DELAY)
            val connected = _state.value.connected ?: return@launch
            if (connected.transport != JadeTransportKind.BLUETOOTH) return@launch
            Logger.info("Releasing the Jade bluetooth link while the app is in the background", context = TAG)
            disconnect()
        }
    }

    fun onAppForegrounded() {
        backgroundReleaseJob?.cancel()
        backgroundReleaseJob = null
        scope.launch {
            if (_state.value.connected != null || isConnectInProgress()) return@launch
            val knownDevices = _state.value.knownDevices.ifEmpty { loadKnownDevices() }
            if (knownDevices.none { it.transportType == TransportType.BLUETOOTH }) return@launch
            Logger.info("Attempting Jade bluetooth auto-reconnect after app foregrounded", context = TAG)
            launchTransportReconnect(TransportType.BLUETOOTH)
        }
    }

    /** Pre-connects a known Bluetooth Jade before a sign screen asks for it, without unlocking. */
    fun warmUpKnownDevice(deviceId: String) {
        scope.launch {
            if (awaitConnectedOrNull(deviceId) != null) return@launch
            if (isConnectInProgress()) return@launch
            if (!isKnownBluetoothDevice(deviceId)) return@launch
            Logger.info("Warming up known Jade '$deviceId'", context = TAG)
            connectKnownDevice(deviceId, unlock = false).onFailure {
                Logger.debug("Warm up connect failed for '$deviceId'", context = TAG)
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------
    // Connect internals
    // ------------------------------------------------------------------

    private suspend fun resolveDevice(path: String): JadeDeviceInfo {
        _state.value.nearbyDevices.firstOrNull { it.path == path }?.let { return it }
        jadeService.listDevices().firstOrNull { it.path == path }?.let { return it }
        // Core only connects to a device of its last scan, so refresh it for a path handed in by
        // the OS attach intent.
        val includeBluetooth = path.startsWith(BLE_PATH_PREFIX)
        val scanned = if (jadeService.isConnected()) jadeService.listDevices() else jadeService.scan(includeBluetooth)
        return scanned.firstOrNull { it.path == path }
            ?: JadeDeviceInfo(
                path = path,
                transport = if (includeBluetooth) JadeTransportKind.BLUETOOTH else JadeTransportKind.SERIAL,
                name = null,
                serialNumber = null,
            )
    }

    private suspend fun findKnownDeviceNearby(entry: KnownDevice, requestUsbPermission: Boolean): JadeDeviceInfo {
        val transport = entry.transportType.toJadeTransportKind()
        val includeBluetooth = transport == JadeTransportKind.BLUETOOTH
        val scanned = runSuspendCatching {
            if (jadeService.isConnected()) jadeService.listDevices() else jadeService.scan(includeBluetooth)
        }.getOrElse {
            Logger.warn("Scan before Jade reconnect failed", it, context = TAG)
            emptyList()
        }
        scanned.firstOrNull { it.path == entry.path && it.transport == transport }?.let { return it }
        if (includeBluetooth) {
            // A Jade advertises under a fresh random address after a reboot, but its name carries
            // the tail of the efuse MAC, so a renamed entry is still recognisable in the scan.
            scanned.firstOrNull { it.transport == transport && entry.advertisesAs(it.name) }?.let { return it }
            // Otherwise the stored address is tried directly: the transport resolves it without a scan hit.
            return JadeDeviceInfo(path = entry.path, transport = transport, name = entry.name, serialNumber = null)
        }
        val candidates = scanned.filter { it.transport == JadeTransportKind.SERIAL }
            .filter { requestUsbPermission || jadeTransport.hasUsbPermission(it.path) }
        return candidates.firstOrNull() ?: throw AppError("Jade not found nearby: is it plugged in?")
    }

    private suspend fun connectDevice(
        device: JadeDeviceInfo,
        requestUsbPermission: Boolean,
        unlock: Boolean,
        expected: KnownDevice? = null,
    ): ConnectedJadeDevice {
        var version = jadeService.connect(device.transport, device.path, requestUsbPermission = requestUsbPermission)
        Logger.info(
            "Connected Jade '${device.path}' firmware '${version.jadeVersion}' state '${version.jadeState}'",
            context = TAG,
        )
        rejectUnusableDevice(version, expected)
        if (unlock && version.jadeState == JadeState.LOCKED) {
            version = unlockConnected()
        }
        val known = if (version.jadeState.isUnlocked()) {
            val xpubs = exportAccounts()
            addOrUpdateKnownDevice(device, version, xpubs)
        } else {
            // Still locked, so its keys cannot be read: only an entry already holding them is usable.
            val entry = expected ?: knownDevice(deviceIdFor(device.transport, version.efuseMac) ?: device.path)
            entry?.let { refreshKnownDevice(it, device) } ?: rejectDevice(JadeException.DeviceLocked())
        }
        val connected = ConnectedJadeDevice(
            id = known.id,
            path = device.path,
            transport = device.transport,
            versionInfo = version,
            walletId = known.walletId.takeIf { it.isNotBlank() },
        )
        _state.update { it.copy(connected = connected) }
        return connected
    }

    private suspend fun rejectUnusableDevice(version: JadeVersionInfo, expected: KnownDevice?) {
        if (version.jadeState == JadeState.UNINIT) rejectDevice(HwDeviceUninitializedError())
        val expectedHardwareId = expected?.jadeDeviceId
        val hardwareId = version.efuseMac
        if (expectedHardwareId != null && hardwareId != null && expectedHardwareId != hardwareId) {
            rejectDevice(AppError("A different Jade is connected"))
        }
    }

    private suspend fun rejectDevice(error: Throwable): Nothing {
        runSuspendCatching { jadeService.disconnect() }
        throw error
    }

    private suspend fun unlockConnected(): JadeVersionInfo {
        _state.update { it.copy(isUnlocking = true) }
        try {
            // Core enforces the five minute unlock deadline; the PIN is typed on the device.
            jadeService.unlock(Env.network.toJadeNetwork())
            return jadeService.refreshVersionInfo()
        } finally {
            _state.update { it.copy(isUnlocking = false) }
        }
    }

    private suspend fun exportAccounts(): Map<String, String> {
        val network = Env.network.toJadeNetwork()
        val export = runSuspendCatching { jadeService.getAccountExport(network, ALL_ACCOUNT_TYPES) }
            .getOrElse {
                if (it !is JadeException.UnsupportedFirmware) throw it
                Logger.warn("Retrying Jade account export without taproot", it, context = TAG)
                jadeService.getAccountExport(network, ALL_ACCOUNT_TYPES - AccountType.TAPROOT)
            }
        val xpubs = export.accounts.associate {
            HwFundingAddressType.fromJadeVariant(it.variant).settingsKey to it.xpub
        }
        if (xpubs.isEmpty()) throw AppError("Could not read any account keys from your Jade. Reconnect and try again.")
        return xpubs
    }

    private suspend fun addOrUpdateKnownDevice(
        device: JadeDeviceInfo,
        version: JadeVersionInfo,
        fetchedXpubs: Map<String, String>,
    ): KnownDevice {
        val stored = loadKnownDevices()
        val storedEntries = stored.map { it.id to it.walletKey }.toSet()
        val knownDevices = stored + _state.value.knownDevices.filter { (it.id to it.walletKey) !in storedEntries }
        val id = deviceIdFor(device.transport, version.efuseMac) ?: device.path
        // The hardware id, not the path, identifies an entry: a USB path is renumbered on every plug.
        val candidates = knownDevices.filter { it.id == id }
        val previous = candidates.firstOrNull {
            it.xpubs.values.intersect(fetchedXpubs.values.toSet()).isNotEmpty()
        } ?: candidates.singleOrNull()?.takeIf { it.xpubs.isEmpty() }
        val xpubs = previous?.xpubs.orEmpty() + fetchedXpubs
        val identityKey = walletKey(xpubs, id)
        val named = previous ?: knownDevices.firstOrNull { it.walletKey == identityKey }
        val resolvedWalletId = previous?.walletId?.takeIf { it.isNotBlank() }
            ?: knownDevices.findHardwareWalletId(xpubs, fallback = id, vendor = HwWalletVendor.BLOCKSTREAM)
        val pendingName = pendingNameFor(resolvedWalletId)
        val known = KnownDevice(
            id = id,
            name = device.name,
            path = device.path,
            transportType = device.transport.toTransportType(),
            label = null,
            model = version.boardType.toJadeModel(),
            lastConnectedAt = clock.nowMs(),
            xpubs = xpubs,
            customLabel = named?.customLabel ?: pendingName,
            walletId = resolvedWalletId,
            vendor = HwWalletVendor.BLOCKSTREAM,
            jadeDeviceId = version.efuseMac,
        )
        val updated = knownDevices.filterNot { it.isReplacedBy(known, refreshed = previous) } + known
        saveKnownDevices(
            updated,
            pendingName = pendingName?.let { PendingNameUpdate(resolvedWalletId, name = null) },
        )
        _state.update { it.copy(knownDevices = updated.toImmutableList()) }
        return known
    }

    private suspend fun refreshKnownDevice(entry: KnownDevice, device: JadeDeviceInfo): KnownDevice {
        val refreshed = entry.copy(path = device.path, lastConnectedAt = clock.nowMs())
        val updated = knownDevices().map { if (it.id == entry.id && it.walletKey == entry.walletKey) refreshed else it }
        saveKnownDevices(updated)
        _state.update { it.copy(knownDevices = updated.toImmutableList()) }
        return refreshed
    }

    private suspend fun pendingNameFor(walletId: String): String? = walletId
        .takeIf { it.isNotBlank() }
        ?.let { runSuspendCatching { hwWalletStore.loadPendingNames()[it] }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    private suspend fun hasPluggedInJade(): Boolean = runSuspendCatching {
        jadeService.scan(includeBluetooth = false).any { it.transport == JadeTransportKind.SERIAL }
    }.getOrDefault(false)

    private suspend fun knownDevices(): List<KnownDevice> =
        (_state.value.knownDevices + loadKnownDevices()).distinctBy { it.id to it.walletKey }

    private suspend fun knownDevice(deviceId: String): KnownDevice? =
        knownDevices().firstOrNull { it.matches(deviceId) }

    private suspend fun awaitConnectedOrNull(deviceId: String): ConnectedJadeDevice? {
        connectedDevice(deviceId)?.let { return it }
        if (isConnectInProgress()) {
            transportReconnectJob?.takeIf { it.isActive }?.join()
            waitForConnectAttempt(deviceId)
            connectedDevice(deviceId)?.let { return it }
        }
        return null
    }

    private suspend fun connectedDevice(deviceId: String): ConnectedJadeDevice? {
        val current = _state.value.connected ?: return null
        return if (current.matches(deviceId) && jadeService.isConnected()) current else null
    }

    private suspend fun waitForConnectAttempt(deviceId: String) {
        runCatching {
            withTimeout(CONNECT_ATTEMPT_MAX_WAIT) {
                while (true) {
                    if (connectedDevice(deviceId) != null) return@withTimeout
                    if (!isConnectInProgress()) return@withTimeout
                    delay(CONNECT_ATTEMPT_POLL_INTERVAL)
                }
            }
        }.onFailure {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
        }
    }

    private fun isConnectInProgress(): Boolean = _state.value.let { it.isConnecting || it.isAutoReconnecting }

    private fun launchTransportReconnect(transportType: TransportType) {
        if (transportReconnectJob?.isActive == true) return
        transportReconnectJob = scope.launch { retryAutoReconnect(transportType) }
    }

    private suspend fun retryAutoReconnect(transportType: TransportType) {
        repeat(TRANSPORT_RESTORED_MAX_ATTEMPTS) { attempt ->
            if (_state.value.connected != null || isConnectInProgress()) return
            delay(TRANSPORT_RESTORED_RECONNECT_DELAY * (attempt + 1))
            if (_state.value.connected != null || isConnectInProgress()) return
            Logger.info(
                "Attempting Jade auto-reconnect after transport restored, attempt '${attempt + 1}'",
                context = TAG
            )
            val result = autoReconnect(preferredTransport = transportType)
            if (result.isSuccess) return
            if (result.exceptionOrNull()?.isJadeDeviceBusy() == true) return
        }
    }

    private fun observeExternalDisconnects() {
        jadeTransport.externalDisconnect.onEach { path ->
            val connected = _state.value.connected ?: return@onEach
            if (connected.path != path) return@onEach
            Logger.warn("External disconnect detected for Jade '${connected.id}'", context = TAG)
            _state.update { it.copy(connected = null, error = "Device disconnected") }
            runSuspendCatching { jadeService.notifyDisconnected(path) }
                .onFailure { Logger.warn("Failed to report Jade disconnect", it, context = TAG) }
        }.launchIn(scope)
    }

    private fun observeTransportRestored() {
        jadeTransport.transportRestored.onEach { launchTransportReconnect(it) }.launchIn(scope)
    }

    private suspend fun awaitSetup() {
        initialize().getOrThrow()
        isSetup.await()
    }

    private suspend fun loadKnownDevices(): List<KnownDevice> = runCatching {
        val devices = hwWalletStore.loadKnownDevices(HwWalletVendor.BLOCKSTREAM)
        val migrated = devices.withHardwareWalletIds()
        if (migrated != devices) {
            hwWalletStore.saveKnownDevices(migrated, vendor = HwWalletVendor.BLOCKSTREAM)
        }
        migrated
    }.onFailure {
        Logger.error("Failed to load known Jade devices", it, context = TAG)
    }.getOrDefault(emptyList())

    private suspend fun saveKnownDevices(devices: List<KnownDevice>, pendingName: PendingNameUpdate? = null) {
        runSuspendCatching {
            hwWalletStore.saveKnownDevices(devices, pendingName, vendor = HwWalletVendor.BLOCKSTREAM)
        }.onFailure { Logger.error("Failed to save known Jade devices", it, context = TAG) }
    }

    private fun errorMessage(error: Throwable): String? = when {
        error.isJadeUserCancellation() -> null
        else -> HwErrorPresenter.userMessage(context, error, fallback = error.message.orEmpty()).ifBlank { null }
    }

    private fun KnownDevice.advertisesAs(name: String?): Boolean {
        val suffix = jadeDeviceId?.takeLast(BLE_NAME_SUFFIX_LENGTH)?.takeIf { it.isNotBlank() } ?: return false
        return name?.endsWith(suffix, ignoreCase = true) == true
    }

    private fun KnownDevice.isSameDevice(device: JadeDeviceInfo): Boolean = when (device.transport) {
        JadeTransportKind.BLUETOOTH -> path == device.path || advertisesAs(device.name)
        // A plugged-in Jade cannot be told from a paired one before connecting, so a paired USB Jade
        // claims every serial device; a second one is added through the Add button, which offers it anyway.
        JadeTransportKind.SERIAL -> transportType == TransportType.USB
    }
}

@Stable
data class JadeRepoState(
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isAutoReconnecting: Boolean = false,
    val isUnlocking: Boolean = false,
    val knownDevices: ImmutableList<KnownDevice> = persistentListOf(),
    val nearbyDevices: ImmutableList<JadeDeviceInfo> = persistentListOf(),
    val connected: ConnectedJadeDevice? = null,
    val error: String? = null,
) {
    fun connectedDeviceId(): String? = connected?.id

    fun connectedWalletId(): String? = connected?.walletId
}

@Stable
data class ConnectedJadeDevice(
    val id: String,
    val path: String,
    val transport: JadeTransportKind,
    val versionInfo: JadeVersionInfo,
    val walletId: String? = null,
) {
    val isLocked: Boolean
        get() = versionInfo.jadeState == JadeState.LOCKED

    fun matches(deviceId: String): Boolean = id == deviceId || path == deviceId
}

fun JadeRepoState.toHwDeviceState() = HwDeviceState(
    isScanning = isScanning,
    isConnecting = isConnecting,
    isAutoReconnecting = isAutoReconnecting,
    isUnlocking = isUnlocking,
    knownDevices = knownDevices,
    nearbyDevices = nearbyDevices.map { it.toHwNearbyDevice() }.toImmutableList(),
    connected = connected?.toHwConnectedDevice(),
    error = error,
)

fun JadeDeviceInfo.toHwNearbyDevice() = HwNearbyDevice(
    vendor = HwWalletVendor.BLOCKSTREAM,
    id = path,
    path = path,
    transportType = transport.toTransportType(),
    name = name,
    model = "Jade",
)

fun ConnectedJadeDevice.toHwConnectedDevice() = HwConnectedDevice(
    vendor = HwWalletVendor.BLOCKSTREAM,
    id = id,
    label = null,
    model = versionInfo.boardType.toJadeModel(),
    walletId = walletId,
    passphraseProtection = false,
    isLocked = isLocked,
)

private const val BLE_PATH_PREFIX = "ble:"

/** A Jade advertises as "Jade" followed by the last six hex digits of its efuse MAC. */
private const val BLE_NAME_SUFFIX_LENGTH = 6

/** Stable entry id from the hardware id, so a USB replug refreshes the entry instead of adding one. */
private fun deviceIdFor(transport: JadeTransportKind, efuseMac: String?): String? =
    efuseMac?.takeIf { it.isNotBlank() }?.let { "jade:${transport.name.lowercase()}:$it" }

private fun JadeState.isUnlocked(): Boolean = this == JadeState.READY || this == JadeState.TEMP

/** Jade Plus reports a v2 board; every other board is the original Jade. */
private fun String?.toJadeModel(): String =
    if (this?.uppercase()?.contains("V2") == true) "Jade Plus" else "Jade"

fun JadeTransportKind.toTransportType(): TransportType = when (this) {
    JadeTransportKind.BLUETOOTH -> TransportType.BLUETOOTH
    JadeTransportKind.SERIAL -> TransportType.USB
}

fun TransportType.toJadeTransportKind(): JadeTransportKind = when (this) {
    TransportType.BLUETOOTH -> JadeTransportKind.BLUETOOTH
    TransportType.USB -> JadeTransportKind.SERIAL
}
