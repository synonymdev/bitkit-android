package to.bitkit.repositories

import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PaymentRequestTerms
import java.math.BigDecimal
import java.math.RoundingMode

internal object PaykitBitcoinRequestPricing {
    /** Decimal precision supported by both mobile platforms. */
    private const val MAX_SIGNIFICANT_DIGITS = 38

    /** Maximum length of an amount or rate before decimal parsing. */
    private const val MAX_DECIMAL_LENGTH = 80

    /** Millisatoshis per Bitcoin, expressed as a decimal scale. */
    private const val MILLISATOSHI_SCALE = 11

    data class BitcoinPayment(val amountSats: ULong, val endpointIdentifiers: List<String>)

    @Suppress("ReturnCount")
    fun bitcoinPayment(terms: PaymentRequestTerms, endpoints: List<String>): BitcoinPayment? {
        val rates = (terms.conversion as? PaymentConversion.Fixed)?.rates ?: return null
        if (rates.isEmpty() || rates.distinctBy { it.asset }.size != rates.size) return null
        val requestedAmount = positiveDecimal(terms.amount.value) ?: return null
        val amounts = mutableSetOf<ULong>()
        val payableEndpoints = mutableListOf<String>()
        for (endpoint in endpoints) {
            val selector = endpoint.split('-').take(2).joinToString("-")
            val rate = rates.firstOrNull { it.asset == selector } ?: rates.firstOrNull { it.asset == "btc" }
            val rateValue = rate?.value ?: "1".takeIf { terms.amount.asset == "btc" } ?: continue
            val multiplier = positiveDecimal(rateValue) ?: return null
            val sats = sats(requestedAmount, multiplier, lightning = selector == "btc-lightning") ?: return null
            amounts.add(sats)
            payableEndpoints.add(endpoint)
        }
        return amounts.singleOrNull()?.let { BitcoinPayment(it, payableEndpoints) }
    }

    private fun sats(amount: BigDecimal, rate: BigDecimal, lightning: Boolean): ULong? {
        val bitcoin = amount.multiply(rate).stripTrailingZeros()
        if (bitcoin.precision() > MAX_SIGNIFICANT_DIGITS) return null
        val units = bitcoin.movePointRight(if (lightning) MILLISATOSHI_SCALE else 8).setScale(0, RoundingMode.CEILING)
        val integer = units.toPlainString().toULongOrNull()?.takeIf { it > 0uL } ?: return null
        if (lightning && integer % 1000uL != 0uL) return null
        val sats = if (lightning) integer / 1000uL else integer
        return sats.takeIf { it <= ULong.MAX_VALUE / 1000uL }
    }

    private fun positiveDecimal(value: String): BigDecimal? {
        if (value.length > MAX_DECIMAL_LENGTH) return null
        if (!value.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
        if (value.replace(".", "").trimStart('0').length > MAX_SIGNIFICANT_DIGITS) return null
        return value.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }
}
