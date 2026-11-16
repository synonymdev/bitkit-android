package to.bitkit.fcm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.R
import to.bitkit.androidServices.LightningNodeService.Companion.CHANNEL_ID_NODE
import to.bitkit.ui.ID_NOTIFICATION_WAKE
import to.bitkit.ui.notificationBuilder
import to.bitkit.utils.Logger

/** Receives one push as an expedited job, so `onMessageReceived` returns at once. */
@HiltWorker
class WakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val wakeInbox: WakeInbox,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private const val TAG = "WakeWorker"
        private const val KEY_PUSH_JSON = "push_json"

        /** Runs for one push before its acknowledgement is given up, which leaves the wake's fallback alert to fire. */
        private const val MAX_RUNS = 3

        fun enqueue(context: Context, pushJson: String) {
            val request = OneTimeWorkRequestBuilder<WakeWorker>()
                .setInputData(workDataOf(KEY_PUSH_JSON to pushJson))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val pushJson = inputData.getString(KEY_PUSH_JSON) ?: return Result.failure()
        return wakeInbox.receive(pushJson).fold(
            onSuccess = {
                Logger.debug("Received wake push '$it'", context = TAG)
                Result.success()
            },
            onFailure = {
                Logger.warn("Failed to receive wake push", it, context = TAG)
                if (runAttemptCount + 1 < MAX_RUNS) Result.retry() else Result.failure()
            },
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        ID_NOTIFICATION_WAKE,
        applicationContext.notificationBuilder(channelId = CHANNEL_ID_NODE)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification__service__body))
            .build(),
    )
}
