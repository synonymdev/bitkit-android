package to.bitkit.wear.ui

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
    const val HOME = "home"
    const val SEND = "send"
    const val RECEIVE = "receive"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val UNSUPPORTED = "unsupported"

    fun restoreWord(index: Int): String = "restore/word/$index"
}
