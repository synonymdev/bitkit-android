package to.bitkit.ext

import com.synonym.bitkitcore.TrezorException

fun Throwable.isTrezorUserCancellation(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is TrezorException.UserCancelled,
            is TrezorException.PinCancelled,
            is TrezorException.PassphraseCancelled,
            -> return true
        }
        current = current.cause
    }
    return false
}
