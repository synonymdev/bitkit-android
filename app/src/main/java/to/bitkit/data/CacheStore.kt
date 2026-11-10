package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.data.serializers.AppCacheSerializer
import to.bitkit.ext.scopedActivityId
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.BalanceState
import to.bitkit.models.FxRate
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.QuickPayLedger
import to.bitkit.models.WalletScope
import to.bitkit.repositories.PendingSparkWithdrawal
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many paid send hashes to keep per rail; an older paid send's invoice has long expired, so the other rail
 * cannot pay it again. Sends that may still be pending are always kept, past this cap too.
 */
internal const val MAX_SPARK_SEND_HASHES = 100

private val Context.appCacheDataStore: DataStore<AppCacheData> by dataStore(
    fileName = "app_cache.json",
    serializer = AppCacheSerializer
)

@Singleton
class CacheStore internal constructor(
    private val store: DataStore<AppCacheData>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.appCacheDataStore)

    val data: Flow<AppCacheData> = store.data
    val backupStatuses: Flow<Map<BackupCategory, BackupItemStatus>> = data.map { it.backupStatuses }

    suspend fun update(transform: (AppCacheData) -> AppCacheData) {
        store.updateData(transform)
    }

    suspend fun setOnchainAddress(address: String) {
        store.updateData { it.copy(onchainAddress = address) }
    }

    suspend fun setActivityContactDetached(activityId: String, walletId: String, detached: Boolean) {
        val id = scopedActivityId(walletId, activityId)
        store.updateData {
            val contacts = it.detachedActivityContacts
            it.copy(detachedActivityContacts = if (detached) contacts + id else contacts - id)
        }
    }

    suspend fun saveBolt11(bolt11: String, paymentHash: String) {
        store.updateData { it.copy(bolt11 = bolt11, bolt11PaymentHash = paymentHash) }
    }

    suspend fun setBip21(bip21: String) {
        store.updateData { it.copy(bip21 = bip21) }
    }

    suspend fun invalidateReceiveLightningInvoice(expectedBolt11: String): Boolean {
        var invalidated = false
        store.updateData {
            if (it.bolt11 == expectedBolt11) {
                invalidated = true
                it.invalidateReceiveLightningInvoice()
            } else {
                it
            }
        }
        return invalidated
    }

    suspend fun invalidateReceiveOnchainAddress(expectedAddress: String): Boolean {
        var invalidated = false
        store.updateData {
            if (it.onchainAddress == expectedAddress) {
                invalidated = true
                it.invalidateReceiveOnchainAddress()
            } else {
                it
            }
        }
        return invalidated
    }

    suspend fun cacheBalance(balanceState: BalanceState) {
        store.updateData { it.copy(balance = balanceState) }
    }

    suspend fun updateBackupStatus(category: BackupCategory, transform: (BackupItemStatus) -> BackupItemStatus) {
        store.updateData { currentData ->
            val currentStatus = currentData.backupStatuses[category] ?: BackupItemStatus()
            val updatedStatus = transform(currentStatus)
            val updatedStatuses = currentData.backupStatuses.toMutableMap()
            updatedStatuses[category] = updatedStatus
            currentData.copy(backupStatuses = updatedStatuses)
        }
    }

    suspend fun addActivityToDeletedList(
        activityId: String,
        walletId: String = WalletScope.default,
    ) {
        if (activityId.isBlank()) return
        val scopedId = scopedActivityId(walletId, activityId)
        if (scopedId in store.data.first().deletedActivities) return
        store.updateData {
            it.copy(deletedActivities = it.deletedActivities + scopedId)
        }
    }

    suspend fun addActivityToPendingBoost(pendingBoostActivity: PendingBoostActivity) {
        if (pendingBoostActivity in store.data.first().pendingBoostActivities) return
        store.updateData {
            it.copy(pendingBoostActivities = it.pendingBoostActivities + pendingBoostActivity)
        }
    }

    suspend fun removeActivityFromPendingBoost(pendingBoostActivity: PendingBoostActivity) {
        if (pendingBoostActivity !in store.data.first().pendingBoostActivities) return
        store.updateData {
            it.copy(pendingBoostActivities = it.pendingBoostActivities - pendingBoostActivity)
        }
    }

    suspend fun setPendingLightningMessage(paymentHash: String, message: String) {
        store.updateData { it.copy(pendingLightningMessages = it.pendingLightningMessages + (paymentHash to message)) }
    }

    suspend fun removePendingLightningMessage(paymentHash: String) {
        if (paymentHash !in store.data.first().pendingLightningMessages) return
        store.updateData { it.copy(pendingLightningMessages = it.pendingLightningMessages - paymentHash) }
    }

    suspend fun addSparkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        store.updateData {
            it.copy(sparkSendHashes = it.sparkSendHashes - hash + hash)
        }
    }

    suspend fun removeSparkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        if (hash !in store.data.first().sparkSendHashes) return
        store.updateData { it.copy(sparkSendHashes = it.sparkSendHashes - hash) }
    }

    suspend fun addLdkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        store.updateData {
            it.copy(ldkSendHashes = it.ldkSendHashes - hash + hash)
        }
    }

    /** Keeps [paymentHashes], the node's pending and paid sends, once, ahead of the hashes kept since. */
    suspend fun seedLdkSendHashes(paymentHashes: List<String>) {
        if (store.data.first().ldkSendHashesSeeded) return
        store.updateData { data ->
            if (data.ldkSendHashesSeeded) return@updateData data
            val seeded = paymentHashes.map { it.lowercase() }.distinct() - data.ldkSendHashes.toSet()
            data.copy(
                ldkSendHashes = seeded + data.ldkSendHashes,
                ldkSendHashesSeeded = true,
            )
        }
    }

    /** Past the cap, drops the oldest of the node's sends [paid] lists as paid; a send that may be pending stays. */
    suspend fun trimLdkSendHashes(paid: Set<String>) {
        if (store.data.first().ldkSendHashes.size <= MAX_SPARK_SEND_HASHES) return
        store.updateData { it.copy(ldkSendHashes = it.ldkSendHashes.withoutOldestPaid(paid)) }
    }

    /** Past the cap, drops the oldest of Spark's sends [paid] lists as paid; a send that may be pending stays. */
    suspend fun trimSparkSendHashes(paid: Set<String>) {
        if (store.data.first().sparkSendHashes.size <= MAX_SPARK_SEND_HASHES) return
        store.updateData { it.copy(sparkSendHashes = it.sparkSendHashes.withoutOldestPaid(paid)) }
    }

    private fun List<String>.withoutOldestPaid(paid: Set<String>): List<String> {
        val lowercasePaid = paid.mapTo(mutableSetOf()) { it.lowercase() }
        var excess = size - MAX_SPARK_SEND_HASHES
        return filterNot { hash -> (excess > 0 && hash in lowercasePaid).also { if (it) excess-- } }
    }

    suspend fun removeLdkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        if (hash !in store.data.first().ldkSendHashes) return
        store.updateData { it.copy(ldkSendHashes = it.ldkSendHashes - hash) }
    }

    suspend fun setBackgroundReceive(details: NewTransactionSheetDetails) = store.updateData {
        it.copy(backgroundReceive = details)
    }

    suspend fun clearBackgroundReceive() {
        store.updateData { it.copy(backgroundReceive = null) }
    }

    suspend fun reset() {
        store.updateData { AppCacheData() }
        Logger.info("Deleted all app cached data.")
    }
}

