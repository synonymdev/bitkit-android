package to.bitkit.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.lightningdevkit.ldknode.LogRecord
import org.lightningdevkit.ldknode.LogWriter
import to.bitkit.async.ServiceQueue
import to.bitkit.async.newSingleThreadDispatcher
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.ext.DatePattern
import to.bitkit.ext.utcDateFormatterOf
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Locale
import kotlin.time.measureTime
import org.lightningdevkit.ldknode.LogLevel as LdkLogLevel

private const val APP = "APP"
private const val LDK = "LDK"
private const val COMPACT = false

enum class LogSource { Ldk, Bitkit, Unknown }
enum class LogLevel { PERF, VERBOSE, GOSSIP, TRACE, DEBUG, INFO, WARN, ERROR; }

val Logger = AppLogger()

class AppLogger {
    companion object {
        private const val TAG = "Logger"
    }

    private var delegate: LoggerImpl? = null

    init {
        delegate = runCatching { createDelegate() }.getOrNull()
    }

    private fun createDelegate() = LoggerImpl(APP, LogSaverImpl.shared(), source = APP)

    fun reset() {
        warn("Wiping entire logs directory…", context = TAG)
        runCatching { Env.logDir.deleteRecursively() }
        LogSaverImpl.reset()
        delegate = runCatching { createDelegate() }.getOrNull()
    }

    fun info(
        msg: String?,
        context: String = "",
        file: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        delegate?.info(msg, context, file, line)
    }

    fun debug(
        msg: String?,
        context: String = "",
        file: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        delegate?.debug(msg, context, file, line)
    }

    fun warn(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        file: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        delegate?.warn(msg, e, context, file, line)
    }

    fun error(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        file: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        delegate?.error(msg, e, context, file, line)
    }

    fun verbose(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        file: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        delegate?.verbose(msg, e, context, file, line)
    }

    fun perf(
        msg: String?,
        context: String = "",
        file: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        delegate?.perf(msg, context, file, line)
    }
}

class LoggerImpl(
    private val tag: String = APP,
    private val saver: LogSaver,
    private val compact: Boolean = COMPACT,
    private val source: String = APP,
) {
    fun info(
        msg: String?,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val message = formatLog(LogLevel.INFO, msg, context, path, line, source)
        Log.i(tag, message)
        saver.save(message)
    }

    fun debug(
        msg: String?,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val message = formatLog(LogLevel.DEBUG, msg, context, path, line, source)
        Log.d(tag, message)
        saver.save(message)
    }

    fun warn(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val errMsg = e?.let { errorLogOf(it) }.orEmpty()
        val message = formatLog(LogLevel.WARN, "$msg $errMsg", context, path, line, source)
        if (compact) Log.w(tag, message) else Log.w(tag, message, e)
        saver.save(message)
    }

    fun error(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val errMsg = e?.let { errorLogOf(it) }.orEmpty()
        val message = formatLog(LogLevel.ERROR, "$msg $errMsg", context, path, line, source)
        if (compact) Log.e(tag, message) else Log.e(tag, message, e)
        saver.save(message)
    }

    @Suppress("LongParameterList")
    fun verbose(
        msg: String?,
        e: Throwable? = null,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
        level: LogLevel = LogLevel.VERBOSE,
    ) {
        val message = formatLog(level, msg, context, path, line, source)
        if (compact) Log.v(tag, message) else Log.v(tag, message, e)
        saver.save(message)
    }

    fun perf(
        msg: String?,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val message = formatLog(LogLevel.PERF, msg, context, path, line, source)
        Log.v(tag, message)
        saver.save(message)
    }
}

interface LogSaver {
    fun save(message: String)
}

class LogSaverImpl private constructor(
    private val sessionFilePath: String,
) : LogSaver {
    private val queue: CoroutineScope by lazy {
        CoroutineScope(newSingleThreadDispatcher(ServiceQueue.LOG.name) + SupervisorJob())
    }

    init {
        if (sessionFilePath.isNotEmpty()) {
            log("Log session initialized with file path: '$sessionFilePath'")

            // Clean all old log files in background
            CoroutineScope(Dispatchers.IO).launch {
                cleanupOldLogFiles()
            }
        }
    }

    override fun save(message: String) {
        if (sessionFilePath.isEmpty()) return

        queue.launch {
            runCatching {
                FileOutputStream(File(sessionFilePath), true).use { stream ->
                    stream.write("$message\n".toByteArray())
                }
            }.onFailure {
                Log.e(APP, "Error writing to log file: '$sessionFilePath'", it)
            }
        }
    }

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val formatted = formatLog(level, message, TAG, getCallerPath(), getCallerLine())
        Log.i(APP, formatted)
        save(formatted)
    }

    private fun cleanupOldLogFiles(maxTotalSizeMB: Int = 20) {
        log("Deleting old log files…", LogLevel.VERBOSE)
        val logDir = runCatching { Env.logDir }.getOrNull() ?: return

        val logFiles = logDir
            .listFiles { file -> file.extension == "log" }
            ?.map { file -> Triple(file, file.length(), file.lastModified()) }
            ?: return

        var totalSize = logFiles.sumOf { it.second }
        val maxSizeBytes = maxTotalSizeMB * 1024L * 1024L

        // Sort by creation date (oldest first)
        logFiles
            .sortedBy { it.third }
            .forEach { (file, size, _) ->
                if (totalSize <= maxSizeBytes) return

                runCatching {
                    Log.d(APP, "Deleting old log file: '${file.name}'")
                    if (file.delete()) {
                        totalSize -= size
                    }
                }.onFailure {
                    Log.w(APP, "Failed to delete old log file: '${file.name}'", it)
                }
            }
        Log.v(APP, "Deleted all old log files.")
    }

    companion object {
        private const val TAG = "LogSaver"

        @Volatile
        private var instance: LogSaverImpl? = null

        fun shared(): LogSaverImpl = instance ?: synchronized(this) {
            instance ?: runCatching {
                LogSaverImpl(buildSessionLogFilePath())
            }.getOrElse {
                LogSaverImpl("")
            }.also { instance = it }
        }

        fun reset() {
            synchronized(this) {
                instance = null
            }
        }
    }
}

