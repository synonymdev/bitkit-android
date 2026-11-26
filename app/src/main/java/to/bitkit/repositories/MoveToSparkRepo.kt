package to.bitkit.repositories

import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.data.CacheStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.filterOpen
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.msatFloorOf
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import to.bitkit.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Moves the balance of the user's Lightning channels to their own Spark wallet, paying a Spark invoice over the
 * channels only. Blocktank is closing its channels, and this is the cheapest way to keep the balance in Spending.
 */
@Singleton
class MoveToSparkRepo internal constructor(
    private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
    private val cacheStore: CacheStore,
    private val activityRepo: Lazy<ActivityRepo>,
    private val paymentHashOf: (bolt11: String) -> String,
) {
    companion object {
        private const val TAG = "MoveToSparkRepo"
        private const val INVOICE_DESCRIPTION = "Move to Spark"
        private const val INVOICE_EXPIRY_SECS = 3600u
        private const val MAX_QUOTE_ROUNDS = 4

        /** How often, and how many times, the next part waits for a settled part's HTLC to leave the send limit. */
        private val HTLC_CLEAR_RECHECK = 2.seconds
        private const val HTLC_CLEAR_RECHECKS = 15

        /** The most parts one swipe moves; a balance left after them is quoted again. */
        private const val MAX_PARTS = 16

        /** How long the move waits for the payment to settle before it reports it as still in flight. */
        private val PAYMENT_TIMEOUT = 2.minutes

        /** How often a move waiting past its timeout reads the node's stored payment again, as the node may be down. */
        private val STORED_PAYMENT_RECHECK = 30.seconds

        /** How long a payment after an update waits for Spark to show the moved balance. */
        private val SPARK_BALANCE_WAIT = 1.minutes

        /** How often that wait reads Spark's balance again. */
        private val SPARK_BALANCE_RECHECK = 2.seconds

        /** The failures that mean no route carried the amount, so a smaller one may route. */
        private val NO_ROUTE_REASONS =
            setOf(PaymentFailureReason.ROUTE_NOT_FOUND, PaymentFailureReason.RETRIES_EXHAUSTED)
    }

    @Inject
    constructor(
        @BgDispatcher bgDispatcher: CoroutineDispatcher,
        lightningRepo: LightningRepo,
        sparkRepo: SparkRepo,
        cacheStore: CacheStore,
        activityRepo: Lazy<ActivityRepo>,
    ) : this(
        bgDispatcher,
        lightningRepo,
        sparkRepo,
        cacheStore,
        activityRepo,
        { Bolt11Invoice.fromStr(it).paymentHash() },
    )

    private val watchedPaymentHashes = ConcurrentHashMap.newKeySet<String>()

    /** One move at a time: the modal's and a payment's update would otherwise pay from the same channel balance. */
    private val moveLock = Mutex()
    private val reportedPaymentHashes = ConcurrentHashMap.newKeySet<String>()

    /** The payments of the larger parts a part still in flight replaced, by that part's hash, for when it settles. */
    private val retriedBeforeInFlight = ConcurrentHashMap<String, List<String>>()

    /**
     * Whether [paymentHash] is a move to Spark, whose send and receive the app does not announce as payments; also one
     * from before a restart.
     */
    suspend fun isMovePayment(paymentHash: String): Boolean =
        paymentHash.lowercase() in cacheStore.data.first().moveToSparkHashes

    /**
     * Whether the move screen waits on [paymentHash] or already reported its outcome, so the app raises no failure
     * notice of its own; after a timeout or cancellation the app does.
     */
    fun isWatchedMovePayment(paymentHash: String): Boolean =
        paymentHash in watchedPaymentHashes || paymentHash in reportedPaymentHashes

    /** The payment hashes of the moves to Spark, so Activity shows each as a move rather than a payment. */
    val moveHashes: Flow<Set<String>> = cacheStore.data.map { it.moveToSparkHashes.toSet() }.distinctUntilChanged()

    /** Whether the channels hold a balance worth moving to a usable Spark wallet. */
    val canMove: Flow<Boolean> = combine(lightningRepo.lightningState, sparkRepo.state) { lightning, spark ->
        MoveToSparkMath.canMove(lightning.channels, spark)
    }.distinctUntilChanged()

    /** The most the channels can send now, before routing fees. */
    fun movableSats(): ULong = MoveToSparkMath.movableSats(lightningRepo.lightningState.value.channels)

    /** The most a Lightning payment can send once the channels' balance is updated to Spark first. */
    fun combinedSendableSats(): ULong = MoveToSparkMath.combinedSendableSats(
        channels = lightningRepo.lightningState.value.channels.takeIf { canRunNode() },
        spark = sparkRepo.state.value,
    )

    /** The most a Lightning payment can send over the channels or Spark alone, without an update. */
    fun singleRailSendableSats(): ULong = MoveToSparkMath.singleRailSendableSats(
        channels = lightningRepo.lightningState.value.channels.takeIf { canRunNode() },
        spark = sparkRepo.state.value,
    )

    /** Whether paying [amountSats] needs the channels' balance updated to Spark first, as neither rail covers it. */
    fun needsUpdateToSend(amountSats: ULong): Boolean = MoveToSparkMath.needsUpdateToSend(
        amountSats = amountSats,
        channels = lightningRepo.lightningState.value.channels.takeIf { canRunNode() },
        spark = sparkRepo.state.value,
    )

    private fun canRunNode(): Boolean = lightningRepo.lightningState.value.nodeLifecycleState.canRun()

    /**
     * Updates the spending balance for a payment of [amountSats]: moves the channels' balance to Spark with
     * [quote], whose fee the user saw, and waits until Spark can pay the amount. A move still in flight, or a balance
     * Spark does not show in time, fails with [MoveToSparkError.UpdateUnsettled]; the funds then reach Spark on their
     * own. [onMove] gets the move's outcome as soon as the move returns, before the wait. A quote whose amount, with
     * Spark's balance, would not cover the payment of [bolt11] and Spark's fee for it, or a fee nothing can price,
     * fails with [MoveToSparkError.UpdateFallsShort] before any move. When another move, such as the modal's, took
     * the channels' balance before this one could pay, it moves nothing and calls no [onMove]: it returns once Spark
     * covers the payment with its fee, or fails with [MoveToSparkError.UpdateFallsShort] when Spark does not in time.
     */
    suspend fun updateToSend(
        quote: MoveToSparkQuote,
        bolt11: String,
        amountSats: ULong,
        onMove: (MoveToSparkOutcome) -> Unit = {},
    ): Result<MoveToSparkOutcome.Moved> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                val feeSats = feeAfterUpdateOrNull(bolt11, amountSats)
                    ?: throw MoveToSparkError.UpdateFallsShort(amountSats)
                if (!MoveToSparkMath.coversPayment(quote, sparkRepo.state.value, amountSats, feeSats)) {
                    throw MoveToSparkError.UpdateFallsShort(amountSats)
                }
                val outcome = moveUnlessCovered(quote, amountSats, feeSats)
                    ?: return@runSuspendCatching MoveToSparkOutcome.Moved(0uL, 0uL)
                onMove(outcome)
                val moved = outcome as? MoveToSparkOutcome.Moved ?: throw MoveToSparkError.UpdateUnsettled()
                val shown = withTimeoutOrNull(SPARK_BALANCE_WAIT) {
                    while (!sparkRepo.canSend(amountSats)) {
                        sparkRepo.refreshState()
                        if (!sparkRepo.canSend(amountSats)) delay(SPARK_BALANCE_RECHECK)
                    }
                }
                if (shown == null) throw MoveToSparkError.UpdateUnsettled()
                moved
            }.onFailure {
                Logger.warn("Failed to update the spending balance for '$amountSats' sats", it, context = TAG)
            }
        }

    private suspend fun moveUnlessCovered(
        quote: MoveToSparkQuote,
        amountSats: ULong,
        feeSats: ULong,
    ): MoveToSparkOutcome? {
        val outcome = moveLock.withLock {
            // Another move, such as the modal's, may have taken the channels' balance while this one waited for it.
            val fits = quote.amountSats.safe() + quote.feeSats.safe() <= movableSats()
            if (fits) payPart(quote) else null
        }
        if (outcome != null) return outcome
        val covered = withTimeoutOrNull(SPARK_BALANCE_WAIT) {
            while (!MoveToSparkMath.sparkCovers(sparkRepo.state.value, amountSats, feeSats)) {
                sparkRepo.refreshState()
                if (!MoveToSparkMath.sparkCovers(sparkRepo.state.value, amountSats, feeSats)) {
                    delay(SPARK_BALANCE_RECHECK)
                }
            }
        }
        if (covered == null) throw MoveToSparkError.UpdateFallsShort(amountSats)
        return null
    }

    /**
     * The fee Spark charges for paying [bolt11] once the spending balance is updated; Spark's service fee alone when
     * nothing can price it, or when there is no invoice yet, as for an LNURL before its callback.
     */
    suspend fun estimateFeeAfterUpdate(bolt11: String?, amountSats: ULong): ULong = withContext(bgDispatcher) {
        bolt11?.let { feeAfterUpdateOrNull(it, amountSats) } ?: SparkFees.serviceFeeSats(amountSats).also {
            Logger.debug("Fell back to Spark's service fee for an update payment", context = TAG)
        }
    }

    /**
     * Spark's fee for paying [bolt11] once the spending balance is updated, from Spark's own quote, which prices the
     * amount whatever its balance; null when Spark cannot price it.
     */
    private suspend fun feeAfterUpdateOrNull(bolt11: String, amountSats: ULong): ULong? =
        sparkRepo.estimateFeeSats(bolt11, amountSats).getOrNull()

    /**
     * Quotes moving [targetSats], or everything the channels can send, to a new Spark invoice.
     * The amount leaves room for the routing fee, which the quote carries and the move caps the payment at.
     */
    suspend fun quote(targetSats: ULong? = null): Result<MoveToSparkQuote> = withContext(bgDispatcher) {
        runSuspendCatching {
            val limitSats = movableSats()
            val wantedSats = targetSats?.coerceAtMost(limitSats) ?: limitSats
            if (wantedSats < MoveToSparkMath.MIN_MOVE_SATS || !MoveToSparkMath.hasMovableBalance(limitSats)) {
                throw MoveToSparkError.NothingToMove()
            }
            if (!MoveToSparkMath.isSparkUsable(sparkRepo.state.value)) throw MoveToSparkError.SparkUnavailable()

            val invoice = sparkRepo.createInvoice(null, INVOICE_DESCRIPTION, INVOICE_EXPIRY_SECS).getOrThrow()
            var amountSats = MoveToSparkMath.firstCandidate(wantedSats, limitSats)
            var fitting: MoveToSparkQuote? = null
            // The first amount leaves room for a guessed fee; once the real fee is known, the amount grows to fit it.
            repeat(MAX_QUOTE_ROUNDS) {
                if (amountSats < MoveToSparkMath.MIN_MOVE_SATS) return@repeat
                val estimateSats = lightningRepo.estimateLdkRoutingFeesForAmount(invoice, amountSats).getOrElse {
                    fitting?.let { quote -> return@runSuspendCatching quote }
                    throw if (it.isNoRoute()) MoveToSparkError.TooLarge(amountSats, it) else it
                }
                val feeSats = MoveToSparkMath.quotedFeeSats(estimateSats)
                val nextSats = minOf(wantedSats, MoveToSparkMath.amountAfterFee(limitSats, estimateSats))
                if (amountSats.safe() + feeSats.safe() <= limitSats) {
                    fitting = MoveToSparkQuote(invoice, amountSats, feeSats)
                    if (nextSats <= amountSats) return@runSuspendCatching checkNotNull(fitting)
                }
                amountSats = nextSats
            }
            fitting ?: throw MoveToSparkError.TooLarge(amountSats)
        }.onFailure {
            Logger.warn("Failed to quote move to Spark of '$targetSats'", it, context = TAG)
        }
    }

    /**
     * Quotes moving the whole balance: one payment when it routes, else parts, each halved until it routes, priced on
     * one invoice until what is left is below [MoveToSparkMath.MIN_MOVE_SATS]. The plan's fee is the sum of the parts'
     * fees, the most the move pays.
     */
    suspend fun quoteAll(): Result<MoveToSparkPlan> = withContext(bgDispatcher) {
        runSuspendCatching {
            val limitSats = movableSats()
            if (!MoveToSparkMath.hasMovableBalance(limitSats)) throw MoveToSparkError.NothingToMove()
            if (!MoveToSparkMath.isSparkUsable(sparkRepo.state.value)) throw MoveToSparkError.SparkUnavailable()

            val invoice = sparkRepo.createInvoice(null, INVOICE_DESCRIPTION, INVOICE_EXPIRY_SECS).getOrThrow()
            val parts = mutableListOf<MoveToSparkQuote>()
            val usable = lightningRepo.lightningState.value.channels.filter { it.isUsable }
            val plannedSats = MoveToSparkMath.plannedSats(usable)
            var leftSats = maxOf(limitSats, plannedSats)
            var wantedSats = limitSats
            while (parts.size < MAX_PARTS && MoveToSparkMath.hasMovableBalance(leftSats)) {
                val part = probePart(invoice, wantedSats, minOf(leftSats, limitSats)) ?: break
                parts += part
                leftSats = leftSats.safe() - (part.amountSats.safe() + part.feeSats.safe()).safe()
                wantedSats = part.amountSats
            }
            if (parts.isEmpty()) throw MoveToSparkError.TooLarge(limitSats)
            MoveToSparkPlan(parts.first(), parts.fold(0uL) { total, part -> total.safe() + part.feeSats.safe() })
        }.onFailure {
            Logger.warn("Failed to quote moving the balance to Spark", it, context = TAG)
        }
    }

    private suspend fun probePart(invoice: String, wantedSats: ULong, limitSats: ULong): MoveToSparkQuote? {
        var amountSats = MoveToSparkMath.firstCandidate(wantedSats, limitSats)
        var isHalved = false
        var probedSats: ULong? = null
        while (probedSats == null) {
            if (amountSats < MoveToSparkMath.MIN_MOVE_SATS) return null
            val estimated = lightningRepo.estimateLdkRoutingFeesForAmount(invoice, amountSats)
            estimated.exceptionOrNull()?.let { if (!it.isNoRoute()) throw it }
            probedSats = estimated.getOrNull()
            if (probedSats == null) {
                amountSats = MoveToSparkMath.smallerAmount(amountSats) ?: return null
                isHalved = true
            }
        }
        var estimateSats: ULong = probedSats
        val firstSats = minOf(amountSats, MoveToSparkMath.amountAfterFee(limitSats, estimateSats))
        if (firstSats < MoveToSparkMath.MIN_MOVE_SATS) return null
        var fitting = MoveToSparkQuote(invoice, firstSats, MoveToSparkMath.quotedFeeSats(estimateSats))
        if (isHalved) return fitting
        repeat(MAX_QUOTE_ROUNDS) {
            val grownSats = minOf(wantedSats, MoveToSparkMath.amountAfterFee(limitSats, estimateSats))
            if (grownSats <= fitting.amountSats) return fitting
            estimateSats = lightningRepo.estimateLdkRoutingFeesForAmount(invoice, grownSats).getOrNull()
                ?: return fitting
            val feeSats = MoveToSparkMath.quotedFeeSats(estimateSats)
            if (grownSats.safe() + feeSats.safe() <= limitSats) fitting = MoveToSparkQuote(invoice, grownSats, feeSats)
        }
        return fitting
    }

    /**
     * Moves the whole balance as [plan] quoted: pays its first part, then quotes and pays the next on a new invoice
     * from what the channels can still send, halving a part that does not route, until what is left is below
     * [MoveToSparkMath.MIN_MOVE_SATS]. Each part's fee is capped at what the plan's fee leaves, so the move never pays
     * more than the user saw; a part quoted above it fails the move with [MoveToSparkError.FeeRose], so the user can
     * see the new fee. It moves at most [MAX_PARTS] parts. A part still in flight ends the move in flight. Otherwise
     * fails only when no part moved; a balance it leaves is quoted again by the caller.
     */
    suspend fun moveAll(plan: MoveToSparkPlan): Result<MoveToSparkOutcome> = withContext(bgDispatcher) {
        runSuspendCatching {
            moveLock.withLock { payParts(plan) }
        }.onFailure {
            Logger.error("Failed to move the balance to Spark", it, context = TAG)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun payParts(plan: MoveToSparkPlan): MoveToSparkOutcome {
        var next: MoveToSparkQuote? = plan.firstPart
        var partSats = plan.firstPart.amountSats
        var feeLeftMsat = MoveToSparkMath.feeCapMsat(plan.feeSats)
        var movedSats = 0uL
        var paidMsat = 0uL
        var parts = 0
        var lastError: Throwable? = null
        var lastOutboundMsat = outboundMsat()
        val retriedHashes = mutableListOf<String>()
        while (parts < MAX_PARTS) {
            val quote = next ?: nextPart(partSats)?.getOrElse {
                lastError = it
                null
            } ?: break
            next = null
            if (MoveToSparkMath.feeCapMsat(quote.feeSats) > feeLeftMsat) throw feeRoseError(movedSats, paidMsat)
            val outcome = runSuspendCatching { payPart(quote, feeCapMsat = feeLeftMsat) }.getOrElse {
                lastError = it
                if (it !is MoveToSparkError.TooLarge) break
                retriedHashes += paymentHashOf(quote.invoice).lowercase()
                lightningRepo.syncState()
                partSats = MoveToSparkMath.smallerAmount(quote.amountSats) ?: run {
                    if (feeRose(quote.amountSats, feeLeftMsat)) throw feeRoseError(movedSats, paidMsat)
                    break
                }
                continue
            }
            when (outcome) {
                is MoveToSparkOutcome.InFlight -> {
                    if (retriedHashes.isNotEmpty()) retriedBeforeInFlight[outcome.paymentHash] = retriedHashes.toList()
                    return outcome.copy(earlierSats = movedSats)
                }
                is MoveToSparkOutcome.Moved -> {
                    parts++
                    movedSats = movedSats.safe() + outcome.amountSats.safe()
                    paidMsat = paidMsat.safe() + outcome.feeMsat.safe()
                    feeLeftMsat = feeLeftMsat.safe() - outcome.feeMsat.safe()
                    dropFromActivity(retriedHashes)
                    retriedHashes.clear()
                    lightningRepo.syncState()
                    val nowMsat = outboundMsat()
                    if (nowMsat >= lastOutboundMsat) break
                    lastOutboundMsat = nowMsat
                }
            }
        }
        if (movedSats == 0uL) throw lastError ?: MoveToSparkError.NothingToMove()
        return MoveToSparkOutcome.Moved(movedSats, paidMsat / 1000uL, paidMsat)
    }

    private suspend fun dropFromActivity(retriedHashes: List<String>) = retriedHashes.forEach { hash ->
        // A smaller part moved what the larger ones could not, so their failures are no failed moves.
        runSuspendCatching {
            cacheStore.addActivityToDeletedList(hash)
            activityRepo.get().handlePaymentEvent(hash)
        }.onFailure { Logger.warn("Failed to drop the retried move part '$hash' from Activity", it, context = TAG) }
    }

    private fun feeRoseError(movedSats: ULong, paidMsat: ULong) = MoveToSparkError.FeeRose(movedSats, paidMsat / 1000uL)

    private suspend fun feeRose(amountSats: ULong, feeLeftMsat: ULong): Boolean =
        quote(amountSats).getOrNull()?.let { MoveToSparkMath.feeCapMsat(it.feeSats) > feeLeftMsat } ?: false

    private fun outboundMsat(): ULong = lightningRepo.lightningState.value.channels
        .fold(0uL) { total, channel -> total.safe() + channel.outboundCapacityMsat.safe() }

    private suspend fun nextPart(partSats: ULong): Result<MoveToSparkQuote>? {
        var rechecks = 0
        while (!MoveToSparkMath.hasMovableBalance(movableSats()) && rechecks++ < HTLC_CLEAR_RECHECKS &&
            lightningRepo.lightningState.value.channels.any { it.isUsable && MoveToSparkMath.mayHoldTheMoveHtlc(it) }
        ) {
            delay(HTLC_CLEAR_RECHECK)
            lightningRepo.syncState()
        }
        if (!MoveToSparkMath.hasMovableBalance(movableSats())) return null
        return quoteRoutingPart(partSats)
    }

    private suspend fun quoteRoutingPart(targetSats: ULong?): Result<MoveToSparkQuote> {
        var target = targetSats
        while (true) {
            val quoted = quote(target)
            val tooLarge = quoted.exceptionOrNull() as? MoveToSparkError.TooLarge ?: return quoted
            target = MoveToSparkMath.smallerAmount(tooLarge.amountSats) ?: return quoted
        }
    }

    /** Pays [quote] over the channels and waits for it to settle in Spark. */
    suspend fun move(quote: MoveToSparkQuote): Result<MoveToSparkOutcome> = withContext(bgDispatcher) {
        runSuspendCatching {
            moveLock.withLock { payPart(quote) }
        }.onFailure {
            Logger.error("Failed to move '${quote.amountSats}' sats to Spark", it, context = TAG)
        }
    }

    private suspend fun payPart(
        quote: MoveToSparkQuote,
        feeCapMsat: ULong = MoveToSparkMath.feeCapMsat(quote.feeSats),
    ): MoveToSparkOutcome {
        val paymentHash = paymentHashOf(quote.invoice)
        cacheStore.addMoveToSparkHash(paymentHash)
        watchedPaymentHashes += paymentHash
        try {
            return payAndWait(quote, paymentHash, feeCapMsat)
        } finally {
            watchedPaymentHashes -= paymentHash
        }
    }

    private suspend fun payAndWait(
        quote: MoveToSparkQuote,
        paymentHash: String,
        feeCapMsat: ULong,
    ): MoveToSparkOutcome =
        coroutineScope {
            // LDK can report a failure without its hash, so the payment's id identifies it too once it is known.
            val paymentId = AtomicReference<String?>(null)
            // A failure without a hash can arrive before the send returns its id, so it is kept until the id is known.
            val hashlessFailures = ConcurrentLinkedQueue<Event.PaymentFailed>()
            val settled = async(start = CoroutineStart.UNDISPATCHED) {
                lightningRepo.nodeEvents.first {
                    if (it is Event.PaymentFailed && it.paymentHash == null && paymentId.get() == null) {
                        hashlessFailures += it
                    }
                    it.settles(paymentHash, paymentId.get())
                }
            }
            val sentId = lightningRepo.payInvoiceOverLdk(
                bolt11 = quote.invoice,
                paymentHash = paymentHash,
                sats = quote.amountSats,
                maxTotalRoutingFeeMsat = feeCapMsat,
            ).onSuccess { paymentId.set(it) }.getOrElse {
                settled.cancel()
                val isNoRoute = it.isNoRoute() || it.isSendWithoutRoute(quote.invoice)
                throw if (isNoRoute) MoveToSparkError.TooLarge(quote.amountSats, it) else it
            }
            val earlyFailure = hashlessFailures.firstOrNull { it.paymentId == sentId }
            val event = earlyFailure?.also { settled.cancel() }
                ?: withTimeoutOrNull(PAYMENT_TIMEOUT) { settled.await() }
            if (event != null) reportedPaymentHashes += paymentHash
            when (event) {
                is Event.PaymentSuccessful -> event.moved(quote.amountSats)
                is Event.PaymentFailed if event.reason in NO_ROUTE_REASONS ->
                    throw MoveToSparkError.TooLarge(quote.amountSats, reason = event.reason)
                is Event.PaymentFailed -> throw MoveToSparkError.PaymentFailed(event.reason)
                else -> {
                    settled.cancel()
                    MoveToSparkOutcome.InFlight(quote.amountSats, paymentHash, sentId)
                }
            }
        }

    /**
     * Waits for the payment of a move still [inFlight] to settle after the move stopped waiting. It listens first, then
     * reads the node's stored payment, so an outcome that arrived after the move stopped listening is not missed, and
     * reads it again while it waits, as the node may be down for a read.
     */
    suspend fun awaitSettled(inFlight: MoveToSparkOutcome.InFlight): Result<MoveToSparkOutcome.Moved> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                coroutineScope {
                    val settled = async(start = CoroutineStart.UNDISPATCHED) {
                        lightningRepo.nodeEvents.first { it.settles(inFlight.paymentHash, inFlight.paymentId) }
                    }
                    var moved: MoveToSparkOutcome.Moved? = null
                    while (moved == null) {
                        val stored = lightningRepo.listPaymentsOrNull()?.firstOrNull { it.id == inFlight.paymentId }
                        moved = when (stored?.status) {
                            PaymentStatus.SUCCEEDED ->
                                MoveToSparkOutcome.Moved(
                                    amountSats = inFlight.amountSats,
                                    feeSats = (stored.feePaidMsat ?: 0uL) / 1000uL,
                                    feeMsat = stored.feePaidMsat ?: 0uL,
                                )
                            PaymentStatus.FAILED -> throw MoveToSparkError.PaymentFailed(null)
                            else -> withTimeoutOrNull(STORED_PAYMENT_RECHECK) { settled.await() }?.let {
                                (it as? Event.PaymentSuccessful)?.moved(inFlight.amountSats)
                                    ?: throw MoveToSparkError.PaymentFailed((it as? Event.PaymentFailed)?.reason)
                            }
                        }
                    }
                    settled.cancel()
                    retriedBeforeInFlight.remove(inFlight.paymentHash)?.let { dropFromActivity(it) }
                    moved
                }
            }.onFailure {
                if (it is MoveToSparkError.PaymentFailed) retriedBeforeInFlight.remove(inFlight.paymentHash)
                Logger.warn("Failed to move '${inFlight.amountSats}' sats to Spark after the wait", it, context = TAG)
            }
        }

    private fun Throwable.isNoRoute(): Boolean = this is NodeException.RouteNotFound ||
        (this as? LdkError)?.compactType == NodeException.RouteNotFound::class.simpleName

    /** LDK reports a route lost since the quote, before any payment exists, as a sending failure on a live invoice. */
    private fun Throwable.isSendWithoutRoute(invoice: String): Boolean {
        val isSendingFailed = this is NodeException.PaymentSendingFailed ||
            (this as? LdkError)?.compactType == NodeException.PaymentSendingFailed::class.simpleName
        return isSendingFailed && runCatching { !Bolt11Invoice.fromStr(invoice).isExpired() }.getOrDefault(false)
    }

    private fun Event.PaymentSuccessful.moved(amountSats: ULong) = MoveToSparkOutcome.Moved(
        amountSats = amountSats,
        feeSats = (feePaidMsat ?: 0uL) / 1000uL,
        feeMsat = feePaidMsat ?: 0uL,
    )

    private fun Event.settles(paymentHash: String, paymentId: String?): Boolean = when (this) {
        is Event.PaymentSuccessful -> this.paymentHash == paymentHash
        is Event.PaymentFailed -> this.paymentHash == paymentHash || (paymentId != null && this.paymentId == paymentId)
        else -> false
    }
}

