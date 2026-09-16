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

        assertEquals(ReceiveTab.TREZOR, state.initialTab(hardwareWalletId = "trezor-1"))
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

        assertEquals(ReceiveTab.TREZOR, state.initialTab(hardwareWalletId = null))
        assertTrue(state.isHardwareInvoice)
    }

    @Test
    fun `normal receive has no initial tab before editing`() {
        val state = ReceiveInvoiceEditState()

        assertNull(state.initialTab(hardwareWalletId = null))
    }

    @Test
    fun `receive CJIT session keeps invoice when edit is cancelled`() {
        val state = ReceiveCjitSessionState()
        val entry = cjitEntryDetails(invoice = "first")

        state.onCjitCreated(entry)
        state.onCjitConfirmed("first")

        assertEquals("first", state.cjitInvoice)
        assertEquals(entry, state.entryDetails)
    }

    @Test
    fun `receive CJIT session clears stale invoice when edit is applied`() {
        val state = ReceiveCjitSessionState()
        val entry = cjitEntryDetails(invoice = "first")

        state.onCjitCreated(entry)
        state.onCjitConfirmed("first")
        state.clear()

        assertNull(state.cjitInvoice)
        assertNull(state.entryDetails)
    }

    @Test
    fun `receive CJIT session keeps old invoice until fresh CJIT is confirmed`() {
        val state = ReceiveCjitSessionState()
        val first = cjitEntryDetails(invoice = "first")
        val second = cjitEntryDetails(invoice = "second")

        state.onCjitCreated(first)
        state.onCjitConfirmed("first")
        state.onCjitCreated(second)

        assertEquals("first", state.cjitInvoice)
        assertEquals(second, state.entryDetails)

        state.onCjitConfirmed("second")

        assertEquals("second", state.cjitInvoice)
        assertEquals(second, state.entryDetails)
    }

    @Test
    fun `receive CJIT session exposes confirmed fresh invoice`() {
        val state = ReceiveCjitSessionState()
        val entry = cjitEntryDetails(invoice = "fresh")

        state.onCjitCreated(entry)
        state.onCjitConfirmed("fresh")

        assertEquals("fresh", state.cjitInvoice)
        assertEquals(entry, state.entryDetails)
    }

    @Test
    fun `receive CJIT session matches confirmed invoice amount`() {
        val state = ReceiveCjitSessionState()
        val entry = cjitEntryDetails(invoice = "fresh", receiveAmountSats = 1_000)

        state.onCjitCreated(entry)

        assertFalse(state.hasConfirmedInvoiceForAmount(1_000uL))

        state.onCjitConfirmed("fresh")

        assertTrue(state.hasConfirmedInvoiceForAmount(1_000uL))
        assertFalse(state.hasConfirmedInvoiceForAmount(2_000uL))
        assertFalse(state.hasConfirmedInvoiceForAmount(null))
    }

    @Test
    fun `receive CJIT session matches confirmed amount when pending quote differs`() {
        val state = ReceiveCjitSessionState()
        val first = cjitEntryDetails(invoice = "first", receiveAmountSats = 1_000)
        val second = cjitEntryDetails(invoice = "second", receiveAmountSats = 2_000)

        state.onCjitCreated(first)
        state.onCjitConfirmed("first")
        state.onCjitCreated(second)

        assertTrue(state.hasConfirmedInvoiceForAmount(1_000uL))
        assertFalse(state.hasConfirmedInvoiceForAmount(2_000uL))
    }

    private fun cjitEntryDetails(
        invoice: String,
        receiveAmountSats: Long = 1_000,
    ) = CjitEntryDetails(
        networkFeeSat = 1,
        serviceFeeSat = 1,
        channelSizeSat = 10_000,
        feeSat = 2,
        receiveAmountSats = receiveAmountSats,
        invoice = invoice,
    )
}
