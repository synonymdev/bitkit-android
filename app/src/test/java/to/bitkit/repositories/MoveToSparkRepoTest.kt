package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
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
import to.bitkit.ext.createChannelDetails
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveToSparkRepoTest : BaseUnitTest() {
    companion object {
        private const val INVOICE = "lnbcrt1spark"
    }

    private val lightningRepo = mock<LightningRepo>()
    private val sparkRepo = mock<SparkRepo>()
    private val sparkState = MutableStateFlow(SparkState(isReady = true))
    private val lightningState = MutableStateFlow(LightningState())
    private lateinit var sut: MoveToSparkRepo

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(sparkRepo.state).thenReturn(sparkState)
        sut = MoveToSparkRepo(testDispatcher, lightningRepo, sparkRepo)
    }

    private fun sendable(sats: ULong) {
        val channel = createChannelDetails().copy(isUsable = true, nextOutboundHtlcLimitMsat = sats * 1000uL)
        lightningState.value = LightningState(channels = persistentListOf(channel))
    }

    @Test
    fun `quote leaves room under the outbound limit for the routing fee`() = test {
        sendable(100_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(30uL))

        val quote = sut.quote().getOrThrow()

        assertEquals(INVOICE, quote.invoice)
        assertEquals(30uL, quote.feeSats)
        assertEquals(MoveToSparkMath.amountAfterFee(100_000uL, 30uL), quote.amountSats)
        verify(sparkRepo).createInvoice(eq(null), any(), any())
    }

    @Test
    fun `quote shrinks the amount when the fee does not fit the first guess`() = test {
        sendable(1_000_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(9_000uL))

        val quote = sut.quote().getOrThrow()

        assertEquals(MoveToSparkMath.amountAfterFee(1_000_000uL, 9_000uL), quote.amountSats)
        assertTrue(quote.amountSats + quote.feeSats <= 1_000_000uL)
    }

    @Test
    fun `quote of a smaller target keeps the target`() = test {
        sendable(100_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(5uL))

        assertEquals(50_000uL, sut.quote(50_000uL).getOrThrow().amountSats)
    }

    @Test
    fun `quote fails as too large when no route is found`() = test {
        sendable(100_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any()))
            .thenReturn(Result.failure(IllegalStateException("route not found")))

        val error = sut.quote().exceptionOrNull()

        assertIs<MoveToSparkError.TooLarge>(error)
    }

    @Test
    fun `quote fails with nothing to move below the minimum`() = test {
        sendable(MoveToSparkMath.MIN_MOVE_SATS - 1uL)

        assertIs<MoveToSparkError.NothingToMove>(sut.quote().exceptionOrNull())
        verify(sparkRepo, never()).createInvoice(anyOrNull(), any(), any())
    }

    @Test
    fun `quote fails while Spark is not usable`() = test {
        sendable(100_000uL)
        sparkState.value = SparkState(isReady = false)

        assertIs<MoveToSparkError.SparkUnavailable>(sut.quote().exceptionOrNull())
    }

    @Test
    fun `a move is offered only with a usable Spark and a balance above the minimum`() {
        val channels = listOf(createChannelDetails().copy(isUsable = true, nextOutboundHtlcLimitMsat = 5_000_000uL))
        assertTrue(MoveToSparkMath.canMove(channels, SparkState(isReady = true)))
        assertTrue(!MoveToSparkMath.canMove(channels, SparkState(isReady = false)))
        assertTrue(!MoveToSparkMath.canMove(emptyList(), SparkState(isReady = true)))
    }

    @Test
    fun `smaller amount halves until the minimum`() {
        assertEquals(50_000uL, MoveToSparkMath.smallerAmount(100_000uL))
        assertNull(MoveToSparkMath.smallerAmount(MoveToSparkMath.MIN_MOVE_SATS))
    }

    @Test
    fun `fee cap covers the quoted fee and its margin`() {
        assertEquals(32_000uL, MoveToSparkMath.feeCapMsat(30uL))
    }
}
