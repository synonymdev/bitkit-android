package to.bitkit.ext

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StringExtTest : BaseUnitTest() {

    private lateinit var defaultLocale: Locale

    @Before
    fun setUpLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDownLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `formatPlural selects the one branch`() {
        val pattern = "Retrying in {interval, plural, one {# minute} other {# minutes}}."

        val result = pattern.formatPlural(mapOf("interval" to 1))

        assertEquals("Retrying in 1 minute.", result)
    }

    @Test
    fun `formatPlural selects the other branch`() {
        val pattern = "Retrying in {interval, plural, one {# minute} other {# minutes}}."

        val result = pattern.formatPlural(mapOf("interval" to 5))

        assertEquals("Retrying in 5 minutes.", result)
    }

    @Test
    fun `formatPlural returns the raw pattern instead of crashing on malformed input`() {
        val malformed = "Retrying in {interval, plural, one {# minute} other {# minutes}."

        val result = malformed.formatPlural(mapOf("interval" to 1))

        assertEquals(malformed, result)
    }
}
