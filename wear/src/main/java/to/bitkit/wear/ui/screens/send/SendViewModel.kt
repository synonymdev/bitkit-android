package to.bitkit.wear.ui.screens.send

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.R
import to.bitkit.wear.data.AmbientMonitor
import to.bitkit.wear.data.ElapsedClock
import to.bitkit.wear.data.ForegroundMonitor
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.models.BitcoinDisplayUnit
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.PreparedSend
import to.bitkit.wear.models.SendError
import to.bitkit.wear.models.SendRequest
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.PaymentsRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.ui.Routes
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.screens.home.bitcoinAmount
import to.bitkit.wear.ui.screens.home.fiatAmount
import to.bitkit.wear.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The send flow for one payment input from a deeplink: it waits for the wallet, reads the input, asks for an amount
 * only when the input names none, prices the send and shows it for review. The send goes out only after the hold to
 * pay completes, unless [PaymentsRepo.skipConfirmation] lets it out under QuickPay, straight from pricing to sending,
 * and the result closes the flow by itself.
 * The app pays only online: a wallet that is offline before the send starts ends the flow on [SendStep.Offline],
 * and the request is dropped, not kept for when the network returns.
 *
 * QuickPay is asked only for a send the wearer is there for: one priced within [QUICKPAY_WINDOW] of its link, with
 * the app on screen, out of ambient mode and the watch unlocked all the while. A link that waited longer for the
 * wallet, as without a network, or across a lock, a pause or ambient mode, always ends on the review and the hold,
 * so nothing is ever paid long after the wearer looked away. Only the first flow of a link asks: one that Android
 * builds again for the same link, as after the process was killed and the back stack restored, finds its mark in the
 * saved state and ends on the review.
 */
