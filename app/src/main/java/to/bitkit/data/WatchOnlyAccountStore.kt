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
import org.bitcoinj.base.Base58
import to.bitkit.data.serializers.WatchOnlyAccountDataSerializer
import to.bitkit.di.IoDispatcher
import to.bitkit.models.WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE
import to.bitkit.models.WATCH_ONLY_ACCOUNT_SERIALIZED_XPUB_LENGTH
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

    suspend fun loadReconciliationState(): WatchOnlyAccountReconciliationState = withContext(ioDispatcher) {
        store.data.first().let { data ->
            WatchOnlyAccountReconciliationState(
                accounts = data.accounts,
                accountsPendingRemoval = data.accountsPendingRemoval,
            )
        }
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

    suspend fun completeReconciliation(walletIndex: Int) = withContext(ioDispatcher) {
        store.updateData { current -> current.completeReconciliation(walletIndex) }
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
    val accountsPendingRemoval: List<WatchOnlyAccountRecord> = emptyList(),
    val highestAccountIndexByWallet: Map<String, Int> = emptyMap(),
    val pendingAccountIndexByRequest: Map<String, Int> = emptyMap(),
)

data class WatchOnlyAccountReconciliationState(
    val accounts: List<WatchOnlyAccountRecord>,
    val accountsPendingRemoval: List<WatchOnlyAccountRecord>,
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

internal fun WatchOnlyAccountData.markAccountActive(id: String): WatchOnlyAccountData {
    val account = checkNotNull(accounts.firstOrNull { it.id == id }) {
        "Watch-only account '$id' not found"
    }
    val requestKey = account.allocationRequestKey()
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
    val restoredAccounts = accounts.sanitizedAccounts()
    val locallyManagedAccounts = uniqueAccountsByManagementKey(this.accounts + accountsPendingRemoval)
    val protectedLocalAccounts = locallyManagedAccounts
        .filter { localAccount ->
            localAccount.setupState == WatchOnlyAccountSetupState.Authorizing ||
                localAccount.shouldPreserveFrom(restoredAccounts)
        }
        .sanitizedAccounts()
    val mergedAccounts = (protectedLocalAccounts + restoredAccounts).sanitizedAccounts()
    val mergedManagementKeys = mergedAccounts.mapTo(mutableSetOf(), WatchOnlyAccountRecord::managementKey)
    val updatedAccountsPendingRemoval = uniqueAccountsByManagementKey(this.accounts + accountsPendingRemoval)
        .filterNot { it.managementKey() in mergedManagementKeys }

    val validLocalPendingAccountIndexes = pendingAccountIndexByRequest.validPendingAccountIndexes()
    val localHighestIndexes = highestAccountIndexByWallet
        .validHighestAccountIndexes()
        .withAccountIndexes(locallyManagedAccounts)
        .withPendingAccountIndexes(validLocalPendingAccountIndexes)
    val highestIndexes = allocationState?.highestAccountIndexByWallet
        .orEmpty()
        .validHighestAccountIndexes()
        .entries
        .fold(localHighestIndexes) { indexes, (wallet, index) ->
            indexes + (wallet to maxOf(indexes[wallet] ?: 0, index))
        }
        .withAccountIndexes(locallyManagedAccounts + accounts + updatedAccountsPendingRemoval)

    val retainedLocalPendingAccountIndexes = if (allocationState == null) {
        emptyMap()
    } else {
        validLocalPendingAccountIndexes
    }
    val mergedPendingAccountIndexes = mergedPendingAccountIndexes(
        accounts = mergedAccounts,
        blockedAccounts = updatedAccountsPendingRemoval,
        localPendingAccountIndexes = retainedLocalPendingAccountIndexes,
        restoredPendingAccountIndexes = allocationState?.pendingAccountIndexByRequest.orEmpty(),
        localHighestAccountIndexByWallet = localHighestIndexes,
    )

    return copy(
        accounts = mergedAccounts,
        accountsPendingRemoval = updatedAccountsPendingRemoval,
        highestAccountIndexByWallet = highestIndexes.withPendingAccountIndexes(mergedPendingAccountIndexes),
        pendingAccountIndexByRequest = mergedPendingAccountIndexes,
    )
}

private fun WatchOnlyAccountRecord.managementKey(): String = "$walletIndex:$addressType:$accountIndex"

private fun WatchOnlyAccountRecord.allocationRequestKey(): String = "$walletIndex:$requestFingerprint"

private fun WatchOnlyAccountRecord.normalizedTrackingState(): WatchOnlyAccountRecord = when (setupState) {
    WatchOnlyAccountSetupState.PendingDelivery -> copy(isTrackingEnabled = false)
    WatchOnlyAccountSetupState.Authorizing -> copy(isTrackingEnabled = true)
    WatchOnlyAccountSetupState.Active -> this
}

private fun WatchOnlyAccountRecord.isUsableAccount(): Boolean = walletIndex >= 0 &&
    accountIndex > 0 &&
    addressType == WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE &&
    runCatching { Base58.decodeChecked(xpub).size == WATCH_ONLY_ACCOUNT_SERIALIZED_XPUB_LENGTH }
        .getOrDefault(false)

private fun List<WatchOnlyAccountRecord>.sanitizedAccounts(): List<WatchOnlyAccountRecord> {
    val ids = mutableSetOf<String>()
    val managementKeys = mutableSetOf<String>()
    val incompleteRequestKeys = mutableSetOf<String>()

    return mapNotNull { input ->
        if (!input.isUsableAccount()) return@mapNotNull null
        val account = input.normalizedTrackingState()
        val incompleteRequestKey = account
            .takeIf { it.setupState != WatchOnlyAccountSetupState.Active }
            ?.allocationRequestKey()
        if (account.id in ids || account.managementKey() in managementKeys) return@mapNotNull null
        if (incompleteRequestKey != null && incompleteRequestKey in incompleteRequestKeys) return@mapNotNull null
        ids += account.id
        managementKeys += account.managementKey()
        incompleteRequestKey?.let(incompleteRequestKeys::add)
        account
    }.sortedWith(
        compareBy(
            WatchOnlyAccountRecord::walletIndex,
            WatchOnlyAccountRecord::accountIndex,
            WatchOnlyAccountRecord::createdAt,
        ),
    )
}

private fun uniqueAccountsByManagementKey(accounts: List<WatchOnlyAccountRecord>): List<WatchOnlyAccountRecord> =
    accounts.distinctBy(WatchOnlyAccountRecord::managementKey)
        .sortedWith(compareBy(WatchOnlyAccountRecord::walletIndex, WatchOnlyAccountRecord::accountIndex))

private fun WatchOnlyAccountRecord.shouldPreserveFrom(
    restoredAccounts: List<WatchOnlyAccountRecord>,
): Boolean {
    val conflicts = restoredAccounts.filter {
        it.id == id || it.managementKey() == managementKey()
    }
    if (conflicts.isEmpty()) return false
    if (conflicts.any { !hasSameOwner(it) }) return true
    return setupState == WatchOnlyAccountSetupState.Active &&
        conflicts.all { it.setupState != WatchOnlyAccountSetupState.Active }
}

private fun WatchOnlyAccountRecord.hasSameOwner(other: WatchOnlyAccountRecord): Boolean =
    managementKey() == other.managementKey() &&
        requestFingerprint == other.requestFingerprint &&
        xpub == other.xpub

private data class AccountIndexKey(
    val walletIndex: Int,
    val accountIndex: Int,
)

private fun mergedPendingAccountIndexes(
    accounts: List<WatchOnlyAccountRecord>,
    blockedAccounts: List<WatchOnlyAccountRecord>,
    localPendingAccountIndexes: Map<String, Int>,
    restoredPendingAccountIndexes: Map<String, Int>,
    localHighestAccountIndexByWallet: Map<String, Int>,
): Map<String, Int> {
    val activeSlots = accounts
        .filter { it.setupState == WatchOnlyAccountSetupState.Active }
        .mapTo(mutableSetOf()) { AccountIndexKey(it.walletIndex, it.accountIndex) }
    val blockedSlots = blockedAccounts
        .mapTo(mutableSetOf()) { AccountIndexKey(it.walletIndex, it.accountIndex) }
    val pendingAccountIndexes = linkedMapOf<String, Int>()
    val reservedSlots = mutableSetOf<AccountIndexKey>()

    fun reserve(requestKey: String, accountIndex: Int, allowsHistoricalIndex: Boolean) {
        val walletIndex = requestKey.allocationWalletIndex() ?: return
        val slot = AccountIndexKey(walletIndex, accountIndex)
        if (requestKey in pendingAccountIndexes || accountIndex <= 0) return
        if (slot in reservedSlots || slot in activeSlots || slot in blockedSlots) return
        if (
            !allowsHistoricalIndex &&
            accountIndex <= (localHighestAccountIndexByWallet[walletIndex.toString()] ?: 0)
        ) {
            return
        }
        pendingAccountIndexes[requestKey] = accountIndex
        reservedSlots += slot
    }

    accounts.filter { it.setupState != WatchOnlyAccountSetupState.Active }.forEach {
        reserve(it.allocationRequestKey(), it.accountIndex, allowsHistoricalIndex = true)
    }
    localPendingAccountIndexes.toSortedMap().forEach { (requestKey, accountIndex) ->
        reserve(requestKey, accountIndex, allowsHistoricalIndex = true)
    }
    restoredPendingAccountIndexes.toSortedMap().forEach { (requestKey, accountIndex) ->
        reserve(requestKey, accountIndex, allowsHistoricalIndex = false)
    }

    return pendingAccountIndexes
}

private fun Map<String, Int>.validHighestAccountIndexes(): Map<String, Int> =
    entries.fold(emptyMap()) { indexes, (wallet, index) ->
        val walletIndex = wallet.toIntOrNull()?.takeIf { it >= 0 }
            ?: return@fold indexes
        if (index <= 0) return@fold indexes
        val walletKey = walletIndex.toString()
        indexes + (walletKey to maxOf(indexes[walletKey] ?: 0, index))
    }

private fun Map<String, Int>.validPendingAccountIndexes(): Map<String, Int> =
    filter { (requestKey, accountIndex) ->
        requestKey.allocationWalletIndex() != null && accountIndex > 0
    }

private fun String.allocationWalletIndex(): Int? {
    val separatorIndex = indexOf(':')
    if (separatorIndex <= 0 || separatorIndex == lastIndex) return null
    return substring(0, separatorIndex).toIntOrNull()?.takeIf { it >= 0 }
}

private fun Map<String, Int>.withPendingAccountIndexes(
    pendingAccountIndexes: Map<String, Int>,
): Map<String, Int> {
    val updated = toMutableMap()
    pendingAccountIndexes.forEach { (requestKey, accountIndex) ->
        val walletIndex = requestKey.allocationWalletIndex() ?: return@forEach
        if (accountIndex <= 0) return@forEach
        val walletKey = walletIndex.toString()
        updated[walletKey] = maxOf(updated[walletKey] ?: 0, accountIndex)
    }
    return updated
}

internal fun WatchOnlyAccountData.completeReconciliation(walletIndex: Int): WatchOnlyAccountData = copy(
    accountsPendingRemoval = accountsPendingRemoval.filterNot { it.walletIndex == walletIndex },
)

private fun Map<String, Int>.withAccountIndexes(accounts: List<WatchOnlyAccountRecord>): Map<String, Int> {
    val updated = toMutableMap()
    accounts.filter { it.walletIndex >= 0 && it.accountIndex > 0 }
        .groupBy(WatchOnlyAccountRecord::walletIndex).forEach { (walletIndex, walletAccounts) ->
            val highestAccountIndex = walletAccounts.maxOf(WatchOnlyAccountRecord::accountIndex)
            val walletKey = walletIndex.toString()
            updated[walletKey] = maxOf(updated[walletKey] ?: 0, highestAccountIndex)
        }
    return updated
}
