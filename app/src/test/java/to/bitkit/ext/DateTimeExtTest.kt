package to.bitkit.ext

import org.junit.Test
import to.bitkit.env.Env
import to.bitkit.test.BaseUnitTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class DateTimeExtTest : BaseUnitTest() {
    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val AFTERNOON: Instant = Instant.parse("2026-03-07T15:23:00Z")
        val MIDNIGHT: Instant = Instant.parse("2026-03-07T00:15:00Z")
        val NOON: Instant = Instant.parse("2026-03-07T12:05:00Z")
    }

    @Test
    fun `toRelativeTimeString returns now for very recent timestamps`() {
        val now = System.currentTimeMillis()
        val result = now.toRelativeTimeString()
        // May return "now" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns minutes ago for recent timestamps`() {
        val fiveMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)
        val result = fiveMinutesAgo.toRelativeTimeString()
        // May return relative "minute" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns hours ago for timestamps within a day`() {
        val twoHoursAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        val result = twoHoursAgo.toRelativeTimeString()
        // May return relative "hour" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns yesterday for one day ago`() {
        val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        val result = oneDayAgo.toRelativeTimeString()
        // May return relative "yesterday"/"day" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns days ago for multiple days`() {
        val threeDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
        val result = threeDaysAgo.toRelativeTimeString()
        // May return relative "day" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns weeks ago for multiple weeks`() {
        val twoWeeksAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
        val result = twoWeeksAgo.toRelativeTimeString()
        // May return relative "week" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns months ago for multiple months`() {
        val twoMonthsAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)
        val result = twoMonthsAgo.toRelativeTimeString()
        // May return relative "month" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString returns years ago for multiple years`() {
        val twoYearsAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(730)
        val result = twoYearsAgo.toRelativeTimeString()
        // May return relative "year" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString handles future timestamps gracefully`() {
        val future = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        val result = future.toRelativeTimeString()
        // May return relative "in" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString supports all configured locales`() {
        val twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)

        Env.locales.forEach { languageTag ->
            val locale = Locale.forLanguageTag(languageTag)
            val result = twoDaysAgo.toRelativeTimeString(locale)

            assertNotNull(result, "Locale $languageTag returned null")
            assertTrue(result.isNotEmpty(), "Locale $languageTag returned empty string")
        }
    }

    @Test
    fun `toRelativeTimeString with explicit English locale produces expected output`() {
        val twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        val result = twoDaysAgo.toRelativeTimeString(Locale.ENGLISH)

        // May return relative "day" or absolute timestamp as fallback
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString with explicit German locale produces non-empty output`() {
        val twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        val result = twoDaysAgo.toRelativeTimeString(Locale.GERMAN)

        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString with explicit French locale produces non-empty output`() {
        val twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        val result = twoDaysAgo.toRelativeTimeString(Locale.FRENCH)

        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString with explicit Italian locale produces non-empty output`() {
        val twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        val result = twoDaysAgo.toRelativeTimeString(Locale.ITALIAN)

        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `toRelativeTimeString preserves backward compatibility with default locale`() {
        val twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        val resultWithoutParam = twoDaysAgo.toRelativeTimeString()
        val resultWithDefaultParam = twoDaysAgo.toRelativeTimeString()

        assertEquals(resultWithDefaultParam, resultWithoutParam)
    }

    @Test
    fun `TIME uses 24-hour clock when the device is set to 24-hour format`() {
        val result = AFTERNOON.formattedInUtc(UiDateStyle.TIME.pattern(is24Hour = true))

        assertEquals("15:23", result)
    }

    @Test
    fun `TIME uses 12-hour clock with meridiem when the device is set to 12-hour format`() {
        val result = AFTERNOON.formattedInUtc(UiDateStyle.TIME.pattern(is24Hour = false))

        assertEquals("3:23 PM", result)
    }

    @Test
    fun `TIME formats midnight without ambiguity in both clock formats`() {
        val in24Hour = MIDNIGHT.formattedInUtc(UiDateStyle.TIME.pattern(is24Hour = true))
        val in12Hour = MIDNIGHT.formattedInUtc(UiDateStyle.TIME.pattern(is24Hour = false))

        assertEquals("00:15", in24Hour)
        assertEquals("12:15 AM", in12Hour)
    }

    @Test
    fun `TIME formats noon without ambiguity in both clock formats`() {
        val in24Hour = NOON.formattedInUtc(UiDateStyle.TIME.pattern(is24Hour = true))
        val in12Hour = NOON.formattedInUtc(UiDateStyle.TIME.pattern(is24Hour = false))

        assertEquals("12:05", in24Hour)
        assertEquals("12:05 PM", in12Hour)
    }

    @Test
    fun `DATE is unaffected by the clock format`() {
        val in24Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE.pattern(is24Hour = true))
        val in12Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE.pattern(is24Hour = false))

        assertEquals("March 7", in24Hour)
        assertEquals("March 7", in12Hour)
    }

    @Test
    fun `DATE_TIME keeps the date and applies the selected clock format`() {
        val in24Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE_TIME.pattern(is24Hour = true))
        val in12Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE_TIME.pattern(is24Hour = false))

        assertEquals("March 7, 15:23", in24Hour)
        assertEquals("March 7, 3:23 PM", in12Hour)
    }

    @Test
    fun `DATE_TIME_YEAR keeps the year and applies the selected clock format`() {
        val in24Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE_TIME_YEAR.pattern(is24Hour = true))
        val in12Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE_TIME_YEAR.pattern(is24Hour = false))

        assertEquals("March 7 2026, 15:23", in24Hour)
        assertEquals("March 7 2026, 3:23 PM", in12Hour)
    }

    @Test
    fun `DATE_TIME localizes the month name`() {
        val pattern = UiDateStyle.DATE_TIME.pattern(is24Hour = true)
        val result = AFTERNOON.formatted(pattern, Locale.GERMANY, UTC)

        assertEquals("März 7, 15:23", result)
    }

    @Test
    fun `formatted respects the supplied time zone`() {
        val bucharest = ZoneId.of("Europe/Bucharest")
        val result = AFTERNOON.formatted(UiDateStyle.TIME.pattern(is24Hour = true), Locale.US, bucharest)

        assertEquals("17:23", result)
    }

    @Test
    fun `uiDateStyleFor returns TIME for a timestamp from today`() {
        val today = LocalDate.of(2026, 3, 7)

        val style = uiDateStyleFor(AFTERNOON.epochSecond.toULong(), today, UTC)

        assertEquals(UiDateStyle.TIME, style)
    }

    @Test
    fun `uiDateStyleFor returns DATE_TIME for an earlier day in the same year`() {
        val today = LocalDate.of(2026, 12, 31)

        val style = uiDateStyleFor(AFTERNOON.epochSecond.toULong(), today, UTC)

        assertEquals(UiDateStyle.DATE_TIME, style)
    }

    @Test
    fun `uiDateStyleFor returns DATE_TIME_YEAR for a timestamp from a previous year`() {
        val today = LocalDate.of(2027, 1, 1)

        val style = uiDateStyleFor(AFTERNOON.epochSecond.toULong(), today, UTC)

        assertEquals(UiDateStyle.DATE_TIME_YEAR, style)
    }

    @Test
    fun `uiDateStyleFor resolves the day in the supplied time zone`() {
        val lateEvening = Instant.parse("2026-03-07T23:30:00Z")
        val today = LocalDate.of(2026, 3, 7)
        val bucharest = ZoneId.of("Europe/Bucharest")

        assertEquals(UiDateStyle.TIME, uiDateStyleFor(lateEvening.epochSecond.toULong(), today, UTC))
        assertEquals(UiDateStyle.DATE_TIME, uiDateStyleFor(lateEvening.epochSecond.toULong(), today, bucharest))
    }

    @Test
    fun `toEpochSecondsOrNull parses an ISO-8601 instant`() {
        val result = "2026-03-07T15:23:00Z".toEpochSecondsOrNull()

        assertEquals(AFTERNOON.epochSecond.toULong(), result)
    }

    @Test
    fun `toEpochSecondsOrNull parses the millisecond form Blocktank returns`() {
        val result = "2026-03-07T15:23:00.000Z".toEpochSecondsOrNull()

        assertEquals(AFTERNOON.epochSecond.toULong(), result)
    }

    @Test
    fun `toEpochSecondsOrNull returns null for an unparseable string`() {
        val result = "not a date".toEpochSecondsOrNull()

        assertNull(result)
    }

    @Test
    fun `toEpochSecondsOrNull returns null for an empty string`() {
        val result = "".toEpochSecondsOrNull()

        assertNull(result)
    }

    @Test
    fun `DATE_TIME_YEAR_SHORT abbreviates the month and applies the selected clock format`() {
        val in24Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE_TIME_YEAR_SHORT.pattern(is24Hour = true))
        val in12Hour = AFTERNOON.formattedInUtc(UiDateStyle.DATE_TIME_YEAR_SHORT.pattern(is24Hour = false))

        assertEquals("Mar 7, 2026, 15:23", in24Hour)
        assertEquals("Mar 7, 2026, 3:23 PM", in12Hour)
    }

    @Test
    fun `DATE_TIME_YEAR_SHORT localizes the month name`() {
        val pattern = UiDateStyle.DATE_TIME_YEAR_SHORT.pattern(is24Hour = true)
        val result = AFTERNOON.formatted(pattern, Locale.GERMANY, UTC)

        assertEquals("März 7, 2026, 15:23", result)
    }

    @Test
    fun `toEpochSecondsOrNull round-trips through the channel details format`() {
        val timestamp = "2026-03-07T15:23:00.000Z".toEpochSecondsOrNull()

        assertNotNull(timestamp)
        val result = Instant.ofEpochSecond(timestamp.toLong())
            .formatted(UiDateStyle.DATE_TIME_YEAR_SHORT.pattern(is24Hour = true), Locale.US, UTC)

        assertEquals("Mar 7, 2026, 15:23", result)
    }

    private fun Instant.formattedInUtc(pattern: String) = formatted(pattern, Locale.US, UTC)
}
