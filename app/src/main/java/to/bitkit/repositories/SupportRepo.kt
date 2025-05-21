package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.BuildConfig
import to.bitkit.data.ChatwootHttpClient
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.ChatwootMessage
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.LogFile
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupportRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val chatwootHttpClient: ChatwootHttpClient

) {

    suspend fun postQuestion(email: String, message: String): Result<Unit> = withContext(bgDispatcher) {
        return@withContext try {

            val lastLog = getLogs().getOrNull()?.lastOrNull()

            val logsList = if (lastLog == null) {
                Logger.warn("lastLog null", context = TAG)
                emptyList()
            } else {
                loadLogContent(lastLog).getOrElse { emptyList() }
            }.joinToString()

            chatwootHttpClient.postQuestion(
                message = ChatwootMessage(
                    email = email,
                    message = message,
                    platform = "${Env.PLATFORM} ${Env.androidSDKVersion}",
                    version = "${BuildConfig.VERSION_NAME} ${BuildConfig.VERSION_CODE}",
                    logs = logsList,
                    logsFileName = lastLog?.fileName.orEmpty()
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(msg = e.message, e = e, context = TAG)
            Result.failure(e)
        }
    }

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
                        ?: "Unknown"
                    val timestamp = if (components.size >= 3) components[components.size - 2] else ""
                    val displayName = "$serviceName Log: $timestamp"

                    LogFile(
                        displayName = displayName,
                        file = file,
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

    private companion object {
        const val TAG = "SupportRepo"
    }
}
