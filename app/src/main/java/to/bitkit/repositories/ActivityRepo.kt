package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.Activity.Onchain
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.lightningdevkit.ldknode.PaymentDetails
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.InProgressTransfer
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.data.dto.TransferType
import to.bitkit.data.entities.TagMetadataEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.matchesPaymentId
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.rawId
import to.bitkit.services.CoreService
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import java.security.InvalidParameterException
import javax.inject.Inject
import javax.inject.Singleton

private const val SYNC_TIMEOUT_MS = 40_000L

@Singleton
class ActivityRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
    private val db: AppDb,
    private val addressChecker: AddressChecker,
) {
    val isSyncingLdkNodePayments = MutableStateFlow(false)

    val inProgressTransfers = cacheStore.data.map { it.inProgressTransfers }

    suspend fun syncActivities(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("syncActivities called", context = TAG)

        return@withContext runCatching {
            withTimeout(SYNC_TIMEOUT_MS) {
                Logger.debug("isSyncingLdkNodePayments = ${isSyncingLdkNodePayments.value}", context = TAG)
                isSyncingLdkNodePayments.first { !it }
            }

            isSyncingLdkNodePayments.value = true

            deletePendingActivities()
            return@withContext lightningRepo.getPayments()
                .onSuccess { payments ->
                    Logger.debug("Got payments with success, syncing activities", context = TAG)
                    syncLdkNodePayments(payments = payments).onFailure { e ->
                        return@withContext Result.failure(e)
                    }
                    updateActivitiesMetadata()
                    syncTagsMetaData()
                    boostPendingActivities()
                    updateInProgressTransfers()
                    isSyncingLdkNodePayments.value = false
                    return@withContext Result.success(Unit)
                }.onFailure { e ->
                    Logger.error("Failed to sync ldk-node payments", e, context = TAG)
                    isSyncingLdkNodePayments.value = false
                    return@withContext Result.failure(e)
                }.map { Unit }
        }.onFailure { e ->
            when (e) {
                is TimeoutCancellationException -> {
                    isSyncingLdkNodePayments.value = false
                    Logger.warn("Timeout waiting for sync to complete, forcing reset", context = TAG)
                }

                else -> {
                    isSyncingLdkNodePayments.value = false
                    Logger.error("syncActivities error", e, context = TAG)
                }
            }
        }
    }

    /**
     * Business logic: Syncs LDK node payments with proper error handling and counting
     * @return a list with added payments
     */
    private suspend fun syncLdkNodePayments(
        payments: List<PaymentDetails>,
    ): Result<Unit> {
        return runCatching {
            coreService.activity.syncLdkNodePayments(payments)
        }.onFailure { e ->
            Logger.error("Error syncing LDK payment:", e, context = TAG)
        }
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

    /**
     * Gets activities with specified filters
     */
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

    /**
     * Gets a specific activity by ID
     */
    suspend fun getActivity(id: String): Result<Activity?> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.getActivity(id)
        }.onFailure { e ->
            Logger.error("getActivity error for ID: $id", e, context = TAG)
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
        }.onFailure { e ->
            Logger.error("updateActivity error for ID: $id", e, context = TAG)
        }
    }

    /**
     * Updates an activity and delete other one. In case of failure in the update or deletion, the data will be cached
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
                    "Activity $id updated with success. new data: $activity. Deleting activity $activityIdToDelete",
                    context = TAG
                )
                deleteActivity(activityIdToDelete).onFailure { e ->
                    Logger.warn(
                        "Failed to delete $activityIdToDelete caching to retry on next sync",
                        e = e,
                        context = TAG
                    )
                    cacheStore.addActivityToPendingDelete(activityId = activityIdToDelete)
                }
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
        cacheStore.data.first().activitiesPendingDelete.forEach { activityId ->
            deleteActivity(id = activityId).onSuccess {
                cacheStore.removeActivityFromPendingDelete(activityId)
            }
        }
    }

    private suspend fun updateActivitiesMetadata() = withContext(bgDispatcher) {
        cacheStore.data.first().transactionsMetadata.forEach { activityMetaData ->
            findActivityByPaymentId(
                paymentHashOrTxId = activityMetaData.txId,
                type = ActivityFilter.ALL,
                txType = PaymentType.SENT
            ).onSuccess { activityToUpdate ->
                Logger.debug("updateActivitiesMetaData = Activity found: ${activityToUpdate.rawId()}", context = TAG)

                when (activityToUpdate) {
                    is Activity.Onchain -> {
                        val onChainActivity = activityToUpdate.v1.copy(
                            feeRate = activityMetaData.feeRate.toULong(),
                            address = activityMetaData.address,
                            isTransfer = activityMetaData.isTransfer,
                            channelId = activityMetaData.channelId,
                            transferTxId = activityMetaData.transferTxId
                        )
                        val updatedActivity = Onchain(
                            v1 = onChainActivity
                        )

                        updateActivity(
                            id = updatedActivity.v1.id,
                            activity = updatedActivity
                        ).onSuccess {
                            if (onChainActivity.isTransfer && onChainActivity.doesExist) {
                                cacheStore.addInProgressTransfer(
                                    InProgressTransfer(
                                        activityId = updatedActivity.v1.id,
                                        type = if (onChainActivity.txType == PaymentType.SENT) {
                                            TransferType.TO_SPENDING
                                        } else {
                                            TransferType.TO_SAVINGS
                                        }
                                    )
                                )
                            }
                            cacheStore.removeTransactionMetadata(activityMetaData)
                        }
                    }

                    is Activity.Lightning -> Unit
                }
            }
        }
    }

    private suspend fun syncTagsMetaData(
    ) = withContext(context = bgDispatcher) {
        runCatching {
            if (db.tagMetadataDao().getAll().isEmpty()) return@withContext
            val lastActivities = getActivities(limit = 10u).getOrNull() ?: return@withContext
            Logger.debug("syncTagsMetaData called")
            lastActivities.forEach { activity ->
                when (activity) {
                    is Activity.Lightning -> {
                        val paymentHash = activity.rawId()
                        db.tagMetadataDao().searchByPaymentHash(paymentHash = paymentHash)?.let { tagMetadata ->
                            Logger.debug("Tags metadata found! $tagMetadata", context = TAG)
                            addTagsToTransaction(
                                paymentHashOrTxId = paymentHash,
                                type = ActivityFilter.LIGHTNING,
                                txType = if (tagMetadata.isReceive) PaymentType.RECEIVED else PaymentType.SENT,
                                tags = tagMetadata.tags
                            ).onSuccess {
                                Logger.debug("Tags synced with success!", context = TAG)
                                db.tagMetadataDao().deleteByPaymentHash(paymentHash = paymentHash)
                            }
                        }
                    }

                    is Onchain -> {
                        when (activity.v1.txType) {
                            PaymentType.RECEIVED -> {
                                // TODO Temporary solution while whe ldk-node doesn't return the address directly
                                Logger.debug("Fetching data for txId: ${activity.v1.txId}", context = TAG)
                                runCatching { addressChecker.getTransaction(activity.v1.txId) }.onSuccess { txDetails ->
                                    Logger.debug("Tx detail fetched with success: $txDetails", context = TAG)
                                    txDetails.vout.forEach { vOut ->
                                        vOut.scriptpubkey_address?.let {
                                            Logger.debug("Extracted address: $it", context = TAG)
                                            db.tagMetadataDao().searchByAddress(it)
                                        }?.let { tagMetadata ->
                                            Logger.debug("Tags metadata found! $tagMetadata", context = TAG)
                                            addTagsToTransaction(
                                                paymentHashOrTxId = txDetails.txid,
                                                type = ActivityFilter.ONCHAIN,
                                                txType = PaymentType.RECEIVED,
                                                tags = tagMetadata.tags
                                            ).onSuccess {
                                                Logger.debug("Tags synced with success! $tagMetadata", context = TAG)
                                                db.tagMetadataDao().deleteByTxId(activity.v1.txId)
                                            }
                                        }
                                    }
                                }.onFailure {
                                    Logger.warn("Failed getting transaction detail", context = TAG)
                                }
                            }

                            PaymentType.SENT -> {
                                db.tagMetadataDao().searchByTxId(activity.v1.txId)?.let { tagMetadata ->
                                    addTagsToTransaction(
                                        paymentHashOrTxId = activity.v1.txId,
                                        type = ActivityFilter.ONCHAIN,
                                        txType = PaymentType.SENT,
                                        tags = tagMetadata.tags
                                    ).onSuccess {
                                        Logger.debug("Tags synced with success! $tagMetadata", context = TAG)
                                        db.tagMetadataDao().deleteByTxId(activity.v1.txId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateInProgressTransfers() {
        cacheStore.data.first().inProgressTransfers.forEach { transfer ->
            getActivity(transfer.activityId).onSuccess { activity ->
                (activity as? Onchain)?.let { onChain ->
                    if (onChain.v1.confirmed) {
                        cacheStore.removeInProgressTransfer(transfer)
                    }
                }
            }
        }
    }

    private suspend fun boostPendingActivities() = withContext(bgDispatcher) {
        cacheStore.data.first().pendingBoostActivities.forEach { pendingBoostActivity ->
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

                val updatedActivity = Activity.Onchain(
                    v1 = newOnChainActivity.v1.copy(
                        isBoosted = true,
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
    }

    /**
     * Deletes an activity
     */
    suspend fun deleteActivity(id: String): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val deleted = coreService.activity.delete(id)
            if (deleted) {
                cacheStore.addActivityToDeletedList(id)
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
        }.onFailure { e ->
            Logger.error("insertActivity error", e, context = TAG)
        }
    }

    suspend fun addActivityToPendingBoost(pendingBoostActivity: PendingBoostActivity) = withContext(bgDispatcher) {
        cacheStore.addActivityToPendingBoost(pendingBoostActivity)
    }

    /**
     * Adds tags to an activity with business logic validation
     */
    suspend fun addTagsToActivity(activityId: String, tags: List<String>): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            // Business logic: validate activity exists before adding tags
            val activity = coreService.activity.getActivity(activityId)
                ?: throw IllegalArgumentException("Activity with ID $activityId not found")

            // Business logic: filter out empty or duplicate tags
            val existingTags = coreService.activity.tags(activityId)
            val newTags = tags.filter { it.isNotBlank() && it !in existingTags }

            if (newTags.isNotEmpty()) {
                coreService.activity.appendTags(activityId, newTags).getOrThrow()
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
        ).onSuccess { activity ->
            addTagsToActivity(activity.rawId(), tags = tags)
        }.onFailure { e ->
            return@withContext Result.failure(e)
        }.map { Unit }
    }

    /**
     * Removes tags from an activity
     */
    suspend fun removeTagsFromActivity(activityId: String, tags: List<String>): Result<Unit> =
        withContext(bgDispatcher) {
            return@withContext runCatching {
                // Business logic: validate activity exists before removing tags
                val activity = coreService.activity.getActivity(activityId)
                    ?: throw IllegalArgumentException("Activity with ID $activityId not found")

                coreService.activity.dropTags(activityId, tags)
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

    /**
     * Gets all possible tags across all activities
     */
    suspend fun getAllAvailableTags(): Result<List<String>> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.allPossibleTags()
        }.onFailure { e ->
            Logger.error("getAllAvailableTags error", e, context = TAG)
        }
    }

    suspend fun saveTagsMetadata(
        id: String,
        paymentHash: String? = null,
        txId: String? = null,
        address: String,
        isReceive: Boolean,
        tags: List<String>,
    ): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {

            if (tags.isEmpty()) throw InvalidParameterException("tags must not be empty")

            val entity = TagMetadataEntity(
                id = id,
                paymentHash = paymentHash,
                txId = txId,
                address = address,
                isReceive = isReceive,
                tags = tags,
                createdAt = nowTimestamp().toEpochMilli()
            )
            db.tagMetadataDao().saveTagMetadata(
                tagMetadata = entity
            )
            Logger.debug("Tag metadata saved: $entity", context = TAG)
        }.onFailure { e ->
            Logger.error("getAllAvailableTags error", e, context = TAG)
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
