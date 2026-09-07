package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.JadeException
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.async.appScope
import to.bitkit.data.HwWalletStore
import to.bitkit.data.PendingNameUpdate
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.isHwSessionFailure
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.scopedId
import to.bitkit.ext.timestamp
import to.bitkit.ext.walletId
import to.bitkit.models.HwConnectedDevice
import to.bitkit.models.HwDeviceState
import to.bitkit.models.HwFundingAccount
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwNearbyDevice
import to.bitkit.models.HwReceiveAddress
import to.bitkit.models.HwWallet
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.models.toAccountType
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toTrezorCoinType
import to.bitkit.models.walletKey
import to.bitkit.services.TrezorWalletMode
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Production hardware-wallet business layer. Tracks paired devices of every vendor as
 * watch-only balances by running one on-chain xpub watcher per (wallet, address type)
 * and exposing the aggregated per-wallet balance and activity to the UI.
 *
 * Device sessions are owned per vendor by [TrezorRepo] and [JadeRepo]; every call that
 * touches a device is routed by the vendor of the wallet's stored entry. The watcher and
 * on-chain transport is vendor neutral and lives in [TrezorRepo].
 */
@Suppress("LargeClass", "TooManyFunctions", "LongParameterList")
@Singleton
class HwWalletRepo @Inject constructor(
    private val trezorRepo: TrezorRepo,
    private val jadeRepo: JadeRepo,
    private val activityRepo: ActivityRepo,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val hwWalletStore: HwWalletStore,
    private val settingsStore: SettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "HwWalletRepo"
        private const val WATCHER_ID_SEPARATOR = "|"
        private val WATCHER_START_RETRY_DELAY = 30.seconds
        const val DEVICE_LABEL_MAX_LENGTH = 50

        /** Trezor v1 (2.4.0) tracks native SegWit accounts. */
        private val SUPPORTED_WATCHER_ADDRESS_TYPES = setOf(HwFundingAddressType.NATIVE_SEGWIT.settingsKey)

        /** A Trezor reconnect is a session handshake; a Jade one may include entering the PIN on the device. */
        private val TREZOR_RECONNECT_TIMEOUT = 30.seconds
        private val JADE_RECONNECT_TIMEOUT = 5.minutes
    }

    /** Alternates which vendor gets the Bluetooth part of a scan, to stay under Android's scan-rate limit. */
    private var scanBluetoothVendor = HwWalletVendor.TREZOR

    private val scope = appScope(ioDispatcher, TAG)
    private val watcherMutex = Mutex()

    private val activeWatchers = mutableSetOf<String>()
    private val activeWatcherElectrumUrls = mutableMapOf<String, String>()
    private val activeWatcherWalletIds = mutableMapOf<String, String>()
    private val retryingWatcherStarts = mutableSetOf<String>()
    private val watcherSyncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _watcherData = MutableStateFlow<Map<String, HwWatcherData>>(emptyMap())
    private val trackedWalletIds = mutableSetOf<String>()
    private val lastPersistedHwSnapshots = mutableMapOf<String, PersistedHwSnapshot>()
    private val persistedActivityIds = mutableMapOf<String, Set<String>>()
    private val emittedReceivedTxIds = mutableSetOf<String>()

    private val _receivedTxs = MutableSharedFlow<HwWalletReceivedTx>(extraBufferCapacity = 8)

    /** Inbound transactions detected by a running watcher after its initial history sync. */
    val receivedTxs: SharedFlow<HwWalletReceivedTx> = _receivedTxs.asSharedFlow()

    /** Forwards UI-delivered transport events, e.g. the USB attach intent from the OS app picker. */
    fun onTransportRestored(transportType: TransportType, vendor: HwWalletVendor? = null) {
        if (vendor != HwWalletVendor.BLOCKSTREAM) trezorRepo.onTransportRestored(transportType)
        if (vendor != HwWalletVendor.TREZOR) jadeRepo.onTransportRestored(transportType)
    }

    fun onAppForegrounded() {
        trezorRepo.onAppForegrounded()
        jadeRepo.onAppForegrounded()
    }

    /** The whole app left the foreground; a Jade releases its Bluetooth link after a grace period. */
    fun onAppBackgrounded() = jadeRepo.onAppBackgrounded()

    fun warmUpKnownDevice(walletId: String) {
        scope.launch {
            val deviceId = transportDeviceIdOrNull(walletId) ?: return@launch
            when (vendorOf(walletId)) {
                HwWalletVendor.TREZOR -> trezorRepo.warmUpKnownDevice(deviceId)
                HwWalletVendor.BLOCKSTREAM -> jadeRepo.warmUpKnownDevice(deviceId)
            }
        }
    }

    /** The vendor of the device tracking [walletId]; entries stored before Jade existed are Trezor ones. */
    private suspend fun vendorOf(walletId: String): HwWalletVendor =
        devicesForWallet(walletId).firstOrNull()?.vendor ?: HwWalletVendor.TREZOR

    /** How long a reconnect may take before the UI gives up; a Jade may be waiting for its PIN. */
    suspend fun reconnectTimeout(walletId: String): Duration = when (vendorOf(walletId)) {
        HwWalletVendor.TREZOR -> TREZOR_RECONNECT_TIMEOUT
        HwWalletVendor.BLOCKSTREAM -> JADE_RECONNECT_TIMEOUT
    }

    /**
     * Entries tracking one wallet identity. A physical device holds the standard wallet plus one
     * entry per passphrase wallet, and each of those is stored once per transport it paired over.
     */
    private suspend fun devicesForWallet(walletId: String): List<KnownDevice> =
        hwWalletStore.loadKnownDevices().filter { it.resolvedWalletId() == walletId }

    /** Transport-level id to reach [walletId] with: the connected entry, else the most recent one. */
    private suspend fun transportDeviceIdOrNull(walletId: String): String? {
        val devices = devicesForWallet(walletId)
        val connectedId = deviceState.value.connectedDeviceId()
        return devices.find { it.id == connectedId }?.id ?: devices.maxByOrNull { it.lastConnectedAt }?.id
    }

    private suspend fun transportDeviceId(walletId: String): String =
        requireNotNull(transportDeviceIdOrNull(walletId)) { "Unknown hardware wallet '$walletId'" }

    suspend fun resetState() = withContext(ioDispatcher) {
        watcherMutex.withLock {
            activeWatchers.toList().forEach { watcherId ->
                trezorRepo.stopWatcher(watcherId)
                    .onFailure { Logger.warn("Failed to stop watcher '$watcherId' while resetting", it, context = TAG) }
            }
            activeWatchers.clear()
            activeWatcherElectrumUrls.clear()
            activeWatcherWalletIds.clear()
            retryingWatcherStarts.clear()
            trackedWalletIds.clear()
            lastPersistedHwSnapshots.clear()
            persistedActivityIds.clear()
            emittedReceivedTxIds.clear()
            _watcherData.update { emptyMap() }
        }
        trezorRepo.resetState()
        jadeRepo.resetState()
    }

    /** Pairing-code request raised by the device during connect; the UI shows the Pair Device sheet. */
    val needsPairingCode = trezorRepo.needsPairingCode

    /** Identity of the active request, incremented for each pairing-code callback. */
    val pairingCodeRequestId = trezorRepo.pairingCodeRequestId

    fun submitPairingCode(code: String) = trezorRepo.submitPairingCode(code)

    fun cancelPairingCode() = trezorRepo.cancelPairingCode()

    /** Device discovery and connection state of every vendor, merged for the Connect Hardware flow. */
    val deviceState: StateFlow<HwDeviceState> = combine(trezorRepo.state, jadeRepo.state) { trezor, jade ->
        val trezorState = trezor.toHwDeviceState()
        val jadeState = jade.toHwDeviceState()
        HwDeviceState(
            isScanning = trezorState.isScanning || jadeState.isScanning,
            isConnecting = trezorState.isConnecting || jadeState.isConnecting,
            isAutoReconnecting = trezorState.isAutoReconnecting || jadeState.isAutoReconnecting,
            isUnlocking = jadeState.isUnlocking,
            knownDevices = (trezorState.knownDevices + jadeState.knownDevices).toImmutableList(),
            nearbyDevices = (trezorState.nearbyDevices + jadeState.nearbyDevices).toImmutableList(),
            connected = trezorState.connected ?: jadeState.connected,
            error = trezorState.error ?: jadeState.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, HwDeviceState())

    /**
     * Scans every vendor for nearby unpaired devices; results land in [deviceState]'s nearbyDevices.
     * USB is enumerated for both vendors every time, while the Bluetooth part alternates between
     * them: Android throttles apps that start scans too often, and a scan drops any open Bluetooth
     * link, so none runs while either vendor holds one. Succeeds when either vendor's scan does.
     */
    suspend fun scan(
        includeBluetooth: Boolean = true,
    ): Result<List<HwNearbyDevice>> = withContext(ioDispatcher) {
        val bluetoothVendor = scanBluetoothVendor
        scanBluetoothVendor = when (bluetoothVendor) {
            HwWalletVendor.TREZOR -> HwWalletVendor.BLOCKSTREAM
            HwWalletVendor.BLOCKSTREAM -> HwWalletVendor.TREZOR
        }
        val bluetoothFree = includeBluetooth && !hasOpenBluetoothSession()
        val trezor = trezorRepo.scan(includeBluetooth = bluetoothFree && bluetoothVendor == HwWalletVendor.TREZOR)
        val jade = jadeRepo.scan(includeBluetooth = bluetoothFree && bluetoothVendor == HwWalletVendor.BLOCKSTREAM)
        if (trezor.isFailure && jade.isFailure) {
            return@withContext Result.failure(checkNotNull(trezor.exceptionOrNull()))
        }
        val found = trezor.getOrDefault(emptyList()).map { it.toHwNearbyDevice() } +
            jade.getOrDefault(emptyList()).map { it.toHwNearbyDevice() }
        Result.success(found)
    }

    private suspend fun hasOpenBluetoothSession(): Boolean {
        val connected = deviceState.value.connected ?: return false
        return devicesForDeviceId(connected.id).any { it.transportType == TransportType.BLUETOOTH } ||
            connected.id.startsWith("ble:")
    }

    private suspend fun devicesForDeviceId(deviceId: String): List<KnownDevice> =
        hwWalletStore.loadKnownDevices().filter { it.id == deviceId || it.path == deviceId }

    suspend fun hasKnownDevice(deviceId: String, vendor: HwWalletVendor? = null): Boolean = when (vendor) {
        HwWalletVendor.TREZOR -> trezorRepo.hasKnownDevice(deviceId)
        // A USB path is renumbered on every plug, so any paired USB Jade claims a plugged-in one.
        HwWalletVendor.BLOCKSTREAM -> jadeRepo.hasKnownUsbDevice(deviceId)
        null -> trezorRepo.hasKnownDevice(deviceId) || jadeRepo.hasKnownDevice(deviceId)
    }

    /** Connects and pairs a discovered device, persisting it as a watch-only known device. */
    suspend fun connect(
        deviceId: String,
        vendor: HwWalletVendor = HwWalletVendor.TREZOR,
    ): Result<HwConnectedDevice> = when (vendor) {
        HwWalletVendor.TREZOR -> {
            trezorRepo.resetWalletSelection()
            trezorRepo.connect(deviceId).map { features ->
                trezorRepo.state.value.connected?.toHwConnectedDevice() ?: features.toHwConnectedDevice(deviceId)
            }
        }
        HwWalletVendor.BLOCKSTREAM -> jadeRepo.connect(deviceId).map { it.toHwConnectedDevice() }
    }

    /**
     * Opens the passphrase (hidden) wallet of an already paired device and watches it as its own
     * identity, returning its wallet id. The passphrase is bound to a fresh Trezor session and is
     * never persisted; re-entering it is what makes the wallet reachable again.
     *
     * Re-entering a passphrase that is already watched updates that entry rather than adding a
     * second one, and reports [HwPassphraseAlreadyAddedError] so the UI can say so.
     */
    suspend fun connectWithPassphrase(deviceId: String, passphrase: String): Result<String> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                // A device with passphrase protection turned off ignores the passphrase and simply
                // reopens the standard wallet, which would surface as "already added" and leave the
                // user retyping a passphrase that can never take effect. Only the device can say
                // that though: with no session there is nothing to ask, and sending the user to
                // enable a setting they already have on helps nobody.
                if (jadeRepo.state.value.connected?.matches(deviceId) == true) throw HwPassphraseDisabledError()
                val features = trezorRepo.state.value.connectedDevice()
                    ?: throw AppError("Lost the session with device '$deviceId' before reading its wallet")
                if (features.passphraseProtection != true) throw HwPassphraseDisabledError()
                val watchedWalletIds = hwWalletStore.loadKnownDevices().mapNotNull { it.resolvedWalletId() }.toSet()
                trezorRepo.setWalletMode(TrezorWalletMode.PASSPHRASE_HOST, passphrase).getOrThrow()
                val walletId = requireNotNull(trezorRepo.state.value.connectedWalletId()) {
                    "Could not read the accounts of the passphrase wallet from device '$deviceId'"
                }
                if (walletId in watchedWalletIds) throw HwPassphraseAlreadyAddedError()
                walletId
            }
        }

    /** Reconnects a known paired wallet so its session is live for on-device signing. */
    suspend fun reconnect(
        walletId: String,
        forceSession: Boolean = false,
    ): Result<HwConnectedDevice> = withContext(ioDispatcher) {
        runSuspendCatching {
            val deviceId = transportDeviceId(walletId)
            when (vendorOf(walletId)) {
                HwWalletVendor.TREZOR -> trezorRepo.connectKnownDevice(deviceId, forceSession = forceSession)
                    .getOrThrow()
                    .toHwConnectedDevice(deviceId)
                HwWalletVendor.BLOCKSTREAM -> jadeRepo.connectKnownDevice(deviceId, forceSession = forceSession)
                    .getOrThrow()
                    .toHwConnectedDevice()
            }
        }
    }

    /**
     * Makes the device session belong to [walletId], not merely to its transport. A device holds
     * one identity open at a time, so a session opened for another wallet on the same device would
     * otherwise be accepted and sign with the wrong seed. The standard wallet needs no secret to
     * reopen; a passphrase wallet does, which the caller has to collect.
     */
    suspend fun ensureConnected(walletId: String): Result<HwConnectedDevice> = withContext(ioDispatcher) {
        runSuspendCatching {
            val deviceId = transportDeviceId(walletId)
            if (vendorOf(walletId) == HwWalletVendor.BLOCKSTREAM) {
                val connected = jadeRepo.ensureConnected(deviceId).getOrThrow()
                val opened = connected.walletId
                if (opened != null && opened != walletId) {
                    throw AppError("Device '$deviceId' is not holding wallet '$walletId'")
                }
                return@runSuspendCatching connected.toHwConnectedDevice()
            }
            val features = trezorRepo.ensureConnected(deviceId).getOrThrow().toHwConnectedDevice(deviceId)
            if (trezorRepo.state.value.connectedWalletId().isIdentityOf(walletId)) {
                return@runSuspendCatching features
            }

            Logger.info("Reopening '$walletId': session belongs to another identity", context = TAG)
            if (devicesForWallet(walletId).any { it.passphraseProtected }) throw HwPassphraseRequiredError()

            val reopened = trezorRepo.setWalletMode(TrezorWalletMode.STANDARD).getOrThrow()
            if (!trezorRepo.state.value.connectedWalletId().isIdentityOf(walletId)) {
                // Passphrase wallets already returned above, so this one has none to ask for: the
                // device simply is not holding it, which is a reconnect failure.
                throw AppError("Device '$deviceId' is not holding wallet '$walletId'")
            }
            reopened.toHwConnectedDevice(deviceId)
        }
    }

    private suspend fun String?.isIdentityOf(walletId: String): Boolean = when {
        this == walletId -> true
        this != null -> false
        // A session whose accounts could not be read reports no identity. The standard wallet
        // tolerates that, since reopening it proves nothing either; a hidden wallet is only ever
        // opened by proving its identity, so an unresolved session is never one of them.
        else -> devicesForWallet(walletId).none { it.passphraseProtected }
    }

    /**
     * Whether reaching [walletId] needs the passphrase again. The device only holds one hidden
     * wallet open at a time and forgets the passphrase with the session, so a passphrase wallet
     * that is not the live session cannot be reconnected — or signed with — without it.
     */
    suspend fun needsPassphrase(walletId: String): Boolean = withContext(ioDispatcher) {
        val devices = devicesForWallet(walletId)
        devices.any { it.passphraseProtected } && trezorRepo.state.value.connectedWalletId() != walletId
    }

    private fun HwWalletVendor.requirePassphraseSupport() {
        if (this == HwWalletVendor.BLOCKSTREAM) throw HwPassphraseDisabledError()
    }

    /**
     * Reopens a watched passphrase wallet for signing. A wrong passphrase is not rejected by the
     * device — it silently derives a different wallet — so the reopened session is only accepted
     * when its accounts resolve back to [walletId]; anything else is torn down again and reported
     * as [HwPassphraseMismatchError] rather than signing from the wrong wallet.
     */
    suspend fun reconnectWithPassphrase(walletId: String, passphrase: String): Result<Unit> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                vendorOf(walletId).requirePassphraseSupport()
                val deviceId = transportDeviceId(walletId)
                val watchedBefore = hwWalletStore.loadKnownDevices().mapNotNull { it.resolvedWalletId() }.toSet()
                // Not setWalletMode: the session this reopens is usually already gone, either
                // because the app restarted or because a wrong passphrase closed it.
                trezorRepo.connectWithWalletMode(deviceId, TrezorWalletMode.PASSPHRASE_HOST, passphrase).getOrThrow()
                val opened = trezorRepo.state.value.connectedWalletId()
                if (opened == walletId) return@runSuspendCatching

                if (opened == null) {
                    // The session opened but its accounts could not be read, so nothing says the
                    // passphrase was wrong. Tear it down: an unresolved session is refused by every
                    // later hidden-wallet call anyway.
                    trezorRepo.disconnectStaleSession(deviceId)
                    throw AppError("Could not read the accounts of the reopened wallet on '$deviceId'")
                }

                Logger.warn("Rejected hardware session for '$walletId': opened wallet '$opened'", context = TAG)
                // Reading the accounts of the wrong wallet already stored it; a mistyped passphrase
                // must not leave a stray watch-only wallet behind. Its backup data is kept: the wallet
                // is a real one the user owns, and storing it has already consumed any name restored
                // for it into the entry about to be forgotten.
                if (opened !in watchedBefore) {
                    removeDevice(opened, keepBackupData = true)
                        .onFailure { Logger.warn("Failed to drop unwatched wallet '$opened'", it, context = TAG) }
                }
                trezorRepo.disconnectStaleSession(deviceId)
                throw HwPassphraseMismatchError()
            }
        }

    suspend fun isKnownBluetoothDevice(walletId: String): Boolean = withContext(ioDispatcher) {
        val deviceId = transportDeviceIdOrNull(walletId) ?: return@withContext false
        when (vendorOf(walletId)) {
            HwWalletVendor.TREZOR -> trezorRepo.isKnownBluetoothDevice(deviceId)
            HwWalletVendor.BLOCKSTREAM -> jadeRepo.isKnownBluetoothDevice(deviceId)
        }
    }

    suspend fun getFundingAccount(
        walletId: String,
        addressType: HwFundingAddressType = HwFundingAddressType.DEFAULT,
    ): Result<HwFundingAccount> = withContext(ioDispatcher) {
        runSuspendCatching {
            val devices = devicesForWallet(walletId)
            val target = requireNotNull(devices.firstOrNull { it.xpubs.containsKey(addressType.settingsKey) }) {
                "Hardware wallet '$walletId' has no '${addressType.settingsKey}' account"
            }
            val xpub = requireNotNull(target.xpubs[addressType.settingsKey]) {
                "Hardware wallet '$walletId' has no '${addressType.settingsKey}' account"
            }
            val balanceSats = _watcherData.value
                .values
                .filter { it.addressType == addressType && it.walletId == walletId }
                .fold(0uL) { acc, watcher -> acc + watcher.balanceSats }
            when (target.vendor) {
                HwWalletVendor.TREZOR -> HwFundingAccount.Trezor(
                    xpub = xpub,
                    addressType = addressType,
                    balanceSats = balanceSats,
                )
                HwWalletVendor.BLOCKSTREAM -> HwFundingAccount.Jade(
                    xpub = xpub,
                    addressType = addressType,
                    balanceSats = balanceSats,
                )
            }
        }
    }

    /** Resolves the next unused external address from watcher state, falling back to an account scan. */
    suspend fun getReceiveAddress(
        walletId: String,
        addressType: HwFundingAddressType = HwFundingAddressType.DEFAULT,
    ): Result<HwReceiveAddress> = withContext(ioDispatcher) {
        runSuspendCatching {
            watcherReceiveAddress(walletId, addressType)?.let { return@runSuspendCatching it }
            val account = getFundingAccount(walletId, addressType).getOrThrow()
            val accountInfo = trezorRepo.getAccountInfo(
                extendedKey = account.xpub,
                network = Env.network.toCoreNetwork(),
                scriptType = account.accountType,
            ).getOrThrow()
            val unused = requireNotNull(accountInfo.account.addresses.unused.firstOrNull()) {
                "No unused external address returned for hardware wallet '$walletId'"
            }
            val scannedAddress = HwReceiveAddress(
                address = unused.address,
                path = unused.path,
                addressType = addressType,
            )
            watcherReceiveAddress(walletId, addressType) ?: scannedAddress
        }
    }

    fun observeReceiveAddress(
        walletId: String,
        addressType: HwFundingAddressType = HwFundingAddressType.DEFAULT,
    ): Flow<HwReceiveAddress?> = _watcherData
        .map { watcherData -> watcherData.receiveAddress(walletId, addressType) }
        .distinctUntilChanged()

    private fun watcherReceiveAddress(
        walletId: String,
        addressType: HwFundingAddressType,
    ): HwReceiveAddress? = _watcherData.value.receiveAddress(walletId, addressType)

    /** Displays the exact address currently shown by Bitkit on the device and rejects a mismatch. */
    suspend fun verifyReceiveAddress(
        walletId: String,
        receiveAddress: HwReceiveAddress,
    ): Result<Unit> = withContext(ioDispatcher) {
        if (vendorOf(walletId) == HwWalletVendor.BLOCKSTREAM) {
            return@withContext verifyJadeReceiveAddress(walletId, receiveAddress)
        }
        runSuspendCatching {
            suspend fun readOnDevice() = trezorRepo.getAddress(
                path = receiveAddress.path,
                showOnTrezor = true,
                scriptType = receiveAddress.addressType.trezorScriptType,
                coin = Env.network.toTrezorCoinType(),
            ).getOrThrow()

            ensureConnected(walletId).getOrThrow()
            val firstAttempt = runSuspendCatching { readOnDevice() }
            val firstError = firstAttempt.exceptionOrNull()
            val response = if (firstError == null) {
                firstAttempt.getOrThrow()
            } else {
                if (!firstError.isHwSessionFailure()) throw firstError
                disconnectStaleSession(walletId).getOrThrow()
                ensureConnected(walletId).getOrThrow()
                runSuspendCatching { readOnDevice() }
                    .onFailure {
                        if (it.isHwSessionFailure()) {
                            disconnectStaleSession(walletId).getOrThrow()
                        }
                    }
                    .getOrThrow()
            }
            if (response.address != receiveAddress.address) {
                throw HwReceiveAddressMismatchError(
                    "Address verification failed: Trezor returned '${response.address}' for " +
                        "'${receiveAddress.path}', expected '${receiveAddress.address}'"
                )
            }
        }
    }

    /** Jade compares on the device itself: it shows the address and answers with a mismatch error. */
    private suspend fun verifyJadeReceiveAddress(
        walletId: String,
        receiveAddress: HwReceiveAddress,
    ): Result<Unit> = runSuspendCatching {
        suspend fun verifyOnDevice() = jadeRepo.verifyAddress(
            addressType = receiveAddress.addressType,
            derivationPath = receiveAddress.path,
            expectedAddress = receiveAddress.address,
        ).getOrThrow()

        ensureConnected(walletId).getOrThrow()
        runSuspendCatching { verifyOnDevice() }
            .recoverCatching { error ->
                if (!error.isHwSessionFailure()) throw error
                disconnectStaleSession(walletId).getOrThrow()
                ensureConnected(walletId).getOrThrow()
                runSuspendCatching { verifyOnDevice() }
                    .onFailure { if (it.isHwSessionFailure()) disconnectStaleSession(walletId).getOrThrow() }
                    .getOrThrow()
            }
            .recoverCatching { error ->
                if (error !is JadeException.AddressMismatch) throw error
                throw HwReceiveAddressMismatchError(
                    "Address verification failed: Jade returned '${error.returned}' for " +
                        "'${receiveAddress.path}', expected '${receiveAddress.address}'"
                )
            }
            .getOrThrow()
    }

    /** Composes the exact on-chain funding payment before prompting for the Trezor signature. */
    suspend fun composeFundingTransaction(
        walletId: String,
        address: String,
        sats: ULong,
        satsPerVByte: ULong,
    ): Result<HwFundingTransaction> = withContext(ioDispatcher) {
        runSuspendCatching {
            val account = getFundingAccount(walletId).getOrThrow()
            val network = Env.network.toCoreNetwork()
            val outputs = listOf(ComposeOutput.Payment(address = address, amountSats = sats))
            val composed = when (account) {
                is HwFundingAccount.Trezor -> trezorRepo.composeTransaction(
                    extendedKey = account.xpub,
                    outputs = outputs,
                    feeRates = listOf(satsPerVByte.toFloat()),
                    network = network,
                    accountType = account.accountType,
                    coinSelection = CoinSelection.BRANCH_AND_BOUND,
                ).getOrThrow()
                // The PSBT must carry the Jade's key origins, or the device signs nothing.
                is HwFundingAccount.Jade -> {
                    ensureConnected(walletId).getOrThrow()
                    val fingerprint = jadeRepo.getMasterFingerprint().getOrThrow()
                    trezorRepo.composeTransactionOffline(
                        extendedKey = account.xpub,
                        outputs = outputs,
                        feeRates = listOf(satsPerVByte.toFloat()),
                        network = network,
                        accountType = account.accountType,
                        coinSelection = CoinSelection.BRANCH_AND_BOUND,
                        fingerprint = fingerprint,
                    ).getOrThrow()
                }
            }
            val success = composed.filterIsInstance<ComposeResult.Success>().firstOrNull()
                ?: throw AppError(
                    composed.filterIsInstance<ComposeResult.Error>().firstOrNull()?.error
                        ?: "Failed to compose hardware transfer"
                )
            HwFundingTransaction(
                psbt = success.psbt,
                miningFeeSats = success.fee,
                feeRate = success.feeRate,
                totalSpent = success.totalSpent,
                satsPerVByte = satsPerVByte,
            )
        }
    }

    /** Estimates the exact funding fee from the public account key without opening the device. */
    suspend fun estimateFundingMiningFee(
        walletId: String,
        address: String,
        sats: ULong,
        satsPerVByte: ULong,
    ): Result<ULong> = withContext(ioDispatcher) {
        runSuspendCatching {
            composeFundingOffline(
                walletId = walletId,
                output = ComposeOutput.Payment(address = address, amountSats = sats),
                satsPerVByte = satsPerVByte,
            ).fee
        }
    }

    /** Exact amount available after the coin-selection fee, computed offline from the account xpub. */
    suspend fun maxSpendableFunding(
        walletId: String,
        address: String,
        satsPerVByte: ULong,
    ): Result<ULong> = withContext(ioDispatcher) {
        runSuspendCatching {
            val success = composeFundingOffline(
                walletId = walletId,
                output = ComposeOutput.SendMax(address = address),
                satsPerVByte = satsPerVByte,
            )
            success.totalSpent.safe() - success.fee.safe()
        }
    }

    private suspend fun composeFundingOffline(
        walletId: String,
        output: ComposeOutput,
        satsPerVByte: ULong,
    ): ComposeResult.Success {
        val account = getFundingAccount(walletId).getOrThrow()
        val composed = trezorRepo.composeTransactionOffline(
            extendedKey = account.xpub,
            outputs = listOf(output),
            feeRates = listOf(satsPerVByte.toFloat()),
            network = Env.network.toCoreNetwork(),
            accountType = account.accountType,
            coinSelection = CoinSelection.BRANCH_AND_BOUND,
        ).getOrThrow()
        return composed.filterIsInstance<ComposeResult.Success>().firstOrNull()
            ?: throw AppError(
                composed.filterIsInstance<ComposeResult.Error>().firstOrNull()?.error
                    ?: "Failed to compose hardware wallet payment"
            )
    }

    /** Signs a composed funding payment on the device. */
    suspend fun signFunding(
        walletId: String,
        funding: HwFundingTransaction,
    ): Result<HwFundingSignedTx> = withContext(ioDispatcher) {
        runSuspendCatching {
            val serializedTx = when (vendorOf(walletId)) {
                HwWalletVendor.TREZOR -> signTrezorFunding(walletId, funding)
                HwWalletVendor.BLOCKSTREAM -> jadeRepo.signPsbt(funding.psbt).getOrElse {
                    if (it.isHwSessionFailure()) disconnectStaleSession(walletId)
                    throw it
                }.serializedTx
            }
            HwFundingSignedTx(
                serializedTx = serializedTx,
                miningFeeSats = funding.miningFeeSats,
                feeRate = ceil(funding.feeRate.toDouble()).toULong(),
                totalSpent = funding.totalSpent,
            )
        }
    }

    private suspend fun signTrezorFunding(walletId: String, funding: HwFundingTransaction): String {
        // The session can change between connecting and signing, and signing the wrong seed
        // would produce signatures that do not match the inputs being spent.
        if (!trezorRepo.state.value.connectedWalletId().isIdentityOf(walletId)) {
            throw HwPassphraseRequiredError()
        }
        return trezorRepo.signTxFromPsbt(
            psbtBase64 = funding.psbt,
            network = Env.network.toTrezorCoinType(),
        ).getOrElse {
            if (it.isHwSessionFailure()) {
                transportDeviceIdOrNull(walletId)?.let { deviceId -> trezorRepo.disconnectStaleSession(deviceId) }
            }
            throw it
        }.serializedTx
    }

    /** Broadcasts a signed funding payment without requiring the hardware device. */
    suspend fun broadcastFunding(
        signedTx: HwFundingSignedTx,
    ): Result<HwFundingBroadcastResult> = withContext(ioDispatcher) {
        runSuspendCatching {
            val txId = trezorRepo.broadcastRawTx(serializedTx = signedTx.serializedTx).getOrThrow()
            HwFundingBroadcastResult(
                txId = txId,
                miningFeeSats = signedTx.miningFeeSats,
                feeRate = signedTx.feeRate,
                totalSpent = signedTx.totalSpent,
            )
        }
    }

    suspend fun disconnectStaleSession(walletId: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val deviceId = transportDeviceIdOrNull(walletId) ?: return@runSuspendCatching
            when (vendorOf(walletId)) {
                HwWalletVendor.TREZOR -> trezorRepo.disconnectStaleSession(deviceId).getOrThrow()
                HwWalletVendor.BLOCKSTREAM -> jadeRepo.disconnectStaleSession(deviceId).getOrThrow()
            }
        }
    }

    /**
     * Persists the Bitkit-side funds label for a paired wallet. Applied to every entry sharing the
     * same wallet identity so the same device paired over both transports renames consistently.
     */
    suspend fun setDeviceLabel(walletId: String, label: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            val devices = hwWalletStore.loadKnownDevices()
            val target = requireNotNull(devices.find { it.resolvedWalletId() == walletId }) {
                "Unknown hardware wallet '$walletId'"
            }
            val customLabel = label.trim().take(DEVICE_LABEL_MAX_LENGTH).ifEmpty { null }
            val updated = devices.map {
                if (it.walletKey == target.walletKey) it.copy(customLabel = customLabel) else it
            }
            hwWalletStore.saveKnownDevices(updated)
        }
    }

    /**
     * Removes a paired hardware wallet: stops its watchers and forgets every device entry that
     * tracks the same wallet identity. The same physical device paired over both bluetooth and usb
     * is stored once per transport but shares an xpub-derived identity, so forgetting a single id
     * would leave the tile reappearing through the other transport. Other identities on the same
     * device — the standard wallet, or another passphrase wallet — are left paired.
     *
     * @param keepBackupData whether to carry the wallet's name and tags in the backup, so re-pairing
     * the device restores them. Off by default: only a user removing a wallet is asked, and internal
     * cleanup of a wallet the user never meant to watch must not leave its data behind.
     */
    suspend fun removeDevice(
        walletId: String,
        keepBackupData: Boolean = false,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            watcherMutex.withLock {
                val knownDevices = hwWalletStore.loadKnownDevices()
                val targets = knownDevices.filter { it.resolvedWalletId() == walletId }
                // Without an entry there is nothing to forget, and the check below would pass on an
                // empty set: report the failure instead of telling the user the wallet was removed.
                require(targets.isNotEmpty()) { "Unknown hardware wallet '$walletId'" }
                // Read before the deletion below, which takes the tags with the activities they are on.
                val keptName = targets.firstNotNullOfOrNull { it.customLabel?.takeIf(String::isNotBlank) }
                    .takeIf { keepBackupData }
                // Nothing has been deleted yet, so failing here costs nothing and keeps the choice with
                // the user: retry, or remove the wallet without keeping its data.
                val keptTagMetadata = when {
                    keepBackupData -> activityRepo.getTagMetadataForWallet(walletId)
                        .getOrElse { throw HwBackupDataUnreadableError(it) }
                    else -> emptyList()
                }
                activeWatchers.toList()
                    .filter { it.toWalletId() == walletId }
                    .forEach {
                        if (!stopActiveWatcherLocked(it)) {
                            throw AppError("Failed to stop hardware wallet watcher '$it'")
                        }
                    }
                activityRepo.deleteForWallet(walletId).getOrThrow()
                // Written back only now: the deletion above drops the wallet's stored tag metadata
                // along with its activities. Core re-attaches these once the watcher recreates them,
                // so re-pairing the device brings the tags back.
                if (keptTagMetadata.isNotEmpty()) {
                    // Nothing to roll back to at this point, and the wallet is already half removed,
                    // so a failure here loses the tags rather than failing the removal.
                    preActivityMetadataRepo.upsertPreActivityMetadata(keptTagMetadata)
                }
                trackedWalletIds -= walletId
                lastPersistedHwSnapshots -= walletId
                // The name is stored in the same write that forgets the entries carrying it, so the
                // store never publishes a device list still holding this wallet. A separate write would,
                // and a reconcile reading it restarts the watcher of the wallet being removed.
                val failures = targets.mapNotNull { device ->
                    val pendingName = PendingNameUpdate(walletId, keptName)
                    when (device.vendor) {
                        HwWalletVendor.TREZOR -> trezorRepo.forgetDevice(
                            device.id,
                            walletKey = device.walletKey,
                            pendingName = pendingName,
                        )
                        HwWalletVendor.BLOCKSTREAM -> jadeRepo.forgetDevice(
                            device.id,
                            walletKey = device.walletKey,
                            pendingName = pendingName,
                        )
                    }.exceptionOrNull()
                }
                val remaining = hwWalletStore.loadKnownDevices()
                failures.firstOrNull()?.let { throw it }
                check(remaining.none { it.resolvedWalletId() == walletId }) {
                    "Hardware wallet '$walletId' still present after removal"
                }
            }
        }.onFailure {
            watcherSyncRequests.tryEmit(Unit)
        }
    }

    val wallets: StateFlow<ImmutableList<HwWallet>> = combine(
        hwWalletStore.data,
        deviceState,
        _watcherData,
    ) { data, hwState, watcherData ->
        // The same physical device paired over both bluetooth and usb is stored as two
        // entries with different transport-level ids; its xpubs are the cross-transport
        // identity, so group by them to show one wallet and count its balance once. A
        // passphrase wallet derives different xpubs, so it groups into its own wallet.
        data.knownDevices
            .filter { it.xpubs.isNotEmpty() }
            .groupBy { it.walletKey }
            .mapNotNull { (_, devices) ->
                val walletId = devices.firstNotNullOfOrNull { it.resolvedWalletId() } ?: return@mapNotNull null
                val connectedDevice = devices.find { it.id == hwState.connectedDeviceId() }
                val device = connectedDevice ?: devices.maxBy { it.lastConnectedAt }
                val ids = devices.map { it.id }.toSet()
                val walletWatchers = watcherData.values.filter { it.walletId == walletId }
                val fundingBalanceSats = walletWatchers
                    .filter { it.addressType == HwFundingAddressType.DEFAULT }
                    .fold(0uL) { acc, watcher -> acc + watcher.balanceSats }
                HwWallet(
                    id = walletId,
                    name = device.displayName,
                    model = device.model,
                    transportType = device.transportType,
                    // A device holding several passphrase wallets only has a session for one of
                    // them, and only that identity can sign; mark the others disconnected. Sessions
                    // opened before an identity was resolved report no wallet and stay inclusive.
                    isConnected = connectedDevice != null &&
                        hwState.connectedWalletId().let { it == null || it == walletId },
                    balanceSats = walletWatchers.fold(0uL) { acc, watcher -> acc + watcher.balanceSats },
                    activities = walletWatchers
                        .toMergedActivities()
                        .toImmutableList(),
                    fundingBalanceSats = fundingBalanceSats,
                    deviceIds = ids.toImmutableSet(),
                    passphraseProtected = devices.any { it.passphraseProtected },
                    vendor = device.vendor,
                )
            }
            .toImmutableList()
    }.stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    val walletsLoaded: StateFlow<Boolean> = hwWalletStore.data
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val totalSats: StateFlow<ULong> = wallets
        .map { wallets -> wallets.fold(0uL) { acc, wallet -> acc + wallet.balanceSats } }
        .stateIn(scope, SharingStarted.Eagerly, 0uL)

    val activities: StateFlow<ImmutableList<Activity>> = combine(
        hwWalletStore.data,
        _watcherData,
    ) { data, watcherData ->
        val knownWalletIds = data.knownDevices
            .filter { it.xpubs.isNotEmpty() }
            .mapNotNull { it.resolvedWalletId() }
            .toSet()
        watcherData.values
            .filter { it.walletId in knownWalletIds }
            .toMergedActivities()
            .toImmutableList()
    }
        .stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    init {
        observeWatcherEvents()
        syncWatchers()
    }

    private fun observeWatcherEvents() {
        scope.launch {
            trezorRepo.watcherEvents.collect { (watcherId, event) ->
                if (event !is WatcherEvent.TransactionsChanged) return@collect
                val receivedTxs = watcherMutex.withLock {
                    val walletId = activeWatcherWalletIds[watcherId] ?: return@withLock emptyList()
                    val activities = event.activities
                        .filter { it.walletId() == walletId }
                        .toImmutableList()
                    val transactionDetails = event.transactionDetails
                        .filter { it.walletId == walletId }
                        .toImmutableList()
                    val addressType = watcherId.toFundingAddressType() ?: return@withLock emptyList()
                    val watcher = HwWatcherData(
                        walletId = walletId,
                        addressType = addressType,
                        balanceSats = event.balance.total,
                        activities = activities,
                        receiveAddress = event.toReceiveAddress(addressType),
                    )
                    _watcherData.update { it + (watcherId to watcher) }
                    val previousIds = persistedActivityIds.getOrPut(watcherId) {
                        activities.map { it.scopedId() }.toSet()
                    }
                    val snapshot = HwSnapshot(
                        activities = activities,
                        transactionDetails = transactionDetails,
                    )
                    val snapshotCacheKey = snapshot.toCacheKey()
                    lastPersistedHwSnapshots[walletId]
                        ?.takeIf { it.source == snapshotCacheKey && !it.hasRetainedPendingSend() }
                        ?.let {
                            _watcherData.update { data ->
                                data + (watcherId to watcher.copy(activities = it.activities))
                            }
                            return@withLock emptyList()
                        }

                    val persistedActivities = activityRepo.persistHwSnapshot(
                        walletId = walletId,
                        activities = activities,
                        transactionDetails = transactionDetails,
                    ).getOrElse { return@withLock emptyList() }
                    val immutablePersistedActivities = persistedActivities.toImmutableList()
                    lastPersistedHwSnapshots[walletId] = PersistedHwSnapshot(
                        source = snapshotCacheKey,
                        activities = immutablePersistedActivities,
                    )
                    val persistedWatcher = watcher.copy(activities = immutablePersistedActivities)
                    val updatedWatcherData = _watcherData.value + (watcherId to persistedWatcher)
                    _watcherData.update { updatedWatcherData }
                    persistedActivityIds[watcherId] = persistedActivities.map { it.scopedId() }.toSet()
                    buildReceivedTxs(previousIds, persistedActivities, updatedWatcherData)
                }
                receivedTxs.forEach { _receivedTxs.emit(it) }
            }
        }
    }

    /**
     * The first event after a watcher starts delivers the full transaction history;
     * treat it as the baseline so only transactions arriving while watching are emitted.
     */
    private fun buildReceivedTxs(
        previousActivityIds: Set<String>,
        activities: List<Activity>,
        watcherData: Map<String, HwWatcherData>,
    ): List<HwWalletReceivedTx> {
        val mergedActivities = watcherData.values.toList().toMergedActivities()
        return activities.filterIsInstance<Activity.Onchain>()
            .filter { it.v1.txType == PaymentType.RECEIVED }
            .mapNotNull { onchain ->
                val scopedId = onchain.scopedId()
                if (scopedId in previousActivityIds || !emittedReceivedTxIds.add(scopedId)) return@mapNotNull null
                val sats = mergedActivities.findOnchain(onchain.v1.txId, onchain.v1.walletId)?.v1?.value
                    ?: onchain.v1.value
                HwWalletReceivedTx(
                    txid = onchain.v1.txId,
                    sats = sats,
                    walletId = onchain.v1.walletId,
                )
            }
    }

    private fun syncWatchers() {
        scope.launch {
            val desiredWatchers = combine(
                hwWalletStore.data,
                settingsStore.data
                    .map { WatcherSettings(it.addressTypesToMonitor.toSet(), it.electrumServer) }
                    .distinctUntilChanged(),
            ) { data, settings ->
                data.knownDevices to settings
            }

            combine(
                desiredWatchers,
                watcherSyncRequests.onStart { emit(Unit) },
            ) { desired, _ ->
                desired
            }.collect { (knownDevices, watcherSettings) ->
                reconcileWatchers(knownDevices, watcherSettings)
            }
        }
    }

    private suspend fun reconcileWatchers(
        knownDevices: List<KnownDevice>,
        watcherSettings: WatcherSettings,
    ) {
        watcherMutex.withLock {
            // Read under the lock: a removal deletes a wallet's activities while holding it, and this
            // set decides what to delete. Reading it first would let a removal complete in between and
            // then be undone here, taking the tag metadata it deliberately kept with it.
            val persistedWalletIds = activityRepo.getWalletIds().getOrDefault(emptySet())
                .filterNot { it == WalletScope.default }
                .toSet()
            val specs = knownDevices.toWatcherSpecs(watcherSettings.electrumUrl)
            val desiredIds = specs.map { it.watcherId }.toSet()
            val knownWalletIds = knownDevices.mapNotNull { it.resolvedWalletId() }.toSet()
            trackedWalletIds += persistedWalletIds
            // Only wallets that still have activities to clear. Core drops a wallet's tag metadata
            // along with its activities whether or not any matched, so cleaning up a wallet that has
            // none is not a no-op: it takes the metadata a removal deliberately kept.
            val removedWalletIds = (trackedWalletIds - knownWalletIds).intersect(persistedWalletIds)
            trackedWalletIds += knownWalletIds

            specs.forEach { spec ->
                val isActive = spec.watcherId in activeWatchers
                if (
                    isActive &&
                    activeWatcherElectrumUrls[spec.watcherId] == spec.electrumUrl &&
                    activeWatcherWalletIds[spec.watcherId] == spec.walletId
                ) {
                    return@forEach
                }
                if (isActive && !stopActiveWatcherLocked(spec.watcherId)) return@forEach

                trezorRepo.startWatcher(
                    watcherId = spec.watcherId,
                    extendedKey = spec.xpub,
                    network = Env.network.toCoreNetwork(),
                    accountType = spec.addressType.toAddressType()?.toAccountType(),
                    electrumUrl = spec.electrumUrl,
                    walletId = spec.walletId,
                ).onSuccess {
                    activeWatchers += spec.watcherId
                    activeWatcherElectrumUrls[spec.watcherId] = spec.electrumUrl
                    activeWatcherWalletIds[spec.watcherId] = spec.walletId
                    retryingWatcherStarts -= spec.watcherId
                }.onFailure {
                    Logger.warn("Retrying watcher '${spec.watcherId}' after start failure", it, context = TAG)
                    scheduleWatcherStartRetry(spec.watcherId)
                }
            }

            // A failed stop stays active so the next sync retries it; dropping it here
            // would leave the orphaned watcher feeding _watcherData as a ghost balance.
            (activeWatchers - desiredIds).forEach { stopActiveWatcherLocked(it) }

            removedWalletIds
                .filterNot { it in activeWatcherWalletIds.values }
                .forEach { walletId ->
                    activityRepo.deleteForWallet(walletId).onSuccess {
                        trackedWalletIds -= walletId
                        lastPersistedHwSnapshots -= walletId
                    }
                }
        }
    }

    private fun List<KnownDevice>.toWatcherSpecs(electrumUrl: String): List<WatcherSpec> = flatMap { device ->
        val walletId = device.resolvedWalletId() ?: return@flatMap emptyList()
        device.xpubs
            .filterKeys { it in SUPPORTED_WATCHER_ADDRESS_TYPES }
            .map { (addressType, xpub) ->
                WatcherSpec(
                    addressType = addressType,
                    xpub = xpub,
                    electrumUrl = electrumUrl,
                    walletId = walletId,
                )
            }
    }.distinctBy { it.watcherId }

    private suspend fun stopActiveWatcherLocked(watcherId: String): Boolean =
        trezorRepo.stopWatcher(watcherId).onSuccess {
            activeWatchers -= watcherId
            activeWatcherElectrumUrls -= watcherId
            activeWatcherWalletIds -= watcherId
            persistedActivityIds -= watcherId
            _watcherData.update { it - watcherId }
        }.isSuccess

    private fun scheduleWatcherStartRetry(watcherId: String) {
        if (!retryingWatcherStarts.add(watcherId)) return

        scope.launch {
            delay(WATCHER_START_RETRY_DELAY)
            watcherMutex.withLock {
                retryingWatcherStarts -= watcherId
            }
            watcherSyncRequests.emit(Unit)
        }
    }

    private fun KnownDevice.resolvedWalletId(): String? = walletId.takeIf { it.isNotBlank() } ?: when (vendor) {
        HwWalletVendor.TREZOR -> trezorRepo.deriveWalletId(xpubs)
        HwWalletVendor.BLOCKSTREAM -> jadeRepo.deriveWalletId(xpubs)
    }

    private fun List<HwWatcherData>.toMergedActivities(): List<Activity> =
        flatMap { it.activities }
            .groupBy { it.scopedId() }
            .values
            .map { it.mergedActivity() }
            .sortedByDescending { it.timestamp() }

    private fun List<Activity>.mergedActivity(): Activity {
        if (size == 1) return first()

        val onchainActivities = filterIsInstance<Activity.Onchain>()
        if (onchainActivities.size != size) return first()

        val base = onchainActivities.minBy { it.v1.timestamp }
        val received = onchainActivities.filter { it.v1.txType == PaymentType.RECEIVED }
            .fold(0uL) { acc, activity -> acc.safe() + activity.v1.value.safe() }
        val sent = onchainActivities.filter { it.v1.txType == PaymentType.SENT }
            .fold(0uL) { acc, activity -> acc.safe() + activity.v1.value.safe() }
        val fee = onchainActivities.maxOf { it.v1.fee }
        val feeRate = onchainActivities.maxOf { it.v1.feeRate }
        val txType = when {
            received > sent -> PaymentType.RECEIVED
            sent > received -> PaymentType.SENT
            else -> base.v1.txType
        }
        val value = when (txType) {
            PaymentType.RECEIVED -> received.safe() - sent.safe()
            PaymentType.SENT -> sent.safe() - received.safe()
        }

        return Activity.Onchain(
            base.v1.copy(
                txType = txType,
                value = value,
                fee = fee,
                feeRate = feeRate,
                address = onchainActivities.firstOrNull { it.v1.address.isNotBlank() }?.v1?.address.orEmpty(),
                confirmed = onchainActivities.any { it.v1.confirmed },
                isBoosted = onchainActivities.any { it.v1.isBoosted },
                boostTxIds = onchainActivities.flatMap { it.v1.boostTxIds }.distinct(),
                isTransfer = onchainActivities.any { it.v1.isTransfer },
                doesExist = onchainActivities.any { it.v1.doesExist },
                confirmTimestamp = onchainActivities.mapNotNull { it.v1.confirmTimestamp }.maxOrNull(),
                channelId = onchainActivities.firstNotNullOfOrNull { it.v1.channelId },
                transferTxId = onchainActivities.firstNotNullOfOrNull { it.v1.transferTxId },
                contact = onchainActivities.firstNotNullOfOrNull { it.v1.contact },
                createdAt = onchainActivities.mapNotNull { it.v1.createdAt }.minOrNull(),
                updatedAt = onchainActivities.mapNotNull { it.v1.updatedAt }.maxOrNull(),
                seenAt = onchainActivities.mapNotNull { it.v1.seenAt }.minOrNull(),
            )
        )
    }

    private fun List<Activity>.findOnchain(txid: String, walletId: String) = filterIsInstance<Activity.Onchain>()
        .firstOrNull { it.v1.txId == txid && it.v1.walletId == walletId }

    private data class WatcherSpec(
        val addressType: String,
        val xpub: String,
        val electrumUrl: String,
        val walletId: String,
    ) {
        // Keyed by wallet, not by device: a device holding several passphrase wallets would
        // otherwise collide on one watcher id per address type.
        val watcherId: String get() = "$walletId$WATCHER_ID_SEPARATOR$addressType"
    }

    private fun String.toWalletId(): String = substringBefore(WATCHER_ID_SEPARATOR)

    private fun String.toAddressTypeKey(): String = substringAfter(WATCHER_ID_SEPARATOR)

    private fun String.toFundingAddressType(): HwFundingAddressType? =
        HwFundingAddressType.entries.firstOrNull { it.settingsKey == toAddressTypeKey() }
}

