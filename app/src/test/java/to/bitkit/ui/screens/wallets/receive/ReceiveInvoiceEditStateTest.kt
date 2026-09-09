package to.bitkit.ui.screens.wallets.receive

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiveInvoiceEditStateTest {
    @Test
    fun `hardware wallet receive starts on trezor before editing`() {
        val state = ReceiveInvoiceEditState()

        assertEquals(ReceiveTab.HARDWARE, state.initialTab(hardwareWalletId = "trezor-1"))
    }

    @Test
    fun `software edit returns to source tab when hardware wallet is active`() {
        val state = ReceiveInvoiceEditState()

        state.beginSoftwareEdit(ReceiveTab.SAVINGS)

        assertEquals(ReceiveTab.SAVINGS, state.initialTab(hardwareWalletId = "trezor-1"))
        assertFalse(state.isHardwareInvoice)
    }

    @Test
    fun `hardware edit returns to trezor tab`() {
        val state = ReceiveInvoiceEditState()

        state.beginHardwareEdit()

        assertEquals(ReceiveTab.HARDWARE, state.initialTab(hardwareWalletId = null))
        assertTrue(state.isHardwareInvoice)
    }

    @Test
    fun `normal receive has no initial tab before editing`() {
        val state = ReceiveInvoiceEditState()

        assertNull(state.initialTab(hardwareWalletId = null))
    }
}