/** The amounts behind moving the channels' balance, kept apart so they can be tested without a node. */
object MoveToSparkMath {
    /** Below this a move is not offered: what is left is about the routing fee. */
    const val MIN_MOVE_SATS = 1_000uL
    private const val ANCHOR_COMMITMENT_WEIGHT = 1_124L
    private const val LEGACY_COMMITMENT_WEIGHT = 724L
    private const val HTLC_OUTPUT_WEIGHT = 172L
    private const val HTLC_TIMEOUT_WEIGHT = 663L
    private const val DUST_LIMIT_SATS = 354L

    /** The fee guessed for the first quote of a full move, as a share of the amount in parts per million. */
    private const val FIRST_FEE_GUESS_PPM = 5_000uL

    /** The least fee guessed for the first quote of a full move. */
    private const val MIN_FEE_GUESS_SATS = 10uL

    /** Room left above the quoted fee, in sats, for the fee moving between the quote and the payment. */
    private const val FEE_MARGIN_SATS = 2uL

    fun movableSats(channels: List<ChannelDetails>?): ULong = channels.totalNextOutboundHtlcLimitSats()

    /** Whether Spark can take a move; every Move to Spark check goes through here. */
    fun isSparkUsable(spark: SparkState): Boolean = spark.isReady && !spark.isExiting

