package to.bitkit.repositories

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import to.bitkit.data.ChatwootHttpClient
import to.bitkit.di.BgDispatcher
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.DatePattern
import to.bitkit.ext.fromBase64
import to.bitkit.ext.getEnumValueOf
import to.bitkit.ext.toBase64
import to.bitkit.ext.utcDateFormatterOf
import to.bitkit.models.ChatwootMessage
import to.bitkit.models.NodeLifecycleState
import to.bitkit.utils.LogSource
import to.bitkit.utils.Logger
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import to.bitkit.di.json as appJson

@Singleton
class LogsRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val chatwootHttpClient: ChatwootHttpClient,
    private val lightningRepo: LightningRepo,
) {
    suspend fun postQuestion(email: String, message: String): Result<Unit> = withContext(bgDispatcher) {
        runCatching {
            val logsBase64 = zipLogs(maxEncodedBytes = MAX_SUPPORT_UPLOAD_BASE64_BYTES).getOrDefault("")
            val logsArchiveBaseName = currentLogsArchiveName(SUPPORT_LOGS_ARCHIVE_PREFIX).baseName

            chatwootHttpClient.postQuestion(
                message = ChatwootMessage(
                    email = email,
                    message = message,
                    platform = Env.platform,
                    version = Env.version,
                    logs = logsBase64,
                    logsFileName = logsArchiveBaseName,
                )
            )
        }.onFailure {
            Logger.error("Failed to post support question", it, context = TAG)
        }
    }

    /** Lists log files sorted by newest first */
    @Suppress("NestedBlockDepth")
    suspend fun getLogs(): Result<List<LogFile>> = withContext(bgDispatcher) {
        runCatching {
            val logDir = Env.logDir

            val logFiles = logDir
                .listFiles { file -> file.extension == "log" }
                ?.map { it.toLogFile() }
                ?.sortedByDescending { it.file.lastModified() }
                ?: emptyList()

            return@runCatching logFiles
        }.onFailure {
            Logger.error("Failed to load logs", it, context = TAG)
        }
    }

    suspend fun loadLogContent(logFile: LogFile): Result<List<String>> = withContext(bgDispatcher) {
        runCatching {
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

            return@runCatching lines
        }.onFailure {
            Logger.error("Failed to load log content", it, context = TAG)
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

                val zipFileName = currentLogsArchiveName().fileName
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
            Logger.error("Failed to prepare logs for sharing", it, context = TAG)
        }
    }

    /** Zips the most recent logs and returns base64 of zip file */
    suspend fun zipLogs(
        limit: Int = 20,
        source: LogSource? = null,
        maxEncodedBytes: Int? = null,
    ): Result<String> = withContext(bgDispatcher) {
        runCatching {
            val logsResult = getLogs().onFailure {
                return@withContext Result.failure(it)
            }

            val allLogs = logsResult.getOrDefault(emptyList()).filter { it.source != LogSource.Unknown }
            val logsToZip = if (source != null) {
                allLogs.filter { it.source == source }.take(limit)
            } else {
                allLogs.take(limit)
            }

            return@runCatching createZipBase64(logsToZip, maxEncodedBytes, ::createSupportSnapshot)
        }.onFailure {
            Logger.error("Failed to zip logs", it, context = TAG)
        }
    }

    private fun createSupportSnapshot(): String {
        val state = lightningRepo.lightningState.value
        val snapshot = SupportSnapshot(
            generatedAt = currentLogTimestamp(),
            platform = Env.platform,
            version = Env.version,
            network = Env.network.name,
            nodeId = state.nodeId,
            lifecycle = state.nodeLifecycleState.supportName(),
            isSyncingWallet = state.isSyncingWallet,
            isGeoBlocked = state.isGeoBlocked,
            lastSuccessfulSyncAt = state.lastSuccessfulSyncAt?.toString(),
            lastSyncError = state.lastSyncError?.javaClass?.simpleName,
            blockHeight = state.nodeStatus?.currentBestBlock?.height?.toString(),
            blockHash = state.nodeStatus?.currentBestBlock?.blockHash,
            latestRgsSnapshotTimestamp = state.nodeStatus?.latestRgsSnapshotTimestamp?.toString(),
            peers = state.peers.map {
                SupportPeerSnapshot(
                    nodeId = it.nodeId,
                    address = it.address,
                    isConnected = it.isConnected,
                    isPersisted = it.isPersisted,
                )
            },
            channels = state.channels.map {
                SupportChannelSnapshot(
                    channelId = it.channelId,
                    counterpartyNodeId = it.counterpartyNodeId,
                    isChannelReady = it.isChannelReady,
                    isUsable = it.isUsable,
                    isAnnounced = it.isAnnounced,
                    channelValueSats = it.channelValueSats.toString(),
                    outboundCapacityMsat = it.outboundCapacityMsat.toString(),
                    inboundCapacityMsat = it.inboundCapacityMsat.toString(),
                )
            },
            balances = state.balances?.let {
                SupportBalanceSnapshot(
                    totalOnchainBalanceSats = it.totalOnchainBalanceSats.toString(),
                    spendableOnchainBalanceSats = it.spendableOnchainBalanceSats.toString(),
                    totalAnchorChannelsReserveSats = it.totalAnchorChannelsReserveSats.toString(),
                    totalLightningBalanceSats = it.totalLightningBalanceSats.toString(),
                    lightningBalancesCount = it.lightningBalances.size,
                    pendingChannelClosureBalancesCount = it.pendingBalancesFromChannelClosures.size,
                )
            },
        )

        return appJson.encodeToString(snapshot)
    }

    private fun currentLogsArchiveName(prefix: String = LOGS_ARCHIVE_PREFIX): LogsArchiveName {
        return createLogsArchiveName(prefix, currentLogTimestamp())
    }

    private fun currentLogTimestamp(): String {
        return utcDateFormatterOf(DatePattern.LOG_FILE).format(Date())
    }
}

