package to.bitkit.ui.screens.wallets.usdt

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.UsdtRepo
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

    val destinations = kotlinx.coroutines.flow.flow {
        repo.sendDestinations()
            .onSuccess { emit(it) }
            .onFailure { Logger.warn("Could not load USDT bridge destinations", it, context = TAG) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        to.bitkit.env.Env.usdtDestinations.toImmutableList()
    )

    val wallet = repo.state
    val receivedTxs = repo.receivedTxs
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
    private val _state = MutableStateFlow(UsdtSendState())
    val state = _state.asStateFlow()

    var isSendPresented: Boolean
        get() = repo.isSendPresented
        set(value) { repo.isSendPresented = value }

    suspend fun refresh(history: Boolean = false) {
        repo.refresh(history)
            .onSuccess { _state.update { it.copy(refreshError = null) } }
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

    fun validateRecipient(value: String, destination: UsdtDestination, onValid: (UsdtPaymentRequest) -> Unit) {
        parseUsdtPaymentRequest(value, destination)
            .onSuccess { request ->
                if (request.chainId != null && destination != UsdtDestination.ARBITRUM) {
                    _state.update { it.copy(error = UsdtException.WrongNetwork().messageResource()) }
                    return@onSuccess
                }
                _state.update { it.copy(error = null) }
                onValid(request)
            }
            .onFailure { error -> _state.update { it.copy(error = error.messageResource()) } }
    }

    fun quote(recipient: String, amount: String, destination: UsdtDestination) = viewModelScope.launch {
        if (_state.value.busy || _state.value.authenticationRequired || _state.value.warning != null) return@launch
        _state.update { UsdtSendState(busy = true, refreshError = it.refreshError) }
        repo.quote(recipient, amount, destination)
            .onSuccess { quote -> _state.update { it.copy(quote = quote, busy = false) } }
            .onFailure { error ->
                Logger.warn("Failed to quote USDT with '${error.javaClass.simpleName}'", context = TAG)
                _state.update { it.copy(error = error.messageResource(), busy = false) }
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

    private fun send(quote: UsdtQuote) = viewModelScope.launch {
        if (_state.value.quote?.id != quote.id) return@launch
        if (_state.value.busy) return@launch
        _state.update { it.copy(busy = true, error = null) }
        runSuspendCatching {
            val payment = sendPayment
            if (payment != null) payment(quote) else repo.send(quote).getOrThrow()
        }
            .onSuccess {
                _state.update { it.copy(submitted = true) }
            }
            .onFailure { error ->
                Logger.warn("Failed to send USDT with '${error.javaClass.simpleName}'", context = TAG)
                _state.update {
                    it.copy(
                        busy = false,
                        quote = null,
                        error = error.messageResource()
                    )
                }
            }
    }
}

@Stable
data class UsdtSendState(
    val quote: UsdtQuote? = null,
    val busy: Boolean = false,
    val submitted: Boolean = false,
    val authenticationRequired: Boolean = false,
    val warning: SanityWarning? = null,
    val confirmedWarnings: ImmutableList<SanityWarning> = persistentListOf(),
    @StringRes val error: Int? = null,
    @StringRes val refreshError: Int? = null,
)

private fun UsdtQuote.warnings(balance: ULong?, warnOver100: Boolean): List<SanityWarning> = buildList {
    if (balance != null && amount > balance / 2u) add(SanityWarning.OVER_HALF_BALANCE)
    if (warnOver100 && amount > 100_000_000u) add(SanityWarning.VALUE_OVER_100_USD)
    if (maximumFee > 10_000_000u) add(SanityWarning.FEE_OVER_10_USD)
    if (maximumFee > amount / 2u) add(SanityWarning.FEE_OVER_HALF_VALUE)
}

@StringRes
@Suppress("CyclomaticComplexMethod")
internal fun Throwable.messageResource(): Int = when (this) {
    is UsdtException.NotConfigured -> R.string.usdt__error_configuration
    is UsdtException.InvalidAmount -> R.string.usdt__error_amount
    is UsdtException.InvalidAddress, is UsdtException.WrongNetwork -> R.string.usdt__error_address
    is UsdtException.InsufficientBalance -> R.string.usdt__error_balance
    is UsdtException.QuoteExpired -> R.string.usdt__error_expired
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
    is UsdtException.TransactionRejected -> R.string.usdt__error_rejected
    else -> R.string.usdt__error_network
}
