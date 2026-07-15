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

internal fun WatchOnlyAccountData.completeAccountIndexAllocation(requestKey: String): WatchOnlyAccountData =
    copy(pendingAccountIndexByRequest = pendingAccountIndexByRequest - requestKey)

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
    val locallyManagedAccounts = this.accounts + accountsPendingRemoval
    val authorizingAccounts = locallyManagedAccounts.uniqueAuthorizingAccounts()
    val authorizingIds = authorizingAccounts.mapTo(mutableSetOf(), WatchOnlyAccountRecord::id)
    val authorizingKeys = authorizingAccounts.mapTo(mutableSetOf(), WatchOnlyAccountRecord::managementKey)
    val authorizingRequestKeys = authorizingAccounts.mapTo(mutableSetOf(), WatchOnlyAccountRecord::allocationRequestKey)
    val protectedRestorationConflicts = uniqueLogicalAccounts(
        prioritizedAccounts = emptyList(),
        remainingAccounts = locallyManagedAccounts.filter { account ->
            account.setupState != WatchOnlyAccountSetupState.Authorizing &&
                account.id !in authorizingIds &&
                account.managementKey() !in authorizingKeys &&
                (
                    account.setupState == WatchOnlyAccountSetupState.Active ||
                        account.allocationRequestKey() !in authorizingRequestKeys
                    ) &&
                account.shouldProtectFrom(accounts)
        }.map { it.promotedTrackingState(accounts) },
    )
    val protectedLocalAccounts = authorizingAccounts + protectedRestorationConflicts
    val mergedAccounts = uniqueLogicalAccounts(
        prioritizedAccounts = protectedLocalAccounts,
        remainingAccounts = accounts,
    )
    val restoredKeys = mergedAccounts.mapTo(mutableSetOf(), WatchOnlyAccountRecord::managementKey)
    val updatedAccountsPendingRemoval = uniqueAccountsByManagementKey(
        (accountsPendingRemoval + this.accounts).filterNot { it.managementKey() in restoredKeys },
    )
    val localHighestIndexes = highestAccountIndexByWallet
        .withAccountIndexes(this.accounts + accountsPendingRemoval)
        .withPendingAccountIndexes(pendingAccountIndexByRequest)
    val restoredHighestIndexes = allocationState?.highestAccountIndexByWallet.orEmpty()
    val mergedHighestIndexes = restoredHighestIndexes.entries
        .fold(localHighestIndexes) { indexes, (wallet, index) ->
            indexes + (wallet to maxOf(indexes[wallet] ?: 0, index))
        }.withPendingAccountIndexes(allocationState?.pendingAccountIndexByRequest.orEmpty())
        .withAccountIndexes(accounts + mergedAccounts + updatedAccountsPendingRemoval)
    val restoredPendingAllocations = allocationState?.pendingAccountIndexByRequest.orEmpty()
        .withoutAccountIndexConflicts(
            accounts = mergedAccounts,
            restoredAccounts = accounts,
            accountsPendingRemoval = updatedAccountsPendingRemoval,
            localHighestIndexes = localHighestIndexes,
            localPendingAccountIndexes = pendingAccountIndexByRequest,
        )
    val authorizingAllocations = authorizingAccounts.associate {
        it.allocationRequestKey() to it.accountIndex
    }
    val mergedPendingAllocations = restoredPendingAllocations + authorizingAllocations
    val finalizedHighestIndexes = mergedHighestIndexes.withPendingAccountIndexes(mergedPendingAllocations)

    return copy(
        accounts = mergedAccounts.sortedBy(WatchOnlyAccountRecord::accountIndex),
        accountsPendingRemoval = updatedAccountsPendingRemoval,
        highestAccountIndexByWallet = finalizedHighestIndexes,
        pendingAccountIndexByRequest = mergedPendingAllocations,
    )
}

private fun WatchOnlyAccountRecord.managementKey(): String = "$walletIndex:$addressType:$accountIndex"

private fun WatchOnlyAccountRecord.allocationRequestKey(): String = "$walletIndex:$requestFingerprint"

private fun List<WatchOnlyAccountRecord>.uniqueAuthorizingAccounts(): List<WatchOnlyAccountRecord> =
    uniqueLogicalAccounts(
        prioritizedAccounts = emptyList(),
        remainingAccounts = filter { it.setupState == WatchOnlyAccountSetupState.Authorizing },
    )

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

