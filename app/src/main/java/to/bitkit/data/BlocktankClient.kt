package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import to.bitkit.env.Env
import to.bitkit.shared.BlocktankError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocktankClient @Inject constructor(
    private val client: HttpClient,
) {
    private val baseUrl get() = Env.blocktankBaseUrl
    private val notificationsApi get() = "${baseUrl}/notifications/api/device"

    suspend fun registerDeviceForNotifications(payload: RegisterDeviceRequest) {
        post(notificationsApi, payload)
    }

    suspend fun testNotification(deviceToken: String, payload: TestNotificationRequest) {
        post("$notificationsApi/$deviceToken/test-notification", payload)
    }

    private suspend inline fun <reified T> post(url: String, payload: T): HttpResponse {
        val response = client.post(url) { setBody(payload) }
        return when (val statusCode = response.status.value) {
            !in 200..299 -> throw BlocktankError.InvalidResponse(statusCode)
            else -> response
        }
    }
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
        val payload: JsonObject,
    )
}
