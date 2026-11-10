package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwapEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.R
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toUserMessage
import to.bitkit.models.Toast
import to.bitkit.models.TransferType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.services.BoltzService
import to.bitkit.services.SparkError
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

const val RETRY_INTERVAL_MS = 1 * 60 * 1000L // 1 minutes in ms
const val GIVE_UP_MS = 30 * 60 * 1000L // 30 minutes in ms

@OptIn(ExperimentalTime::class)
@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val transferRepo: TransferRepo,
    private val boltzService: BoltzService,
    private val sparkRepo: SparkRepo,
    private val clock: Clock,
) : ViewModel() {

    private val _isForceTransferLoading = MutableStateFlow(false)
    val isForceTransferLoading = _isForceTransferLoading.asStateFlow()

    private val _savingsSelection = MutableStateFlow(SavingsSelection())
    val savingsSelection = _savingsSelection.asStateFlow()

    private val _sparkWithdrawFeeSats = MutableStateFlow<ULong?>(null)
    val sparkWithdrawFeeSats = _sparkWithdrawFeeSats.asStateFlow()

    private val _isSparkFeePricing = MutableStateFlow(false)
    val isSparkFeePricing = _isSparkFeePricing.asStateFlow()

    private val _isSparkWithdrawTooSmall = MutableStateFlow(false)
    val isSparkWithdrawTooSmall = _isSparkWithdrawTooSmall.asStateFlow()

    private var sparkFeeJob: Job? = null
    private var confirmedSparkFeeSats: ULong? = null
    private var sparkWithdrawJob: Job? = null
    private var closeJob: Deferred<List<ChannelDetails>>? = null
    private var sparkFeeBalanceSats: ULong? = null
    private var sparkAddress: String? = null

    private val isNodeRunning = lightningRepo.lightningState.map { it.nodeStatus?.isRunning ?: false }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // region Savings

    private var channelsToClose = emptyList<ChannelDetails>()
    private var withdrawsSpark = false

    /** Spark's part of the last Transfer to Savings failed, so its balance stayed in Spending. */
    private val _isSparkWithdrawFailed = MutableStateFlow(false)
    val isSparkWithdrawFailed = _isSparkWithdrawFailed.asStateFlow()

    /**
     * How the LN -> onchain "transfer to savings" is executed. Closing a channel is the
     * default because it always works; swapping funds out keeps channels open and is used
     * whenever a priced quote is available.
     */
    private val _savingsTransferMode = MutableStateFlow(SavingsTransferMode.CLOSE)
    val savingsTransferMode = _savingsTransferMode.asStateFlow()

    private val _savingsSwapState = MutableStateFlow(SavingsSwapUiState())
    val savingsSwapState = _savingsSwapState.asStateFlow()

    /** Outcome of the swap started by [startSavingsSwap]; null while none has finished. */
    private val _savingsSwapResult = MutableStateFlow<SavingsSwapResult?>(null)
    val savingsSwapResult = _savingsSwapResult.asStateFlow()

    private var savingsSwapJob: Job? = null

    /** The amount (sat) that will actually be swapped out; adjustable via the confirm slider. */
    private var pendingSwapAmountSat: ULong = 0uL

    /** Cached swap limits so the slider can re-price locally without hitting the network. */
    private var reverseLimits: BoltzPairInfo? = null

    private var savingsSwapQuoteJob: Job? = null

    /**
     * Fetch swap limits, derive the adjustable amount range, and publish an initial fee quote
     * (defaulting to the maximum transferable) so the user sees the cost before confirming.
     * The confirm slider then re-prices locally via [onSwapAmountChange]. A quote is the only
     * thing that unlocks the swap, so every failure simply leaves it null and the transfer
     * falls back to closing a channel.
     * Cancels any in-flight quote so a slower earlier request cannot overwrite a newer one.
     * [channelSendableSat] is what the selected channels can send, so Spark's balance never sizes the swap.
     */
    fun loadSavingsSwapQuote(requestedSat: ULong, channelSendableSat: ULong) {
        if (!boltzService.isSwapSupported) return
        savingsSwapQuoteJob?.cancel()
        if (requestedSat == 0uL) {
            pendingSwapAmountSat = 0uL
            _savingsSwapState.update { it.copy(isLoading = false, quote = null, minSat = 0uL, maxSat = 0uL) }
            return
        }
        savingsSwapQuoteJob = viewModelScope.launch {
            val isSwapEnabled = boltzService.isSwapEnabled()
            _savingsSwapState.update { it.copy(isSwapEnabled = isSwapEnabled) }
            if (!isSwapEnabled) return@launch
            _savingsSwapState.update { it.copy(isLoading = true) }
            awaitNodeRunning()

            // Bounded so a hanging Boltz request cannot leave the confirm swipe stuck loading.
            val limits = withTimeoutOrNull(SWAP_QUOTE_TIMEOUT) {
                runSuspendCatching { boltzService.reverseLimits() }
                    .onFailure { Logger.error("Failed to load reverse swap limits", it, context = TAG) }
                    .getOrNull()
            }
            if (limits == null) {
                reverseLimits = null
                _savingsSwapState.update { it.copy(isLoading = false, quote = null) }
                return@launch
            }
            reverseLimits = limits

            // Reserve headroom for Lightning routing fees. Paying an invoice for 100% of
            // outbound capacity leaves nothing for fees and fails with RouteNotFound, so cap
            // the swap at outbound minus ~1% (with a small floor).
            val spendable = channelSendableSat.toLong()
            val routingReserve = (spendable / 100).coerceAtLeast(MIN_LN_ROUTING_FEE_RESERVE_SATS)
            val sendable = (spendable - routingReserve).coerceAtLeast(0).toULong()
            val maxSat = minOf(requestedSat, limits.maximalSat, sendable)
            val minSat = limits.minimalSat

            if (maxSat < minSat) {
                // Below the swap minimum: revert to the pre-swap view where the swipe closes
                // the channel instead. No error text or extra close action is shown.
                pendingSwapAmountSat = 0uL
                _savingsSwapState.update {
                    it.copy(
                        isLoading = false,
                        quote = null,
                        minSat = 0uL,
                        maxSat = 0uL,
                    )
                }
                return@launch
            }

            // Default to transferring as much as possible; the slider can lower it.
            pendingSwapAmountSat = maxSat
            _savingsSwapState.update {
                it.copy(
                    isLoading = false,
                    minSat = minSat,
                    maxSat = maxSat,
                    quote = buildQuote(maxSat, limits),
                )
            }
        }
    }

    /** Re-price the swap for a slider-selected amount, clamped to the allowed range. */
    fun onSwapAmountChange(sat: ULong) {
        val limits = reverseLimits ?: return
        val state = _savingsSwapState.value
        if (state.maxSat < state.minSat) return
        val amount = sat.coerceIn(state.minSat, state.maxSat)
        pendingSwapAmountSat = amount
        _savingsSwapState.update { it.copy(quote = buildQuote(amount, limits)) }
    }

    private fun buildQuote(amount: ULong, limits: BoltzPairInfo): SavingsSwapQuote {
        val swapFee = (amount.toDouble() * limits.feePercentage / 100.0).roundToLong().coerceAtLeast(0).toULong()
        val networkFee = limits.minerFeesSat
        val receive = (amount.toLong() - swapFee.toLong() - networkFee.toLong()).coerceAtLeast(0).toULong()
        return SavingsSwapQuote(
            amountSat = amount,
            networkFeeSat = networkFee,
            swapFeeSat = swapFee,
            receiveSat = receive,
        )
    }

    /**
     * Run the swap in [viewModelScope] so it outlives the progress screen: navigating away
     * mid-flight must not cancel a swap whose hold invoice is already paid. Idempotent once
     * started: a swap already in flight, or one whose outcome is already in [savingsSwapResult],
     * is never re-run, so re-entering the screen or a configuration change cannot create and pay
     * a second swap. A fresh journey clears the outcome in [onTransferToSavingsConfirm].
     */
    fun startSavingsSwap() {
        if (savingsSwapJob?.isActive == true || _savingsSwapResult.value != null) return
        savingsSwapJob = viewModelScope.launch {
            if (withdrawsSpark) withdrawSparkToSavings()
            val result = executeSavingsSwap()
            _savingsSwapResult.update { result }
        }
    }

    /**
     * Execute the LN -> onchain swap: derive a fresh claim address, create the swap,
     * pay the returned hold invoice over Lightning, then wait for the on-chain claim.
     * The claim is auto-broadcast by the updates stream as soon as the lockup hits the
     * mempool, so a timeout here is not a failure; the swap completes in the background.
     */
    private suspend fun executeSavingsSwap(): SavingsSwapResult {
        val amount = pendingSwapAmountSat.takeIf { it > 0uL }
            ?: return SavingsSwapResult.Failure(
                context.getString(R.string.lightning__savings_confirm__amount_too_low),
            )

        return runSuspendCatching {
            val claimAddress = lightningRepo.newAddress().getOrThrow()
            val swap = boltzService.createReverseSwap(amountSat = amount, claimAddress = claimAddress)
            Logger.info("Created savings transfer swap ${swap.id}", context = TAG)

            coroutineScope {
                // Whichever resolves first wins: an on-chain claim, a Boltz error, or a Lightning
                // routing failure. The losing watcher is cancelled so this scope can return.
                val outcome = CompletableDeferred<SavingsSwapResult>()

                // UNDISPATCHED so the collector actually subscribes before we pay: the events
                // flow has no replay, so a claim settling faster than the payment call returns
                // would otherwise be missed and the swap would idle until the claim timeout.
                val claim = launch(start = CoroutineStart.UNDISPATCHED) {
                    outcome.complete(awaitSwapClaim(swap.id))
                }

                // Pay the hold invoice (amount is encoded) from the channels it was quoted on, never Spark.
                // It stays pending until Boltz locks funds on-chain and we claim them, the expected happy path.
                val paymentHash = lightningRepo.payInvoiceWithNode(swap.invoice).getOrThrow()

                // payInvoice returns as soon as the HTLC is dispatched, and a hold invoice never
                // settles until Boltz claims, so nothing else notices a payment that fails to route.
                // Watch for it so an unroutable payment surfaces as an error instead of idling into
                // the claim timeout and reporting a settling transfer that never completes.
                val failure = launch {
                    outcome.complete(awaitPaymentFailure(paymentHash))
                }

                outcome.await().also {
                    claim.cancel()
                    failure.cancel()
                }
            }
        }.getOrElse { e ->
            Logger.error("Savings transfer swap failed", e, context = TAG)
            SavingsSwapResult.Failure(e.message ?: context.getString(R.string.common__error_body))
        }
    }

    private suspend fun awaitSwapClaim(swapId: String): SavingsSwapResult {
        val event = withTimeoutOrNull(SWAP_CLAIM_TIMEOUT) {
            boltzService.events.first {
                (it is BoltzSwapEvent.Claimed && it.swapId == swapId) ||
                    (it is BoltzSwapEvent.Error && it.swapId == swapId)
            }
        }
        return when (event) {
            is BoltzSwapEvent.Claimed -> SavingsSwapResult.Success(event.txid)
            is BoltzSwapEvent.Error -> SavingsSwapResult.Failure(event.message)
            else -> SavingsSwapResult.Pending
        }
    }

    /** Suspends until the paid hold invoice fails to route on Lightning, then maps it to a reason. */
    private suspend fun awaitPaymentFailure(paymentHash: PaymentId): SavingsSwapResult {
        val event = lightningRepo.nodeEvents
            .filterIsInstance<Event.PaymentFailed>()
            .first { it.paymentHash == paymentHash }
        return SavingsSwapResult.Failure(event.reason.toUserMessage(context))
    }

    /** Opens Transfer to Savings with nothing left from an earlier visit that ended before the swipe. */
    fun beginSavingsTransfer() {
        // A wallet wipe keeps this view model, so an address kept from before may belong to the wiped wallet.
        sparkAddress = null
        // Spark's withdrawal fee moves, so each transfer prices it again.
        sparkFeeJob?.cancel()
        sparkFeeBalanceSats = null
        _sparkWithdrawFeeSats.update { null }
        setSavingsSelection(SavingsSelection())
    }

    fun setSavingsSelection(selection: SavingsSelection) {
        _savingsSelection.update { selection }
    }

    /** Prices Spark's withdrawal to savings for the confirm screen; null until priced or when it can't be. */
    fun loadSparkWithdrawFee(balanceSats: ULong) {
        val isPriced = sparkFeeJob?.isActive == true || _sparkWithdrawFeeSats.value != null
        if (balanceSats == sparkFeeBalanceSats && isPriced) return
        sparkFeeJob?.cancel()
        sparkFeeBalanceSats = balanceSats
        _sparkWithdrawFeeSats.update { null }
        _isSparkWithdrawTooSmall.update { false }
        _isSparkFeePricing.update { true }
        val job = viewModelScope.launch {
            val address = sparkWithdrawAddress()
            val result = address?.let {
                withTimeoutOrNull(SWAP_QUOTE_TIMEOUT) { sparkRepo.estimateWithdrawAllFeeSats(it) }
            }
            if (address != null && result == null) {
                Logger.warn("Timed out estimating Spark withdrawal fee", context = TAG)
            }
            result
                ?.onSuccess { fee -> _sparkWithdrawFeeSats.update { fee } }
                ?.onFailure {
                    if (it is SparkError.WithdrawalTooSmall) _isSparkWithdrawTooSmall.update { true }
                    Logger.warn("Failed to estimate Spark withdrawal fee", it, context = TAG)
                }
            if (result?.isSuccess != true) sparkFeeBalanceSats = null
        }
        sparkFeeJob = job
        job.invokeOnCompletion { if (sparkFeeJob === job) _isSparkFeePricing.update { false } }
    }

    /** One Savings address for Spark's withdrawal, priced on the confirm screen and then paid to. */
    private suspend fun sparkWithdrawAddress(): String? {
        sparkAddress?.let { return it }
        return lightningRepo.newAddress()
            .onFailure { Logger.error("Failed to get address for Spark withdrawal", it, context = TAG) }
            .getOrNull()
            ?.also { sparkAddress = it }
    }

    /**
     * The swipe: commits the transfer only while Spark's withdrawal is still priced at the fee shown when the swipe
     * began, for the balance shown then. Returns false without committing when Spark's balance changed or is being
     * priced again since, so the swipe resets instead of opening progress.
     */
    fun confirmSavingsSwipe(
        channels: List<ChannelDetails>,
        withdrawSpark: Boolean,
        shownSparkSats: ULong,
        shownFeeSats: ULong?,
    ): Boolean {
        if (withdrawSpark && !isSparkPricedAt(shownSparkSats, shownFeeSats)) {
            Logger.info("Refused the savings swipe as Spark changed since it was shown", context = TAG)
            return false
        }
        onTransferToSavingsConfirm(channels, withdrawSpark, confirmedSparkFeeSats = shownFeeSats)
        return true
    }

    private fun isSparkPricedAt(balanceSats: ULong, feeSats: ULong?): Boolean =
        feeSats != null &&
            sparkFeeJob?.isActive != true &&
            _sparkWithdrawFeeSats.value == feeSats &&
            sparkFeeBalanceSats == balanceSats &&
            sparkRepo.state.value.balanceSats == balanceSats

    /**
     * Commit the transfer and pick how it runs. A swap needs a priced quote, so without one
     * (swaps unsupported on this network, Boltz unreachable, or an amount below the swap
     * minimum) the transfer closes a channel exactly as it did before swaps existed. Spark's withdrawal pays at most
     * [confirmedSparkFeeSats], the fee shown when the user confirmed, even if the screen prices it again after.
     */
    fun onTransferToSavingsConfirm(
        channels: List<ChannelDetails>,
        withdrawSpark: Boolean,
        mode: SavingsTransferMode = resolveSavingsTransferMode(channels),
        confirmedSparkFeeSats: ULong? = _sparkWithdrawFeeSats.value,
    ) {
        _savingsTransferMode.update { mode }
        // Drop any outcome from an earlier attempt so the progress screen cannot act on it.
        _savingsSwapResult.update { null }
        _isSparkWithdrawFailed.update { false }
        sparkWithdrawJob = null
        closeJob = null
        _savingsSelection.update { SavingsSelection() }
        channelsToClose = channels
        withdrawsSpark = withdrawSpark
        this.confirmedSparkFeeSats = confirmedSparkFeeSats
    }

    private fun resolveSavingsTransferMode(channels: List<ChannelDetails>): SavingsTransferMode =
        if (channels.isNotEmpty() && _savingsSwapState.value.quote != null) {
            SavingsTransferMode.SWAP
        } else {
            SavingsTransferMode.CLOSE
        }

    /** Whether Transfer to Savings closes channels, rather than only withdrawing Spark's balance. */
    fun hasChannelsToClose(): Boolean = channelsToClose.isNotEmpty()

    /**
     * Withdraws Spark's balance when selected, then closes the channels selected earlier, pending closure. Once per
     * confirmed transfer, in viewModelScope, so leaving the progress screen during the withdrawal skips no close.
     */
    suspend fun closeSelectedChannels(): List<ChannelDetails> {
        val job = closeJob ?: viewModelScope.async {
            if (withdrawsSpark) withdrawSparkToSavings()
            closeChannels(channelsToClose)
        }.also { closeJob = it }
        return job.await()
    }

    private suspend fun withdrawSparkToSavings() {
        // Once per confirmed transfer, in viewModelScope, so leaving the progress screen cannot cut it short.
        val job = sparkWithdrawJob ?: viewModelScope.launch { runSparkWithdrawal() }.also { sparkWithdrawJob = it }
        job.join()
    }

    private suspend fun runSparkWithdrawal() {
        sparkFeeJob?.join()
        if (sparkRepo.state.value.balanceSats == 0uL) return
        val address = sparkWithdrawAddress()
        val confirmedFeeSats = confirmedSparkFeeSats
        if (address == null || confirmedFeeSats == null) {
            Logger.warn("Skipped Spark withdrawal without an address or a priced fee", context = TAG)
            _isSparkWithdrawFailed.update { true }
            return
        }
        sparkRepo.withdrawAll(address, confirmedFeeSats)
            .onSuccess {
                sparkAddress = null
                sparkFeeBalanceSats = null
            }
            .onFailure {
                // A refused fee is quoted again, so the next visit doesn't show it and fail the same way.
                sparkFeeBalanceSats = null
                _isSparkWithdrawFailed.update { true }
            }
    }

    private suspend fun closeChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
        if (channels.isEmpty()) return emptyList()
        lightningRepo.awaitPeerConnected()
        val channelsFailedToClose = coroutineScope {
            channels.map { channel ->
                async {
                    lightningRepo.closeChannel(channel)
                        .onSuccess {
                            transferRepo.createTransfer(
                                type = TransferType.COOP_CLOSE,
                                amountSats = channel.amountOnClose.toLong(),
                                channelId = channel.channelId,
                                fundingTxId = channel.fundingTxo?.txid,
                            )
                        }
                        .fold(
                            onSuccess = { null },
                            onFailure = { channel }
                        )
                }
            }.awaitAll()
        }.filterNotNull()

        return channelsFailedToClose
    }

    private var coopCloseRetryJob: Job? = null

    /** Retry to coop close the channel(s) for 30 min */
    fun startCoopCloseRetries(
        channels: List<ChannelDetails>,
        onGiveUp: () -> Unit,
    ) {
        val startTimeMs = clock.now().toEpochMilliseconds()
        channelsToClose = channels
        coopCloseRetryJob?.cancel()

        coopCloseRetryJob = viewModelScope.launch {
            val giveUpTime = startTimeMs + GIVE_UP_MS
            while (isActive && System.currentTimeMillis() < giveUpTime) {
                Logger.info("Trying coop close...")
                val channelsFailedToCoopClose = closeChannels(channelsToClose)

                if (channelsFailedToCoopClose.isEmpty()) {
                    channelsToClose = emptyList()
                    Logger.info("Coop close success.")
                    return@launch
                } else {
                    channelsToClose = channelsFailedToCoopClose
                    Logger.info("Coop close failed: ${channelsFailedToCoopClose.map { it.channelId }}")
                }

                delay(RETRY_INTERVAL_MS)
            }

            Logger.info("Giving up on coop close.", context = TAG)
            onGiveUp()
        }
    }

    fun forceTransfer(onComplete: () -> Unit) = viewModelScope.launch {
        _isForceTransferLoading.value = true
        runCatching {
            val failedChannels = forceCloseChannels(channelsToClose)
            channelsToClose = failedChannels

            if (failedChannels.isEmpty()) {
                Logger.info("Force close initiated successfully for all channels", context = TAG)
                val description = context.getString(R.string.lightning__force_init_msg)
                ToastEventBus.send(
                    type = Toast.ToastType.LIGHTNING,
                    title = context.getString(R.string.lightning__force_init_title),
                    description = description,
                )
            } else {
                Logger.error("Force close failed for ${failedChannels.size} channels", context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__force_failed_title),
                    description = context.getString(R.string.lightning__force_failed_msg)
                )
            }
        }.onFailure {
            Logger.error("Force close failed", e = it, context = TAG)
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.lightning__force_failed_title),
                description = context.getString(R.string.lightning__force_failed_msg)
            )
        }
        _isForceTransferLoading.value = false
        onComplete()
    }

    private suspend fun forceCloseChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
        val channelsFailedToClose = coroutineScope {
            channels.map { channel ->
                async {
                    lightningRepo.closeChannel(channel, force = true)
                        .onSuccess {
                            transferRepo.createTransfer(
                                type = TransferType.FORCE_CLOSE,
                                amountSats = channel.amountOnClose.toLong(),
                                channelId = channel.channelId,
                                fundingTxId = channel.fundingTxo?.txid,
                            )
                        }
                        .onFailure { e -> Logger.error("Error force closing channel: ${channel.channelId}", e) }
                        .fold(
                            onSuccess = { null },
                            onFailure = { channel },
                        )
                }
            }.awaitAll()
        }.filterNotNull()

        return channelsFailedToClose
    }

    // endregion

    private suspend fun awaitNodeRunning() {
        withTimeoutOrNull(1.minutes) {
            isNodeRunning.first { it }
        }
    }

    companion object {
        private const val TAG = "TransferViewModel"

        /** How long the confirm/progress flow waits for the on-chain claim before backgrounding it. */
        private val SWAP_CLAIM_TIMEOUT = 30.seconds

        /** Upper bound for fetching swap limits before the confirm screen gives up on a quote. */
        private val SWAP_QUOTE_TIMEOUT = 15.seconds

        /** Minimum sats held back from a swap to cover Lightning routing fees. */
        private const val MIN_LN_ROUTING_FEE_RESERVE_SATS = 10L
    }
}

