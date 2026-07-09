package to.bitkit.utils

import android.content.Context
import to.bitkit.R
import to.bitkit.ext.isTrezorDeviceBusy

object TrezorErrorPresenter {
    fun userMessage(context: Context, error: Throwable): String {
        if (error.isTrezorDeviceBusy()) {
            return context.getString(R.string.hardware__device_busy)
        }
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
        return error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
