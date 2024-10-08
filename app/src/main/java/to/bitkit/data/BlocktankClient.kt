package to.bitkit.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.http.parametersOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.models.blocktank.Bt0ConfMinTxFeeWindow
import to.bitkit.models.blocktank.BtEstimateFeeResponse
import to.bitkit.models.blocktank.BtInfo
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.models.blocktank.CJitEntry
import to.bitkit.models.blocktank.CreateCjitOptions
import to.bitkit.models.blocktank.CreateCjitRequest
import to.bitkit.models.blocktank.CreateOrderOptions
import to.bitkit.models.blocktank.CreateOrderRequest
import to.bitkit.shared.AppError
import javax.inject.Inject
import javax.inject.Singleton

private typealias IgnoreResponse = String

@Singleton
class BlocktankClient @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun getInfo(): BtInfo {
        return get<BtInfo>("${Env.blocktankClientServer}/info")
    }

    // region orders
    suspend fun createOrder(lspBalanceSat: Int, channelExpiryWeeks: Int, options: CreateOrderOptions): BtOrder {
        val payload = CreateOrderRequest(
            lspBalanceSat,
            channelExpiryWeeks,
        ).also {
            it.withOptions(options)
        }
        return post<BtOrder>("${Env.blocktankClientServer}/channels", payload)
    }

    suspend fun getOrder(orderId: String): BtOrder {
        return get<BtOrder>("${Env.blocktankClientServer}/channels/$orderId")
    }

    suspend fun getOrders(orderIds: List<String>): List<BtOrder> {
        return get<List<BtOrder>>("${Env.blocktankClientServer}/channels", mapOf("ids" to orderIds))
    }
    // endregion

    // region channels
    suspend fun openChannel(orderId: String, nodeId: String) {
        post<IgnoreResponse>(
            "${Env.blocktankClientServer}/channels/$orderId/open", mapOf(
                "connectionStringOrPubkey" to nodeId,
                "announceChannel" to false,
            )
        )
    }
    // endregion

    // region fees
    suspend fun estimateOrderFee(
        lspBalanceSat: Int,
        channelExpiryWeeks: Int,
        options: CreateOrderOptions,
    ): BtEstimateFeeResponse {
        val payload = CreateOrderRequest(
            lspBalanceSat,
            channelExpiryWeeks,
        ).also {
            it.withOptions(options)
        }
        return post<BtEstimateFeeResponse>("${Env.blocktankClientServer}/channels/estimate-fee", payload)
    }

    suspend fun getMin0ConfTxFee(orderId: String): Bt0ConfMinTxFeeWindow {
        return get<Bt0ConfMinTxFeeWindow>("${Env.blocktankClientServer}/channels/$orderId/min-0conf-tx-fee")
    }
    // endregion

    // region cjit
    suspend fun createCJitEntry(
        channelSizeSat: Int,
        invoiceSat: Int,
        invoiceDescription: String,
        nodeId: String,
        channelExpiryWeeks: Int,
        options: CreateCjitOptions,
    ): CJitEntry {
        val payload = CreateCjitRequest(
            channelSizeSat,
            invoiceSat,
            invoiceDescription,
            nodeId,
            channelExpiryWeeks,
        ).also {
            it.withOptions(options)
        }
        return post<CJitEntry>("${Env.blocktankClientServer}/cjit", payload)
    }
    // endregion

    // region notifications
    suspend fun registerDevice(payload: RegisterDeviceRequest) {
        post<IgnoreResponse>("${Env.blocktankPushNotificationServer}/device", payload)
    }

    suspend fun testNotification(deviceToken: String, payload: TestNotificationRequest) {
        post<IgnoreResponse>("${Env.blocktankPushNotificationServer}/device/$deviceToken/test-notification", payload)
    }
    // endregion

    private suspend inline fun <reified T> post(url: String, payload: Any? = null): T {
        val response = client.post(url) { payload?.let { setBody(it) } }
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<T>() }.getOrElse { throw BlocktankError.InvalidResponse }
                Log.d(APP, "Http call: $response; response: $responseBody")
                responseBody
            }

            else -> throw BlocktankError.InvalidResponse
        }
    }

    private suspend inline fun <reified T> get(url: String, queryParams: Map<String, List<String>>? = null): T {
        val response: HttpResponse = client.get(url) { queryParams?.let { parametersOf(it) } }
        return when (response.status.isSuccess()) {
            true -> {
                val responseBody = runCatching { response.body<T>() }.getOrElse { throw BlocktankError.InvalidResponse }
                Log.d(APP, "Http call: $response; response: $responseBody")
                responseBody
            }

            else -> throw BlocktankError.InvalidResponse
        }
    }
}

sealed class BlocktankError(message: String) : AppError(message) {
    data object InvalidResponse : BlocktankError("Invalid response.")
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
