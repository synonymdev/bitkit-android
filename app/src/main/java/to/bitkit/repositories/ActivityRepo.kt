package to.bitkit.repositories

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.SortDirection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.TransactionDetails
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.di.BgDispatcher
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.contact
import to.bitkit.ext.create
import to.bitkit.ext.isReplacedSentTransaction
import to.bitkit.ext.matchesPaymentId
import to.bitkit.ext.nowMillis
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.rawId
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.walletId
import to.bitkit.models.ActivityBackupV1
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.WalletScope
import to.bitkit.services.CoreService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import com.synonym.bitkitcore.TransactionDetails as BitkitCoreTransactionDetails

private const val MS_SYNC_TIMEOUT = 40_000L

@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
@OptIn(ExperimentalTime::class)
@Singleton
class ActivityRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
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
            withTimeout(MS_SYNC_TIMEOUT) {
                Logger.debug("isSyncingLdkNodePayments = ${isSyncingLdkNodePayments.value}", context = TAG)
                isSyncingLdkNodePayments.first { !it }
            }

            isSyncingLdkNodePayments.update { true }

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
    suspend fun syncLdkNodePayments(payments: List<PaymentDetails>): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            val channelIdsByTxId = findChannelsForPayments(payments)
            coreService.activity.syncLdkNodePaymentsToActivities(payments, channelIdsByTxId = channelIdsByTxId)
            notifyActivitiesChanged()
        }.onFailure {
            Logger.error("Error syncing LDK payments:", it, context = TAG)
        }
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

    private suspend fun findChannelForTransaction(
        txid: String,
        direction: PaymentDirection,
    ): String? = if (direction == PaymentDirection.OUTBOUND) {
        findOpenChannelForTransaction(txid)
    } else {
        findClosedChannelForTransaction(txid)
    }

    private fun findOpenChannelForTransaction(txid: String): String? = runCatching {
        val channels = lightningRepo.lightningState.value.channels
        if (channels.isEmpty()) return null

        channels.firstOrNull { channel -> channel.fundingTxo?.txid == txid }
            ?.channelId
            ?: run {
                val orders = blocktankRepo.blocktankState.value.orders
                val matchingOrder = orders.firstOrNull { order ->
                    order.payment?.onchain?.transactions?.any { it.txId == txid } == true
                } ?: return null

                val orderChannel = matchingOrder.channel ?: return null
                channels.firstOrNull { it.fundingTxo?.txid == orderChannel.fundingTx.id }?.channelId
            }
    }.onFailure {
        Logger.warn("Failed to find open channel for transaction: '$txid'", it, context = TAG)
    }.getOrNull()

    private suspend fun findClosedChannelForTransaction(txid: String): String? =
        coreService.activity.findClosedChannelForTransaction(txid, null)

    suspend fun getOnchainActivityByTxId(
        txid: String,
        walletId: String = WalletScope.default,
    ): OnchainActivity? = coreService.activity.getOnchainActivityByTxId(txid, walletId)

    /**
     * Checks if a transaction is inbound (received) by looking up the payment direction.
     */
    suspend fun isReceivedTransaction(txid: String): Boolean = withContext(bgDispatcher) {
        lightningRepo.getPayments().getOrNull()?.let { payments ->
            payments.firstOrNull { (it.kind as? PaymentKind.Onchain)?.txid == txid }
        }?.direction == PaymentDirection.INBOUND
    }

    /**
     * Checks if a transaction was replaced (RBF) by checking if the activity exists but `doesExist=false`.
     */
    suspend fun wasTransactionReplaced(txid: String): Boolean = withContext(bgDispatcher) {
        val onchainActivity = getOnchainActivityByTxId(txid) ?: return@withContext false
        return@withContext !onchainActivity.doesExist
    }

    suspend fun handleOnchainTransactionReceived(
        txid: String,
        details: TransactionDetails,
    ) {
        coreService.activity.handleOnchainTransactionReceived(txid, details)
        notifyActivitiesChanged()
    }

    suspend fun handleOnchainTransactionConfirmed(
        txid: String,
        details: TransactionDetails,
    ) {
        coreService.activity.handleOnchainTransactionConfirmed(txid, details)
        notifyActivitiesChanged()
    }

    suspend fun persistHwSnapshot(
        walletId: String,
        activities: List<Activity>,
        transactionDetails: List<BitkitCoreTransactionDetails>,
    ): Result<List<Activity>> = withContext(bgDispatcher) {
        runSuspendCatching {
            val transferChannelIds = transferRepo.getChannelIdsByFundingTxId().getOrDefault(emptyMap())
            val persistedActivities = coreService.activity.replaceHwSnapshot(
                walletId = walletId,
                activities = activities,
                transactionDetails = transactionDetails,
                transferChannelIdsByFundingTxId = transferChannelIds,
            )
            notifyActivitiesChanged()
            persistedActivities
        }.onFailure {
            Logger.error("Failed to persist hardware activities for '$walletId'", it, context = TAG)
        }
    }

    suspend fun deleteForWallet(walletId: String): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            val deleted = coreService.activity.deleteByWalletId(walletId)
            notifyActivitiesChanged()
            Logger.info("Deleted '$deleted' activities for hardware wallet '$walletId'", context = TAG)
        }.onFailure {
            Logger.error("Failed to delete activities for hardware wallet '$walletId'", it, context = TAG)
        }
    }

    suspend fun handleOnchainTransactionReplaced(txid: String, conflicts: List<String>) {
        coreService.activity.handleOnchainTransactionReplaced(txid, conflicts)
        notifyActivitiesChanged()
    }

    suspend fun handleOnchainTransactionReorged(txid: String) {
        coreService.activity.handleOnchainTransactionReorged(txid)
        notifyActivitiesChanged()
    }

    suspend fun handleOnchainTransactionEvicted(txid: String) {
        coreService.activity.handleOnchainTransactionEvicted(txid)
        notifyActivitiesChanged()
    }

    suspend fun handlePaymentEvent(paymentHash: String) {
        coreService.activity.handlePaymentEvent(paymentHash)
        notifyActivitiesChanged()
    }

    suspend fun notifyPaymentActivityChanged() = notifyActivitiesChanged()

    suspend fun shouldShowReceivedSheet(txid: String, value: ULong): Boolean {
        return coreService.activity.shouldShowReceivedSheet(txid, value)
    }

    suspend fun isActivitySeen(
        activityId: String,
        walletId: String = WalletScope.default,
    ): Boolean {
        return coreService.activity.isActivitySeen(activityId, walletId)
    }

    suspend fun markActivityAsSeen(
        activityId: String,
        walletId: String = WalletScope.default,
    ) {
        coreService.activity.markActivityAsSeen(activityId, walletId = walletId)
        notifyActivitiesChanged()
    }

    suspend fun markOnchainActivityAsSeen(
        txid: String,
        walletId: String = WalletScope.default,
    ) {
        coreService.activity.markOnchainActivityAsSeen(txid, walletId = walletId)
        notifyActivitiesChanged()
    }

    suspend fun getTransactionDetails(
        txid: String,
        walletId: String = WalletScope.default,
    ): Result<BitkitCoreTransactionDetails?> = runSuspendCatching {
        coreService.activity.getTransactionDetails(txid, walletId)
    }

    suspend fun getBoostTxDoesExist(
        boostTxIds: List<String>,
        walletId: String = WalletScope.default,
    ): Map<String, Boolean> {
        return coreService.activity.getBoostTxDoesExist(boostTxIds, walletId)
    }

    suspend fun isCpfpChildTransaction(
        txId: String,
        walletId: String = WalletScope.default,
    ): Boolean = coreService.activity.isCpfpChildTransaction(txId, walletId)

    suspend fun getTxIdsInBoostTxIds(walletId: String = WalletScope.default): Set<String> =
        coreService.activity.getTxIdsInBoostTxIds(walletId)

    /**
     * Gets a specific activity by payment hash or txID with retry logic
     */
    suspend fun findActivityByPaymentId(
        paymentHashOrTxId: String,
        type: ActivityFilter,
        txType: PaymentType?,
        retry: Boolean = true,
    ): Result<Activity> = withContext(bgDispatcher) {
        runCatching {
            require(paymentHashOrTxId.isNotEmpty()) { "paymentHashOrTxId is empty" }

            suspend fun findActivity(): Activity? = getActivities(filter = type, txType = txType, limit = 10u)
                .getOrNull()
                ?.firstOrNull { it.matchesPaymentId(paymentHashOrTxId) }

            var activity = findActivity()
            if (activity == null && retry) {
                Logger.warn(
                    "activity with paymentHashOrTxId:'$paymentHashOrTxId' not found, retrying after sync",
                    context = TAG
                )

                lightningRepo.sync().onSuccess { Logger.debug("Syncing LN node SUCCESS", context = TAG) }

                syncActivities().onSuccess {
                    Logger.debug(
                        "Sync success, searching again the activity with paymentHashOrTxId:'$paymentHashOrTxId'",
                        context = TAG,
                    )
                    activity = findActivity()
                }
            }

            checkNotNull(activity) { "Activity not found" }
        }.onFailure {
            Logger.error(
                "findActivityByPaymentId error (paymentHashOrTxId:'$paymentHashOrTxId' type:'$type' txType:'$txType')",
                context = TAG,
            )
        }
    }

    suspend fun getActivities(
        walletId: String? = WalletScope.default,
        filter: ActivityFilter? = null,
        txType: PaymentType? = null,
        tags: List<String>? = null,
        search: String? = null,
        minDate: ULong? = null,
        maxDate: ULong? = null,
        limit: UInt? = null,
        sortDirection: SortDirection? = null,
    ): Result<List<Activity>> = withContext(bgDispatcher) {
        runSuspendCatching {
            coreService.activity.get(
                walletId = walletId,
                filter = filter,
                txType = txType,
                tags = tags,
                search = search,
                minDate = minDate,
                maxDate = maxDate,
                limit = limit,
                sortDirection = sortDirection,
            )
        }.onFailure {
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
                it,
                context = TAG,
            )
        }
    }

    suspend fun getActivity(
        id: String,
        walletId: String = WalletScope.default,
    ): Result<Activity?> = withContext(bgDispatcher) {
        runSuspendCatching {
            coreService.activity.getActivity(id, walletId)
        }.onFailure {
            Logger.error("getActivity error for ID: $id", it, context = TAG)
        }
    }

    suspend fun contactActivities(publicKey: String): Result<List<Activity>> = withContext(ioDispatcher) {
        runCatching {
            val normalizedKey = PubkyPublicKeyFormat.normalized(publicKey) ?: publicKey
            val txIdsInBoostTxIds = getTxIdsInBoostTxIds()
            getActivities(
                filter = ActivityFilter.ALL,
                sortDirection = SortDirection.DESC,
            ).getOrThrow()
                .filterNot { it.isReplacedSentTransaction(txIdsInBoostTxIds) }
                .filter { PubkyPublicKeyFormat.matches(it.contact(), normalizedKey) }
        }.onFailure {
            Logger.error("Failed to load contact activities for '$publicKey'", it, context = TAG)
        }
    }

    suspend fun setContact(
        contactPublicKey: String,
        forPaymentId: String,
        syncLdkPayments: Boolean = true,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            if (syncLdkPayments) {
                lightningRepo.getPayments().onSuccess {
                    syncLdkNodePayments(it).getOrThrow()
                }.getOrThrow()
            }

            val normalizedKey = PubkyPublicKeyFormat.normalized(contactPublicKey) ?: contactPublicKey
            val activity = findActivityForPaymentId(forPaymentId, syncLdkPayments)
            if (activity == null) {
                Logger.warn(
                    "Skipped setting contact for payment '$forPaymentId' because activity was not found",
                    context = TAG,
                )
                return@runCatching
            }
            if (PubkyPublicKeyFormat.matches(activity.contact(), normalizedKey)) {
                return@runCatching
            }

            val updatedAt = nowTimestamp().epochSecond.toULong()
            val updatedActivity = activity.withContact(normalizedKey, updatedAt)
            updateActivity(updatedActivity.rawId(), updatedActivity).getOrThrow()
            updateReplacementContactIfNeeded(updatedActivity, normalizedKey, updatedAt)
        }.onFailure {
            Logger.error("Failed to set contact for payment '$forPaymentId'", it, context = TAG)
        }
    }

    suspend fun clearContact(
        forPaymentId: String,
        syncLdkPayments: Boolean = true,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            if (syncLdkPayments) {
                lightningRepo.getPayments().onSuccess {
                    syncLdkNodePayments(it).getOrThrow()
                }.getOrThrow()
            }

            val activity = findActivityForPaymentId(forPaymentId, syncLdkPayments)
            if (activity == null) {
                Logger.warn(
                    "Skipped clearing contact for payment '$forPaymentId' because activity was not found",
                    context = TAG,
                )
                return@runCatching
            }
            if (activity.contact() == null) return@runCatching

            val updatedAt = nowTimestamp().epochSecond.toULong()
            val updatedActivity = activity.withContact(null, updatedAt)
            updateActivity(updatedActivity.rawId(), updatedActivity).getOrThrow()
            updateReplacementContactIfNeeded(updatedActivity, null, updatedAt)
        }.onFailure {
            Logger.error("Failed to clear contact for payment '$forPaymentId'", it, context = TAG)
        }
    }

    private suspend fun updateReplacementContactIfNeeded(
        activity: Activity,
        normalizedKey: String?,
        updatedAt: ULong,
    ) {
        if (activity !is Activity.Onchain || activity.v1.doesExist || activity.v1.txType != PaymentType.SENT) return

        getActivities(filter = ActivityFilter.ONCHAIN).getOrThrow()
            .filterIsInstance<Activity.Onchain>()
            .filter { activity.v1.txId in it.v1.boostTxIds }
            .filterNot { PubkyPublicKeyFormat.matches(it.v1.contact, normalizedKey) }
            .forEach {
                val updatedReplacement = Activity.Onchain(it.v1.copy(contact = normalizedKey, updatedAt = updatedAt))
                updateActivity(updatedReplacement.rawId(), updatedReplacement).getOrThrow()
            }
    }

    private suspend fun findActivityForPaymentId(forPaymentId: String, syncLdkPayments: Boolean): Activity? {
        val activity = getActivityByPaymentId(forPaymentId)
        if (activity != null) return activity
        if (!syncLdkPayments) return null

        syncActivities().getOrThrow()
        return getActivityByPaymentId(forPaymentId)
    }

    private suspend fun getActivityByPaymentId(forPaymentId: String): Activity? =
        coreService.activity.getActivity(forPaymentId, WalletScope.default)
            ?: getOnchainActivityByTxId(forPaymentId)?.let { Activity.Onchain(it) }

    private fun Activity.withContact(normalizedKey: String?, updatedAt: ULong): Activity = when (this) {
        is Activity.Lightning -> Activity.Lightning(v1.copy(contact = normalizedKey, updatedAt = updatedAt))
        is Activity.Onchain -> Activity.Onchain(v1.copy(contact = normalizedKey, updatedAt = updatedAt))
    }

    suspend fun getClosedChannels(
        sortDirection: SortDirection = SortDirection.ASC,
    ): Result<List<ClosedChannelDetails>> = withContext(bgDispatcher) {
        runCatching {
            coreService.activity.closedChannels(sortDirection)
        }.onFailure {
            Logger.error("Error getting closed channels (sortDirection=$sortDirection)", it, context = TAG)
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
        runCatching {
            if (cacheStore.data.first().isActivityDeleted(id, activity.walletId()) && !forceUpdate) {
                Logger.debug("Activity $id was deleted", context = TAG)
                return@withContext Result.failure(
                    Exception(
                        "Activity $id was deleted. If you want update it, set forceUpdate as true"
                    )
                )
            }
            coreService.activity.update(id, activity)
            notifyActivitiesChanged()
        }.onFailure {
            Logger.error("updateActivity error for ID: $id", it, context = TAG)
        }
    }

    /**
     * Updates an activity and marks the old one as removed from mempool (for RBF).
     * In case of failure in the update or marking as removed, the data will be cached
     * to try again on the next sync.
     */
    suspend fun replaceActivity(
        id: String,
        activityIdToDelete: String,
        activity: Activity,
    ): Result<Unit> = withContext(bgDispatcher) {
        updateActivity(
            id = id,
            activity = activity
        ).onSuccess {
            Logger.debug("Activity $id updated with success. new data: $activity", context = TAG)
            val tags = coreService.activity.tags(activityIdToDelete, WalletScope.default)
            addTagsToActivity(activityId = id, tags = tags)
        }.onFailure {
            Logger.error(
                "updateActivity error: id:$id, activityIdToDelete:$activityIdToDelete activity:$activity",
                it,
                context = TAG,
            )
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

    suspend fun deleteActivity(
        id: String,
        walletId: String = WalletScope.default,
    ): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            val deleted = coreService.activity.delete(id, walletId)
            check(deleted) { "Activity not deleted" }
            cacheStore.addActivityToDeletedList(id, walletId)
            notifyActivitiesChanged()
        }.onFailure {
            Logger.error("deleteActivity error for ID: $id", it, context = TAG)
        }
    }

    suspend fun insertActivity(activity: Activity): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            if (cacheStore.data.first().isActivityDeleted(activity.rawId(), activity.walletId())) {
                Logger.debug("Activity ${activity.rawId()} was deleted, skipping", context = TAG)
                return@withContext Result.failure(Exception("Activity ${activity.rawId()} was deleted"))
            }
            coreService.activity.insert(activity)
            notifyActivitiesChanged()
        }.onFailure {
            Logger.error("insertActivity error", it, context = TAG)
        }
    }

    suspend fun upsertActivity(activity: Activity): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            val id = activity.rawId()
            if (cacheStore.data.first().isActivityDeleted(id, activity.walletId())) {
                Logger.debug("Activity $id was deleted, skipping", context = TAG)
                return@withContext Result.failure(AppError("Activity $id was deleted"))
            }
            coreService.activity.upsert(activity)
            notifyActivitiesChanged()
        }.onFailure {
            Logger.error("upsertActivity error", it, context = TAG)
        }
    }

    /**
     * Inserts a new activity for a fulfilled (channel ready) CJIT order.
     *
     * Returns `true` when a new activity was inserted and `false` when one already existed for the
     * channel. Callers use this to deduplicate repeated channel ready events so the same CJIT receive
     * is not reported twice.
     */
    suspend fun insertActivityFromCjit(
        cjitEntry: IcJitEntry?,
        channel: ChannelDetails,
    ): Result<Boolean> = withContext(bgDispatcher) {
        runCatching {
            requireNotNull(cjitEntry)
            val id = channel.fundingTxo?.txid.orEmpty()
            if (coreService.activity.getActivity(id, WalletScope.default) != null) {
                Logger.debug("Skipping CJIT activity insert: already exists for '$id'", context = TAG)
                return@runCatching false
            }
            val amount = channel.amountOnClose
            val now = nowTimestamp().epochSecond.toULong()
            insertActivity(
                Activity.Lightning(
                    LightningActivity.create(
                        id = id,
                        txType = PaymentType.RECEIVED,
                        status = PaymentState.SUCCEEDED,
                        value = amount,
                        fee = 0U,
                        invoice = cjitEntry.invoice.request,
                        message = "",
                        timestamp = now,
                        preimage = null,
                        contact = null,
                        createdAt = now,
                        updatedAt = null,
                        seenAt = null,
                    )
                )
            ).getOrThrow()
            true
        }.onFailure {
            Logger.error("insertActivity error", it, context = TAG)
        }
    }

    suspend fun addActivityToPendingBoost(pendingBoostActivity: PendingBoostActivity) = withContext(bgDispatcher) {
        cacheStore.addActivityToPendingBoost(pendingBoostActivity)
    }

    @VisibleForTesting
    suspend fun addTagsToActivity(
        activityId: String,
        tags: List<String>,
        walletId: String = WalletScope.default,
    ): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            checkNotNull(coreService.activity.getActivity(activityId, walletId)) {
                "Activity with ID $activityId not found"
            }

            val existingTags = coreService.activity.tags(activityId, walletId)
            val newTags = tags.filter { it.isNotBlank() && it !in existingTags }

            if (newTags.isNotEmpty()) {
                coreService.activity.appendTags(activityId, newTags, walletId).getOrThrow()
                notifyActivitiesChanged()
                Logger.info("Added ${newTags.size} new tags to activity $activityId", context = TAG)
            } else {
                Logger.info("No new tags to add to activity $activityId", context = TAG)
            }
        }.onFailure {
            Logger.error("addTagsToActivity error for activity $activityId", it, context = TAG)
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

        findActivityByPaymentId(
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
    suspend fun removeTagsFromActivity(
        activityId: String,
        tags: List<String>,
        walletId: String = WalletScope.default,
    ): Result<Unit> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                checkNotNull(coreService.activity.getActivity(activityId, walletId)) {
                    "Activity with ID $activityId not found"
                }

                coreService.activity.dropTags(activityId, tags, walletId)
                notifyActivitiesChanged()
                Logger.info("Removed ${tags.size} tags from activity $activityId", context = TAG)
            }.onFailure {
                Logger.error("removeTagsFromActivity error for activity $activityId", it, context = TAG)
            }
        }

    /**
     * Gets all tags for an activity
     */
    suspend fun getActivityTags(
        activityId: String,
        walletId: String = WalletScope.default,
    ): Result<List<String>> = withContext(bgDispatcher) {
        runSuspendCatching {
            coreService.activity.tags(activityId, walletId)
        }.onFailure {
            Logger.error("getActivityTags error for activity $activityId", it, context = TAG)
        }
    }

    suspend fun getAllAvailableTags(): Result<List<String>> = withContext(bgDispatcher) {
        runCatching {
            coreService.activity.allPossibleTags()
        }.onSuccess { tags ->
            _state.update { it.copy(tags = tags.toImmutableList()) }
        }.onFailure {
            Logger.error("getAllAvailableTags error", it, context = TAG)
        }
    }

    /**
     * Get all [ActivityTags] for backup.
     *
     * Scoped to the default wallet: hardware activities are rebuilt by the device watcher and are not
     * backed up, so a restored hardware [ActivityTags] row would have no parent activity and Core's
     * foreign key would reject it. Hardware tags travel as [PreActivityMetadata] instead, see
     * [getHardwareTagsAsPreActivityMetadata].
     */
    suspend fun getAllActivitiesTags(): Result<List<ActivityTags>> = withContext(bgDispatcher) {
        runCatching {
            coreService.activity.getAllActivitiesTags()
                .filter { it.walletId == WalletScope.default }
        }.onFailure {
            Logger.error("getAllActivityTags error", it, context = TAG)
        }
    }

    /**
     * Hardware wallet tags rendered as [PreActivityMetadata] so they can travel in the metadata backup.
     *
     * Hardware activities are rebuilt by the device watcher and are deliberately not backed up, so a
     * restored hardware [ActivityTags] row would reference a missing activity. Core re-attaches
     * pre-activity metadata when the watcher recreates the activity, matching received activities on
     * address and sent activities on payment id, so the tags land back on the right rows.
     *
     * Every other field is left neutral: Core copies `address`, `feeRate`, `isTransfer` and `channelId`
     * onto the activity it attaches to, and only when they are set, so a tag-only record must not carry
     * them.
     */
    suspend fun getHardwareTagsAsPreActivityMetadata(): Result<List<PreActivityMetadata>> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                val hardwareTags = coreService.activity.getAllActivitiesTags()
                    .filter { it.walletId != WalletScope.default }
                if (hardwareTags.isEmpty()) return@runSuspendCatching emptyList()

                val onchainByScopedId = coreService.activity.get(
                    walletId = null,
                    filter = ActivityFilter.ONCHAIN,
                    txType = null,
                    tags = null,
                    search = null,
                    minDate = null,
                    maxDate = null,
                    limit = null,
                    sortDirection = null,
                )
                    .filterIsInstance<Activity.Onchain>()
                    .associateBy { it.v1.walletId to it.v1.id }

                hardwareTags.mapNotNull { tag ->
                    val activity = onchainByScopedId[tag.walletId to tag.activityId] ?: return@mapNotNull null
                    activity.v1.toPreActivityMetadata(tag.tags)
                }
            }.onFailure {
                Logger.error("getHardwareTagsAsPreActivityMetadata error", it, context = TAG)
            }
        }

    /**
     * Fill in wallet ids missing from a backup envelope's `activities` slice, letting Core migrate its own
     * model JSON before the app decodes it.
     */
    suspend fun migrateBackupActivitiesJson(json: String): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching {
            coreService.activity.migrateBackupActivitiesJson(json)
        }
    }

    /**
     * Fill in wallet ids missing from a backup envelope's `activityTags` slice.
     */
    suspend fun migrateBackupActivityTagsJson(json: String): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching {
            coreService.activity.migrateBackupActivityTagsJson(json)
        }
    }

    suspend fun getWalletIds(): Result<Set<String>> = withContext(bgDispatcher) {
        runSuspendCatching {
            coreService.activity.getWalletIds()
        }.onFailure {
            Logger.error("Failed to get activity wallet IDs", it, context = TAG)
        }
    }

    suspend fun restoreFromBackup(payload: ActivityBackupV1): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
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

    suspend fun markAllUnseenActivitiesAsSeen(): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            coreService.activity.markAllUnseenActivitiesAsSeen()
            notifyActivitiesChanged()
        }.onFailure {
            Logger.error("Failed to mark all activities as seen: $it", it, context = TAG)
        }
    }

    // MARK: - Debug Methods

    /**
     * Removes all activities
     */
    suspend fun removeAllActivities(): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            coreService.activity.removeAll()
            Logger.info("Removed all activities", context = TAG)
        }.onFailure {
            Logger.error("removeAllActivities error", it, context = TAG)
        }
    }

    /**
     * Generates random test data (regtest only) with business logic
     */
    suspend fun generateTestData(count: Int = 100): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            val validatedCount = count.coerceIn(1, 1000)
            if (validatedCount != count) {
                Logger.warn("Adjusted test data count from $count to $validatedCount", context = TAG)
            }

            coreService.activity.generateRandomTestData(validatedCount)
            Logger.info("Generated $validatedCount test activities", context = TAG)
        }.onFailure {
            Logger.error("generateTestData error", it, context = TAG)
        }
    }

    companion object {
        private const val TAG = "ActivityRepo"
    }
}

@Immutable
data class ActivityState(
    val tags: ImmutableList<String> = persistentListOf(),
)

/**
 * Renders an on-chain activity's tags as a [PreActivityMetadata] Core can re-attach later.
 *
 * The lookup key mirrors Core: received activities are matched on `address` with `isReceive` set, sent
 * activities on `paymentId`.
 */
private fun OnchainActivity.toPreActivityMetadata(tags: List<String>): PreActivityMetadata {
    val isReceive = txType == PaymentType.RECEIVED
    return PreActivityMetadata(
        walletId = walletId,
        paymentId = if (isReceive) id else txId,
        tags = tags,
        paymentHash = null,
        txId = txId,
        address = address.takeIf { isReceive },
        isReceive = isReceive,
        feeRate = 0uL,
        isTransfer = false,
        channelId = null,
        createdAt = timestamp,
    )
}
