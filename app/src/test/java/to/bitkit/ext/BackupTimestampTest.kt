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
        assertEquals("Jan 2, 2023, 11:45", time.toBackupTimestamp(is24Hour = true, Locale.US, utc))
    }

    @Test
    fun `toBackupTimestamp follows the UK pattern`() {
        assertEquals("2 Jan 2023, 11:45", time.toBackupTimestamp(is24Hour = true, Locale.UK, utc))
    }

    @Test
    fun `toBackupTimestamp follows the German pattern with a localized month`() {
        assertEquals("2. Jan. 2023, 11:45", time.toBackupTimestamp(is24Hour = true, Locale.GERMANY, utc))
    }

    @Test
    fun `toBackupTimestamp follows a 24-hour device setting`() {
        val evening = Instant.parse("2023-01-02T23:45:00Z").toEpochMilli()

        assertEquals("Jan 2, 2023, 23:45", evening.toBackupTimestamp(is24Hour = true, Locale.US, utc))
    }

    @Test
    fun `toBackupTimestamp follows a 12-hour device setting`() {
        val evening = Instant.parse("2023-01-02T23:45:00Z").toEpochMilli()

        val text = evening.toBackupTimestamp(is24Hour = false, Locale.US, utc)

        // CLDR versions differ in the space before PM.
        assertEquals("Jan 2, 2023, 11:45 PM", text.replace('\u202F', ' ').replace('\u00A0', ' '))
    }
}
