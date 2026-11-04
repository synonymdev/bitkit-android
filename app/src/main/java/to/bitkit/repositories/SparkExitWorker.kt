package to.bitkit.repositories

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.App
import to.bitkit.R
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The background work that drives a Spark exit while Bitkit is closed. */
object SparkExitWork {
    private const val PERIODIC_WORK = "spark-exit-tick"
    private const val REMINDER_WORK = "spark-exit-reminder"
    internal const val INPUT_NOTIFY = "notify"

    /** The shortest period WorkManager runs periodic work at. */
    private val PERIOD = 15.minutes

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SparkExitWorker>(PERIOD.inWholeMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Drives the exit after [delay] and, when Bitkit is not open then, asks the user to open it. */
    fun scheduleReminder(context: Context, delay: Duration) {
        val request = OneTimeWorkRequestBuilder<SparkExitWorker>()
            .setInitialDelay(delay.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(workDataOf(INPUT_NOTIFY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REMINDER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(REMINDER_WORK)
        }
    }
}

@HiltWorker
class SparkExitWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sparkExitRepo: SparkExitRepo,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "SparkExitWorker"
    }

    override suspend fun doWork(): Result {
        // Notified before the run: the run queues the next reminder, which replaces this work.
        val shouldNotify = inputData.getBoolean(SparkExitWork.INPUT_NOTIFY, false)
        val isExiting = sparkExitRepo.load().getOrNull()?.isActive == true
        if (shouldNotify && isExiting && App.currentActivity?.value == null) {
            applicationContext.pushNotification(
                title = applicationContext.getString(R.string.lightning__spark_exit__notification_title),
                text = applicationContext.getString(R.string.lightning__spark_exit__notification_body),
            )
        }
        sparkExitRepo.runInBackground().onFailure {
            Logger.warn("Failed to drive the Spark exit in background", it, context = TAG)
            return Result.retry()
        }
        return Result.success()
    }
}