@Serializable
data class AppCacheData(
    val cachedRates: List<FxRate> = listOf(),
    val onchainAddress: String = "",
    val bolt11: String = "",
    val bolt11PaymentHash: String = "",
    val bip21: String = "",
    val balance: BalanceState? = null,
    val usdtDisplayBalance: CachedUsdtBalance? = null,
    val backupStatuses: Map<BackupCategory, BackupItemStatus> = mapOf(),
    val deletedActivities: List<String> = listOf(),
    val detachedActivityContacts: Set<String> = emptySet(),
    val pendingBoostActivities: List<PendingBoostActivity> = listOf(),
    val backgroundReceive: NewTransactionSheetDetails? = null,
    val addressSearchLastUsedReceiveIndexes: Map<String, Int> = mapOf(),
    val addressSearchLastUsedChangeIndexes: Map<String, Int> = mapOf(),
    val quickPayLedger: QuickPayLedger? = null,
    /** LNURL-pay comments by payment hash, kept until the sent payment's activity stores them. */
    val pendingLightningMessages: Map<String, String> = mapOf(),
    /** A Spark withdrawal to a Paykit contact that started, kept until Spark's payment list settles it. */
    val pendingSparkWithdrawal: PendingSparkWithdrawal? = null,
    /** Hashes of the latest Lightning sends Spark started, so the node holds off while Spark can't list them. */
    val sparkSendHashes: List<String> = listOf(),
    /** Hashes of the Lightning sends the node started, so Spark holds off while the node can't list them. */
    val ldkSendHashes: List<String> = listOf(),
    /**
     * Set once the node's own list has filled [ldkSendHashes], as node sends made before hashes were kept carry none
     * there. Until then an unlistable node holds every Spark send.
     */
    val ldkSendHashesSeeded: Boolean = false,
) {
    fun isContactDetached(activityId: String, walletId: String): Boolean =
        scopedActivityId(walletId, activityId) in detachedActivityContacts

    fun isActivityDeleted(activityId: String, walletId: String): Boolean =
        scopedActivityId(walletId, activityId) in deletedActivities ||
            walletId == WalletScope.default && activityId in deletedActivities

    fun resetBip21() = copy(bip21 = "", bolt11 = "", bolt11PaymentHash = "", onchainAddress = "")

    fun invalidateReceiveLightningInvoice() = copy(bip21 = "", bolt11 = "", bolt11PaymentHash = "")

    fun invalidateReceiveOnchainAddress() = copy(bip21 = "", onchainAddress = "")
}

@Serializable
data class CachedUsdtBalance(
    val address: String,
    val network: String,
    val balance: ULong,
) {
    fun balanceFor(address: String, network: String): ULong? =
        balance.takeIf { this.address == address && this.network == network }
}
