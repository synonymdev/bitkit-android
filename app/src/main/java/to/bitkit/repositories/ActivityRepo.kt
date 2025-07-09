package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.matchesPaymentId
import to.bitkit.services.CoreService
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo
) {

    var isSyncingLdkNodePayments = false
    suspend fun syncActivities(): Result<Unit> = withContext(bgDispatcher) {
        Logger.debug("syncActivities called", context = TAG)

        return@withContext runCatching {
            if (isSyncingLdkNodePayments) {
                Logger.warn("LDK-node payments are already being synced, skipping", context = TAG)
                return@withContext Result.failure(Exception())
            }

            isSyncingLdkNodePayments = true
            return@withContext lightningRepo.getPayments()
                .onSuccess { payments ->
                    Logger.debug("Got payments with success, syncing activities", context = TAG)
                    coreService.activity.syncLdkNodePayments(payments = payments)
                    isSyncingLdkNodePayments = false
                    return@withContext Result.success(Unit)
                }.onFailure { e ->
                    Logger.error("Failed to sync ldk-node payments", e, context = TAG)
                    isSyncingLdkNodePayments = false
                    return@withContext Result.failure(e)
                }.map { Unit }
        }.onFailure { e ->
            Logger.error("syncLdkNodePayments error", e, context = TAG)
        }
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
                    Logger.debug(
                        "Sync success, searching again the activity with paymentHashOrTxId:$paymentHashOrTxId",
                        context = TAG
                    )
                    activity = findActivity()
                }
            }

            if (activity != null) Result.success(activity) else Result.failure(IllegalStateException("Activity not found"))
        } catch (e: Exception) {
            Logger.error(
                "findActivityByPaymentId error. Parameters:\n paymentHashOrTxId:$paymentHashOrTxId type:$type txType:$txType",
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
                "getActivities error. Parameters:\nfilter:$filter txType:$txType tags:$tags search:$search minDate:$minDate maxDate:$maxDate limit:$limit sortDirection:$sortDirection",
                e = e,
                context = TAG
            )
        }
    }

    companion object {
        private const val TAG = "ActivityRepo"
    }
}