    /** Whether the channels hold a balance worth moving to a usable Spark wallet. */
    fun canMove(channels: List<ChannelDetails>?, spark: SparkState): Boolean =
        isSparkUsable(spark) && hasMovableBalance(movableSats(channels))

    /** Whether [limitSats] still leaves at least [MIN_MOVE_SATS] to quote once the first fee guess is reserved. */
    fun hasMovableBalance(limitSats: ULong): Boolean = firstCandidate(limitSats, limitSats) >= MIN_MOVE_SATS

    /** The first amount to quote: [wantedSats] when it leaves room for fees, else the limit less a guessed fee. */
    fun firstCandidate(wantedSats: ULong, limitSats: ULong): ULong {
        val guessSats = maxOf(limitSats.safe() * FIRST_FEE_GUESS_PPM.safe() / 1_000_000uL, MIN_FEE_GUESS_SATS)
        return minOf(wantedSats, amountAfterFee(limitSats, guessSats))
    }

    /** The amount that fits under [limitSats] together with the fee quoted for [estimateSats]. */
    fun amountAfterFee(limitSats: ULong, estimateSats: ULong): ULong =
        limitSats.safe() - quotedFeeSats(estimateSats).safe()

    /** The routing fee to show for an estimate: the estimate and its margin, which also covers its rounding down. */
    fun quotedFeeSats(estimateSats: ULong): ULong = estimateSats.safe() + FEE_MARGIN_SATS.safe()

