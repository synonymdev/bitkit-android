package to.bitkit.fcm

import android.os.Bundle
import androidx.core.os.toPersistableBundle
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.env.Env
import to.bitkit.repositories.WakeRepo
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Logger
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "FcmService"
    }

    /** Resolved only on a token change, so a push does not build the wallet graph. */
    @Inject
    lateinit var wakeRepo: Lazy<WakeRepo>

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

        if (Env.wakeGatewayUrl != null && message.data.isNotEmpty()) {
            WakeWorker.enqueue(applicationContext, wakePushJson(message.data))
        }
    }

    private fun sendNotification(title: String?, body: String?, extras: Bundle? = null) {
        applicationContext.pushNotification(title, body, extras)
    }

    override fun onNewToken(token: String) {
        Logger.debug("Received new FCM token", context = TAG)
        if (Env.wakeGatewayUrl == null) return
        wakeRepo.get().onNewPushToken(token)
    }
}
