package to.bitkit.models

import com.synonym.bitkitcore.FeeRates
import org.junit.Test
import kotlin.test.assertEquals

class FeeRateTest {
    private val feeRates = FeeRates(fast = 10u, mid = 5u, slow = 2u)

    @Test
    fun `custom rate at or above the fast tier confirms like fast`() {
        assertEquals(FeeRate.FAST, FeeRate.forConfirmation(TransactionSpeed.Custom(10u), feeRates))
        assertEquals(FeeRate.FAST, FeeRate.forConfirmation(TransactionSpeed.Custom(40u), feeRates))
    }

    @Test
    fun `custom rate between tiers confirms like the tier below it`() {
        assertEquals(FeeRate.NORMAL, FeeRate.forConfirmation(TransactionSpeed.Custom(7u), feeRates))
        assertEquals(FeeRate.SLOW, FeeRate.forConfirmation(TransactionSpeed.Custom(3u), feeRates))
    }

    @Test
    fun `custom rate below the slow tier confirms like minimum`() {
        assertEquals(FeeRate.MINIMUM, FeeRate.forConfirmation(TransactionSpeed.Custom(1u), feeRates))
    }

    @Test
    fun `custom rate without fee tiers keeps the custom description`() {
        assertEquals(FeeRate.CUSTOM, FeeRate.forConfirmation(TransactionSpeed.Custom(4u), null))
        assertEquals(FeeRate.CUSTOM, FeeRate.forConfirmation(TransactionSpeed.Custom(0u), feeRates))
    }

    @Test
    fun `preset speeds keep their own tier`() {
        assertEquals(FeeRate.FAST, FeeRate.forConfirmation(TransactionSpeed.Fast, feeRates))
        assertEquals(FeeRate.NORMAL, FeeRate.forConfirmation(TransactionSpeed.Medium, feeRates))
        assertEquals(FeeRate.SLOW, FeeRate.forConfirmation(TransactionSpeed.Slow, feeRates))
    }
}
