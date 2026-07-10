package to.bitkit.utils

import android.content.Context
import to.bitkit.R
import to.bitkit.ext.isTrezorLockedOrBusy

object TrezorErrorPresenter {
    fun userMessage(context: Context, error: Throwable): String {
        if (error.isTrezorLockedOrBusy()) {
            return context.getString(R.string.hardware__device_busy)
        }
        return userMessage(
            context = context,
            error = error,
            fallback = context.getString(R.string.hardware__connect_error),
        )
    }

    fun userMessage(context: Context, error: Throwable, fallback: String): String {
        if (error.isTrezorLockedOrBusy()) {
            return context.getString(R.string.hardware__device_busy)
        }
        return error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
