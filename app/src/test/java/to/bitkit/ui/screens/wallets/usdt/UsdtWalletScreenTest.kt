package to.bitkit.ui.screens.wallets.usdt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private val otherRecipient = "0x2222222222222222222222222222222222222222"

    @Test
    fun `a positive requested amount is fixed and zero is not`() {
        assertEquals(recipient, fixedUsdtAmountRecipient(recipient, requestAmount = "0.010001", fixedRecipient = null))
        assertNull(fixedUsdtAmountRecipient(recipient, requestAmount = "0", fixedRecipient = recipient))
        assertFalse(isPositiveUsdtAmount("0"))
    }

    @Test
    fun `continuing with the recipient that fixed the amount keeps it`() {
        assertEquals(recipient, fixedUsdtAmountRecipient(recipient, requestAmount = null, fixedRecipient = recipient))
        val checksummed = recipient.uppercase().replace("0X", "0x")
        assertEquals(recipient, fixedUsdtAmountRecipient(checksummed, requestAmount = null, fixedRecipient = recipient))
    }

    @Test
    fun `another recipient or no earlier fixed amount leaves the amount editable`() {
        assertNull(fixedUsdtAmountRecipient(otherRecipient, requestAmount = null, fixedRecipient = recipient))
        assertNull(fixedUsdtAmountRecipient(recipient, requestAmount = null, fixedRecipient = null))
    }
}
