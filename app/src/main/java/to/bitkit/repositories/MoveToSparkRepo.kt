package to.bitkit.repositories

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
import org.lightningdevkit.ldknode.PaymentFailureReason
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
    private val paymentHashOf: (bolt11: String) -> String,
) {
    companion object {
        private const val TAG = "MoveToSparkRepo"
        private const val INVOICE_DESCRIPTION = "Move to Spark"
        private const val INVOICE_EXPIRY_SECS = 3600u
        private const val MAX_QUOTE_ROUNDS = 4

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
    ) : this(bgDispatcher, lightningRepo, sparkRepo, cacheStore, { Bolt11Invoice.fromStr(it).paymentHash() })

    private val watchedPaymentHashes = ConcurrentHashMap.newKeySet<String>()

    /** One move at a time: the modal's and a payment's update would otherwise pay from the same channel balance. */
    private val moveLock = Mutex()
    private val reportedPaymentHashes = ConcurrentHashMap.newKeySet<String>()

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
     * [knownQuote], or a new quote, and waits until Spark can pay the amount. A move still in flight, or a balance
     * Spark does not show in time, fails with [MoveToSparkError.UpdateUnsettled]; the funds then reach Spark on their
     * own. [onMove] gets the move's outcome as soon as the move returns, before the wait. A quote whose amount, with
     * Spark's balance, would not cover the payment fails with [MoveToSparkError.UpdateFallsShort] before any move.
     */
    suspend fun updateToSend(
        knownQuote: MoveToSparkQuote?,
        amountSats: ULong,
        onMove: (MoveToSparkOutcome) -> Unit = {},
    ): Result<MoveToSparkOutcome.Moved> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                val usedQuote = knownQuote ?: quote().getOrThrow()
                if (!MoveToSparkMath.coversPayment(usedQuote, sparkRepo.state.value, amountSats)) {
                    throw MoveToSparkError.UpdateFallsShort(amountSats)
                }
                val outcome = move(usedQuote).getOrThrow().also(onMove)
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

    /**
     * The fee Spark charges for paying [bolt11] once the spending balance is updated; Spark's service fee alone when
     * Spark cannot price it before the moved balance arrives.
     */
    suspend fun estimateFeeAfterUpdate(bolt11: String, amountSats: ULong): ULong = withContext(bgDispatcher) {
        sparkRepo.estimateFeeSats(bolt11, amountSats).getOrElse {
            Logger.debug("Fell back to Spark's service fee for an update payment", context = TAG)
            SparkFees.serviceFeeSats(amountSats)
        }
    }

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

    /** Pays [quote] over the channels and waits for it to settle in Spark. */
    suspend fun move(quote: MoveToSparkQuote): Result<MoveToSparkOutcome> = withContext(bgDispatcher) {
        runSuspendCatching {
            moveLock.withLock {
                val paymentHash = paymentHashOf(quote.invoice)
                cacheStore.addMoveToSparkHash(paymentHash)
                watchedPaymentHashes += paymentHash
                try {
                    payAndWait(quote, paymentHash)
                } finally {
                    watchedPaymentHashes -= paymentHash
                }
            }
        }.onFailure {
            Logger.error("Failed to move '${quote.amountSats}' sats to Spark", it, context = TAG)
        }
    }

    private suspend fun payAndWait(quote: MoveToSparkQuote, paymentHash: String): MoveToSparkOutcome =
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
                maxTotalRoutingFeeMsat = MoveToSparkMath.feeCapMsat(quote.feeSats),
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
                                MoveToSparkOutcome.Moved(inFlight.amountSats, (stored.feePaidMsat ?: 0uL) / 1000uL)
                            PaymentStatus.FAILED -> throw MoveToSparkError.PaymentFailed(null)
                            else -> withTimeoutOrNull(STORED_PAYMENT_RECHECK) { settled.await() }?.let {
                                (it as? Event.PaymentSuccessful)?.moved(inFlight.amountSats)
                                    ?: throw MoveToSparkError.PaymentFailed((it as? Event.PaymentFailed)?.reason)
                            }
                        }
                    }
                    settled.cancel()
                    moved
                }
            }.onFailure {
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

    /** The smaller amount to offer after one did not route: half of it, or nothing below the minimum. */
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

    /** Whether Spark can pay [amountSats], its service fee included, once [quote] has moved to it. */
    fun coversPayment(quote: MoveToSparkQuote, spark: SparkState, amountSats: ULong): Boolean =
        spark.isUsable && SparkFees.sendableSats(spark.balanceSats.safe() + quote.amountSats.safe()) >= amountSats

    /** The open channels in [after] that could send at least [MIN_MOVE_SATS] in [before] and no longer can. */
    fun emptiedChannels(before: List<ChannelDetails>, after: List<ChannelDetails>?): List<ChannelDetails> {
        val beforeSats = before.associate { it.channelId to msatFloorOf(it.nextOutboundHtlcLimitMsat) }
        return after.orEmpty().filterOpen().filter {
            val wasSats = beforeSats[it.channelId] ?: return@filter false
            wasSats >= MIN_MOVE_SATS && msatFloorOf(it.nextOutboundHtlcLimitMsat) < MIN_MOVE_SATS
        }
    }
}

data class MoveToSparkQuote(
    val invoice: String,
    val amountSats: ULong,
    val feeSats: ULong,
)

sealed interface MoveToSparkOutcome {
    val amountSats: ULong

    data class Moved(override val amountSats: ULong, val feeSats: ULong) : MoveToSparkOutcome

    /** The payment left but had not settled when the move stopped waiting; Spark shows it once it does. */
    data class InFlight(
        override val amountSats: ULong,
        val paymentHash: String,
        val paymentId: String,
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

    /** The update moved the balance, but Spark cannot pay with it yet: the move or Spark's balance has not settled. */
    class UpdateUnsettled : MoveToSparkError("The update to Spark has not settled yet")

    /** The payment failed for a reason other than its route, such as an expired invoice. */
    class PaymentFailed(val reason: PaymentFailureReason?) : MoveToSparkError("Failed to pay Spark, reason '$reason'")
}
