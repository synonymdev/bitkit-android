package to.bitkit.data.widgets

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.env.Env
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsService @Inject constructor(
    private val client: HttpClient,
) {

    /**
     * Fetches articles from the news API
     * @return List of articles
     * @throws NewsError on network or parsing errors
     */
    suspend fun fetchLatestNews(): List<ArticleDTO> {
        return get<List<ArticleDTO>>(Env.newsBaseUrl + "/articles")
    }

    private suspend inline fun <reified T> get(url: String): T {
        val response: HttpResponse = client.get(url)
        Logger.debug("Http call: $response")
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<T>() }.getOrElse {
                    throw NewsError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw NewsError.InvalidResponse(response.status.description)
        }
    }
}

/**
 * News-specific error types
 */
sealed class NewsError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : NewsError(message)
}
