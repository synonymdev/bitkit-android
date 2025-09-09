package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import to.bitkit.async.NetworkException
import to.bitkit.env.Env
import to.bitkit.models.FxRateResponse
import to.bitkit.utils.Logger
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlocktankHttpClient @Inject constructor(
    private val client: HttpClient,
) {

    @Suppress("TooGenericExceptionThrown", "TooGenericExceptionCaught", "ThrowsCount")
    suspend fun fetchLatestRates(): FxRateResponse {
        return try {
            val response = client.get(Env.btcRatesServer)
            Logger.verbose("Http call: $response")

            when (response.status.isSuccess()) {
                true -> response.body()
                else -> throw Exception("Http error: ${response.status}")
            }
        } catch (e: UnknownHostException) {
            Logger.warn("DNS resolution failed for rates server: ${e.message}")
            throw NetworkException("Unable to resolve rates server: ${e.message}", e)
        } catch (e: ConnectException) {
            Logger.warn("Connection failed to rates server: ${e.message}")
            throw NetworkException("Cannot connect to rates server: ${e.message}", e)
        } catch (e: SocketTimeoutException) {
            Logger.warn("Timeout connecting to rates server: ${e.message}")
            throw NetworkException("Timeout connecting to rates server: ${e.message}", e)
        } catch (e: HttpRequestTimeoutException) {
            Logger.warn("HTTP request timeout to rates server: ${e.message}")
            throw NetworkException("Request timeout to rates server: ${e.message}", e)
        } catch (e: Exception) {
            Logger.error("Unexpected error fetching rates", e)
            throw e
        }
    }
}
