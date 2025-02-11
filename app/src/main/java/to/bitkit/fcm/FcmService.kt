package to.bitkit.fcm

import android.os.Bundle
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
import to.bitkit.ext.fromBase64
import to.bitkit.ext.fromHex
import to.bitkit.models.blocktank.BlocktankNotificationType
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Crypto
import to.bitkit.utils.Logger
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
internal class FcmService : FirebaseMessagingService() {
    private var notificationType: BlocktankNotificationType? = null
    private var notificationPayload: JsonObject? = null

    @Inject
    lateinit var crypto: Crypto

    @Inject
    lateinit var keychain: Keychain

    /**
     * Act on received messages. [Debug](https://goo.gl/39bRNJ)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        Logger.debug("New FCM at: ${Date(message.sentTime)}")

        message.notification?.run {
            Logger.debug("FCM title: $title")
            Logger.debug("FCM body: $body")
            sendNotification(title, body, Bundle(message.data.toPersistableBundle()))
        }

        if (message.data.isNotEmpty()) {
            Logger.debug("FCM data: ${message.data}")

            val shouldSchedule = runCatching {
                val isEncryptedNotification = message.data.tryAs<EncryptedNotification> {
                    decryptPayload(it)
                }
                isEncryptedNotification
            }.getOrElse {
                Logger.error("Failed to read encrypted notification payload", it)
                // Let the node to spin up and handle incoming events
                true
            }

            when (shouldSchedule) {
                true -> handleAsync()
                else -> handleNow(message.data)
            }
        }
    }

    private fun handleAsync() {
        val work = OneTimeWorkRequestBuilder<WakeNodeWorker>()
            .setInputData(
                workDataOf(
                    "type" to notificationType?.name,
                    "payload" to notificationPayload?.toString(),
                )
            )
            .build()
        WorkManager.getInstance(this)
            .beginWith(work)
            .enqueue()
    }

    private fun handleNow(data: Map<String, String>) {
        Logger.warn("FCM handler not implemented for: $data")
    }

    private fun decryptPayload(response: EncryptedNotification) {
        val ciphertext = runCatching { response.cipher.fromBase64() }.getOrElse {
            Logger.error("Failed to decode cipher", it)
            return
        }
        val privateKey = runCatching { keychain.load(Keychain.Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)!! }.getOrElse {
            Logger.error("Missing PUSH_NOTIFICATION_PRIVATE_KEY", it)
            return
        }
        val password =
            runCatching { crypto.generateSharedSecret(privateKey, response.publicKey, DERIVATION_NAME) }.getOrElse {
                Logger.error("Failed to generate shared secret", it)
                return
            }

        val decrypted = crypto.decrypt(
            encryptedPayload = Crypto.EncryptedPayload(ciphertext, response.iv.fromHex(), response.tag.fromHex()),
            secretKey = password,
        )

        val decoded = decrypted.decodeToString()
        Logger.debug("Decrypted payload: $decoded")

        val (payload, type) = runCatching { json.decodeFromString<DecryptedNotification>(decoded) }.getOrElse {
            Logger.error("Failed to decode decrypted data", it)
            return
        }

        if (payload == null) {
            Logger.error("Missing payload")
            return
        }

        if (type == null) {
            Logger.error("Missing type")
            return
        }

        notificationType = type
        notificationPayload = payload
    }

    private fun sendNotification(title: String?, body: String?, extras: Bundle? = null) {
        pushNotification(title, body, extras, context = applicationContext)
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

    override fun onNewToken(token: String) {
        // this.token = token
        // TODO call blocktankService.registerDevice(token)
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
