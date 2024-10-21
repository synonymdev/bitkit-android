package to.bitkit.ext

import org.lightningdevkit.ldknode.PaymentDetails

val PaymentDetails.amountSats: ULong? get() = amountMsat?.let { it / 1000u }
