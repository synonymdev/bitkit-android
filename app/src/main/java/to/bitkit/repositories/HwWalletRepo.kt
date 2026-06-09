package to.bitkit.repositories

import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.TrezorStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.models.toAccountType
import to.bitkit.models.toAddressType
import to.bitkit.models.toCoreNetwork
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production hardware-wallet business layer. Tracks paired Trezor devices as
 * watch-only balances by running one on-chain xpub watcher per (device, address type)
 * and exposing the aggregated per-device balance and activity to the UI.
 *
 * Built on top of [TrezorRepo], which owns the device list, connect orchestration
 * and the underlying watcher transport.
 */
@Singleton
class HwWalletRepo @Inject constructor(
    private val trezorRepo: TrezorRepo,
    private val trezorStore: TrezorStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val WATCHER_ID_SEPARATOR = "|"
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val activeWatchers = mutableSetOf<String>()
    private val _watcherData = MutableStateFlow<Map<String, HwWatcherData>>(emptyMap())

    val hardwareWallets: StateFlow<ImmutableList<HwWallet>> = combine(
        trezorStore.data,
        trezorRepo.state,
        _watcherData,
    ) { data, trezorState, watcherData ->
        data.knownDevices.map { device ->
            val deviceWatchers = watcherData.values.filter { it.deviceId == device.id }
            HwWallet(
                id = device.id,
                name = device.label ?: device.model ?: "Trezor",
                model = device.model,
                transportType = device.transportType,
                isConnected = trezorState.connectedDeviceId == device.id,
                balanceSats = deviceWatchers.fold(0uL) { acc, watcher -> acc + watcher.balanceSats },
                activities = deviceWatchers.flatMap { it.activities }.toImmutableList(),
            )
        }.toImmutableList()
    }.stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    val totalHardwareSats: StateFlow<ULong> = hardwareWallets
        .map { wallets -> wallets.fold(0uL) { acc, wallet -> acc + wallet.balanceSats } }
        .stateIn(scope, SharingStarted.Eagerly, 0uL)

    val hardwareActivities: StateFlow<ImmutableList<Activity>> = hardwareWallets
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
                val activities = event.transactions.map { it.toOnchainActivity() }.toImmutableList()
                _watcherData.update {
                    it + (watcherId to HwWatcherData(watcherId.toDeviceId(), event.balance.total, activities))
                }
            }
        }
    }

    private fun syncWatchers() {
        scope.launch {
            trezorStore.data.collect { data ->
                val wanted = data.knownDevices.flatMap { device ->
                    device.xpubs.map { (addressType, xpub) -> WatcherSpec(device.id, addressType, xpub) }
                }
                val wantedIds = wanted.map { it.watcherId }.toSet()

                wanted.forEach { spec ->
                    if (spec.watcherId in activeWatchers) return@forEach
                    trezorRepo.startWatcher(
                        watcherId = spec.watcherId,
                        extendedKey = spec.xpub,
                        network = Env.network.toCoreNetwork(),
                        accountType = spec.addressType.toAddressType()?.toAccountType(),
                    ).onSuccess { activeWatchers += spec.watcherId }
                }

                (activeWatchers - wantedIds).forEach { staleId ->
                    activeWatchers -= staleId
                    trezorRepo.stopWatcher(staleId)
                    _watcherData.update { it - staleId }
                }
            }
        }
    }

    private fun HistoryTransaction.toOnchainActivity(): Activity {
        val type = when (direction) {
            TxDirection.RECEIVED -> PaymentType.RECEIVED
            TxDirection.SENT, TxDirection.SELF_TRANSFER -> PaymentType.SENT
        }
        val activityTimestamp = timestamp ?: (System.currentTimeMillis() / 1000).toULong()
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

@Stable
data class HwWallet(
    val id: String,
    val name: String,
    val model: String?,
    val transportType: KnownDeviceTransportType,
    val isConnected: Boolean,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
)

private data class HwWatcherData(
    val deviceId: String,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
)
