package to.bitkit.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import to.bitkit.utils.AppError
import to.bitkit.utils.HttpError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LnurlService @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun requestLnurlWithdraw(callbackUrl: String): Result<LnurlWithdrawResponse> = runCatching {
        Logger.debug("Requesting LNURL withdraw via: '$callbackUrl'", context = TAG)

        val response: HttpResponse = client.get(callbackUrl)
        Logger.debug("Http call: $response", context = TAG)

        if (!response.status.isSuccess()) {
            throw HttpError("requestLnurlWithdraw error: '${response.status.description}'", response.status.value)
        }

        val withdrawResponse = response.body<LnurlWithdrawResponse>()

        when {
            withdrawResponse.status == "ERROR" -> {
                throw AppError("requestLnurlWithdraw error: ${withdrawResponse.reason}")
            }

            else -> withdrawResponse
        }
    }.onFailure {
        Logger.warn("Failed to request LNURL withdraw", it, context = TAG)
    }

    suspend fun fetchLnurlInvoice(
        callbackUrl: String,
        amountSats: ULong,
        comment: String? = null,
    ): Result<LnurlPayResponse> = runCatching {
        Logger.debug("Fetching LNURL pay invoice from: $callbackUrl", context = TAG)

        val response = client.get(callbackUrl) {
            url {
                parameters["amount"] = "${amountSats * 1000u}" // convert to msat
                comment?.takeIf { it.isNotBlank() }?.let {
                    parameters["comment"] = it
                }
            }
        }
        Logger.debug("Http call: $response", context = TAG)

        if (!response.status.isSuccess()) {
            throw HttpError("fetchLnurlInvoice error: '${response.status.description}'", response.status.value)
        }

        return@runCatching response.body<LnurlPayResponse>()
    }

    suspend fun requestLnurlChannel(url: String): Result<LnurlChannelResponse> = runCatching {
        Logger.debug("Requesting LNURL channel request via: '$url'", context = TAG)

        val response: HttpResponse = client.get(url)
        Logger.debug("Http call: $response", context = TAG)

        if (!response.status.isSuccess()) {
            throw HttpError("requestLnurlChannel error: '${response.status.description}'", response.status.value)
        }

        val parsedResponse = response.body<LnurlChannelResponse>()

        when (parsedResponse.status == "ERROR") {
            true -> throw HttpError("requestLnurlChannel error: '${parsedResponse.reason}'", response.status.value)
            else -> parsedResponse
        }
    }.onFailure {
        Logger.warn("Failed to request LNURL channel", it, context = TAG)
    }

    companion object {
        private const val TAG = "LnurlService"
    }
}

@Serializable
data class LnurlWithdrawResponse(
    val status: String? = null,
    val reason: String? = null,
    val tag: String? = null,
    val callback: String? = null,
    val k1: String? = null,
    val defaultDescription: String? = null,
    val minWithdrawable: Long? = null,
    val maxWithdrawable: Long? = null,
    val balanceCheck: String? = null,
)

@Serializable
data class LnurlPayResponse(
    val pr: String,
    val routes: List<String>,
)

@Serializable
data class LnurlChannelResponse(
    val status: String? = null,
    val reason: String? = null,
)
