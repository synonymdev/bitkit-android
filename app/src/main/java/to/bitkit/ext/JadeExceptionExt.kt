package to.bitkit.ext

import com.synonym.bitkitcore.JadeException

fun Throwable.isJadeUserCancellation(): Boolean =
    generateSequence(this) { it.cause }.any { it is JadeException.UserCancelled }

/** The device cannot serve the request until the user acts on it: busy with another prompt, or locked. */
fun Throwable.isJadeDeviceBusy(): Boolean =
    generateSequence(this) { it.cause }.any { it is JadeException.DeviceBusy || it is JadeException.DeviceLocked }

fun Throwable.isJadeFirmwareError(): Boolean =
    generateSequence(this) { it.cause }.any { it is JadeException.UnsupportedFirmware }

fun Throwable.isJadeSessionFailure(): Boolean =
    generateSequence(this) { it.cause }.any {
        when (it) {
            is JadeException.TransportException,
            is JadeException.DeviceDisconnected,
            is JadeException.ConnectionException,
            is JadeException.Timeout,
            is JadeException.NotConnected,
            is JadeException.NotInitialized,
            is JadeException.IoException,
            -> true

            else -> false
        }
    }
