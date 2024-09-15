package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.LspApi
import to.bitkit.data.RegisterDeviceRequest
import to.bitkit.data.TestNotificationRequest
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
) : BaseCoroutineScope(bgDispatcher) {

    suspend fun registerDevice(deviceToken: String) {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        Log.d(LSP, "Registering device for notifications…")

        val isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        val messageToSign = "bitkit-notifications$deviceToken$isoTimestamp"

        val signature = lightningService.sign(messageToSign)

        // TODO: Use actual public key to enable decryption of the push notification payload
        val publicKey = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"

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
}
