package to.bitkit.fcm

import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import to.bitkit._FCM
import java.util.Date

internal class FcmService : FirebaseMessagingService() {
    private lateinit var token: String

    /**
     * Act on received messages
     *
     * To generate notifications as a result of a received FCM message, see:
     * [MyFirebaseMessagingService.sendNotification](https://github.com/firebase/snippets-android/blob/ae9bd6ff8eccfb3eeba863d41eaca2b0e77eaa01/messaging/app/src/main/java/com/google/firebase/example/messaging/kotlin/MyFirebaseMessagingService.kt#L89-L124)
     *
     * [Debug messages not received](https://goo.gl/39bRNJ)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(_FCM, "New FCM at: ${Date(message.sentTime)}")

        message.notification?.run {
            Log.d(_FCM, "FCM title: $title")
            Log.d(_FCM, "FCM body: $body")
        }

        if (message.data.isNotEmpty()) {
            Log.d(_FCM, "FCM data: ${message.data}")

            if (message.needsScheduling()) {
                scheduleJob(message.data)
            } else {
                handleNow(message.data)
            }
        }
    }

    /**
     * Handle message within 10 seconds.
     */
    private fun handleNow(data: Map<String, String>) {
        Log.e(_FCM, "FCM handler not implemented for: $data")
    }

    /**
     * Schedule async work via WorkManager for tasks of 10+ seconds.
     */
    private fun scheduleJob(messageData: Map<String, String>) {
        val work = OneTimeWorkRequest.Builder(Wake2PayWorker::class.java)
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
        Log.d(_FCM, "FCM registration token refreshed: $token")
    }
}

