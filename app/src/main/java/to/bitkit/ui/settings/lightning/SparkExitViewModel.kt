package to.bitkit.ui.settings.lightning

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import breez_sdk_spark.RecoveryTxKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.FeeRate
import to.bitkit.models.Toast
import to.bitkit.models.safe
import to.bitkit.repositories.SparkExitBackgroundLimits
import to.bitkit.repositories.SparkExitCancel
import to.bitkit.repositories.SparkExitCancelUnreadable
import to.bitkit.repositories.SparkExitFeeBumpLeavesBehind
import to.bitkit.repositories.SparkExitFeeBumpOption
import to.bitkit.repositories.SparkExitFundingKey
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitNetworkFees
import to.bitkit.repositories.SparkExitPrestartFunding
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitStartCancelled
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.surfacedRejection
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Drives the emergency exit screens, from the quote to the progress of a running exit. */
@HiltViewModel
class SparkExitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sparkExitRepo: SparkExitRepo,
    private val sparkRepo: SparkRepo,
) : ViewModel() {
    companion object {
        /** How often the funding screen looks at the funding address while the user pays from another wallet. */
        private val FUNDING_POLL_INTERVAL = 15.seconds
    }

    private val _uiState = MutableStateFlow(SparkExitUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SparkExitEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var fundingWatch: Job? = null

    init {
        viewModelScope.launch {
            // Spark's live balance, for what a stopped exit leaves on Spark after a relaunch too.
            combine(sparkExitRepo.state, sparkRepo.state) { exit, spark -> exit to spark.balanceSats }
                .collect { (exit, balanceSats) -> showExit(exit, balanceSats) }
        }
    }

    private fun showExit(exit: SparkExitState, balanceSats: ULong) {
        _uiState.update {
            it.copy(
                record = exit.record,
                tipHeight = exit.tipHeight,
                fundingSeenSats = exit.fundingSeenSats,
                fundingSeenCount = exit.fundingSeenCount,
                fundingFeeRate = exit.pending?.feeRate,
                fundingState = exit.fundingState,
                fundingAddress = exit.fundingAddress,
                canCancel = sparkExitRepo.canCancel,
                isSavingsPaymentRefused = exit.isSavingsPaymentRefused,
                outsideExitSats = exit.outsideExitSats,
                staysOnSparkSats = SparkExitMath.staysOnSparkSats(balanceSats, exit.record),
                isSettling = exit.isSettling,
                networkFees = exit.networkFees,
                receivedSats = exit.record?.let(::receivedSats),
                fundingPath = exit.record?.let { record ->
                    SparkExitFundingKey.path(record.fundingIndex.toInt(), Env.network)
                },
            )
        }
    }

    /** What the ended exit paid to savings, read from its sweep and cooperative transactions. */
    private fun receivedSats(record: SparkExitRecord): ULong? {
        if (record.phase != SparkExitRecord.Phase.Done) return null
        return SparkExitMath.receivedSats(record)?.takeIf { it > 0uL }
    }

    fun loadQuote() {
        viewModelScope.launch {
            // A new quote starts the funding choice over.
            stopWatchingFundingAddress()
            _uiState.update {
                it.copy(isQuoting = true, hasQuoteError = false, fundFromSavings = false, showsFundingAddress = false)
            }
            sparkExitRepo.loadQuote()
                .onSuccess { quote -> _uiState.update { it.copy(quote = quote, isQuoting = false) } }
                .onFailure { _uiState.update { it.copy(quote = null, isQuoting = false, hasQuoteError = true) } }
        }
    }

    fun loadSavingsFunding() {
        viewModelScope.launch {
            _uiState.update { it.copy(savingsFeeSats = null, hasSavingsFeeFailed = false) }
            sparkExitRepo.savingsFundingFee()
                .onSuccess { funding ->
                    _uiState.update { it.copy(savingsFeeSats = funding.feeSats, canFundFromSavings = funding.canPay) }
                }
                .onFailure { _uiState.update { it.copy(hasSavingsFeeFailed = true, canFundFromSavings = false) } }
        }
    }

    /**
     * Entering the funding step offers both choices again, unless the funding address already received a payment:
     * then it keeps showing the address and what reached it.
     */
    fun onFundingShown() {
        stopWatchingFundingAddress()
        _uiState.update { it.copy(fundFromSavings = false, showsFundingAddress = false) }
        viewModelScope.launch {
            val seen = sparkExitRepo.refreshFundingAddress().getOrNull() ?: return@launch
            if (seen > 0uL) onFundExternally()
        }
    }

    fun onFundFromSavings() {
        _uiState.update { it.copy(fundFromSavings = true) }
    }

    fun onFundExternally() {
        _uiState.update { it.copy(fundFromSavings = false, showsFundingAddress = true) }
        watchFundingAddress()
    }

    fun watchFundingAddress() {
        if (fundingWatch?.isActive == true) return
        fundingWatch = viewModelScope.launch {
            while (isActive) {
                sparkExitRepo.refreshFundingAddress()
                delay(FUNDING_POLL_INTERVAL)
            }
        }
    }

    fun stopWatchingFundingAddress() {
        fundingWatch?.cancel()
        fundingWatch = null
    }

    fun start() {
        viewModelScope.launch {
            _uiState.update { it.copy(isStarting = true) }
            sparkExitRepo.start(_uiState.value.fundFromSavings)
                .onSuccess { _effects.emit(SparkExitEffect.Started) }
                .onFailure {
                    // A cancel during the start already said so on progress.
                    if (it !is SparkExitStartCancelled) ToastEventBus.send(it)
                    // The exit started but paying its fee funding failed: progress shows where to fund it.
                    if (sparkExitRepo.state.value.isExiting) _effects.emit(SparkExitEffect.Started)
                }
            _uiState.update { it.copy(isStarting = false) }
        }
    }

    /** Prices the higher rates the running exit can move to, selecting the fastest. */
    fun loadFeeBump() {
        viewModelScope.launch {
            _uiState.update { it.copy(feeBump = SparkExitFeeBumpUiState(isLoading = true)) }
            val spendable = sparkExitRepo.spendableSavingsSats().getOrNull()
            sparkExitRepo.feeBumpOptions()
                .onSuccess { options ->
                    val bump = SparkExitFeeBumpUiState(options = options.toImmutableList(), spendableSats = spendable)
                    // The fastest rate savings can pay for, or none when it covers no rate.
                    val selected = options.firstOrNull(bump::canPay)?.speed
                    _uiState.update { it.copy(feeBump = bump.copy(selected = selected)) }
                }
                .onFailure { error ->
                    val bump = if (error is SparkExitFeeBumpLeavesBehind) {
                        SparkExitFeeBumpUiState(isLeavingLeaves = true)
                    } else {
                        SparkExitFeeBumpUiState(hasError = true)
                    }
                    _uiState.update { it.copy(feeBump = bump) }
                }
        }
    }

    fun selectFeeBump(speed: FeeRate) {
        _uiState.update { it.copy(feeBump = it.feeBump.copy(selected = speed)) }
    }

    fun payHigherFee() {
        val option = _uiState.value.feeBump.selectedOption ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(feeBump = it.feeBump.copy(isPaying = true)) }
            sparkExitRepo.payHigherFee(option)
                .onSuccess { _effects.emit(SparkExitEffect.FeeBumped) }
                .onFailure { ToastEventBus.send(it) }
            _uiState.update { it.copy(feeBump = it.feeBump.copy(isPaying = false)) }
        }
    }

    /** Progress showed the result of an ended exit, so a later Emergency exit can start a new one. */
    fun onResultShown() {
        viewModelScope.launch { sparkExitRepo.markResultSeen() }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            sparkExitRepo.load()
            sparkExitRepo.tick()
            _uiState.update { it.copy(isRefreshing = false, canCancel = sparkExitRepo.canCancel) }
        }
    }

    /** Pays the refused savings payment again, over the inputs it already spends. */
    fun retrySavingsPayment() {
        if (_uiState.value.isRetryingSavingsPayment) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRetryingSavingsPayment = true) }
            sparkExitRepo.retrySavingsPayment().onFailure { ToastEventBus.send(it) }
            _uiState.update { it.copy(isRetryingSavingsPayment = false) }
        }
    }

    fun refreshBackgroundLimits() {
        _uiState.update { it.copy(backgroundLimits = sparkExitRepo.backgroundLimits()) }
    }

    fun cancel() {
        viewModelScope.launch {
            sparkExitRepo.cancel()
                // Funding that reached the address keeps the exit on screen while it is sent back to savings.
                .onSuccess { if (it == SparkExitCancel.Cancelled) _effects.emit(SparkExitEffect.Cancelled) }
                .onFailure {
                    if (it !is SparkExitCancelUnreadable) return@onFailure ToastEventBus.send(it)
                    ToastEventBus.send(
                        Toast.ToastType.ERROR,
                        context.getString(R.string.lightning__spark_exit__cancel_failed)
                    )
                }
        }
    }
}

