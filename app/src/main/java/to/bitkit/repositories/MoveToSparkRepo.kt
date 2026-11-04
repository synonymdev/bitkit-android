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
import org.lightningdevkit.ldknode.PaymentFailureReason
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.util.concurrent.ConcurrentHashMap
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
    }

    private val ownPaymentHashes = ConcurrentHashMap.newKeySet<String>()

    /** Whether [paymentHash] is a move to Spark, whose send and receive the app does not announce as payments. */
    fun isMovePayment(paymentHash: String): Boolean = paymentHash in ownPaymentHashes

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
            if (wantedSats < MoveToSparkMath.MIN_MOVE_SATS) throw MoveToSparkError.NothingToMove()
            if (!sparkRepo.state.value.isReady) throw MoveToSparkError.SparkUnavailable()

            val invoice = sparkRepo.createInvoice(null, INVOICE_DESCRIPTION, INVOICE_EXPIRY_SECS).getOrThrow()
            var amountSats = MoveToSparkMath.firstCandidate(wantedSats, limitSats)
            var fitting: MoveToSparkQuote? = null
            // The first amount leaves room for a guessed fee; once the real fee is known, the amount grows to fit it.
            repeat(MAX_QUOTE_ROUNDS) {
                if (amountSats < MoveToSparkMath.MIN_MOVE_SATS) return@repeat
                val feeSats = lightningRepo.estimateLdkRoutingFeesForAmount(invoice, amountSats).getOrElse {
                    fitting?.let { quote -> return@runSuspendCatching quote }
                    throw MoveToSparkError.TooLarge(amountSats, it)
                }
                val nextSats = minOf(wantedSats, MoveToSparkMath.amountAfterFee(limitSats, feeSats))
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
            coroutineScope {
                val settled = async(start = CoroutineStart.UNDISPATCHED) {
                    lightningRepo.nodeEvents.first { it.settles(paymentHash) }
                }
                lightningRepo.payInvoiceOverLdk(
                    bolt11 = quote.invoice,
                    sats = quote.amountSats,
                    maxTotalRoutingFeeMsat = MoveToSparkMath.feeCapMsat(quote.feeSats),
                ).getOrElse {
                    settled.cancel()
                    throw MoveToSparkError.TooLarge(quote.amountSats, it)
                }
                when (val event = withTimeoutOrNull(PAYMENT_TIMEOUT) { settled.await() }) {
                    is Event.PaymentSuccessful -> MoveToSparkOutcome.Moved(
                        amountSats = quote.amountSats,
                        feeSats = (event.feePaidMsat ?: 0uL) / 1000uL,
                    )
                    is Event.PaymentFailed -> throw MoveToSparkError.TooLarge(quote.amountSats, reason = event.reason)
                    else -> {
                        settled.cancel()
                        MoveToSparkOutcome.InFlight(quote.amountSats)
                    }
                }
            }
        }.onFailure {
            Logger.error("Failed to move '${quote.amountSats}' sats to Spark", it, context = TAG)
        }
    }

    private fun Event.settles(paymentHash: String): Boolean = when (this) {
        is Event.PaymentSuccessful -> this.paymentHash == paymentHash
        is Event.PaymentFailed -> this.paymentHash == paymentHash
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

    /** Whether the channels hold a balance worth moving to a usable Spark wallet. */
    fun canMove(channels: List<ChannelDetails>?, spark: SparkState): Boolean =
        spark.isReady && movableSats(channels) >= MIN_MOVE_SATS

    /** The first amount to quote: [wantedSats] when it leaves room for fees, else the limit less a guessed fee. */
    fun firstCandidate(wantedSats: ULong, limitSats: ULong): ULong {
        val guessSats = maxOf(limitSats.safe() * FIRST_FEE_GUESS_PPM.safe() / 1_000_000uL, MIN_FEE_GUESS_SATS)
        return minOf(wantedSats, amountAfterFee(limitSats, guessSats))
    }

    /** The amount that fits under [limitSats] together with [feeSats] and its margin. */
    fun amountAfterFee(limitSats: ULong, feeSats: ULong): ULong =
        limitSats.safe() - (feeSats.safe() + FEE_MARGIN_SATS.safe()).safe()

    /** The routing fee cap for the payment: the quoted fee and its margin, so it never pays more than shown. */
    fun feeCapMsat(feeSats: ULong): ULong = (feeSats.safe() + FEE_MARGIN_SATS.safe()).safe() * 1000uL.safe()

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
    data class InFlight(override val amountSats: ULong) : MoveToSparkOutcome
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
}