class LdkLogWriter(
    private val maxLogLevel: LdkLogLevel = Env.ldkLogLevel,
    saver: LogSaver = LogSaverImpl.shared(),
) : LogWriter {
    private val delegate: LoggerImpl = LoggerImpl(LDK, saver, source = LDK)

    override fun log(record: LogRecord) {
        if (record.level < maxLogLevel) return

        val msg = record.args
        val path = record.modulePath
        val line = record.line.toInt()

        when (record.level) {
            LdkLogLevel.GOSSIP -> delegate.verbose(msg, path = path, line = line, level = LogLevel.GOSSIP)
            LdkLogLevel.TRACE -> delegate.verbose(msg, path = path, line = line, level = LogLevel.TRACE)
            LdkLogLevel.DEBUG -> delegate.debug(msg, path = path, line = line)
            LdkLogLevel.INFO -> delegate.info(msg, path = path, line = line)
            LdkLogLevel.WARN -> delegate.warn(msg, path = path, line = line)
            LdkLogLevel.ERROR -> delegate.error(msg, path = path, line = line)
        }
    }
}

private fun buildSessionLogFilePath(): String {
    val logDir = Env.logDir
    val timestamp = utcDateFormatterOf(DatePattern.LOG_FILE).format(Date())
    val path = logDir.resolve("bitkit_$timestamp.log").path
    return path
}

private fun formatLog(
    level: LogLevel,
    msg: String?,
    context: String,
    path: String,
    line: Int,
    source: String = APP,
): String {
    val timestamp = utcDateFormatterOf(DatePattern.LOG_LINE).format(Date())
    val message = msg?.trim().orEmpty()
    val contextString = if (context.isNotEmpty()) " - $context" else ""
    val location = "[$path:$line]"
    return String.format(
        Locale.US,
        "%s %-4s %-7s %-36s %s%s",
        timestamp,
        source,
        level.name,
        location,
        message,
        contextString,
    )
}

/**
 * Determines which stack frame to use for caller info.
 *
 * The value 5 is chosen based on the current call chain:
 * - [0] `Thread.getStackTrace`
 * - [1] `getCallerPath`/`getCallerLine`
 * - [2] `LoggerImpl.log_method` (e.g., debug, info, etc.)
 * - [3] `LdkLogWriter.log_method`
 * - [4] external caller
 * - [5] actual caller of logging function
 *
 * If the call chain changes, this index may need to be updated.
 */

private const val STACK_INDEX = 5
private fun getCallerPath(): String = Thread.currentThread().stackTrace.getOrNull(STACK_INDEX)?.fileName ?: "Unknown"
private fun getCallerLine(): Int = Thread.currentThread().stackTrace.getOrNull(STACK_INDEX)?.lineNumber ?: -1

val jsonLogger = Json(json) {
    prettyPrint = false
}

inline fun <reified T : Any> jsonLogOf(value: T): String {
    val jsonElement = jsonLogger.encodeToJsonElement(jsonLogger.serializersModule.serializer<T>(), value)
    if (jsonElement !is JsonObject || "type" !in jsonElement) return jsonElement.toString()

    return buildJsonObject {
        jsonElement.forEach { (key, elem) ->
            if (key == "type") {
                put("type", value::class.simpleName ?: "Unknown")
            } else {
                put(key, elem)
            }
        }
    }.toString()
}

fun errorLogOf(e: Throwable): String = "[${e::class.simpleName}='${e.message}']"

internal inline fun <T> measured(
    label: String,
    context: String,
    block: () -> T,
): T {
    var result: T
    val elapsed = measureTime {
        result = block()
    }
    Logger.perf("$label took $elapsed", context = context)
    return result
}
