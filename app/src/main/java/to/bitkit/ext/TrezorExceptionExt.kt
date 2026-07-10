package to.bitkit.ext

import com.synonym.bitkitcore.TrezorException

fun Throwable.isTrezorUserCancellation(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is TrezorException.UserCancelled ||
            it is TrezorException.PinCancelled ||
            it is TrezorException.PassphraseCancelled
    }

fun Throwable.isTrezorDeviceBusy(): Boolean =
    generateSequence(this) { it.cause }.any { it is TrezorException.DeviceBusy }

fun Throwable.isTrezorLockedOrBusy(): Boolean =
    isTrezorDeviceBusy() ||
        generateSequence(this) { it.cause }.any {
            val message = it.message.orEmpty()
            "Device error (code 99)" in message && "Firmware error" in message
        }
