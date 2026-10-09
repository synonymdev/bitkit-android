package to.bitkit.ui.components

import org.junit.Test
import to.bitkit.models.PaykitExchangeRate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsdtDisplayTest {
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
            0uL to "0",
            1uL to "<0.01",
            9999uL to "<0.01",
            10000uL to "0.01",
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
