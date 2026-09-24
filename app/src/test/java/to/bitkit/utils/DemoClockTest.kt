package to.bitkit.utils

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class DemoClockTest {
    private val date = Instant.parse("2027-01-15T08:00:00Z")

    @After
    fun tearDown() {
        DemoClock.setOffsetDays(0)
    }

    @Test
    fun `offset is off by default`() {
        assertEquals(date, DemoClock.subscriptionDate(date, offsetDays = 0, isAvailable = true))
    }

    @Test
    fun `offset moves the subscription date by whole days`() {
        assertEquals(date + 31.days, DemoClock.subscriptionDate(date, offsetDays = 31, isAvailable = true))
    }

    @Test
    fun `offset is ignored when unavailable`() {
        assertEquals(date, DemoClock.subscriptionDate(date, offsetDays = 31, isAvailable = false))
    }

    @Test
    fun `offset is clamped to the supported range`() {
        DemoClock.setOffsetDays(-5)
        assertEquals(0, DemoClock.offsetDays)

        DemoClock.setOffsetDays(10_000)
        assertEquals(DemoClock.OFFSET_DAYS_RANGE.last, DemoClock.offsetDays)
    }

    @Test
    fun `subscription clock follows the base clock and the current offset`() {
        val base = object : Clock {
            override fun now(): Instant = date
        }
        val subscriptionClock = DemoClock.subscriptionClock(base)

        DemoClock.setOffsetDays(7)

        assertEquals(DemoClock.subscriptionDate(date, offsetDays = 7), subscriptionClock.now())
    }
}
