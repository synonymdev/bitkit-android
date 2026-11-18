package to.bitkit.ext

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class BackupTimestampTest {
    private val time = Instant.parse("2023-01-02T11:45:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test
    fun `toBackupTimestamp follows the US pattern`() {
        assertEquals("Jan 2, 2023, 11:45", time.toBackupTimestamp(Locale.US, utc))
    }

    @Test
    fun `toBackupTimestamp follows the UK pattern`() {
        assertEquals("2 Jan 2023, 11:45", time.toBackupTimestamp(Locale.UK, utc))
    }

    @Test
    fun `toBackupTimestamp follows the German pattern with a localized month`() {
        assertEquals("2. Jan. 2023, 11:45", time.toBackupTimestamp(Locale.GERMANY, utc))
    }

    @Test
    fun `toBackupTimestamp keeps a 24-hour time`() {
        val evening = Instant.parse("2023-01-02T23:45:00Z").toEpochMilli()

        assertEquals("Jan 2, 2023, 23:45", evening.toBackupTimestamp(Locale.US, utc))
    }
}
