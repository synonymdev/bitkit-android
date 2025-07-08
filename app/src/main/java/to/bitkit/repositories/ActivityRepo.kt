package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.BgDispatcher
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

    companion object {
        private const val TAG = "ActivityRepo"
    }
}
