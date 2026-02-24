package to.bitkit.ext

import android.content.Context
import org.lightningdevkit.ldknode.PaymentFailureReason
import to.bitkit.R

fun PaymentFailureReason?.toUserMessage(context: Context): String = when (this) {
    PaymentFailureReason.RECIPIENT_REJECTED ->
        context.getString(R.string.wallet__toast_payment_failed_recipient_rejected)
    PaymentFailureReason.RETRIES_EXHAUSTED ->
        context.getString(R.string.wallet__toast_payment_failed_retries_exhausted)
    PaymentFailureReason.ROUTE_NOT_FOUND ->
        context.getString(R.string.wallet__toast_payment_failed_route_not_found)
    PaymentFailureReason.PAYMENT_EXPIRED ->
        context.getString(R.string.wallet__toast_payment_failed_timeout)
    else -> context.getString(R.string.wallet__toast_payment_failed_description)
}
