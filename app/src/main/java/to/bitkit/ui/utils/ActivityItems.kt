package to.bitkit.ui.utils

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentType
import to.bitkit.R

fun Activity.getScreenTitleRes(): Int {
    val isSent = when (this) {
        is Activity.Lightning -> v1.txType == PaymentType.SENT
        is Activity.Onchain -> v1.txType == PaymentType.SENT
    }

    var resId = when {
        isSent -> R.string.wallet__activity_bitcoin_sent
        else -> R.string.wallet__activity_bitcoin_received
    }

    val isTransfer = this is Activity.Onchain && v1.isTransfer
    if (isTransfer) {
        resId = when {
            isSent -> R.string.wallet__activity_transfer_spending_done
            else -> R.string.wallet__activity_transfer_savings_done
        }
    }

    return resId
}
