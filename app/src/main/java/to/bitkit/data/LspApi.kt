package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import javax.inject.Inject

interface LspApi {
    suspend fun registerDeviceForNotifications(payload: RegisterDeviceRequest)
    suspend fun testNotification(deviceToken: String, payload: TestNotificationRequest)
}

class BlocktankApi @Inject constructor(
    private val client: HttpClient,
) : LspApi {
    private val baseUrl = "https://api.stag.blocktank.to"
    private val notificationsApi = "$baseUrl/notifications/api/device"

    override suspend fun registerDeviceForNotifications(payload: RegisterDeviceRequest) {
        post(notificationsApi, payload)
    }

    override suspend fun testNotification(deviceToken: String, payload: TestNotificationRequest) {
        post("$notificationsApi/$deviceToken/test-notification", payload)
    }

    private suspend inline fun <reified T> post(url: String, payload: T) = client.post(url) { setBody(payload) }
}

@Serializable
data class RegisterDeviceRequest(
    val deviceToken: String,
    val publicKey: String,
    val features: List<String>,
    val nodeId: String,
    val isoTimestamp: String,
    val signature: String,
)

@Serializable
data class TestNotificationRequest(
    val data: Data,
) {
    @Serializable
    data class Data(
        val source: String,
        val type: String,
        val payload: Payload,
    ) {
        @Serializable
        data class Payload(
            val secretMessage: String,
        )
    }
}
