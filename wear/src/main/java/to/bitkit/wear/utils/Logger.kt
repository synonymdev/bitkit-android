package to.bitkit.wear.utils

import android.util.Log

/** Logcat only: the watch keeps no log files, so nothing it logs outlives the process. */
object Logger {
    private const val APP = "BitkitWear"

    fun debug(msg: String, context: String = "") {
        Log.d(APP, format(msg, context))
    }

    fun info(msg: String, context: String = "") {
        Log.i(APP, format(msg, context))
    }

    fun warn(msg: String, e: Throwable? = null, context: String = "") {
        Log.w(APP, format(msg, context, e))
    }

    fun error(msg: String, e: Throwable? = null, context: String = "") {
        Log.e(APP, format(msg, context, e))
    }

    private fun format(msg: String, context: String, e: Throwable? = null): String {
        val prefix = if (context.isEmpty()) "" else "[$context] "
        val suffix = e?.let { ": ${it::class.simpleName}: ${it.message}" }.orEmpty()
        return "$prefix$msg$suffix"
    }
}