/**
 * What Transfer to Savings moves: the open channels picked in [channelIds], null for all of them, and whether
 * Spark's balance goes too. The default moves everything.
 */
@Immutable
data class SavingsSelection(
    val channelIds: ImmutableSet<String>? = null,
    val includesSpark: Boolean = true,
) {
    val isAll: Boolean get() = channelIds == null && includesSpark

    fun channels(openChannels: List<ChannelDetails>): List<ChannelDetails> =
        channelIds?.let { ids -> openChannels.filter { it.channelId in ids } } ?: openChannels

    /** Spark moves when picked and holding a balance its withdrawal fee leaves something of ([isSparkTooSmall]). */
    fun withdrawsSpark(sparkSats: ULong, isSparkTooSmall: Boolean = false): Boolean =
        includesSpark && sparkSats > 0uL && !isSparkTooSmall
}

/** Whether a transfer to savings swaps funds out or closes a channel (default). */
enum class SavingsTransferMode { SWAP, CLOSE }

@Immutable
data class SavingsSwapQuote(
    val amountSat: ULong,
    val networkFeeSat: ULong,
    val swapFeeSat: ULong,
    val receiveSat: ULong,
)

@Immutable
data class SavingsSwapUiState(
    /** Whether swaps are switched on for this network and in dev settings; see [BoltzService.isSwapEnabled]. */
    val isSwapEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val quote: SavingsSwapQuote? = null,
    /** Inclusive adjustable range for the confirm slider (sat). Equal/zero when unavailable. */
    val minSat: ULong = 0uL,
    val maxSat: ULong = 0uL,
)

sealed interface SavingsSwapResult {
    /** Funds landed on-chain during the flow. */
    data class Success(val txid: String) : SavingsSwapResult

    /** Swap created and invoice paid; the claim completes in the background. */
    data object Pending : SavingsSwapResult

    data class Failure(val reason: String) : SavingsSwapResult
}
// endregion
