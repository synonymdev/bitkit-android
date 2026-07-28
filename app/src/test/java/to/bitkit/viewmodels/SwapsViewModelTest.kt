package to.bitkit.viewmodels

import com.synonym.bitkitcore.BoltzNetwork
import com.synonym.bitkitcore.BoltzSwap
import com.synonym.bitkitcore.BoltzSwapStatus
import com.synonym.bitkitcore.BoltzSwapType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.services.BoltzService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwapsViewModelTest : BaseUnitTest() {
    private val boltzService = mock<BoltzService>()

    @Test
    fun `refresh sorts swaps by creation time descending`() = test {
        whenever(boltzService.listSwaps()).thenReturn(
            listOf(
                swap(id = "older", createdAt = 100uL),
                swap(id = "newer", createdAt = 200uL),
            ),
        )

        val sut = SwapsViewModel(boltzService)
        advanceUntilIdle()

        assertEquals(listOf("newer", "older"), sut.swaps.value.map { it.id })
    }

    @Test
    fun `refresh surfaces the error when listing swaps fails`() = test {
        whenever(boltzService.listSwaps()).thenAnswer { throw AppError(LIST_ERROR) }

        val sut = SwapsViewModel(boltzService)
        advanceUntilIdle()

        assertEquals(LIST_ERROR, sut.error.value)
        assertTrue(sut.swaps.value.isEmpty())
    }

    @Test
    fun `claimReverseSwap reports the broadcast txid`() = test {
        whenever(boltzService.listSwaps()).thenReturn(emptyList())
        whenever(boltzService.claimReverseSwap(any(), anyOrNull())).thenReturn(TXID)
        val sut = SwapsViewModel(boltzService)

        var result: Result<String>? = null
        sut.claimReverseSwap(SWAP_ID) { result = it }
        advanceUntilIdle()

        assertEquals(TXID, result?.getOrNull())
    }

    @Test
    fun `isClaimable while a reverse swap is unclaimed and not in a terminal state`() {
        assertTrue(swap(status = BoltzSwapStatus.TransactionMempool).isClaimable)
        assertTrue(swap(status = BoltzSwapStatus.TransactionConfirmed).isClaimable)
        assertTrue(swap(status = BoltzSwapStatus.TransactionClaimPending).isClaimable)
        assertTrue(swap(status = BoltzSwapStatus.InvoicePending).isClaimable)

        // A stalled updates stream leaves the swap at SwapCreated locally even once Boltz has
        // locked up on-chain, so the claim must stay reachable: this is the recovery case.
        assertTrue(swap(status = BoltzSwapStatus.SwapCreated).isClaimable)
    }

    @Test
    fun `isClaimable is false for terminal, already claimed, and submarine swaps`() {
        assertFalse(swap(status = BoltzSwapStatus.SwapExpired).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.TransactionFailed).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.TransactionLockupFailed).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.TransactionRefunded).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.TransactionClaimed).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.InvoiceSettled).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.InvoiceExpired).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.InvoiceFailedToPay).isClaimable)
        assertFalse(swap(status = BoltzSwapStatus.TransactionConfirmed, claimTxId = TXID).isClaimable)
        assertFalse(
            swap(swapType = BoltzSwapType.SUBMARINE, status = BoltzSwapStatus.TransactionConfirmed).isClaimable,
        )
    }

    private fun swap(
        id: String = SWAP_ID,
        swapType: BoltzSwapType = BoltzSwapType.REVERSE,
        status: BoltzSwapStatus = BoltzSwapStatus.TransactionConfirmed,
        claimTxId: String? = null,
        createdAt: ULong = 0uL,
    ) = BoltzSwap(
        id = id,
        swapType = swapType,
        status = status,
        network = BoltzNetwork.REGTEST,
        swapIndex = 0uL,
        amountSat = 100_000uL,
        onchainAmountSat = 99_000uL,
        invoice = null,
        lockupAddress = null,
        onchainAddress = null,
        timeoutBlockHeight = 800uL,
        createdAt = createdAt,
        claimTxId = claimTxId,
        refundTxId = null,
    )

    private companion object {
        const val SWAP_ID = "swap1"
        const val TXID = "txid1"
        const val LIST_ERROR = "database unavailable"
    }
}
