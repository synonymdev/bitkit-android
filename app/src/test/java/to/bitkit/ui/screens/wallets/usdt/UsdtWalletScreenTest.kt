package to.bitkit.ui.screens.wallets.usdt

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsdtWalletScreenTest {
    private val recipient = "0x1111111111111111111111111111111111111111"

    @Test
    fun `initial payment with recipient and positive amount is quoted`() {
        assertTrue(shouldQuoteInitialUsdtPayment(recipient, "0.010001"))
        assertTrue(shouldQuoteInitialUsdtPayment(recipient, "5"))
    }

    @Test
    fun `initial payment without a payable amount is not quoted`() {
        assertFalse(shouldQuoteInitialUsdtPayment(recipient, ""))
        assertFalse(shouldQuoteInitialUsdtPayment(recipient, "0"))
        assertFalse(shouldQuoteInitialUsdtPayment(recipient, "abc"))
        assertFalse(shouldQuoteInitialUsdtPayment(recipient, "0.0000001"))
    }

    @Test
    fun `initial payment without recipient is not quoted`() {
        assertFalse(shouldQuoteInitialUsdtPayment("", "5"))
    }

    @Test
    fun `a positive requested amount is fixed and zero is not`() {
        assertTrue(isUsdtAmountFixed(requestAmount = "0.010001", wasFixed = false, isSameRecipient = false))
        assertFalse(isUsdtAmountFixed(requestAmount = "0", wasFixed = true, isSameRecipient = true))
        assertFalse(isPositiveUsdtAmount("0"))
    }

    @Test
    fun `continuing with the same recipient keeps a fixed amount`() {
        assertTrue(isUsdtAmountFixed(requestAmount = null, wasFixed = true, isSameRecipient = true))
        assertFalse(isUsdtAmountFixed(requestAmount = null, wasFixed = true, isSameRecipient = false))
        assertFalse(isUsdtAmountFixed(requestAmount = null, wasFixed = false, isSameRecipient = true))
    }
}
