package to.bitkit.ui.utils

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentState
import to.bitkit.R
import to.bitkit.ext.isSent
import to.bitkit.ext.isTransfer

fun Activity.getScreenTitleRes(): Int {
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