private fun WatchOnlyAccountRecord.shouldProtectFrom(restoredAccounts: List<WatchOnlyAccountRecord>): Boolean {
    val conflicts = restoredAccounts.filter(WatchOnlyAccountRecord::isUsableAccount).filter {
        it.managementKey() == managementKey() || it.id == id
    }
    val highestRestoredPriority = conflicts.minOfOrNull { it.setupState.restorePriority() } ?: return false
    val localPriority = setupState.restorePriority()
    if (localPriority != highestRestoredPriority) return localPriority < highestRestoredPriority
    return conflicts.any {
        it.setupState.restorePriority() == highestRestoredPriority && !hasSameOwner(it)
    }
}

private fun WatchOnlyAccountRecord.promotedTrackingState(
    restoredAccounts: List<WatchOnlyAccountRecord>,
): WatchOnlyAccountRecord {
    if (setupState != WatchOnlyAccountSetupState.Active) return this
    val restoredOwnerRequiresTracking = restoredAccounts.filter(WatchOnlyAccountRecord::isUsableAccount).any {
        it.managementKey() == managementKey() &&
            (
                it.setupState == WatchOnlyAccountSetupState.Authorizing ||
                    it.setupState == WatchOnlyAccountSetupState.Active && it.isTrackingEnabled
                )
    }
    return if (restoredOwnerRequiresTracking) copy(isTrackingEnabled = true) else this
}

private fun WatchOnlyAccountRecord.hasSameOwner(other: WatchOnlyAccountRecord): Boolean =
    managementKey() == other.managementKey() &&
        requestFingerprint == other.requestFingerprint &&
        xpub == other.xpub

private val accountRestoreOrder = compareBy<WatchOnlyAccountRecord>(
    { it.setupState.restorePriority() },
    WatchOnlyAccountRecord::createdAt,
    WatchOnlyAccountRecord::walletIndex,
    WatchOnlyAccountRecord::accountIndex,
    WatchOnlyAccountRecord::addressType,
    WatchOnlyAccountRecord::requestFingerprint,
    WatchOnlyAccountRecord::id,
    WatchOnlyAccountRecord::xpub,
    WatchOnlyAccountRecord::name,
    { !it.isTrackingEnabled },
)

private fun WatchOnlyAccountSetupState.restorePriority(): Int = when (this) {
    WatchOnlyAccountSetupState.Active -> 0
    WatchOnlyAccountSetupState.Authorizing -> 1
    WatchOnlyAccountSetupState.PendingDelivery -> 2
}

private fun uniqueLogicalAccounts(
    prioritizedAccounts: List<WatchOnlyAccountRecord>,
    remainingAccounts: List<WatchOnlyAccountRecord>,
): List<WatchOnlyAccountRecord> {
    val ids = mutableSetOf<String>()
    val managementKeys = mutableSetOf<String>()
    val incompleteRequestKeys = mutableSetOf<String>()
    return buildList {
        (prioritizedAccounts + remainingAccounts.sortedWith(accountRestoreOrder))
            .filter(WatchOnlyAccountRecord::isUsableAccount)
            .forEach { account ->
                val incompleteRequestKey = account
                    .takeIf { it.setupState != WatchOnlyAccountSetupState.Active }
                    ?.allocationRequestKey()
                if (account.id in ids || account.managementKey() in managementKeys) return@forEach
                if (incompleteRequestKey != null && incompleteRequestKey in incompleteRequestKeys) return@forEach
                add(account.normalizedTrackingState())
                ids += account.id
                managementKeys += account.managementKey()
                incompleteRequestKey?.let(incompleteRequestKeys::add)
            }
    }
}

private fun uniqueAccountsByManagementKey(accounts: List<WatchOnlyAccountRecord>): List<WatchOnlyAccountRecord> =
    accounts.sortedWith(accountRestoreOrder).distinctBy(WatchOnlyAccountRecord::managementKey)

private data class AccountIndexKey(
    val walletIndex: Int,
    val accountIndex: Int,
)

private data class PendingAccountIndexReservation(
    val requestKey: String,
    val accountIndexKey: AccountIndexKey,
)

