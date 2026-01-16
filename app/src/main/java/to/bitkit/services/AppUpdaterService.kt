package to.bitkit.services

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import to.bitkit.data.dto.ReleaseInfoDTO
import to.bitkit.env.Env
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdaterService @Inject constructor(
    private val client: HttpClient,
) {

    suspend fun getReleaseInfo(): ReleaseInfoDTO {
        val response: HttpResponse = client.get(Env.RELEASE_URL)
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching {
                    Json.decodeFromString<ReleaseInfoDTO>(response.bodyAsText())
                }.getOrElse {
                    throw AppUpdaterError.InvalidResponse(it.message.orEmpty())
                }
                responseBody
            }

            else -> throw AppUpdaterError.InvalidResponse(
                "Failed to fetch release info: ${response.status.description}"
            )
        }
    }
}

sealed class AppUpdaterError(message: String) : AppError(message) {
    class InvalidResponse(override val message: String) : AppUpdaterError(message)
}
