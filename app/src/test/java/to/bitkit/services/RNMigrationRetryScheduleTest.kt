package to.bitkit.services

import org.junit.Test
import to.bitkit.di.json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RNMigrationRetryScheduleTest {
    @Test
    fun `backoff survives serialization and rejects sync frequency retries`() {
        val schedule = RNMigrationRetrySchedule.afterAttempt(null, 1_000_000)
        for (seconds in 0..50 step 10) {
            assertFalse(schedule.isDue(1_000_000 + seconds.seconds.inWholeMilliseconds))
        }
        val reloaded = json.decodeFromString<RNMigrationRetrySchedule>(json.encodeToString(schedule))
        assertEquals(schedule, reloaded)
        assertFalse(reloaded.isDue(1_059_999))
        assertTrue(reloaded.isDue(1_060_000))
        val second = RNMigrationRetrySchedule.afterAttempt(reloaded, 1_060_000)
        assertFalse(second.isDue(1_179_999))
        assertTrue(second.isDue(1_180_000))
    }

    @Test
    fun `retries double up to an hourly cap`() {
        var now = 1_000_000L
        var schedule: RNMigrationRetrySchedule? = null
        for (seconds in listOf(60, 120, 240, 480, 960, 1920, 3600, 3600)) {
            schedule = RNMigrationRetrySchedule.afterAttempt(schedule, now)
            val delay = seconds.seconds.inWholeMilliseconds
            assertEquals(delay, schedule.delayMillis)
            assertFalse(schedule.isDue(now + delay - 1))
            now += delay
            assertTrue(schedule.isDue(now))
        }
    }

    @Test
    fun `clock rollback allows a new attempt instead of starving recovery`() {
        val schedule = RNMigrationRetrySchedule.afterAttempt(null, 1_000_000)
        assertTrue(schedule.isDue(999_999))
        val restarted = RNMigrationRetrySchedule.afterAttempt(schedule, 999_999)
        assertFalse(restarted.isDue(999_999))
        assertTrue(restarted.isDue(1_119_999))
    }
}
