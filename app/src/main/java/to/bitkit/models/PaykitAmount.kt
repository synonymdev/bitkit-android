package to.bitkit.models

import kotlinx.serialization.Serializable
import to.bitkit.utils.AppError
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
enum class PaykitAsset(val code: String, val decimals: Int) {
    BTC("btc", 8),
    USD("usd", 2),
    USDT("usdt", 6),
}

sealed class PaykitAmountError(message: String) : AppError(message) {
    data object InvalidAmount : PaykitAmountError("Invalid payment amount")
    data object RateUnavailable : PaykitAmountError("Payment rates are unavailable or expired")
}

@Serializable
data class PaykitExchangeRate(val price: String, val timestampMillis: Long) {
    companion object {
        const val MAX_AGE_MILLIS = 10 * 60 * 1000L
        private const val MAX_PRICE_DIGITS = 18
    }

    fun value(nowMillis: Long): BigDecimal {
        val isFresh = timestampMillis <= nowMillis && nowMillis - timestampMillis <= MAX_AGE_MILLIS
        val isDecimal = price.count { it != '.' } <= MAX_PRICE_DIGITS &&
            price.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))
        if (!isFresh || !isDecimal) {
            throw PaykitAmountError.RateUnavailable
        }
        return price.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: throw PaykitAmountError.RateUnavailable
    }
}

@Serializable
data class PaykitAmount(val asset: PaykitAsset, val atomic: ULong) {
    companion object {
        /** Precision shared with the native decimal implementation. */
        private const val MAX_QUOTE_DIGITS = 38

        /** Maximum accepted decimal rate text length. */
        private const val MAX_RATE_LENGTH = 80

        fun parse(asset: PaykitAsset, value: String): PaykitAmount {
            val scaled = value.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) }
                ?.toBigDecimalOrNull()?.movePointRight(asset.decimals)
                ?: throw PaykitAmountError.InvalidAmount
            if (scaled.stripTrailingZeros().scale() > 0 || scaled <= BigDecimal.ZERO) {
                throw PaykitAmountError.InvalidAmount
            }
            return PaykitAmount(asset, atomic(scaled))
        }

        private fun atomic(value: BigDecimal): ULong = value.setScale(0, RoundingMode.CEILING)
            .toPlainString().toULongOrNull() ?: throw PaykitAmountError.InvalidAmount
    }

    val value: String get() = decimalValue().stripTrailingZeros().toPlainString()

    fun convertedTo(paymentAsset: PaykitAsset, rate: PaykitExchangeRate?, nowMillis: Long): PaykitAmount {
        val value = valueIn(paymentAsset, rate, nowMillis, paymentAsset.decimals)
        return PaykitAmount(paymentAsset, atomic(value.movePointRight(paymentAsset.decimals)))
    }

    fun quotedTo(asset: PaykitAsset, multiplier: String): PaykitAmount {
        val rate = multiplier.takeIf {
            it.length <= MAX_RATE_LENGTH && it.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))
        }?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: throw PaykitAmountError.RateUnavailable
        val product = atomic.toString().toBigDecimal().multiply(rate).stripTrailingZeros()
        if (product.precision() > MAX_QUOTE_DIGITS) throw PaykitAmountError.InvalidAmount
        return PaykitAmount(asset, atomic(product.scaleByPowerOfTen(asset.decimals - this.asset.decimals)))
    }

    private fun valueIn(target: PaykitAsset, rate: PaykitExchangeRate?, nowMillis: Long, scale: Int): BigDecimal {
        val amount = decimalValue()
        if ((asset == PaykitAsset.BTC) == (target == PaykitAsset.BTC)) return amount
        val price = rate?.value(nowMillis) ?: throw PaykitAmountError.RateUnavailable
        return if (asset == PaykitAsset.BTC) {
            amount.multiply(price)
        } else {
            amount.divide(price, scale, RoundingMode.CEILING)
        }
    }

    private fun decimalValue() = atomic.toString().toBigDecimal().movePointLeft(asset.decimals)
}
