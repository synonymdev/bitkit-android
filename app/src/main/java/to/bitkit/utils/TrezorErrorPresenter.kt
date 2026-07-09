package to.bitkit.utils

import android.content.Context
import to.bitkit.R
import to.bitkit.ext.isTrezorDeviceBusy

object TrezorErrorPresenter {
    fun userMessage(
        context: Context,
        error: Throwable,
        fallback: String = context.getString(R.string.hardware__connect_error),
    ): String {
        if (error.isTrezorDeviceBusy()) {
            return context.getString(R.string.hardware__device_busy)
        }
        return error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
