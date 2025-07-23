package to.bitkit.ext

import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData

fun LnurlPayData.commentAllowed(): Boolean = commentAllowed?.let { it > 0u } == true
fun LnurlPayData.maxSendableSat(): ULong = maxSendable / 1000u
fun LnurlPayData.minSendableSat(): ULong = minSendable / 1000u

fun LnurlWithdrawData.maxWithdrawableSat(): ULong = maxWithdrawable / 1000u
