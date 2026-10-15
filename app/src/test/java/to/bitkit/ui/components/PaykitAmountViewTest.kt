package to.bitkit.ui.components

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class PaykitAmountViewTest {
    @Test
    fun `usd display preserves cents and uses locale grouping`() {
        listOf(
            Triple(0uL, "0.00", "0,00"),
            Triple(1uL, "0.01", "0,01"),
            Triple(100uL, "1.00", "1,00"),
            Triple(15050uL, "150.50", "150,50"),
            Triple(123456uL, "1,234.56", "1.234,56"),
            Triple(ULong.MAX_VALUE, "184,467,440,737,095,516.15", "184.467.440.737.095.516,15"),
        ).forEach { (cents, us, de) ->
            assertEquals(us, usdDisplayAmount(cents, Locale.US), "cents: $cents")
            assertEquals(de, usdDisplayAmount(cents, Locale.GERMANY), "cents: $cents")
        }
    }
}
