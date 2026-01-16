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
import to.bitkit.ext.nowMs
import to.bitkit.models.USD
import to.bitkit.models.WidgetType
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Singleton
class WeatherService @Inject constructor(
    private val client: HttpClient,
    private val currencyRepo: CurrencyRepo,
    private val clock: Clock,
) : WidgetService<WeatherDTO> {

    override val widgetType = WidgetType.WEATHER
    override val refreshInterval = 8.minutes

    @Volatile
    private var cachedFeeEstimates: FeeEstimates? = null

    @Volatile
    private var feeEstimatesTimestamp: Long = 0L

    @Volatile
    private var cachedHistoricalData: List<BlockFeeRates>? = null

    @Volatile
    private var historicalDataTimestamp: Long = 0L

    companion object {
        private const val TAG = "WeatherService"

        private const val AVERAGE_SEGWIT_VB_SIZE = 140
        private const val USD_GOOD_THRESHOLD = 1.0 // $1 USD threshold for good condition
        private const val PERCENTILE_LOW = 0.33
        private const val PERCENTILE_HIGH = 0.66
        private val TTL_FEE_ESTIMATES = 2.minutes
        private val TTL_HISTORICAL_DATA = 30.minutes
    }

    private fun isCacheValid(timestamp: Long, ttl: Duration) = clock.nowMs() - timestamp < ttl.inWholeMilliseconds

    override suspend fun fetchData(): Result<WeatherDTO> = runCatching {
        // Fetch fee estimates and historical data in parallel
        val feeEstimates = getFeeEstimates()
        val history = getHistoricalFeeData()

        // Calculate condition based on current fees and history
        val condition = calculateCondition(feeEstimates.normal, history)

        // Calculate average fee for display
        val avgFeeSats = (feeEstimates.normal * AVERAGE_SEGWIT_VB_SIZE).toLong()
        val currentFee = formatFeeForDisplay(avgFeeSats)

        WeatherDTO(
            condition = condition,
            currentFee = currentFee,
            nextBlockFee = feeEstimates.fast,
        )
    }.onFailure {
        Logger.warn("Failed to fetch weather data", it, context = TAG)
    }

    private suspend fun getFeeEstimates(): FeeEstimates {
        cachedFeeEstimates?.takeIf { isCacheValid(feeEstimatesTimestamp, TTL_FEE_ESTIMATES) }?.let { return it }
        val response: HttpResponse = client.get("${Env.mempoolBaseUrl}/v1/fees/recommended")
        return when (response.status.isSuccess()) {
            true -> response.body<FeeEstimates>().also {
                cachedFeeEstimates = it
                feeEstimatesTimestamp = clock.nowMs()
            }

            else -> throw WeatherError.InvalidResponse("Failed to fetch fee estimates: ${response.status.description}")
        }
    }

    private suspend fun getHistoricalFeeData(): List<BlockFeeRates> {
        cachedHistoricalData?.takeIf { isCacheValid(historicalDataTimestamp, TTL_HISTORICAL_DATA) }?.let { return it }
        val response: HttpResponse = client.get("${Env.mempoolBaseUrl}/v1/mining/blocks/fee-rates/3m")
        return when (response.status.isSuccess()) {
            true -> response.body<List<BlockFeeRates>>().also {
                cachedHistoricalData = it
                historicalDataTimestamp = clock.nowMs()
            }

            else -> throw WeatherError.InvalidResponse(
                "Failed to fetch historical fee data: ${response.status.description}"
            )
        }
    }

    private fun calculateCondition(
        currentFeeRate: Double,
        history: List<BlockFeeRates>,
    ): FeeCondition {
        if (history.isEmpty()) return FeeCondition.AVERAGE

        // Extract median fees from historical data and sort
        val historicalFees = history.map { it.avgFee50 }.sorted()

        // Calculate percentile thresholds
        val lowThreshold = historicalFees[floor(historicalFees.size * PERCENTILE_LOW).toInt()]
        val highThreshold = historicalFees[floor(historicalFees.size * PERCENTILE_HIGH).toInt()]

        // Check USD threshold first
        val avgFeeSats = currentFeeRate * AVERAGE_SEGWIT_VB_SIZE
        val avgFeeUsd = currencyRepo.convertSatsToFiat(avgFeeSats.toLong(), USD).getOrNull()
            ?: return FeeCondition.AVERAGE

        if (avgFeeUsd.value <= BigDecimal(USD_GOOD_THRESHOLD)) return FeeCondition.GOOD

        // Determine condition based on percentiles
        return when {
            currentFeeRate <= lowThreshold -> FeeCondition.GOOD
            currentFeeRate >= highThreshold -> FeeCondition.POOR
            else -> FeeCondition.AVERAGE
        }
    }

    private fun formatFeeForDisplay(sats: Long): String {
        val usdValue = currencyRepo.convertSatsToFiat(sats, USD).getOrNull()
        return usdValue?.formatted.orEmpty()
    }
}

sealed class WeatherError(message: String) : AppError(message) {
    class InvalidResponse(override val message: String) : WeatherError(message)
}
