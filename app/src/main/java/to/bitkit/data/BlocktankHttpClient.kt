package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import to.bitkit.env.Env
import to.bitkit.models.FxRateResponse
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocktankHttpClient @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun fetchLatestRates(): FxRateResponse {
        val response = client.get(Env.btcRatesServer)
        Logger.debug("Http call: $response")

        return when (response.status.isSuccess()) {
            true -> response.body()
            else -> throw Exception("Http error: ${response.status}")
        }
    }
}
