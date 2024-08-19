package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import javax.inject.Inject

interface LspApi {
    suspend fun registerDeviceForNotifications(payload: RegisterDeviceRequest): String
}

class BlocktankApi @Inject constructor(
    private val client: HttpClient,
) : LspApi {
    private val baseUrl = "https://api.stag.blocktank.to"

    override suspend fun registerDeviceForNotifications(payload: RegisterDeviceRequest): String {
        val response = client.post("$baseUrl/notifications/api/device") {
            setBody(payload)
        }

        return response.body<ByteArray>().decodeToString()
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
