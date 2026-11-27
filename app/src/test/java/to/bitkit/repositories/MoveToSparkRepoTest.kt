package to.bitkit.repositories

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.WalletScope
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
    private val activityRepo = mock<ActivityRepo>()
    private val quote = MoveToSparkQuote(INVOICE, amountSats = 50_000uL, feeSats = 12uL)
    private val settledHashes = mutableListOf<String>()
    private val failedHashes = mutableListOf<String>()
    private val quotedSats = mutableListOf<ULong>()
    private val retriedAtPay = mutableMapOf<String, List<String>?>()
    private lateinit var sut: MoveToSparkRepo

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(sparkRepo.state).thenReturn(sparkState)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        sut = createSut()
    }

    private fun createSut(paymentHashOf: (String) -> String = { "hash" }) = MoveToSparkRepo(
        bgDispatcher = testDispatcher,
        lightningRepo = lightningRepo,
        sparkRepo = sparkRepo,
        cacheStore = cacheStore,
        activityRepo = { activityRepo },
        paymentHashOf = paymentHashOf,
    )

    private fun sendable(sats: ULong, htlcCapSats: ULong = sats) {
        val channel = createChannelDetails().copy(
            isUsable = true,
            nextOutboundHtlcLimitMsat = minOf(sats, htlcCapSats) * 1000uL,
            outboundCapacityMsat = sats * 1000uL,
        )
        lightningState.value = lightningState.value.copy(channels = persistentListOf(channel))
    }

    @Test
    fun `quote leaves room under the outbound limit for the routing fee`() = test {
        sendable(100_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(30uL))

        val quote = sut.quote().getOrThrow()

        assertEquals(INVOICE, quote.invoice)
        assertEquals(MoveToSparkMath.cappedFeeSats(30uL, 100_000uL), quote.feeSats)
        assertEquals(MoveToSparkMath.amountAfterCappedFee(100_000uL, 30uL), quote.amountSats)
        verify(sparkRepo).createInvoice(eq(null), any(), any())
    }

    @Test
    fun `quote leaves a costlier route room under the fee cap and the send limit`() = test {
        // RC1 mainnet: an 11-sat estimate on a 10,001-sat limit; the route that worked cost 51 sats.
        sendable(10_001uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(11uL))

        val quote = sut.quote().getOrThrow()

        assertTrue(quote.feeSats >= 51uL, "fee cap ${quote.feeSats}")
        assertTrue(quote.amountSats + 51uL <= 10_001uL, "amount ${quote.amountSats}")
        assertTrue(quote.amountSats >= MoveToSparkMath.MIN_MOVE_SATS)
    }

    @Test
    fun `capped fee keeps the margin, LDK's cap and the minimum move for any estimate and limit`() {
        val random = Random(2164)
        repeat(10_000) {
            val limitSats = random.nextLong(0L, 2_000_000L).toULong()
            val estimateSats = random.nextLong(0L, 30_000L).toULong()
            val feeSats = MoveToSparkMath.cappedFeeSats(estimateSats, limitSats)
            val amountSats = MoveToSparkMath.amountAfterCappedFee(limitSats, estimateSats)
            val context = "limit $limitSats, estimate $estimateSats, fee $feeSats"

            assertTrue(feeSats >= MoveToSparkMath.quotedFeeSats(estimateSats), context)
            assertTrue(feeSats <= maxOf(MoveToSparkMath.quotedFeeSats(estimateSats), limitSats / 100uL + 50uL), context)
            assertTrue(amountSats + feeSats <= maxOf(limitSats, feeSats), context)
            if (limitSats >= MoveToSparkMath.MIN_MOVE_SATS + MoveToSparkMath.quotedFeeSats(estimateSats)) {
                assertTrue(amountSats >= MoveToSparkMath.MIN_MOVE_SATS, context)
            }
        }
    }

    @Test
    fun `quote shrinks the amount when the fee does not fit the first guess`() = test {
        sendable(1_000_000uL)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(9_000uL))

        val quote = sut.quote().getOrThrow()

        assertEquals(MoveToSparkMath.amountAfterCappedFee(1_000_000uL, 9_000uL), quote.amountSats)
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

    /**
     * Channels holding [limitSats] whose routes carry at most [maxRouteSats] per payment when quoted and [maxPaySats]
     * when paid, each payment of an amount costing [feeMsatOf] it; the [settles] payments settle, the others stay in
     * flight. A payment whose fee cap is below its fee does not route. After each settled payment, its HTLC holds the
     * send limit under the minimum for [heldSyncs] syncs. The peer caps each HTLC at [htlcCapSats]. Once the first
     * payment settles, quotes route at most [routeSatsAfterFirst]. With [distinctParts], each invoice has its own hash.
     */
    private suspend fun channelsRouting(
        limitSats: ULong,
        maxRouteSats: ULong = ULong.MAX_VALUE,
        maxPaySats: ULong = maxRouteSats,
        feeMsatOf: (ULong) -> ULong = { 10_000uL },
        heldSyncs: Int = 0,
        htlcCapSats: ULong = ULong.MAX_VALUE,
        routeSatsAfterFirst: ULong = maxRouteSats,
        distinctParts: Boolean = false,
        settles: (Int) -> Boolean = { true },
    ) {
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        sendable(limitSats, htlcCapSats)
        var limit = limitSats
        var payments = 0
        var held = 0
        var maxRoute = maxRouteSats
        whenever(lightningRepo.syncState()).thenAnswer {
            if (held > 0 && --held == 0) sendable(limit, htlcCapSats)
        }
        if (distinctParts) {
            var invoices = 0
            whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).doSuspendableAnswer {
                Result.success("$INVOICE${++invoices}")
            }
            sut = createSut { "hash${it.removePrefix(INVOICE)}" }
        } else {
            whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        }
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(any(), any())).doSuspendableAnswer {
            val sats = it.arguments[1].asULong()
            quotedSats += sats
            if (sats > maxRoute) {
                Result.failure(LdkError(NodeException.RouteNotFound("no route")))
            } else {
                Result.success(feeMsatOf(sats) / 1000uL)
            }
        }
        whenever(lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
            val hash = it.arguments[1] as String
            retriedAtPay[hash] = cacheStore.data.first().moveToSparkRetriedBefore[hash]
            val sats = it.arguments[2].asULong()
            val feeCapMsat = it.arguments[3].asULong()
            val feeMsat = feeMsatOf(sats)
            payments++
            when {
                sats > maxPaySats || feeCapMsat < feeMsat -> {
                    failedHashes += hash
                    nodeEvents.emit(Event.PaymentFailed("id", hash, PaymentFailureReason.ROUTE_NOT_FOUND))
                }
                settles(payments) -> {
                    settledHashes += hash
                    maxRoute = routeSatsAfterFirst
                    limit -= sats + (feeMsat + 999uL) / 1000uL
                    held = heldSyncs + 1
                    if (heldSyncs == 0) {
                        sendable(limit, htlcCapSats)
                    } else {
                        val holding = createChannelDetails().copy(
                            isUsable = true,
                            nextOutboundHtlcLimitMsat = 500_000uL,
                            outboundCapacityMsat = limit * 1000uL,
                        )
                        lightningState.value = lightningState.value.copy(channels = persistentListOf(holding))
                    }
                    nodeEvents.emit(Event.PaymentSuccessful("id", hash, null, feeMsat))
                }
            }
            Result.success("id")
        }
    }

    /** Mockito sees a ULong argument as a Long, or boxed when the parameter is nullable. */
    private fun Any?.asULong(): ULong = (this as? ULong) ?: (this as Long).toULong()

    @Test
    fun `a whole balance that routes in one payment moves in one part at its quoted fee`() = test {
        channelsRouting(100_000uL)

        val plan = sut.quoteAll().getOrThrow()
        val outcome = sut.moveAll(plan).getOrThrow()

        val amountSats = MoveToSparkMath.amountAfterCappedFee(100_000uL, 10uL)
        val feeSats = MoveToSparkMath.cappedFeeSats(10uL, 100_000uL)
        assertEquals(MoveToSparkQuote(INVOICE, amountSats, feeSats), plan.firstPart)
        assertEquals(feeSats, plan.feeSats)
        assertEquals(MoveToSparkOutcome.Moved(amountSats, feeSats = 10uL), outcome)
        verify(lightningRepo, times(1)).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a balance too large for one payment is priced and moved in halved parts within the quoted fee`() = test {
        channelsRouting(100_000uL, maxRouteSats = 30_000uL)

        val plan = sut.quoteAll().getOrThrow()
        val outcome = assertIs<MoveToSparkOutcome.Moved>(sut.moveAll(plan).getOrThrow())

        assertEquals(24_874uL, plan.firstPart.amountSats)
        assertEquals(MoveToSparkMath.cappedFeeSats(10uL, 100_000uL) * 4uL, plan.feeSats)
        assertEquals(MoveToSparkOutcome.Moved(99_496uL, feeSats = 40uL), outcome)
        assertTrue(outcome.feeSats <= plan.feeSats)
        verify(lightningRepo, times(4)).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `each part's fee is capped at what the quoted total leaves`() = test {
        channelsRouting(100_000uL, maxRouteSats = 30_000uL)

        sut.moveAll(sut.quoteAll().getOrThrow())

        // Four parts quoted at 70 sats each; each part pays 10 sats of the 280 left.
        listOf(280_000uL, 270_000uL, 260_000uL, 250_000uL).forEach { capMsat ->
            verify(lightningRepo).payInvoiceOverLdk(any(), any(), anyOrNull(), eq(capMsat))
        }
    }

    @Test
    fun `a part that does not route at send halves and the move carries on`() = test {
        channelsRouting(50_000uL, maxPaySats = 30_000uL)
        val plan = MoveToSparkPlan(sut.quoteAll().getOrThrow().firstPart, feeSats = 100uL)

        val outcome = sut.moveAll(plan).getOrThrow()

        // Half of the 49,930 sats that did not route, then the rest less its 70-sat cap.
        assertEquals(MoveToSparkOutcome.Moved(24_965uL + 24_955uL, feeSats = 20uL), outcome)
    }

    @Test
    fun `a split move drops the failed parts it moved in smaller parts from Activity`() = test {
        channelsRouting(50_000uL, maxPaySats = 30_000uL, distinctParts = true)
        val plan = MoveToSparkPlan(sut.quoteAll().getOrThrow().firstPart, feeSats = 100uL)

        sut.moveAll(plan).getOrThrow()

        val cache = cacheStore.data.first()
        assertEquals(1, failedHashes.size)
        assertEquals(2, settledHashes.size)
        failedHashes.forEach {
            assertTrue(cache.isActivityDeleted(it, WalletScope.default), "a retried part's failure stayed")
            verify(activityRepo).handlePaymentEvent(it)
        }
        settledHashes.forEach {
            assertFalse(cache.isActivityDeleted(it, WalletScope.default), "a settled part left Activity")
            verify(activityRepo, never()).handlePaymentEvent(it)
        }
        assertEquals(
            failedHashes,
            retriedAtPay[settledHashes.first()],
            "a restart mid-send would forget the retried part",
        )
        assertTrue(cache.moveToSparkRetriedBefore.isEmpty())
    }

    @Test
    fun `a split move whose smaller part settles after the wait drops the failed part from Activity then`() = test {
        channelsRouting(50_000uL, maxPaySats = 30_000uL, distinctParts = true) { false }
        val plan = MoveToSparkPlan(sut.quoteAll().getOrThrow().firstPart, feeSats = 100uL)
        val inFlight = assertIs<MoveToSparkOutcome.InFlight>(sut.moveAll(plan).getOrThrow())
        verify(activityRepo, never()).handlePaymentEvent(any())
        val retried = failedHashes.single()
        assertEquals(
            mapOf(inFlight.paymentHash to listOf(retried)),
            cacheStore.data.first().moveToSparkRetriedBefore,
            "a restart would forget the part the one in flight replaced",
        )
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.SUCCEEDED)))

        sut.awaitSettled(inFlight).getOrThrow()

        verify(activityRepo).handlePaymentEvent(retried)
        verify(activityRepo, never()).handlePaymentEvent(inFlight.paymentHash)
        assertTrue(cacheStore.data.first().moveToSparkRetriedBefore.isEmpty())
    }

    @Test
    fun `a split move whose smaller part fails after the wait keeps the failed part in Activity`() = test {
        channelsRouting(50_000uL, maxPaySats = 30_000uL, distinctParts = true) { false }
        val plan = MoveToSparkPlan(sut.quoteAll().getOrThrow().firstPart, feeSats = 100uL)
        val inFlight = assertIs<MoveToSparkOutcome.InFlight>(sut.moveAll(plan).getOrThrow())
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(nodeSend(PaymentStatus.FAILED)))

        sut.awaitSettled(inFlight)

        verify(activityRepo, never()).handlePaymentEvent(any())
        assertFalse(cacheStore.data.first().isActivityDeleted(failedHashes.single(), WalletScope.default))
        assertTrue(cacheStore.data.first().moveToSparkRetriedBefore.isEmpty())
    }

    @Test
    fun `a split move still reports what moved when dropping its failed parts from Activity fails`() = test {
        channelsRouting(50_000uL, maxPaySats = 30_000uL)
        whenever(activityRepo.handlePaymentEvent(any())).thenThrow(IllegalStateException("core down"))
        val plan = MoveToSparkPlan(sut.quoteAll().getOrThrow().firstPart, feeSats = 100uL)

        val outcome = sut.moveAll(plan).getOrThrow()

        // Half of the 49,930 sats that did not route, then the rest less its 70-sat cap.
        assertEquals(MoveToSparkOutcome.Moved(24_965uL + 24_955uL, feeSats = 20uL), outcome)
    }

    @Test
    fun `a move that moves nothing keeps its failed parts in Activity`() = test {
        channelsRouting(1_800uL, maxPaySats = 1_000uL, distinctParts = true)

        sut.moveAll(sut.quoteAll().getOrThrow())

        assertTrue(failedHashes.isNotEmpty())
        failedHashes.forEach { assertFalse(cacheStore.data.first().isActivityDeleted(it, WalletScope.default)) }
    }

    @Test
    fun `a route that shrinks during the move leaves the later parts the size that routed`() = test {
        channelsRouting(200_000uL, maxRouteSats = 120_000uL, routeSatsAfterFirst = 30_000uL)
        val plan = sut.quoteAll().getOrThrow()
        quotedSats.clear()

        sut.moveAll(plan)

        // Only the second part tries the sizes that no longer route: about 100k, then 50k, before 25k routes.
        assertEquals(2, quotedSats.count { it > 30_000uL }, "a later part quoted a size that no longer routed")
    }

    @Test
    fun `a part capped while the peer was away keeps later parts at the earlier size`() = test {
        channelsRouting(100_000uL, maxRouteSats = 30_000uL)
        // Room for four 70-sat part caps at 10 sats paid each, not a fifth.
        val plan = MoveToSparkPlan(sut.quoteAll().getOrThrow().firstPart, feeSats = 100uL)
        var syncs = 0
        whenever(lightningRepo.syncState()).thenAnswer {
            if (++syncs != 1) return@thenAnswer Unit
            val channel = lightningState.value.channels.single()
            lightningState.value = LightningState(
                channels = persistentListOf(channel.copy(nextOutboundHtlcLimitMsat = 2_000_000uL)),
            )
        }

        val error = sut.moveAll(plan).exceptionOrNull()

        val cappedSats = MoveToSparkMath.amountAfterCappedFee(2_000uL, 10uL)
        assertEquals(24_874uL + cappedSats + 24_874uL * 2uL, assertIs<MoveToSparkError.FeeRose>(error).movedSats)
    }

    @Test
    fun `a move stops when the next part's fee would pass the quoted total`() = test {
        channelsRouting(50_000uL, maxPaySats = 30_000uL)
        val plan = sut.quoteAll().getOrThrow()

        val error = assertIs<MoveToSparkError.FeeRose>(sut.moveAll(plan).exceptionOrNull())

        assertEquals(MoveToSparkMath.cappedFeeSats(10uL, 50_000uL), plan.feeSats)
        assertEquals(MoveToSparkMath.amountAfterCappedFee(50_000uL, 10uL) / 2uL, error.movedSats)
        verify(lightningRepo, times(2)).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a part still in flight ends the move in flight after what the earlier parts moved`() = test {
        channelsRouting(100_000uL, maxRouteSats = 30_000uL) { payment -> payment == 1 }

        val outcome = sut.moveAll(sut.quoteAll().getOrThrow()).getOrThrow()

        assertEquals(MoveToSparkOutcome.InFlight(24_874uL, "hash", "id", earlierSats = 24_874uL), outcome)
    }

    @Test
    fun `a whole balance on a fee that grows with the amount is priced at the size it pays`() = test {
        // Small enough that the cap's unused headroom leaves no second part.
        channelsRouting(500_000uL, feeMsatOf = { 1_000uL + it * 1_000uL / 1_000uL })

        val plan = sut.quoteAll().getOrThrow()
        val outcome = sut.moveAll(plan).getOrThrow()

        assertEquals(MoveToSparkMath.amountAfterCappedFee(500_000uL, 499uL), plan.firstPart.amountSats)
        assertEquals(MoveToSparkOutcome.Moved(plan.firstPart.amountSats, feeSats = 499uL, feeMsat = 499_952uL), outcome)
        assertTrue(plan.firstPart.amountSats + plan.feeSats <= 500_000uL)
        verify(lightningRepo, times(1)).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a part grown past what the route carries keeps the size that routed`() = test {
        // Large enough that the first guess's fee is above the 50-sat cap, so the part tries to grow, to 19,950.
        channelsRouting(20_000uL, maxRouteSats = 19_900uL, feeMsatOf = { 0uL })

        val plan = sut.quoteAll().getOrThrow()

        assertTrue(MoveToSparkMath.amountAfterCappedFee(20_000uL, 0uL) in quotedSats)
        assertEquals(MoveToSparkMath.firstCandidate(20_000uL, 20_000uL), plan.firstPart.amountSats)
        assertEquals(MoveToSparkOutcome.Moved(19_898uL, feeSats = 0uL, feeMsat = 0uL), sut.moveAll(plan).getOrThrow())
    }

    @Test
    fun `fees are counted in msat, so rounding lends no later part budget`() = test {
        channelsRouting(100_000uL, maxRouteSats = 30_000uL, feeMsatOf = { 12_500uL })

        sut.moveAll(sut.quoteAll().getOrThrow())

        // Four parts quoted at 74 sats each; each part pays 12.5 sats of what is left.
        listOf(296_000uL, 283_500uL, 271_000uL, 258_500uL).forEach { capMsat ->
            verify(lightningRepo).payInvoiceOverLdk(any(), any(), anyOrNull(), eq(capMsat))
        }
    }

    @Test
    fun `a peer's htlc cap prices and moves the whole balance in capped parts`() = test {
        channelsRouting(100_000uL, htlcCapSats = 30_000uL)

        val plan = sut.quoteAll().getOrThrow()
        val outcome = sut.moveAll(plan).getOrThrow()

        assertEquals(MoveToSparkMath.amountAfterCappedFee(30_000uL, 10uL), plan.firstPart.amountSats)
        assertEquals(MoveToSparkMath.cappedFeeSats(10uL, 100_000uL) * 4uL, plan.feeSats)
        // Three parts of 29,930 sats, then 10,110 of the 10,180 left, leaving its cap's 60 unpaid sats.
        assertEquals(MoveToSparkOutcome.Moved(99_900uL, feeSats = 40uL, feeMsat = 40_000uL), outcome)
    }

    @Test
    fun `a part whose fee rose at send with no half left asks for a new quote`() = test {
        var feeMsat = 10_000uL
        channelsRouting(1_800uL, feeMsatOf = { feeMsat })
        val plan = sut.quoteAll().getOrThrow()
        // Past the part's 68-sat cap, which a smaller rise would still fit under.
        feeMsat = 100_000uL

        val error = sut.moveAll(plan).exceptionOrNull()

        assertEquals(MoveToSparkMath.amountAfterCappedFee(1_800uL, 10uL), plan.firstPart.amountSats)
        assertEquals(0uL, assertIs<MoveToSparkError.FeeRose>(error).movedSats)
    }

    @Test
    fun `a part that keeps failing to route at its quoted fee with no half left fails the move`() = test {
        channelsRouting(1_800uL, maxPaySats = 1_000uL)

        val error = sut.moveAll(sut.quoteAll().getOrThrow()).exceptionOrNull()

        assertIs<MoveToSparkError.TooLarge>(error)
    }

    @Test
    fun `a quote prices at most sixteen parts`() = test {
        channelsRouting(100_000uL, maxRouteSats = 2_000uL)

        val plan = sut.quoteAll().getOrThrow()

        assertEquals(1_554uL, plan.firstPart.amountSats)
        assertEquals(MoveToSparkMath.cappedFeeSats(10uL, 100_000uL) * 16uL, plan.feeSats)
    }

    @Test
    fun `the next part waits for a settled part's htlc to leave the send limit`() = test {
        channelsRouting(100_000uL, maxRouteSats = 60_000uL, heldSyncs = 3)

        val outcome = sut.moveAll(sut.quoteAll().getOrThrow()).getOrThrow()

        assertEquals(MoveToSparkOutcome.Moved(99_498uL, feeSats = 20uL, feeMsat = 20_000uL), outcome)
    }

    @Test
    fun `a move that stops at its last part waits for that part's htlc to leave the send limit`() = test {
        channelsRouting(100_000uL, maxRouteSats = 5_000uL, heldSyncs = 3)

        sut.moveAll(sut.quoteAll().getOrThrow()).getOrThrow()

        assertTrue(MoveToSparkMath.hasMovableBalance(sut.movableSats()), "the last part's htlc still held the limit")
    }

    @Test
    fun `the wait for a settled part's htlc leaves the cached channels alone while the node is not running`() = test {
        // A settled part's HTLC holds the send limit of a channel that still has capacity.
        lightningState.value = LightningState(
            channels = persistentListOf(
                createChannelDetails().copy(isUsable = true, isOutbound = false, outboundCapacityMsat = 60_000_000uL),
            ),
            nodeLifecycleState = NodeLifecycleState.Stopped,
        )

        sut.movableSatsSettled()

        verify(lightningRepo, never()).syncState()
    }

    @Test
    fun `a move fails when no part moves`() = test {
        channelsRouting(100_000uL)
        val plan = sut.quoteAll().getOrThrow()
        whenever(lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.failure(LdkError(NodeException.NotRunning("node stopped"))))

        assertIs<LdkError>(sut.moveAll(plan).exceptionOrNull())
    }

    @Test
    fun `a balance that routes in no part fails as too large`() = test {
        channelsRouting(100_000uL, maxRouteSats = 500uL)

        assertIs<MoveToSparkError.TooLarge>(sut.quoteAll().exceptionOrNull())
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
    fun `a channel with capacity left may hold the move's htlc unless its gap is the commitment fee`() {
        val held = channel("held", 0uL).copy(outboundCapacityMsat = 5_002_000uL)
        // 2 500 sat/kW: a legacy commitment keeps back 4 480 sats, so 5 342 sats of capacity send only 862.
        val legacy = channel("legacy", 862uL).copy(
            outboundCapacityMsat = 5_342_000uL,
            isOutbound = true,
            feerateSatPer1000Weight = 2_500u,
        )

        val emptied = MoveToSparkMath.emptiedChannels(listOf(channel("held", 50_000uL)), listOf(held))
        assertEquals(listOf("held"), emptied.map { it.channelId })
        assertTrue(MoveToSparkMath.mayHoldTheMoveHtlc(held), "a peer-funded channel with capacity left")
        assertTrue(MoveToSparkMath.mayHoldTheMoveHtlc(held.copy(isOutbound = true, feerateSatPer1000Weight = 2_500u)))
        assertFalse(MoveToSparkMath.mayHoldTheMoveHtlc(legacy), "a legacy reserve gap")
        // A legacy channel at 253 sat/kW reserves 540 sats above dust: 1 400 of capacity sends 860.
        val small = legacy.copy(outboundCapacityMsat = 1_400_000uL, feerateSatPer1000Weight = 253u)
        assertFalse(MoveToSparkMath.mayHoldTheMoveHtlc(small.copy(nextOutboundHtlcLimitMsat = 860_000uL)))
        assertTrue(MoveToSparkMath.mayHoldTheMoveHtlc(small.copy(nextOutboundHtlcLimitMsat = 947_000uL)))
        assertFalse(MoveToSparkMath.mayHoldTheMoveHtlc(held.copy(outboundCapacityMsat = 999_000uL)))
    }

    @Test
    fun `a channel we funded counts its cleared send limit toward a plan, also under a peer's htlc cap`() {
        val funded = createChannelDetails().copy(
            isOutbound = true,
            outboundCapacityMsat = 100_000_000uL,
            nextOutboundHtlcLimitMsat = 0uL,
            feerateSatPer1000Weight = 2_500u,
        )
        val anchorMsat = MoveToSparkMath.clearedLimitMsat(100_000_000L, 2_500L, isLegacy = false)
        val atReserve = funded.copy(nextOutboundHtlcLimitMsat = anchorMsat.toULong())

        assertEquals((anchorMsat / 1000L).toULong(), MoveToSparkMath.plannedSats(listOf(funded)))
        assertEquals((anchorMsat / 1000L).toULong(), MoveToSparkMath.plannedSats(listOf(atReserve)))
        val legacyMsat = MoveToSparkMath.clearedLimitMsat(100_000_000L, 4_000L, isLegacy = true)
        val legacy = funded.copy(feerateSatPer1000Weight = 4_000u, nextOutboundHtlcLimitMsat = legacyMsat.toULong())
        val restMsat = MoveToSparkMath.clearedLimitMsat(100_000_000L - legacyMsat, 4_000L, isLegacy = true)
        assertTrue(restMsat >= 1_000_000L)
        assertEquals(((legacyMsat + restMsat) / 1000L).toULong(), MoveToSparkMath.plannedSats(listOf(legacy)))
        val peerMin = legacy.copy(nextOutboundHtlcMinimumMsat = restMsat.toULong() + 1uL)
        assertEquals((legacyMsat / 1000L).toULong(), MoveToSparkMath.plannedSats(listOf(peerMin)))
        assertEquals(100_000uL, MoveToSparkMath.plannedSats(listOf(funded.copy(isOutbound = false))))
    }

    @Test
    fun `a plan leaves out a channel's rest that cannot be a part once the fee guess is reserved`() {
        // 2 904 sat/kW: a 100 000-sat legacy channel leaves a rest of exactly 1 000 sats, under the 1 012 a part needs.
        val limitMsat = MoveToSparkMath.clearedLimitMsat(100_000_000L, 2_904L, isLegacy = true)
        val restMsat = MoveToSparkMath.clearedLimitMsat(100_000_000L - limitMsat, 2_904L, isLegacy = true)
        val channel = legacyChannel(limitMsat, feerate = 2_904u)

        assertEquals(1_000L, restMsat / 1000L)
        assertEquals((limitMsat / 1000L).toULong(), MoveToSparkMath.plannedSats(listOf(channel)))
    }

    @Test
    fun `a plan counts small rests on several channels that one payment can route together`() {
        val limitMsat = MoveToSparkMath.clearedLimitMsat(100_000_000L, 2_500L, isLegacy = true)
        val restSats = (MoveToSparkMath.clearedLimitMsat(100_000_000L - limitMsat, 2_500L, isLegacy = true) / 1000L)
            .toULong()
        val channels = List(2) { legacyChannel(limitMsat, feerate = 2_500u).copy(channelId = "channel_$it") }

        assertFalse(MoveToSparkMath.hasMovableBalance(restSats))
        assertTrue(MoveToSparkMath.hasMovableBalance(restSats * 2uL))
        assertEquals(
            ((limitMsat / 1000L).toULong() + restSats) * 2uL,
            MoveToSparkMath.plannedSats(channels),
        )
    }

    private fun legacyChannel(limitMsat: Long, feerate: UInt) = createChannelDetails().copy(
        isOutbound = true,
        outboundCapacityMsat = 100_000_000uL,
        nextOutboundHtlcLimitMsat = limitMsat.toULong(),
        feerateSatPer1000Weight = feerate,
    )

    @Test
    fun `a channel with capacity left is emptied for good only on two unchanged reads at its reserve`() {
        // 4 000 sat/kW: a peer-capped 2-sat limit on 7 170 sats matches the 7 168-sat legacy reserve by chance.
        val capped = channel("capped", 2uL).copy(
            outboundCapacityMsat = 7_170_000uL,
            isOutbound = true,
            feerateSatPer1000Weight = 4_000u,
        )

        assertFalse(MoveToSparkMath.mayHoldTheMoveHtlc(capped))
        assertFalse(MoveToSparkMath.isEmptiedForGood(capped, previous = null), "closed on a single read")
        val risen = capped.copy(nextOutboundHtlcLimitMsat = 1_332_000uL)
        assertFalse(MoveToSparkMath.isEmptiedForGood(capped, previous = listOf(risen)), "closed on a changed read")
        assertTrue(MoveToSparkMath.isEmptiedForGood(capped, previous = listOf(capped)))
        assertTrue(MoveToSparkMath.isEmptiedForGood(channel("small", 0uL), previous = null))
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
        sendable(60_000uL)
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
    fun `update to send of the most it can send moves the fee's headroom too`() = test {
        sendable(5_000uL)
        sparkState.value = SparkState(isReady = true)
        whenever(sparkRepo.createInvoice(anyOrNull(), any(), any())).thenReturn(Result.success(INVOICE))
        whenever(lightningRepo.estimateLdkRoutingFeesForAmount(eq(INVOICE), any())).thenReturn(Result.success(5uL))
        val maxSats = MoveToSparkMath.combinedSendableSats(lightningState.value.channels, sparkState.value)
        whenever(sparkRepo.estimateFeeSats(INVOICE, maxSats)).thenReturn(Result.success(0uL))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        whenever(sparkRepo.canSend(maxSats)).thenReturn(true)
        val quote = sut.quote().getOrThrow()
        val updating = async { sut.updateToSend(quote, INVOICE, maxSats) }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 5_000uL))

        assertEquals(5_000uL - 7uL, updating.await().getOrThrow().amountSats)
        verify(lightningRepo).payInvoiceOverLdk(
            bolt11 = INVOICE,
            paymentHash = "hash",
            sats = 5_000uL - 7uL,
            maxTotalRoutingFeeMsat = 7_000uL,
        )
    }

    @Test
    fun `update to send of the most it can send prices the larger move on a fee that grows with the amount`() =
        test {
            channelsRouting(1_000_000uL, feeMsatOf = { it * 5uL })
            sparkState.value = SparkState(isReady = true)
            val maxSats = MoveToSparkMath.combinedSendableSats(lightningState.value.channels, sparkState.value)
            whenever(sparkRepo.estimateFeeSats(INVOICE, maxSats)).thenReturn(Result.success(0uL))
            whenever(sparkRepo.canSend(maxSats)).thenReturn(true)
            val quote = sut.quote().getOrThrow()

            val moved = sut.updateToSend(quote, INVOICE, maxSats).getOrThrow()

            assertTrue(moved.amountSats >= maxSats, "moved '${moved.amountSats}'")
            assertTrue(failedHashes.isEmpty(), "failed '$failedHashes'")
        }

    @Test
    fun `update to send moves nothing once another move took the channels and Spark covers the payment`() = test {
        sendable(50_100uL)
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())).doSuspendableAnswer {
            sendable(0uL)
            Result.success("id")
        }
        whenever(sparkRepo.refreshState()).doSuspendableAnswer {
            sparkState.value = SparkState(isReady = true, balanceSats = 70_000uL)
            Result.success(Unit)
        }
        val moving = async { sut.move(quote) }
        var moved: MoveToSparkOutcome? = null
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) { moved = it } }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertIs<MoveToSparkOutcome.Moved>(moving.await().getOrThrow())
        assertEquals(MoveToSparkOutcome.Moved(0uL, feeSats = 0uL), updating.await().getOrThrow())
        assertNull(moved)
        verify(lightningRepo, times(1)).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `update to send reads the channels from the node before it pays from them`() = test {
        sendable(0uL)
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        whenever(lightningRepo.syncState()).thenAnswer { sendable(60_000uL) }
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        whenever(sparkRepo.canSend(60_000uL)).thenReturn(true)
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 9uL), updating.await().getOrThrow())
    }

    @Test
    fun `update to send leaves the cached channels alone while the node is not running`() = test {
        sendable(60_000uL)
        whenever(lightningRepo.syncState()).thenAnswer { sendable(0uL) }
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        whenever(sparkRepo.canSend(60_000uL)).thenReturn(true)
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 9uL), updating.await().getOrThrow())
    }

    @Test
    fun `update to send still pays when the channels' limit moved a little since the quote`() = test {
        sendable(quote.amountSats + quote.feeSats - 1uL)
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        whenever(sparkRepo.canSend(60_000uL)).thenReturn(true)
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertEquals(MoveToSparkOutcome.Moved(50_000uL, feeSats = 9uL), updating.await().getOrThrow())
    }

    @Test
    fun `update to send fails short once another move took the channels and Spark does not cover the payment`() =
        test {
            sendable(0uL)
            sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
            whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
            whenever(sparkRepo.refreshState()).thenReturn(Result.success(Unit))

            val result = sut.updateToSend(quote, INVOICE, 60_000uL)

            assertIs<MoveToSparkError.UpdateFallsShort>(result.exceptionOrNull())
            verify(lightningRepo, never()).payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        }

    @Test
    fun `update to send waits for Spark without holding back another move`() = test {
        sendable(0uL)
        sparkState.value = SparkState(isReady = true, balanceSats = 20_000uL)
        whenever(sparkRepo.estimateFeeSats(INVOICE, 60_000uL)).thenReturn(Result.success(SPARK_FEE))
        whenever(sparkRepo.refreshState()).thenReturn(Result.success(Unit))
        whenever(
            lightningRepo.payInvoiceOverLdk(any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(Result.success("id"))
        val updating = async { sut.updateToSend(quote, INVOICE, 60_000uL) }
        val moving = async { sut.move(quote) }

        nodeEvents.emit(Event.PaymentSuccessful("id", "hash", null, 9_000uL))

        assertIs<MoveToSparkOutcome.Moved>(moving.await().getOrThrow())
        assertFalse(updating.isCompleted)
        sparkState.value = SparkState(isReady = true, balanceSats = 70_000uL)
        assertEquals(MoveToSparkOutcome.Moved(0uL, feeSats = 0uL), updating.await().getOrThrow())
    }

    @Test
    fun `update to send fails as unsettled while the move is in flight`() = test {
        sendable(60_000uL)
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
        sendable(60_000uL)
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
