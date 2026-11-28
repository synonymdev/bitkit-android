package to.bitkit.wear.ui

import android.net.Uri

object Routes {
    const val ONBOARDING = "onboarding"

    /** The graph of the restore screens, which share one view model. */
    const val RESTORE = "restore"
    const val RESTORE_START = "restore/start"
    const val RESTORE_WORD_INDEX = "index"
    const val RESTORE_WORD = "restore/word/{$RESTORE_WORD_INDEX}"
    const val RESTORE_CHECKSUM = "restore/checksum"
    const val RESTORE_PASSPHRASE = "restore/passphrase"
    const val WALLET_REMOVED = "wallet_removed"

    /** The home pager, with the balance, receive, activity and settings pages. */
    const val HOME = "home"
    const val ABOUT = "about"

    /** The full-screen QR of a receive invoice. */
    const val RECEIVE_QR = "receive/qr"

    /** The send flow for the payment input in [SEND_INPUT], from a deeplink. */
    const val SEND_INPUT = "input"
    const val SEND = "send?$SEND_INPUT={$SEND_INPUT}"

    /** One payment of the activity list, by its id in [ACTIVITY_PAYMENT_ID]. */
    const val ACTIVITY_PAYMENT_ID = "id"
    const val ACTIVITY_DETAIL = "activity/{$ACTIVITY_PAYMENT_ID}"
    const val UNSUPPORTED = "unsupported"

    fun restoreWord(index: Int): String = "restore/word/$index"

    fun send(input: String): String = "send?$SEND_INPUT=${Uri.encode(input)}"

    fun activityDetail(paymentId: String): String = "activity/${Uri.encode(paymentId)}"
}
