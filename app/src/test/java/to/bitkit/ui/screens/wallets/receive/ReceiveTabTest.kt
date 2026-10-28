package to.bitkit.ui.screens.wallets.receive

import org.junit.Test
import kotlin.test.assertEquals

class ReceiveTabTest {
    @Test
    fun `bitcoin and lightning are available without liquidity setup`() {
        assertEquals(listOf(ReceiveTab.AUTO, ReceiveTab.SPENDING), visibleReceiveTabs(false, false))
    }

    @Test
    fun `hardware and usdt remain distinct assets`() {
        assertEquals(
            listOf(ReceiveTab.HARDWARE, ReceiveTab.AUTO, ReceiveTab.SPENDING, ReceiveTab.USDT),
            visibleReceiveTabs(true, true),
        )
    }
}
