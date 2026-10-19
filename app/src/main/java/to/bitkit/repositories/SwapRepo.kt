package to.bitkit.repositories

import com.synonym.bitkitcore.PubkySendTerms
import com.synonym.bitkitcore.ReverseSwapResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Defaults
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.safe
import to.bitkit.services.BoltzService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pays onchain addresses from the spending balance: the connected Pubky swap provider locks the funds onchain
 * once its hold invoice is paid, and the claim sends exactly the recipient amount to the address. Quotes are
 * priced and checked against the fee and amount policy here, before a swap is created, because bitkit-core does
 * not apply that policy to send terms.
 */
@OptIn(ExperimentalTime::class)
@Singleton
class SwapRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val boltzService: BoltzService,
    private val pubkySwapRepo: PubkySwapRepo,
    private val walletRepo: WalletRepo,
    private val invoiceAmountReader: SwapInvoiceAmountReader,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "SwapRepo"

        /** How long fetched send terms are reused for quotes. Creating a swap always refetches them. */
        private val TERMS_TTL = 60.seconds

        /** Upper bound for reading the send terms of the provider. */
        private val TERMS_TIMEOUT = 15.seconds

        /** Share of the Lightning capacity kept for routing fees, in percent. */
        private const val ROUTING_RESERVE_PERCENT = 1uL

        /** Smallest routing fee reserve in sats. */
        private const val MIN_ROUTING_RESERVE_SAT = 10uL
    }

    private val termsMutex = Mutex()
    private var cachedTerms: CachedTerms? = null

    /** Whether paying addresses through a swap can be offered right now. */
    suspend fun isAvailable(): Boolean = pubkySwapRepo.isAvailable()

    /** Prices paying [recipientSat] to an address, failing with [SwapAmountError] outside the payable range. */
    suspend fun quote(recipientSat: ULong): Result<SwapSendQuote> = withContext(ioDispatcher) {
        runSuspendCatching {
            val pricing = pricing(terms().getOrThrow())
            pricing.checkedQuote(recipientSat).getOrThrow()
        }
    }

    /**
     * The recipient amounts that can be paid now. Fails with [SwapSendBlockedError] when no amount can, saying what
     * the smallest swap needs.
     */
    suspend fun sendLimits(): Result<SwapSendLimits> = withContext(ioDispatcher) {
        runSuspendCatching {
            val pricing = pricing(terms().getOrThrow())
            pricing.limits() ?: throw blockedError(pricing)
        }
            .onSuccess { Logger.debug("Swap send limits: $it", context = TAG) }
            .onFailure { if (it !is SwapUnavailableError) Logger.warn("No swap send limits", it, context = TAG) }
    }

    /**
     * Creates the swap for [quote] after pricing it again with fresh terms. Changed amounts fail with
     * [SwapQuoteExpiredError] carrying the new quote, without creating a swap. A reissued offer with the same amounts
     * is used as is, since its pair hash changes with every offer id. The created swap is checked against the quote
     * before it is returned, so its hold invoice is never paid for different amounts.
     */
    suspend fun createSendSwap(address: String, quote: SwapSendQuote): Result<ReverseSwapResponse> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                val fresh = pricing(terms(forceRefresh = true).getOrThrow()).checkedQuote(quote.recipientSat)
                    .getOrThrow()
                if (fresh.copy(pairHash = quote.pairHash) != quote) throw SwapQuoteExpiredError(fresh)
                val swap = boltzService.createSendSwap(
                    invoiceSat = fresh.invoiceSat,
                    recipientSat = fresh.recipientSat,
                    claimAddress = address,
                    pairHash = fresh.pairHash,
                )
                val invoiceMsat = invoiceAmountReader.amountMsat(swap.invoice)
                if (swap.onchainAmountSat != quote.lockupSat || invoiceMsat != quote.invoiceSat * 1_000uL) {
                    Logger.error("Rejected send swap '${swap.id}' that does not match its quote", context = TAG)
                    throw SwapQuoteMismatchError()
                }
                swap
            }
        }

    /** Pays the hold invoice of a send swap once, see [PubkySwapRepo.payHoldInvoice]. */
    suspend fun paySendSwap(swap: ReverseSwapResponse): Result<HoldInvoicePayment> =
        pubkySwapRepo.payHoldInvoice(swapId = swap.id, bolt11 = swap.invoice)

    private suspend fun terms(forceRefresh: Boolean = false): Result<PubkySendTerms> = runSuspendCatching {
        if (!pubkySwapRepo.isAvailable()) throw SwapUnavailableError()
        termsMutex.withLock {
            val now = clock.now()
            cachedTerms?.takeIf { !forceRefresh && now - it.fetchedAt < TERMS_TTL }?.let { return@withLock it.terms }
            pubkySwapRepo.ensureConfigured().getOrThrow()
            val terms = withTimeoutOrNull(TERMS_TIMEOUT) { boltzService.sendTerms() } ?: throw PubkySwapError.Timeout
            Logger.debug("Fetched swap send terms: $terms", context = TAG)
            cachedTerms = CachedTerms(terms, now)
            terms
        }
    }

    private fun pricing(terms: PubkySendTerms) = SwapSendPricing(
        terms = terms,
        policy = PubkySwapRepo.POLICY,
        sendableSat = sendableLightningSat(),
    )

    private fun sendableLightningSat(): ULong = sendableSat(walletRepo.balanceState.value.maxSendLightningSats)

    /** The part of a Lightning [capacitySat] a swap invoice may use, keeping a routing fee reserve. */
    private fun sendableSat(capacitySat: ULong): ULong {
        val reserve = maxOf(capacitySat * ROUTING_RESERVE_PERCENT / 100uL, MIN_ROUTING_RESERVE_SAT)
        return capacitySat.safe() - reserve.safe()
    }

    private fun blockedError(pricing: SwapSendPricing): SwapSendBlockedError {
        val smallest = pricing.smallestQuoteForAnyBalance() ?: return SwapSendBlockedError.NoPayableAmount
        var requiredSpendingSat = smallest.invoiceSat
        while (sendableSat(requiredSpendingSat) < smallest.invoiceSat) requiredSpendingSat++
        return SwapSendBlockedError.InsufficientSpending(smallest.recipientSat, requiredSpendingSat)
    }

    private data class CachedTerms(val terms: PubkySendTerms, val fetchedAt: Instant)
}

