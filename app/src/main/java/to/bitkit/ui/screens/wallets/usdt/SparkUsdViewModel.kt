package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.usdtFormatAmount
import com.synonym.bitkitcore.usdtParseAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkUsdError
import javax.inject.Inject

@HiltViewModel
class SparkUsdViewModel @Inject constructor(
    private val sparkRepo: SparkRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val wallet = sparkRepo.state.map { it.usd }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), sparkRepo.state.value.usd)

    val settings = settingsStore.data
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsData(hideBalance = true))

    private val _state = MutableStateFlow(SparkUsdSendState())
    val state = _state.asStateFlow()

    fun refresh() = viewModelScope.launch { sparkRepo.refreshState() }

    fun loadReceiveRequest() = viewModelScope.launch {
        _state.update { it.copy(receiveRequest = null, error = null) }
        sparkRepo.createUsdRequest(amount = null)
            .onSuccess { request -> _state.update { it.copy(receiveRequest = request) } }
            .onFailure { _state.update { it.copy(error = R.string.usdt__error_network) } }
    }

    fun startSend(input: String = "") = _state.update {
        SparkUsdSendState(page = SparkUsdPage.RECIPIENT, recipientInput = input)
    }

    fun showReceive() {
        _state.update { SparkUsdSendState(page = SparkUsdPage.RECEIVE) }
        loadReceiveRequest()
    }

    fun dismiss() {
        if (_state.value.busy) return
        _state.update { SparkUsdSendState() }
    }

    fun back() = _state.update {
        when {
            it.busy -> it
            it.authenticationRequired -> it.copy(authenticationRequired = false)
            it.page == SparkUsdPage.REVIEW -> it.copy(page = SparkUsdPage.AMOUNT, error = null)
            it.page == SparkUsdPage.AMOUNT -> it.copy(page = SparkUsdPage.RECIPIENT, error = null)
            else -> SparkUsdSendState()
        }
    }

    fun setRecipientInput(input: String) = _state.update { it.copy(recipientInput = input, error = null) }

    fun continueWithRecipient() = viewModelScope.launch {
        val input = _state.value.recipientInput.trim()
        if (input.isEmpty()) return@launch
        _state.update { it.copy(busy = true, error = null) }
        sparkRepo.parseUsdRecipient(input)
            .onSuccess { recipient ->
                _state.update {
                    it.copy(
                        busy = false,
                        recipient = recipient.request,
                        amount = recipient.amount,
                        amountInput = recipient.amount?.let(::usdtFormatAmount).orEmpty(),
                        isAmountFixed = recipient.amount != null,
                        page = if (recipient.amount != null) SparkUsdPage.REVIEW else SparkUsdPage.AMOUNT,
                    )
                }
            }
            .onFailure { error ->
                val message = if (error is SparkUsdError) R.string.usdt__error_address else R.string.usdt__error_network
                _state.update { it.copy(busy = false, error = message) }
            }
    }

    fun setAmountInput(input: String) = _state.update { it.copy(amountInput = input, error = null) }

    fun setMaximum() = _state.update { state ->
        wallet.value?.balance?.let { state.copy(amountInput = usdtFormatAmount(it), error = null) } ?: state
    }

    fun review() = _state.update { state ->
        val amount = runCatching { usdtParseAmount(state.amountInput) }.getOrNull()?.takeIf { it > 0uL }
            ?: return@update state.copy(error = R.string.usdt__error_amount)
        val balance = wallet.value?.balance ?: 0uL
        if (amount > balance) return@update state.copy(error = R.string.usdt__error_balance)
        state.copy(amount = amount, page = SparkUsdPage.REVIEW, error = null)
    }

    fun confirm() = viewModelScope.launch {
        val state = _state.value
        if (state.busy || state.completed || state.authenticationRequired) return@launch
        val amount = state.amount ?: return@launch
        if (amount > (wallet.value?.balance ?: 0uL)) {
            _state.update { it.copy(error = R.string.usdt__error_balance) }
            return@launch
        }
        val settings = runSuspendCatching { settingsStore.data.first() }.getOrNull() ?: return@launch
        if (settings.isPinEnabled && settings.isPinForPaymentsEnabled) {
            _state.update { it.copy(authenticationRequired = true) }
        } else {
            send()
        }
    }

    fun authenticationVerified() {
        if (!_state.value.authenticationRequired) return
        _state.update { it.copy(authenticationRequired = false) }
        viewModelScope.launch { send() }
    }

    private suspend fun send() {
        val state = _state.value
        val recipient = state.recipient ?: return
        val amount = state.amount ?: return
        _state.update { it.copy(busy = true, error = null) }
        sparkRepo.sendUsd(recipient, amount, state.isAmountFixed)
            .onSuccess { _state.update { it.copy(busy = false, completed = true) } }
            .onFailure { _state.update { it.copy(busy = false, error = R.string.usdt__send_failed_description) } }
    }
}

enum class SparkUsdPage { WALLET, RECEIVE, RECIPIENT, AMOUNT, REVIEW }

@Immutable
data class SparkUsdSendState(
    val page: SparkUsdPage = SparkUsdPage.WALLET,
    val receiveRequest: String? = null,
    val recipientInput: String = "",
    val recipient: String? = null,
    val amountInput: String = "",
    val amount: ULong? = null,
    val isAmountFixed: Boolean = false,
    val busy: Boolean = false,
    val completed: Boolean = false,
    val authenticationRequired: Boolean = false,
    val error: Int? = null,
)