internal fun createZipBase64(
    logFiles: List<LogFile>,
    maxEncodedBytes: Int?,
    supportSnapshot: () -> String,
): String {
    val selectedLogFiles = logFiles.toMutableList()

    while (true) {
        val encoded = createZipBytes(selectedLogFiles, supportSnapshot).toBase64()
        if (maxEncodedBytes == null || encoded.length <= maxEncodedBytes || selectedLogFiles.isEmpty()) {
            Logger.info("Created support logs archive with '${selectedLogFiles.size}' log file(s)", context = TAG)
            return encoded
        }

        selectedLogFiles.removeAt(selectedLogFiles.lastIndex)
    }
}

internal fun createZipBytes(
    logFiles: List<LogFile>,
    supportSnapshot: () -> String,
): ByteArray {
    return ByteArrayOutputStream().use { byteArrayOut ->
        ZipOutputStream(byteArrayOut).use { zipOut ->
            zipOut.writeSupportSnapshot(supportSnapshot())
            logFiles.filter { it.file.exists() }.forEach { logFile ->
                zipOut.writeLogFile(logFile)
            }
        }
        byteArrayOut.toByteArray()
    }
}

private fun ZipOutputStream.writeSupportSnapshot(supportSnapshot: String) {
    putNextEntry(ZipEntry(SUPPORT_SNAPSHOT_FILE_NAME))
    write(supportSnapshot.toByteArray())
    closeEntry()
}

private fun ZipOutputStream.writeLogFile(logFile: LogFile) {
    putNextEntry(ZipEntry("${logFile.source.name.lowercase()}/${logFile.fileName}"))
    FileInputStream(logFile.file).use { fileIn ->
        fileIn.copyTo(this)
    }
    closeEntry()
}

