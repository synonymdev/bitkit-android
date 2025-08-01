package to.bitkit.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.async.ServiceQueue
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.Keychain.Key
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Env.DERIVATION_NAME
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.toHex
import to.bitkit.utils.Crypto
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocktankNotificationsService @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val keychain: Keychain,
    private val crypto: Crypto,
) {
    suspend fun registerDevice(deviceToken: String) = withContext(bgDispatcher) {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        Logger.debug("Registering device for notifications…")

        val timestamp = nowTimestamp()
        val messageToSign = "$DERIVATION_NAME$deviceToken$timestamp"

        val signature = lightningService.sign(messageToSign)

        val keypair = crypto.generateKeyPair()
        val publicKey = keypair.publicKey.toHex()
        Logger.debug("Notification encryption public key: $publicKey")

        // New keypair for each token registration
        if (keychain.exists(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)) {
            keychain.delete(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)
        }
        keychain.save(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name, keypair.privateKey)

        ServiceQueue.CORE.background {
            com.synonym.bitkitcore.registerDevice(
                deviceToken = deviceToken,
                publicKey = publicKey,
                features = Env.pushNotificationFeatures.map { it.toString() },
                nodeId = nodeId,
                isoTimestamp = "$timestamp",
                signature = signature,
                customUrl = Env.blocktankPushNotificationServer,
                isProduction = null,
            )
        }

        // Cache token so we can avoid re-registering
        if (keychain.exists(Key.PUSH_NOTIFICATION_TOKEN.name)) {
            keychain.delete(Key.PUSH_NOTIFICATION_TOKEN.name)
        }
        keychain.saveString(Key.PUSH_NOTIFICATION_TOKEN.name, deviceToken)

        Logger.info("Device registered for notifications")
    }
}
