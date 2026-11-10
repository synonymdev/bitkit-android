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
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * A listing drops a rail's send hashes it shows failed only, or paid once the invoice has expired, so the other rail
 * cannot pay it again. Paid sends with no known expiry (kept before expiries were, or whose invoice can't be parsed)
 * are dropped oldest first only past this many hashes. Pending sends and ones no listing has shown yet always stay.
 */
internal const val MAX_SPARK_SEND_HASHES = 100

private val Context.appCacheDataStore: DataStore<AppCacheData> by dataStore(
    fileName = "app_cache.json",
    serializer = AppCacheSerializer
)

@Singleton
class CacheStore internal constructor(
    private val store: DataStore<AppCacheData>,
    private val clock: Clock = Clock.System,
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

    /** Keeps [paymentHash] with its invoice's expiry in epoch seconds, [expiresAt], when known. */
    suspend fun addSparkSendHash(paymentHash: String, expiresAt: Long? = null) {
        val hash = paymentHash.lowercase()
        store.updateData {
            it.copy(
                sparkSendHashes = it.sparkSendHashes - hash + hash,
                sparkSendExpiries = it.sparkSendExpiries.with(hash, expiresAt),
            )
        }
    }

    suspend fun removeSparkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        if (hash !in store.data.first().sparkSendHashes) return
        store.updateData {
            it.copy(sparkSendHashes = it.sparkSendHashes - hash, sparkSendExpiries = it.sparkSendExpiries - hash)
        }
    }

    /** Keeps [paymentHash] with its invoice's expiry in epoch seconds, [expiresAt], when known. */
    suspend fun addLdkSendHash(paymentHash: String, expiresAt: Long? = null) {
        val hash = paymentHash.lowercase()
        store.updateData {
            it.copy(
                ldkSendHashes = it.ldkSendHashes - hash + hash,
                ldkSendExpiries = it.ldkSendExpiries.with(hash, expiresAt),
            )
        }
    }

    private fun Map<String, Long>.with(hash: String, expiresAt: Long?) =
        if (expiresAt == null) this else this + (hash to expiresAt)

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

    /**
     * Drops the node's sends a listing showed as [failed] only, or as [paid] with an expired invoice, and past the cap
     * the oldest paid ones with no known expiry; a send that may be pending is never passed in, so it stays.
     */
    suspend fun trimLdkSendHashes(paid: Set<String>, failed: Set<String>) {
        store.updateData {
            val (hashes, expiries) = withoutSettled(it.ldkSendHashes, it.ldkSendExpiries, paid, failed)
            it.copy(ldkSendHashes = hashes, ldkSendExpiries = expiries)
        }
    }

    /** Drops Spark's sends as [trimLdkSendHashes] drops the node's. */
    suspend fun trimSparkSendHashes(paid: Set<String>, failed: Set<String>) {
        store.updateData {
            val (hashes, expiries) = withoutSettled(it.sparkSendHashes, it.sparkSendExpiries, paid, failed)
            it.copy(sparkSendHashes = hashes, sparkSendExpiries = expiries)
        }
    }

    private fun withoutSettled(
        hashes: List<String>,
        expiries: Map<String, Long>,
        paid: Set<String>,
        failed: Set<String>,
    ): Pair<List<String>, Map<String, Long>> {
        val now = clock.now().epochSeconds
        val paidHashes = paid.mapTo(mutableSetOf()) { it.lowercase() }
        val failedHashes = failed.mapTo(mutableSetOf()) { it.lowercase() }
        val listed = hashes.filterNot { hash ->
            hash in failedHashes || hash in paidHashes && expiries[hash]?.let { it <= now } == true
        }
        var excess = listed.size - MAX_SPARK_SEND_HASHES
        val kept = listed.filterNot { hash ->
            (excess > 0 && hash in paidHashes && hash !in expiries).also { if (it) excess-- }
        }
        return kept to expiries.filterKeys { it in kept }
    }

    suspend fun removeLdkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        if (hash !in store.data.first().ldkSendHashes) return
        store.updateData {
            it.copy(ldkSendHashes = it.ldkSendHashes - hash, ldkSendExpiries = it.ldkSendExpiries - hash)
        }
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
    /** Hashes of the latest Lightning sends Spark started, so the node holds off while Spark can't list them. */
    val sparkSendHashes: List<String> = listOf(),
    /** Invoice expiry in epoch seconds of the [sparkSendHashes] that know it. */
    val sparkSendExpiries: Map<String, Long> = mapOf(),
    /** Hashes of the Lightning sends the node started, so Spark holds off while the node can't list them. */
    val ldkSendHashes: List<String> = listOf(),
    /** Invoice expiry in epoch seconds of the [ldkSendHashes] that know it. */
    val ldkSendExpiries: Map<String, Long> = mapOf(),
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