/**
 * Prices payments to an address with the provider [terms]: the recipient gets `R`, the provider locks
 * `L = R + claimFee`, and the hold invoice is `I = L + baseFee + floor(L * feePpm / 1e6) + lockupFee`, which is how
 * the provider prices its lockup. A quote is payable when its fees stay within [policy], `L` is within the
 * provider limits, and `I` fits the policy and [sendableSat].
 */
internal class SwapSendPricing(
    private val terms: PubkySendTerms,
    private val policy: PubkySwapPolicy,
    private val sendableSat: ULong,
) {
    companion object {
        private const val PPM = 1_000_000uL
        private const val BPS = 10_000uL

        /** Steps tried past the estimated minimum, where rounding can make the fee check flip. */
        private const val MIN_SEARCH_STEPS = 10_000
    }

    private val dustLimitSat = Defaults.dustLimit.toULong()

    /** Whether the terms can be priced without overflow. Fees above 100% or above the policy cap never are. */
    private val isPriceable = terms.feePpm <= PPM &&
        listOf(terms.baseFeeSat, terms.lockupFeeSat, terms.claimFeeSat).all { it <= policy.maxAmountSat }

    fun price(recipientSat: ULong): SwapSendQuote? {
        if (!isPriceable || recipientSat == 0uL || recipientSat > policy.maxAmountSat) return null
        val lockupSat = recipientSat + terms.claimFeeSat
        val serviceFeeSat = terms.baseFeeSat + lockupSat * terms.feePpm / PPM
        return SwapSendQuote(
            recipientSat = recipientSat,
            lockupSat = lockupSat,
            invoiceSat = lockupSat + serviceFeeSat + terms.lockupFeeSat,
            serviceFeeSat = serviceFeeSat,
            networkFeeSat = terms.lockupFeeSat + terms.claimFeeSat,
            pairHash = terms.pairHash,
        )
    }

    fun checkedQuote(recipientSat: ULong): Result<SwapSendQuote> {
        val quote = price(recipientSat) ?: return Result.failure(
            when {
                !isPriceable -> SwapUnavailableError()
                recipientSat == 0uL -> SwapAmountError.TooSmall
                else -> SwapAmountError.TooLarge
            }
        )
        return check(quote)?.let { Result.failure(it) } ?: Result.success(quote)
    }

    /** Finds the smallest and largest payable recipient amounts, or null when none is payable. */
    fun limits(): SwapSendLimits? {
        if (!isPriceable) return null
        val max = largestWithinUpperBounds() ?: return null
        var candidate = estimatedMinimum()
        repeat(MIN_SEARCH_STEPS) {
            if (candidate > max) return null
            if (checkedQuote(candidate).isSuccess) {
                return if (checkedQuote(max).isSuccess) SwapSendLimits(candidate, max) else null
            }
            candidate++
        }
        return null
    }

    /** The smallest payable quote if the spending balance were no limit, or null when the terms allow none. */
    fun smallestQuoteForAnyBalance(): SwapSendQuote? {
        val unlimited = SwapSendPricing(terms, policy, sendableSat = policy.maxAmountSat)
        return unlimited.limits()?.let { unlimited.price(it.minRecipientSat) }
    }

    private fun check(quote: SwapSendQuote): SwapAmountError? {
        val feeSat = quote.serviceFeeSat + terms.lockupFeeSat
        val maxFeeSat = quote.lockupSat * policy.maxFeeBps.toULong() / BPS
        return when {
            quote.recipientSat <= dustLimitSat || quote.lockupSat < terms.minimumLockupSat -> SwapAmountError.TooSmall
            feeSat > maxFeeSat -> SwapAmountError.TooSmall
            quote.lockupSat > terms.maximumLockupSat -> SwapAmountError.TooLarge
            quote.invoiceSat > policy.maxAmountSat -> SwapAmountError.TooLarge
            quote.invoiceSat > sendableSat -> SwapAmountError.InsufficientSpending
            else -> null
        }
    }

    private fun withinUpperBounds(recipientSat: ULong): Boolean {
        val quote = price(recipientSat) ?: return false
        return quote.lockupSat <= terms.maximumLockupSat &&
            quote.invoiceSat <= policy.maxAmountSat &&
            quote.invoiceSat <= sendableSat
    }

    private fun largestWithinUpperBounds(): ULong? {
        var low = 1uL
        var high = policy.maxAmountSat
        if (!withinUpperBounds(low)) return null
        while (low < high) {
            val mid = low + (high - low + 1uL) / 2uL
            if (withinUpperBounds(mid)) low = mid else high = mid - 1uL
        }
        return low
    }

    private fun estimatedMinimum(): ULong {
        val fixedFeeSat = terms.baseFeeSat + terms.lockupFeeSat
        val limitPpm = policy.maxFeeBps.toULong() * (PPM / BPS)
        // Below this lockup the proportional fee headroom cannot cover the fixed fees yet.
        val feeMinimumLockupSat = when {
            limitPpm <= terms.feePpm -> 0uL
            fixedFeeSat <= 1uL -> 0uL
            else -> (fixedFeeSat - 1uL) * PPM / (limitPpm - terms.feePpm)
        }
        val lockupMinimumSat = maxOf(terms.minimumLockupSat, feeMinimumLockupSat)
        val recipientMinimumSat = lockupMinimumSat.safe() - terms.claimFeeSat.safe()
        return maxOf(recipientMinimumSat, dustLimitSat + 1uL)
    }
}

