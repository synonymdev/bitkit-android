package to.bitkit.services

import kotlinx.coroutines.delay
import to.bitkit.async.ServiceQueue
import to.bitkit.data.BlocktankHttpClient
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.FxRate
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.ui.utils.formatCurrency
import to.bitkit.utils.AppError
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.roundToLong

@Singleton
class CurrencyService @Inject constructor(
    private val blocktankHttpClient: BlocktankHttpClient,
) {
    private var cachedRates: List<FxRate>? = null

    private val maxRetries = 3

    suspend fun fetchLatestRates(): List<FxRate> {
        var lastError: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                val response = ServiceQueue.FOREX.background { blocktankHttpClient.fetchLatestRates() }
                val rates = response.tickers

                // TODO Cache to disk
                cachedRates = rates

                return rates
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    // Wait a bit before retrying, with exponential backoff
                    val waitTime = 2.0.pow(attempt.toDouble()).toLong() * 1000L
                    delay(waitTime)
                }
            }
        }

        throw lastError ?: CurrencyError.Unknown
    }

    fun loadCachedRates(): List<FxRate>? {
        // TODO load from disk
        return cachedRates
    }

    fun convert(sats: Long, rate: FxRate): ConvertedAmount? {
        val btcAmount = BigDecimal(sats).divide(BigDecimal(SATS_IN_BTC))
        val value: BigDecimal = btcAmount.multiply(BigDecimal.valueOf(rate.rate))

        val formatted = value.formatCurrency() ?: return null

        return ConvertedAmount(
            value = value,
            formatted = formatted,
            symbol = rate.currencySymbol,
            currency = rate.quote,
            flag = rate.currencyFlag,
            sats = sats,
        )
    }

    suspend fun convertSatsToFiat(satsAmount: Long, currency: String): Double {
        val rates = cachedRates ?: fetchLatestRates()
        val rate = getCurrentRate(currency, rates) ?: return 0.0

        return convert(satsAmount.toLong(), rate)?.value?.toDouble() ?: 0.0
    }

    fun convertFiatToSats(fiatValue: BigDecimal, rate: FxRate): ULong {
        val btcAmount = fiatValue.divide(BigDecimal.valueOf(rate.rate), 8, RoundingMode.HALF_UP)
        val satsDecimal = btcAmount.multiply(BigDecimal(SATS_IN_BTC))

        val roundedNumber = satsDecimal.setScale(0, RoundingMode.HALF_UP)

        return roundedNumber.toLong().toULong()
    }

    fun convertFiatToSats(fiatAmount: Double, currency: String, rates: List<FxRate>): Long {
        val rate = getCurrentRate(currency, rates) ?: return 0

        // Convert the fiat amount to BTC, then to sats
        val btc = fiatAmount / rate.rate
        val sats = (btc * SATS_IN_BTC).roundToLong()

        return sats
    }

    suspend fun convertFiatToSats(fiatAmount: Double, currency: String): Long {
        val rates = cachedRates ?: fetchLatestRates()
        return convertFiatToSats(fiatAmount, currency, rates)
    }

    fun getCurrentRate(currency: String, rates: List<FxRate>): FxRate? {
        return rates.firstOrNull { it.quote == currency }
    }
}

sealed class CurrencyError(message: String) : AppError(message) {
    data object Unknown : CurrencyError("Unknown error occurred while fetching rates")
}
