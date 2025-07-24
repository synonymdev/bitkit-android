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
class LnurlService @Inject constructor(
    private val client: HttpClient,
) {

    suspend fun fetchWithdrawInfo(callbackUrl: String): Result<LnurlWithdrawResponse> = runCatching {
        Logger.debug("Fetching LNURL withdraw info from: $callbackUrl")

        val response: HttpResponse = client.get(callbackUrl)
        Logger.debug("Http call: $response")

        if (!response.status.isSuccess()) {
            throw Exception("HTTP error: ${response.status}")
        }

        val withdrawResponse = response.body<LnurlWithdrawResponse>()

        when {
            withdrawResponse.status == "ERROR" -> {
                throw Exception("LNURL error: ${withdrawResponse.reason}")
            }

            else -> withdrawResponse
        }
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch withdraw info", context = TAG)
    }

    suspend fun fetchLnurlInvoice(
        callbackUrl: String,
        amountSats: ULong,
        comment: String? = null,
    ): Result<LnurlPayResponse> = runCatching {
        Logger.debug("Fetching LNURL pay invoice info from: $callbackUrl")

        val response = client.get(callbackUrl) {
            url {
                parameters["amount"] = "${amountSats * 1000u}" // convert to msat
                comment?.takeIf { it.isNotBlank() }?.let {
                    parameters["comment"] = it
                }
            }
        }
        Logger.debug("Http call: $response")

        if (!response.status.isSuccess()) {
            throw Exception("HTTP error: ${response.status}")
        }

        return@runCatching response.body<LnurlPayResponse>()
    }

    suspend fun fetchLnurlChannelInfo(url: String): Result<LnurlChannelInfoResponse> = runCatching {
        Logger.debug("Fetching LNURL channel info from: $url")

        val response: HttpResponse = client.get(url)
        Logger.debug("Http call: $response")

        if (!response.status.isSuccess()) {
            throw Exception("HTTP error: ${response.status}")
        }

        return@runCatching response.body<LnurlChannelInfoResponse>()
    }.onFailure {
        Logger.warn(msg = "Failed to fetch channel info", e = it, context = TAG)
    }

    suspend fun handleLnurlChannel(
        k1: String,
        callback: String,
        nodeId: String,
    ): Result<LnurlChannelResponse> = runCatching {
        Logger.debug("Handling LNURL channel request to: $callback")

        val response = client.get(callback) {
            url {
                parameters["k1"] = k1
                parameters["remoteid"] = nodeId
                parameters["private"] = "1" // Private channel
            }
        }
        Logger.debug("Http call: $response")

        if (!response.status.isSuccess()) throw Exception("HTTP error: ${response.status}")

        val parsedResponse = response.body<LnurlChannelResponse>()

        when {
            parsedResponse.status == "ERROR" -> {
                throw Exception("LNURL channel error: ${parsedResponse.reason}")
            }

            else -> parsedResponse
        }
    }.onFailure {
        Logger.warn(msg = "Failed to handle LNURL channel", e = it, context = TAG)
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

@Serializable
data class LnurlChannelInfoResponse(
    val uri: String,
    val tag: String,
    val callback: String,
    val k1: String,
)
