package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import to.bitkit.env.Env
import to.bitkit.models.FxRateResponse
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private typealias IgnoreResponse = String

@Singleton
class BlocktankHttpClient @Inject constructor(
    private val client: HttpClient,
) {
    // region notifications
    suspend fun registerDevice(payload: RegisterDeviceRequest) {
        post<IgnoreResponse>("${Env.blocktankPushNotificationServer}/device", payload)
    }

    suspend fun testNotification(deviceToken: String, payload: TestNotificationRequest) {
        post<IgnoreResponse>("${Env.blocktankPushNotificationServer}/device/$deviceToken/test-notification", payload)
    }
    // endregion

    // region rates
    suspend fun fetchLatestRates(): FxRateResponse {
        return get<FxRateResponse>(Env.btcRatesServer)
    }
    // endregion

    // region utils
    private suspend inline fun <reified T> post(url: String, payload: Any? = null): T {
        val response = client.post(url) { payload?.let { setBody(it) } }
        Logger.debug("Http call: $response")
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<T>() }.getOrElse {
                    throw BlocktankErrorOld.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw BlocktankErrorOld.InvalidResponse(response.status.description)
        }
    }

    private suspend inline fun <reified T> get(url: String, queryParams: Map<String, List<String>>? = null): T {
        val response: HttpResponse = client.get(url) {
            url {
                queryParams?.forEach { parameters.appendAll(it.key, it.value) }
            }
        }
        Logger.debug("Http call: $response")
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<T>() }.getOrElse {
                    throw BlocktankErrorOld.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw BlocktankErrorOld.InvalidResponse(response.status.description)
        }
    }
    // endregion
}

sealed class BlocktankErrorOld(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : BlocktankErrorOld(message)
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
