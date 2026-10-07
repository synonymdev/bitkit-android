package to.bitkit.ui.screens.wallets.usdt

import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDepositAddress
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.UsdtDepositOrder
import com.synonym.bitkitcore.UsdtDepositPage
import com.synonym.bitkitcore.UsdtException
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.repositories.UsdtDepositRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsdtDepositViewModelTest : BaseUnitTest() {
    private val repo: UsdtDepositRepo = mock()

    @Test
    fun `editing a rejected amount clears its error and limits`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf(UsdtDepositNetwork.POLYGON)))
        val error = UsdtException.DepositAmountOutOfRange("200", "1200000000")
        whenever(repo.prepareDeposit(UsdtDepositNetwork.POLYGON, "1")).thenReturn(Result.failure(error))
        val model = UsdtDepositViewModel(repo)
        model.selectNetwork(UsdtDepositNetwork.POLYGON)
        model.prepare("1").join()
        assertEquals(R.string.usdt__deposit_amount_out_of_range, model.state.value.error)
        assertEquals(error, model.state.value.amountLimits)
        model.clearAmountError()
        assertNull(model.state.value.error)
        assertNull(model.state.value.amountLimits)
        assertEquals(UsdtDepositNetwork.POLYGON, model.state.value.network)
        assertNull(model.state.value.address)
    }

    @Test
    fun `overlapping history pages replace stale deposits and retain their new offset`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        val first = UsdtDeposit("first", "tron", "USDT", 100u, "tx1", "held", null, null)
        val second = first.copy(id = "second", sourceTx = "tx2")
        val updated = second.copy(status = "completed")
        whenever(repo.depositHistory(0u)).thenReturn(Result.success(UsdtDepositPage(listOf(first, second), 50u)))
        whenever(repo.depositHistory(50u)).thenReturn(Result.success(UsdtDepositPage(listOf(updated), null)))
        whenever(repo.depositDetail("second", 50u)).thenReturn(Result.success(UsdtDepositDetail(updated, null)))
        val model = UsdtDepositViewModel(repo)
        model.history().join()
        model.history(50u).join()
        assertEquals(listOf(first, updated), model.state.value.deposits)
        assertNull(model.state.value.nextOffset)
        model.detail("second")
        assertEquals(50u, model.state.value.offset)
        assertEquals(updated, model.state.value.detail?.deposit)
    }

    @Test
    fun `linked order status and error supersede deposit state together`() {
        val deposit = UsdtDeposit("deposit", "tron", "USDT", 100u, "tx", "held", "standing_route_unavailable", null)
        val completed = UsdtDepositOrder("completed", 100u, 90u, "destination-tx", null, null)
        val cases = listOf(
            Triple(completed, R.string.usdt__confirmed, null),
            Triple(completed.copy(status = "refunded"), R.string.usdt__deposit_refunded, null),
            Triple(null, R.string.usdt__deposit_needs_attention, deposit.code),
            Triple(completed.copy(status = "unknown"), R.string.usdt__deposit_needs_attention, null),
            Triple(completed.copy(status = "pending"), R.string.usdt__pending, null),
            Triple(completed.copy(status = "processing"), R.string.usdt__pending, null),
            Triple(completed.copy(status = "refund_requested"), R.string.usdt__deposit_refunding, null),
            Triple(
                completed.copy(status = "failed", code = "order_error"),
                R.string.usdt__deposit_needs_attention,
                "order_error"
            ),
        )
        cases.forEach { (order, expectedStatus, expectedCode) ->
            val detail = UsdtDepositDetail(deposit, order)
            assertEquals(expectedStatus, detail.statusResource())
            assertEquals(expectedCode, detail.statusCode)
        }
    }

    @Test
    fun `deposit source names resolve to every supported network`() {
        UsdtDepositNetwork.entries.forEach { network ->
            assertEquals(network, network.name.lowercase().depositNetwork())
        }
        assertNull("unsupported".depositNetwork())
    }

    @Test
    fun `receive request locks network and navigation until cancellation`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf(UsdtDepositNetwork.TRON)))
        val result = CompletableDeferred<Result<UsdtDepositAddress>>()
        whenever(repo.prepareDeposit(UsdtDepositNetwork.TRON, "100")).doSuspendableAnswer { result.await() }
        val model = UsdtDepositViewModel(repo)
        model.selectNetwork(UsdtDepositNetwork.TRON)
        val request = model.prepare("100")
        assertTrue(model.state.value.busy)
        model.selectNetwork(UsdtDepositNetwork.ETHEREUM)
        model.prepare("100").join()
        assertEquals(UsdtDepositNetwork.TRON, model.state.value.network)
        assertTrue(model.back())
        verify(repo, times(1)).prepareDeposit(UsdtDepositNetwork.TRON, "100")
        request.cancelAndJoin()
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.address)
        assertNull(model.state.value.error)
        assertFalse(model.back())
    }

    @Test
    fun `provider refund acknowledgment stays a request and keeps history intact`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        whenever(repo.depositHistory(50u)).thenReturn(Result.success(UsdtDepositPage(emptyList(), null)))
        whenever(repo.refundDeposit("deposit", 50u, "source-address", UsdtDepositNetwork.TRON))
            .thenReturn(Result.success(Unit))
        val deposit = UsdtDeposit("deposit", "tron", "USDT", 100u, "source-tx", "held", null, null)
        whenever(repo.depositDetail("deposit", 50u)).thenReturn(Result.success(UsdtDepositDetail(deposit, null)))
        val model = UsdtDepositViewModel(repo)
        model.history(50u).join()
        model.detail("deposit")
        model.refund("deposit", 50u, "source-address", UsdtDepositNetwork.TRON).join()
        assertEquals(50u, model.state.value.offset)
        assertTrue(model.state.value.history)
        assertEquals(R.string.usdt__deposit_refund_requested, model.state.value.message)
        assertEquals("refund_requested", model.state.value.detail?.deposit?.status)
        model.refund("deposit", 50u, "source-address", UsdtDepositNetwork.TRON).join()
        verify(repo, times(1)).refundDeposit("deposit", 50u, "source-address", UsdtDepositNetwork.TRON)
        assertFalse(model.state.value.busy)
        assertTrue(model.back())
        assertTrue(model.back())
        assertFalse(model.state.value.history)
    }
}
