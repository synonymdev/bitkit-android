package to.bitkit.ext

import org.lightningdevkit.ldknode.PaymentDetails
import to.bitkit.models.msatCeilOf

val PaymentDetails.amountSats: ULong?
    get() = amountMsat?.let { msatCeilOf(it) }
