package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import org.bitcoinj.core.ECKey
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.BlocktankApi
import to.bitkit.data.RegisterDeviceRequest
import to.bitkit.data.TestNotificationRequest
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.Keychain.Key
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Tag.LSP
import to.bitkit.shared.ServiceError
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class BlocktankService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val api: BlocktankApi,
    private val lightningService: LightningService,
    private val keychain: Keychain,
) : BaseCoroutineScope(bgDispatcher) {

    // region notifications
    suspend fun registerDevice(deviceToken: String) {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        Log.d(LSP, "Registering device for notifications…")

        val isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        val messageToSign = "bitkit-notifications$deviceToken$isoTimestamp"

        val signature = lightningService.sign(messageToSign)

        val keypair = ECKey()
        val publicKey = keypair.publicKeyAsHex
        Log.d(LSP, "Notification encryption public key: $publicKey")

        // New keypair for each token registration
        if (keychain.exists(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)) {
            keychain.delete(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)
        }
        keychain.save(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name, keypair.privKeyBytes)

        val payload = RegisterDeviceRequest(
            deviceToken = deviceToken,
            publicKey = publicKey,
            features = listOf("blocktank.incomingHtlc"),
            nodeId = nodeId,
            isoTimestamp = isoTimestamp,
            signature = signature,
        )

        ServiceQueue.LSP.background {
            api.registerDeviceForNotifications(payload)
        }
    }

    suspend fun testNotification(deviceToken: String) {
        Log.d(LSP, "Sending test notification to self…")

        val payload = TestNotificationRequest(
            data = TestNotificationRequest.Data(
                source = "blocktank",
                type = "incomingHtlc",
                payload = TestNotificationRequest.Data.Payload(secretMessage = "hello")
            )
        )

        ServiceQueue.LSP.background {
            api.testNotification(deviceToken, payload)
        }
    }
    // endregion
}
