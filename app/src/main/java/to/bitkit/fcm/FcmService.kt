package to.bitkit.fcm

import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import androidx.core.os.toPersistableBundle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import to.bitkit.di.json
import to.bitkit.env.Tag.FCM
import to.bitkit.ui.pushNotification
import java.util.Date

internal class FcmService : FirebaseMessagingService() {
    private lateinit var token: String

    /**
     * Act on received messages
     *
     * [Debug](https://goo.gl/39bRNJ)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(FCM, "New FCM at: ${Date(message.sentTime)}")

        message.notification?.run {
            Log.d(FCM, "FCM title: $title")
            Log.d(FCM, "FCM body: $body")
            sendNotification(title, body, Bundle(message.data.toPersistableBundle()))
        }

        if (message.data.isNotEmpty()) {
            Log.d(FCM, "FCM data: ${message.data}")

            if (message.needsScheduling()) {
                scheduleJob(message.data)
            } else {
                handleNow(message.data)
            }
        }
    }

    private fun sendNotification(title: String?, body: String?, extras: Bundle) {
        pushNotification(title, body, extras)
    }

    /**
     * Handle message within 10 seconds.
     */
    private fun handleNow(data: Map<String, String>) {
        val isHandled = data.runAs<EncryptedNotification> {
            val extras = bundleOf(
                "tag" to tag,
                "sound" to sound,
                "publicKey" to publicKey,
            )

            sendNotification(title, message, extras)
        }
        if (!isHandled) {
            Log.e(FCM, "FCM handler not implemented for: $data")
        }
    }

    private inline fun <reified T> Map<String, String>.runAs(block: T.() -> Unit): Boolean {
        val encoded = json.encodeToString(this)
        return try {
            val decoded = json.decodeFromString<T>(encoded)
            block(decoded)
            true
        } catch (e: SerializationException) {
            false
        }
    }

    /**
     * Schedule async work via WorkManager for tasks of 10+ seconds.
     */
    private fun scheduleJob(messageData: Map<String, String>) {
        val work = OneTimeWorkRequestBuilder<Wake2PayWorker>()
            .setInputData(
                workDataOf(
                    "bolt11" to messageData["bolt11"].orEmpty()
                )
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
        Log.d(FCM, "FCM registration token refreshed: $token")
        // TODO call sharedViewModel.registerForNotifications(token)
    }
}

@Serializable
data class EncryptedNotification(
    val cipher: String,
    val iv: String,
    val tag: String,
    val sound: String,
    val title: String,
    val message: String,
    val publicKey: String,
)
