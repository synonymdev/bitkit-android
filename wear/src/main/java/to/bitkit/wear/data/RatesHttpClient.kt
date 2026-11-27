package to.bitkit.wear.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import to.bitkit.wear.env.Env
import to.bitkit.wear.models.FxRateResponse
import to.bitkit.wear.utils.HttpError
import javax.inject.Inject
import javax.inject.Singleton

/** Blocktank's bitcoin exchange rates, the source the phone uses. */
@Singleton
class RatesHttpClient @Inject constructor(
    private val client: HttpClient,
) {
    suspend fun fetchLatestRates(): FxRateResponse {
        val response = client.get(Env.btcRatesServer)
        if (!response.status.isSuccess()) {
            throw HttpError("Failed to fetch rates: '${response.status.description}'", response.status.value)
        }
        return response.body()
    }
}