    /** The routing fee cap for the payment: exactly the quoted fee, so it never pays more than shown. */
    fun feeCapMsat(feeSats: ULong): ULong = feeSats.safe() * 1000uL.safe()

    /** The most a Lightning payment can send: over the channels, from Spark, or from Spark after an update. */
    fun maxSendSats(channels: List<ChannelDetails>?, spark: SparkState): ULong =
        maxOf(movableSats(channels), spark.sendableSats, combinedSendableSats(channels, spark))

    /** The next part to try after one did not route: half of it, or nothing below the minimum. */
    fun smallerAmount(sats: ULong): ULong? = (sats / 2uL).takeIf { it >= MIN_MOVE_SATS }

    /**
     * The most Spark can send once the channels' balance less a guessed routing fee has moved to it; 0 when nothing
     * can move.
     */
    fun combinedSendableSats(channels: List<ChannelDetails>?, spark: SparkState): ULong {
        if (!canMove(channels, spark)) return 0uL
        val limitSats = movableSats(channels)
        return SparkFees.sendableSats(spark.balanceSats.safe() + firstCandidate(limitSats, limitSats).safe())
    }

    /** The most the channels or a ready Spark can send alone. */
    fun singleRailSendableSats(channels: List<ChannelDetails>?, spark: SparkState): ULong =
        maxOf(movableSats(channels), spark.sendableSats.takeIf { spark.isReady } ?: 0uL)

