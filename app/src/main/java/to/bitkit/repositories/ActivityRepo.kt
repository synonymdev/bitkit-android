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
    private suspend fun syncActivities(): Result<Unit> = withContext(bgDispatcher) {
        return@withContext runCatching {
            if (isSyncingLdkNodePayments) {
                Logger.warn("LDK-node payments are already being synced, skipping", context = TAG)
                return@withContext Result.failure(Exception())
            }

            isSyncingLdkNodePayments = true
            lightningRepo.getPayments()
                .onSuccess { payments ->
                    Logger.debug("Got payments with success, syncing activities")
                    coreService.activity.syncLdkNodePayments(payments = payments)
                    return@withContext Result.success(Unit)
                }.onFailure { e ->
                    Logger.error("Failed to sync ldk-node payments", e, context = TAG)
                    return@withContext Result.failure(e)
                }
            isSyncingLdkNodePayments = false
        }.onFailure { e ->
            Logger.error("syncLdkNodePayments error", e, context = TAG)
        }
    }

    companion object {
        private const val TAG = "ActivityRepo"
    }
}
