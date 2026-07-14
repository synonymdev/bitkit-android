package to.bitkit.ext

import com.synonym.bitkitcore.TrezorException

/** Generic Trezor protocol Failure_FirmwareError code. */
private const val FIRMWARE_ERROR_CODE = 99

fun Throwable.isTrezorUserCancellation(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is TrezorException.UserCancelled ||
            it is TrezorException.PinCancelled ||
            it is TrezorException.PassphraseCancelled
    }

fun Throwable.isTrezorDeviceBusy(): Boolean =
    generateSequence(this) { it.cause }.any { it is TrezorException.DeviceBusy }

fun Throwable.isTrezorFirmwareError(): Boolean =
    generateSequence(this) { it.cause }.any {
        val message = it.message.orEmpty()
        "Device error (code $FIRMWARE_ERROR_CODE)" in message && "Firmware error" in message
    }