    /** Whether [amountSats] fits neither the channels nor Spark alone, but fits Spark once the channels moved to it. */
    fun needsUpdateToSend(amountSats: ULong, channels: List<ChannelDetails>?, spark: SparkState): Boolean =
        amountSats > singleRailSendableSats(channels, spark) && amountSats <= combinedSendableSats(channels, spark)

    /**
     * Whether Spark can pay [amountSats] and its [feeSats] for the payment once [quote] has moved to it, also by the
     * rule the wait after the move applies, which keeps Spark's service fee aside.
     */
    fun coversPayment(quote: MoveToSparkQuote, spark: SparkState, amountSats: ULong, feeSats: ULong): Boolean =
        covers(spark.isUsable, spark.balanceSats.safe() + quote.amountSats.safe(), amountSats, feeSats)

    /** Whether Spark can pay [amountSats] and its [feeSats] for the payment from its balance now. */
    fun sparkCovers(spark: SparkState, amountSats: ULong, feeSats: ULong): Boolean =
        covers(spark.isUsable, spark.balanceSats, amountSats, feeSats)

    private fun covers(isUsable: Boolean, haveSats: ULong, amountSats: ULong, feeSats: ULong): Boolean {
        if (!isUsable || haveSats < amountSats.safe() + feeSats.safe()) return false
        return SparkFees.sendableSats(haveSats) >= amountSats
    }

