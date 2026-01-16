package to.bitkit.services

import kotlinx.coroutines.delay
import to.bitkit.async.ServiceQueue
import to.bitkit.data.BlocktankHttpClient
import to.bitkit.models.FxRate
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class CurrencyService @Inject constructor(
    private val blocktankHttpClient: BlocktankHttpClient,
) {
    private val maxRetries = 3

    suspend fun fetchLatestRates(): List<FxRate> {
        var lastError: Throwable? = null

        for (attempt in 0 until maxRetries) {
            runCatching {
                val response = ServiceQueue.FOREX.background { blocktankHttpClient.fetchLatestRates() }
                val rates = response.tickers
                return rates
            }.onFailure {
                lastError = it
                if (attempt < maxRetries - 1) {
                    // Wait a bit before retrying, with exponential backoff
                    val waitTime = 2.0.pow(attempt.toDouble()).toLong() * 1000L
                    delay(waitTime)
                }
            }
        }

        throw lastError ?: CurrencyError.Unknown()
    }
}

sealed class CurrencyError(message: String) : AppError(message) {
    class Unknown : CurrencyError("Unknown error occurred while fetching rates")
}
