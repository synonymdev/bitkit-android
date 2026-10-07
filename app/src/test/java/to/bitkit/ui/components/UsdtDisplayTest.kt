package to.bitkit.ui.components

import org.junit.Test
import to.bitkit.models.PaykitExchangeRate
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
    fun `small nonzero balances remain visible`() {
        assertEquals("0", usdtOverviewAmount(0uL))
        assertEquals("<0.01", usdtOverviewAmount(1uL))
        assertEquals("<0.01", usdtOverviewAmount(9999uL))
    }
}
