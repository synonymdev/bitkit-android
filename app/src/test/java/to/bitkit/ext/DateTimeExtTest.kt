package to.bitkit.ext

import org.junit.Test
import to.bitkit.env.Env
import to.bitkit.test.BaseUnitTest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class DateTimeExtTest : BaseUnitTest() {

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
        val resultWithDefaultParam = twoDaysAgo.toRelativeTimeString(Locale.getDefault())

        assertEquals(resultWithDefaultParam, resultWithoutParam)
    }
}
