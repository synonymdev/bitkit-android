@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.models

import com.synonym.paykit.ConversionRate
import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PaymentConversionQuoteRecord
import com.synonym.paykit.PaymentDeadline
import kotlinx.serialization.Serializable
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PaykitBillingPeriod
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Market rates are only used when creating request terms. */
data class PaykitRequestPricing(
    val conversion: PaymentConversion? = null,
    val deadline: PaymentDeadline? = null,
    val quotes: List<PaymentConversionQuoteRecord> = emptyList(),
) {
    fun payment(
        requested: PaykitAmount,
        endpoint: String,
        period: PaykitBillingPeriod?,
        at: Instant,
        quoteId: String? = null,
    ): PaykitRequestPayment {
        val parts = endpoint.split("-")
        val asset = PaykitAsset.entries.firstOrNull { it.code == parts[0] }
        if (asset == null || parts.size != 3 || parts.any { !it.matches(Regex("[a-z0-9]+")) }) {
            throw PaykitAmountError.InvalidAmount
        }
        val deadline = paymentDeadline(period)
        val (rates, selected) = if (conversion == PaymentConversion.PerPeriod && requested.asset == asset) {
            emptyList<ConversionRate>() to quoteId?.let { periodQuote(period, at, it) }
        } else {
            conversionRates(period, at, quoteId)
        }
        val selector = parts.take(2).joinToString("-")
        val rate = rates.firstOrNull { it.asset == selector } ?: rates.firstOrNull { it.asset == asset.code }
        if (rate == null && requested.asset != asset) throw PaykitAmountError.RateUnavailable
        return PaykitRequestPayment(
            amount = requested.quotedTo(asset, rate?.value ?: "1"),
            quoteId = selected?.eventId,
            validFrom = selected?.let { Instant.parse(it.validFrom) },
            expiresAt = listOfNotNull(deadline, selected?.let { Instant.parse(it.expiresAt) }).minOrNull(),
        )
    }

    private fun conversionRates(
        period: PaykitBillingPeriod?,
        at: Instant,
        quoteId: String?,
    ): Pair<List<ConversionRate>, PaymentConversionQuoteRecord?> {
        var selected: PaymentConversionQuoteRecord? = null
        val rates = when (val terms = conversion) {
            is PaymentConversion.Fixed -> {
                if (
                    quoteId != null || terms.rates.isEmpty() ||
                    terms.rates.distinctBy { it.asset }.size != terms.rates.size
                ) {
                    throw PaykitAmountError.InvalidAmount
                }
                terms.rates
            }
            PaymentConversion.PerPeriod -> {
                selected = periodQuote(period, at, quoteId)
                selected.rates
            }
            null -> {
                if (quoteId != null) throw PaykitAmountError.InvalidAmount
                emptyList()
            }
        }
        return rates to selected
    }

    private fun periodQuote(period: PaykitBillingPeriod?, at: Instant, quoteId: String?): PaymentConversionQuoteRecord {
        if (period == null) throw PaykitAmountError.InvalidAmount
        return quotes.lastOrNull {
            Instant.parse(it.billingPeriod.startsAt) == period.startsAt &&
                Instant.parse(it.billingPeriod.endsAt) == period.endsAt &&
                if (quoteId != null) {
                    it.eventId == quoteId
                } else {
                    at >= Instant.parse(it.validFrom) && at <= Instant.parse(it.expiresAt)
                }
        } ?: throw PaykitAmountError.RateUnavailable
    }

    fun paymentDeadline(period: PaykitBillingPeriod?): Instant? = when (val value = deadline) {
        null -> null
        is PaymentDeadline.At -> {
            if (period != null) throw PaykitAmountError.InvalidAmount
            Instant.parse(value.timestamp)
        }
        is PaymentDeadline.PeriodStart -> {
            if (period == null || value.seconds > Long.MAX_VALUE.toULong()) throw PaykitAmountError.InvalidAmount
            period.startsAt + value.seconds.toLong().seconds
        }
    }

    companion object {
        fun subscriptionEndpoints(requested: PaykitAsset, available: List<String>): List<String> = available.filter {
            val method = MethodId.fromRawValue(it) ?: return@filter false
            when (requested) {
                PaykitAsset.BTC -> method != MethodId.UsdtArbitrum
                PaykitAsset.USD -> method == MethodId.UsdtArbitrum
                PaykitAsset.USDT -> false
            }
        }

        fun rates(
            requested: PaykitAsset,
            endpoints: List<String>,
            market: PaykitExchangeRate?,
            at: Instant,
        ): List<ConversionRate> = endpoints.mapNotNull { MethodId.fromRawValue(it) }
            .map { if (it == MethodId.UsdtArbitrum) PaykitAsset.USDT else PaykitAsset.BTC }
            .distinct().sortedBy { it.code }.filter { it != requested }.map { asset ->
                val value = if ((requested == PaykitAsset.BTC) != (asset == PaykitAsset.BTC)) {
                    val price = market?.value(at.toEpochMilliseconds()) ?: throw PaykitAmountError.RateUnavailable
                    if (requested == PaykitAsset.BTC) {
                        price
                    } else {
                        BigDecimal.ONE.divide(
                            price,
                            18,
                            RoundingMode.HALF_EVEN
                        )
                    }
                } else {
                    BigDecimal.ONE
                }
                if (value <= BigDecimal.ZERO) throw PaykitAmountError.RateUnavailable
                ConversionRate(asset.code, value.stripTrailingZeros().toPlainString())
            }
    }
}

@Serializable
data class PaykitRequestPayment(
    val amount: PaykitAmount,
    val quoteId: String?,
    val validFrom: Instant?,
    val expiresAt: Instant?,
) {
    fun isValid(at: Instant): Boolean = (validFrom == null || at >= validFrom) && (expiresAt == null || at <= expiresAt)
}
