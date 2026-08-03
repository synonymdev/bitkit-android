package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.dto.PendingBarkBoard
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class BarkTransferRepoTest : BaseUnitTest() {

    private val lightningRepo: LightningRepo = mock()
    private val barkRepo: BarkRepo = mock()
    private val cacheStore: CacheStore = mock()
    private val clock: Clock = mock()

    private val cacheData = MutableStateFlow(AppCacheData())
    private val barkState = MutableStateFlow(BarkState())

    private lateinit var sut: BarkTransferRepo

    @Before
    fun setUp() {
        whenever(cacheStore.data).thenReturn(cacheData)
        whenever(barkRepo.barkState).thenReturn(barkState)
        whenever(clock.now()).thenReturn(Clock.System.now())
        whenever { cacheStore.update(any()) }.thenAnswer { invocation ->
            val transform = invocation.getArgument<(AppCacheData) -> AppCacheData>(0)
            cacheData.value = transform(cacheData.value)
            Unit
        }
        whenever { barkRepo.onchainDepositAddress() }.thenReturn(Result.success(BARK_ADDRESS))
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(),
                any(), anyOrNull(), any(), any(),
            )
        }.thenReturn(Result.success(FUNDING_TXID))
        whenever { barkRepo.board(any()) }.thenReturn(Result.success(FUNDING_TXID))
        whenever { lightningRepo.newAddress() }.thenReturn(Result.success(SAVINGS_ADDRESS))
        whenever { barkRepo.offboardAll(any()) }.thenReturn(Result.success("round-1"))

        sut = BarkTransferRepo(
            bgDispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            barkRepo = barkRepo,
            cacheStore = cacheStore,
            clock = clock,
        )
    }

    // region board

    @Test
    fun `startBoard funds bark and records the pending board`() = test {
        val result = sut.startBoard(50_000uL)

        assertEquals(FUNDING_TXID, result.getOrNull())
        val pending = assertNotNull(cacheData.value.pendingBarkBoard)
        assertEquals(FUNDING_TXID, pending.fundingTxId)
        assertEquals(50_000uL, pending.amountSats)
        // The board itself must wait for confirmations.
        verify(barkRepo, never()).board(any())
    }

    @Test
    fun `startBoard refuses a second board while one is in flight`() = test {
        cacheData.value = AppCacheData(pendingBarkBoard = newPendingBoard())

        assertTrue(sut.startBoard(10_000uL).isFailure)
    }

    @Test
    fun `startBoard rejects an amount below the ark minimum`() = test {
        barkState.value = BarkState(arkInfo = BarkArkInfo(600uL, 1u, 10_000uL, null))

        assertTrue(sut.startBoard(5_000uL).isFailure)
        assertNull(cacheData.value.pendingBarkBoard)
    }

    @Test
    fun `startBoard rejects an amount above the max vtxo size`() = test {
        barkState.value = BarkState(arkInfo = BarkArkInfo(600uL, 1u, 1_000uL, 100_000uL))

        assertTrue(sut.startBoard(200_000uL).isFailure)
        assertNull(cacheData.value.pendingBarkBoard)
    }

    // endregion

    // region resume

    @Test
    fun `resumePendingBoard does nothing when there is no pending board`() = test {
        val result = sut.resumePendingBoard()

        assertFalse(result.getOrThrow())
        verify(barkRepo, never()).board(any())
    }

    @Test
    fun `resumePendingBoard waits while the funding tx is unconfirmed`() = test {
        cacheData.value = AppCacheData(pendingBarkBoard = newPendingBoard(amountSats = 50_000uL))
        whenever { barkRepo.onchainSpendableSats() }.thenReturn(Result.success(0uL))

        assertFalse(sut.resumePendingBoard().getOrThrow())
        verify(barkRepo, never()).board(any())
        // The intent must survive so a later run can finish it.
        assertNotNull(cacheData.value.pendingBarkBoard)
    }

    @Test
    fun `resumePendingBoard boards and clears once funds have confirmed`() = test {
        cacheData.value = AppCacheData(pendingBarkBoard = newPendingBoard(amountSats = 50_000uL))
        whenever { barkRepo.onchainSpendableSats() }.thenReturn(Result.success(50_000uL))

        assertTrue(sut.resumePendingBoard().getOrThrow())
        verify(barkRepo).board(eq(50_000uL))
        assertNull(cacheData.value.pendingBarkBoard)
    }

    @Test
    fun `resumePendingBoard keeps the pending board when boarding fails`() = test {
        cacheData.value = AppCacheData(pendingBarkBoard = newPendingBoard(amountSats = 50_000uL))
        whenever { barkRepo.onchainSpendableSats() }.thenReturn(Result.success(50_000uL))
        whenever { barkRepo.board(any()) }.thenReturn(Result.failure(RuntimeException("server down")))

        assertTrue(sut.resumePendingBoard().isFailure)
        assertNotNull(cacheData.value.pendingBarkBoard)
    }

    @Test
    fun `pendingBoardSats reports the in-flight amount`() = test {
        assertEquals(0uL, sut.pendingBoardSats())

        cacheData.value = AppCacheData(pendingBarkBoard = newPendingBoard(amountSats = 7_500uL))

        assertEquals(7_500uL, sut.pendingBoardSats())
    }

    // endregion

    @Test
    fun `offboardToSavings sends the ark balance to a fresh savings address`() = test {
        val result = sut.offboardToSavings()

        assertEquals("round-1", result.getOrNull())
        verify(barkRepo).offboardAll(eq(SAVINGS_ADDRESS))
    }

    private fun newPendingBoard(amountSats: ULong = 50_000uL) = PendingBarkBoard(
        fundingTxId = FUNDING_TXID,
        amountSats = amountSats,
        createdAtMillis = 0L,
    )

    private companion object {
        const val BARK_ADDRESS = "bcrt1qbark"
        const val SAVINGS_ADDRESS = "bcrt1qsavings"
        const val FUNDING_TXID = "funding-txid"
    }
}
