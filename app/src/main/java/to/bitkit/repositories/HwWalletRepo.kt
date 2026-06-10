package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TxDirection
import com.synonym.bitkitcore.WatcherEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.HwWalletStore
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.rawId
import to.bitkit.models.HwWallet
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.toAccountType
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoreNetwork
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Production hardware-wallet business layer. Tracks paired Trezor devices as
 * watch-only balances by running one on-chain xpub watcher per (device, address type)
 * and exposing the aggregated per-device balance and activity to the UI.
 *
 * Built on top of [TrezorRepo], which owns the device list, connect orchestration
 * and the underlying watcher transport.
 */
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
        private const val WATCHER_ID_SEPARATOR = "|"
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val activeWatchers = mutableSetOf<String>()
    private val _watcherData = MutableStateFlow<Map<String, HwWatcherData>>(emptyMap())

    private val _receivedTxs = MutableSharedFlow<HwWalletReceivedTx>(extraBufferCapacity = 8)

    /** Inbound transactions detected by a running watcher after its initial history sync. */
    val receivedTxs: SharedFlow<HwWalletReceivedTx> = _receivedTxs.asSharedFlow()

    /** Forwards UI-delivered transport events, e.g. the USB attach intent from the OS app picker. */
    fun onTransportRestored() = trezorRepo.onTransportRestored()

    val wallets: StateFlow<ImmutableList<HwWallet>> = combine(
        hwWalletStore.data,
        trezorRepo.state,
        _watcherData,
    ) { data, trezorState, watcherData ->
        // The same physical device paired over both bluetooth and usb is stored as two
        // entries with different transport-level ids; its xpubs are the cross-transport
        // identity, so group by them to show one wallet and count its balance once.
        data.knownDevices
            .groupBy { it.walletKey }
            .map { (_, devices) ->
                val connectedDevice = devices.find { it.id == trezorState.connectedDeviceId }
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
                    activities = deviceWatchers.flatMap { it.activities }
                        .distinctBy { it.rawId() }
                        .toImmutableList(),
                )
            }
            .toImmutableList()
    }.stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    val totalSats: StateFlow<ULong> = wallets
        .map { wallets -> wallets.fold(0uL) { acc, wallet -> acc + wallet.balanceSats } }
        .stateIn(scope, SharingStarted.Eagerly, 0uL)

    val activities: StateFlow<ImmutableList<Activity>> = wallets
        .map { wallets -> wallets.flatMap { it.activities }.toImmutableList() }
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
                val activities = event.transactions.map { it.toOnchainActivity(clock) }.toImmutableList()
                _watcherData.update {
                    it + (watcherId to HwWatcherData(watcherId.toDeviceId(), event.balance.total, activities))
                }
                emitReceivedTxs(previous, event)
            }
        }
    }

    /**
     * The first event after a watcher starts delivers the full transaction history;
     * treat it as the baseline so only transactions arriving while watching are emitted.
     */
    private suspend fun emitReceivedTxs(previous: HwWatcherData?, event: WatcherEvent.TransactionsChanged) {
        if (previous == null) return
        val knownTxIds = previous.activities.map { it.rawId() }.toSet()
        event.transactions
            .filter { it.direction == TxDirection.RECEIVED && it.txid !in knownTxIds }
            .forEach { _receivedTxs.emit(HwWalletReceivedTx(txid = it.txid, sats = it.amount)) }
    }

    private fun syncWatchers() {
        scope.launch {
            combine(
                hwWalletStore.data,
                settingsStore.data.map { it.addressTypesToMonitor.toSet() }.distinctUntilChanged(),
            ) { data, monitoredTypes ->
                data.knownDevices to monitoredTypes
            }.collect { (knownDevices, monitoredTypes) ->
                // Only watch the address types the user monitors (Settings > Advanced > Address Type),
                // mirroring the on-chain wallet. Xpubs for all types are still captured on connect, so
                // toggling a type on later starts its watcher without reconnecting the device.
                // Device entries sharing an xpub (same device on bluetooth and usb) watch it only once.
                val filtered = knownDevices.flatMap { device ->
                    device.xpubs
                        .filterKeys { it in monitoredTypes }
                        .map { (addressType, xpub) -> WatcherSpec(device.id, addressType, xpub) }
                }.distinctBy { it.addressType to it.xpub }
                val filteredIds = filtered.map { it.watcherId }.toSet()

                filtered.forEach { spec ->
                    if (spec.watcherId in activeWatchers) return@forEach
                    trezorRepo.startWatcher(
                        watcherId = spec.watcherId,
                        extendedKey = spec.xpub,
                        network = Env.network.toCoreNetwork(),
                        accountType = spec.addressType.toAddressType()?.toAccountType(),
                    ).onSuccess { activeWatchers += spec.watcherId }
                }

                // A failed stop stays active so the next sync retries it; dropping it here
                // would leave the orphaned watcher feeding _watcherData as a ghost balance.
                (activeWatchers - filteredIds).forEach { staleId ->
                    trezorRepo.stopWatcher(staleId).onSuccess {
                        activeWatchers -= staleId
                        _watcherData.update { it - staleId }
                    }
                }
            }
        }
    }

    private fun HistoryTransaction.toOnchainActivity(clock: Clock): Activity {
        val type = when (direction) {
            TxDirection.RECEIVED -> PaymentType.RECEIVED
            TxDirection.SENT, TxDirection.SELF_TRANSFER -> PaymentType.SENT
        }
        val activityTimestamp = timestamp ?: clock.now().epochSeconds.toULong()
        return Activity.Onchain(
            OnchainActivity.create(
                id = txid,
                txType = type,
                txId = txid,
                value = amount,
                fee = fee ?: 0uL,
                address = "",
                timestamp = activityTimestamp,
                confirmed = confirmations > 0u,
            )
        )
    }

    private data class WatcherSpec(
        val deviceId: String,
        val addressType: String,
        val xpub: String,
    ) {
        val watcherId: String get() = "$deviceId$WATCHER_ID_SEPARATOR$addressType"
    }

    private fun String.toDeviceId(): String = substringBefore(WATCHER_ID_SEPARATOR)
}

/**
 * Cross-transport identity of the wallet a device entry tracks: entries created by
 * pairing the same physical device over different transports share the same xpubs.
 * Entries without captured xpubs fall back to their own transport-level id.
 */
private val KnownDevice.walletKey: String
    get() = xpubs.values.sorted().joinToString().ifEmpty { id }

/**
 * The label is the user-set name stored on the device itself; without one (or with the
 * factory default that just mirrors the model), fall back to the vendor-prefixed model
 * (e.g. "Safe 7" reads as "Trezor Safe 7").
 */
private val KnownDevice.displayName: String
    get() {
        label?.takeIf { it != model }?.let { return it }
        val model = model ?: return "Trezor"
        return if (model.startsWith("Trezor")) model else "Trezor $model"
    }

private data class HwWatcherData(
    val deviceId: String,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
)
