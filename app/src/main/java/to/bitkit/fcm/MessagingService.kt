package to.bitkit.fcm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import to.bitkit._FCM
import to.bitkit.ui.payInvoice
import to.bitkit.warmupNode
import java.util.Date

internal class MessagingService : FirebaseMessagingService() {
    private lateinit var token: String

    /**
     * Act on received messages
     *
     * To generate notifications as a result of a received FCM message, see:
     * [MyFirebaseMessagingService.sendNotification](https://github.com/firebase/snippets-android/blob/ae9bd6ff8eccfb3eeba863d41eaca2b0e77eaa01/messaging/app/src/main/java/com/google/firebase/example/messaging/kotlin/MyFirebaseMessagingService.kt#L89-L124)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(_FCM, "--- new FCM ---")
        Log.d(_FCM, "\n")
        Log.d(_FCM, "at: \t ${Date(message.sentTime)}")

        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        message.notification?.let {
            Log.d(_FCM, "title: \t ${it.title}")
            Log.d(_FCM, "body: \t ${it.body}")
        }

        // Check if message contains a data payload.
        if (message.data.isNotEmpty()) {
            Log.d(_FCM, "data: \t ${message.data}")
            Log.d(_FCM, "\n")

            if (message.needsScheduling()) {
                scheduleJob(message.data)
            } else {
                handleNow(message.data)
            }
        }
        Log.d(_FCM, "--- end FCM ---")
    }

    /**
     * Handle message within 10 seconds.
     */
    private fun handleNow(data: Map<String, String>) {
        val bolt11 = data["bolt11"].orEmpty()
        if (bolt11.isNotEmpty()) {
            payInvoice(bolt11)
            return
        }
        Log.d(_FCM, "handleNow() not yet implemented")
    }

    /**
     * Schedule async work using WorkManager for tasks of 10+ seconds.
     */
    private fun scheduleJob(messageData: Map<String, String>) {
        val work = OneTimeWorkRequest.Builder(PayWorker::class.java)
            .setInputData(
                Data.Builder()
                    .putString("bolt11", messageData["bolt11"].orEmpty())
                    .build()
            )
            .build()
        WorkManager.getInstance(this)
            .beginWith(work)
            .enqueue()
    }

    private fun RemoteMessage.needsScheduling(): Boolean {
        return notification == null &&
            data.containsKey("bolt11")
    }

    override fun onNewToken(token: String) {
        this.token = token
        Log.d(_FCM, "onNewToken: $token")
    }
}

@HiltWorker
class PayWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.d(_FCM, "Node wakeup from notification…")
        warmupNode(appContext.filesDir.absolutePath)

        workerParams.inputData.getString("bolt11")?.let { bolt11 ->
            delay(1500) // sleep on bg queue
            val isSuccess = payInvoice(bolt11)
            if (isSuccess) {
                return Result.success()
            } else {
                return Result.failure(
                    Data.Builder()
                        .putString("reason:", "payment error")
                        .build()
                )
            }
        }
        return Result.failure(
            Data.Builder()
                .putString("reason:", "bolt11 field missing")
                .build()
        )
    }
}