sealed interface SparkExitEffect {
    data object Started : SparkExitEffect
    data object Cancelled : SparkExitEffect
    data object FeeBumped : SparkExitEffect
}

@Stable
data class SparkExitUiState(
    val quote: SparkExitQuote? = null,
    val isQuoting: Boolean = false,
    val hasQuoteError: Boolean = false,
    val savingsFeeSats: ULong? = null,
    val hasSavingsFeeFailed: Boolean = false,
    val canFundFromSavings: Boolean = false,
    val fundFromSavings: Boolean = false,
    val showsFundingAddress: Boolean = false,
    val isStarting: Boolean = false,
    val isRefreshing: Boolean = false,
    val record: SparkExitRecord? = null,
    val tipHeight: UInt? = null,
    val fundingSeenSats: ULong = 0uL,
    val fundingSeenCount: Int = 0,
    /** The quote's fee rate, which prices each extra payment to the funding address. */
    val fundingFeeRate: ULong? = null,
    /** What progress shows while the exit needs fee funding. */
    val fundingState: SparkExitFundingState? = null,
    val fundingAddress: String? = null,
    val canCancel: Boolean = false,
    /** A node refused the savings payment of the fee funding: progress says so and offers to pay again. */
    val isSavingsPaymentRefused: Boolean = false,
    val isRetryingSavingsPayment: Boolean = false,
    val backgroundLimits: SparkExitBackgroundLimits = SparkExitBackgroundLimits(),
    val outsideExitSats: ULong? = null,
    /** Spark's balance beyond the exit's leaves in any phase: what a stopped exit leaves on Spark. */
    val staysOnSparkSats: ULong? = null,
    /** The quote waits for Spark to finish moving leaves first. */
    val isSettling: Boolean = false,
    /** What a finished exit paid to savings. */
    val receivedSats: ULong? = null,
    /** The derivation path of the exit's funding key, where a leftover that could not be sent stays. */
    val fundingPath: String? = null,
    /** What the network asks now, for the fee bump and its hint. */
    val networkFees: SparkExitNetworkFees? = null,
    val feeBump: SparkExitFeeBumpUiState = SparkExitFeeBumpUiState(),
) {
    /** What leaves savings when it pays the fee funding: the funding and the fee of the payment that sends it. */
    val savingsTotalSats: ULong?
        get() {
            val fundingSats = quote?.fundingSats?.takeIf { fundFromSavings && it > 0uL } ?: return null
            val feeSats = savingsFeeSats ?: return null
            return fundingSats.safe() + feeSats.safe()
        }

    /** Progress offers Pay a higher fee. */
    val canPayHigherFee: Boolean get() = record?.canPayHigherFee(tipHeight, networkFees) == true

    /** Progress hints that the network asks more than the exit pays, while it does not show the fee as too low. */
    val showsFeeRose: Boolean
        get() = canPayHigherFee && lowFeeRate == null && record?.isBelowNetwork(networkFees) == true

    val fundingSats: ULong get() = quote?.fundingSats ?: 0uL

    /** What the funding address holds against the quote before the exit starts. */
    val prestartFunding: SparkExitPrestartFunding
        get() = SparkExitMath.prestartFunding(fundingSats, fundingSeenCount, fundingSeenSats, fundingFeeRate ?: 0uL)

    /**
     * Continue after funding from another wallet: once the funding address holds what the quote asks for, plus the
     * input fee of each payment beyond the first.
     */
    val canContinueFunding: Boolean get() = prestartFunding.isCovered

    /** Why the quote cannot continue, or null when it can. */
    val quoteBlock: SparkExitQuoteBlock?
        get() {
            if (hasQuoteError) return SparkExitQuoteBlock.Error
            val quote = quote ?: return null
            if (quote.prepared.leaves.isEmpty()) return SparkExitQuoteBlock.Nothing
            if (!quote.isWorthExiting) return SparkExitQuoteBlock.Uneconomic
            return null
        }

    val rejection: String? get() = record?.surfacedRejection

    /** The exit's fee rate, while a step the network accepted does not confirm for longer than that rate needs. */
    val lowFeeRate: ULong?
        get() {
            // Not while a higher fee is paid and the exit waits to rebuild at it.
            val running = record?.takeIf { it.phase == SparkExitRecord.Phase.Running && !it.isRebuilding }
                ?: return null
            return running.feeRateSatPerVbyte.takeIf { running.underpricedStep(tipHeight) != null }
        }

    /** The SWEEP transaction of a finished exit, or the one that landed before a retry of leaves replaced it. */
    val sweepTxid: String?
        get() {
            val done = record?.takeIf { it.phase == SparkExitRecord.Phase.Done } ?: return null
            return done.recovery?.transactions?.firstOrNull { it.kind == RecoveryTxKind.SWEEP }?.txid
                ?: done.landedSweepTxid
        }

    /**
     * The fee funding progress shows, asking for the whole shortfall, at least the funding output's dust, until the
     * funding address was read.
     */
    val progressFunding: SparkExitFundingState?
        get() {
            val shortfall = record?.fundingShortfallSats ?: return null
            return fundingState ?: SparkExitFundingState.Ask(maxOf(shortfall, SparkExitMath.FUNDING_DUST_SATS))
        }
}

enum class SparkExitQuoteBlock { Error, Nothing, Uneconomic }

/** The fee bump screen: the rates it offers, the one picked, and what savings can spend. */
@Stable
data class SparkExitFeeBumpUiState(
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    /** Every higher rate would leave part of the exit on Spark, so none is offered. */
    val isLeavingLeaves: Boolean = false,
    val options: ImmutableList<SparkExitFeeBumpOption> = persistentListOf(),
    val selected: FeeRate? = null,
    val spendableSats: ULong? = null,
    val isPaying: Boolean = false,
) {
    val selectedOption: SparkExitFeeBumpOption? get() = options.firstOrNull { it.speed == selected }

    /** Savings covers none of the rates offered. */
    val isSavingsShort: Boolean get() = options.isNotEmpty() && options.none(::canPay)

    /** Savings covers [option]'s cost, or there is nothing to pay. */
    fun canPay(option: SparkExitFeeBumpOption): Boolean =
        option.totalSats == 0uL || (spendableSats ?: 0uL) >= option.totalSats
}
