package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import to.bitkit.env.Env
import to.bitkit.models.FxRateResponse
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocktankHttpClient @Inject constructor(
    private val client: HttpClient,
) {
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
                    throw BlocktankHttpError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw BlocktankHttpError.InvalidResponse(response.status.description)
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
                    throw BlocktankHttpError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw BlocktankHttpError.InvalidResponse(response.status.description)
        }
    }
    // endregion
}

sealed class BlocktankHttpError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : BlocktankHttpError(message)
}
