package to.bitkit.services

import kotlinx.coroutines.delay
import to.bitkit.async.NetworkException
import to.bitkit.async.ServiceQueue
import to.bitkit.data.BlocktankHttpClient
import to.bitkit.models.FxRate
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class CurrencyService @Inject constructor(
    private val blocktankHttpClient: BlocktankHttpClient,
) {
    private val maxRetries = 3

    suspend fun fetchLatestRates(): List<FxRate> {
        var lastError: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                val response = ServiceQueue.FOREX.background { blocktankHttpClient.fetchLatestRates() }
                val rates = response.tickers
                return rates
            } catch (e: NetworkException) {
                Logger.warn(
                    "Network error fetching rates (attempt ${attempt + 1}/$maxRetries): ${e.message}",
                    context = TAG
                )
                lastError = e

                // Don't retry network errors on last attempt, or if it's a DNS resolution issue
                if (attempt == maxRetries - 1 || e.message?.contains("No address associated with hostname") == true) {
                    break
                }

                // Wait before retrying, with exponential backoff
                val waitTime = 2.0.pow(attempt.toDouble()).toLong() * 1000L
                Logger.debug("Retrying in ${waitTime}ms...", context = TAG)
                delay(waitTime)
            } catch (e: Exception) {
                Logger.error("Unexpected error fetching rates (attempt ${attempt + 1}/$maxRetries)", e, context = TAG)
                lastError = e

                if (attempt < maxRetries - 1) {
                    // Wait before retrying, with exponential backoff
                    val waitTime = 2.0.pow(attempt.toDouble()).toLong() * 1000L
                    delay(waitTime)
                }
            }
        }

        when (lastError) {
            is NetworkException -> throw CurrencyError.NetworkUnavailable(lastError.message ?: "Network unavailable")
            else -> throw lastError ?: CurrencyError.Unknown
        }
    }

    private companion object {
        const val TAG = "CurrencyService"
    }
}

sealed class CurrencyError(message: String) : AppError(message) {
    data object Unknown : CurrencyError("Unknown error occurred while fetching rates")
    data class NetworkUnavailable(val details: String) : CurrencyError("Network unavailable: $details")
}
