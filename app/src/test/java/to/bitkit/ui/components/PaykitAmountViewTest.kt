package to.bitkit.ui.components

import org.junit.Test
import to.bitkit.models.formatCurrency
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals

class PaykitAmountViewTest {
    @Test
    fun `usd display preserves cents and matches fiat separators across regions`() {
        listOf(
            0uL to "0.00",
            1uL to "0.01",
            100uL to "1.00",
            15050uL to "150.50",
            123456uL to "1,234.56",
            ULong.MAX_VALUE to "184,467,440,737,095,516.15",
        ).forEach { (cents, expected) ->
            listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("nl-NL"), Locale.FRANCE).forEach { locale ->
                assertEquals(expected, usdDisplayAmount(cents, locale), locale.toString())
                assertEquals(
                    BigDecimal(cents.toString()).movePointLeft(2).formatCurrency(locale = locale),
                    usdDisplayAmount(cents, locale),
                )
            }
        }
    }
}
