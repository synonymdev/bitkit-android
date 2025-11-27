package to.bitkit.repositories

import androidx.annotation.VisibleForTesting
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.matchesPaymentId
import to.bitkit.ext.nowMillis
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.rawId
import to.bitkit.models.ActivityBackupV1
import to.bitkit.services.CoreService
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private const val SYNC_TIMEOUT_MS = 40_000L

@Singleton
@Suppress("LargeClass", "LongParameterList")
class ActivityRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val addressChecker: AddressChecker,
    private val cacheStore: CacheStore,
    private val transferRepo: TransferRepo,
    private val clock: Clock,
) {
    val isSyncingLdkNodePayments = MutableStateFlow(false)

    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state

    private val _activitiesChanged = MutableStateFlow(0L)
    val activitiesChanged: StateFlow<Long> = _activitiesChanged

    private fun notifyActivitiesChanged() = _activitiesChanged.update { nowMillis(clock) }

    suspend fun resetState() = withContext(bgDispatcher) {
        _state.update { ActivityState() }
        isSyncingLdkNodePayments.update { false }
        notifyActivitiesChanged()
        Logger.debug("Activity state reset", context = TAG)
    }

    suspend fun syncActivities(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("syncActivities called", context = TAG)

        val result = runCatching {
            withTimeout(SYNC_TIMEOUT_MS) {
                Logger.debug("isSyncingLdkNodePayments = ${isSyncingLdkNodePayments.value}", context = TAG)
                isSyncingLdkNodePayments.first { !it }
            }

            isSyncingLdkNodePayments.update { true }

            deletePendingActivities()

            lightningRepo.getPayments().mapCatching { payments ->
                Logger.debug("Got payments with success, syncing activities", context = TAG)
                syncLdkNodePayments(payments).getOrThrow()
                boostPendingActivities()
                transferRepo.syncTransferStates().getOrThrow()
            }.onSuccess {
                getAllAvailableTags().getOrNull()
            }.getOrThrow()
        }.onFailure { e ->
            if (e is TimeoutCancellationException) {
                Logger.warn("syncActivities timeout, forcing reset", context = TAG)
            } else {
                Logger.error("Failed to sync activities", e, context = TAG)
            }
        }

        isSyncingLdkNodePayments.update { false }
        notifyActivitiesChanged()

        return@withContext result
    }

    /**
     * Syncs `ldk-node` [PaymentDetails] list to `bitkit-core` [Activity] items.
     */
    private suspend fun syncLdkNodePayments(payments: List<PaymentDetails>): Result<Unit> = runCatching {
        val channelIdsByTxId = findChannelsForPayments(payments)
        coreService.activity.syncLdkNodePaymentsToActivities(payments, channelIdsByTxId = channelIdsByTxId)
    }.onFailure { e ->
        Logger.error("Error syncing LDK payments:", e, context = TAG)
    }

    private suspend fun findChannelsForPayments(
        payments: List<PaymentDetails>,
    ): Map<String, String> = withContext(bgDispatcher) {
        val channelIdsByTxId = mutableMapOf<String, String>()

        payments.filter { it.kind is PaymentKind.Onchain }.forEach { payment ->
            val kind = payment.kind as? PaymentKind.Onchain ?: return@forEach
            val channelId = findChannelForTransaction(kind.txid, payment.direction)
            if (channelId != null) {
                channelIdsByTxId[kind.txid] = channelId
            }
        }

        return@withContext channelIdsByTxId
    }

    private suspend fun findChannelForTransaction(txid: String, direction: PaymentDirection): String? {
        return if (direction == PaymentDirection.OUTBOUND) {
            findOpenChannelForTransaction(txid)
        } else {
            findClosedChannelForTransaction(txid)
        }
    }

    private suspend fun findOpenChannelForTransaction(txid: String): String? {
        return try {
            val channels = lightningRepo.lightningState.value.channels
            if (channels.isEmpty()) return null

            channels.firstOrNull { channel ->
                channel.fundingTxo?.txid == txid
            }?.channelId
                ?: run {
                    val orders = blocktankRepo.blocktankState.value.orders
                    val matchingOrder = orders.firstOrNull { order ->
                        order.payment?.onchain?.transactions?.any { it.txId == txid } == true
                    } ?: return null

                    val orderChannel = matchingOrder.channel ?: return null
                    channels.firstOrNull { channel ->
                        channel.fundingTxo?.txid == orderChannel.fundingTx.id
                    }?.channelId
                }
        } catch (e: Exception) {
            Logger.warn("Failed to find open channel for transaction: $txid", e, context = TAG)
            null
        }
    }

    private suspend fun findClosedChannelForTransaction(txid: String): String? {
        return try {
            val closedChannelsResult = getClosedChannels(SortDirection.DESC)
            val closedChannels = closedChannelsResult.getOrNull() ?: return null
            if (closedChannels.isEmpty()) return null

            val txDetails = addressChecker.getTransaction(txid)

            txDetails.vin.firstNotNullOfOrNull { input ->
                val inputTxid = input.txid ?: return@firstNotNullOfOrNull null
                val inputVout = input.vout ?: return@firstNotNullOfOrNull null

                closedChannels.firstOrNull { channel ->
                    channel.fundingTxoTxid == inputTxid && channel.fundingTxoIndex == inputVout.toUInt()
                }?.channelId
            }
        } catch (e: Exception) {
            Logger.warn(
                "Failed to check if transaction $txid spends closed channel funding UTXO",
                e,
                context = TAG
            )
            null
        }
    }

    private suspend fun getOnchainActivityByTxId(txid: String): OnchainActivity? {
        return coreService.activity.getOnchainActivityByTxId(txid)
    }

    /**
     * Determines whether to show the payment received UI for an onchain transaction.
     * Returns false for:
     * - Zero value transactions
     * - Channel closure transactions (transfers to savings)
     * - RBF replacement transactions with the same value as the original
     */
    suspend fun shouldShowPaymentReceived(txid: String, value: ULong): Boolean = withContext(bgDispatcher) {
        if (value == 0uL) return@withContext false

        if (findClosedChannelForTransaction(txid) != null) {
            Logger.debug("Skipping payment received UI for channel closure tx: $txid", context = TAG)
            return@withContext false
        }

        val onchainActivity = getOnchainActivityByTxId(txid)
        if (onchainActivity != null && onchainActivity.boostTxIds.isNotEmpty()) {
            for (replacedTxid in onchainActivity.boostTxIds) {
                val replacedActivity = getOnchainActivityByTxId(replacedTxid)
                if (replacedActivity != null && replacedActivity.value == value) {
                    Logger.info(
                        "Skipping payment received UI for RBF replacement $txid with same value as $replacedTxid",
                        context = TAG
                    )
                    return@withContext false
                }
            }
        }

        return@withContext true
    }

    /**
     * Checks if a transaction is inbound (received) by looking up the payment direction.
     */
    suspend fun isReceivedTransaction(txid: String): Boolean = withContext(bgDispatcher) {
        lightningRepo.getPayments().getOrNull()?.let { payments ->
            payments.firstOrNull { payment ->
                (payment.kind as? PaymentKind.Onchain)?.txid == txid
            }
        }?.direction == PaymentDirection.INBOUND
    }

    /**
     * Checks if a transaction was replaced (RBF) by checking if the activity exists but doesExist=false.
     */
    suspend fun wasTransactionReplaced(txid: String): Boolean = withContext(bgDispatcher) {
        val onchainActivity = getOnchainActivityByTxId(txid) ?: return@withContext false
        return@withContext !onchainActivity.doesExist
    }

    /**
     * Gets a specific activity by payment hash or txID with retry logic
     */
    suspend fun findActivityByPaymentId(
        paymentHashOrTxId: String,
        type: ActivityFilter,
        txType: PaymentType?,
        retry: Boolean = true,
    ): Result<Activity> = withContext(bgDispatcher) {
        if (paymentHashOrTxId.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("paymentHashOrTxId is empty")
            )
        }

        return@withContext try {
            suspend fun findActivity(): Activity? = getActivities(
                filter = type,
                txType = txType,
                limit = 10u
            ).getOrNull()?.firstOrNull { it.matchesPaymentId(paymentHashOrTxId) }

            var activity = findActivity()
            if (activity == null && retry) {
                Logger.warn(
                    "activity with paymentHashOrTxId:$paymentHashOrTxId not found, trying again after sync",
                    context = TAG
                )

                lightningRepo.sync().onSuccess {
                    Logger.debug("Syncing LN node SUCCESS", context = TAG)
                }

                syncActivities().onSuccess {
                    Logger.debug(
                        "Sync success, searching again the activity with paymentHashOrTxId:$paymentHashOrTxId",
                        context = TAG
                    )
                    activity = findActivity()
                }
            }

            if (activity != null) {
                Result.success(activity)
            } else {
                Result.failure(IllegalStateException("Activity not found"))
            }
        } catch (e: Exception) {
            Logger.error(
                "findActivityByPaymentId error. Parameters:" +
                    "\n paymentHashOrTxId:$paymentHashOrTxId type:$type txType:$txType",
                context = TAG
            )
            Result.failure(e)
        }
    }

    suspend fun getActivities(
        filter: ActivityFilter? = null,
        txType: PaymentType? = null,
        tags: List<String>? = null,
        search: String? = null,
        minDate: ULong? = null,
        maxDate: ULong? = null,
        limit: UInt? = null,
        sortDirection: SortDirection? = null,
    ): Result<List<Activity>> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.get(filter, txType, tags, search, minDate, maxDate, limit, sortDirection)
        }.onFailure { e ->
            Logger.error(
                "getActivities error. Parameters:" +
                    "\nfilter:$filter " +
                    "txType:$txType " +
                    "tags:$tags " +
                    "search:$search " +
                    "minDate:$minDate " +
                    "maxDate:$maxDate " +
                    "limit:$limit " +
                    "sortDirection:$sortDirection",
                e = e,
                context = TAG
            )
        }
    }

    suspend fun getActivity(id: String): Result<Activity?> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.getActivity(id)
        }.onFailure { e ->
            Logger.error("getActivity error for ID: $id", e, context = TAG)
        }
    }

    suspend fun getClosedChannels(
        sortDirection: SortDirection = SortDirection.ASC,
    ): Result<List<ClosedChannelDetails>> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.closedChannels(sortDirection)
        }.onFailure { e ->
            Logger.error("Error getting closed channels (sortDirection=$sortDirection)", e, context = TAG)
        }
    }

    /**
     * Updates an activity
     * @param forceUpdate use it if you want update a deleted activity
     */
    suspend fun updateActivity(
        id: String,
        activity: Activity,
        forceUpdate: Boolean = false,
    ): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            if (id in cacheStore.data.first().deletedActivities && !forceUpdate) {
                Logger.debug("Activity $id was deleted", context = TAG)
                return@withContext Result.failure(
                    Exception(
                        "Activity $id was deleted. If you want update it, set forceUpdate as true"
                    )
                )
            }
            coreService.activity.update(id, activity)
            notifyActivitiesChanged()
        }.onFailure { e ->
            Logger.error("updateActivity error for ID: $id", e, context = TAG)
        }
    }

    /**
     * Updates an activity and marks the old one as removed from mempool (for RBF).
     * In case of failure in the update or marking as removed, the data will be cached
     * to try again on the next sync
     */
    suspend fun replaceActivity(
        id: String,
        activityIdToDelete: String,
        activity: Activity,
    ): Result<Unit> = withContext(bgDispatcher) {
        return@withContext updateActivity(
            id = id,
            activity = activity
        ).fold(
            onSuccess = {
                Logger.debug(
                    "Activity $id updated with success. new data: $activity. " +
                        "Marking activity $activityIdToDelete as removed from mempool",
                    context = TAG
                )

                val tags = coreService.activity.tags(activityIdToDelete)
                addTagsToActivity(activityId = id, tags = tags)

                markActivityAsRemovedFromMempool(activityIdToDelete).onFailure { e ->
                    Logger.warn(
                        "Failed to mark $activityIdToDelete as removed from mempool, caching to retry on next sync",
                        e = e,
                        context = TAG
                    )
                    cacheStore.addActivityToPendingDelete(activityId = activityIdToDelete)
                }
                Result.success(Unit)
            },
            onFailure = { e ->
                Logger.error(
                    "Update activity fail. Parameters: id:$id, " +
                        "activityIdToDelete:$activityIdToDelete activity:$activity",
                    e = e,
                    context = TAG
                )
                Result.failure(e)
            }
        )
    }

    private suspend fun deletePendingActivities() = withContext(bgDispatcher) {
        cacheStore.data.first().activitiesPendingDelete.map { activityId ->
            async {
                markActivityAsRemovedFromMempool(activityId).onSuccess {
                    cacheStore.removeActivityFromPendingDelete(activityId)
                }
            }
        }.awaitAll()
    }

    private suspend fun boostPendingActivities() = withContext(bgDispatcher) {
        cacheStore.data.first().pendingBoostActivities.map { pendingBoostActivity ->
            async {
                findActivityByPaymentId(
                    paymentHashOrTxId = pendingBoostActivity.txId,
                    type = ActivityFilter.ONCHAIN,
                    txType = PaymentType.SENT
                ).onSuccess { activityToUpdate ->
                    Logger.debug("boostPendingActivities = Activity found: ${activityToUpdate.rawId()}", context = TAG)

                    val newOnChainActivity = activityToUpdate as? Activity.Onchain ?: return@onSuccess

                    if ((newOnChainActivity.v1.updatedAt ?: 0u) > pendingBoostActivity.updatedAt) {
                        cacheStore.removeActivityFromPendingBoost(pendingBoostActivity)
                        return@onSuccess
                    }

                    val updatedBoostTxIds = if (pendingBoostActivity.parentTxId != null) {
                        newOnChainActivity.v1.boostTxIds + pendingBoostActivity.parentTxId
                    } else {
                        newOnChainActivity.v1.boostTxIds
                    }

                    val updatedActivity = Activity.Onchain(
                        v1 = newOnChainActivity.v1.copy(
                            isBoosted = true,
                            boostTxIds = updatedBoostTxIds,
                            updatedAt = pendingBoostActivity.updatedAt
                        )
                    )

                    if (pendingBoostActivity.activityToDelete != null) {
                        replaceActivity(
                            id = updatedActivity.v1.id,
                            activity = updatedActivity,
                            activityIdToDelete = pendingBoostActivity.activityToDelete
                        ).onSuccess {
                            cacheStore.removeActivityFromPendingBoost(pendingBoostActivity)
                        }
                    } else {
                        updateActivity(
                            id = updatedActivity.v1.id,
                            activity = updatedActivity
                        ).onSuccess {
                            cacheStore.removeActivityFromPendingBoost(pendingBoostActivity)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * Marks an activity as removed from mempool (sets doesExist = false).
     * Used for RBFed transactions that are replaced.
     */
    private suspend fun markActivityAsRemovedFromMempool(activityId: String): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val existingActivity = getActivity(activityId).getOrNull()
                ?: return@withContext Result.failure(Exception("Activity $activityId not found"))

            if (existingActivity is Activity.Onchain) {
                val updatedActivity = Activity.Onchain(
                    v1 = existingActivity.v1.copy(
                        doesExist = false,
                        updatedAt = nowTimestamp().toEpochMilli().toULong()
                    )
                )
                updateActivity(id = activityId, activity = updatedActivity, forceUpdate = true).getOrThrow()
                notifyActivitiesChanged()
            } else {
                return@withContext Result.failure(Exception("Activity $activityId is not an onchain activity"))
            }
        }.onFailure { e ->
            Logger.error("markActivityAsRemovedFromMempool error for ID: $activityId", e, context = TAG)
        }
    }

    /**
     * Deletes an activity
     */
    suspend fun deleteActivity(id: String): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val deleted = coreService.activity.delete(id)
            if (deleted) {
                cacheStore.addActivityToDeletedList(id)
                notifyActivitiesChanged()
            } else {
                return@withContext Result.failure(Exception("Activity not deleted"))
            }
        }.onFailure { e ->
            Logger.error("deleteActivity error for ID: $id", e, context = TAG)
        }
    }

    /**
     * Inserts a new activity
     */
    suspend fun insertActivity(activity: Activity): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            if (activity.rawId() in cacheStore.data.first().deletedActivities) {
                Logger.debug("Activity ${activity.rawId()} was deleted, skipping", context = TAG)
                return@withContext Result.failure(Exception("Activity ${activity.rawId()} was deleted"))
            }
            coreService.activity.insert(activity)
            notifyActivitiesChanged()
        }.onFailure { e ->
            Logger.error("insertActivity error", e, context = TAG)
        }
    }

    /**
     * Upserts an activity (insert or update if exists)
     */
    suspend fun upsertActivity(activity: Activity): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            if (activity.rawId() in cacheStore.data.first().deletedActivities) {
                Logger.debug("Activity ${activity.rawId()} was deleted, skipping", context = TAG)
                return@withContext Result.failure(Exception("Activity ${activity.rawId()} was deleted"))
            }
            coreService.activity.upsert(activity)
            notifyActivitiesChanged()
        }.onFailure { e ->
            Logger.error("upsertActivity error", e, context = TAG)
        }
    }

    /**
     * Inserts a new activity for a fulfilled (channel ready) cjit channel order
     */
    suspend fun insertActivityFromCjit(
        cjitEntry: IcJitEntry?,
        channel: ChannelDetails,
    ): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            requireNotNull(cjitEntry)

            val amount = channel.amountOnClose
            val now = nowTimestamp().epochSecond.toULong()

            return@withContext insertActivity(
                Activity.Lightning(
                    LightningActivity(
                        id = channel.fundingTxo?.txid.orEmpty(),
                        txType = PaymentType.RECEIVED,
                        status = PaymentState.SUCCEEDED,
                        value = amount,
                        fee = 0U,
                        invoice = cjitEntry.invoice.request,
                        message = "",
                        timestamp = now,
                        preimage = null,
                        createdAt = now,
                        updatedAt = null,
                    )
                )
            )
        }.onFailure { e ->
            Logger.error("insertActivity error", e, context = TAG)
        }
    }

    suspend fun addActivityToPendingBoost(pendingBoostActivity: PendingBoostActivity) = withContext(bgDispatcher) {
        cacheStore.addActivityToPendingBoost(pendingBoostActivity)
    }

    @VisibleForTesting
    suspend fun addTagsToActivity(
        activityId: String,
        tags: List<String>,
    ): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            checkNotNull(coreService.activity.getActivity(activityId)) { "Activity with ID $activityId not found" }

            val existingTags = coreService.activity.tags(activityId)
            val newTags = tags.filter { it.isNotBlank() && it !in existingTags }

            if (newTags.isNotEmpty()) {
                coreService.activity.appendTags(activityId, newTags).getOrThrow()
                notifyActivitiesChanged()
                Logger.info("Added ${newTags.size} new tags to activity $activityId", context = TAG)
            } else {
                Logger.info("No new tags to add to activity $activityId", context = TAG)
            }
        }.onFailure { e ->
            Logger.error("addTagsToActivity error for activity $activityId", e, context = TAG)
        }
    }

    /**
     * Adds tags to an activity with business logic validation
     */
    suspend fun addTagsToTransaction(
        paymentHashOrTxId: String,
        type: ActivityFilter,
        txType: PaymentType?,
        tags: List<String>,
    ): Result<Unit> = withContext(bgDispatcher) {
        if (tags.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No tags selected"))
        return@withContext findActivityByPaymentId(
            paymentHashOrTxId = paymentHashOrTxId,
            type = type,
            txType = txType
        ).mapCatching { activity ->
            addTagsToActivity(activity.rawId(), tags = tags).getOrThrow()
        }
    }

    /**
     * Removes tags from an activity
     */
    suspend fun removeTagsFromActivity(activityId: String, tags: List<String>): Result<Unit> =
        withContext(bgDispatcher) {
            return@withContext runCatching {
                checkNotNull(coreService.activity.getActivity(activityId)) { "Activity with ID $activityId not found" }

                coreService.activity.dropTags(activityId, tags)
                notifyActivitiesChanged()
                Logger.info("Removed ${tags.size} tags from activity $activityId", context = TAG)
            }.onFailure { e ->
                Logger.error("removeTagsFromActivity error for activity $activityId", e, context = TAG)
            }
        }

    /**
     * Gets all tags for an activity
     */
    suspend fun getActivityTags(activityId: String): Result<List<String>> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.tags(activityId)
        }.onFailure { e ->
            Logger.error("getActivityTags error for activity $activityId", e, context = TAG)
        }
    }

    suspend fun getAllAvailableTags(): Result<List<String>> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.allPossibleTags()
        }.onSuccess { tags ->
            _state.update { it.copy(tags = tags) }
        }.onFailure { e ->
            Logger.error("getAllAvailableTags error", e, context = TAG)
        }
    }

    /**
     * Get all [ActivityTags] for backup
     */
    suspend fun getAllActivitiesTags(): Result<List<ActivityTags>> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.getAllActivitiesTags()
        }.onFailure { e ->
            Logger.error("getAllActivityTags error", e, context = TAG)
        }
    }

    suspend fun restoreFromBackup(payload: ActivityBackupV1): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.upsertList(payload.activities)
            coreService.activity.upsertTags(payload.activityTags)
            coreService.activity.upsertClosedChannelList(payload.closedChannels)
        }.onSuccess {
            Logger.debug(
                "Restored ${payload.activities.size} activities, ${payload.activityTags.size} activity tags, " +
                    "${payload.closedChannels.size} closed channels",
                context = TAG,
            )
            notifyActivitiesChanged()
        }
    }

    // MARK: - Development/Testing Methods

    /**
     * Removes all activities
     */
    suspend fun removeAllActivities(): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.removeAll()
            Logger.info("Removed all activities", context = TAG)
        }.onFailure { e ->
            Logger.error("removeAllActivities error", e, context = TAG)
        }
    }

    /**
     * Generates random test data (regtest only) with business logic
     */
    suspend fun generateTestData(count: Int = 100): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            // Business logic: validate count is reasonable
            val validatedCount = count.coerceIn(1, 1000)
            if (validatedCount != count) {
                Logger.warn("Adjusted test data count from $count to $validatedCount", context = TAG)
            }

            coreService.activity.generateRandomTestData(validatedCount)
            Logger.info("Generated $validatedCount test activities", context = TAG)
        }.onFailure { e ->
            Logger.error("generateTestData error", e, context = TAG)
        }
    }

    companion object {
        private const val TAG = "ActivityRepo"
    }
}

data class ActivityState(
    val tags: List<String> = emptyList(),
)
