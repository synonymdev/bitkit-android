package to.bitkit.ui.screens.transfer

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.FeeRates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.formatMoney
import to.bitkit.models.formatToModernDisplay
import to.bitkit.models.safe
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendBlockedError
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.repositories.SparkDepositClaim
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.screens.transfer.SparkFundingContract.SideEffect
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.screens.wallets.send.HwSendRequest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Transfer to Spending through Spark: sends savings to the Spark deposit address at the speed the user picks, then
 * hands the deposit to [SparkRepo] to claim it Instant or Later.
 */
@HiltViewModel
class SparkFundingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletRepo: WalletRepo,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
    private val currencyRepo: CurrencyRepo,
    private val hwWalletRepo: HwWalletRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "SparkFundingViewModel"

        /** Share of the most Savings can send that the 25% button picks. */
        private const val QUARTER = 0.25

        /** Fast, so the deposit confirms soon whichever claim speed the user picks. */
        private val SPEED = TransactionSpeed.Fast
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var maxJob: Job? = null
    private var quoteJob: Job? = null
    private var retainedFundingJob: Job? = null

    /** The fee rates the shown quote was priced at, so the transfer pays exactly what the screen shows. */
    private var quotedFeeRates: FeeRates? = null

    init {
        viewModelScope.launch {
            lightningRepo.lightningState.map { it.nodeStatus?.isRunning ?: false }.distinctUntilChanged()
                .collect { isRunning -> _uiState.update { it.copy(isNodeRunning = isRunning) } }
        }
        viewModelScope.launch {
            walletRepo.balanceState.map { it.maxSendOnchainSats }.distinctUntilChanged().collectLatest {
                refreshMax()
            }
        }
        viewModelScope.launch {
            sparkRepo.depositClaims.collect { claims ->
                _uiState.update { state -> state.copy(claim = state.txid?.let { claims[it] } ?: state.claim) }
            }
        }
    }

    /** Sends from the hardware wallet [walletId] instead of Savings. */
    fun useHardwareWallet(walletId: String) {
        if (_uiState.value.hwWalletId == walletId) return
        _uiState.update { it.copy(hwWalletId = walletId, isMaxLoading = true) }
        refreshMax()
    }

    /** Works out the most Savings can send to Spark after the network fee; shown as available and used by Max. */
    fun refreshMax() {
        maxJob?.cancel()
        maxJob = viewModelScope.launch {
            val max = runSuspendCatching {
                val address = sparkRepo.depositAddress().getOrThrow()
                val feeRates = lightningRepo.getFeeRates().getOrThrow()
                _uiState.value.hwWalletId?.let { walletId ->
                    return@runSuspendCatching hwWalletRepo.maxSpendableFunding(
                        walletId = walletId,
                        address = address,
                        satsPerVByte = feeRates.satsPerVByte(),
                    ).getOrThrow().toLong()
                }
                val spendable = spendableSats()
                if (spendable == 0uL) return@runSuspendCatching 0L
                val fee = lightningRepo.estimateSendAllFee(address = address, speed = SPEED, feeRates = feeRates)
                    .getOrThrow()
                (spendable.safe() - fee.safe()).toLong()
            }.getOrElse {
                Logger.warn("Failed to estimate the most Spark funding can send", it, context = TAG)
                if (_uiState.value.hwWalletId != null) 0L else walletRepo.balanceState.value.maxSendOnchainSats.toLong()
            }
            _uiState.update { it.copy(maxSats = max, isMaxLoading = false) }
        }
    }

    fun onAmountChange(sats: Long) {
        _uiState.update { it.copy(amountSats = sats, isMax = sats > 0 && sats == it.maxSats) }
    }

    fun quarterSats(): Long = _uiState.value.maxSats.let { max -> minOf((max * QUARTER).roundToLong(), max) }

    fun onMaxExceeded() {
        toast(
            title = R.string.lightning__spending_amount__error_max__title,
            description = context.getString(R.string.lightning__spending_amount__error_max__description)
                .replace("{amount}", _uiState.value.maxSats.formatToModernDisplay()),
        )
    }

    /** Prices the transfer and moves on to the confirm screen when Savings can cover it and Spark can add it. */
    fun onAmountContinue() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val quote = quote().getOrElse {
                Logger.error("Failed to price Spark funding", it, context = TAG)
                toastQuoteError()
                _uiState.update { state -> state.copy(isLoading = false) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = false) }
            when {
                !quote.isCovered -> toast(
                    title = R.string.lightning__spending_amount__error_balance__title,
                    description = context.getString(R.string.lightning__spending_amount__error_balance__description),
                )

                quote.amountSats <= quote.instantFeeSats -> toastBelowClaimFee(quote)

                else -> {
                    showQuote(quote)
                    _effects.emit(SideEffect.AmountReady)
                }
            }
        }
    }

    private fun toastBelowClaimFee(quote: Quote) = toast(
        title = R.string.lightning__spending_amount__error_min__title,
        description = context.getString(R.string.lightning__spending_amount__error_min__description)
            .replace("{amount}", quote.instantFeeSats.formatToModernDisplay()),
    )

    /** Re-prices the confirm screen, so it never shows a stale quote. */
    fun onConfirmAppear() = requote()

    fun onSpeedChange(speed: SparkClaimSpeed) {
        if (_uiState.value.isSending) return
        _uiState.update { it.copy(speed = speed) }
        requote()
    }

    private fun requote() {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            _uiState.update { it.copy(isQuoting = true) }
            quote()
                .onSuccess { showQuote(it) }
                .onFailure {
                    Logger.error("Failed to re-price Spark funding", it, context = TAG)
                    toastQuoteError()
                }
            _uiState.update { it.copy(isQuoting = false) }
        }
    }

    /** Prices again at swipe time, and asks for another swipe when the transfer got worse since it was shown. */
    fun onConfirm() {
        val shown = _uiState.value
        val shownQuote = shown.quote ?: return
        if (shown.isSending || shown.isQuoting) return
        quoteJob?.cancel()
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            val quote = quote().getOrElse {
                Logger.error("Failed to re-price Spark funding at swipe", it, context = TAG)
                toastQuoteError()
                _uiState.update { state -> state.copy(isSending = false) }
                return@launch
            }
            showQuote(quote)
            if (!quote.isCovered) {
                Logger.info("Refused transfer that savings no longer cover", context = TAG)
                toast(
                    title = R.string.lightning__spending_amount__error_balance__title,
                    description = context.getString(R.string.lightning__spending_amount__error_balance__description),
                )
                _uiState.update { it.copy(isSending = false) }
                return@launch
            }
            if (quote.amountSats <= quote.instantFeeSats) {
                Logger.info("Refused transfer that no longer covers its claim fee", context = TAG)
                toastBelowClaimFee(quote)
                _uiState.update { it.copy(isSending = false) }
                return@launch
            }
            val totalRise = quote.totalSats - shownQuote.totalSats
            val spendingDrop = shownQuote.toSpendingSats(shown.speed) - quote.toSpendingSats(shown.speed)
            if (totalRise > 0 || spendingDrop > 0) {
                Logger.info("Asking for another swipe, fees rose since shown", context = TAG)
                toast(
                    title = R.string.lightning__spending_confirm__fees_changed_title,
                    description = context.getString(R.string.lightning__spending_confirm__fees_changed_network)
                        .replace("{amount}", formatPrimaryAmount(maxOf(totalRise, spendingDrop))),
                    type = Toast.ToastType.INFO,
                )
                _uiState.update { it.copy(isSending = false) }
                return@launch
            }
            val walletId = shown.hwWalletId
            if (walletId == null) {
                send(quote)
                return@launch
            }
            _uiState.update { it.copy(isSending = false) }
            _effects.emit(
                SideEffect.SignHw(
                    HwSendRequest(
                        walletId = walletId,
                        address = quote.address,
                        amountSats = quote.amountSats.toULong(),
                        satsPerVByte = quotedFeeRates?.satsPerVByte() ?: return@launch,
                        tags = emptyList(),
                        isTransfer = true,
                    )
                )
            )
        }
    }

    /** Hands the deposit the hardware wallet broadcast in [txid] to Spark, as [send] does for Savings. */
    fun onHwBroadcast(txid: String, amountSats: ULong) {
        val state = _uiState.value
        val quote = state.quote ?: return
        viewModelScope.launch {
            withContext(NonCancellable) {
                sparkRepo.onDepositBroadcast(txid, amountSats)
                sparkRepo.followDepositClaim(txid, state.speed, quote.instantFeeSats.toULong())
            }
            Logger.info("Funded Spark from a hardware wallet with '$amountSats' sats in '$txid'", context = TAG)
            _uiState.update { it.copy(txid = txid, claim = sparkRepo.depositClaims.value[txid] ?: it.claim) }
            _effects.emit(SideEffect.Sent)
        }
    }

    private suspend fun send(quote: Quote) {
        val state = _uiState.value
        runSuspendCatching {
            val outcome = lightningRepo.sendOnChain(
                address = quote.address,
                sats = quote.amountSats.toULong(),
                speed = SPEED,
                feeRates = quotedFeeRates,
                isTransfer = true,
                isMaxAmount = state.isMax,
            ).getOrThrow()
            if (outcome !is OnchainSendOutcome.Accepted) {
                Logger.warn("Spark funding was not accepted: '$outcome'", context = TAG)
                if (showRetainedFunding(quote)) return@runSuspendCatching
                error("Spark funding was not accepted: '$outcome'")
            }
            withContext(NonCancellable) {
                runSuspendCatching {
                    lightningRepo.completeAcceptedTransferFollowup(orderId = null, txid = outcome.txid)
                }.onFailure { Logger.warn("Failed to finish Spark funding locally", it, context = TAG) }
            }
            onFundingAccepted(outcome.txid, outcome.amountSats ?: quote.amountSats.toULong(), quote)
        }.onFailure {
            Logger.error("Failed to fund Spark", it, context = TAG)
            if (it is OnchainSendBlockedError && showRetainedFunding(quote)) return@onFailure
            toast(
                title = R.string.lightning__close_error,
                description = context.getString(R.string.wallet__send_error_create_tx),
            )
        }
        _uiState.update { it.copy(isSending = false) }
    }

    private suspend fun onFundingAccepted(txid: String, amountSats: ULong, quote: Quote) {
        val speed = _uiState.value.speed
        withContext(NonCancellable) {
            sparkRepo.onDepositBroadcast(txid, amountSats)
            sparkRepo.followDepositClaim(txid, speed, quote.instantFeeSats.toULong())
        }
        Logger.info("Funded Spark with '$amountSats' sats in '$txid'", context = TAG)
        _uiState.update { it.copy(txid = txid, claim = sparkRepo.depositClaims.value[txid] ?: it.claim) }
        _effects.emit(SideEffect.Sent)
    }

    // A signed funding that was not accepted is kept; Payment Pending shows it and retries it at a new fee.
    private suspend fun showRetainedFunding(quote: Quote): Boolean {
        val attempt = runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull()
        if (attempt == null || !attempt.isTransfer || !attempt.blocksNextSend) return false
        watchRetainedFunding(attempt, quote)
        _effects.emit(SideEffect.FundingPending(attempt))
        return true
    }

    // Payment Pending can be dismissed, so the confirm screen leaves for Setting Up once the kept funding completes.
    private fun watchRetainedFunding(retained: OnchainSendAttempt, quote: Quote) {
        retainedFundingJob?.cancel()
        lightningRepo.followFundingOnScreen(retained.attemptId, isFollowed = true)
        retainedFundingJob = viewModelScope.launch {
            val completed = lightningRepo.onchainSendAttemptUpdates
                .map { runSuspendCatching { lightningRepo.currentOnchainSendAttempt() }.getOrNull() }
                .filterNotNull()
                .first {
                    it.attemptId == retained.attemptId && it.address == quote.address &&
                        it.hasPositiveEvidence && it.localFollowupComplete && it.txid != null
                }
            onFundingAccepted(requireNotNull(completed.txid), completed.amountSats, quote)
        }.apply { invokeOnCompletion { lightningRepo.followFundingOnScreen(retained.attemptId, isFollowed = false) } }
    }

    fun onPayRepriced() {
        val txid = _uiState.value.txid ?: return
        sparkRepo.payRepricedClaim(txid)
    }

    fun onWait() {
        val txid = _uiState.value.txid ?: return
        sparkRepo.waitForMatureClaim(txid)
    }

    private suspend fun quote(): Result<Quote> = runSuspendCatching {
        val state = _uiState.value
        val address = sparkRepo.depositAddress().getOrThrow()
        val feeRates = lightningRepo.getFeeRates().getOrThrow()
        state.hwWalletId?.let { return@runSuspendCatching hwQuote(it, address, feeRates) }
        val spendable = spendableSats()
        val networkFee = if (state.isMax) {
            lightningRepo.estimateSendAllFee(address = address, speed = SPEED, feeRates = feeRates).getOrThrow()
        } else {
            lightningRepo.calculateTotalFee(
                amountSats = state.amountSats.toULong(),
                address = address,
                speed = SPEED,
                feeRates = feeRates,
            ).getOrThrow()
        }
        val amount = if (state.isMax) (spendable.safe() - networkFee.safe()) else state.amountSats.toULong()
        val instantFee = sparkRepo.estimateDepositClaimFeeSats(amount).getOrThrow()
        val laterFee = sparkRepo.estimateMatureClaimFeeSats().getOrThrow()
        quotedFeeRates = feeRates
        Quote(
            address = address,
            amountSats = amount.toLong(),
            networkFeeSats = networkFee.toLong(),
            instantFeeSats = instantFee.toLong(),
            laterFeeSats = laterFee.toLong(),
            spendableSats = spendable.toLong(),
        )
    }

    private suspend fun hwQuote(walletId: String, address: String, feeRates: FeeRates): Quote {
        val amount = _uiState.value.amountSats.toULong()
        val satsPerVByte = feeRates.satsPerVByte()
        val max = hwWalletRepo.maxSpendableFunding(walletId, address, satsPerVByte).getOrThrow()
        val networkFee = hwWalletRepo.estimateFundingMiningFee(walletId, address, amount, satsPerVByte).getOrThrow()
        val instantFee = sparkRepo.estimateDepositClaimFeeSats(amount).getOrThrow()
        val laterFee = sparkRepo.estimateMatureClaimFeeSats().getOrThrow()
        quotedFeeRates = feeRates
        return Quote(
            address = address,
            amountSats = amount.toLong(),
            networkFeeSats = networkFee.toLong(),
            instantFeeSats = instantFee.toLong(),
            laterFeeSats = laterFee.toLong(),
            // Max is what the device can send once its own fee is paid, so it covers any amount up to it.
            spendableSats = (max.safe() + networkFee.safe()).toLong(),
        )
    }

    private fun FeeRates.satsPerVByte(): ULong = getSatsPerVByteFor(SPEED).toULong()

    private fun showQuote(quote: Quote) {
        _uiState.update { it.copy(quote = quote, amountSats = quote.amountSats) }
    }

    private suspend fun spendableSats(): ULong =
        lightningRepo.getBalancesAsync().getOrThrow().spendableOnchainBalanceSats

    private fun toastQuoteError() = toast(
        title = R.string.lightning__spending_confirm__quote_error_title,
        description = context.getString(R.string.lightning__spending_confirm__quote_error_text),
    )

    private fun toast(title: Int, description: String, type: Toast.ToastType = Toast.ToastType.WARNING) {
        viewModelScope.launch {
            ToastEventBus.send(
                type = type,
                title = context.getString(title),
                description = description,
                visibilityTime = Toast.VISIBILITY_TIME_SHORT,
            )
        }
    }

    private fun formatPrimaryAmount(sats: Long): String {
        val state = currencyRepo.currencyState.value
        val fiat = currencyRepo.convertSatsToFiat(sats).getOrNull()
        if (state.primaryDisplay == PrimaryDisplay.FIAT && fiat != null) return fiat.formattedWithSymbol()
        return "$BITCOIN_SYMBOL ${sats.formatMoney(state.displayUnit)}"
    }
}

