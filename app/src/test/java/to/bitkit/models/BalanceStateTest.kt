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
    fun `totalHardwareSats sums all hardware wallet balances`() {
        val state = BalanceState(
            hardwareWallets = listOf(
                HwWalletBalance(id = "dev1", sats = 25uL),
                HwWalletBalance(id = "dev2", sats = 75uL),
            ),
        )
        assertEquals(100uL, state.totalHardwareSats)
    }

    @Test
    fun `totalWithHardwareSats adds the hardware balance on top of the total`() {
        val state = BalanceState(
            totalOnchainSats = 100uL,
            totalLightningSats = 50uL,
            hardwareWallets = listOf(HwWalletBalance(id = "dev1", sats = 25uL)),
        )
        assertEquals(175uL, state.totalWithHardwareSats)
    }

    @Test
    fun `totalWithHardwareSats equals totalSats when there is no hardware balance`() {
        val state = BalanceState(totalOnchainSats = 100uL, totalLightningSats = 50uL)
        assertEquals(state.totalSats, state.totalWithHardwareSats)
    }

    @Test
    fun `totalWithHardwareSats saturates instead of overflowing`() {
        val state = BalanceState(
            totalLightningSats = ULong.MAX_VALUE,
            hardwareWallets = listOf(HwWalletBalance(id = "dev1", sats = 10uL)),
        )
        assertEquals(ULong.MAX_VALUE, state.totalWithHardwareSats)
    }
}
