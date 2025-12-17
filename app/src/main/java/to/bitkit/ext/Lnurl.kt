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

fun LnurlWithdrawData.minWithdrawableSat(): ULong = msatsToSatsCeil(minWithdrawable ?: 0u)
fun LnurlWithdrawData.maxWithdrawableSat(): ULong = maxWithdrawable / MSATS_PER_SAT
