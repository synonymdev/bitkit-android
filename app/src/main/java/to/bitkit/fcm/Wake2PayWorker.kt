package to.bitkit.fcm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import to.bitkit.Tag.FCM
import to.bitkit.ldk.LightningService
import to.bitkit.ldk.payInvoice
import to.bitkit.ldk.warmupNode

@HiltWorker
class Wake2PayWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.d(FCM, "Node wakeup from notification…")

        warmupNode()

        val bolt11 = workerParams.inputData.getString("bolt11") ?: return Result.failure(
            workDataOf("reason" to "bolt11 field missing")
        )

        val isSuccess = LightningService.shared.payInvoice(bolt11)
        return if (isSuccess) {
            Result.success()
        } else {
            Result.failure(
                workDataOf("reason" to "payment error")
            )
        }
    }
}
