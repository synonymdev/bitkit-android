package to.bitkit.ui.utils.visualTransformation

import okhttp3.internal.toLongOrDefault
import to.bitkit.ext.removeSpaces
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.models.asBtc
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.utils.formatCurrency
import to.bitkit.viewmodels.CurrencyViewModel
import java.math.BigDecimal
import java.math.RoundingMode

object CalculatorFormatter {

    fun convertBtcToFiat(
        btcValue: String,
        displayUnit: BitcoinDisplayUnit,
        currencyViewModel: CurrencyViewModel
    ): String? {
        val satsOrBtc = btcValue.removeSpaces()
        val satsLong = when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> {
                satsOrBtc.toLongOrDefault(0L)
            }

            BitcoinDisplayUnit.CLASSIC -> {
                val btcDecimal = BigDecimal.valueOf(satsOrBtc.toDoubleOrNull() ?: 0.0)
                val satsDecimal = btcDecimal.multiply(BigDecimal(SATS_IN_BTC))
                val roundedNumber = satsDecimal.setScale(0, RoundingMode.HALF_UP)
                roundedNumber.toLong()
            }
        }

        val fiat = currencyViewModel.convert(sats = satsLong)
        return fiat?.formatted
    }

    fun convertFiatToBtc(
        fiatValue: String,
        displayUnit: BitcoinDisplayUnit,
        currencyViewModel: CurrencyViewModel
    ): String {
        val satsValue = currencyViewModel.convertFiatToSats(fiatValue.toDoubleOrNull() ?: 0.0)

        return when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> {
                satsValue.formatToModernDisplay()
            }

            BitcoinDisplayUnit.CLASSIC -> {
                satsValue.asBtc()
                    .formatCurrency(decimalPlaces = 8)
                    .orEmpty()
            }
        }
    }
}
