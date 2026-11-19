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
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.createChannelDetails
import to.bitkit.services.SparkError
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.InMemoryDataStore
import to.bitkit.utils.LdkError
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveToSparkRepoTest : BaseUnitTest() {
    companion object {
        private const val INVOICE = "lnbcrt1spark"
        private const val SPARK_FEE = 200uL
    }

    private val lightningRepo = mock<LightningRepo>()
    private val sparkRepo = mock<SparkRepo>()
    private val sparkState = MutableStateFlow(SparkState(isReady = true))
    private val lightningState = MutableStateFlow(LightningState())
    private val nodeEvents = MutableSharedFlow<Event>()
    private val inFlight = MoveToSparkOutcome.InFlight(50_000uL, paymentHash = "hash", paymentId = "id")
    private val cacheStore = CacheStore(InMemoryDataStore(AppCacheData()))
    private val quote = MoveToSparkQuote(INVOICE, amountSats = 50_000uL, feeSats = 12uL)
    private lateinit var sut: MoveToSparkRepo

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(sparkRepo.state).thenReturn(sparkState)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        sut = createSut()
    }

    private fun createSut() = MoveToSparkRepo(testDispatcher, lightningRepo, sparkRepo, cacheStore) { "hash" }

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
    fun `quote fails while an exit holds Spark`() = test {
        sendable(100_000uL)
        sparkState.value = SparkState(isReady = true, isExiting = true)

        assertIs<MoveToSparkError.SparkUnavailable>(sut.quote().exceptionOrNull())
        verify(sparkRepo, never()).createInvoice(anyOrNull(), any(), any())
    }

    @Test
    fun `a move is not offered while an exit holds Spark`() {
        val channels = listOf(createChannelDetails().copy(isUsable = true, nextOutboundHtlcLimitMsat = 5_000_000uL))
        assertTrue(!MoveToSparkMath.canMove(channels, SparkState(isReady = true, isExiting = true)))
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

    @Test
    fun `an in-flight move whose outcome was missed while the node was down reads it once the node is back`() = test {
        // The node can't list its payments at first, and the settling event came before the listener started.
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(null, listOf(nodeSend(PaymentStatus.SUCCEEDED)))
        val settled = async(start = CoroutineStart.UNDISPATCHED) { sut.awaitSettled(inFlight) }

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 7uL), settled.await().getOrThrow())
    }

    @Test
    fun `move pays over the channels capped at the quoted fee and reports the fee paid`() = test {
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        val moving = async { sut.move(quote) }

        nodeEvents.emit(Event.PaymentSuccessful("other", "other_hash", null, 1_000uL))
        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 9uL), moving.await().getOrThrow())
        verify(lightningRepo).payInvoiceOverLdk(
            bolt11 = INVOICE,
            paymentHash = "hash",
            sats = 50_000uL,
            maxTotalRoutingFeeMsat = 12_000uL,
        )
    }

    @Test
    fun `move matches a failure without its hash by the payment id`() = test {
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        val moving = async { sut.move(quote) }

        nodeEvents.emit(Event.PaymentFailed("other", null, PaymentFailureReason.ROUTE_NOT_FOUND))
        nodeEvents.emit(Event.PaymentFailed("id", null, PaymentFailureReason.PAYMENT_EXPIRED))

        val error = assertIs<MoveToSparkError.PaymentFailed>(moving.await().exceptionOrNull())
        assertEquals(PaymentFailureReason.PAYMENT_EXPIRED, error.reason)
    }

    @Test
    fun `move reports a payment without an outcome in time as in flight`() = test {
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        // Nothing settles it, so the virtual clock runs to the end of the wait.
        assertEquals(inFlight, sut.move(quote).getOrThrow())
        assertTrue(sut.isWatchedMovePayment("hash").not(), "a move no one waits on still mutes the app's notices")
    }

    @Test
    fun `a move's payment stays a move after a restart`() = test {
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        sut.move(quote)

        assertTrue(createSut().isMovePayment("HASH"), "a restart forgot the move's payment")
        assertTrue(!createSut().isMovePayment("other"))
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

    @Test
    fun `combined sendable is what Spark can send once the channels' balance less the fee guess moved`() {
        val channels = listOf(channel("c1", 100_000uL))

        val combined = MoveToSparkMath.combinedSendableSats(
            channels,
            SparkState(isReady = true, balanceSats = 50_000uL)
        )

        // The fee guess on 100 000 sats is 500, so 99 498 move; Spark keeps 0.25% of 149 498.
        assertEquals(149_125uL, combined)
    }

    @Test
    fun `combined sendable is zero without a usable Spark or a balance worth moving`() {
        val channels = listOf(channel("c1", 100_000uL))
        assertEquals(0uL, MoveToSparkMath.combinedSendableSats(channels, SparkState(isReady = false)))
        assertEquals(0uL, MoveToSparkMath.combinedSendableSats(channels, SparkState(isReady = true, isExiting = true)))
        assertEquals(0uL, MoveToSparkMath.combinedSendableSats(emptyList(), SparkState(isReady = true)))
    }

    @Test
    fun `a payment needs the update only above both rails and within the combined balance`() {
        val channels = listOf(channel("c1", 100_000uL))
        val spark = SparkState(isReady = true, balanceSats = 50_000uL)

        assertTrue(!MoveToSparkMath.needsUpdateToSend(100_000uL, channels, spark))
        assertTrue(!MoveToSparkMath.needsUpdateToSend(spark.sendableSats, channels, spark))
        assertTrue(MoveToSparkMath.needsUpdateToSend(100_001uL, channels, spark))
        assertTrue(MoveToSparkMath.needsUpdateToSend(149_125uL, channels, spark))
        assertTrue(!MoveToSparkMath.needsUpdateToSend(149_126uL, channels, spark))
    }

    @Test
    fun `max send counts the combined balance`() {
        val channels = listOf(channel("c1", 100_000uL))
        val spark = SparkState(isReady = true, balanceSats = 50_000uL)

        assertEquals(149_125uL, MoveToSparkMath.maxSendSats(channels, spark))
        assertEquals(100_000uL, MoveToSparkMath.maxSendSats(channels, SparkState(isReady = false)))
    }

    @Test
    fun `emptied channels are the open ones a move took from the minimum to below it`() {
        val before = listOf(
            channel("emptied", 50_000uL),
            channel("small", 500uL),
            channel("kept", 20_000uL),
        )
        val after = listOf(
            channel("emptied", 0uL),
            channel("small", 0uL),
            channel("kept", 10_000uL),
            channel("new", 0uL),
            channel("emptied_pending", 0uL).copy(isChannelReady = false),
        )

        assertEquals(listOf("emptied"), MoveToSparkMath.emptiedChannels(before, after).map { it.channelId })
    }

    @Test
    fun `a channel whose limit an in-flight htlc lowered still holds its capacity and stays open`() {
        val before = listOf(channel("held", 50_000uL))
        val after = listOf(channel("held", 0uL).copy(outboundCapacityMsat = 20_000_000uL))

        assertEquals(emptyList<String>(), MoveToSparkMath.emptiedChannels(before, after).map { it.channelId })
    }

    @Test
    fun `a funded channel's capacity counts without the commitment fee it keeps back`() {
        val funded = channel("funded", 0uL).copy(
            outboundCapacityMsat = 1_900_000uL,
            isOutbound = true,
            feerateSatPer1000Weight = 253u,
        )
        val anchorBefore = channel("funded", 50_000uL).copy(
            outboundCapacityMsat = 50_371_000uL,
            isOutbound = true,
            feerateSatPer1000Weight = 253u,
        )
        val before = listOf(anchorBefore, channel("inbound", 50_000uL))
        val after = listOf(funded, funded.copy(channelId = "inbound", isOutbound = false))

        assertFalse(MoveToSparkMath.reservesLegacyFee(anchorBefore))
        assertEquals(1_529uL, MoveToSparkMath.sendableAfterCommitmentFeeSats(funded, isLegacy = false))
        assertEquals(emptyList<String>(), MoveToSparkMath.emptiedChannels(before, after).map { it.channelId })
        val nearlyEmpty = after.map { it.copy(outboundCapacityMsat = 1_300_000uL) }
        assertEquals(listOf("funded"), MoveToSparkMath.emptiedChannels(before, nearlyEmpty).map { it.channelId })
    }

    @Test
    fun `a move closes a legacy channel at its reserve`() {
        // 2 500 sat/kW: a legacy commitment keeps back 4 480 sats, so 5 342 sats of capacity send only 862.
        val legacyBefore = channel("legacy", 50_000uL).copy(
            outboundCapacityMsat = 54_480_000uL,
            isOutbound = true,
            feerateSatPer1000Weight = 2_500u,
        )
        val after = channel("legacy", 0uL).copy(
            outboundCapacityMsat = 5_342_000uL,
            isOutbound = true,
            feerateSatPer1000Weight = 2_500u,
        )

        assertTrue(MoveToSparkMath.reservesLegacyFee(legacyBefore))
        assertEquals(862uL, MoveToSparkMath.sendableAfterCommitmentFeeSats(after, isLegacy = true))
        val emptied = MoveToSparkMath.emptiedChannels(listOf(legacyBefore), listOf(after))
        assertEquals(listOf("legacy"), emptied.map { it.channelId })
    }

    @Test
    fun `payments around the channels read are unclear when one changed or could not be read`() {
        val sent = nodeSend(PaymentStatus.FAILED)
        fun around(start: List<PaymentDetails>?, before: List<PaymentDetails>?, after: List<PaymentDetails>?) =
            MoveToSparkMath.paymentsAround(start, before, after) { it == "move" }
        assertEquals(null, around(emptyList(), null, emptyList()))
        assertEquals(null, around(emptyList(), emptyList(), null))
        assertEquals(null, around(null, emptyList(), emptyList()))
        assertEquals(null, around(listOf(sent), emptyList(), listOf(sent)), "a payment appeared")
        assertEquals(null, around(emptyList(), listOf(sent), listOf(sent)), "another send since the move started")
        val move = sent.copy(id = "move")
        assertEquals(listOf(move, move), around(emptyList(), listOf(move), listOf(move)), "the move's own send")
        assertEquals(listOf(sent, sent), around(listOf(sent), listOf(sent), listOf(sent)))
    }

    @Test
    fun `combined amounts keep their invariants for generated balances`() {
        val random = Random(seed = 64)
        repeat(1_000) {
            val channels = List(random.nextInt(0, 4)) { index ->
                channel("c$index", random.nextLong(0L, 2_000_000L).toULong())
            }
            val spark = SparkState(
                isReady = random.nextBoolean(),
                isExiting = random.nextInt(10) == 0,
                balanceSats = random.nextLong(0L, 2_000_000L).toULong(),
            )
            val movable = MoveToSparkMath.movableSats(channels)
            val combined = MoveToSparkMath.combinedSendableSats(channels, spark)
            val max = MoveToSparkMath.maxSendSats(channels, spark)

            assertTrue(combined <= SparkFees.sendableSats(spark.balanceSats + movable), "combined '$combined'")
            assertTrue(max >= movable && max >= spark.sendableSats && max >= combined, "max '$max'")
            if (MoveToSparkMath.canMove(channels, spark)) assertTrue(combined >= spark.sendableSats)
            val amount = random.nextLong(1L, 4_000_000L).toULong()
            if (MoveToSparkMath.needsUpdateToSend(amount, channels, spark)) {
                assertTrue(amount > movable && amount <= combined && amount <= max, "amount '$amount'")
            }
        }
    }

    @Test
    fun `update to send moves nothing when the quote and Spark's balance fall short of the payment`() = test {
        sparkState.value = SparkState(isReady = true, balanceSats = 5_000uL)

        val result = sut.updateToSend(quote, INVOICE, 60_000uL)

        assertIs<MoveToSparkError.UpdateFallsShort>(result.exceptionOrNull())
        verify(lightningRepo, never()).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `update to send moves nothing when Spark's routing fee would leave the payment short`() = test {
        sparkState.value = SparkState(isReady = true, balanceSats = 10_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 59_800uL)).thenReturn(Result.success(500uL))

        val result = sut.updateToSend(quote, INVOICE, 59_800uL)

        assertIs<MoveToSparkError.UpdateFallsShort>(result.exceptionOrNull())
        verify(lightningRepo, never()).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `update to send moves nothing when no fee for the payment can be priced`() = test {
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.failure(SparkError.SendRefused("")))

        val result = sut.updateToSend(quote, INVOICE, 60_000uL)

        assertIs<MoveToSparkError.UpdateFallsShort>(result.exceptionOrNull())
        verify(lightningRepo, never()).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a quote covers a payment once Spark can pay it with its fee`() {
        val spark = SparkState(isReady = true, balanceSats = 10_000uL)
        val total = 10_000uL + quote.amountSats

        assertTrue(MoveToSparkMath.coversPayment(quote, spark, total - SPARK_FEE, SPARK_FEE))
        assertTrue(!MoveToSparkMath.coversPayment(quote, spark, total - SPARK_FEE + 1uL, SPARK_FEE))
        assertTrue(!MoveToSparkMath.coversPayment(quote, spark.copy(isExiting = true), 1_000uL, SPARK_FEE))
    }

    @Test
    fun `a quote covers a payment only if the wait after the move would accept it`() {
        val spark = SparkState(isReady = true, balanceSats = 20_000uL)
        val have = 20_000uL + quote.amountSats
        val priced = 2uL

        // A fee priced below Spark's service fee passes the plain sum, yet Spark keeps the service fee aside.
        assertTrue(!MoveToSparkMath.coversPayment(quote, spark, have - priced, priced))
        assertTrue(MoveToSparkMath.coversPayment(quote, spark, SparkFees.sendableSats(have), priced))
    }

    @Test
    fun `single rail sendable is the larger of the channels and a ready Spark`() {
        val channels = listOf(channel("a", 30_000uL))

        assertEquals(30_000uL, MoveToSparkMath.singleRailSendableSats(channels, SparkState(isReady = true)))
        assertEquals(
            SparkFees.sendableSats(50_000uL),
            MoveToSparkMath.singleRailSendableSats(channels, SparkState(isReady = true, balanceSats = 50_000uL)),
        )
        assertEquals(30_000uL, MoveToSparkMath.singleRailSendableSats(channels, SparkState(balanceSats = 50_000uL)))
    }

    @Test
    fun `update to send moves the balance, then waits until Spark can pay`() = test {
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        whenever(sparkRepo.canSend(60_000uL)).thenReturn(false, false, true)
        whenever(sparkRepo.refreshState()).thenReturn(Result.success(Unit))
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 9uL), updating.await().getOrThrow())
        verify(sparkRepo).refreshState()
    }

    @Test
    fun `update to send fails as unsettled while the move is in flight`() = test {
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))

        var moved: MoveToSparkOutcome? = null

        val result = sut.updateToSend(quote, INVOICE, 60_000uL) { moved = it }

        assertIs<MoveToSparkError.UpdateUnsettled>(result.exceptionOrNull())
        assertIs<MoveToSparkOutcome.InFlight>(moved)
        verify(sparkRepo, never()).refreshState()
    }

    @Test
    fun `update to send reports a settled move even when Spark never shows the balance`() = test {
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        whenever(sparkRepo.canSend(60_000uL)).thenReturn(false)
        whenever(sparkRepo.refreshState()).thenReturn(Result.success(Unit))
        var moved: MoveToSparkOutcome? = null
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) { moved = it } }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertIs<MoveToSparkError.UpdateUnsettled>(updating.await().exceptionOrNull())
        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 9uL), moved)
    }

    @Test
    fun `the fee after an update falls back to Spark's service fee when Spark cannot price it`() = test {
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.failure(SparkError.SendRefused("")))

        assertEquals(SparkFees.serviceFeeSats(60_000uL), sut.estimateFeeAfterUpdate(INVOICE, 60_000uL))
    }

    @Test
    fun `the fee after an update is Spark's service fee before there is an invoice`() = test {
        assertEquals(SparkFees.serviceFeeSats(60_000uL), sut.estimateFeeAfterUpdate(null, 60_000uL))
        verify(sparkRepo, never()).estimateFeeSats(any(), any())
    }

    private fun channel(id: String, sendableSats: ULong) = createChannelDetails().copy(
        channelId = id,
        isUsable = true,
        isChannelReady = true,
        nextOutboundHtlcLimitMsat = sendableSats * 1000uL,
    )
}
