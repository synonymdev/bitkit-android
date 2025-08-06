package to.bitkit.models

import androidx.annotation.StringRes
import com.synonym.bitkitcore.BtOrderState2
import to.bitkit.R

enum class ChannelSetupStep(
    val stepNumber: Int,
    @StringRes val label: Int,
) {
    PROCESSING_PAYMENT(stepNumber = 1, label = R.string.lightning__setting_up_step1),
    PAYMENT_SUCCESSFUL(stepNumber = 2, label = R.string.lightning__setting_up_step2),
    QUEUED_FOR_OPENING(stepNumber = 3, label = R.string.lightning__setting_up_step3),
    OPENING_CONNECTION(stepNumber = 4, label = R.string.lightning__setting_up_step4),
//    EXPIRED(stepNumber = -1, label = R.string.lightning__setting_up_step4),
}



//fun BtOrderState2.toChannelSetupStep() = when(this) {
//    BtOrderState2.CREATED -> TODO()
//    BtOrderState2.EXPIRED -> TODO()
//    BtOrderState2.EXECUTED -> TODO()
//    BtOrderState2.PAID -> TODO()
//}
