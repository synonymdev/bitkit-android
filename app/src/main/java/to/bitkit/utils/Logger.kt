package to.bitkit.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

object Logger {
    private const val TAG = "APP"

    private val singleThreadDispatcher = Executors
        .newSingleThreadExecutor { Thread(it, "bitkit.log").apply { priority = Thread.NORM_PRIORITY - 1 } }
        .asCoroutineDispatcher()
    private val queue = CoroutineScope(singleThreadDispatcher + SupervisorJob())

    fun info(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("INFOℹ️: $msg", context, file, line)
        Log.i(TAG, message)
        saveLog(message)
    }

    fun debug(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("DEBUG: $msg", context, file, line)
        Log.d(TAG, message)
        saveLog(message)
    }

    fun warn(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val errMsg = e?.message?.let { " (err: '$it')" } ?: ""
        val message = format("WARN⚠️: $msg$errMsg", context, file, line)
        Log.w(TAG, message, e)
        saveLog(message)
    }

    fun error(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val errMsg = e?.message?.let { " (err: '$it')" } ?: ""
        val message = format("ERROR❌️: $msg$errMsg", context, file, line)
        Log.e(TAG, message, e)
        saveLog(message)
    }

    fun verbose(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("VERBOSE: $msg", context, file, line)
        Log.v(TAG, message)
        saveLog(message)
    }

    fun performance(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("PERF: $msg", context, file, line)
        Log.v(TAG, message)
        saveLog(message)
    }

    private fun format(message: Any, context: String, file: String, line: Int): String {
        return "$message ${if (context.isNotEmpty()) "- $context " else ""}[$file:$line]"
    }

    private fun getCallerFile(): String {
        return Thread.currentThread().stackTrace.getOrNull(4)?.fileName ?: "UnknownFile"
    }

    private fun getCallerLine(): Int {
        return Thread.currentThread().stackTrace.getOrNull(4)?.lineNumber ?: -1
    }

    private fun saveLog(message: String) {
        queue.launch {
            // TODO: save log to file
        }
    }
}
