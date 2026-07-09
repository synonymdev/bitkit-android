package to.bitkit.ext

import com.synonym.bitkitcore.TrezorException

fun Throwable.isTrezorUserCancellation(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is TrezorException.UserCancelled ||
            it is TrezorException.PinCancelled ||
            it is TrezorException.PassphraseCancelled
    }