    /** The open channels in [after] that could send at least [MIN_MOVE_SATS] in [before] and no longer can. */
    fun emptiedChannels(before: List<ChannelDetails>, after: List<ChannelDetails>?): List<ChannelDetails> {
        val beforeById = before.associateBy { it.channelId }
        return after.orEmpty().filterOpen().filter {
            val was = beforeById[it.channelId] ?: return@filter false
            msatFloorOf(was.nextOutboundHtlcLimitMsat) >= MIN_MOVE_SATS &&
                msatFloorOf(it.nextOutboundHtlcLimitMsat) < MIN_MOVE_SATS
        }
    }

    /**
     * Whether a channel's send limit may still be held under the minimum by the move's settled HTLC counting against
     * the peer's in-flight cap, which clears, rather than by what stays. ldk-node exposes neither the HTLCs nor the
     * channel type, so it is ruled out only when the capacity itself is under the minimum, or when the limit read is,
     * within a sat, the one LDK leaves on a channel we funded once its HTLCs clear, for an anchor or a legacy
     * commitment.
     */
    fun mayHoldTheMoveHtlc(channel: ChannelDetails): Boolean {
        if (msatFloorOf(channel.outboundCapacityMsat) < MIN_MOVE_SATS) return false
        if (!channel.isOutbound) return true
        val limitSats = msatFloorOf(channel.nextOutboundHtlcLimitMsat).toLong()
        return listOf(false, true).none { isLegacy ->
            val predictedSats = clearedLimitMsat(
                capacityMsat = channel.outboundCapacityMsat.toLong(),
                feerate = channel.feerateSatPer1000Weight.toLong(),
                isLegacy = isLegacy,
            ) / 1000
            abs(limitSats - predictedSats) <= 1
        }
    }

