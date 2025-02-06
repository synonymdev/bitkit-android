package to.bitkit.data

import android.util.Log
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
import to.bitkit.env.Tag.APP
import to.bitkit.models.FxRateResponse
import to.bitkit.models.blocktank.RegtestCloseChannelRequest
import to.bitkit.models.blocktank.RegtestDepositRequest
import to.bitkit.models.blocktank.RegtestMineRequest
import to.bitkit.models.blocktank.RegtestPayRequest
import to.bitkit.utils.AppError
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

    // region regtest
    /**
     * Mines a number of blocks on the regtest network.
     * @param count Number of blocks to mine. Default is 1.
     */
    suspend fun regtestMine(count: Int = 1) {
         val payload = RegtestMineRequest(count)
        post<IgnoreResponse>("${Env.blocktankClientServer}/regtest/chain/mine", payload)
    }

    /**
     * Deposits a number of satoshis to an address on the regtest network.
     * @param address Address to deposit to.
     * @param amountSat Amount of satoshis to deposit. Default is 10,000,000.
     * @return Onchain transaction ID.
     */
    suspend fun regtestDeposit(address: String, amountSat: Int = 10_000_000): String {
        val payload = RegtestDepositRequest(address = address, amountSat = amountSat)
        return post("${Env.blocktankClientServer}/regtest/chain/deposit", payload)
    }

    /**
     * Pays an invoice on the regtest network.
     * @param invoice Invoice to pay.
     * @param amountSat Amount of satoshis to pay (only for 0-amount invoices).
     * @return Blocktank payment ID.
     */
    suspend fun regtestPay(invoice: String, amountSat: Int? = null): String {
        val payload = RegtestPayRequest(invoice = invoice, amountSat = amountSat)
        return post("${Env.blocktankClientServer}/regtest/channel/pay", payload)
    }

    /**
     * Closes a channel on the regtest network.
     * @param fundingTxId Funding transaction ID.
     * @param vout Funding transaction output index.
     * @param forceCloseAfterS Time in seconds to force-close the channel after. Default is 24 hours (86400). Set it to 0 for immediate force close.
     * @return Closing transaction ID.
     */
    suspend fun regtestCloseChannel(fundingTxId: String, vout: Int, forceCloseAfterS: Int = 86400): String {
        val payload = RegtestCloseChannelRequest(
            fundingTxId = fundingTxId,
            vout = vout,
            forceCloseAfterS = forceCloseAfterS,
        )
        return post("${Env.blocktankClientServer}/regtest/channel/close", payload)
    }
    // endregion

    // region utils
    private suspend inline fun <reified T> post(url: String, payload: Any? = null): T {
        val response = client.post(url) { payload?.let { setBody(it) } }
        Log.d(APP, "Http call: $response")
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
        Log.d(APP, "Http call: $response")
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
