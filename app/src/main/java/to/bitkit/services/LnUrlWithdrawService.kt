package to.bitkit.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LnUrlWithdrawService @Inject constructor(
    private val client: HttpClient,
) {

    suspend fun fetchWithdrawInfo(lnUrlCallBack: String): Result<LnUrlWithdrawResponse> = runCatching {
        val response: HttpResponse = client.get(lnUrlCallBack)

        if (!response.status.isSuccess()) {
            throw Exception("HTTP error: ${response.status}")
        }

        val withdrawResponse = response.body<LnUrlWithdrawResponse>()

        when {
            withdrawResponse.status == "ERROR" -> {
                throw Exception("LNURL error: ${withdrawResponse.reason}")
            }
            else -> withdrawResponse
        }
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch withdraw info", context = TAG)
    }


    companion object {
        private const val TAG = "LnUrlWithdrawService"
    }
}

@Serializable
data class LnUrlWithdrawResponse(
    val status: String? = null,
    val reason: String? = null,
    val tag: String? = null,
    val callback: String? = null,
    val k1: String? = null,
    val defaultDescription: String? = null,
    val minWithdrawable: Long? = null,
    val maxWithdrawable: Long? = null,
    val balanceCheck: String? = null
)
