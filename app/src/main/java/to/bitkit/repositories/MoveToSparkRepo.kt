package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
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

/**
 * Moves the balance of the user's Lightning channels to their own Spark wallet, paying a Spark invoice over the
 * channels only. Blocktank is closing its channels, and this is the cheapest way to keep the balance in Spending.
 */
@Singleton
class MoveToSparkRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
) {
    companion object {
        private const val TAG = "MoveToSparkRepo"
        private const val INVOICE_DESCRIPTION = "Move to Spark"
        private const val INVOICE_EXPIRY_SECS = 3600u
        private const val MAX_QUOTE_ROUNDS = 4

        /** How long the move waits for the payment to settle before it reports it as still in flight. */
        private val PAYMENT_TIMEOUT = 2.minutes

        /** The failures that mean no route carried the amount, so a smaller one may route. */
        private val NO_ROUTE_REASONS =
            setOf(PaymentFailureReason.ROUTE_NOT_FOUND, PaymentFailureReason.RETRIES_EXHAUSTED)
    }

    private val ownPaymentHashes = ConcurrentHashMap.newKeySet<String>()
    private val watchedPaymentHashes = ConcurrentHashMap.newKeySet<String>()
    private val reportedPaymentHashes = ConcurrentHashMap.newKeySet<String>()

    /** Whether [paymentHash] is a move to Spark, whose send and receive the app does not announce as payments. */
    fun isMovePayment(paymentHash: String): Boolean = paymentHash in ownPaymentHashes

    /**
     * Whether the move screen waits on [paymentHash] or already reported its outcome, so the app raises no failure
     * notice of its own; after a timeout or cancellation the app does.
     */
    fun isWatchedMovePayment(paymentHash: String): Boolean =
        paymentHash in watchedPaymentHashes || paymentHash in reportedPaymentHashes

    /** Whether the channels hold a balance worth moving to a usable Spark wallet. */
    val canMove: Flow<Boolean> = combine(lightningRepo.lightningState, sparkRepo.state) { lightning, spark ->
        MoveToSparkMath.canMove(lightning.channels, spark)
    }.distinctUntilChanged()

    /** The most the channels can send now, before routing fees. */
    fun movableSats(): ULong = MoveToSparkMath.movableSats(lightningRepo.lightningState.value.channels)

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
            val paymentHash = Bolt11Invoice.fromStr(quote.invoice).paymentHash()
            ownPaymentHashes += paymentHash
            watchedPaymentHashes += paymentHash
            try {
                payAndWait(quote, paymentHash)
            } finally {
                watchedPaymentHashes -= paymentHash
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
     * reads the node's stored payment, so an outcome that arrived after the move stopped listening is not missed.
     */
    suspend fun awaitSettled(inFlight: MoveToSparkOutcome.InFlight): Result<MoveToSparkOutcome.Moved> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                coroutineScope {
                    val settled = async(start = CoroutineStart.UNDISPATCHED) {
                        lightningRepo.nodeEvents.first { it.settles(inFlight.paymentHash, inFlight.paymentId) }
                    }
                    val stored = lightningRepo.listPaymentsOrNull()?.firstOrNull { it.id == inFlight.paymentId }
                    when (stored?.status) {
                        PaymentStatus.SUCCEEDED -> {
                            settled.cancel()
                            MoveToSparkOutcome.Moved(inFlight.amountSats, (stored.feePaidMsat ?: 0uL) / 1000uL)
                        }
                        PaymentStatus.FAILED -> {
                            settled.cancel()
                            throw MoveToSparkError.PaymentFailed(null)
                        }
                        else -> when (val event = settled.await()) {
                            is Event.PaymentSuccessful -> event.moved(inFlight.amountSats)
                            else -> throw MoveToSparkError.PaymentFailed((event as? Event.PaymentFailed)?.reason)
                        }
                    }
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
    fun isSparkUsable(spark: SparkState): Boolean = spark.isReady

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

    /** The smaller amount to offer after one did not route: half of it, or nothing below the minimum. */
    fun smallerAmount(sats: ULong): ULong? = (sats / 2uL).takeIf { it >= MIN_MOVE_SATS }
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

    /** The payment failed for a reason other than its route, such as an expired invoice. */
    class PaymentFailed(val reason: PaymentFailureReason?) : MoveToSparkError("Failed to pay Spark, reason '$reason'")
}
