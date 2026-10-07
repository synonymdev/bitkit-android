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
        asset: PaykitAsset,
        period: PaykitBillingPeriod?,
        at: Instant,
        quoteId: String? = null,
    ): PaykitRequestPayment {
        val deadline = paymentDeadline(period)
        if (requested.asset == asset) {
            if (quoteId != null) throw PaykitAmountError.InvalidAmount
            return PaykitRequestPayment(requested, null, null, deadline)
        }
        val (rates, selected) = conversionRates(period, at, quoteId)
        val rate = rates.firstOrNull { it.asset == asset.code } ?: throw PaykitAmountError.RateUnavailable
        return PaykitRequestPayment(
            amount = requested.quotedTo(asset, rate.value),
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
                if (quoteId != null) throw PaykitAmountError.InvalidAmount
                terms.rates
            }
            PaymentConversion.PerPeriod -> {
                selected = periodQuote(period, at, quoteId)
                selected.rates
            }
            null -> throw PaykitAmountError.RateUnavailable
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
