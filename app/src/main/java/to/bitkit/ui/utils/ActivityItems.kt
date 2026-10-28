package to.bitkit.ui.utils

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentState
import to.bitkit.R
import to.bitkit.ext.isSent
import to.bitkit.ext.isTransfer
import to.bitkit.ext.totalValue

fun Activity.displayValue(isPrimaryFunding: Boolean = false): ULong =
    if (isPrimaryFunding && this is Activity.Onchain && isSent()) v1.fee else totalValue()

fun Activity.getScreenTitleRes(isPrimaryFunding: Boolean = false): Int {
    if (isPrimaryFunding && this is Activity.Onchain && isSent()) return R.string.primary_node__funding_fee
    val isSent = this.isSent()

    if (this is Activity.Lightning && isSent && v1.status == PaymentState.PENDING) {
        return R.string.wallet__activity_pending_nav_title
    }

    var resId = when {
        isSent -> R.string.wallet__activity_bitcoin_sent
        else -> R.string.wallet__activity_bitcoin_received
    }

    if (this.isTransfer()) {
        resId = when {
            isSent -> R.string.wallet__activity_transfer_spending_done
            else -> R.string.wallet__activity_transfer_savings_done
        }
    }

    return resId
}
