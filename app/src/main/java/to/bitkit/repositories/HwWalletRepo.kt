package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TxDirection
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withContext
import to.bitkit.data.HwWalletStore
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.rawId
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HwFundingAccount
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwWallet
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.KnownDevice
import to.bitkit.models.TransportType
import to.bitkit.models.safe
import to.bitkit.models.toAccountType
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toTrezorCoinType
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Production hardware-wallet business layer. Tracks paired Trezor devices as
 * watch-only balances by running one on-chain xpub watcher per (device, address type)
 * and exposing the aggregated per-device balance and activity to the UI.
 *
 * Built on top of [TrezorRepo], which owns the device list, connect orchestration
 * and the underlying watcher transport.
 */
@Suppress("TooManyFunctions")
@OptIn(ExperimentalTime::class)
@Singleton
class HwWalletRepo @Inject constructor(
    private val trezorRepo: TrezorRepo,
    private val hwWalletStore: HwWalletStore,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "HwWalletRepo"
        private const val WATCHER_ID_SEPARATOR = "|"
        private val WATCHER_START_RETRY_DELAY = 30.seconds
        const val DEVICE_LABEL_MAX_LENGTH = 50
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val activeWatchers = mutableSetOf<String>()
    private val activeWatcherElectrumUrls = mutableMapOf<String, String>()
    private val retryingWatcherStarts = mutableSetOf<String>()
    private val watcherSyncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _watcherData = MutableStateFlow<Map<String, HwWatcherData>>(emptyMap())
    private val emittedReceivedTxIds = mutableSetOf<String>()

    private val _receivedTxs = MutableSharedFlow<HwWalletReceivedTx>(extraBufferCapacity = 8)

    /** Inbound transactions detected by a running watcher after its initial history sync. */
    val receivedTxs: SharedFlow<HwWalletReceivedTx> = _receivedTxs.asSharedFlow()

    /** Forwards UI-delivered transport events, e.g. the USB attach intent from the OS app picker. */
    fun onTransportRestored(transportType: TransportType) = trezorRepo.onTransportRestored(transportType)

    fun onAppForegrounded() = trezorRepo.onAppForegrounded()

    suspend fun resetState() = withContext(ioDispatcher) {
        activeWatchers.toList().forEach { watcherId ->
            trezorRepo.stopWatcher(watcherId)
                .onFailure { Logger.warn("Failed to stop watcher '$watcherId' while resetting", it, context = TAG) }
        }
        activeWatchers.clear()
        activeWatcherElectrumUrls.clear()
        retryingWatcherStarts.clear()
        emittedReceivedTxIds.clear()
        _watcherData.update { emptyMap() }
        trezorRepo.resetState()
    }

    /** Pairing-code request raised by the device during connect; the UI shows the Pair Device sheet. */
    val needsPairingCode = trezorRepo.needsPairingCode

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

    /** Reconnects a known paired device so its session is live for on-device signing. */
    suspend fun reconnect(
        deviceId: String,
        forceSession: Boolean = false,
    ): Result<TrezorFeatures> = trezorRepo.connectKnownDevice(deviceId, forceSession = forceSession)

    suspend fun getFundingAccount(
        deviceId: String,
        addressType: HwFundingAddressType = HwFundingAddressType.DEFAULT,
    ): Result<HwFundingAccount> = withContext(ioDispatcher) {
        runSuspendCatching {
            val devices = hwWalletStore.loadKnownDevices()
            val target = requireNotNull(devices.find { it.id == deviceId }) { "Unknown hardware wallet '$deviceId'" }
            val groupIds = devices.filter { it.walletKey == target.walletKey }.map { it.id }.toSet()
            val xpub = requireNotNull(target.xpubs[addressType.settingsKey]) {
                "Hardware wallet '$deviceId' has no '${addressType.settingsKey}' account"
            }
            val balanceSats = _watcherData.value
                .filterKeys { key ->
                    key.substringAfter(WATCHER_ID_SEPARATOR) == addressType.settingsKey &&
                        key.toDeviceId() in groupIds
                }
                .values.fold(0uL) { acc, watcher -> acc + watcher.balanceSats }
            HwFundingAccount.Trezor(
                xpub = xpub,
                addressType = addressType,
                balanceSats = balanceSats,
            )
        }
    }

    /** Composes, signs on the Trezor, and broadcasts the on-chain funding payment. */
    suspend fun signAndBroadcastFunding(
        deviceId: String,
        address: String,
        sats: ULong,
        satsPerVByte: ULong,
    ): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            val account = getFundingAccount(deviceId).getOrThrow()
            val network = Env.network.toCoreNetwork()
            val signed = runSuspendCatching {
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
                trezorRepo.signTxFromPsbt(
                    psbtBase64 = success.psbt,
                    network = Env.network.toTrezorCoinType(),
                ).getOrThrow()
            }
            if (signed.isFailure) {
                trezorRepo.disconnectStaleSession(deviceId)
            }
            trezorRepo.broadcastRawTx(serializedTx = signed.getOrThrow().serializedTx).getOrThrow()
        }
    }

    /**
     * Persists the Bitkit-side funds label for a paired device. Applied to every entry sharing the
     * same wallet identity so the same device paired over both transports renames consistently.
     */
    suspend fun setDeviceLabel(deviceId: String, label: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val devices = hwWalletStore.loadKnownDevices()
            val target = requireNotNull(devices.find { it.id == deviceId }) { "Unknown hardware wallet '$deviceId'" }
            val customLabel = label.trim().take(DEVICE_LABEL_MAX_LENGTH).ifEmpty { null }
            val updated = devices.map {
                if (it.walletKey == target.walletKey) it.copy(customLabel = customLabel) else it
            }
            hwWalletStore.saveKnownDevices(updated)
        }
    }

    /**
     * Removes a paired hardware wallet: stops its watchers and forgets every device entry
     * that tracks the same wallet. The same physical device paired over both bluetooth and
     * usb is stored once per transport but shares an xpub-derived identity, so forgetting a
     * single id would leave the tile reappearing through the other transport.
     */
    suspend fun removeDevice(deviceId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val knownDevices = hwWalletStore.loadKnownDevices()
            val target = knownDevices.find { it.id == deviceId }
            val ids = when (target) {
                null -> setOf(deviceId)
                else -> knownDevices.filter { it.walletKey == target.walletKey }.map { it.id }.toSet()
            }
            activeWatchers.toList()
                .filter { it.toDeviceId() in ids }
                .forEach {
                    if (!stopActiveWatcher(it)) throw AppError("Failed to stop hardware wallet watcher '$it'")
                }
            val failures = ids.mapNotNull { trezorRepo.forgetDevice(it).exceptionOrNull() }
            val remaining = hwWalletStore.loadKnownDevices().map { it.id }.toSet()
            failures.firstOrNull()?.let { throw it }
            check(ids.none { it in remaining }) { "Hardware wallet '$deviceId' still present after removal" }
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
        // identity, so group by them to show one wallet and count its balance once.
        data.knownDevices
            .filter { it.xpubs.isNotEmpty() }
            .groupBy { it.walletKey }
            .map { (_, devices) ->
                val connectedDevice = devices.find { it.id == trezorState.connectedDeviceId() }
                val device = connectedDevice ?: devices.maxBy { it.lastConnectedAt }
                val ids = devices.map { it.id }.toSet()
                val deviceWatchers = watcherData.values.filter { it.deviceId in ids }
                HwWallet(
                    id = device.id,
                    name = device.displayName,
                    model = device.model,
                    transportType = device.transportType,
                    isConnected = connectedDevice != null,
                    balanceSats = deviceWatchers.fold(0uL) { acc, watcher -> acc + watcher.balanceSats },
                    activities = deviceWatchers
                        .toMergedActivities()
                        .toImmutableList(),
                    deviceIds = ids.toImmutableSet(),
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
        val knownDeviceIds = data.knownDevices
            .filter { it.xpubs.isNotEmpty() }
            .map { it.id }
            .toSet()
        watcherData.values
            .filter { it.deviceId in knownDeviceIds }
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
                val previous = _watcherData.value[watcherId]
                val activities = event.transactions
                    .map { it.toOnchainActivity(clock, previous?.activities.orEmpty()) }
                    .toImmutableList()
                val watcher = HwWatcherData(
                    deviceId = watcherId.toDeviceId(),
                    balanceSats = event.balance.total,
                    transactions = event.transactions.toImmutableList(),
                    activities = activities,
                )
                val updatedWatcherData = _watcherData.value + (watcherId to watcher)
                _watcherData.update { updatedWatcherData }
                emitReceivedTxs(previous, event, updatedWatcherData)
            }
        }
    }

    /**
     * The first event after a watcher starts delivers the full transaction history;
     * treat it as the baseline so only transactions arriving while watching are emitted.
     */
    private suspend fun emitReceivedTxs(
        previous: HwWatcherData?,
        event: WatcherEvent.TransactionsChanged,
        watcherData: Map<String, HwWatcherData>,
    ) {
        if (previous == null) return
        val knownTxIds = previous.activities.map { it.rawId() }.toSet()
        val mergedActivities = watcherData.values.toList().toMergedActivities()
        event.transactions
            .filter {
                it.direction == TxDirection.RECEIVED &&
                    it.txid !in knownTxIds &&
                    emittedReceivedTxIds.add(it.txid)
            }
            .forEach {
                val sats = mergedActivities.findOnchain(it.txid)?.v1?.value ?: it.amount
                _receivedTxs.emit(HwWalletReceivedTx(txid = it.txid, sats = sats))
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
                // Only watch the address types the user monitors (Settings > Advanced > Address Type),
                // mirroring the on-chain wallet. Xpubs for all types are still captured on connect, so
                // toggling a type on later starts its watcher without reconnecting the device.
                // Device entries sharing an xpub (same device on bluetooth and usb) watch it only once.
                val filtered = knownDevices.flatMap { device ->
                    device.xpubs
                        .filterKeys { it in watcherSettings.monitoredTypes }
                        .map { (addressType, xpub) ->
                            WatcherSpec(device.id, addressType, xpub, watcherSettings.electrumUrl)
                        }
                }.distinctBy { it.addressType to it.xpub }
                val filteredIds = filtered.map { it.watcherId }.toSet()

                filtered.forEach { spec ->
                    val isActive = spec.watcherId in activeWatchers
                    if (isActive && activeWatcherElectrumUrls[spec.watcherId] == spec.electrumUrl) return@forEach
                    if (isActive && !stopActiveWatcher(spec.watcherId)) return@forEach

                    trezorRepo.startWatcher(
                        watcherId = spec.watcherId,
                        extendedKey = spec.xpub,
                        network = Env.network.toCoreNetwork(),
                        accountType = spec.addressType.toAddressType()?.toAccountType(),
                        electrumUrl = spec.electrumUrl,
                    ).onSuccess {
                        activeWatchers += spec.watcherId
                        activeWatcherElectrumUrls[spec.watcherId] = spec.electrumUrl
                        retryingWatcherStarts -= spec.watcherId
                    }.onFailure {
                        Logger.warn("Retrying watcher '${spec.watcherId}' after start failure", it, context = TAG)
                        scheduleWatcherStartRetry(spec.watcherId)
                    }
                }

                // A failed stop stays active so the next sync retries it; dropping it here
                // would leave the orphaned watcher feeding _watcherData as a ghost balance.
                (activeWatchers - filteredIds).forEach { staleId ->
                    stopActiveWatcher(staleId)
                }
            }
        }
    }

    private suspend fun stopActiveWatcher(watcherId: String): Boolean =
        trezorRepo.stopWatcher(watcherId).onSuccess {
            activeWatchers -= watcherId
            activeWatcherElectrumUrls -= watcherId
            _watcherData.update { it - watcherId }
        }.isSuccess

    private fun scheduleWatcherStartRetry(watcherId: String) {
        if (!retryingWatcherStarts.add(watcherId)) return

        scope.launch {
            delay(WATCHER_START_RETRY_DELAY)
            retryingWatcherStarts -= watcherId
            watcherSyncRequests.emit(Unit)
        }
    }

    private fun HistoryTransaction.toOnchainActivity(clock: Clock, previousActivities: List<Activity>): Activity {
        val activityTimestamp = timestamp ?: previousActivities.findOnchain(txid)?.v1?.timestamp
            ?: clock.now().epochSeconds.toULong()
        return listOf(this).toOnchainActivity(
            timestamp = activityTimestamp,
            sourceActivities = previousActivities,
        )
    }

    private fun List<HwWatcherData>.toMergedActivities(): List<Activity> {
        val sourceActivities = flatMap { it.activities }
        return flatMap { it.transactions }
            .groupBy { it.txid }
            .values
            .map { transactions ->
                val timestamp = transactions.mapNotNull { it.timestamp }.minOrNull()
                    ?: sourceActivities.findOnchain(transactions.first().txid)?.v1?.timestamp
                    ?: 0uL
                transactions.toOnchainActivity(timestamp, sourceActivities)
            }
    }

    private fun List<HistoryTransaction>.toOnchainActivity(
        timestamp: ULong,
        sourceActivities: List<Activity>,
    ): Activity {
        val first = first()
        val received = fold(0uL) { acc, tx -> acc.safe() + tx.received.safe() }
        val sent = fold(0uL) { acc, tx -> acc.safe() + tx.sent.safe() }
        val fee = mapNotNull { it.fee }.maxOrNull() ?: 0uL
        val type = when {
            received > sent -> PaymentType.RECEIVED
            else -> PaymentType.SENT
        }
        val value = when (type) {
            PaymentType.RECEIVED -> received.safe() - sent.safe()
            PaymentType.SENT -> (sent.safe() - received.safe()).safe() - fee.safe()
        }
        val confirmations = maxOf { it.confirmations }
        val sourceActivity = sourceActivities.findOnchain(first.txid)
        return Activity.Onchain(
            OnchainActivity.create(
                id = first.txid,
                txType = type,
                txId = first.txid,
                value = value,
                fee = fee,
                address = "",
                timestamp = timestamp,
                confirmed = confirmations > 0u,
                confirmTimestamp = sourceActivity?.v1?.confirmTimestamp,
            )
        )
    }

    private fun List<Activity>.findOnchain(txid: String) = filterIsInstance<Activity.Onchain>()
        .firstOrNull { it.v1.txId == txid }

    private data class WatcherSpec(
        val deviceId: String,
        val addressType: String,
        val xpub: String,
        val electrumUrl: String,
    ) {
        val watcherId: String get() = "$deviceId$WATCHER_ID_SEPARATOR$addressType"
    }

    private fun String.toDeviceId(): String = substringBefore(WATCHER_ID_SEPARATOR)
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

private data class HwWatcherData(
    val deviceId: String,
    val balanceSats: ULong,
    val transactions: ImmutableList<HistoryTransaction>,
    val activities: ImmutableList<Activity>,
)
