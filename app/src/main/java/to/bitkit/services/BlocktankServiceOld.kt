package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.BlocktankHttpClient
import to.bitkit.data.RegisterDeviceRequest
import to.bitkit.data.TestNotificationRequest
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.Keychain.Key
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Env.DERIVATION_NAME
import to.bitkit.env.Tag.LSP
import to.bitkit.ext.first
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.toHex
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.CJitEntry
import to.bitkit.models.blocktank.CreateCjitOptions
import to.bitkit.models.blocktank.CreateOrderOptions
import to.bitkit.shared.Crypto
import to.bitkit.shared.ServiceError
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.initDb
import uniffi.bitkitcore.updateBlocktankUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocktankServiceOld @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val blocktankHttpClient: BlocktankHttpClient,
    private val lightningService: LightningService,
    private val keychain: Keychain,
    private val crypto: Crypto,
) : BaseCoroutineScope(bgDispatcher) {

    suspend fun getInfoCore() {
        ServiceQueue.LSP.background {
            try {
                // Initialize database
                val dbPath = Env.bitkitCoreStoragePath(walletIndex = 0)
                initDb(basePath = dbPath)

                // Update Blocktank URL
                // updateBlocktankUrl("https://api1.blocktank.to/api")
                updateBlocktankUrl(Env.blocktankClientServer)

                uniffi.bitkitcore.getInfo(refresh = false)
                uniffi.bitkitcore.getInfo(refresh = true)?.let { info: IBtInfo ->
                    Log.d(LSP, "Blocktank info: $info")
                }

            } catch (e: Exception) {
                Log.e(LSP, "Error getting Blocktank info:", e)
            }
        }
    }

    suspend fun getInfo() = ServiceQueue.LSP.background { blocktankHttpClient.getInfo() }

    // region orders
    suspend fun createOrder(spendingBalanceSats: Int, channelExpiryWeeks: Int = 6): BtOrder {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val receivingBalanceSats = spendingBalanceSats * 2 // TODO: confirm
        val timestamp = nowTimestamp()
        val signature = lightningService.sign("channelOpen-$timestamp")
        val options = CreateOrderOptions.initWithDefaults().copy(
            wakeToOpen = CreateOrderOptions.WakeToOpen(
                nodeId = nodeId,
                timestamp = "$timestamp",
                signature = signature,
            ),
            clientBalanceSat = spendingBalanceSats,
            zeroConf = true,
            zeroReserve = true,
            zeroConfPayment = false,
        )

        val order = ServiceQueue.LSP.background {
            blocktankHttpClient.createOrder(receivingBalanceSats, channelExpiryWeeks, options)
        }

        return order
    }

    suspend fun getOrders(orderIds: List<String>): List<BtOrder> {
        return ServiceQueue.LSP.background {
            if (orderIds.size == 1) {
                listOfNotNull(
                    orderIds.first?.let { blocktankHttpClient.getOrder(it) }
                )
            } else {
                blocktankHttpClient.getOrders(orderIds)
            }
        }
    }
    // endregion

    // region cjit
    suspend fun createCjit(amountSats: Int, description: String): CJitEntry {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val entry = ServiceQueue.LSP.background {
            blocktankHttpClient.createCJitEntry(
                channelSizeSat = amountSats * 2, // TODO: confirm default from RN app
                invoiceSat = amountSats,
                invoiceDescription = description,
                nodeId = nodeId,
                channelExpiryWeeks = 2, // TODO: check default value in RN app
                options = CreateCjitOptions(),
            )
        }

        return entry
    }
    // endregion

    // region channels
    suspend fun openChannel(orderId: String) {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        ServiceQueue.LSP.background {
            blocktankHttpClient.openChannel(orderId, nodeId)
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
            blocktankHttpClient.registerDevice(payload)
        }

        // Cache token so we can avoid re-registering
        if (keychain.exists(Key.PUSH_NOTIFICATION_TOKEN.name)) {
            keychain.delete(Key.PUSH_NOTIFICATION_TOKEN.name)
        }
        keychain.saveString(Key.PUSH_NOTIFICATION_TOKEN.name, deviceToken)

        Log.i(LSP, "Device registered for notifications")
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
            blocktankHttpClient.testNotification(deviceToken, payload)
        }
    }
    // endregion
}