private fun Map<String, Int>.withoutAccountIndexConflicts(
    accounts: List<WatchOnlyAccountRecord>,
    restoredAccounts: List<WatchOnlyAccountRecord>,
    accountsPendingRemoval: List<WatchOnlyAccountRecord>,
    localHighestIndexes: Map<String, Int>,
    localPendingAccountIndexes: Map<String, Int>,
): Map<String, Int> {
    val accountsByIndex = accounts.groupBy { AccountIndexKey(it.walletIndex, it.accountIndex) }
    val incompleteAccountIndexesByRequest = accounts
        .filter { it.setupState != WatchOnlyAccountSetupState.Active }
        .associate { it.allocationRequestKey() to AccountIndexKey(it.walletIndex, it.accountIndex) }
    val restoredIncompleteRequestKeys = restoredAccounts.asSequence()
        .filter { it.setupState != WatchOnlyAccountSetupState.Active }
        .mapTo(mutableSetOf(), WatchOnlyAccountRecord::allocationRequestKey)
    val restoredAccountIndexes = restoredAccounts.mapTo(mutableSetOf()) {
        AccountIndexKey(it.walletIndex, it.accountIndex)
    }
    val indexesPendingRemoval = accountsPendingRemoval.mapTo(mutableSetOf()) {
        AccountIndexKey(it.walletIndex, it.accountIndex)
    }
    return entries.asSequence()
        .mapNotNull { (requestKey, accountIndex) -> pendingReservation(requestKey, accountIndex) }
        .filter { reservation ->
            if (reservation.accountIndexKey in indexesPendingRemoval) return@filter false
            val localAccountIndex = localPendingAccountIndexes[reservation.requestKey]
            if (localAccountIndex != null && localAccountIndex != reservation.accountIndexKey.accountIndex) {
                return@filter false
            }
            val incompleteAccountIndex = incompleteAccountIndexesByRequest[reservation.requestKey]
            if (reservation.requestKey in restoredIncompleteRequestKeys && incompleteAccountIndex == null) {
                return@filter false
            }
            if (incompleteAccountIndex != null && incompleteAccountIndex != reservation.accountIndexKey) {
                return@filter false
            }
            if (reservation.conflictsWithRestoredAccount(restoredAccountIndexes, incompleteAccountIndex)) {
                return@filter false
            }
            val occupyingAccounts = accountsByIndex[reservation.accountIndexKey].orEmpty()
            if (occupyingAccounts.isEmpty()) {
                val localHighestIndex = localHighestIndexes[reservation.accountIndexKey.walletIndex.toString()] ?: 0
                return@filter localAccountIndex == reservation.accountIndexKey.accountIndex ||
                    reservation.accountIndexKey.accountIndex > localHighestIndex
            }
            occupyingAccounts.all {
                it.setupState != WatchOnlyAccountSetupState.Active &&
                    it.allocationRequestKey() == reservation.requestKey
            }
        }
        .sortedBy(PendingAccountIndexReservation::requestKey)
        .distinctBy(PendingAccountIndexReservation::accountIndexKey)
        .associate { it.requestKey to it.accountIndexKey.accountIndex }
}

private fun pendingReservation(requestKey: String, accountIndex: Int): PendingAccountIndexReservation? {
    val walletIndex = requestKey.allocationWalletIndex() ?: return null
    if (accountIndex <= 0) return null
    return PendingAccountIndexReservation(
        requestKey = requestKey,
        accountIndexKey = AccountIndexKey(walletIndex, accountIndex),
    )
}

private fun PendingAccountIndexReservation.conflictsWithRestoredAccount(
    restoredAccountIndexes: Set<AccountIndexKey>,
    incompleteAccountIndex: AccountIndexKey?,
): Boolean = accountIndexKey in restoredAccountIndexes && incompleteAccountIndex != accountIndexKey

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
    accounts.groupBy(WatchOnlyAccountRecord::walletIndex).forEach { (walletIndex, walletAccounts) ->
        val highestAccountIndex = walletAccounts.maxOf(WatchOnlyAccountRecord::accountIndex)
        val walletKey = walletIndex.toString()
        updated[walletKey] = maxOf(updated[walletKey] ?: 0, highestAccountIndex)
    }
    return updated
}
