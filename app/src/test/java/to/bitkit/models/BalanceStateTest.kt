package to.bitkit.models

import org.junit.Test
import kotlin.test.assertEquals

class BalanceStateTest {

    @Test
    fun `totalSats sums onchain and lightning`() {
        val state = BalanceState(totalOnchainSats = 100uL, totalLightningSats = 50uL)
        assertEquals(150uL, state.totalSats)
    }

    @Test
    fun `totalWithHardwareSats adds the hardware balance on top of the total`() {
        val state = BalanceState(
            totalOnchainSats = 100uL,
            totalLightningSats = 50uL,
            totalHardwareSats = 25uL,
        )
        assertEquals(175uL, state.totalWithHardwareSats)
    }

    @Test
    fun `totalWithHardwareSats equals totalSats when there is no hardware balance`() {
        val state = BalanceState(totalOnchainSats = 100uL, totalLightningSats = 50uL)
        assertEquals(state.totalSats, state.totalWithHardwareSats)
    }
}