/** Reads the amount of a BOLT11 invoice in millisatoshis, or null when it has none or cannot be decoded. */
fun interface SwapInvoiceAmountReader {
    fun amountMsat(bolt11: String): ULong?
}

/** Paying [recipientSat] to an address through a hold invoice of [invoiceSat]. */
data class SwapSendQuote(
    val recipientSat: ULong,
    val lockupSat: ULong,
    val invoiceSat: ULong,
    val serviceFeeSat: ULong,
    val networkFeeSat: ULong,
    val pairHash: String,
) {
    val totalFeeSat: ULong get() = invoiceSat - recipientSat
}

data class SwapSendLimits(
    val minRecipientSat: ULong,
    val maxRecipientSat: ULong,
)

sealed class SwapAmountError(message: String) : AppError(message) {
    data object TooSmall : SwapAmountError("The amount is below the swap minimum")
    data object TooLarge : SwapAmountError("The amount is above the swap maximum")
    data object InsufficientSpending : SwapAmountError("The spending balance cannot cover this swap")
}

/** Why no amount can be paid to an address through a swap now. */
sealed class SwapSendBlockedError(message: String) : AppError(message) {
    /** The smallest payment the provider accepts, [minRecipientSat], needs [requiredSpendingSat] of spending. */
    data class InsufficientSpending(val minRecipientSat: ULong, val requiredSpendingSat: ULong) :
        SwapSendBlockedError("The smallest swap needs a spending balance of $requiredSpendingSat sats")

    /** The fees and limits of the provider leave no amount within the policy. */
    data object NoPayableAmount : SwapSendBlockedError("The swap provider terms leave no payable amount")
}

class SwapUnavailableError : AppError("No swap provider is available")

class SwapQuoteExpiredError(val quote: SwapSendQuote) : AppError("The swap fees changed")

class SwapQuoteMismatchError : AppError("The swap provider returned different amounts than quoted")