    /**
     * What [channels] can send across a move's parts: each one's outbound capacity, less the commitment fee a channel
     * we funded keeps back. Such a channel counts its send limit when the limit is what it clears to, and LDK leaves
     * what the rest clears to once that limit has moved under the dust limit. Those rests count when each passes its
     * peer's HTLC minimum and together they can be a part, as one payment can route them across the channels. When a
     * peer's HTLC cap holds the limit lower, it counts the larger of the two commitment types' predictions, within the
     * limit and capacity.
     */
    fun plannedSats(channels: List<ChannelDetails>): ULong {
        var sentSats = 0uL
        var restSats = 0uL
        channels.forEach {
            val (sent, rest) = plannedParts(it)
            sentSats = sentSats.safe() + sent.safe()
            restSats = restSats.safe() + rest.safe()
        }
        return if (hasMovableBalance(restSats)) sentSats.safe() + restSats.safe() else sentSats
    }

    private fun plannedParts(channel: ChannelDetails): Pair<ULong, ULong> {
        if (!channel.isOutbound) return msatFloorOf(channel.outboundCapacityMsat) to 0uL
        val limitMsat = channel.nextOutboundHtlcLimitMsat
        val capacityMsat = channel.outboundCapacityMsat.toLong()
        val feerate = channel.feerateSatPer1000Weight.toLong()
        val types = listOf(false, true)
        val clearedMsat = types.map { clearedLimitMsat(capacityMsat, feerate, isLegacy = it).coerceAtLeast(0L) }
        val matched = types.filterIndexed { i, _ ->
            msatFloorOf(clearedMsat[i].toULong()).toLong() - msatFloorOf(limitMsat).toLong() in -1L..1L
        }
        if (matched.isNotEmpty()) {
            val restMsat = (capacityMsat - limitMsat.toLong()).coerceAtLeast(0L)
            val leftMsat = matched.minOf { clearedLimitMsat(restMsat, feerate, isLegacy = it) }
                .coerceAtLeast(0L)
                .toULong()
            val restSats = if (leftMsat < channel.nextOutboundHtlcMinimumMsat) 0uL else msatFloorOf(leftMsat)
            return msatFloorOf(limitMsat) to restSats
        }
        return msatFloorOf(maxOf(limitMsat, minOf(channel.outboundCapacityMsat, clearedMsat.max().toULong()))) to 0uL
    }

    /**
     * The send limit LDK 0.2 leaves on a channel we funded with no HTLCs pending (`get_available_balances_for_scope`):
     * capacity less the commitment fee for an above-dust HTLC and its fee spike buffer HTLC, doubled for a legacy
     * commitment; under the dust limit it reserves one HTLC less and stays under the limit. The dust limit is LDK's
     * default 354 sats, plus the HTLC timeout transaction's fee on a legacy commitment.
     */
    fun clearedLimitMsat(capacityMsat: Long, feerate: Long, isLegacy: Boolean): Long {
        val baseWeight = if (isLegacy) LEGACY_COMMITMENT_WEIGHT else ANCHOR_COMMITMENT_WEIGHT
        val multiplier = if (isLegacy) 2 else 1
        fun feeMsat(htlcs: Long) = feerate * (baseWeight + htlcs * HTLC_OUTPUT_WEIGHT) / 1000 * 1000 * multiplier
        val dustMsat = ((if (isLegacy) feerate * HTLC_TIMEOUT_WEIGHT / 1000 else 0L) + DUST_LIMIT_SATS) * 1000
        val aboveDust = capacityMsat - feeMsat(2)
        if (aboveDust >= dustMsat) return aboveDust
        return maxOf(0L, minOf(capacityMsat - feeMsat(1), dustMsat - 1, capacityMsat))
    }

