package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import org.bitcoinj.core.ECKey
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.LspApi
import to.bitkit.data.RegisterDeviceRequest
import to.bitkit.data.TestNotificationRequest
import to.bitkit.data.keychain.KeychainStore
import to.bitkit.data.keychain.KeychainStore.Key
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Tag.LSP
import to.bitkit.shared.ServiceError
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class BlocktankService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val lspApi: LspApi,
    private val lightningService: LightningService,
    private val keychainStore: KeychainStore,
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
        if (keychainStore.exists(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)) {
            keychainStore.delete(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)
        }
        keychainStore.save(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name, keypair.privKeyBytes)

        val payload = RegisterDeviceRequest(
            deviceToken = deviceToken,
            publicKey = publicKey,
            features = listOf("blocktank.incomingHtlc"),
            nodeId = nodeId,
            isoTimestamp = isoTimestamp,
            signature = signature,
        )

        ServiceQueue.LSP.background {
            lspApi.registerDeviceForNotifications(payload)
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
            lspApi.testNotification(deviceToken, payload)
        }
    }
    // endregion
}