private data class WatcherSettings(
    val monitoredTypes: Set<String>,
    val electrumUrl: String,
)

/**
 * Resolves the name shown for a hardware wallet: the Bitkit-side custom label if the user set one,
 * otherwise the device's own label; without one (or with the factory default that just mirrors the
 * model) it falls back to the vendor-prefixed model (e.g. "Safe 7" reads as "Trezor Safe 7"). Jade
 * models already carry their name ("Jade", "Jade Plus") and a Jade has no label of its own.
 */
fun resolveHwWalletName(
    label: String?,
    model: String?,
    customLabel: String? = null,
    vendor: HwWalletVendor = HwWalletVendor.TREZOR,
): String {
    customLabel?.takeIf { it.isNotBlank() }?.let { return it }
    if (vendor == HwWalletVendor.BLOCKSTREAM) return model?.takeIf { it.isNotBlank() } ?: "Jade"
    label?.takeIf { it != model }?.let { return it }
    val resolvedModel = model ?: return "Trezor"
    return if (resolvedModel.startsWith("Trezor")) resolvedModel else "Trezor $resolvedModel"
}

private val KnownDevice.displayName: String
    get() = resolveHwWalletName(label = label, model = model, customLabel = customLabel, vendor = vendor)

private fun TrezorFeatures.toHwConnectedDevice(deviceId: String) = HwConnectedDevice(
    vendor = HwWalletVendor.TREZOR,
    id = deviceId,
    label = label,
    model = model,
    walletId = null,
    passphraseProtection = passphraseProtection == true,
    isLocked = pinProtection == true && unlocked == false,
)

