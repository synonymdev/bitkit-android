package to.bitkit.services

import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.TransactionDetails
import org.lightningdevkit.ldknode.TxInput
import org.lightningdevkit.ldknode.TxOutput
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.create
import to.bitkit.models.WalletScope
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrimaryWalletNodeServiceTest : BaseUnitTest() {
    private val lightningService = mock<LightningService>()
    private val node = mock<Node>()
    private val service = PrimaryWalletNodeService(lightningService, mock())

    @Before
    fun setUp() {
        whenever(lightningService.currentWalletIndex).thenReturn(0)
        whenever(lightningService.node).thenReturn(node)
        whenever { lightningService.callOnchainSend<ConfirmationStatus?>(any()) }.doSuspendableAnswer {
            it.getArgument<suspend () -> ConfirmationStatus?>(0).invoke()
        }
    }

    private fun activity() = OnchainActivity.create(
        walletId = WalletScope.default,
        id = "external",
        txId = "external",
        txType = PaymentType.SENT,
        value = 5_000uL,
        fee = 2_344uL,
        address = "recipient",
        timestamp = 100uL,
    )

    private fun transaction() = TransactionDetails(
        amountSats = 0L,
        inputs = listOf(TxInput("parent", 0u, "", emptyList(), 0u)),
        outputs = listOf(TxOutput("", null, "recipient", 5_000L, 0u)),
    )

    @Test
    fun `external confirmation requires exact recipient and full prevout fee proof`() = test {
        val expected = activity()
        val transaction = transaction()
        val parent = TransactionDetails(0L, emptyList(), listOf(TxOutput("", null, null, 7_344L, 0u)))
        val status = ConfirmationStatus.Confirmed("block", 6u, 150uL)
        whenever(node.getTransactionDetails(expected.txId)).thenReturn(transaction)
        whenever(node.getTransactionDetails("parent")).thenReturn(parent)
        whenever(node.fetchTransactionStatus(expected.txId)).thenReturn(status)

        assertEquals(status, service.verifiedPaymentConfirmation(expected))
        verify(node).fetchTransactionStatus(expected.txId)
    }

    @Test
    fun `wrong recipient or fee cannot change confirmation even when backend has a status`() = test {
        val expected = activity()
        whenever(node.getTransactionDetails(expected.txId)).thenReturn(transaction())
        whenever(node.getTransactionDetails("parent")).thenReturn(
            TransactionDetails(0L, emptyList(), listOf(TxOutput("", null, null, 7_344L, 0u)))
        )

        assertNull(service.verifiedPaymentConfirmation(expected.copy(address = "someone-else")))
        assertNull(service.verifiedPaymentConfirmation(expected.copy(value = 4_000uL)))
        assertNull(service.verifiedPaymentConfirmation(expected.copy(fee = 1uL)))
        verify(node, never()).fetchTransactionStatus(any())
    }

    @Test
    fun `hardware receipts and internal funding do not enter external payment confirmation lookup`() = test {
        assertNull(service.verifiedPaymentConfirmation(activity().copy(walletId = "hardware")))
        assertNull(service.verifiedPaymentConfirmation(activity().copy(isTransfer = true)))
        verify(node, never()).getTransactionDetails(any())
        verify(node, never()).fetchTransactionStatus(any())
    }
}
