package to.bitkit.ui.screens.wallets.activity

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import org.junit.Test
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsdtActivityFilterTest {
    @Test
    fun `search matches full asset name or recipient and transaction fragments`() {
        val transfer = UsdtTransfer(
            sender = null,
            id = "received",
            txHash = "0xAbCd5678",
            userOperationHash = null,
            bridgeGuid = null,
            orchestra = null,
            recipient = "0xEf901234",
            destination = UsdtDestination.ARBITRUM,
            amount = 1_000_000u,
            receivedAmount = 1_000_000u,
            fee = null,
            isIncoming = true,
            status = UsdtTransferStatus.CONFIRMED,
            timestamp = 1u,
        )
        for (query in listOf("", "USDT", "usdt", "eF90", "aBcD")) {
            assertTrue(transfer.matchesActivityFilter(ActivityTab.ALL, true, query, null, null), query)
        }
        for (query in listOf("u", "us", "t", "BTC", "no match")) {
            assertFalse(transfer.matchesActivityFilter(ActivityTab.ALL, true, query, null, null), query)
        }
        val unconfirmed = transfer.copy(txHash = null)
        assertTrue(unconfirmed.matchesActivityFilter(ActivityTab.ALL, true, "eF90", null, null))
        assertFalse(unconfirmed.matchesActivityFilter(ActivityTab.ALL, true, "aBcD", null, null))
    }
}
