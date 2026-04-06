package to.bitkit.ext

import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData

private const val MSATS_PER_SAT: ULong = 1000u

/**
 * LNURL amounts are expressed in millisatoshis (msat).
 *
 * When converting a minimum bound to whole sats we must round up:
 * `minSendable = 100500 msat` means the minimum payable amount is `101 sat` (not `100 sat`).
 */
private fun msatsToSatsCeil(msats: ULong): ULong {
    val quotient = msats / MSATS_PER_SAT
    val remainder = msats % MSATS_PER_SAT
    return when (remainder) {
        0uL -> quotient
        else -> quotient + 1uL
    }
}

fun LnurlPayData.commentAllowed(): Boolean = commentAllowed?.let { it > 0u } == true
fun LnurlPayData.maxSendableSat(): ULong = maxSendable / MSATS_PER_SAT
fun LnurlPayData.minSendableSat(): ULong = msatsToSatsCeil(minSendable)

/**
 * True when the LNURL-pay endpoint specifies a single exact amount.
 *
 * This also covers the sub-sat edge case where `minSendable` and `maxSendable` differ
 * in their sub-sat fraction but map to the same (or inverted) sat range after rounding,
 * e.g. `minSendable = 500500, maxSendable = 500500` → `minSendableSat() = 501, maxSendableSat() = 500`.
 */
fun LnurlPayData.isFixedAmount(): Boolean =
    minSendable == maxSendable || (minSendable > 0u && minSendableSat() > maxSendableSat())

/**
 * Returns the amount in millisatoshis to send in the LNURL-pay callback.
 *
 * For fixed-amount requests (including sub-sat ranges) the original msat value
 * from the server is returned verbatim, avoiding precision loss from the
 * msat→sat→msat round-trip.
 *
 * For variable-amount requests the user-selected sat amount is converted to msats.
 */
fun LnurlPayData.callbackAmountMsats(userSats: ULong? = null): ULong =
    if (isFixedAmount()) minSendable else (userSats ?: minSendableSat()) * MSATS_PER_SAT

fun LnurlWithdrawData.minWithdrawableSat(): ULong = msatsToSatsCeil(minWithdrawable ?: 0u)
fun LnurlWithdrawData.maxWithdrawableSat(): ULong = maxWithdrawable / MSATS_PER_SAT

/**
 * True when the LNURL-withdraw endpoint specifies a single exact amount,
 * including the sub-sat edge case where rounding causes `min > max` in whole sats.
 */
fun LnurlWithdrawData.isFixedAmount(): Boolean {
    val min = minWithdrawable ?: 0u
    return min == maxWithdrawable || (min > 0u && minWithdrawableSat() > maxWithdrawableSat())
}
