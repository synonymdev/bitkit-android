package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.LightningActivity
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
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.data.entities.TagMetadataEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.matchesPaymentId
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.rawId
import to.bitkit.models.ActivityBackupV1
import to.bitkit.services.CoreService
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private const val SYNC_TIMEOUT_MS = 40_000L

@Suppress("LongParameterList")
@Singleton
class ActivityRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
    private val db: AppDb,
    private val addressChecker: AddressChecker,
    private val transferRepo: TransferRepo,
    private val clock: Clock,
) {
    val isSyncingLdkNodePayments = MutableStateFlow(false)

    private val _activitiesChanged = MutableStateFlow(0L)
    val activitiesChanged: StateFlow<Long> = _activitiesChanged

    private fun notifyActivitiesChanged() = _activitiesChanged.update { clock.now().toEpochMilliseconds() }

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
                    syncTagsMetadata()
                    boostPendingActivities()
                    transferRepo.syncTransferStates()
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
     * Syncs `ldk-node` [PaymentDetails] list to `bitkit-core` [Activity] items.
     */
    private suspend fun syncLdkNodePayments(payments: List<PaymentDetails>): Result<Unit> = runCatching {
        coreService.activity.syncLdkNodePaymentsToActivities(payments)
    }.onFailure { e ->
        Logger.error("Error syncing LDK payments:", e, context = TAG)
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
        runCatching {
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

                val tags = coreService.activity.tags(activityIdToDelete)
                addTagsToActivity(activityId = id, tags = tags)

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
        cacheStore.data.first().activitiesPendingDelete.map { activityId ->
            async {
                deleteActivity(id = activityId).onSuccess {
                    cacheStore.removeActivityFromPendingDelete(activityId)
                }
            }
        }.awaitAll()
    }

    private suspend fun updateActivitiesMetadata() = withContext(bgDispatcher) {
        cacheStore.data.first().transactionsMetadata.map { metadata ->
            async {
                findActivityByPaymentId(
                    paymentHashOrTxId = metadata.txId,
                    type = ActivityFilter.ALL,
                    txType = PaymentType.SENT
                ).onSuccess { activityToUpdate ->
                    Logger.debug(
                        "updateActivitiesMetaData - Activity found: ${activityToUpdate.rawId()}",
                        context = TAG
                    )

                    if (activityToUpdate is Activity.Onchain) {
                        val onChainActivity = activityToUpdate.v1.copy(
                            feeRate = metadata.feeRate.toULong(),
                            address = metadata.address.ifEmpty { activityToUpdate.v1.address },
                            isTransfer = metadata.isTransfer,
                            channelId = metadata.channelId,
                            transferTxId = metadata.transferTxId(),
                            updatedAt = nowTimestamp().toEpochMilli().toULong(),
                        )
                        val updatedActivity = Activity.Onchain(v1 = onChainActivity)

                        updateActivity(id = updatedActivity.v1.id, activity = updatedActivity).onSuccess {
                            cacheStore.removeTransactionMetadata(metadata)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun syncTagsMetadata() = withContext(context = bgDispatcher) {
        runCatching {
            if (db.tagMetadataDao().getAll().isEmpty()) return@withContext
            val lastActivities = getActivities(limit = 10u).getOrNull() ?: return@withContext
            Logger.debug("syncTagsMetadata called")

            lastActivities.map { activity ->
                async {
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

                        is Activity.Onchain -> {
                            when (activity.v1.txType) {
                                PaymentType.RECEIVED -> {
                                    // TODO Temporary solution while whe ldk-node doesn't return the address directly
                                    Logger.verbose("Fetching data for txId: ${activity.v1.txId}", context = TAG)
                                    runCatching {
                                        addressChecker.getTransaction(activity.v1.txId)
                                    }.onSuccess { txDetails ->
                                        Logger.verbose("Tx detail fetched with success: $txDetails", context = TAG)
                                        txDetails.vout.map { vOut ->
                                            async {
                                                vOut.scriptpubkey_address?.let {
                                                    Logger.verbose("Extracted address: $it", context = TAG)
                                                    db.tagMetadataDao().searchByAddress(it)
                                                }?.let { tagMetadata ->
                                                    Logger.debug("Tags metadata found! $tagMetadata", context = TAG)
                                                    addTagsToTransaction(
                                                        paymentHashOrTxId = txDetails.txid,
                                                        type = ActivityFilter.ONCHAIN,
                                                        txType = PaymentType.RECEIVED,
                                                        tags = tagMetadata.tags
                                                    ).onSuccess {
                                                        Logger.debug(
                                                            "Tags synced with success! $tagMetadata",
                                                            context = TAG
                                                        )
                                                        db.tagMetadataDao().deleteByTxId(activity.v1.txId)
                                                    }
                                                }
                                            }
                                        }.awaitAll()
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
            }.awaitAll()
        }
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

                    val updatedActivity = Activity.Onchain(
                        v1 = newOnChainActivity.v1.copy(
                            isBoosted = true,
                            boostTxIds = pendingBoostActivity.boostTxIds,
                            updatedAt = pendingBoostActivity.updatedAt,
                        )
                    )

                    if (pendingBoostActivity.activityToDelete != null) {
                        // RBF: Replace old activity with new one, applying parent chain boostTxIds
                        replaceActivity(
                            id = updatedActivity.v1.id,
                            activity = updatedActivity,
                            activityIdToDelete = pendingBoostActivity.activityToDelete,
                        ).onSuccess {
                            cacheStore.removeActivityFromPendingBoost(pendingBoostActivity)
                        }
                    } else {
                        // CPFP: Update existing activity (though CPFP is handled immediately now)
                        updateActivity(
                            id = updatedActivity.v1.id,
                            activity = updatedActivity,
                        ).onSuccess {
                            cacheStore.removeActivityFromPendingBoost(pendingBoostActivity)
                        }
                    }
                }
            }
        }.awaitAll()
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

    /**
     * Adds tags to an activity with business logic validation
     */
    suspend fun addTagsToActivity(activityId: String, tags: List<String>): Result<Unit> = withContext(bgDispatcher) {
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
            require(tags.isNotEmpty())

            val entity = TagMetadataEntity(
                id = id,
                paymentHash = paymentHash,
                txId = txId,
                address = address,
                isReceive = isReceive,
                tags = tags,
                createdAt = nowTimestamp().toEpochMilli()
            )
            db.tagMetadataDao().insert(tagMetadata = entity)
            Logger.debug("Tag metadata saved: $entity", context = TAG)
        }.onFailure { e ->
            Logger.error("getAllAvailableTags error", e, context = TAG)
        }
    }

    suspend fun restoreFromBackup(backup: ActivityBackupV1): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            coreService.activity.upsertList(backup.activities)
            coreService.activity.upsertClosedChannelList(backup.closedChannels)
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
