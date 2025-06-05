package to.bitkit.data.widgets

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import to.bitkit.data.dto.BlockFeeRates
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.FeeEstimates
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.models.WidgetType
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.time.Duration.Companion.minutes


@Singleton
class WeatherService @Inject constructor(
    private val client: HttpClient,
) : WidgetService<WeatherDTO> {

    override val widgetType = WidgetType.WEATHER
    override val refreshInterval = 8.minutes

    private companion object {
        private const val TAG = "WeatherService"
        private const val BASE_URL = "https://mempool.space/api/v1"
        private const val VBYTES_SIZE = 140 // average native segwit transaction size
        private const val USD_GOOD_THRESHOLD = 1.0 // $1 USD threshold for good condition
        private const val PERCENTILE_LOW = 0.33
        private const val PERCENTILE_HIGH = 0.66
    }

    override suspend fun fetchData(): Result<WeatherDTO> = runCatching {
        // Fetch fee estimates and historical data in parallel
        val feeEstimates = getFeeEstimates()
        val history = getHistoricalFeeData()

        // Calculate condition based on current fees and history
        val condition = calculateCondition(feeEstimates.normal, history)

        // Calculate average fee for display
        val avgFeeSats = (feeEstimates.normal * VBYTES_SIZE).toInt()
        val currentFee = formatFeeForDisplay(avgFeeSats)

        WeatherDTO(
            condition = condition,
            currentFee = currentFee,
            nextBlockFee = feeEstimates.fast
        )
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch weather data", context = TAG)
    }

    private suspend fun getFeeEstimates(): FeeEstimates {
        val response: HttpResponse = client.get("$BASE_URL/fees/recommended")
        return when (response.status.isSuccess()) {
            true -> response.body<FeeEstimates>()
            else -> throw WeatherError.InvalidResponse("Failed to fetch fee estimates: ${response.status.description}")
        }
    }

    private suspend fun getHistoricalFeeData(): List<BlockFeeRates> {
        val response: HttpResponse = client.get("$BASE_URL/mining/blocks/fee-rates/3m")
        return when (response.status.isSuccess()) {
            true -> response.body<List<BlockFeeRates>>()
            else -> throw WeatherError.InvalidResponse("Failed to fetch historical fee data: ${response.status.description}")
        }
    }

    private fun calculateCondition(
        currentFeeRate: Double,
        history: List<BlockFeeRates>
    ): FeeCondition {
        if (history.isEmpty()) {
            return FeeCondition.AVERAGE
        }

        // Extract median fees from historical data and sort
        val historicalFees = history.map { it.avgFee50 }.sorted()

        // Calculate percentile thresholds
        val lowThreshold = historicalFees[floor(historicalFees.size * PERCENTILE_LOW).toInt()]
        val highThreshold = historicalFees[floor(historicalFees.size * PERCENTILE_HIGH).toInt()]

        // Check USD threshold first
        val avgFeeSats = currentFeeRate * VBYTES_SIZE
        val avgFeeUsd = convertSatsToUsd(avgFeeSats.toInt()) // TODO This would need to be implemented

        if (avgFeeUsd <= USD_GOOD_THRESHOLD) {
            return FeeCondition.GOOD
        }

        // Determine condition based on percentiles
        return when {
            currentFeeRate <= lowThreshold -> FeeCondition.GOOD
            currentFeeRate >= highThreshold -> FeeCondition.POOR
            else -> FeeCondition.AVERAGE
        }
    }

    private fun formatFeeForDisplay(satoshis: Int): String {
        // TODO This would integrate with your existing display value utilities
        // For now, returning a simple format
        val usdValue = convertSatsToUsd(satoshis)
        return "$ ${String.format("%.2f", usdValue)}"
    }

    private fun convertSatsToUsd(satoshis: Int): Double {
        // TODO This would need to integrate with your existing currency conversion logic
        // Placeholder implementation - you'd want to use your actual exchange rate service
        // Assuming ~$50,000 per BTC for example
        val btcValue = satoshis / 100_000_000.0
        return btcValue * 50000.0 // This should use actual exchange rates
    }
}
/**
 * Weather-specific error types
 */
sealed class WeatherError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : WeatherError(message)
    data class ConversionError(override val message: String) : WeatherError(message)
}
