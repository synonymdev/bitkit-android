package to.bitkit.models

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

const val STUB_RATE = 115_150.0
const val BITCOIN_SYMBOL = "₿"
const val USD_SYMBOL = "$"
const val SATS_IN_BTC = 100_000_000
const val BTC_SCALE = 8
const val SATS_GROUPING_SEPARATOR = ' '
const val FIAT_GROUPING_SEPARATOR = ','
const val DECIMAL_SEPARATOR = '.'
const val CLASSIC_DECIMALS = 8
const val FIAT_DECIMALS = 2
const val EUR_CURRENCY = "EUR"

@Serializable
data class FxRateResponse(
    val tickers: List<FxRate>,
)

@Serializable
data class FxRate(
    val symbol: String,
    val lastPrice: String,
    val base: String,
    val baseName: String,
    val quote: String,
    val quoteName: String,
    val currencySymbol: String,
    val currencyFlag: String,
    val lastUpdatedAt: Long,
) {
    val rate: Double get() = lastPrice.toDoubleOrNull() ?: 0.0

    @OptIn(ExperimentalTime::class)
    val timestamp: Instant get() = Instant.fromEpochMilliseconds(lastUpdatedAt)
}

/** aka. Unit */
enum class PrimaryDisplay {
    BITCOIN, FIAT;

    operator fun not() = when (this) {
        BITCOIN -> FIAT
        FIAT -> BITCOIN
    }
}

/** aka. Denomination */
enum class BitcoinDisplayUnit {
    MODERN, CLASSIC;

    operator fun not() = when (this) {
        MODERN -> CLASSIC
        CLASSIC -> MODERN
    }

    fun isModern() = this == MODERN
}

data class ConvertedAmount(
    val value: BigDecimal,
    val formatted: String,
    val symbol: String,
    val currency: String,
    val flag: String,
    val sats: Long,
    val locale: Locale = Locale.getDefault(),
) {
    data class BitcoinDisplayComponents(
        val symbol: String,
        val value: String,
    )

    fun bitcoinDisplay(unit: BitcoinDisplayUnit): BitcoinDisplayComponents {
        val formattedValue = when (unit) {
            BitcoinDisplayUnit.MODERN -> sats.formatToModernDisplay(locale)
            BitcoinDisplayUnit.CLASSIC -> sats.formatToClassicDisplay(locale)
        }
        return BitcoinDisplayComponents(
            symbol = BITCOIN_SYMBOL,
            value = formattedValue,
        )
    }
}

fun Long.formatToModernDisplay(locale: Locale = Locale.getDefault()): String {
    val sats = this
    val symbols = DecimalFormatSymbols(locale).apply {
        groupingSeparator = SATS_GROUPING_SEPARATOR
    }
    val formatter = DecimalFormat("#,###", symbols).apply {
        isGroupingUsed = true
    }
    return formatter.format(sats)
}

fun ULong.formatToModernDisplay(locale: Locale = Locale.getDefault()): String = toLong().formatToModernDisplay(locale)

fun Long.formatToClassicDisplay(locale: Locale = Locale.getDefault()): String {
    val symbols = DecimalFormatSymbols(locale).apply {
        decimalSeparator = DECIMAL_SEPARATOR
    }
    val pattern = "0.${"0".repeat(CLASSIC_DECIMALS)}"
    val formatter = DecimalFormat(pattern, symbols).apply {
        minimumFractionDigits = CLASSIC_DECIMALS
        maximumFractionDigits = CLASSIC_DECIMALS
        isGroupingUsed = false
    }
    return formatter.format(asBtc())
}

fun BigDecimal.formatCurrency(decimalPlaces: Int = FIAT_DECIMALS, locale: Locale = Locale.getDefault()): String? {
    val symbols = DecimalFormatSymbols(locale).apply {
        decimalSeparator = DECIMAL_SEPARATOR
        groupingSeparator = FIAT_GROUPING_SEPARATOR
    }

    val decimalPlacesString = "0".repeat(decimalPlaces)
    val formatter = DecimalFormat("#,##0.$decimalPlacesString", symbols).apply {
        minimumFractionDigits = decimalPlaces
        maximumFractionDigits = decimalPlaces
        isGroupingUsed = true
    }

    return runCatching { formatter.format(this) }.getOrNull()
}

/** Represent this sat value in Bitcoin BigDecimal. */
fun Long.asBtc(): BigDecimal = BigDecimal(this).divide(BigDecimal(SATS_IN_BTC), BTC_SCALE, RoundingMode.HALF_UP)
