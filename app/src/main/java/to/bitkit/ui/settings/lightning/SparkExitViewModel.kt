package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.repositories.SparkExitBackgroundLimits
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.surfacedRejection
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Drives the emergency exit screens, from the quote to the progress of a running exit. */
@HiltViewModel
class SparkExitViewModel @Inject constructor(
    private val sparkExitRepo: SparkExitRepo,
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
            sparkExitRepo.state.collect { exit ->
                _uiState.update {
                    it.copy(
                        record = exit.record,
                        tipHeight = exit.tipHeight,
                        fundingSeenSats = exit.fundingSeenSats,
                        fundingState = exit.fundingState,
                        fundingAddress = exit.fundingAddress,
                        canCancel = sparkExitRepo.canCancel,
                        outsideExitSats = exit.outsideExitSats,
                        isSettling = exit.isSettling,
                    )
                }
            }
        }
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
                    ToastEventBus.send(it)
                    // The exit started but paying its fee funding failed: progress shows where to fund it.
                    if (sparkExitRepo.state.value.isExiting) _effects.emit(SparkExitEffect.Started)
                }
            _uiState.update { it.copy(isStarting = false) }
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

    fun refreshBackgroundLimits() {
        _uiState.update { it.copy(backgroundLimits = sparkExitRepo.backgroundLimits()) }
    }

    fun cancel() {
        viewModelScope.launch {
            sparkExitRepo.cancel()
                .onSuccess { _effects.emit(SparkExitEffect.Cancelled) }
                .onFailure { ToastEventBus.send(it) }
        }
    }
}

sealed interface SparkExitEffect {
    data object Started : SparkExitEffect
    data object Cancelled : SparkExitEffect
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
    /** What progress shows while the exit needs fee funding. */
    val fundingState: SparkExitFundingState? = null,
    val fundingAddress: String? = null,
    val canCancel: Boolean = false,
    val backgroundLimits: SparkExitBackgroundLimits = SparkExitBackgroundLimits(),
    val outsideExitSats: ULong? = null,
    /** The quote waits for Spark to finish moving leaves first. */
    val isSettling: Boolean = false,
) {
    val fundingSats: ULong get() = quote?.fundingSats ?: 0uL

    /** Continue after funding from another wallet: once the funding address holds what the quote asks for. */
    val canContinueFunding: Boolean get() = fundingSats > 0uL && fundingSeenSats >= fundingSats

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