/** The device has passphrase protection turned off, so it cannot open a hidden wallet at all. */
class HwPassphraseDisabledError : AppError("Passphrase protection is off on this device")

/** The entered passphrase resolves to a wallet Bitkit already watches. */
class HwPassphraseAlreadyAddedError : AppError("Passphrase wallet already added")

/** The device session belongs to another identity, and only its passphrase can reopen this one. */
class HwPassphraseRequiredError : AppError("Passphrase needed to reopen this wallet")

/** The entered passphrase opened a different wallet than the one being signed from. */
class HwPassphraseMismatchError : AppError("Passphrase opened a different wallet")

class HwReceiveAddressMismatchError(message: String) : AppError(message)

/** The device has no wallet yet; it has to be created or restored on the device itself. */
class HwDeviceUninitializedError : AppError("Hardware device is not set up")

/**
 * A removal asked to keep the wallet's backup data, but its tags could not be read. Raised before
 * anything is deleted, so the wallet is untouched and the removal can be retried or repeated without
 * keeping the data.
 */
class HwBackupDataUnreadableError(cause: Throwable) : AppError("Could not read the backup data", cause)

private data class HwWatcherData(
    val walletId: String,
    val addressType: HwFundingAddressType,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
    val receiveAddress: HwReceiveAddress,
)

