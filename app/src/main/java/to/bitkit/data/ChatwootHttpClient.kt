package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import to.bitkit.BuildConfig
import to.bitkit.env.Env
import to.bitkit.models.ChatwootMessage
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatwootHttpClient @Inject constructor(
    private val client: HttpClient,
) {

    suspend fun postQuestion(message: ChatwootMessage) {
        return post(Env.chatwootUrl, message)
    }

    // region utils
    private suspend inline fun <reified T> post(url: String, payload: Any? = null): T {
        val response = client.post(url) { payload?.let { setBody(it) } }
        Logger.debug("Http call: $response")
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<T>() }.getOrElse {
                    throw ChatwootHttpError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw ChatwootHttpError.InvalidResponse(response.status.description)
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
                    throw ChatwootHttpError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw ChatwootHttpError.InvalidResponse(response.status.description)
        }
    }
}

sealed class ChatwootHttpError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : ChatwootHttpError(message)
}