@HiltViewModel
class SendViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val paymentsRepo: PaymentsRepo,
    private val walletRepo: WalletRepo,
    private val currencyRepo: CurrencyRepo,
    private val settingsStore: SettingsStore,
    private val foregroundMonitor: ForegroundMonitor,
    private val ambientMonitor: AmbientMonitor,
    private val clock: ElapsedClock,
) : ViewModel() {
    companion object {
        private const val TAG = "SendViewModel"

        /** How long a send runs before the screen says it is waiting for the network. */
        val WAITING_HINT_DELAY = 3.seconds

        /** How long the sent check shows before the flow closes. */
        val SENT_CLOSE_DELAY = 2.seconds

        /** How long a pending or unknown outcome shows before the flow closes. */
        val PENDING_CLOSE_DELAY = 3.seconds

        /** The fee shows on review only from this share of the amount, in percent; a smaller fee needs no line. */
        const val FEE_SHOWN_PERCENT = 1uL

        /** The longest amount the wearer can type, in digits: more than every bitcoin there will be, in sats. */
        const val MAX_AMOUNT_DIGITS = 16

        /**
         * How long after its link arrived a send may still go out under QuickPay: long enough for a wallet that
         * connects and syncs as the link opens the app, and short enough that the wearer still looks at it.
         */
        val QUICKPAY_WINDOW = 30.seconds

        /** The saved state's mark that a flow already ran for this link, so a rebuilt one never asks QuickPay. */
        const val KEY_STARTED = "send_started"
    }

    private val input: String = savedStateHandle.get<String>(Routes.SEND_INPUT).orEmpty()

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    private val _effects = Channel<SendEffect>(Channel.BUFFERED)
    val effects: Flow<SendEffect> = _effects.receiveAsFlow()

    private var request: SendRequest? = null
    private var prepared: PreparedSend? = null
    private var unit = BitcoinDisplayUnit.MODERN

    /** Reads, prices and shows the request before the send; cancelled when the wallet goes offline. */
    private var flow: Job? = null

    /** When the link arrived, on [clock]. */
    private val arrivedAtMs = clock.nowMs()

    /**
     * Whether the wearer was away at some time since the link arrived: see [isAttended]. It never turns back, and a
     * flow rebuilt for a link that already had one starts with it set, however long ago the link arrived.
     */
    @Volatile
    private var wasAway = savedStateHandle.get<Boolean>(KEY_STARTED) == true

    /** Whether the wallet was seen out of [WalletStatus.Paused], which it may be in only while the app returns. */
    private var hasLeftPause = false

    init {
        savedStateHandle[KEY_STARTED] = true
        flow = viewModelScope.launch { start() }
        viewModelScope.launch {
            combine(
                walletRepo.walletState,
                foregroundMonitor.isForeground,
                ambientMonitor.isAmbient,
            ) { wallet, isForeground, isAmbient -> Triple(wallet.status, isForeground, isAmbient) }
                .collect { (status, isForeground, isAmbient) ->
                    val isPausedAgain = status == WalletStatus.Paused && hasLeftPause
                    if (status != WalletStatus.Paused) hasLeftPause = true
                    if (!isThere(status, isForeground, isAmbient) || isPausedAgain) wasAway = true
                }
        }
        viewModelScope.launch {
            walletRepo.walletState.collect { wallet ->
                _uiState.update { it.copy(isWalletReady = wallet.status == WalletStatus.Ready) }
                if (wallet.status == WalletStatus.Offline) dropOffline()
            }
        }
    }

    fun onAmountChange(text: String) {
        val digits = text.filter { it.isDigit() }.take(MAX_AMOUNT_DIGITS)
        _uiState.update { state ->
            val step = state.step as? SendStep.Amount ?: return@update state
            state.copy(amountText = digits, step = step.copy(isOutOfRange = false))
        }
    }

    fun onAmountConfirm() {
        val step = _uiState.value.step as? SendStep.Amount ?: return
        val request = request ?: return
        val amount = _uiState.value.amountText.toULongOrNull()
        if (amount == null || amount == 0uL || !step.contains(amount)) {
            _uiState.update { it.copy(step = step.copy(isOutOfRange = true)) }
            return
        }
        flow = viewModelScope.launch { prepare(request, amount) }
    }

    /** Sends the reviewed payment once the hold to pay completes, while the wallet is ready. */
    fun onHoldComplete() {
        val prepared = prepared ?: return
        val state = _uiState.value
        if (state.step !is SendStep.Review || !state.isWalletReady) return
        send(prepared)
    }

    /** Closes the flow from a failure's one action. */
    fun onCloseClick() {
        _effects.trySend(SendEffect.Close)
    }

    /**
     * Ends a flow whose send has not started on [SendStep.Offline]: on arrival, while the input is read or priced,
     * during the amount entry or on review. A send already under way keeps its own outcome.
     */
    private fun dropOffline() {
        val step = _uiState.value.step
        if (step !is SendStep.Loading && step !is SendStep.Amount && step !is SendStep.Review) return
        Logger.info("Dropped a send request while offline", context = TAG)
        flow?.cancel()
        request = null
        prepared = null
        _uiState.update { it.copy(step = SendStep.Offline) }
    }

    /** Shows [step] unless the flow already ended on [SendStep.Offline]. */
    private fun show(step: SendStep) {
        _uiState.update { if (it.step is SendStep.Offline) it else it.copy(step = step) }
    }

    private suspend fun start() {
        walletRepo.walletState.first { it.status == WalletStatus.Ready }
        val parsed = paymentsRepo.parse(input).getOrElse {
            fail(it)
            return
        }
        request = parsed
        if (parsed.fixedAmountSats == null) {
            show(amountStep(parsed))
            return
        }
        prepare(parsed, amountSats = null)
    }

    private suspend fun prepare(request: SendRequest, amountSats: ULong?) {
        show(SendStep.Loading)
        val result = paymentsRepo.prepare(request, amountSats).getOrElse {
            if (it is SendError.AmountOutOfRange && request.fixedAmountSats == null) {
                show(amountStep(request).copy(isOutOfRange = true))
            } else {
                fail(it)
            }
            return
        }
        if (_uiState.value.step is SendStep.Offline) return
        prepared = result
        unit = runSuspendCatching { settingsStore.data.first().displayUnit }.getOrDefault(BitcoinDisplayUnit.MODERN)
        // Asked last and only when it can apply, since a yes is counted against the day's limit. The wearer may
        // have looked away while it was answered, hence the second look, which the repo takes before it says yes.
        if (isQuickPayOpen() && paymentsRepo.skipConfirmation(result, ::isAttended)) {
            Logger.info("Skipped the hold to pay under QuickPay", context = TAG)
            send(result)
            return
        }
        show(review(result, request))
    }

    /** Whether QuickPay may be asked for a send priced now: see the class. */
    private fun isQuickPayOpen(): Boolean =
        isAttended() && clock.nowMs() - arrivedAtMs in 0..QUICKPAY_WINDOW.inWholeMilliseconds

    /**
     * Whether the wearer was there since the link arrived and still is: the app on screen, not in ambient mode, the
     * watch never locked, and the wallet never paused after it ran.
     */
    private fun isAttended(): Boolean = !wasAway && isThere(
        walletRepo.walletState.value.status,
        foregroundMonitor.isForeground.value,
        ambientMonitor.isAmbient.value,
    )

    private fun isThere(status: WalletStatus, isForeground: Boolean, isAmbient: Boolean): Boolean =
        isForeground && !isAmbient && status != WalletStatus.Locked

    private suspend fun review(prepared: PreparedSend, request: SendRequest): SendStep.Review {
        val currency = settingsStore.data.first().selectedCurrency
        val amount = prepared.amountSats.toLong()
        val isFeeShown = prepared.feeSats > 0uL && prepared.feeSats * 100uL >= prepared.amountSats * FEE_SHOWN_PERCENT
        return SendStep.Review(
            amount = bitcoinAmount(amount, unit),
            fiat = fiatAmount(amount, currencyRepo.rates.value, currency),
            fee = if (isFeeShown) bitcoinAmount(prepared.feeSats.toLong(), unit) else null,
            payee = (prepared.payee ?: request.payee)?.takeIf { it.isNotBlank() },
        )
    }

    private fun send(prepared: PreparedSend) {
        _uiState.update { it.copy(step = SendStep.Sending()) }
        viewModelScope.launch {
            val hint = launch {
                delay(WAITING_HINT_DELAY)
                _uiState.update { state ->
                    val step = state.step as? SendStep.Sending ?: return@update state
                    state.copy(step = step.copy(isWaiting = true))
                }
            }
            val result = paymentsRepo.send(prepared)
            hint.cancel()
            val outcome = result.fold(
                onSuccess = {
                    when (it.state) {
                        PaymentState.Completed -> SendResult.Sent
                        PaymentState.Pending -> SendResult.Pending
                        PaymentState.Failed -> null
                    }
                },
                onFailure = { if (it is SendError.Unknown) SendResult.Unknown else null },
            )
            if (outcome == null) {
                fail(result.exceptionOrNull())
                return@launch
            }
            Logger.info("Finished send with outcome '$outcome'", context = TAG)
            // The amount Spark paid, which is an earlier send's when the request was already sent at another amount.
            finish(outcome, result.getOrNull()?.amountSats ?: prepared.amountSats)
        }
    }

    /** Shows [result] with [amountSats] and then closes the flow. */
    private suspend fun finish(result: SendResult, amountSats: ULong) {
        _uiState.update { it.copy(step = SendStep.Done(result, bitcoinAmount(amountSats.toLong(), unit))) }
        delay(if (result == SendResult.Sent) SENT_CLOSE_DELAY else PENDING_CLOSE_DELAY)
        _effects.send(SendEffect.Close)
    }

    /**
     * Shows why nothing was sent. A payment of this request that Spark lists as under way is no failure: it shows as
     * pending. A wallet busy with another send is a failure, as nothing of this request went out.
     */
    private suspend fun fail(error: Throwable?) {
        if (error is SendError.InFlight) {
            finish(SendResult.Pending, amountSats = prepared?.amountSats ?: 0uL)
            return
        }
        show(SendStep.Failed(SendFailure.of(error)))
    }

    private fun amountStep(request: SendRequest): SendStep.Amount = when (request) {
        is SendRequest.Lnurl -> SendStep.Amount(minSats = request.minSats, maxSats = request.maxSats)
        else -> SendStep.Amount()
    }
}

