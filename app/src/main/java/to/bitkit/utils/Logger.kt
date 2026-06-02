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
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

private const val APP = "APP"
private const val COMPACT = false
private const val LOG_FILE_EXTENSION = "log"
private const val MAX_LOG_FILE_BYTES = 1L * 1024L * 1024L
private const val MAX_LOG_RETENTION_BYTES = 500L * 1024L * 1024L
private const val MAX_LOG_RETENTION_DAYS = 60L
private const val MILLIS_IN_DAY = 24L * 60L * 60L * 1000L
private val DEFAULT_SLOW_OPERATION_THRESHOLD = 1.seconds

enum class LogSource { Ldk, Bitkit, Unknown }
enum class LogLevel { PERF, VERBOSE, GOSSIP, TRACE, DEBUG, INFO, WARN, ERROR }

val Logger = AppLogger()

class AppLogger {
    companion object {
        private const val TAG = "Logger"
        private val saverRef = AtomicReference<LogSaver?>(null)

        fun getOrCreateSaver(): LogSaver = saverRef.get() ?: synchronized(this) {
            saverRef.get() ?: run {
                val sessionPath = runCatching { buildSessionLogFilePath() }.getOrElse { "" }
                LogSaverImpl(sessionPath).also { saverRef.set(it) }
            }
        }
    }

    private var delegate: LoggerImpl? = null

    init {
        delegate = runCatching { createDelegate() }.getOrNull()
    }

    private fun createDelegate() = LoggerImpl(APP, getOrCreateSaver())

