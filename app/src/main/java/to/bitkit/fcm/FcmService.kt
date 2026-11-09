package to.bitkit.fcm

import android.os.Bundle
import androidx.core.os.toPersistableBundle
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import java.util.Date

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "FcmService"
    }

    /**
     * Act on received messages. [Debug](https://goo.gl/39bRNJ)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        Logger.debug("New FCM at: ${Date(message.sentTime)}", context = TAG)

        message.notification?.run {
            Logger.debug("FCM title: $title", context = TAG)
            Logger.debug("FCM body: $body", context = TAG)
            sendNotification(title, body, Bundle(message.data.toPersistableBundle()))
        }
    }

    private fun sendNotification(title: String?, body: String?, extras: Bundle? = null) {
        applicationContext.pushNotification(title, body, extras)
    }

    override fun onNewToken(token: String) {
        Logger.debug("Got new FCM token via FirebaseMessagingService.onNewToken: '$token'", context = TAG)
    }
}
