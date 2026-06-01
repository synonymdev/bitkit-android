package to.bitkit.utils

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSaverImplTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `getWritableLogFile increments from full current part`() {
        val logDir = tempFolder.newFolder()
        val sessionFile = logDir.logFile("bitkit_2026-05-22_12-15-00.log", size = 10)
        val currentPart = logDir.logFile("bitkit_2026-05-22_12-15-00.part_002.log", size = 10)

        val file = getWritableLogFile(
            sessionFilePath = sessionFile.absolutePath,
            currentLogFilePath = currentPart.absolutePath,
            nextWriteBytes = 1,
            logDir = logDir,
            maxLogFileBytes = 10,
        )

        assertEquals("bitkit_2026-05-22_12-15-00.part_003.log", file.name)
    }

    @Test
    fun `getWritableLogFile recovers from stale current path`() {
        val logDir = tempFolder.newFolder()
        val staleDir = tempFolder.newFolder()
        val sessionFile = logDir.logFile("bitkit_2026-05-22_12-15-00.log", size = 10)
        val stalePart = staleDir.resolve("bitkit_2026-05-22_12-15-00.part_009.log")

        val file = getWritableLogFile(
            sessionFilePath = sessionFile.absolutePath,
            currentLogFilePath = stalePart.absolutePath,
            nextWriteBytes = 1,
            logDir = logDir,
            maxLogFileBytes = 10,
        )

        assertEquals("bitkit_2026-05-22_12-15-00.part_002.log", file.name)
    }

    @Test
    fun `enforceLogRetentionLimits deletes old and oversized logs without deleting active file`() {
        val nowMillis = 1_000_000_000L
        val logDir = tempFolder.newFolder()
        val activeFile = logDir.logFile("active.log", size = 80, lastModifiedAt = nowMillis - 3.daysInMillis())
        val expiredFile = logDir.logFile("expired.log", size = 5, lastModifiedAt = nowMillis - 3.daysInMillis())
        val oldestFile = logDir.logFile("oldest.log", size = 50, lastModifiedAt = nowMillis - 1_000)
        val newestFile = logDir.logFile("newest.log", size = 20, lastModifiedAt = nowMillis)

        enforceLogRetentionLimits(
            logDir = logDir,
            maxTotalSizeBytes = 100,
            maxAgeDays = 1,
            activeLogFile = activeFile,
            nowMillis = nowMillis,
        )

        assertTrue(activeFile.exists())
        assertFalse(expiredFile.exists())
        assertFalse(oldestFile.exists())
        assertTrue(newestFile.exists())
    }

    private fun File.logFile(
        name: String,
        size: Int,
        lastModifiedAt: Long = 1_000,
    ): File {
        return resolve(name).apply {
            writeBytes(ByteArray(size) { it.toByte() })
            assertTrue(setLastModified(lastModifiedAt))
        }
    }

    private fun Int.daysInMillis(): Long = this * 24L * 60L * 60L * 1000L
}
