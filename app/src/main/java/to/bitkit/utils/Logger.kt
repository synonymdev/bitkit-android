package to.bitkit.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import to.bitkit.env.Env
import to.bitkit.ext.DatePattern
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

object Logger {
    private const val TAG = "APP"

    private val singleThreadDispatcher = Executors
        .newSingleThreadExecutor { Thread(it, "bitkit.log").apply { priority = Thread.NORM_PRIORITY - 1 } }
        .asCoroutineDispatcher()
    private val queue = CoroutineScope(singleThreadDispatcher + SupervisorJob())

    private val sessionLogFile: String by lazy {
        val dateFormatter = SimpleDateFormat(DatePattern.LOG_FILE, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestamp = dateFormatter.format(Date())
        val sessionLogFilePath = File(Env.logDir).resolve("bitkit_$timestamp.log").path

        // Run cleanup in background when logger is initialized
        queue.launch {
            cleanupOldLogFiles()
        }

        Log.i(TAG, "Bitkit logger initialized with session log: $sessionLogFilePath")
        sessionLogFilePath
    }

    fun info(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("INFOℹ️: $msg", context, file, line)
        Log.i(TAG, message)
        saveToFile(message)
    }

    fun debug(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("DEBUG: $msg", context, file, line)
        Log.d(TAG, message)
        saveToFile(message)
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
        saveToFile(message)
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
        saveToFile(message)
    }

    fun verbose(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("VERBOSE: $msg", context, file, line)
        Log.v(TAG, message)
        saveToFile(message)
    }

    fun performance(
        msg: String?,
        context: String = "",
        file: String = getCallerFile(),
        line: Int = getCallerLine(),
    ) {
        val message = format("PERF: $msg", context, file, line)
        Log.v(TAG, message)
        saveToFile(message)
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

    private fun saveToFile(message: String) {
        queue.launch {
            try {
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val timestamp = dateFormatter.format(Date())
                val logMessage = "[$timestamp UTC] $message\n"

                FileOutputStream(File(sessionLogFile), true).use { stream ->
                    stream.write(logMessage.toByteArray())
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }
    }

    // Cleans up both bitkit and ldk log files
    private fun cleanupOldLogFiles(maxTotalSizeMB: Int = 20) {
        val baseDir = File(Env.logDir)
        if (!baseDir.exists()) return

        val logFiles = baseDir
            .listFiles { file -> file.extension == "log" }
            ?.map { file -> Triple(file, file.length(), file.lastModified()) }
            ?: return

        var totalSize = logFiles.sumOf { it.second }
        val maxSizeBytes = maxTotalSizeMB * 1024L * 1024L

        // Sort by creation date (oldest first)
        logFiles.sortedBy { it.third }.forEach { (file, size, _) ->
            if (totalSize <= maxSizeBytes) return

            try {
                if (file.delete()) {
                    totalSize -= size
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to cleanup log file:", e)
            }
        }
    }
}