/** What the send screen shows. */
sealed interface SendStep {
    /** Waiting for the wallet, reading the input or pricing the send. */
    data object Loading : SendStep

    /** The wearer types the amount of an input that names none, within [minSats] to [maxSats] when known. */
    data class Amount(
        val minSats: ULong? = null,
        val maxSats: ULong? = null,
        val isOutOfRange: Boolean = false,
    ) : SendStep {
        fun contains(amountSats: ULong): Boolean =
            (minSats == null || amountSats >= minSats) && (maxSats == null || amountSats <= maxSats)
    }

    /** The priced send with the hold to pay. [fee] is set only when it is worth showing. */
    data class Review(
        val amount: DisplayAmount,
        val fiat: DisplayAmount?,
        val fee: DisplayAmount?,
        val payee: String?,
    ) : SendStep

    /** The send is under way; [isWaiting] once it took longer than a moment. */
    data class Sending(val isWaiting: Boolean = false) : SendStep

    /**
     * The send went out: sent, pending, or with an outcome not known yet. Pending is also a payment of the request
     * that was already under way. Only a sent payment shows its [amount].
     */
    data class Done(val result: SendResult, val amount: DisplayAmount) : SendStep

    /** Nothing was sent, for [failure]. */
    data class Failed(val failure: SendFailure) : SendStep

