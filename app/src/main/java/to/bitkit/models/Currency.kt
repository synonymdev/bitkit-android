package to.bitkit.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

const val BITCOIN_SYMBOL = "₿"
const val SATS_IN_BTC = 100_000_000
const val BTC_PLACEHOLDER = "0.00000000"
const val SATS_PLACEHOLDER = "0"

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
    val rate: Double
        get() = lastPrice.toDoubleOrNull() ?: 0.0

    val timestamp: Instant
        get() = Instant.fromEpochMilliseconds(lastUpdatedAt)
}

/** aka. Unit */
enum class PrimaryDisplay {
    BITCOIN, FIAT
}

/** aka. Denomination */
enum class BitcoinDisplayUnit {
    MODERN, CLASSIC
}

data class ConvertedAmount(
    val value: BigDecimal,
    val formatted: String,
    val symbol: String,
    val currency: String,
    val flag: String,
    val sats: Long,
) {
    val btcValue: BigDecimal = BigDecimal(sats).divide(BigDecimal(SATS_IN_BTC))

    data class BitcoinDisplayComponents(
        val symbol: String,
        val value: String,
    )

    fun bitcoinDisplay(unit: BitcoinDisplayUnit): BitcoinDisplayComponents {
        val symbol = BITCOIN_SYMBOL
        val spaceSeparator = ' '
        val formattedValue = when (unit) {
            BitcoinDisplayUnit.MODERN -> {
                sats.formatToModernDisplay()
            }

            BitcoinDisplayUnit.CLASSIC -> {
                val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
                    groupingSeparator = spaceSeparator
                }
                val formatter = DecimalFormat("#,###.########", formatSymbols)
                formatter.format(btcValue)
            }
        }
        return BitcoinDisplayComponents(
            symbol = symbol,
            value = formattedValue,
        )
    }
}

fun Long.formatToModernDisplay(): String {
    val sats = this
    val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
        groupingSeparator = ' '
    }
    val formatter = DecimalFormat("#,###", formatSymbols).apply {
        isGroupingUsed = true
    }
    return formatter.format(sats)
}

fun ULong.formatToModernDisplay(): String = this.toLong().formatToModernDisplay()
