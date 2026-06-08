package to.bitkit.data.widgets

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PriceServiceTest {

    @Test
    fun `formatPriceValue excludes currency symbol`() {
        val formatted = formatPriceValue(price = 81_444.12, locale = Locale.US)

        assertEquals("81,444", formatted)
        assertFalse(formatted.contains('$'))
    }

    @Test
    fun `formatPriceValue uses provided locale grouping`() {
        val formatted = formatPriceValue(price = 81_444.12, locale = Locale.GERMANY)

        assertEquals("81.444", formatted)
    }

    @Test
    fun `formatPriceValue keeps fractional precision by price range`() {
        assertEquals("81,444", formatPriceValue(price = 81_444.12, locale = Locale.US))
        assertEquals("814.12", formatPriceValue(price = 814.12, locale = Locale.US))
        assertEquals("0.123457", formatPriceValue(price = 0.1234567, locale = Locale.US))
    }
}
