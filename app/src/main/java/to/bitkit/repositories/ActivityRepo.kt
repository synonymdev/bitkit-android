package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.matchesPaymentId
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningService: LightningService,
) {
    private val _activityState = MutableStateFlow(ActivityState())
    val activityState = _activityState.asStateFlow()

    /**
     * Executes an operation within the background dispatcher and handles exceptions
     */
    private suspend fun <T> executeOperation(
        operationName: String,
        operation: suspend () -> T,
    ): Result<T> = withContext(bgDispatcher) {
        return@withContext runCatching {
            Logger.debug("Operation called: $operationName", context = TAG)
            operation()
        }.onFailure { e ->
            Logger.error("$operationName error", e, context = TAG)
        }
    }

    /**
     * Syncs all activity data (latest, lightning, onchain, filtered, and available tags)
     */
    suspend fun syncAllData(): Result<Unit> = executeOperation("Sync all data") {
        // Fetch latest activities for the home screen
        val latestActivities = coreService.activity.getLatestActivities(limit = 3u)

        // Fetch lightning and onchain activities
        val lightningActivities = coreService.activity.getLightningActivities()
        val onchainActivities = coreService.activity.getOnchainActivities()

        // Fetch available tags
        val availableTags = coreService.activity.allPossibleTags()

        // Update state
        _activityState.update { currentState ->
            currentState.copy(
                latestActivities = latestActivities,
                lightningActivities = lightningActivities,
                onchainActivities = onchainActivities,
                availableTags = availableTags
            )
        }

        // Initial sync complete
    }

    /**
     * Gets filtered activities based on provided filter criteria
     */
    suspend fun getFilteredActivities(
        selectedTab: ActivityTab,
        selectedTags: Set<String>,
        searchText: String,
        startDate: Long?,
        endDate: Long?,
    ): Result<List<Activity>> = executeOperation("Get filtered activities") {
        val txType: PaymentType? = when (selectedTab) {
            ActivityTab.SENT -> PaymentType.SENT
            ActivityTab.RECEIVED -> PaymentType.RECEIVED
            else -> null
        }

        val activities = coreService.activity.get(
            filter = ActivityFilter.ALL,
            txType = txType,
            tags = selectedTags.takeIf { it.isNotEmpty() }?.toList(),
            search = searchText.takeIf { it.isNotEmpty() },
            minDate = startDate?.let { it / 1000 }?.toULong(),
            maxDate = endDate?.let { it / 1000 }?.toULong(),
        )

        val filteredActivities = when (selectedTab) {
            ActivityTab.OTHER -> activities.filter { it is Activity.Onchain && it.v1.isTransfer }
            else -> activities
        }

        filteredActivities
    }

    /**
     * Gets a specific activity by ID
     */
    suspend fun getActivity(id: String): Result<Activity?> = executeOperation("Get activity") {
        coreService.activity.getActivity(id)
    }

    /**
     * Gets a specific activity by payment hash or txID
     */
    suspend fun findActivityByPaymentId(
        paymentHashOrTxId: String,
        type: ActivityFilter,
        txType: PaymentType,
    ): Result<Activity> = withContext(bgDispatcher) {

        return@withContext try {
            suspend fun findActivity(): Activity? = getActivities(
                filter = type,
                txType = txType,
                limit = 10u
            ).getOrNull()?.firstOrNull { it.matchesPaymentId(paymentHashOrTxId) }

            var activity = findActivity()
            if (activity == null) {
                Logger.warn(
                    "activity with paymentHashOrTxId:$paymentHashOrTxId not found, trying again after sync",
                    context = TAG
                )
                syncActivities().onSuccess {
                    activity = findActivity()
                }
            }

            if (activity != null) Result.success(activity) else Result.failure(IllegalStateException("Activity not found"))
        } catch (e: Exception) {

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
    ): Result<List<Activity>> = executeOperation("Get activities") {
        coreService.activity.get(filter, txType, tags, search, minDate, maxDate, limit, sortDirection)
    }

    /**
     * Inserts a new activity
     */
    suspend fun insertActivity(activity: Activity): Result<Unit> = executeOperation("Insert activity") {
        coreService.activity.insert(activity)
    }

    /**
     * Upserts an activity (insert or update)
     */
    suspend fun upsertActivity(activity: Activity): Result<Unit> = executeOperation("Upsert activity") {
        coreService.activity.upsert(activity)
    }

    /**
     * Updates an existing activity
     */
    suspend fun updateActivity(id: String, activity: Activity): Result<Unit> = executeOperation("Update activity") {
        coreService.activity.update(id, activity)
    }

    /**
     * Deletes an activity by ID
     */
    suspend fun deleteActivity(id: String): Result<Unit> = executeOperation("Delete activity") {
        val success = coreService.activity.delete(id)
        if (success) Result.success(Unit) else Result.failure(Exception())
    }

    /**
     * Syncs LDK node payments and refreshes state
     */
    suspend fun syncActivities(): Result<Unit> = executeOperation("Sync LDK payments") {
        if (_activityState.value.isSyncingLdkNodePayments) {
            Logger.warn("LDK-node payments are already being synced, skipping")
            return@executeOperation
        }

        _activityState.update { it.copy(isSyncingLdkNodePayments = true) }

        val payments = lightningService.payments

        if (payments.isNullOrEmpty()) {
            Logger.error("Payments not found, skipping")
            return@executeOperation
        }

        try {
            coreService.activity.syncLdkNodePayments(payments)
            syncAllData().getOrThrow()
        } finally {
            _activityState.update { it.copy(isSyncingLdkNodePayments = false) }
        }
    }

    // MARK: - Tag Methods

    /**
     * Adds tags to an activity
     */
    suspend fun addTags(toActivityId: String, tags: List<String>): Result<Unit> = executeOperation("Add tags") {
        coreService.activity.appendTags(toActivityId, tags).getOrThrow()
    }

    /**
     * Removes tags from an activity
     */
    suspend fun removeTags(fromActivityId: String, tags: List<String>): Result<Unit> = executeOperation("Remove tags") {
        coreService.activity.dropTags(fromActivityId, tags)
    }

    /**
     * Gets tags for a specific activity
     */
    suspend fun getActivityTags(forActivityId: String): Result<List<String>> = executeOperation("Get activity tags") {
        coreService.activity.tags(forActivityId)
    }

    /**
     * Gets all available tags across all activities
     */
    suspend fun getAllAvailableTags(): Result<List<String>> = executeOperation("Get all available tags") {
        val tags = coreService.activity.allPossibleTags()
        _activityState.update { it.copy(availableTags = tags) }
        tags
    }

    // MARK: - Test Methods (Regtest only)

    /**
     * Generates random test data for development/testing
     */
    suspend fun generateRandomTestData(count: Int = 100): Result<Unit> = executeOperation("Generate test data") {
        coreService.activity.generateRandomTestData(count)
        syncAllData().getOrThrow()
    }

    /**
     * Removes all activities (Regtest only)
     */
    suspend fun removeAllActivities(): Result<Unit> = executeOperation("Remove all activities") {
        coreService.activity.removeAll()
        syncAllData().getOrThrow()
    }

    private companion object {
        const val TAG = "ActivityRepo"
    }
}

/**
 * Data class representing the current state of activities and filters
 */
data class ActivityState(
    val lightningActivities: List<Activity> = emptyList(),
    val onchainActivities: List<Activity> = emptyList(),
    val latestActivities: List<Activity> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val isSyncingLdkNodePayments: Boolean = false,
)