internal fun File.toLogFile(): LogFile {
    val match = LOG_FILE_NAME_REGEX.matchEntire(name)
    val serviceName = match
        ?.groupValues
        ?.getOrNull(1)
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        ?: LogSource.Unknown.name
    val timestamp = match?.groupValues?.getOrNull(2)?.replace("_", " ")
    val part = match?.groupValues?.getOrNull(3)?.ifBlank { null }
    val partSuffix = part?.let { " part $it" }.orEmpty()
    val displayName = if (timestamp != null) {
        "$serviceName Log: $timestamp$partSuffix"
    } else {
        "$serviceName Log: $name"
    }

    return LogFile(
        displayName = displayName,
        file = this,
        source = getEnumValueOf<LogSource>(serviceName).getOrDefault(LogSource.Unknown),
    )
}

data class LogFile(
    val displayName: String,
    val file: File,
    val source: LogSource,
) {
    val fileName: String get() = file.name
}

internal data class LogsArchiveName(
    val baseName: String,
) {
    val fileName: String get() = "$baseName$ZIP_EXTENSION"
}

internal fun createLogsArchiveName(prefix: String, timestamp: String): LogsArchiveName {
    return LogsArchiveName("${prefix}_$timestamp".withoutZipExtension())
}

internal fun String.withoutZipExtension(): String {
    var name = this
    while (name.endsWith(ZIP_EXTENSION, ignoreCase = true)) {
        name = name.dropLast(ZIP_EXTENSION.length)
    }
    return name
}

private fun NodeLifecycleState.supportName(): String = when (this) {
    is NodeLifecycleState.Stopped -> "Stopped"
    is NodeLifecycleState.Starting -> "Starting"
    is NodeLifecycleState.Running -> "Running"
    is NodeLifecycleState.Stopping -> "Stopping"
    is NodeLifecycleState.Initializing -> "Initializing"
    is NodeLifecycleState.ErrorStarting -> "ErrorStarting"
}

@Serializable
private data class SupportSnapshot(
    val generatedAt: String,
    val platform: String,
    val version: String,
    val network: String,
    val nodeId: String,
    val lifecycle: String,
    val isSyncingWallet: Boolean,
    val isGeoBlocked: Boolean,
    val lastSuccessfulSyncAt: String?,
    val lastSyncError: String?,
    val blockHeight: String?,
    val blockHash: String?,
    val latestRgsSnapshotTimestamp: String?,
    val peers: List<SupportPeerSnapshot>,
    val channels: List<SupportChannelSnapshot>,
    val balances: SupportBalanceSnapshot?,
)

@Serializable
private data class SupportPeerSnapshot(
    val nodeId: String,
    val address: String,
    val isConnected: Boolean,
    val isPersisted: Boolean,
)

@Serializable
private data class SupportChannelSnapshot(
    val channelId: String,
    val counterpartyNodeId: String,
    val isChannelReady: Boolean,
    val isUsable: Boolean,
    val isAnnounced: Boolean,
    val channelValueSats: String,
    val outboundCapacityMsat: String,
    val inboundCapacityMsat: String,
)

@Serializable
private data class SupportBalanceSnapshot(
    val totalOnchainBalanceSats: String,
    val spendableOnchainBalanceSats: String,
    val totalAnchorChannelsReserveSats: String,
    val totalLightningBalanceSats: String,
    val lightningBalancesCount: Int,
    val pendingChannelClosureBalancesCount: Int,
)

private const val TAG = "LogsRepo"
private const val LOGS_ARCHIVE_PREFIX = "bitkit_logs"
private const val MAX_SUPPORT_UPLOAD_BASE64_BYTES = 900 * 1024
private const val SUPPORT_LOGS_ARCHIVE_PREFIX = "bitkit_support_logs"
private const val SUPPORT_SNAPSHOT_FILE_NAME = "support_snapshot.json"
private const val ZIP_EXTENSION = ".zip"
private val LOG_FILE_NAME_REGEX = Regex(
    "^([A-Za-z]+)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})(?:\\.part_(\\d{3}))?\\.log$"
)
