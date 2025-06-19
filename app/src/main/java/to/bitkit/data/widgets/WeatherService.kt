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
import to.bitkit.env.Env
import to.bitkit.models.WidgetType
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.services.CurrencyService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.time.Duration.Companion.minutes


@Singleton
class WeatherService @Inject constructor(
    private val client: HttpClient,
    private val currencyRepo: CurrencyRepo,
) : WidgetService<WeatherDTO> {

    override val widgetType = WidgetType.WEATHER
    override val refreshInterval = 8.minutes

    private companion object {
        private const val TAG = "WeatherService"
        private const val AVERAGE_SEGWIT_VBYTES_SIZE = 140
        private const val USD_GOOD_THRESHOLD = 1.0 // $1 USD threshold for good condition
        private const val PERCENTILE_LOW = 0.33
        private const val PERCENTILE_HIGH = 0.66
        private const val USD_CURRENCY = "USD"
    }

    override suspend fun fetchData(): Result<WeatherDTO> = runCatching {
        // Fetch fee estimates and historical data in parallel
        val feeEstimates = getFeeEstimates()
        val history = getHistoricalFeeData()

        // Calculate condition based on current fees and history
        val condition = calculateCondition(feeEstimates.normal, history)

        // Calculate average fee for display
        val avgFeeSats = (feeEstimates.normal * AVERAGE_SEGWIT_VBYTES_SIZE).toInt()
        val currentFee = formatFeeForDisplay(avgFeeSats)

        WeatherDTO(
            condition = condition,
            currentFee = currentFee,
            nextBlockFee = feeEstimates.fast
        )
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch weather data", context = TAG)
    }

    private suspend fun getFeeEstimates(): FeeEstimates { //TODO CACHE
        val response: HttpResponse = client.get("${Env.mempoolBaseUrl}/v1/fees/recommended")
        return when (response.status.isSuccess()) {
            true -> response.body<FeeEstimates>()
            else -> throw WeatherError.InvalidResponse("Failed to fetch fee estimates: ${response.status.description}")
        }
    }

    private suspend fun getHistoricalFeeData(): List<BlockFeeRates> { //TODO CACHE
        val response: HttpResponse = client.get("${Env.mempoolBaseUrl}/v1/mining/blocks/fee-rates/3m")
        return when (response.status.isSuccess()) {
            true -> response.body<List<BlockFeeRates>>()
            else -> throw WeatherError.InvalidResponse("Failed to fetch historical fee data: ${response.status.description}")
        }
    }

    private suspend fun calculateCondition(
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
        val avgFeeSats = currentFeeRate * AVERAGE_SEGWIT_VBYTES_SIZE
        val avgFeeUsd = currencyRepo.convertSatsToFiat(avgFeeSats.toLong(), currency = USD_CURRENCY).getOrNull() ?: return FeeCondition.AVERAGE

        if (avgFeeUsd.value <= BigDecimal(USD_GOOD_THRESHOLD)) {
            return FeeCondition.GOOD
        }

        // Determine condition based on percentiles
        return when {
            currentFeeRate <= lowThreshold -> FeeCondition.GOOD
            currentFeeRate >= highThreshold -> FeeCondition.POOR
            else -> FeeCondition.AVERAGE
        }
    }

    private suspend fun formatFeeForDisplay(satoshis: Int): String {
        val usdValue = currencyRepo.convertSatsToFiat(satoshis.toLong(), currency = USD_CURRENCY).getOrNull()
        return usdValue?.formatted.orEmpty()
    }
}
/**
 * Weather-specific error types
 */
sealed class WeatherError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : WeatherError(message)
    data class ConversionError(override val message: String) : WeatherError(message)
}
