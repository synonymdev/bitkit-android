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
import to.bitkit.repositories.SentSparkWithdrawal
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

    /** Drops the refusals whose invoice expired by [refusedAt], which the node can no longer pay. */
    suspend fun addSparkRefusedSend(paymentHash: String, refusedAt: Long, expiresAt: Long) {
        val hash = paymentHash.lowercase()
        store.updateData {
            val unexpired = it.sparkRefusedSends.filterValues { send -> send.expiresAt > refusedAt }
            it.copy(sparkRefusedSends = unexpired + (hash to SparkRefusedSend(refusedAt, expiresAt)))
        }
    }

    /** Drops the refusal of [paymentHash] made at [refusedAt] only, as a later one waits for its own sync. */
    suspend fun removeSparkRefusedSend(paymentHash: String, refusedAt: Long) {
        val hash = paymentHash.lowercase()
        store.updateData {
            val isSame = it.sparkRefusedSends[hash]?.refusedAt == refusedAt
            if (isSame) it.copy(sparkRefusedSends = it.sparkRefusedSends - hash) else it
        }
    }

    suspend fun removeSparkSendHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        if (hash !in store.data.first().sparkSendHashes) return
        store.updateData {
            it.copy(sparkSendHashes = it.sparkSendHashes - hash, sparkSendExpiries = it.sparkSendExpiries - hash)
        }
    }

    /**
     * Keeps Spark's send of [paymentHash] as one whose outcome Spark didn't report, started at [startedAt] epoch
     * seconds as the attempt Spark knows by [attemptId].
     */
    suspend fun addSparkUncertainSend(paymentHash: String, startedAt: Long, attemptId: String) {
        val hash = paymentHash.lowercase()
        store.updateData {
            it.copy(
                sparkUncertainSends = it.sparkUncertainSends + (hash to startedAt),
                sparkSendAttemptIds = it.sparkSendAttemptIds + (hash to attemptId),
            )
        }
    }

    suspend fun removeSparkUncertainSend(paymentHash: String) {
        val hash = paymentHash.lowercase()
        val data = store.data.first()
        if (hash !in data.sparkUncertainSends && hash !in data.sparkSendAttemptIds) return
        store.updateData {
            it.copy(
                sparkUncertainSends = it.sparkUncertainSends - hash,
                sparkSendAttemptIds = it.sparkSendAttemptIds - hash,
            )
        }
    }

    /**
     * Keeps [attempt] as the latest attempt at paying the invoice with [paymentHash], which expires at [expiresAt],
     * and drops the attempts of invoices expired before [now], both in epoch seconds.
     */
    suspend fun setSparkSendAttempt(paymentHash: String, attempt: Int, expiresAt: Long, now: Long) {
        val hash = paymentHash.lowercase()
        store.updateData {
            val live = it.sparkSendAttempts.filterValues { kept -> kept.expiresAt >= now }
            it.copy(sparkSendAttempts = live + (hash to SparkSendAttempt(attempt, expiresAt)))
        }
    }

    /** Puts back [previous], the attempt kept before a send whose key never reached Spark, or drops the invoice's. */
    suspend fun restoreSparkSendAttempt(paymentHash: String, previous: SparkSendAttempt?) {
        val hash = paymentHash.lowercase()
        store.updateData {
            val attempts = it.sparkSendAttempts - hash
            it.copy(sparkSendAttempts = if (previous == null) attempts else attempts + (hash to previous))
        }
    }

    suspend fun addSparkUnsavedOwnFailure(paymentId: String) {
        if (paymentId in store.data.first().sparkUnsavedOwnFailures) return
        store.updateData { it.copy(sparkUnsavedOwnFailures = it.sparkUnsavedOwnFailures + paymentId) }
    }

    suspend fun removeSparkUnsavedOwnFailure(paymentId: String) {
        if (paymentId !in store.data.first().sparkUnsavedOwnFailures) return
        store.updateData { it.copy(sparkUnsavedOwnFailures = it.sparkUnsavedOwnFailures - paymentId) }
    }

    suspend fun addMoveToSparkHash(paymentHash: String) {
        val hash = paymentHash.lowercase()
        if (hash in store.data.first().moveToSparkHashes) return
        store.updateData { it.copy(moveToSparkHashes = it.moveToSparkHashes + hash) }
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
     * After a restore, keeps [paymentHashes], Spark's pending and paid sends from its synced list, once, with their
     * invoices' [expiries] in epoch seconds where known, so a still-valid one stays held and outlasts the cap.
     */
    suspend fun seedSparkSendHashes(paymentHashes: List<String>, expiries: Map<String, Long> = emptyMap()) {
        if (store.data.first().sparkHistoryUnseededSince == null) return
        store.updateData { data ->
            if (data.sparkHistoryUnseededSince == null) return@updateData data
            val seeded = paymentHashes.map { it.lowercase() }.distinct() - data.sparkSendHashes.toSet()
            // A kept hash without an expiry gets its invoice's, so the cap can't drop it while valid; a set one stays.
            val listedExpiries = expiries.mapKeys { it.key.lowercase() }
            data.copy(
                sparkSendHashes = seeded + data.sparkSendHashes,
                sparkSendExpiries = listedExpiries + data.sparkSendExpiries,
                sparkHistoryUnseededSince = null,
            )
        }
    }

    /** After a restore, keeps [paymentHashes], the node's pending and paid sends from its own list, once. */
    suspend fun seedRestoredLdkSendHashes(paymentHashes: List<String>) {
        if (store.data.first().ldkHistoryUnseededSince == null) return
        store.updateData { data ->
            if (data.ldkHistoryUnseededSince == null) return@updateData data
            val seeded = paymentHashes.map { it.lowercase() }.distinct() - data.ldkSendHashes.toSet()
            data.copy(ldkSendHashes = seeded + data.ldkSendHashes, ldkHistoryUnseededSince = null)
        }
    }

    /**
     * Drops the node's sends a listing showed as [failed] only, or as [paid] with an expired invoice, ones it didn't
     * show ([listed] is every hash it shows) whose invoice expired past [UNLISTED_SEND_SETTLE_SECS], and past the cap
     * the oldest paid or unlisted ones with no known expiry. A send that may be pending ([live], or [isLive] inside
     * the write, as for one recorded since the listing) always stays.
     */
    suspend fun trimLdkSendHashes(
        paid: Set<String>,
        failed: Set<String>,
        listed: Set<String>,
        live: Set<String>,
        isLive: (String) -> Boolean = { false },
    ) {
        store.updateData {
            val listing = SendListing(paid, failed, listed, live + it.ldkSendHashes.filter(isLive))
            val (hashes, expiries) = withoutSettled(it.ldkSendHashes, it.ldkSendExpiries, listing)
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

        // Compared against now minus the window, as an invoice that never expires is kept at Long.MAX_VALUE.
        fun isExpired(hash: String, settleSecs: Long = 0) = expiries[hash]?.let { it <= now - settleSecs } == true
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

    /** Drops the node's send of [paymentHash] unless [isKept] holds inside the write, as for a send recorded since. */
    suspend fun removeLdkSendHash(paymentHash: String, isKept: () -> Boolean = { false }) {
        val hash = paymentHash.lowercase()
        if (hash !in store.data.first().ldkSendHashes) return
        store.updateData {
            if (isKept()) return@updateData it
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
    /**
     * The `{network}/wallet{n}` keys whose funding index a restore brought back, so the exit looks once for funding
     * addresses that still hold coins and nothing watches.
     */
    val sparkExitFundingRecoveryPending: Set<String> = emptySet(),
    /** A Spark withdrawal to a Paykit contact that started, kept until Spark's payment list settles it. */
    val pendingSparkWithdrawal: PendingSparkWithdrawal? = null,
    /** The latest Spark withdrawals to Paykit contacts that settled as sent, so a restart never reads one as unsent. */
    val sentSparkWithdrawals: List<SentSparkWithdrawal> = listOf(),
    /** Pubky Swaps routes committed or restored whose claim has not confirmed yet, so each start resumes them. */
    val unfinishedPubkySwaps: List<String> = listOf(),
    /** The hold payment hash of each unfinished Pubky Swaps route by swap id, so its failure stops the swap. */
    val pubkySwapHoldHashes: Map<String, String> = mapOf(),
    /** Hashes of the latest Lightning sends Spark started, so the node holds off while Spark can't list them. */
    val sparkSendHashes: List<String> = listOf(),
    /** Invoice expiry in epoch seconds of the [sparkSendHashes] that know it. */
    val sparkSendExpiries: Map<String, Long> = mapOf(),
    /**
     * Spark's sends whose outcome it didn't report, by hash, with when they started in epoch seconds, kept across a
     * restart until Spark settles them.
     */
    val sparkUncertainSends: Map<String, Long> = mapOf(),
    /**
     * The idempotency key each of the [sparkUncertainSends] passed, which Spark keeps as that attempt's payment id, so
     * only its own outcome settles it. A hold without one waits for a paid attempt or its invoice's expiry.
     */
    val sparkSendAttemptIds: Map<String, String> = mapOf(),
    /** The latest attempt at each invoice Spark sent, by hash, whose number picks the attempt's idempotency key. */
    val sparkSendAttempts: Map<String, SparkSendAttempt> = mapOf(),
    /** Spark sends refused after they may have reached the operators: the node stays off each until Spark syncs. */
    val sparkRefusedSends: Map<String, SparkRefusedSend> = mapOf(),
    /**
     * Payment ids of Spark send attempts whose own failure a failed Activity write still has to save over an earlier
     * attempt's row, kept across a restart until it saves.
     */
    val sparkUnsavedOwnFailures: Set<String> = setOf(),
    /**
     * Payment hashes of the moves to Spark, kept across restarts so neither leg of a move is announced as a payment and
     * Activity shows the move as one.
     */
    val moveToSparkHashes: List<String> = listOf(),
    /** Hashes of the Lightning sends the node started, so Spark holds off while the node can't list them. */
    val ldkSendHashes: List<String> = listOf(),
    /** Invoice expiry in epoch seconds of the [ldkSendHashes] that know it. */
    val ldkSendExpiries: Map<String, Long> = mapOf(),
    /**
     * Set once the node's own list has filled [ldkSendHashes], as node sends made before hashes were kept carry none
     * there. Until then an unlistable node holds every Spark send.
     */
    val ldkSendHashesSeeded: Boolean = false,
    /**
     * When a metadata restore brought back [sparkSendHashes], in epoch seconds, until Spark's synced list fills them,
     * as the backup can predate a send Spark made. Null on an install that was never restored.
     */
    val sparkHistoryUnseededSince: Long? = null,
    /**
     * When a metadata restore brought back [ldkSendHashes], in epoch seconds, until the node's own list fills them:
     * while the node can't list its payments, Spark holds off invoices created before it. Null when never restored.
     */
    val ldkHistoryUnseededSince: Long? = null,
    /**
     * When the wallet was restored, in epoch seconds. Spark's list can lack a send it made before then even after a
     * sync, so both rails refuse a still-valid invoice created before it that Spark hasn't listed as pending or paid,
     * unless the Spark operators show it unsent.
     */
    val sparkRestoredAt: Long? = null,
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

/** An attempt at paying an invoice through Spark, the [attempt]th, of an invoice that expires at [expiresAt]. */
@Serializable
data class SparkSendAttempt(val attempt: Int, val expiresAt: Long)

/** When a Spark send was refused and when its invoice expires, in epoch seconds. */
@Serializable
data class SparkRefusedSend(val refusedAt: Long, val expiresAt: Long)

@Serializable
data class CachedUsdtBalance(
    val address: String,
    val network: String,
    val balance: ULong,
) {
    fun balanceFor(address: String, network: String): ULong? =
        balance.takeIf { this.address == address && this.network == network }
}
