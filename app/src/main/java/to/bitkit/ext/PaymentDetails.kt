package to.bitkit.ext

import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.models.msatCeilOf

val PaymentDetails.amountSats: ULong?
    get() = amountMsat?.let { msatCeilOf(it) }

/** The status of this payment if this wallet sent it to pay the Lightning invoice with [paymentHash], or null. */
fun PaymentDetails.outboundPaymentStatus(paymentHash: String): PaymentStatus? = status.takeIf {
    direction == PaymentDirection.OUTBOUND && (kind as? PaymentKind.Bolt11)?.hash.equals(paymentHash, ignoreCase = true)
}