private fun Map<String, HwWatcherData>.receiveAddress(
    walletId: String,
    addressType: HwFundingAddressType,
): HwReceiveAddress? = values.firstOrNull {
    it.walletId == walletId && it.addressType == addressType
}?.receiveAddress

private fun WatcherEvent.TransactionsChanged.toReceiveAddress(
    addressType: HwFundingAddressType,
) = HwReceiveAddress(
    address = nextUnusedExternalAddress.address,
    path = nextUnusedExternalAddress.path,
    addressType = addressType,
)

private data class HwSnapshot(
    val activities: ImmutableList<Activity>,
    val transactionDetails: ImmutableList<TransactionDetails>,
)

private fun HwSnapshot.toCacheKey() = copy(
    activities = activities.map {
        when (it) {
            is Activity.Onchain if !it.v1.confirmed -> Activity.Onchain(it.v1.copy(timestamp = 0uL))
            else -> it
        }
    }.toImmutableList(),
)

private data class PersistedHwSnapshot(
    val source: HwSnapshot,
    val activities: ImmutableList<Activity>,
)

private fun PersistedHwSnapshot.hasRetainedPendingSend(): Boolean {
    val sourceIds = source.activities.map { it.scopedId() }.toSet()
    return activities.any {
        val activity = (it as? Activity.Onchain)?.v1 ?: return@any false
        it.scopedId() !in sourceIds &&
            activity.txType == PaymentType.SENT &&
            !activity.confirmed &&
            !activity.isTransfer &&
            activity.doesExist
    }
}
