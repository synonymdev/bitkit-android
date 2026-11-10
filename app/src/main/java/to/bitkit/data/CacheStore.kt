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
 * cannot pay it again, and ones it doesn't show once the invoice expired [UNLISTED_SEND_SETTLE_SECS] ago. Paid or
 * unlisted sends with no known expiry (kept before expiries were, or whose invoice can't be parsed) are dropped oldest
 * first only past this many hashes. A send that may be pending always stays.
 */
internal const val MAX_SPARK_SEND_HASHES = 100

/**
 * How long past its invoice's expiry a send no listing shows may still be pending: no send starts after the expiry,
 * and one settles within its HTLC timeout, which LDK caps at 1008 blocks (about a week) and Spark's routes keep
 * shorter, so twice that cap leaves room for slow blocks.
 */
internal const val UNLISTED_SEND_SETTLE_SECS = 14L * 24 * 60 * 60

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
     * Drops the node's sends a listing showed as [failed] only, or as [paid] with an expired invoice, ones it didn't
     * show ([listed] is every hash it shows) whose invoice expired past [UNLISTED_SEND_SETTLE_SECS], and past the cap
     * the oldest paid or unlisted ones with no known expiry. A send that may be pending ([live]) always stays.
     */
    suspend fun trimLdkSendHashes(paid: Set<String>, failed: Set<String>, listed: Set<String>, live: Set<String>) {
        store.updateData {
            val (hashes, expiries) =
                withoutSettled(it.ldkSendHashes, it.ldkSendExpiries, SendListing(paid, failed, listed, live))
            it.copy(ldkSendHashes = hashes, ldkSendExpiries = expiries)
        }
    }

    /** Drops Spark's sends as [trimLdkSendHashes] drops the node's. */
    suspend fun trimSparkSendHashes(paid: Set<String>, failed: Set<String>, listed: Set<String>, live: Set<String>) {
        store.updateData {
            val (hashes, expiries) =
                withoutSettled(it.sparkSendHashes, it.sparkSendExpiries, SendListing(paid, failed, listed, live))
            it.copy(sparkSendHashes = hashes, sparkSendExpiries = expiries)
        }
    }

    private class SendListing(paid: Set<String>, failed: Set<String>, listed: Set<String>, live: Set<String>) {
        val paid = paid.lowercased()
        val failed = failed.lowercased()
        val listed = listed.lowercased()
        val live = live.lowercased()

        private fun Set<String>.lowercased() = mapTo(mutableSetOf()) { it.lowercase() }
    }

    private fun withoutSettled(
        hashes: List<String>,
        expiries: Map<String, Long>,
        listing: SendListing,
    ): Pair<List<String>, Map<String, Long>> {
        val now = clock.now().epochSeconds
        fun isExpired(hash: String, settleSecs: Long = 0) = expiries[hash]?.let { it + settleSecs <= now } == true
        fun isSettled(hash: String) = hash !in listing.live && (
            hash in listing.failed ||
                hash in listing.paid && isExpired(hash) ||
                hash !in listing.listed && isExpired(hash, UNLISTED_SEND_SETTLE_SECS)
            )
        fun isAged(hash: String) =
            hash !in listing.live && hash !in expiries && (hash in listing.paid || hash !in listing.listed)
        val unsettled = hashes.filterNot(::isSettled)
        var excess = unsettled.size - MAX_SPARK_SEND_HASHES
        val kept = unsettled.filterNot { hash -> (excess > 0 && isAged(hash)).also { if (it) excess-- } }
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
    /**
     * When a Spark leaf optimization last ran or was cut short, in epoch milliseconds; cleared once it completed or
     * an exit quote waited out the counter-swap grace. Kept across processes, since a swap the app's close cut short
     * leaves value in flight.
     */
    val sparkOptimizationActivityAt: Long? = null,
    /**
     * The funding key index the next Spark emergency exit takes, per `{network}/wallet{n}`, so each exit pays its fees
     * from an address of its own.
     */
    val sparkExitNextFundingIndexes: Map<String, Int> = mapOf(),
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
