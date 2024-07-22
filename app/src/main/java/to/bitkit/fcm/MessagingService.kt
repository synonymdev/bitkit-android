package to.bitkit.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import to.bitkit._FCM
import to.bitkit.ui.payInvoice
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
                scheduleJob()
            } else {
                handleNow(message.data)
            }
        }
        Log.d(_FCM, "--- end FCM ---")
    }

    /**
     * TODO Handle message within 10 seconds.
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
     * TODO Schedule async work using WorkManager for long-running tasks (10 seconds or more)
     */
    private fun scheduleJob() {
        TODO("Not yet implemented: scheduleJob")
    }

    private fun RemoteMessage.needsScheduling(): Boolean {
        // return notification == null && data.isNotEmpty()
        return false
    }

    override fun onNewToken(token: String) {
        this.token = token
        Log.d(_FCM, "onNewToken: $token")
    }
}