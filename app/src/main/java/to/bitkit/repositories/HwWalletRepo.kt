package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.isTrezorUserCancellation
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.scopedId
import to.bitkit.ext.timestamp
import to.bitkit.ext.walletId
import to.bitkit.models.HwFundingAccount
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWallet
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.models.toAccountType
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toTrezorCoinType
import to.bitkit.services.TrezorWalletMode
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

/**
 * Production hardware-wallet business layer. Tracks paired Trezor devices as
 * watch-only balances by running one on-chain xpub watcher per (device, address type)
 * and exposing the aggregated per-device balance and activity to the UI.
 *
 * Built on top of [TrezorRepo], which owns the device list, connect orchestration
 * and the underlying watcher transport.
 */
@Suppress("TooManyFunctions")
@Singleton
class HwWalletRepo @Inject constructor(
    private val trezorRepo: TrezorRepo,
    private val activityRepo: ActivityRepo,
    private val hwWalletStore: HwWalletStore,
    private val settingsStore: SettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "HwWalletRepo"
        private const val WATCHER_ID_SEPARATOR = "|"
        private val WATCHER_START_RETRY_DELAY = 30.seconds
        const val DEVICE_LABEL_MAX_LENGTH = 50

        /** Trezor v1 (2.4.0) tracks native segwit only; multi-type HW support is follow-up work. */
        private val SUPPORTED_WATCHER_ADDRESS_TYPES = setOf(HwFundingAddressType.NATIVE_SEGWIT.settingsKey)
    }

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
    fun onTransportRestored(transportType: TransportType) = trezorRepo.onTransportRestored(transportType)

    fun onAppForegrounded() = trezorRepo.onAppForegrounded()

    fun warmUpKnownDevice(walletId: String) {
        scope.launch {
            transportDeviceIdOrNull(walletId)?.let { trezorRepo.warmUpKnownDevice(it) }
        }
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
        val connectedId = trezorRepo.state.value.connectedDeviceId()
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
    }

    /** Pairing-code request raised by the device during connect; the UI shows the Pair Device sheet. */
    val needsPairingCode = trezorRepo.needsPairingCode

    /** Identity of the active request, incremented for each pairing-code callback. */
    val pairingCodeRequestId = trezorRepo.pairingCodeRequestId

    fun submitPairingCode(code: String) = trezorRepo.submitPairingCode(code)

    fun cancelPairingCode() = trezorRepo.cancelPairingCode()

    /** Device discovery and connection state used by the Connect Hardware flow. */
    val deviceState: StateFlow<TrezorState> = trezorRepo.state

    /** Scans for nearby unpaired devices; results land in [deviceState]'s nearbyDevices. */
    suspend fun scan(
        includeBluetooth: Boolean = true,
    ): Result<List<TrezorDeviceInfo>> = trezorRepo.scan(
        includeBluetooth = includeBluetooth,
    )

    suspend fun hasKnownDevice(deviceId: String): Boolean = trezorRepo.hasKnownDevice(deviceId)

    /** Connects and pairs a discovered device, persisting it as a watch-only known device. */
    suspend fun connect(deviceId: String): Result<TrezorFeatures> {
        trezorRepo.resetWalletSelection()
        return trezorRepo.connect(deviceId)
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
    ): Result<TrezorFeatures> = withContext(ioDispatcher) {
        runSuspendCatching {
            trezorRepo.connectKnownDevice(transportDeviceId(walletId), forceSession = forceSession).getOrThrow()
        }
    }

    /**
     * Makes the device session belong to [walletId], not merely to its transport. A device holds
     * one identity open at a time, so a session opened for another wallet on the same device would
     * otherwise be accepted and sign with the wrong seed. The standard wallet needs no secret to
     * reopen; a passphrase wallet does, which the caller has to collect.
     */
    suspend fun ensureConnected(walletId: String): Result<TrezorFeatures> = withContext(ioDispatcher) {
        runSuspendCatching {
            val deviceId = transportDeviceId(walletId)
            val features = trezorRepo.ensureConnected(deviceId).getOrThrow()
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
            reopened
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

    /**
     * Reopens a watched passphrase wallet for signing. A wrong passphrase is not rejected by the
     * device — it silently derives a different wallet — so the reopened session is only accepted
     * when its accounts resolve back to [walletId]; anything else is torn down again and reported
     * as [HwPassphraseMismatchError] rather than signing from the wrong wallet.
     */
    suspend fun reconnectWithPassphrase(walletId: String, passphrase: String): Result<Unit> =
        withContext(ioDispatcher) {
            runSuspendCatching {
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
                // must not leave a stray watch-only wallet behind.
                if (opened !in watchedBefore) {
                    removeDevice(opened)
                        .onFailure { Logger.warn("Failed to drop unwatched wallet '$opened'", it, context = TAG) }
                }
                trezorRepo.disconnectStaleSession(deviceId)
                throw HwPassphraseMismatchError()
            }
        }

    suspend fun isKnownBluetoothDevice(walletId: String): Boolean = withContext(ioDispatcher) {
        val deviceId = transportDeviceIdOrNull(walletId) ?: return@withContext false
        trezorRepo.isKnownBluetoothDevice(deviceId)
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
                .filter { it.addressType == addressType.settingsKey && it.walletId == walletId }
                .fold(0uL) { acc, watcher -> acc + watcher.balanceSats }
            HwFundingAccount.Trezor(
                xpub = xpub,
                addressType = addressType,
                balanceSats = balanceSats,
            )
        }
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
            val composed = trezorRepo.composeTransaction(
                extendedKey = account.xpub,
                outputs = listOf(ComposeOutput.Payment(address = address, amountSats = sats)),
                feeRates = listOf(satsPerVByte.toFloat()),
                network = network,
                accountType = account.accountType,
                coinSelection = CoinSelection.BRANCH_AND_BOUND,
            ).getOrThrow()
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

    /** Signs a composed funding payment on the Trezor. */
    suspend fun signFunding(
        walletId: String,
        funding: HwFundingTransaction,
    ): Result<HwFundingSignedTx> = withContext(ioDispatcher) {
        runSuspendCatching {
            // The session can change between connecting and signing, and signing the wrong seed
            // would produce signatures that do not match the inputs being spent.
            if (!trezorRepo.state.value.connectedWalletId().isIdentityOf(walletId)) {
                throw HwPassphraseRequiredError()
            }
            val signedTx = trezorRepo.signTxFromPsbt(
                psbtBase64 = funding.psbt,
                network = Env.network.toTrezorCoinType(),
            ).getOrElse {
                if (!it.isTrezorUserCancellation()) {
                    transportDeviceIdOrNull(walletId)?.let { deviceId -> trezorRepo.disconnectStaleSession(deviceId) }
                }
                throw it
            }
            HwFundingSignedTx(
                serializedTx = signedTx.serializedTx,
                miningFeeSats = funding.miningFeeSats,
                feeRate = ceil(funding.feeRate.toDouble()).toULong(),
                totalSpent = funding.totalSpent,
            )
        }
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
            trezorRepo.disconnectStaleSession(deviceId).getOrThrow()
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
     */
    suspend fun removeDevice(walletId: String): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            watcherMutex.withLock {
                val knownDevices = hwWalletStore.loadKnownDevices()
                val targets = knownDevices.filter { it.resolvedWalletId() == walletId }
                // Without an entry there is nothing to forget, and the check below would pass on an
                // empty set: report the failure instead of telling the user the wallet was removed.
                require(targets.isNotEmpty()) { "Unknown hardware wallet '$walletId'" }
                activeWatchers.toList()
                    .filter { it.toWalletId() == walletId }
                    .forEach {
                        if (!stopActiveWatcherLocked(it)) {
                            throw AppError("Failed to stop hardware wallet watcher '$it'")
                        }
                    }
                activityRepo.deleteForWallet(walletId).getOrThrow()
                trackedWalletIds -= walletId
                lastPersistedHwSnapshots -= walletId
                val failures = targets.mapNotNull {
                    trezorRepo.forgetDevice(it.id, walletKey = it.walletKey).exceptionOrNull()
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
        trezorRepo.state,
        _watcherData,
    ) { data, trezorState, watcherData ->
        // The same physical device paired over both bluetooth and usb is stored as two
        // entries with different transport-level ids; its xpubs are the cross-transport
        // identity, so group by them to show one wallet and count its balance once. A
        // passphrase wallet derives different xpubs, so it groups into its own wallet.
        data.knownDevices
            .filter { it.xpubs.isNotEmpty() }
            .groupBy { it.walletKey }
            .mapNotNull { (_, devices) ->
                val walletId = devices.firstNotNullOfOrNull { it.resolvedWalletId() } ?: return@mapNotNull null
                val connectedDevice = devices.find { it.id == trezorState.connectedDeviceId() }
                val device = connectedDevice ?: devices.maxBy { it.lastConnectedAt }
                val ids = devices.map { it.id }.toSet()
                val walletWatchers = watcherData.values.filter { it.walletId == walletId }
                val fundingBalanceSats = walletWatchers
                    .filter { it.addressType == HwFundingAddressType.DEFAULT.settingsKey }
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
                        trezorState.connectedWalletId().let { it == null || it == walletId },
                    balanceSats = walletWatchers.fold(0uL) { acc, watcher -> acc + watcher.balanceSats },
                    activities = walletWatchers
                        .toMergedActivities()
                        .toImmutableList(),
                    fundingBalanceSats = fundingBalanceSats,
                    deviceIds = ids.toImmutableSet(),
                    passphraseProtected = devices.any { it.passphraseProtected },
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
                    val watcher = HwWatcherData(
                        walletId = walletId,
                        addressType = watcherId.toAddressTypeKey(),
                        balanceSats = event.balance.total,
                        activities = activities,
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
                        ?.takeIf { it.source == snapshotCacheKey }
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
                    .map {
                        WatcherSettings(
                            monitoredTypes = it.addressTypesToMonitor.toSet(),
                            electrumUrl = Env.trezorElectrumUrlOrDefault(it.electrumServer),
                        )
                    }
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
        val persistedWalletIds = activityRepo.getWalletIds().getOrDefault(emptySet())
            .filterNot { it == WalletScope.default }
            .toSet()
        watcherMutex.withLock {
            val specs = knownDevices.toWatcherSpecs(watcherSettings.electrumUrl)
            val desiredIds = specs.map { it.watcherId }.toSet()
            val knownWalletIds = knownDevices.mapNotNull { it.resolvedWalletId() }.toSet()
            trackedWalletIds += persistedWalletIds
            val removedWalletIds = trackedWalletIds - knownWalletIds
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

    private fun KnownDevice.resolvedWalletId(): String? =
        walletId.takeIf { it.isNotBlank() } ?: trezorRepo.deriveWalletId(xpubs)

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
}

private data class WatcherSettings(
    val monitoredTypes: Set<String>,
    val electrumUrl: String,
)

/**
 * Cross-transport identity of the wallet a device entry tracks: entries created by
 * pairing the same physical device over different transports share the same xpubs.
 * Entries without captured xpubs fall back to their own transport-level id.
 */
private val KnownDevice.walletKey: String
    get() = xpubs.values.sorted().joinToString().ifEmpty { id }

/**
 * Resolves the name shown for a hardware wallet: the Bitkit-side custom label if the user set one,
 * otherwise the device's own label; without one (or with the factory default that just mirrors the
 * model) it falls back to the vendor-prefixed model (e.g. "Safe 7" reads as "Trezor Safe 7").
 */
fun resolveHwWalletName(label: String?, model: String?, customLabel: String? = null): String {
    customLabel?.takeIf { it.isNotBlank() }?.let { return it }
    label?.takeIf { it != model }?.let { return it }
    val resolvedModel = model ?: return "Trezor"
    return if (resolvedModel.startsWith("Trezor")) resolvedModel else "Trezor $resolvedModel"
}

private val KnownDevice.displayName: String
    get() = resolveHwWalletName(label = label, model = model, customLabel = customLabel)

/** The device has passphrase protection turned off, so it cannot open a hidden wallet at all. */
class HwPassphraseDisabledError : AppError("Passphrase protection is off on this device")

/** The entered passphrase resolves to a wallet Bitkit already watches. */
class HwPassphraseAlreadyAddedError : AppError("Passphrase wallet already added")

/** The device session belongs to another identity, and only its passphrase can reopen this one. */
class HwPassphraseRequiredError : AppError("Passphrase needed to reopen this wallet")

/** The entered passphrase opened a different wallet than the one being signed from. */
class HwPassphraseMismatchError : AppError("Passphrase opened a different wallet")

private data class HwWatcherData(
    val walletId: String,
    val addressType: String,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
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
