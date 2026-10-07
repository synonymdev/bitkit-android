package to.bitkit.ui.screens.wallets.usdt

import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositOrder
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtOrchestraTransfer
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsdtSupportDetailsTest {
    @Test
    fun `transfer support includes provider references and transactions`() {
        val transfer = UsdtTransfer(
            id = "local-id",
            txHash = "source-tx",
            userOperationHash = null,
            bridgeGuid = "message-id",
            orchestra = null,
            recipient = "recipient-address",
            destination = UsdtDestination.POLYGON,
            amount = 100u,
            receivedAmount = 90u,
            fee = 10u,
            isIncoming = false,
            status = UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION,
            timestamp = 1u,
        )
        assertTrue(transfer.supportDetails.contains("Bridge: USDT0"))
        assertTrue(transfer.supportDetails.contains("LayerZero message: message-id"))
        assertFalse(transfer.supportDetails.contains("Destination transaction:"))

        val details = transfer.copy(
            bridgeGuid = null,
            orchestra = UsdtOrchestraTransfer("order-id", "funding-address", "destination-tx", "refund-tx", 90u),
        ).supportDetails
        listOf("Orchestra", "Arbitrum One", "Polygon", "order-id", "source-tx", "destination-tx", "refund-tx").forEach {
            assertTrue(details.contains(it), it)
        }
        assertFalse(details.contains("recipient-address"))
        assertFalse(details.contains("funding-address"))
    }

    @Test
    fun `deposit support uses current order status and refund`() {
        val deposit = UsdtDeposit("deposit-id", "tron", "USDT", 100u, "source-tx", "held", "old-code", "deposit-refund")
        val detail = UsdtDepositDetail(deposit, null)
        assertTrue(detail.supportDetails.contains("Status code: old-code"))
        assertTrue(detail.supportDetails.contains("Refund transaction: deposit-refund"))
        val details = detail.copy(
            order = UsdtDepositOrder("refunded", 100u, null, null, "order-refund", null),
        ).supportDetails
        listOf("Orchestra", "tron", "Arbitrum One", "deposit-id", "source-tx", "refunded", "order-refund").forEach {
            assertTrue(details.contains(it), it)
        }
        assertFalse(details.contains("old-code"))
        assertFalse(details.contains("deposit-refund"))
    }
}
