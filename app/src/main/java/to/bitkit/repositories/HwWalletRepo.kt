package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
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
import to.bitkit.ext.isTrezorUserCancellation
import to.bitkit.ext.rawId
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.timestamp
import to.bitkit.models.HwFundingAccount
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
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
import kotlin.math.ceil
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
    private val activityRepo: ActivityRepo,
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

        /** Trezor v1 (2.4.0) tracks native segwit only; multi-type HW support is follow-up work. */
        private val SUPPORTED_WATCHER_ADDRESS_TYPES = setOf(HwFundingAddressType.NATIVE_SEGWIT.settingsKey)
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val activeWatchers = mutableSetOf<String>()
    private val activeWatcherElectrumUrls = mutableMapOf<String, String>()
    private val activeWatcherWalletIds = mutableMapOf<String, String>()
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

    fun warmUpKnownDevice(deviceId: String) = trezorRepo.warmUpKnownDevice(deviceId)

    suspend fun resetState() = withContext(ioDispatcher) {
        activeWatchers.toList().forEach { watcherId ->
            trezorRepo.stopWatcher(watcherId)
                .onFailure { Logger.warn("Failed to stop watcher '$watcherId' while resetting", it, context = TAG) }
        }
        activeWatchers.clear()
        activeWatcherElectrumUrls.clear()
        activeWatcherWalletIds.clear()
        retryingWatcherStarts.clear()
        emittedReceivedTxIds.clear()
        _watcherData.update { emptyMap() }
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

    /** Reconnects a known paired device so its session is live for on-device signing. */
    suspend fun reconnect(
        deviceId: String,
        forceSession: Boolean = false,
    ): Result<TrezorFeatures> = trezorRepo.connectKnownDevice(deviceId, forceSession = forceSession)

    suspend fun ensureConnected(deviceId: String): Result<TrezorFeatures> = trezorRepo.ensureConnected(deviceId)

    suspend fun isKnownBluetoothDevice(deviceId: String): Boolean = trezorRepo.isKnownBluetoothDevice(deviceId)

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
                .values
                .filter {
                    it.addressType == addressType.settingsKey &&
                        it.deviceId in groupIds
                }
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
        deviceId: String,
        address: String,
        sats: ULong,
        satsPerVByte: ULong,
    ): Result<HwFundingTransaction> = withContext(ioDispatcher) {
        runSuspendCatching {
            val account = getFundingAccount(deviceId).getOrThrow()
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
        deviceId: String,
        funding: HwFundingTransaction,
    ): Result<HwFundingSignedTx> = withContext(ioDispatcher) {
        runSuspendCatching {
            val signedTx = trezorRepo.signTxFromPsbt(
                psbtBase64 = funding.psbt,
                network = Env.network.toTrezorCoinType(),
            ).getOrElse {
                if (!it.isTrezorUserCancellation()) {
                    trezorRepo.disconnectStaleSession(deviceId)
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

    suspend fun disconnectStaleSession(deviceId: String): Result<Unit> = trezorRepo.disconnectStaleSession(deviceId)

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
                val fundingBalanceSats = deviceWatchers
                    .filter { it.addressType == HwFundingAddressType.DEFAULT.settingsKey }
                    .fold(0uL) { acc, watcher -> acc + watcher.balanceSats }
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
                    fundingBalanceSats = fundingBalanceSats,
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
                val activities = event.activities.toImmutableList()
                val watcher = HwWatcherData(
                    deviceId = watcherId.toDeviceId(),
                    addressType = watcherId.toAddressTypeKey(),
                    balanceSats = event.balance.total,
                    activities = activities,
                )
                _watcherData.update { it + (watcherId to watcher) }
                val updatedWatcherData = _watcherData.value
                activities.filterIsInstance<Activity.Onchain>().forEach {
                    activityRepo.syncHardwareOnchainActivity(it.v1)
                }
                emitReceivedTxs(previous, activities, updatedWatcherData)
            }
        }
    }

    /**
     * The first event after a watcher starts delivers the full transaction history;
     * treat it as the baseline so only transactions arriving while watching are emitted.
     */
    private suspend fun emitReceivedTxs(
        previous: HwWatcherData?,
        activities: List<Activity>,
        watcherData: Map<String, HwWatcherData>,
    ) {
        if (previous == null) return
        val knownTxIds = previous.activities.mapNotNull { activity ->
            (activity as? Activity.Onchain)?.v1?.txId
        }.toSet()
        val mergedActivities = watcherData.values.toList().toMergedActivities()
        activities.filterIsInstance<Activity.Onchain>()
            .filter { it.v1.txType == PaymentType.RECEIVED }
            .forEach { onchain ->
                val txid = onchain.v1.txId
                if (txid in knownTxIds || !emittedReceivedTxIds.add(txid)) return@forEach
                val sats = mergedActivities.findOnchain(txid)?.v1?.value ?: onchain.v1.value
                _receivedTxs.emit(HwWalletReceivedTx(txid = txid, sats = sats))
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
                // Trezor v1 watches native segwit only (not global Advanced address-type monitoring).
                // Xpubs for all types are still captured on connect for a future multi-type release.
                // Device entries sharing an xpub (same device on bluetooth and usb) watch it only once.
                val filtered = knownDevices.flatMap { device ->
                    val walletId = device.resolvedWalletId() ?: return@flatMap emptyList<WatcherSpec>()
                    device.xpubs
                        .filterKeys { it in SUPPORTED_WATCHER_ADDRESS_TYPES }
                        .map { (addressType, xpub) ->
                            WatcherSpec(
                                deviceId = device.id,
                                addressType = addressType,
                                xpub = xpub,
                                electrumUrl = watcherSettings.electrumUrl,
                                walletId = walletId,
                            )
                        }
                }.distinctBy { it.addressType to it.xpub }
                val filteredIds = filtered.map { it.watcherId }.toSet()

                filtered.forEach { spec ->
                    val isActive = spec.watcherId in activeWatchers
                    if (
                        isActive &&
                        activeWatcherElectrumUrls[spec.watcherId] == spec.electrumUrl &&
                        activeWatcherWalletIds[spec.watcherId] == spec.walletId
                    ) {
                        return@forEach
                    }
                    if (isActive && !stopActiveWatcher(spec.watcherId)) return@forEach

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
            activeWatcherWalletIds -= watcherId
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

    private fun KnownDevice.resolvedWalletId(): String? =
        walletId.takeIf { it.isNotBlank() } ?: trezorRepo.deriveWalletId(xpubs)

    private fun List<HwWatcherData>.toMergedActivities(): List<Activity> =
        flatMap { it.activities }
            .groupBy { it.rawId() }
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

    private fun List<Activity>.findOnchain(txid: String) = filterIsInstance<Activity.Onchain>()
        .firstOrNull { it.v1.txId == txid }

    private data class WatcherSpec(
        val deviceId: String,
        val addressType: String,
        val xpub: String,
        val electrumUrl: String,
        val walletId: String,
    ) {
        val watcherId: String get() = "$deviceId$WATCHER_ID_SEPARATOR$addressType"
    }

    private fun String.toDeviceId(): String = substringBefore(WATCHER_ID_SEPARATOR)

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

private data class HwWatcherData(
    val deviceId: String,
    val addressType: String,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
)
