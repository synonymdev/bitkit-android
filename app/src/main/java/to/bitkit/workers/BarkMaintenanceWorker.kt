package to.bitkit.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.repositories.BarkRepo
import to.bitkit.repositories.BarkTransferRepo
import to.bitkit.utils.Logger
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Keeps Ark funds alive while the app is backgrounded.
 *
 * VTXOs expire (~28 days for round VTXOs, ~3 days for Lightning receives) and the Ark server can
 * sweep them once they do, so a wallet that never comes online loses its balance. Delegated
 * maintenance is the mobile path: co-signers refresh on the wallet's behalf, so this worker does
 * not have to land inside a round window.
 */
@HiltWorker
class BarkMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val barkRepo: BarkRepo,
    private val barkTransferRepo: BarkTransferRepo,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BarkMaintenanceWorker"
        private const val WORK_NAME = "bark_maintenance"

        /**
         * Well inside the ~3 day Lightning-receive VTXO lifetime, which is the shortest deadline
         * the wallet has to meet.
         */
        private val INTERVAL = 6.hours

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<BarkMaintenanceWorker>(INTERVAL.toJavaDuration())
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(workManager: WorkManager) = run { workManager.cancelUniqueWork(WORK_NAME) }
    }

    override suspend fun doWork(): Result {
        if (!barkRepo.isEnabledNow()) {
            Logger.debug("Skipped Ark maintenance because bark is not the spending backend", context = TAG)
            cancel(WorkManager.getInstance(applicationContext))
            return Result.success()
        }

        return barkRepo.startIfEnabled()
            .mapCatching {
                barkRepo.runMaintenance().getOrThrow()
                barkRepo.claimPendingReceives().getOrThrow()
                barkTransferRepo.resumePendingBoard().getOrThrow()
                barkRepo.sync().getOrThrow()
            }
            .fold(
                onSuccess = {
                    Logger.info("Completed Ark maintenance", context = TAG)
                    Result.success()
                },
                onFailure = {
                    Logger.error("Failed Ark maintenance, will retry", it, context = TAG)
                    Result.retry()
                },
            )
    }
}
