package to.bitkit.data.widgets

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import to.bitkit.data.WidgetsStore
import to.bitkit.data.dto.price.CandleResponse
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceResponse
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.env.Env
import to.bitkit.models.WidgetType
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes


@Singleton
class PriceService @Inject constructor(
    private val client: HttpClient,
    private val widgetsStore: WidgetsStore,
) : WidgetService<PriceDTO> {

    override val widgetType = WidgetType.PRICE
    override val refreshInterval = 1.minutes
    private val sourceLabel = "Bitfinex.com"

    override suspend fun fetchData(): Result<PriceDTO> = runCatching {
        val period = widgetsStore.data.first().pricePreferences.period ?: GraphPeriod.ONE_DAY

        val widgets = TradingPair.entries.map { pair ->
            fetchPairData(pair = pair, period = period)
        }
        PriceDTO(widgets = widgets, source = sourceLabel)
    }.onFailure {
        Logger.warn(e = it, msg = "Failed to fetch price data", context = TAG)
    }

    suspend fun fetchAllPeriods(): Result<List<PriceDTO>> = runCatching {
        coroutineScope {
            GraphPeriod.entries.map { period ->
                async {
                    PriceDTO(
                        widgets = TradingPair.entries.map { pair ->
                            fetchPairData(pair = pair, period = period)
                        },
                        source = sourceLabel
                    )
                }
            }.awaitAll()
        }
    }.onFailure {
        Logger.warn(e = it, msg = "fetchAllPeriods: Failed to fetch price data", context = TAG)
    }


    private suspend fun fetchPairData(pair: TradingPair, period: GraphPeriod): PriceWidgetData {
        val ticker = pair.ticker

        // Fetch historical candles
        val candles = fetchCandles(ticker = ticker, period = period)
        val sortedCandles = candles.sortedBy { it.timestamp }
        val pastValues = sortedCandles.map { it.close }.toMutableList()

        // Fetch latest price and replace last candle value
        val latestPrice = fetchLatestPrice(ticker)
        if (pastValues.isNotEmpty()) {
            pastValues[pastValues.size - 1] = latestPrice
        } else {
            pastValues.add(latestPrice)
        }

        val change = calculateChange(pastValues)
        val formattedPrice = formatPrice(pair, latestPrice)

        return PriceWidgetData(
            pair = pair,
            change = change,
            price = formattedPrice,
            period = period,
            pastValues = pastValues
        )
    }

    private suspend fun fetchLatestPrice(ticker: String): Double {
        val response: HttpResponse = client.get("${Env.pricesWidgetBaseUrl}/price/$ticker/latest")
        return when (response.status.isSuccess()) {
            true -> {
                val priceResponse = runCatching { response.body<PriceResponse>() }.getOrElse {
                    throw PriceError.InvalidResponse("Failed to parse price response: ${it.message}")
                }
                priceResponse.price
            }

            else -> throw PriceError.InvalidResponse("Failed to fetch latest price: ${response.status.description}")
        }
    }

    private suspend fun fetchCandles(
        ticker: String,
        period: GraphPeriod
    ): List<CandleResponse> {
        val response: HttpResponse = client.get(
            "${Env.pricesWidgetBaseUrl}/price/$ticker/history/${period.value}"
        )
        return when (response.status.isSuccess()) {
            true -> {
                runCatching { response.body<List<CandleResponse>>() }.getOrElse {
                    throw PriceError.InvalidResponse("Failed to parse candles response: ${it.message}")
                }
            }

            else -> throw PriceError.InvalidResponse("Failed to fetch candles: ${response.status.description}")
        }
    }

    private fun calculateChange(pastValues: List<Double>): Change {
        if (pastValues.size < 2) {
            return Change(isPositive = true, formatted = "+0%")
        }

        val firstValue = pastValues.first()
        val lastValue = pastValues.last()
        val changeRatio = (lastValue / firstValue) - 1

        val sign = if (changeRatio >= 0) "+" else ""
        val percentage = changeRatio * 100

        return Change(
            isPositive = changeRatio >= 0,
            formatted = "$sign${"%.2f".format(percentage)}%"
        )
    }

    private fun formatPrice(pair: TradingPair, price: Double): String {
        return try {
            val currency = Currency.getInstance(pair.quote)
            val numberFormat = NumberFormat.getCurrencyInstance(Locale.US).apply {
                this.currency = currency
                maximumFractionDigits = when {
                    price >= 1000 -> 0
                    price >= 1 -> 2
                    else -> 6
                }
            }

            // Format and remove currency symbol, keeping only the number with formatting
            val formatted = numberFormat.format(price)
            val currencySymbol = currency.symbol
            formatted.replace(currencySymbol, "").trim()

        } catch (e: Exception) {
            Logger.warn(
                e = e,
                msg = "Error formatting price for ${pair.displayName}",
                context = TAG
            )
            String.format("%.2f", price)
        }
    }

    companion object {
        private const val TAG = "PriceService"
    }
}

/**
 * Price-specific error types
 */
sealed class PriceError(message: String) : AppError(message) {
    data class InvalidResponse(override val message: String) : PriceError(message)
    data class NetworkError(override val message: String) : PriceError(message)
}
