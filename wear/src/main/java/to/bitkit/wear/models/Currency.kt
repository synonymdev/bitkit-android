// Formatting copied from app/src/main/java/to/bitkit/models/Currency.kt.
// Keep both in sync until a shared module exists.
package to.bitkit.wear.models

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

const val BITCOIN_SYMBOL = "₿"
const val SATS_IN_BTC = 100_000_000
const val BTC_SCALE = 8
const val SATS_GROUPING_SEPARATOR = ' '
const val FIAT_GROUPING_SEPARATOR = ','
const val DECIMAL_SEPARATOR = '.'
const val FIAT_DECIMALS = 2
const val USD = "USD"

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
}

/** Which amount the watch shows first. */
enum class PrimaryDisplay {
    BITCOIN, FIAT;

    operator fun not() = when (this) {
        BITCOIN -> FIAT
        FIAT -> BITCOIN
    }
}

/** [sats] in the currency of [rate], or null when the rate is unusable. */
fun fiatValue(sats: Long, rate: FxRate): BigDecimal? {
    if (rate.rate <= 0.0) return null
    return BigDecimal.valueOf(sats)
        .divide(BigDecimal.valueOf(SATS_IN_BTC.toLong()), BTC_SCALE, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(rate.rate))
}

fun Long.formatToModernDisplay(locale: Locale = Locale.getDefault()): String {
    val symbols = DecimalFormatSymbols(locale).apply {
        groupingSeparator = SATS_GROUPING_SEPARATOR
    }
    val formatter = DecimalFormat("#,###", symbols).apply {
        isGroupingUsed = true
    }
    return formatter.format(this)
}

fun BigDecimal.formatCurrencyWithSymbol(
    currencySymbol: String,
    locale: Locale = Locale.getDefault(),
): String {
    val symbols = DecimalFormatSymbols(locale).apply {
        decimalSeparator = DECIMAL_SEPARATOR
        groupingSeparator = FIAT_GROUPING_SEPARATOR
    }
    val formatter = DecimalFormat("#,##0.${"0".repeat(FIAT_DECIMALS)}", symbols).apply {
        minimumFractionDigits = FIAT_DECIMALS
        maximumFractionDigits = FIAT_DECIMALS
        roundingMode = RoundingMode.HALF_EVEN
        isGroupingUsed = true
    }
    return "$currencySymbol${formatter.format(this)}"
}
