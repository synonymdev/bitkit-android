package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.BlocktankClient
import to.bitkit.data.RegisterDeviceRequest
import to.bitkit.data.TestNotificationRequest
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.Keychain.Key
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Env.DERIVATION_NAME
import to.bitkit.env.Tag.LSP
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.toHex
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.CreateOrderOptions
import to.bitkit.shared.Crypto
import to.bitkit.shared.ServiceError
import javax.inject.Inject

class BlocktankService @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val client: BlocktankClient,
    private val lightningService: LightningService,
    private val keychain: Keychain,
    private val crypto: Crypto,
) : BaseCoroutineScope(bgDispatcher) {

    suspend fun getInfo() = ServiceQueue.LSP.background { client.getInfo() }

    // region channels
    suspend fun createOrder(spendingBalanceSats: Int, channelExpiryWeeks: Int = 6): BtOrder {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted
        val receivingBalanceSats = spendingBalanceSats * 2 // TODO: confirm
        val timestamp = nowTimestamp()
        val signature = lightningService.sign("channelOpen-$timestamp")
        val options = CreateOrderOptions().copy(
            wakeToOpen = CreateOrderOptions.WakeToOpen(
                nodeId = nodeId,
                timestamp = "$timestamp",
                signature = signature,
            ),
            clientBalanceSat = spendingBalanceSats,
            nodeId = nodeId,
            zeroConf = true,
            zeroReserve = true,
            zeroConfPayment = false,
        )

        val order = ServiceQueue.LSP.background {
            client.createOrder(receivingBalanceSats, channelExpiryWeeks, options)
        }

        return order
    }

    // endregion

    // region channels
    suspend fun openChannel(orderId: String) {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        ServiceQueue.LSP.background {
            client.openChannel(orderId, nodeId)
            Log.i(LSP, "Opened channel for order $orderId")
        }
    }
    // endregion

    // region notifications
    suspend fun registerDevice(deviceToken: String) {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        Log.d(LSP, "Registering device for notifications…")

        val timestamp = nowTimestamp()
        val messageToSign = "$DERIVATION_NAME$deviceToken$timestamp"

        val signature = lightningService.sign(messageToSign)

        val keypair = crypto.generateKeyPair()
        val publicKey = keypair.publicKey.toHex()
        Log.d(LSP, "Notification encryption public key: $publicKey")

        // New keypair for each token registration
        if (keychain.exists(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)) {
            keychain.delete(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name)
        }
        keychain.save(Key.PUSH_NOTIFICATION_PRIVATE_KEY.name, keypair.privateKey)

        val payload = RegisterDeviceRequest(
            deviceToken = deviceToken,
            publicKey = publicKey,
            features = Env.pushNotificationFeatures.map { it.toString() },
            nodeId = nodeId,
            isoTimestamp = "$timestamp",
            signature = signature,
        )

        ServiceQueue.LSP.background {
            client.registerDevice(payload)
        }
    }

    suspend fun testNotification(deviceToken: String) {
        Log.d(LSP, "Sending test notification to self…")

        val payload = TestNotificationRequest(
            data = TestNotificationRequest.Data(
                source = "blocktank",
                type = "incomingHtlc",
                payload = JsonObject(
                    mapOf(
                        "secretMessage" to JsonPrimitive("hello")
                    )
                )
            )
        )

        ServiceQueue.LSP.background {
            client.testNotification(deviceToken, payload)
        }
    }
    // endregion
}
