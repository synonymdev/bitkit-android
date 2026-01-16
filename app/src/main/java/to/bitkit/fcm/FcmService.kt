package to.bitkit.fcm

import android.os.Bundle
import androidx.core.os.toPersistableBundle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.env.Env.derivationName
import to.bitkit.ext.fromBase64
import to.bitkit.ext.fromHex
import to.bitkit.models.BlocktankNotificationType
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.pushNotification
import to.bitkit.utils.Crypto
import to.bitkit.utils.Logger
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "FcmService"
    }

    private var notificationType: BlocktankNotificationType? = null
    private var notificationPayload: JsonObject? = null

    @Inject
    lateinit var crypto: Crypto

    @Inject
    lateinit var keychain: Keychain

    @Inject
    lateinit var lightningRepo: LightningRepo

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

        if (message.data.isNotEmpty()) {
            Logger.debug("FCM data: ${message.data}", context = TAG)

            val shouldSchedule = runCatching {
                val isEncryptedNotification = message.data.tryAs<EncryptedNotification> {
                    decryptPayload(it)
                }
                isEncryptedNotification
            }.getOrElse {
                Logger.error("Failed to read encrypted notification payload", it, context = TAG)
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
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(this)
            .beginWith(work)
            .enqueue()
    }

    private fun handleNow(data: Map<String, String>) {
        Logger.warn("FCM handler not implemented for: $data", context = TAG)
    }

    @Suppress("ReturnCount")
    private fun decryptPayload(response: EncryptedNotification) {
        val ciphertext = runCatching { response.cipher.fromBase64() }.getOrElse {
            Logger.error("Failed to decode cipher", it, context = TAG)
            return
        }
        val privateKey = runCatching { keychain.load(Keychain.Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)!! }.getOrElse {
            Logger.error("Missing PUSH_NOTIFICATION_PRIVATE_KEY", it, context = TAG)
            return
        }
        val password =
            runCatching { crypto.generateSharedSecret(privateKey, response.publicKey, derivationName) }.getOrElse {
                Logger.error("Failed to generate shared secret", it, context = TAG)
                return
            }

        val decrypted = crypto.decrypt(
            encryptedPayload = Crypto.EncryptedPayload(ciphertext, response.iv.fromHex(), response.tag.fromHex()),
            secretKey = password,
        )

        val decoded = decrypted.decodeToString()
        Logger.debug("Decrypted payload: $decoded", context = TAG)

        val (payload, type) = runCatching { json.decodeFromString<DecryptedNotification>(decoded) }.getOrElse {
            Logger.error("Failed to decode decrypted data", it, context = TAG)
            return
        }

        if (payload == null) {
            Logger.error("Missing payload", context = TAG)
            return
        }

        if (type == null) {
            Logger.error("Missing type", context = TAG)
            return
        }

        notificationType = type
        notificationPayload = payload
    }

    private fun sendNotification(title: String?, body: String?, extras: Bundle? = null) {
        applicationContext.pushNotification(title, body, extras)
    }

    private inline fun <reified T> Map<String, String>.tryAs(block: (T) -> Unit): Boolean {
        val encoded = json.encodeToString(this)
        return try {
            val decoded = json.decodeFromString<T>(encoded)
            block(decoded)
            true
        } catch (_: SerializationException) {
            false
        }
    }

    override fun onNewToken(token: String) {
        Logger.debug("Got new FCM token via FirebaseMessagingService.onNewToken: '$token'", context = TAG)
        lightningRepo.registerForNotificationsAsync(token)
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
