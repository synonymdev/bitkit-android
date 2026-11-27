package to.bitkit.wear.utils

import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Logs to logcat under the tag `BitkitWear`, with the caller's TAG as a `[context]` prefix. Debug builds also write
 * the log to `files/logs/`, as the phone does; see [init]. Never log the recovery phrase or the passphrase.
 */
object Logger {
    private const val APP = "BitkitWear"

    @Volatile
    private var saver: LogFileSaver? = null

    /** Starts writing this process's log to a new file in [filesDir]/logs; only debug builds call it. */
    fun init(filesDir: File) {
        saver = LogFileSaver(File(filesDir, "logs"))
    }

    fun debug(msg: String, context: String = "") = log(Log.DEBUG, "DEBUG", format(msg, context))

    fun info(msg: String, context: String = "") = log(Log.INFO, "INFO", format(msg, context))

    fun warn(msg: String, e: Throwable? = null, context: String = "") = log(Log.WARN, "WARN", format(msg, context, e))

    fun error(msg: String, e: Throwable? = null, context: String = "") =
        log(Log.ERROR, "ERROR", format(msg, context, e))

    private fun log(priority: Int, level: String, line: String) {
        Log.println(priority, APP, line)
        saver?.save(level, line)
    }

    private fun format(msg: String, context: String, e: Throwable? = null): String {
        val prefix = if (context.isEmpty()) "" else "[$context] "
        val suffix = e?.let { ": ${it::class.simpleName}: ${it.message}" }.orEmpty()
        return "$prefix$msg$suffix"
    }
}

/** Appends log lines to one file per process on a background thread, keeping the newest [MAX_FILES] files. */
private class LogFileSaver(private val directory: File) {
    companion object {
        /** How many log files are kept, this process's included. */
        const val MAX_FILES = 5

        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val file = File(directory, "bitkit_${LocalDateTime.now().format(FILE_TIME)}.log")

    init {
        executor.execute {
            directory.mkdirs()
            directory.listFiles { it.name.endsWith(".log") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_FILES - 1)
                ?.forEach { it.delete() }
        }
    }

    fun save(level: String, line: String) {
        val text = "${LocalDateTime.now().format(LINE_TIME)} $level $line\n"
        executor.execute { runCatching { file.appendText(text) } }
    }
}
