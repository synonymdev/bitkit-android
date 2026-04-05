package to.bitkit.ext

import org.lightningdevkit.ldknode.PaymentDetails
import to.bitkit.models.MSat

val PaymentDetails.amountSats: ULong?
    get() = amountMsat?.let { MSat(it).ceil() }