    /** Nothing was sent: the wallet went offline before the send started. The flow ends here. */
    data object Offline : SendStep
}

enum class SendResult { Sent, Pending, Unknown }

/** Why nothing was sent by this flow, with its one-line copy. */
enum class SendFailure(@StringRes val text: Int) {
    NotReady(R.string.send__error_not_ready),
    Invalid(R.string.send__error_invalid),
    Unsupported(R.string.send__error_unsupported),
    Expired(R.string.send__error_expired),
    OwnInvoice(R.string.send__error_own_invoice),
    OutOfRange(R.string.send__error_out_of_range),
    Insufficient(R.string.send__error_insufficient),
    AlreadyPaid(R.string.send__error_already_paid),
    Busy(R.string.send__error_busy),
    Failed(R.string.send__error_failed),
    ;

    companion object {
        fun of(error: Throwable?): SendFailure = when (error) {
            is SendError.NotReady -> NotReady
            is SendError.Invalid -> Invalid
            is SendError.Unsupported -> Unsupported
            is SendError.Expired -> Expired
            is SendError.OwnInvoice -> OwnInvoice
            is SendError.AmountOutOfRange -> OutOfRange
            is SendError.InsufficientBalance -> Insufficient
            is SendError.AlreadyPaid -> AlreadyPaid
            is SendError.Busy -> Busy
            else -> Failed
        }
    }
}

sealed interface SendEffect {
    /** The flow is over: back to the screen the deeplink found. */
    data object Close : SendEffect
}

@Immutable
data class SendUiState(
    val step: SendStep = SendStep.Loading,
    val amountText: String = "",
    val isWalletReady: Boolean = false,
) {
    /** Whether the hold to pay can start: only on review with the wallet ready. */
    val isHoldEnabled: Boolean get() = step is SendStep.Review && isWalletReady

    /** Whether the flow must not be left by a swipe, since a send is under way. */
    val isBusy: Boolean get() = step is SendStep.Sending
}