    fun reset() {
        warn("Wiping entire logs directory…", context = TAG)
        synchronized(AppLogger) {
            runCatching { Env.logDir.deleteRecursively() }
            saverRef.set(null)
            delegate = runCatching { createDelegate() }.getOrNull()
        }
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

internal class LoggerImpl(
    private val tag: String = APP,
    private val saver: LogSaver,
    private val compact: Boolean = COMPACT,
) {
    fun info(
        msg: String?,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val message = formatLog(LogLevel.INFO, msg, context, path, line)
        Log.i(tag, message)
        saver.save(message)
    }

    fun debug(
        msg: String?,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val message = formatLog(LogLevel.DEBUG, msg, context, path, line)
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
        val message = formatLog(LogLevel.WARN, "$msg $errMsg", context, path, line)
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
        val message = formatLog(LogLevel.ERROR, "$msg $errMsg", context, path, line)
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
        val message = formatLog(level, msg, context, path, line)
        if (compact) Log.v(tag, message) else Log.v(tag, message, e)
        saver.save(message)
    }

    fun perf(
        msg: String?,
        context: String = "",
        path: String = getCallerPath(),
        line: Int = getCallerLine(),
    ) {
        val message = formatLog(LogLevel.PERF, msg, context, path, line)
        Log.v(tag, message)
        saver.save(message)
    }
}

interface LogSaver {
    fun save(message: String)
}

class LogSaverImpl(
    private val sessionFilePath: String,
) : LogSaver {
    private val queue: CoroutineScope by lazy {
        CoroutineScope(newSingleThreadDispatcher(ServiceQueue.LOG.name) + SupervisorJob())
    }
    private var currentLogFilePath: String? = null

    init {
        if (sessionFilePath.isNotEmpty()) {
            val sessionFile = File(sessionFilePath)
            currentLogFilePath = sessionFile.absolutePath
            sessionFile.parentFile?.mkdirs()
            log("Log session initialized with file path: '$sessionFilePath'")

            // Apply retention in background without wiping useful startup context.
            CoroutineScope(Dispatchers.IO).launch {
                enforceLogRetentionLimits(activeLogFile = sessionFile)
            }
        }
    }

    override fun save(message: String) {
        if (sessionFilePath.isEmpty()) return

        queue.launch {
            runCatching {
                val sanitized = message.replace("\n", " ")
                val bytes = "$sanitized\n".toByteArray()
                val file = getWritableLogFile(
                    sessionFilePath = sessionFilePath,
                    currentLogFilePath = currentLogFilePath,
                    nextWriteBytes = bytes.size,
                )
                val previousLogFilePath = currentLogFilePath
                currentLogFilePath = file.absolutePath

                FileOutputStream(file, true).use { stream ->
                    stream.write(bytes)
                }

                if (previousLogFilePath != file.absolutePath) {
                    CoroutineScope(Dispatchers.IO).launch {
                        enforceLogRetentionLimits(activeLogFile = file)
                    }
                }
            }.onFailure {
                Log.e(APP, "Error writing to log file: '$sessionFilePath'", it)
            }
        }
    }

    private fun log(
        message: String,
        level: LogLevel = LogLevel.INFO,
        androidLog: (String, String) -> Unit = { tag, msg -> Log.i(tag, msg) },
    ) {
        val formatted = formatLog(level, message, TAG, getCallerPath(), getCallerLine())
        androidLog(APP, formatted)
        save(formatted)
    }

    private fun enforceLogRetentionLimits(
        maxTotalSizeBytes: Long = MAX_LOG_RETENTION_BYTES,
        maxAgeDays: Long = MAX_LOG_RETENTION_DAYS,
        activeLogFile: File? = null,
    ) {
        log("Enforcing log retention limits…", LogLevel.VERBOSE, Log::v)
        val logDir = runCatching { Env.logDir }.getOrNull() ?: return

        enforceLogRetentionLimits(
            logDir = logDir,
            maxTotalSizeBytes = maxTotalSizeBytes,
            maxAgeDays = maxAgeDays,
            activeLogFile = activeLogFile,
            deleteLogFile = ::deleteLogFile,
        )

        log("Enforced log retention limits.", LogLevel.VERBOSE, Log::v)
    }

    private fun deleteLogFile(file: File): Boolean {
        return runCatching {
            log("Deleting old log file: '${file.name}'", LogLevel.DEBUG, Log::d)
            file.delete()
        }.onFailure {
            log("Failed to delete old log file: '${file.name}'", LogLevel.WARN, Log::w)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "LogSaver"
    }
}

internal fun getWritableLogFile(
    sessionFilePath: String,
    currentLogFilePath: String?,
    nextWriteBytes: Int,
    logDir: File? = null,
    maxLogFileBytes: Long = MAX_LOG_FILE_BYTES,
): File {
    val sessionFile = File(sessionFilePath)
    val sessionDir = sessionFile.parentFile ?: logDir ?: Env.logDir
    val sessionName = sessionFile.nameWithoutExtension

    sessionDir.mkdirs()

    val currentFile = currentLogFilePath
        ?.let(::File)
        ?.takeIf {
            it.parentFile?.absolutePath == sessionDir.absolutePath &&
                it.nameWithoutExtension.startsWith(sessionName)
        }

    if (currentFile != null && currentFile.hasRoomFor(nextWriteBytes, maxLogFileBytes)) {
        return currentFile
    }

    var file = currentFile ?: sessionFile
    var part = file.logPartNumber(sessionFile)
    while (file.exists() && file.length() + nextWriteBytes > maxLogFileBytes) {
        part += 1
        file = sessionDir.resolve("$sessionName.part_${part.toString().padStart(3, '0')}.$LOG_FILE_EXTENSION")
    }

    return file
}

@Suppress("LongParameterList")
internal fun enforceLogRetentionLimits(
    logDir: File,
    maxTotalSizeBytes: Long = MAX_LOG_RETENTION_BYTES,
    maxAgeDays: Long = MAX_LOG_RETENTION_DAYS,
    activeLogFile: File? = null,
    nowMillis: Long = System.currentTimeMillis(),
    deleteLogFile: (File) -> Boolean = { it.delete() },
) {
    val logFiles = logDir
        .listFiles { file -> file.extension == LOG_FILE_EXTENSION }
        ?: return

    val activeLogFilePath = activeLogFile?.absolutePath
    val removableFiles = logFiles.filterNot { it.absolutePath == activeLogFilePath }
    val oldestAllowedModifiedAt = nowMillis - maxAgeDays * MILLIS_IN_DAY

    removableFiles
        .sortedBy { it.lastModified() }
        .forEach { file ->
            if (file.lastModified() < oldestAllowedModifiedAt) {
                deleteLogFile(file)
            }
        }

    var totalSize = logDir
        .listFiles { file -> file.extension == LOG_FILE_EXTENSION }
        ?.sumOf { it.length() }
        ?: return

    logDir
        .listFiles { file -> file.extension == LOG_FILE_EXTENSION }
        ?.filterNot { it.absolutePath == activeLogFilePath }
        ?.sortedBy { it.lastModified() }
        ?.forEach { file ->
            if (totalSize <= maxTotalSizeBytes) return@forEach

            val fileSize = file.length()
            if (deleteLogFile(file)) {
                totalSize -= fileSize
            }
        }
}

private fun File.hasRoomFor(nextWriteBytes: Int, maxLogFileBytes: Long): Boolean {
    return !exists() || length() + nextWriteBytes <= maxLogFileBytes
}

private fun File.logPartNumber(sessionFile: File): Int {
    if (absolutePath == sessionFile.absolutePath) return 1
    return nameWithoutExtension.substringAfter(".part_", "1").toIntOrNull() ?: 1
}

private fun buildSessionLogFilePath(): String {
    val logDir = Env.logDir
    val timestamp = utcDateFormatterOf(DatePattern.LOG_FILE).format(Date())
    val path = logDir.resolve("bitkit_$timestamp.log").path
    return path
}

private fun formatLog(level: LogLevel, msg: String?, context: String, path: String, line: Int): String {
    val timestamp = utcDateFormatterOf(DatePattern.LOG_LINE).format(Date())
    val message = msg?.trim().orEmpty()
    val contextString = if (context.isNotEmpty()) " - $context" else ""
    val location = "[$path:$line]"
    return String.format(
        Locale.US,
        "%s %-7s %-36s %s%s",
        timestamp,
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
    slowThreshold: Duration = DEFAULT_SLOW_OPERATION_THRESHOLD,
    block: () -> T,
): T {
    val timedValue = measureTimedValue(block)
    if (timedValue.duration >= slowThreshold) {
        Logger.perf("Measured '$label' in '${timedValue.duration}'", context = context)
    }
    return timedValue.value
}
