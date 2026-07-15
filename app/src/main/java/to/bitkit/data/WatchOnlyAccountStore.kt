package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.WatchOnlyAccountDataSerializer
import to.bitkit.di.IoDispatcher
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchOnlyAccountDataStore: DataStore<WatchOnlyAccountData> by dataStore(
    fileName = "watch_only_accounts.json",
    serializer = WatchOnlyAccountDataSerializer,
)

@Singleton
class WatchOnlyAccountStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val store = context.watchOnlyAccountDataStore

    val data: Flow<WatchOnlyAccountData> = store.data

    suspend fun load(): List<WatchOnlyAccountRecord> = withContext(ioDispatcher) {
        store.data.first().accounts
    }

    suspend fun backupSnapshot(): WatchOnlyAccountBackupSnapshot = withContext(ioDispatcher) {
        store.data.first().let { data ->
            WatchOnlyAccountBackupSnapshot(
                accounts = data.accounts,
                allocationState = WatchOnlyAccountAllocationState(
                    highestAccountIndexByWallet = data.highestAccountIndexByWallet,
                    pendingAccountIndexByRequest = data.pendingAccountIndexByRequest,
                ),
            )
        }
    }

    suspend fun save(accounts: List<WatchOnlyAccountRecord>) = withContext(ioDispatcher) {
        store.updateData { current ->
            current.copy(
                accounts = accounts.sortedBy(WatchOnlyAccountRecord::accountIndex),
                highestAccountIndexByWallet = current.highestAccountIndexByWallet.withAccountIndexes(accounts),
            )
        }
        Unit
    }

    suspend fun restore(
        accounts: List<WatchOnlyAccountRecord>,
        allocationState: WatchOnlyAccountAllocationState? = null,
    ) = withContext(ioDispatcher) {
        store.updateData { current -> current.restoreAccounts(accounts, allocationState) }
        Unit
    }

    suspend fun clear() = withContext(ioDispatcher) {
        store.updateData { WatchOnlyAccountData() }
        Unit
    }

    suspend fun reserveAccountIndex(walletIndex: Int, requestFingerprint: String): Int = withContext(ioDispatcher) {
        var reservedIndex: Int? = null
        store.updateData { current ->
            current.reserveAccountIndex(walletIndex, requestFingerprint).also {
                reservedIndex = it.accountIndex
            }.data
        }
        checkNotNull(reservedIndex)
    }

    suspend fun markActive(id: String) = withContext(ioDispatcher) {
        store.updateData { current -> current.markAccountActive(id) }
        Unit
    }

    suspend fun update(transform: (List<WatchOnlyAccountRecord>) -> List<WatchOnlyAccountRecord>) =
        withContext(ioDispatcher) {
            store.updateData { current ->
                current.copy(accounts = transform(current.accounts).sortedBy(WatchOnlyAccountRecord::accountIndex))
            }
            Unit
        }
}

@Serializable
data class WatchOnlyAccountData(
    val accounts: List<WatchOnlyAccountRecord> = emptyList(),
    val highestAccountIndexByWallet: Map<String, Int> = emptyMap(),
    val pendingAccountIndexByRequest: Map<String, Int> = emptyMap(),
)

@Serializable
data class WatchOnlyAccountAllocationState(
    val highestAccountIndexByWallet: Map<String, Int> = emptyMap(),
    val pendingAccountIndexByRequest: Map<String, Int> = emptyMap(),
)

data class WatchOnlyAccountBackupSnapshot(
    val accounts: List<WatchOnlyAccountRecord>,
    val allocationState: WatchOnlyAccountAllocationState,
)

internal data class WatchOnlyAccountIndexReservation(
    val data: WatchOnlyAccountData,
    val accountIndex: Int,
)

internal fun WatchOnlyAccountData.reserveAccountIndex(
    walletIndex: Int,
    requestFingerprint: String,
): WatchOnlyAccountIndexReservation {
    val requestKey = "$walletIndex:$requestFingerprint"
    pendingAccountIndexByRequest[requestKey]?.let {
        return WatchOnlyAccountIndexReservation(data = this, accountIndex = it)
    }

    val walletKey = walletIndex.toString()
    val highestPersistedIndex = accounts
        .filter { it.walletIndex == walletIndex }
        .maxOfOrNull(WatchOnlyAccountRecord::accountIndex) ?: 0
    val highestAccountIndex = maxOf(highestAccountIndexByWallet[walletKey] ?: 0, highestPersistedIndex)
    check(highestAccountIndex < Int.MAX_VALUE) { "Watch-only account index overflow" }

    val reservedIndex = highestAccountIndex + 1
    return WatchOnlyAccountIndexReservation(
        data = copy(
            highestAccountIndexByWallet = highestAccountIndexByWallet + (walletKey to reservedIndex),
            pendingAccountIndexByRequest = pendingAccountIndexByRequest + (requestKey to reservedIndex),
        ),
        accountIndex = reservedIndex,
    )
}

internal fun WatchOnlyAccountData.completeAccountIndexAllocation(requestKey: String): WatchOnlyAccountData =
    copy(pendingAccountIndexByRequest = pendingAccountIndexByRequest - requestKey)

internal fun WatchOnlyAccountData.markAccountActive(id: String): WatchOnlyAccountData {
    val account = accounts.firstOrNull { it.id == id } ?: return this
    val requestKey = "${account.walletIndex}:${account.requestFingerprint}"
    val updatedAccounts = accounts.map {
        if (it.id == id) {
            it.copy(isTrackingEnabled = true, setupState = WatchOnlyAccountSetupState.Active)
        } else {
            it
        }
    }
    return copy(
        accounts = updatedAccounts,
        pendingAccountIndexByRequest = pendingAccountIndexByRequest - requestKey,
    )
}

internal fun WatchOnlyAccountData.restoreAccounts(
    accounts: List<WatchOnlyAccountRecord>,
    allocationState: WatchOnlyAccountAllocationState? = null,
): WatchOnlyAccountData {
    val restoredHighestIndexes = allocationState?.highestAccountIndexByWallet.orEmpty()
    val mergedHighestIndexes = restoredHighestIndexes.entries
        .fold(highestAccountIndexByWallet) { indexes, (wallet, index) ->
            indexes + (wallet to maxOf(indexes[wallet] ?: 0, index))
        }.withAccountIndexes(accounts)
    val mergedPendingAllocations = allocationState?.pendingAccountIndexByRequest.orEmpty()

    return copy(
        accounts = accounts.sortedBy(WatchOnlyAccountRecord::accountIndex),
        highestAccountIndexByWallet = mergedHighestIndexes,
        pendingAccountIndexByRequest = mergedPendingAllocations,
    )
}

private fun Map<String, Int>.withAccountIndexes(accounts: List<WatchOnlyAccountRecord>): Map<String, Int> {
    val updated = toMutableMap()
    accounts.groupBy(WatchOnlyAccountRecord::walletIndex).forEach { (walletIndex, walletAccounts) ->
        val highestAccountIndex = walletAccounts.maxOf(WatchOnlyAccountRecord::accountIndex)
        val walletKey = walletIndex.toString()
        updated[walletKey] = maxOf(updated[walletKey] ?: 0, highestAccountIndex)
    }
    return updated
}
