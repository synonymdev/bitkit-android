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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.env.Env.DERIVATION_NAME
import to.bitkit.env.Tag.FCM
import to.bitkit.ext.containsKeys
import to.bitkit.ext.fromBase64
import to.bitkit.ext.fromHex
import to.bitkit.models.blocktank.BlocktankNotificationType
import to.bitkit.shared.Crypto
import to.bitkit.ui.pushNotification
import java.time.Instant
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
internal class NotificationService : FirebaseMessagingService() {
    private lateinit var token: String

    private var notificationType: BlocktankNotificationType? = null
    private var notificationPayload: JsonObject? = null

    @Inject
    lateinit var crypto: Crypto

    @Inject
    lateinit var keychain: Keychain

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

            val isHandled = runCatching {
                val startTime = System.currentTimeMillis()
                val startTimeFormatted = Instant.ofEpochMilli(startTime).toString()
                Log.d(FCM, "Start Time: $startTimeFormatted")

                val result = message.data.tryAs<EncryptedNotification> {
                    decryptPayload(it)
                    sendNotification(it.title, it.message, extras = bundleOf("sound" to it.sound))
                }

                val endTime = System.currentTimeMillis()
                val endTimeFormatted = Instant.ofEpochMilli(endTime).toString() // Format as needed
                val duration = (endTime - startTime) / 1000.0
                Log.d(FCM, "End Time: $endTimeFormatted, Duration: $duration seconds")

                result
            }.getOrElse {
                Log.e(FCM, "Failed to read encrypted notification payload", it)
                // TODO actual handling
                // false
                true
            }

            if (isHandled) return
            if (message.needsScheduling()) return scheduleJob(message.data)
            handleNow(message.data)
        }
    }

    private fun decryptPayload(response: EncryptedNotification) {
        val ciphertext = runCatching { response.cipher.fromBase64() }.getOrElse {
            Log.e(FCM, ("Failed to decode cipher"), it)
            return
        }
        val privateKey = runCatching { keychain.load(Keychain.Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)!! }.getOrElse {
            Log.e(FCM, "Missing PUSH_NOTIFICATION_PRIVATE_KEY", it)
            return
        }
        val password =
            runCatching { crypto.generateSharedSecret(privateKey, response.publicKey, DERIVATION_NAME) }.getOrElse {
                Log.e(FCM, "Failed to generate shared secret", it)
                return
            }

        val decrypted = crypto.decrypt(
            encryptedPayload = Crypto.EncryptedPayload(ciphertext, response.iv.fromHex(), response.tag.fromHex()),
            secretKey = password,
        )

        val decoded = decrypted.decodeToString()
        Log.d(FCM, "Decrypted payload: $decoded")

        val (payload, type) = runCatching { json.decodeFromString<DecryptedNotification>(decoded) }.getOrElse {
            Log.e(FCM, "Failed to decode decrypted data", it)
            return
        }

        if (payload == null) {
            Log.e(FCM, "Missing payload")
            return
        }

        if (type == null) {
            Log.e(FCM, "Missing type")
            return
        }

        notificationType = type
        notificationPayload = payload
    }

    private fun sendNotification(title: String?, body: String?, extras: Bundle) {
        pushNotification(title, body, extras)
    }

    /**
     * Handle message within 10 seconds.
     */
    private fun handleNow(data: Map<String, String>) {
        Log.w(FCM, "FCM handler not implemented for: $data")
    }

    private inline fun <reified T> Map<String, String>.tryAs(block: (T) -> Unit): Boolean {
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
            data.containsKeys("cipher", "iv", "tag", "publicKey")
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
    val sound: String = "",
    val title: String = "",
    val message: String = "",
    val publicKey: String = "",
)

@Serializable
data class DecryptedNotification(
    val payload: JsonObject? = null,
    val type: BlocktankNotificationType? = null,
)
