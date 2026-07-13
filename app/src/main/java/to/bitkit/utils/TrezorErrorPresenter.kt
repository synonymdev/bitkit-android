package to.bitkit.utils

import android.content.Context
import to.bitkit.R
import to.bitkit.ext.isTrezorDeviceBusy
import to.bitkit.ext.isTrezorFirmwareError

object TrezorErrorPresenter {
    fun userMessage(context: Context, error: Throwable): String {
        if (error.isTrezorDeviceBusy()) {
            return context.getString(R.string.hardware__device_busy)
        }
        if (error.isTrezorFirmwareError()) return context.getString(R.string.hardware__connect_error)
        return userMessage(
            context = context,
            error = error,
            fallback = context.getString(R.string.hardware__connect_error),
        )
    }

    fun userMessage(context: Context, error: Throwable, fallback: String): String {
        if (error.isTrezorDeviceBusy()) {
            return context.getString(R.string.hardware__device_busy)
        }
        if (error.isTrezorFirmwareError()) return context.getString(R.string.hardware__connect_error)
        return error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
