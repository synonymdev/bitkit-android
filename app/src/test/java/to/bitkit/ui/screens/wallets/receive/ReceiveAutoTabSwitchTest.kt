package to.bitkit.ui.screens.wallets.receive

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiveAutoTabSwitchTest {

    @Test
    fun `switches when lightning becomes available and user has not selected a tab`() {
        assertTrue(decide())
    }

    @Test
    fun `does not switch when auto is already selected`() {
        assertFalse(decide(selectedTab = ReceiveTab.AUTO))
    }

    @Test
    fun `does not switch after user selected a tab`() {
        assertFalse(decide(hasUserSelectedTab = true))
    }

    @Test
    fun `does not switch when lightning invoice cannot be created`() {
        assertFalse(decide(canCreateLightningInvoice = false))
    }

    @Test
    fun `does not switch when cjit invoice exists`() {
        assertFalse(decide(cjitInvoice = "lnbcrt1cjit"))
    }

    @Test
    fun `switches when cjit invoice is empty`() {
        assertTrue(decide(cjitInvoice = ""))
    }

    @Test
    fun `does not switch when an initial tab was requested`() {
        assertFalse(decide(initialTab = ReceiveTab.SPENDING))
    }

    private fun decide(
        selectedTab: ReceiveTab = ReceiveTab.SAVINGS,
        hasUserSelectedTab: Boolean = false,
        canCreateLightningInvoice: Boolean = true,
        cjitInvoice: String? = null,
        initialTab: ReceiveTab? = null,
    ) = shouldAutoSwitchToAuto(
        selectedTab = selectedTab,
        hasUserSelectedTab = hasUserSelectedTab,
        canCreateLightningInvoice = canCreateLightningInvoice,
        cjitInvoice = cjitInvoice,
        initialTab = initialTab,
    )
}
