package to.bitkit.ext

import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData
import to.bitkit.models.MSat
import to.bitkit.models.msatCeilOf
import to.bitkit.models.msatFloorOf

fun LnurlPayData.commentAllowed(): Boolean = commentAllowed?.let { it > 0u } == true
fun LnurlPayData.maxSendableSat(): ULong = msatFloorOf(maxSendable)
fun LnurlPayData.minSendableSat(): ULong = msatCeilOf(minSendable)

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
    if (isFixedAmount()) minSendable else (userSats ?: minSendableSat()) * MSat.PER_SAT

fun LnurlWithdrawData.minWithdrawableSat(): ULong = msatCeilOf(minWithdrawable ?: 0u)
fun LnurlWithdrawData.maxWithdrawableSat(): ULong = msatFloorOf(maxWithdrawable)

/**
 * True when the LNURL-withdraw endpoint specifies a single exact amount,
 * including the sub-sat edge case where rounding causes `min > max` in whole sats.
 */
fun LnurlWithdrawData.isFixedAmount(): Boolean {
    val min = minWithdrawable ?: 0u
    return min == maxWithdrawable || (min > 0u && minWithdrawableSat() > maxWithdrawableSat())
}
