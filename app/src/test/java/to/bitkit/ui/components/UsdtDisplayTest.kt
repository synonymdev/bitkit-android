package to.bitkit.ui.components

import org.junit.Test
import to.bitkit.models.PaykitExchangeRate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsdtDisplayTest {
    @Test
    fun `amount input uses the local separator and preserves precision and trailing zeros`() {
        assertEquals("150,50", usdtInputAmount("150.50", Locale.GERMANY))
        assertEquals("0,000001", usdtInputAmount("0.000001", Locale.GERMANY))
        assertEquals("150.50", usdtInputAmount("150.50", Locale.US))
        assertEquals("1,", usdtInputAmount("1.", Locale.GERMANY))
    }

    @Test
    fun `exact amounts preserve token precision and locale separators`() {
        assertEquals("4.996", usdtExactAmount(4_996_000u, Locale.US))
        assertEquals("1,234567", usdtExactAmount(1_234_567u, Locale.GERMANY))
        assertEquals("3,50", usdtExactAmount(3_500_000u, Locale.GERMANY))
    }

    @Test
    fun `bitcoin equivalent requires a fresh rate and fits display bounds`() {
        val now = 1_800_000_000_000L
        val rate = PaykitExchangeRate("100000", now)
        assertEquals(3500L, usdtDisplaySats("3.5", rate, now))
        assertEquals(0L, usdtDisplaySats("0.000001", rate, now))
        assertNull(usdtDisplaySats("3.5", null, now))
        assertNull(usdtDisplaySats("3.5", rate.copy(timestampMillis = now - 601_000), now))
        assertNull(usdtDisplaySats("-1", rate, now))
        assertNull(usdtDisplaySats("999999999999999999999999", rate, now))
    }

    @Test
    fun `overview amounts round to cents without hiding small balances`() {
        listOf(
            0uL to "0.00",
            1uL to "<0.01",
            9999uL to "<0.01",
            10000uL to "0.01",
            10000000uL to "10.00",
            123456789uL to "123.46",
            1025000uL to "1.03",
            1234567890uL to "1,234.57",
        ).forEach { (amount, expected) ->
            assertEquals(expected, usdtOverviewAmount(amount, Locale.US), "amount: $amount")
        }
        assertEquals("<0,01", usdtOverviewAmount(1uL, Locale.GERMANY))
        assertEquals("1.234,57", usdtOverviewAmount(1234567890uL, Locale.GERMANY))
    }
}
