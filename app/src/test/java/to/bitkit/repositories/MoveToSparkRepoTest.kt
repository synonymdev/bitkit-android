package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.createChannelDetails
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.LdkError
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
    private val nodeEvents = MutableSharedFlow<Event>()
    private val inFlight = MoveToSparkOutcome.InFlight(50_000uL, paymentHash = "hash", paymentId = "id")
    private lateinit var sut: MoveToSparkRepo

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(sparkRepo.state).thenReturn(sparkState)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
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
        assertEquals(MoveToSparkMath.quotedFeeSats(30uL), quote.feeSats)
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
            .thenReturn(Result.failure(LdkError(NodeException.RouteNotFound("no route"))))

        val error = sut.quote().exceptionOrNull()

        assertIs<MoveToSparkError.TooLarge>(error)
    }

    @Test
    fun `quote keeps an estimate error other than no route`() = test {
        sendable(100_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any()))
            .thenReturn(Result.failure(LdkError(NodeException.NotRunning("node stopped"))))

        val error = sut.quote().exceptionOrNull()

        assertIs<LdkError>(error)
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
    fun `a move is offered only when the minimum fits beside the fee reserve`() {
        assertTrue(!MoveToSparkMath.hasMovableBalance(1_011uL))
        assertTrue(MoveToSparkMath.hasMovableBalance(1_012uL))
    }

    @Test
    fun `quote fails with nothing to move when the fee reserve leaves under the minimum`() = test {
        sendable(1_011uL)

        assertIs<MoveToSparkError.NothingToMove>(sut.quote().exceptionOrNull())
        verify(sparkRepo, never()).createInvoice(anyOrNull(), any(), any())
    }

    @Test
    fun `smaller amount halves until the minimum`() {
        assertEquals(50_000uL, MoveToSparkMath.smallerAmount(100_000uL))
        assertNull(MoveToSparkMath.smallerAmount(MoveToSparkMath.MIN_MOVE_SATS))
    }

    @Test
    fun `fee cap is the quoted fee, which covers the estimate and its margin`() {
        val feeSats = MoveToSparkMath.quotedFeeSats(30uL)
        assertEquals(32uL, feeSats)
        assertEquals(32_000uL, MoveToSparkMath.feeCapMsat(feeSats))
        assertEquals(1_000_000uL, MoveToSparkMath.amountAfterFee(1_000_000uL, 30uL) + feeSats)
    }

    @Test
    fun `an in-flight move that fails later matches its payment id when the hash is missing`() = test {
        val settled = async(start = CoroutineStart.UNDISPATCHED) { sut.awaitSettled(inFlight) }

        nodeEvents.emit(Event.PaymentFailed(paymentId = "other", paymentHash = null, reason = null))
        nodeEvents.emit(
            Event.PaymentFailed(paymentId = "id", paymentHash = null, reason = PaymentFailureReason.RETRIES_EXHAUSTED),
        )

        assertIs<MoveToSparkError.PaymentFailed>(settled.await().exceptionOrNull())
    }

    @Test
    fun `an in-flight move that succeeds later reports the fee paid`() = test {
        val settled = async(start = CoroutineStart.UNDISPATCHED) { sut.awaitSettled(inFlight) }

        nodeEvents.emit(
            Event.PaymentSuccessful(
                paymentId = "id",
                paymentHash = "hash",
                paymentPreimage = null,
                feePaidMsat = 7_000uL,
            ),
        )

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 7uL), settled.await().getOrThrow())
    }

    @Test
    fun `an in-flight move that settled before the late listener started reads the stored payment`() = test {
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.SUCCEEDED)))

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 7uL), sut.awaitSettled(inFlight).getOrThrow())
    }

    @Test
    fun `an in-flight move that failed before the late listener started reads the stored payment`() = test {
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.FAILED)))

        assertIs<MoveToSparkError.PaymentFailed>(sut.awaitSettled(inFlight).exceptionOrNull())
    }

    private fun nodeSend(status: PaymentStatus) = PaymentDetails(
        id = "id",
        kind = PaymentKind.Bolt11(hash = "hash", preimage = null, secret = null, description = null, bolt11 = INVOICE),
        amountMsat = 50_000_000uL,
        feePaidMsat = 7_000uL,
        direction = PaymentDirection.OUTBOUND,
        status = status,
        latestUpdateTimestamp = 0uL,
    )
}
