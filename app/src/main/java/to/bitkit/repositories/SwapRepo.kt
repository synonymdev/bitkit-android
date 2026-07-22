package to.bitkit.repositories

import androidx.compose.runtime.Immutable
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwapEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.BoltzService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Pays an on-chain address out of the Lightning balance with a Boltz reverse swap, so a send can
 * go through when savings are short but spending is not.
 *
 * A reverse swap claims to any address, so the recipient's address is used as the claim address and
 * Boltz's payout lands directly on them. Boltz prices a reverse swap from the Lightning invoice
 * amount, while a send starts from the amount the recipient must receive, so the quote here is the
 * inverse of the forward pricing the transfer-to-savings flow does.
 */
@OptIn(ExperimentalTime::class)
@Singleton
class SwapRepo @Inject constructor(
    private val boltzService: BoltzService,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    @Volatile
    private var cachedLimits: BoltzPairInfo? = null

    @Volatile
    private var cachedLimitsAt: Long = 0L

    /**
     * Price a swap that delivers exactly [recipientSat] on-chain. Fails when swaps are off, Boltz is
     * unreachable, or the amount falls outside the swap limits or the spendable Lightning balance,
     * so callers can simply not offer the swap.
     */
    suspend fun quoteForRecipientAmount(recipientSat: ULong): Result<SendSwapQuote> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                if (recipientSat == 0uL) throw SwapUnavailableError("Amount is zero")
                val limits = limits()
                val quote = buildQuote(recipientSat, limits)

                if (quote.invoiceSat < limits.minimalSat || quote.invoiceSat > limits.maximalSat) {
                    throw SwapUnavailableError(
                        "Amount '${quote.invoiceSat}' outside swap limits " +
                            "'${limits.minimalSat}'..'${limits.maximalSat}'"
                    )
                }
                if (quote.invoiceSat > sendableLightningSats()) {
                    throw SwapUnavailableError("Amount '${quote.invoiceSat}' over spendable balance")
                }
                if (!lightningRepo.canSend(quote.invoiceSat)) {
                    throw SwapUnavailableError("Cannot route '${quote.invoiceSat}' over Lightning")
                }
                quote
            }
        }

    /**
     * Largest amount a swap can deliver on-chain right now, so the amount screen can cap its input
     * without pricing every keystroke against Boltz.
     */
    suspend fun maxRecipientSats(): Result<ULong> = withContext(ioDispatcher) {
        runSuspendCatching {
            val limits = limits()
            val ceiling = minOf(limits.maximalSat, sendableLightningSats()).toLong()
            receivedFor(ceiling, limits.feePercentage / PERCENT, limits.minerFeesSat.toLong())
                .coerceAtLeast(0)
                .toULong()
        }
    }

    /**
     * Pair terms, cached briefly: the send flow re-prices on every keystroke and the terms only
     * move with Boltz's fee schedule.
     */
    private suspend fun limits(): BoltzPairInfo {
        if (!boltzService.isSwapEnabled()) throw SwapUnavailableError("Swaps are disabled")

        val cached = cachedLimits
        if (cached != null && nowMillis() - cachedLimitsAt < LIMITS_TTL.inWholeMilliseconds) return cached

        // Bounded so a hanging Boltz request cannot leave the send flow stuck loading.
        val fresh = withTimeoutOrNull(QUOTE_TIMEOUT) { boltzService.reverseLimits() }
            ?: throw SwapUnavailableError("Timed out fetching reverse swap limits")
        cachedLimits = fresh
        cachedLimitsAt = nowMillis()
        return fresh
    }

    /**
     * Create the swap, pay its hold invoice over Lightning and wait for the on-chain claim.
     *
     * Boltz reports the amount it will lock before anything is paid, so a swap that would short the
     * recipient is abandoned while it is still free to abandon. Once the invoice is paid the claim
     * is broadcast by the updates stream, so a timeout waiting for it leaves
     * [SendSwapReceipt.claimTxId] null rather than failing the send. Callers must run this in a
     * scope that outlives the send sheet.
     */
    suspend fun payToAddress(address: String, recipientSat: ULong): Result<SendSwapReceipt> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                val quote = quoteForRecipientAmount(recipientSat).getOrThrow()

                val swap = boltzService.createReverseSwap(
                    amountSat = quote.invoiceSat,
                    claimAddress = address,
                )
                Logger.info("Created send swap '${swap.id}' for '$recipientSat' sat", context = TAG)

                // Boltz reports what it will lock before anything is paid. The quote already holds
                // back Boltz's own claim fee estimate, so a lockup below the recipient amount means
                // Boltz priced on different terms than we quoted and the send is abandoned for free.
                if (swap.onchainAmountSat < recipientSat) {
                    throw SwapQuoteExpiredError(
                        "Boltz locks '${swap.onchainAmountSat}' sat, short of '$recipientSat' sat"
                    )
                }

                coroutineScope {
                    // UNDISPATCHED so the collector subscribes before we pay: the events flow has no
                    // replay, so a claim settling faster than the payment call returns would be missed.
                    val claim = async(start = CoroutineStart.UNDISPATCHED) { awaitClaim(swap.id) }

                    // Pay the hold invoice (amount is encoded). It stays pending until Boltz locks
                    // funds on-chain and we claim them to the recipient, the expected happy path.
                    val paymentHash = lightningRepo.payInvoice(bolt11 = swap.invoice).getOrThrow()

                    SendSwapReceipt(paymentHash = paymentHash, claimTxId = claim.await())
                }
            }
        }

    private suspend fun awaitClaim(swapId: String): String? {
        val event = withTimeoutOrNull(CLAIM_TIMEOUT) {
            boltzService.events.first {
                (it is BoltzSwapEvent.Claimed && it.swapId == swapId) ||
                    (it is BoltzSwapEvent.Error && it.swapId == swapId)
            }
        }
        return when (event) {
            is BoltzSwapEvent.Claimed -> event.txid
            is BoltzSwapEvent.Error -> throw SwapClaimError(event.message)
            else -> null
        }
    }

    /**
     * Solve for the Lightning invoice amount that leaves [recipientSat] on-chain. Seeded from the
     * closed form, then corrected against the integer forward formula, which is what Boltz applies:
     * it charges `ceil(percentage% * invoice)` and takes the miner fees on top.
     */
    private fun buildQuote(recipientSat: ULong, limits: BoltzPairInfo): SendSwapQuote {
        val target = recipientSat.toLong()
        val minerFee = limits.minerFeesSat.toLong()
        val rate = limits.feePercentage / PERCENT

        var invoice = ceil((target + minerFee) / (1.0 - rate)).toLong().coerceAtLeast(target)
        while (receivedFor(invoice, rate, minerFee) < target) invoice++
        while (invoice > 0 && receivedFor(invoice - 1, rate, minerFee) >= target) invoice--

        return SendSwapQuote(
            recipientSat = recipientSat,
            invoiceSat = invoice.toULong(),
            serviceFeeSat = (invoice - target).coerceAtLeast(0).toULong(),
            networkFeeSat = limits.minerFeesSat,
        )
    }

    private fun receivedFor(invoiceSat: Long, rate: Double, minerFeeSat: Long): Long =
        invoiceSat - ceil(rate * invoiceSat).toLong() - minerFeeSat

    /**
     * Lightning outbound minus a routing reserve. Paying an invoice for 100% of outbound capacity
     * leaves nothing for fees and fails with RouteNotFound.
     */
    private fun sendableLightningSats(): ULong {
        val spendable = walletRepo.balanceState.value.maxSendLightningSats.toLong()
        val routingReserve = (spendable / LN_ROUTING_FEE_RESERVE_DIVISOR)
            .coerceAtLeast(MIN_LN_ROUTING_FEE_RESERVE_SATS)
        return (spendable - routingReserve).coerceAtLeast(0).toULong()
    }

    companion object {
        private const val TAG = "SwapRepo"

        private const val PERCENT = 100.0

        /** Upper bound for fetching swap limits before the send flow gives up on a quote. */
        private val QUOTE_TIMEOUT = 15.seconds

        /** How long cached pair terms are reused before Boltz is asked again. */
        private val LIMITS_TTL = 60.seconds

        /** How long the send waits for the on-chain claim before backgrounding it. */
        private val CLAIM_TIMEOUT = 30.seconds

        /** Minimum sats held back from a swap to cover Lightning routing fees. */
        private const val MIN_LN_ROUTING_FEE_RESERVE_SATS = 10L

        /** Holds ~1% of outbound capacity back for Lightning routing fees. */
        private const val LN_ROUTING_FEE_RESERVE_DIVISOR = 100L
    }
}

/** Cost of delivering [recipientSat] on-chain from the Lightning balance. */
@Immutable
data class SendSwapQuote(
    /** What the recipient receives on-chain. */
    val recipientSat: ULong,
    /** What the Lightning invoice pays, i.e. what leaves the spending balance. */
    val invoiceSat: ULong,
    /** Everything the swap costs on top of [recipientSat]: Boltz's cut plus miner fees. */
    val serviceFeeSat: ULong,
    /** Boltz's lockup and claim miner fee estimate, already included in [serviceFeeSat]. */
    val networkFeeSat: ULong,
)

/** What a completed swap send leaves behind. The Lightning leg is settled either way. */
data class SendSwapReceipt(
    /** Hash of the hold invoice payment, i.e. the activity the send produces. */
    val paymentHash: String,
    /** Transaction paying the recipient; null while the updates stream is still broadcasting it. */
    val claimTxId: String?,
)

class SwapUnavailableError(message: String) : AppError(message)
class SwapQuoteExpiredError(message: String) : AppError(message)
class SwapClaimError(message: String) : AppError(message)
