package to.bitkit.models
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

@Serializable
data class FxRateResponse(
    val tickers: List<FxRate>
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

enum class BitcoinDisplayUnit {
    MODERN, CLASSIC
}

data class ConvertedAmount(
    val value: BigDecimal,
    val formatted: String,
    val symbol: String,
    val currency: String,
    val flag: String,
    val sats: Long
) {
    val btcValue: BigDecimal = BigDecimal(sats).divide(BigDecimal(100_000_000))

    data class BitcoinDisplayComponents(
        val symbol: String,
        val value: String
    )

    fun bitcoinDisplay(unit: BitcoinDisplayUnit): BitcoinDisplayComponents {
        val symbol = "₿"
        val spaceSeparator = ' '
        val formattedValue = when (unit) {
            BitcoinDisplayUnit.MODERN -> {
                val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
                    groupingSeparator = spaceSeparator
                }
                val formatter = DecimalFormat("#,###", formatSymbols).apply {
                    isGroupingUsed = true
                }
                formatter.format(sats)
            }
            BitcoinDisplayUnit.CLASSIC -> {
                val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
                    groupingSeparator = spaceSeparator
                }
                val formatter = DecimalFormat("#,###.########", formatSymbols)
                formatter.format(btcValue)
            }
        }
        return BitcoinDisplayComponents(symbol, formattedValue)
    }}
