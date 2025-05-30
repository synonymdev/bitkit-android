package to.bitkit.data.widgets

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.env.Env
import to.bitkit.models.WidgetType
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
class NewsService @Inject constructor(
    private val client: HttpClient,
) : WidgetService<List<ArticleDTO>> {


    override val widgetType = WidgetType.NEWS
    override val refreshInterval = 10.minutes

    override suspend fun fetchData(): Result<List<ArticleDTO>> = runCatching {
        get<List<ArticleDTO>>(Env.newsBaseUrl + "/articles").take(10)
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch news", context = TAG)
    }

    // Future services can be added here
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

    companion object {
        private const val TAG = "NewsService"
    }
}

/**
 * News-specific error types
 */
sealed class NewsError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : NewsError(message)
}