interface SparkFundingContract {
    @Immutable
    data class UiState(
        val isNodeRunning: Boolean = false,
        /** The hardware wallet the transfer sends from, or null for Savings. */
        val hwWalletId: String? = null,
        val isLoading: Boolean = false,
        val isMaxLoading: Boolean = true,
        val maxSats: Long = 0,
        val amountSats: Long = 0,
        val isMax: Boolean = false,
        val speed: SparkClaimSpeed = SparkClaimSpeed.Instant,
        val quote: Quote? = null,
        val isQuoting: Boolean = false,
        val isSending: Boolean = false,
        val txid: String? = null,
        val claim: SparkDepositClaim = SparkDepositClaim.Looking,
    )

    /** What the transfer costs, priced together so the confirm screen's tiles always agree. */
    @Immutable
    data class Quote(
        val address: String,
        val amountSats: Long,
        val networkFeeSats: Long,
        /** The most an Instant claim may cost: the ceiling the user accepts. */
        val instantFeeSats: Long,
        /** What the claim at 3 confirmations costs at the current half-hour fee rate. */
        val laterFeeSats: Long,
        val spendableSats: Long,
    ) {
        val totalSats: Long get() = amountSats + networkFeeSats

        /** Whether Savings can pay the amount and its network fee. */
        val isCovered: Boolean get() = totalSats <= spendableSats

        fun claimFeeSats(speed: SparkClaimSpeed): Long = when (speed) {
            SparkClaimSpeed.Instant -> instantFeeSats
            SparkClaimSpeed.Later -> laterFeeSats
        }

        fun toSpendingSats(speed: SparkClaimSpeed): Long = (amountSats - claimFeeSats(speed)).coerceAtLeast(0)
    }

    sealed interface SideEffect {
        data object AmountReady : SideEffect
        data object Sent : SideEffect

        /** The funding transaction is signed but not accepted; its recovery opens on Payment Pending. */
        data class FundingPending(val attempt: OnchainSendAttempt) : SideEffect

        /** Priced and checked; the hardware wallet signs [request] next. */
        data class SignHw(val request: HwSendRequest) : SideEffect
    }
}
