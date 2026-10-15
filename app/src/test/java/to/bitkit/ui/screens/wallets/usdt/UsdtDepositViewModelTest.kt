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
import kotlinx.coroutines.launch
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.repositories.UsdtDepositRepo
import to.bitkit.test.BaseUnitTest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsdtDepositViewModelTest : BaseUnitTest() {
    private val repo: UsdtDepositRepo = mock()

    @Test
    fun `USD cents respect locale grouping and decimal separators`() {
        assertEquals("$10,000.00", "1000000".usdCents(Locale.US))
        assertEquals("$10.000,00", "1000000".usdCents(Locale.GERMANY))
        assertEquals("$0,05", "5".usdCents(Locale.GERMANY))
    }

    @Test
    fun `background detail refresh leaves editing enabled and updates refund eligibility`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "tx", "held", null, null)
        whenever(repo.depositDetail("deposit", 0u)).thenReturn(Result.success(UsdtDepositDetail(deposit, null)))
        val model = UsdtDepositViewModel(repo)
        model.detail("deposit")
        val result = CompletableDeferred<Result<UsdtDepositDetail>>()
        whenever(repo.depositDetail("deposit", 0u)).doSuspendableAnswer { result.await() }

        val refresh = launch { model.refreshDetail("deposit") }
        assertFalse(model.state.value.busy)
        assertTrue(requireNotNull(model.state.value.detail).canRefund)
        result.complete(Result.success(UsdtDepositDetail(deposit.copy(status = "completed"), null)))
        refresh.join()
        assertFalse(model.state.value.busy)
        assertFalse(requireNotNull(model.state.value.detail).canRefund)
    }

    @Test
    fun `background detail refresh cannot reopen a deposit after back`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "tx", "held", null, null)
        val detail = UsdtDepositDetail(deposit, null)
        whenever(repo.depositDetail("deposit", 0u)).thenReturn(Result.success(detail))
        val model = UsdtDepositViewModel(repo)
        model.detail("deposit")
        val result = CompletableDeferred<Result<UsdtDepositDetail>>()
        whenever(repo.depositDetail("deposit", 0u)).doSuspendableAnswer { result.await() }

        val refresh = launch { model.refreshDetail("deposit") }
        assertTrue(model.back())
        result.complete(Result.success(detail))
        refresh.join()
        assertNull(model.state.value.detail)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `background detail refresh cannot undo an acknowledged refund or duplicate its request`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "tx", "held", null, null)
        val detail = UsdtDepositDetail(deposit, null)
        whenever(repo.depositDetail("deposit", 0u)).thenReturn(Result.success(detail))
        whenever(repo.refundDeposit("deposit", 0u, "address", UsdtDepositNetwork.BSC)).thenReturn(Result.success(Unit))
        val model = UsdtDepositViewModel(repo)
        model.detail("deposit")
        val result = CompletableDeferred<Result<UsdtDepositDetail>>()
        whenever(repo.depositDetail("deposit", 0u)).doSuspendableAnswer { result.await() }

        val refresh = launch { model.refreshDetail("deposit") }
        model.refund("deposit", 0u, "address", UsdtDepositNetwork.BSC).join()
        result.complete(Result.success(detail))
        refresh.join()
        assertEquals("refund_requested", model.state.value.detail?.deposit?.status)
        assertEquals(R.string.usdt__deposit_refund_requested, model.state.value.message)
        model.refund("deposit", 0u, "address", UsdtDepositNetwork.BSC).join()
        verify(repo, times(1)).refundDeposit("deposit", 0u, "address", UsdtDepositNetwork.BSC)
    }

    @Test
    fun `bridge cost rejection shows no deposit address or invented minimum`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf(UsdtDepositNetwork.TRON)))
        whenever(repo.prepareDeposit(UsdtDepositNetwork.TRON, "3.5"))
            .thenReturn(Result.failure(UsdtException.BridgeAmountTooSmall()))
        val model = UsdtDepositViewModel(repo)
        model.selectNetwork(UsdtDepositNetwork.TRON)
        model.prepare("3.5").join()
        assertEquals(R.string.usdt__error_bridge_amount, model.state.value.error)
        assertNull(model.state.value.amountLimits)
        assertNull(model.state.value.address)
        assertFalse(model.state.value.busy)
        model.clearAmountError()
        assertNull(model.state.value.error)
    }

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
    fun `deposit status distinguishes refund progress from actionable errors`() {
        val cases = listOf(
            Triple("completed", "standing_route_unavailable", R.string.usdt__confirmed),
            Triple("refunded", "xchain_amount_too_small", R.string.usdt__deposit_refunded),
            Triple("refund_requested", "standing_refund_pending", R.string.usdt__deposit_refunding),
            Triple("refunding", "standing_refund_pending", R.string.usdt__deposit_refunding),
            Triple(
                "refund_requested",
                "standing_tron_refund_requires_operator",
                R.string.usdt__deposit_needs_attention,
            ),
            Triple("refunding", "refund_error", R.string.usdt__deposit_needs_attention),
            Triple("funded", null, R.string.usdt__pending),
            Triple("confirming", null, R.string.usdt__pending),
            Triple("swapping", null, R.string.usdt__pending),
            Triple("bridging", null, R.string.usdt__pending),
            Triple("delivering", null, R.string.usdt__pending),
            Triple("swapping", "order_error", R.string.usdt__deposit_needs_attention),
            Triple("pending", "standing_route_unavailable", R.string.usdt__deposit_needs_attention),
            Triple("below_minimum", "xchain_amount_too_small", R.string.usdt__deposit_needs_attention),
        )
        cases.forEach { (status, code, expected) ->
            val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "tx", status, code, null)
            val order = UsdtDepositOrder(status, 100u, 90u, null, null, code)
            listOf(null, order).forEach { linkedOrder ->
                val detail = UsdtDepositDetail(deposit, linkedOrder)
                assertEquals(expected, detail.statusResource(), status)
                assertEquals(code, detail.statusCode, status)
            }
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
    fun `refund eligibility follows supported asset network and current order`() {
        val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "tx", "held", null, null)
        val detail = UsdtDepositDetail(deposit, null)
        assertTrue(detail.canRefund)
        listOf("completed", "refunded", "refunding", "refund_requested").forEach { status ->
            assertFalse(detail.copy(deposit = deposit.copy(status = status)).canRefund)
            assertFalse(detail.copy(order = UsdtDepositOrder(status, 100u, null, null, null, null)).canRefund)
        }
        assertFalse(detail.copy(deposit = deposit.copy(asset = "USDC")).canRefund)
        assertFalse(detail.copy(deposit = deposit.copy(network = "unknown")).canRefund)
    }

    @Test
    fun `returning to history shows the refreshed order status and refund acknowledgment`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "tx", "held", "stale", null)
        val order = UsdtDepositOrder("delivering", 100u, null, null, null, null)
        whenever(repo.depositHistory(0u)).thenReturn(Result.success(UsdtDepositPage(listOf(deposit), null)))
        whenever(repo.depositDetail("deposit", 0u)).thenReturn(Result.success(UsdtDepositDetail(deposit, order)))
        whenever(repo.refundDeposit("deposit", 0u, "address", UsdtDepositNetwork.BSC)).thenReturn(Result.success(Unit))
        val model = UsdtDepositViewModel(repo)
        model.history().join()
        model.detail("deposit")
        assertEquals("delivering", model.state.value.deposits.single().status)
        assertNull(model.state.value.deposits.single().code)
        model.refund("deposit", 0u, "address", UsdtDepositNetwork.BSC).join()
        assertEquals("refund_requested", model.state.value.deposits.single().status)
        model.back()
        assertEquals(
            R.string.usdt__deposit_refunding,
            depositStatus(
                model.state.value.deposits.single().status,
                model.state.value.deposits.single().code,
            )
        )
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

    @Test
    fun `completed refund clears pending acknowledgment after refresh`() = test {
        whenever(repo.depositNetworks()).thenReturn(Result.success(persistentListOf()))
        whenever(repo.refundDeposit("deposit", 0u, "source-address", UsdtDepositNetwork.BSC))
            .thenReturn(Result.success(Unit))
        val deposit = UsdtDeposit("deposit", "bsc", "USDT", 100u, "source-tx", "held", "xchain_amount_too_small", null)
        val pending = deposit.copy(status = "refund_requested", code = "standing_refund_pending")
        val refunded = deposit.copy(status = "refunded", refundTx = "refund-tx")
        whenever(repo.depositDetail("deposit", 0u)).thenReturn(
            Result.success(UsdtDepositDetail(deposit, null)),
            Result.success(UsdtDepositDetail(pending, null)),
            Result.success(UsdtDepositDetail(refunded, null)),
        )
        val model = UsdtDepositViewModel(repo)
        model.detail("deposit")
        model.refund("deposit", 0u, "source-address", UsdtDepositNetwork.BSC).join()
        model.detail("deposit")
        assertEquals(R.string.usdt__deposit_refund_requested, model.state.value.message)
        assertEquals(R.string.usdt__deposit_refunding, model.state.value.detail?.statusResource())
        model.detail("deposit")
        assertNull(model.state.value.message)
        assertEquals(R.string.usdt__deposit_refunded, model.state.value.detail?.statusResource())
        assertEquals("refund-tx", model.state.value.detail?.deposit?.refundTx)
    }
}
