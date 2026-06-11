package to.bitkit.repositories

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.bitkit.ext.fromBase64
import to.bitkit.utils.LogSource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LogsRepoTest {
    private companion object {
        const val SUPPORT_SNAPSHOT = """{"generatedAt":"test"}"""
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `toLogFile parses display name source and part suffix`() {
        val file = tempFolder.newFile("bitkit_2026-05-22_12-15-00.part_002.log")

        val logFile = file.toLogFile()

        assertEquals("Bitkit Log: 2026-05-22 12-15-00 part 002", logFile.displayName)
        assertEquals(LogSource.Bitkit, logFile.source)
        assertEquals(file, logFile.file)
    }

    @Test
    fun `toLogFile falls back to unknown for unmatched file name`() {
        val file = tempFolder.newFile("notes.txt")

        val logFile = file.toLogFile()

        assertEquals("Unknown Log: notes.txt", logFile.displayName)
        assertEquals(LogSource.Unknown, logFile.source)
    }

    @Test
    fun `archive name exposes upload base name and local file name`() {
        val archiveName = createLogsArchiveName(
            prefix = "bitkit_logs",
            timestamp = "1779326719473",
        )

        assertEquals("bitkit_logs_1779326719473", archiveName.baseName)
        assertEquals("bitkit_logs_1779326719473.zip", archiveName.fileName)
    }

    @Test
    fun `archive name collapses repeated zip suffixes`() {
        val archiveName = LogsArchiveName("bitkit_logs_1779326719473.zip.zip".withoutZipExtension())

        assertEquals("bitkit_logs_1779326719473", archiveName.baseName)
        assertEquals("bitkit_logs_1779326719473.zip", archiveName.fileName)
    }

    @Test
    fun `archive name collapses mixed case zip suffixes`() {
        val archiveName = LogsArchiveName("bitkit_support_logs_2026-05-22_12-15-00.ZIP.zip".withoutZipExtension())

        assertEquals("bitkit_support_logs_2026-05-22_12-15-00", archiveName.baseName)
        assertEquals("bitkit_support_logs_2026-05-22_12-15-00.zip", archiveName.fileName)
    }

    @Test
    fun `createZipBase64 drops oldest logs first to fit limit`() {
        val newestFile = createLogFile("bitkit_2026-05-22_12-15-00.log", seed = 1)
        val middleFile = createLogFile("bitkit_2026-05-22_12-14-00.log", seed = 2)
        val oldestFile = createLogFile("bitkit_2026-05-22_12-13-00.log", seed = 3)
        val newestLog = newestFile.bitkitLogFile()
        val middleLog = middleFile.bitkitLogFile()
        val oldestLog = oldestFile.bitkitLogFile()
        val maxEncodedBytes = createZipBase64(listOf(newestLog), null) { SUPPORT_SNAPSHOT }.length

        val encoded = createZipBase64(
            logFiles = listOf(newestLog, middleLog, oldestLog),
            maxEncodedBytes = maxEncodedBytes,
            supportSnapshot = { SUPPORT_SNAPSHOT },
        )

        assertEquals(
            listOf(
                "support_snapshot.json",
                "bitkit/bitkit_2026-05-22_12-15-00.log",
            ),
            encoded.zipEntryNames(),
        )
    }

    @Test
    fun `createZipBase64 keeps snapshot when logs are empty and over limit`() {
        val encoded = createZipBase64(
            logFiles = emptyList(),
            maxEncodedBytes = 1,
            supportSnapshot = { SUPPORT_SNAPSHOT },
        )

        assertEquals(listOf("support_snapshot.json"), encoded.zipEntryNames())
    }

    private fun createLogFile(name: String, seed: Int): File {
        return tempFolder.newFile(name).apply {
            writeBytes(Random(seed).nextBytes(16 * 1024))
        }
    }

    private fun File.bitkitLogFile() = LogFile(
        displayName = name,
        file = this,
        source = LogSource.Bitkit,
    )

    private fun String.zipEntryNames(): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(fromBase64())).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                names += entry.name
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return names
    }
}
