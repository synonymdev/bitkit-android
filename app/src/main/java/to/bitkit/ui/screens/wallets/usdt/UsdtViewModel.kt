package to.bitkit.ui.screens.wallets.usdt

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.USat
import to.bitkit.repositories.PaykitPaymentRequestError
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.UsdtSelfSendError
import to.bitkit.repositories.parseUsdtPaymentRequest
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.SanityWarning
import javax.inject.Inject

@HiltViewModel
class UsdtViewModel @Inject constructor(
    private val repo: UsdtRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    companion object {
        private const val TAG = "UsdtViewModel"
    }

    val destinations = flow {
        repo.sendDestinations()
            .onSuccess { emit(it) }
            .onFailure { Logger.warn("Could not load USDT bridge destinations", it, context = TAG) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Env.usdtDestinations.toImmutableList()
    )

    val wallet = repo.state
    fun consumeReceipt(id: String) = repo.consumeReceipt(id)
    val settings = settingsStore.data.catch {
        if (it is CancellationException) throw it
        settingsReadFailed(it)
        emit(SettingsData(hideBalance = true))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsData(hideBalance = true)
    )
    var sendPayment: (suspend (UsdtQuote) -> Unit)? = null
        set(value) {
            if (value != null) requiresPaymentContext = true
            field = value
        }
    private var requiresPaymentContext = false
    private val _state = MutableStateFlow(UsdtSendState())
    private var maximumQuote: UsdtQuote? = null
    private var preparedQuote: PreparedQuote? = null
    private var submissionFailureGeneration: Long? = null
    val state = _state.asStateFlow()

    var isSendPresented: Boolean
        get() = repo.isSendPresented
        set(value) {
            repo.isSendPresented = value
            if (!value) preparedQuote = null
        }

    fun shouldRefresh(enabled: Boolean): Boolean = repo.shouldRefresh(enabled)

    suspend fun refresh(history: Boolean = false) {
        repo.refresh(history)
            .onSuccess {
                _state.update { current ->
                    val recovered = current.submitted && current.canRetryRecovery &&
                        repo.recoveryGeneration != submissionFailureGeneration
                    current.copy(
                        refreshError = null,
                        error = current.error.takeUnless { recovered },
                        canRetryRecovery = current.canRetryRecovery && !recovered,
                    )
                }
            }
            .onFailure { error ->
                Logger.warn("Failed to refresh USDT with '${error.javaClass.simpleName}'", context = TAG)
                _state.update { it.copy(refreshError = error.messageResource()) }
            }
    }

    suspend fun waitForTransfer() {
        val state = _state.value
        val quote = state.quote ?: return
        if (!state.submitted || !state.busy) return
        try {
            if (quote.destination != UsdtDestination.ARBITRUM) return
            repo.waitForTransfer(quote.id)
                .onSuccess { _state.update { it.copy(refreshError = null) } }
                .onFailure { error ->
                    Logger.warn("Failed to check USDT payment", error, context = TAG)
                    _state.update { it.copy(refreshError = error.messageResource()) }
                }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun isValidRecipient(value: String, destination: UsdtDestination) = parseUsdtPaymentRequest(
        value,
        destination
    ).isSuccess

    fun validateRecipient(value: String, destination: UsdtDestination, onValid: (UsdtPaymentRequest) -> Unit) {
        parseUsdtPaymentRequest(value, destination)
            .onSuccess { request ->
                _state.update { it.copy(error = null) }
                onValid(request)
            }
            .onFailure { error -> _state.update { it.copy(error = error.messageResource()) } }
    }

    fun quote(recipient: String, amount: String, destination: UsdtDestination) = viewModelScope.launch {
        if (!_state.value.canQuote) return@launch
        _state.update { UsdtSendState(busy = true, refreshError = it.refreshError) }
        val maximum = maximumQuote?.takeIf {
            it.recipient == recipient && it.destination == destination && usdtFormatAmount(it.amount) == amount
        }
        val sender = wallet.value.address
        val reusable = preparedQuote?.takeIf {
            sender.isNotEmpty() && it.sender == sender && it.amount == amount &&
                it.quote.recipient == recipient && it.quote.destination == destination &&
                it.quote.expiresAt > nowTimestamp().epochSecond.toULong() + 5u
        }?.quote
        preparedQuote = null
        val result = when {
            maximum == null -> {
                maximumQuote = null
                (reusable?.let { Result.success(it) } ?: repo.quote(recipient, amount, destination))
                    .onSuccess { preparedQuote = PreparedQuote(it, amount, sender) }
            }
            maximum.expiresAt > nowTimestamp().epochSecond.toULong() + 5u -> Result.success(maximum)
            else -> repo.quoteMaximum(recipient, destination)
        }
        result.onSuccess { quote -> _state.update { it.copy(quote = quote, busy = false) } }
            .onFailure { error ->
                Logger.warn("Failed to quote USDT with '${error.javaClass.simpleName}'", context = TAG)
                _state.update {
                    it.copy(error = error.messageResource(), errorAmount = error.bridgeLimit(), busy = false)
                }
            }
    }

    fun quoteMaximum(
        recipient: String,
        destination: UsdtDestination,
        onAmount: (String) -> Unit,
    ) = viewModelScope.launch {
        if (!_state.value.canQuote) return@launch
        _state.update { UsdtSendState(busy = true, preparingMaximum = true, refreshError = it.refreshError) }
        preparedQuote = null
        repo.quoteMaximum(recipient, destination)
            .onSuccess { quote ->
                maximumQuote = quote
                onAmount(usdtFormatAmount(quote.amount))
                _state.update { it.copy(busy = false, preparingMaximum = false) }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        error = error.messageResource(),
                        errorAmount = error.bridgeLimit(),
                        busy = false,
                        preparingMaximum = false
                    )
                }
            }
    }

    suspend fun readPaymentSettings(onLoaded: (SettingsData) -> Unit) {
        runSuspendCatching { settingsStore.data.first() }
            .onSuccess {
                _state.update { state -> state.copy(error = null) }
                onLoaded(it)
            }
            .onFailure(::settingsReadFailed)
    }

    private fun settingsReadFailed(error: Throwable) {
        Logger.warn("Failed to read payment settings", error, context = TAG)
        _state.update { it.copy(error = R.string.usdt__error_storage) }
    }

    fun confirm() = viewModelScope.launch {
        if (_state.value.busy || _state.value.submitted) return@launch
        if (_state.value.warning != null || _state.value.authenticationRequired) return@launch
        val quote = _state.value.quote ?: return@launch
        _state.update { it.copy(busy = true) }
        var paymentSettings: SettingsData? = null
        try {
            readPaymentSettings { paymentSettings = it }
        } finally {
            _state.update { if (it.quote?.id == quote.id) it.copy(busy = false) else it }
        }
        val settings = paymentSettings ?: return@launch
        if (_state.value.quote?.id != quote.id) return@launch
        val warnings = quote.warnings(wallet.value.balance, settings.enableSendAmountWarning)
        val warning = warnings.firstOrNull { it !in _state.value.confirmedWarnings }
        if (warning != null) {
            _state.update { it.copy(warning = warning) }
            return@launch
        }
        if (settings.isPinEnabled && settings.isPinForPaymentsEnabled) {
            _state.update { it.copy(authenticationRequired = true) }
        } else {
            send(quote)
        }
    }

    fun acceptWarning() {
        val warning = _state.value.warning ?: return
        _state.update {
            it.copy(
                warning = null,
                confirmedWarnings = (it.confirmedWarnings + warning).toImmutableList()
            )
        }
        confirm()
    }

    fun authenticationVerified() {
        if (_state.value.authenticationRequired) {
            val quote = _state.value.quote ?: return
            _state.update { it.copy(authenticationRequired = false) }
            send(quote)
        }
    }
    fun cancelAuthentication() = _state.update { it.copy(authenticationRequired = false) }
    fun edit() = _state.update { UsdtSendState(refreshError = it.refreshError) }

    fun retryRecovery() = viewModelScope.launch {
        val current = state.value
        val quote = current.quote ?: return@launch
        if (!current.submitted || !current.canRetryRecovery || current.retryingRecovery) return@launch
        _state.update { it.copy(retryingRecovery = true) }
        try {
            repo.retryTransfer(quote.id)
                .onSuccess {
                    if (repo.recoveryGeneration != submissionFailureGeneration) {
                        _state.update { it.copy(error = null, canRetryRecovery = false) }
                    }
                }
                .onFailure { error ->
                    submissionFailureGeneration = repo.recoveryGeneration
                    _state.update { it.copy(error = error.messageResource()) }
                }
        } finally {
            _state.update { it.copy(retryingRecovery = false) }
        }
    }

    private data class PreparedQuote(val quote: UsdtQuote, val amount: String, val sender: String)

    private fun send(quote: UsdtQuote) = viewModelScope.launch {
        if (_state.value.quote?.id != quote.id) return@launch
        if (_state.value.busy) return@launch
        maximumQuote = null
        preparedQuote = null
        _state.update { it.copy(busy = true, error = null) }
        runSuspendCatching {
            val payment = sendPayment
            if (requiresPaymentContext && payment == null) throw PaykitPaymentRequestError.RequestUnavailable
            if (payment != null) payment(quote) else repo.send(quote).getOrThrow()
        }
            .onSuccess {
                _state.update { it.copy(submitted = true) }
            }
            .onFailure { error ->
                Logger.warn("Failed to send USDT with '${error.javaClass.simpleName}'", context = TAG)
                val transfer = wallet.value.transfers.firstOrNull { it.id == quote.id }
                val recorded = transfer != null
                submissionFailureGeneration = repo.recoveryGeneration
                _state.update {
                    it.copy(
                        submitted = recorded,
                        busy = false,
                        quote = quote.takeIf { recorded },
                        error = error.messageResource(),
                        submissionErrorStatus = transfer?.status,
                        canRetryRecovery = recorded && error is UsdtException.BackupUnavailable,
                    )
                }
            }
    }
}

@Stable
data class UsdtSendState(
    val preparingMaximum: Boolean = false,
    val quote: UsdtQuote? = null,
    val busy: Boolean = false,
    val submitted: Boolean = false,
    val retryingRecovery: Boolean = false,
    val canRetryRecovery: Boolean = false,
    val submissionErrorStatus: UsdtTransferStatus? = null,
    val authenticationRequired: Boolean = false,
    val warning: SanityWarning? = null,
    val confirmedWarnings: ImmutableList<SanityWarning> = persistentListOf(),
    @StringRes val error: Int? = null,
    val errorAmount: String? = null,
    @StringRes val refreshError: Int? = null,
) {
    val canQuote: Boolean
        get() = !busy && !submitted && !authenticationRequired && warning == null

    fun submissionError(status: UsdtTransferStatus?): Int? = error.takeIf {
        status != null && status != UsdtTransferStatus.CONFIRMED && status == submissionErrorStatus
    }
}

private fun UsdtQuote.warnings(balance: ULong?, warnOver100: Boolean): List<SanityWarning> = buildList {
    if (balance != null && amount > balance / 2u) add(SanityWarning.OVER_HALF_USDT_BALANCE)
    if (warnOver100 && amount > 100_000_000u) add(SanityWarning.VALUE_OVER_100_USD)
    val totalCost = USat(maximumFee) + USat(USat(amount) - USat(receivedAmount))
    if (totalCost > 10_000_000u) add(SanityWarning.FEE_OVER_10_USD)
    if (totalCost > amount / 2u) add(SanityWarning.FEE_OVER_HALF_VALUE)
}

@StringRes
internal fun Throwable.messageResource(): Int = when (this) {
    is PaykitPaymentRequestError.RequestExpired -> R.string.wallet__payment_request_expired
    is PaykitPaymentRequestError -> R.string.wallet__payment_request_unavailable
    is UsdtSelfSendError -> R.string.usdt__error_self_send
    is UsdtException.NotConfigured -> R.string.usdt__error_configuration
    is UsdtException.InvalidAmount -> R.string.usdt__error_amount
    is UsdtException.BridgeAmountTooSmall -> R.string.usdt__error_bridge_amount
    is UsdtException.BridgeAmountBelowMinimum -> if (minUsdCents != null) {
        R.string.usdt__deposit_minimum
    } else {
        R.string.usdt__error_bridge_minimum
    }
    is UsdtException.BridgeAmountAboveMaximum -> if (maxUsdCents != null) {
        R.string.usdt__deposit_maximum
    } else {
        R.string.usdt__error_bridge_maximum
    }
    is UsdtException.BridgeLiquidityUnavailable -> R.string.usdt__error_bridge_liquidity
    is UsdtException.InvalidAddress, is UsdtException.WrongNetwork -> R.string.usdt__error_address
    is UsdtException.InsufficientBalance -> R.string.usdt__error_balance
    is UsdtException.QuoteExpired -> R.string.usdt__error_expired
    is UsdtException.SubmissionDeadlineExpired -> R.string.wallet__payment_request_expired
    is UsdtException.PendingTransfer -> R.string.usdt__error_pending
    is UsdtException.DepositNeedsAttention -> R.string.usdt__deposit_attention
    is UsdtException.DepositNotFound -> R.string.usdt__deposit_not_found
    is UsdtException.DepositAuthorizationRejected -> R.string.usdt__deposit_authorization
    is UsdtException.DepositAmountOutOfRange -> R.string.usdt__deposit_amount_out_of_range
    is UsdtException.UnsupportedRoute -> R.string.usdt__error_route
    is UsdtException.InvalidCredentials -> R.string.usdt__error_wallet
    is UsdtException.ClockSkew -> R.string.usdt__error_clock
    is UsdtException.UnsupportedDelegation -> R.string.usdt__error_delegation
    is UsdtException.BackupUnavailable -> R.string.usdt__error_backup
    is UsdtException.InvalidBackup, is UsdtException.Storage -> R.string.usdt__error_storage
    is UsdtException.RateLimited -> R.string.usdt__error_rate_limit
    is UsdtException.LogRangeTooLarge, is UsdtException.InvalidResponse -> R.string.usdt__error_response
    is UsdtException.TransactionRejected -> if (reason == "Paymaster fee collection failed") {
        R.string.usdt__error_fee_collection
    } else {
        R.string.usdt__error_rejected
    }
    else -> R.string.usdt__error_network
}

private fun Throwable.bridgeLimit(): String? = when (this) {
    is UsdtException.BridgeAmountBelowMinimum -> minUsdCents?.usdCents()
    is UsdtException.BridgeAmountAboveMaximum -> maxUsdCents?.usdCents()
    else -> null
}
