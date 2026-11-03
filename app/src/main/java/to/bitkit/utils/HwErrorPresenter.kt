package to.bitkit.utils

import android.content.Context
import com.synonym.bitkitcore.JadeException
import to.bitkit.R

/** User-facing messages for hardware wallet errors of every vendor; Jade first, then the Trezor rules. */
object HwErrorPresenter {
    fun userMessage(context: Context, error: Throwable): String =
        jadeMessage(context, error) ?: TrezorErrorPresenter.userMessage(context, error)

    fun userMessage(context: Context, error: Throwable, fallback: String): String =
        jadeMessage(context, error) ?: TrezorErrorPresenter.userMessage(context, error, fallback)

    private fun jadeMessage(context: Context, error: Throwable): String? {
        val jadeError = generateSequence(error) { it.cause }.firstOrNull { it is JadeException } ?: return null
        val res = when (jadeError) {
            is JadeException.InvalidPin -> R.string.hardware__jade_invalid_pin
            is JadeException.DeviceUninitialized -> R.string.hardware__jade_uninitialized
            is JadeException.UnsupportedFirmware -> R.string.hardware__jade_firmware_outdated
            is JadeException.PsbtTooLarge -> R.string.hardware__jade_psbt_too_large
            is JadeException.NetworkMismatch -> R.string.hardware__jade_network_mismatch
            is JadeException.DeviceBusy, is JadeException.DeviceLocked -> R.string.hardware__jade_device_busy
            is JadeException.PinServerException -> R.string.hardware__jade_pinserver_error
            // The transport describes what went wrong in words meant for the user.
            is JadeException.TransportException -> return jadeError.errorDetails.takeIf { it.isNotBlank() }
            is JadeException.ConnectionException -> return jadeError.errorDetails.takeIf { it.isNotBlank() }
            else -> return null
        }
        return context.getString(res)
    }
}