    /**
     * Whether [channel] can close: its capacity is under the minimum, or its reserve explains its limit in this read
     * and in the one before ([previous]), unchanged. One read can match a reserve by chance while the move's HTLC still
     * counts against the peer's cap; the HTLC clears within the recheck, so a reading that holds across it is what
     * stays.
     */
    fun isEmptiedForGood(channel: ChannelDetails, previous: List<ChannelDetails>?): Boolean {
        if (msatFloorOf(channel.outboundCapacityMsat) < MIN_MOVE_SATS) return true
        if (mayHoldTheMoveHtlc(channel)) return false
        val was = previous?.firstOrNull { it.channelId == channel.channelId } ?: return false
        return was.outboundCapacityMsat == channel.outboundCapacityMsat &&
            was.nextOutboundHtlcLimitMsat == channel.nextOutboundHtlcLimitMsat
    }

    /**
     * The payments read before and after a read of the channels, or null when a read failed or an outbound Lightning
     * payment appeared or changed between them: it may have held an HTLC while the channels were read. Also null when a
     * payment other than a move's changed since the move started ([moveStart]): it may have emptied another channel.
     */
    fun paymentsAround(
        moveStart: List<PaymentDetails>?,
        before: List<PaymentDetails>?,
        after: List<PaymentDetails>?,
        isMovePayment: (id: String) -> Boolean,
    ): List<PaymentDetails>? {
        if (moveStart == null || before == null || after == null) return null
        if (outboundLightning(before) != outboundLightning(after)) return null
        val othersAtStart = outboundLightning(moveStart).filterNot { isMovePayment(it.first) }.toSet()
        if (othersAtStart != outboundLightning(before).filterNot { isMovePayment(it.first) }.toSet()) return null
        return before + after
    }

    private fun outboundLightning(payments: List<PaymentDetails>) = payments
        .filter { it.direction == PaymentDirection.OUTBOUND && it.kind !is PaymentKind.Onchain }
        .map { Triple(it.id, it.status, it.latestUpdateTimestamp) }
        .toSet()
}

data class MoveToSparkQuote(
    val invoice: String,
    val amountSats: ULong,
    val feeSats: ULong,
)

/** A move of the whole balance: its [firstPart], and [feeSats], the sum of its parts' fees and the most it pays. */
data class MoveToSparkPlan(
    val firstPart: MoveToSparkQuote,
    val feeSats: ULong,
)

sealed interface MoveToSparkOutcome {
    val amountSats: ULong

    /** [feeMsat] is the exact fee paid, which [feeSats] rounds down. */
    data class Moved(
        override val amountSats: ULong,
        val feeSats: ULong,
        val feeMsat: ULong = feeSats * 1000uL,
    ) : MoveToSparkOutcome

    /**
     * The payment of [amountSats] left but had not settled when the move stopped waiting; Spark shows it once it does.
     * [earlierSats] is what earlier parts of the same move had moved.
     */
    data class InFlight(
        override val amountSats: ULong,
        val paymentHash: String,
        val paymentId: String,
        val earlierSats: ULong = 0uL,
    ) : MoveToSparkOutcome
}

sealed class MoveToSparkError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    class NothingToMove : MoveToSparkError("The channels have nothing to move")
    class SparkUnavailable : MoveToSparkError("Spark is not ready to receive")

    /** [amountSats] did not route from the channels to Spark in one payment. */
    class TooLarge(
        val amountSats: ULong,
        cause: Throwable? = null,
        val reason: PaymentFailureReason? = null,
    ) : MoveToSparkError("Could not route '$amountSats' sats to Spark, reason '$reason'", cause)

    /** The quoted move, with Spark's balance, would not cover a payment of [amountSats], so nothing moved. */
    class UpdateFallsShort(val amountSats: ULong) :
        MoveToSparkError("An update would not cover a payment of '$amountSats' sats")

    /** The update's fee is new or above the one the user saw, so nothing moved until they see it. */
    class UpdateFeeUnseen : MoveToSparkError("The update fee changed since the user saw it")

    /** The next part's fee would pass what the user saw for the move, after [movedSats] had moved. */
    class FeeRose(
        val movedSats: ULong,
        val feeSats: ULong = 0uL,
    ) : MoveToSparkError("The move's fee rose after '$movedSats' sats moved")

    /** The quote for the update did not finish in time, so nothing moved. */
    class QuoteTimedOut : MoveToSparkError("The update quote did not finish in time")

    /** The update moved the balance, but Spark cannot pay with it yet: the move or Spark's balance has not settled. */
    class UpdateUnsettled : MoveToSparkError("The update to Spark has not settled yet")

    /** The payment failed for a reason other than its route, such as an expired invoice. */
    class PaymentFailed(val reason: PaymentFailureReason?) : MoveToSparkError("Failed to pay Spark, reason '$reason'")
}
