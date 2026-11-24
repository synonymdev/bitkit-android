package to.bitkit.ui.components

import org.junit.Test
import kotlin.test.assertEquals

class LightningChannelTest {
    @Test
    fun `channel fills its spending track by local balance over capacity`() {
        assertEquals(0.25f, spendingFillFraction(capacity = 400_000, localBalance = 100_000))
    }

    @Test
    fun `spark balance fills half its spending track`() {
        assertEquals(0.5f, spendingFillFraction(capacity = 580_000, localBalance = 580_000, isSpark = true))
    }

    @Test
    fun `empty balance leaves the spending track unfilled`() {
        assertEquals(0f, spendingFillFraction(capacity = 0, localBalance = 0, isSpark = true))
        assertEquals(0f, spendingFillFraction(capacity = 500_000, localBalance = 0))
    }
}
