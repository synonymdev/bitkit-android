package to.bitkit.repositories

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.data.ChatwootHttpClient
import to.bitkit.di.BgDispatcher
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.fromBase64
import to.bitkit.ext.getEnumValueOf
import to.bitkit.ext.toBase64
import to.bitkit.models.ChatwootMessage
import to.bitkit.utils.Logger
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogsRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val chatwootHttpClient: ChatwootHttpClient,
) {
    suspend fun postQuestion(email: String, message: String): Result<Unit> = withContext(bgDispatcher) {
        return@withContext try {
            val logsBase64 = zipLogs().getOrDefault("")
            val logsFileName = "bitkit_logs_${System.currentTimeMillis()}.zip"

            chatwootHttpClient.postQuestion(
                message = ChatwootMessage(
                    email = email,
                    message = message,
                    platform = Env.platform,
                    version = Env.version,
                    logs = logsBase64,
                    logsFileName = logsFileName,
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(msg = e.message, e = e, context = TAG)
            Result.failure(e)
        }
    }

    /** Lists log files sorted by newest first */
    suspend fun getLogs(): Result<List<LogFile>> = withContext(bgDispatcher) {
        try {
            val logDir = File(Env.logDir)
            if (!logDir.exists()) {
                return@withContext Result.failure(Exception("Logs dir not found"))
            }

            val logFiles = logDir
                .listFiles { file -> file.extension == "log" }
                ?.map { file ->
                    val fileName = file.name
                    val components = fileName.split("_")

                    val serviceName = components.firstOrNull()
                        ?.let { c -> c.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                        ?: LogSource.Unknown.name
                    val timestamp = if (components.size >= 3) components[components.size - 2] else ""
                    val displayName = "$serviceName Log: $timestamp"

                    LogFile(
                        displayName = displayName,
                        file = file,
                        source = getEnumValueOf<LogSource>(serviceName).getOrDefault(LogSource.Unknown),
                    )
                }
                ?.sortedByDescending { it.file.lastModified() }
                ?: emptyList()

            return@withContext Result.success(logFiles)
        } catch (e: Exception) {
            Logger.error("Failed to load logs", e, context = TAG)
            Result.failure(e)
        }
    }

    suspend fun loadLogContent(logFile: LogFile): Result<List<String>> = withContext(bgDispatcher) {
        try {
            if (!logFile.file.exists()) {
                Logger.error("Logs file not found", context = TAG)
                return@withContext Result.failure(Exception("Logs file not found"))
            }

            val lines = mutableListOf<String>()
            BufferedReader(FileReader(logFile.file)).use { reader ->
                reader.forEachLine { line ->
                    lines.add(line.trim())
                }
            }
            return@withContext Result.success(lines)
        } catch (e: Exception) {
            Logger.error("Failed to load log content", e, context = TAG)
            return@withContext Result.failure(e)
        }
    }

    /** Zips and saves the most recent logs returning the content uri */
    suspend fun zipLogsForSharing(
        limit: Int = 20,
        source: LogSource? = null,
    ): Result<Uri> = withContext(bgDispatcher) {
        zipLogs(limit, source).mapCatching { base64String ->
            val file = withContext(ioDispatcher) {
                val tempDir = context.cacheDir.resolve("logs").apply { mkdirs() }

                val zipFileName = "bitkit_logs_${System.currentTimeMillis()}.zip"
                val tempFile = File(tempDir, zipFileName)

                // Convert base64 back to bytes and write to file
                val zipBytes = base64String.fromBase64()
                tempFile.writeBytes(zipBytes)
                return@withContext tempFile
            }
            val contentUri = FileProvider.getUriForFile(context, Env.FILE_PROVIDER_AUTHORITY, file)
            if (contentUri == null) error("Failed to create content uri")

            return@mapCatching contentUri
        }.onFailure {
            Logger.error("Error preparing logs for sharing", it)
        }
    }


    /** Zips the most recent logs and returns base64 of zip file */
    suspend fun zipLogs(
        limit: Int = 20,
        source: LogSource? = null,
    ): Result<String> = withContext(bgDispatcher) {
        return@withContext try {
            val logsResult = getLogs().onFailure {
                return@withContext Result.failure(it)
            }

            val allLogs = logsResult.getOrDefault(emptyList()).filter { it.source != LogSource.Unknown }
            val logsToZip = if (source != null) {
                allLogs.filter { it.source == source }.take(limit)
            } else {
                // Group by source and take most recent from each
                allLogs.groupBy { it.source }
                    .values
                    .flatMap { logs ->
                        val sourcesCount = LogSource.entries.filter { it != LogSource.Unknown }.size
                        logs.take(limit / sourcesCount.coerceAtLeast(1))
                    }
                    .take(limit)
            }

            if (logsToZip.isEmpty()) {
                return@withContext Result.failure(Exception("No log files found"))
            }

            val base64String = createZipBase64(logsToZip)
            Result.success(base64String)
        } catch (e: Exception) {
            Logger.error("Failed to zip logs", e, context = TAG)
            Result.failure(e)
        }
    }

    private fun createZipBase64(logFiles: List<LogFile>): String {
        val zipBytes = ByteArrayOutputStream().use { byteArrayOut ->
            ZipOutputStream(byteArrayOut).use { zipOut ->
                logFiles.forEach { logFile ->
                    if (logFile.file.exists()) {
                        val zipEntry = ZipEntry("${logFile.source.name.lowercase()}/${logFile.fileName}")
                        zipOut.putNextEntry(zipEntry)

                        FileInputStream(logFile.file).use { fileIn ->
                            fileIn.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            }
            byteArrayOut.toByteArray()
        }

        return zipBytes.toBase64()
    }

    private companion object {
        const val TAG = "SupportRepo"
    }
}

data class LogFile(
    val displayName: String,
    val file: File,
    val source: LogSource,
) {
    val fileName: String get() = file.name
}

enum class LogSource { Ldk, Bitkit, Unknown }
