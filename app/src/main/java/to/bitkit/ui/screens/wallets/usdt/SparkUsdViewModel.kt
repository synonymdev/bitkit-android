package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import breez_sdk_spark.SdkException
import com.synonym.bitkitcore.usdtFormatAmount
import com.synonym.bitkitcore.usdtParseAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkUsdError
import to.bitkit.repositories.SparkUsdQuote
import to.bitkit.repositories.UsdtRepo
import javax.inject.Inject

@HiltViewModel
class SparkUsdViewModel @Inject constructor(
    private val sparkRepo: SparkRepo,
    private val settingsStore: SettingsStore,
    private val usdtRepo: UsdtRepo,
) : ViewModel() {
    val wallet = sparkRepo.state.map { it.usd }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), sparkRepo.state.value.usd)

    val settings = settingsStore.data
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsData(hideBalance = true))

    /** Bitkit's own Arbitrum USDT address, the bridge's destination; Orchestra only runs on mainnet. */
    val bridgeAddress = combine(usdtRepo.state, sparkRepo.state) { usdt, _ -> usdt.address }
        .map { address -> address.takeIf { Env.isSparkBridgeAvailable && it.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _state = MutableStateFlow(SparkUsdSendState())
    val state = _state.asStateFlow()

    fun refresh() = viewModelScope.launch { sparkRepo.refreshState() }

    fun syncNow() = viewModelScope.launch { sparkRepo.syncNow() }

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
            it.quote != null && !it.completed -> it.copy(quote = null, error = null)
            it.page == SparkUsdPage.REVIEW -> it.copy(page = SparkUsdPage.AMOUNT, error = null)
            it.page == SparkUsdPage.AMOUNT -> it.copy(page = SparkUsdPage.RECIPIENT, error = null)
            else -> SparkUsdSendState()
        }
    }

    fun startConvert() = _state.update { SparkUsdSendState(page = SparkUsdPage.CONVERT) }

    fun startBridge() = _state.update { SparkUsdSendState(page = SparkUsdPage.BRIDGE) }

    fun setConvertToUsd(toUsd: Boolean) = _state.update { it.copy(convertToUsd = toUsd, quote = null, error = null) }

    fun quote() = viewModelScope.launch {
        val state = _state.value
        val amount = runCatching { usdtParseAmount(state.amountInput) }.getOrNull()?.takeIf { it > 0uL }
        if (amount == null) {
            _state.update { it.copy(error = R.string.usdt__error_amount) }
            return@launch
        }
        val spendsDollars = state.page == SparkUsdPage.BRIDGE || !state.convertToUsd
        if (spendsDollars && amount > (wallet.value?.balance ?: 0uL)) {
            _state.update { it.copy(error = R.string.usdt__error_balance_spark) }
            return@launch
        }
        val spendsSats = state.page == SparkUsdPage.CONVERT && state.convertToUsd
        if (spendsSats && sparkRepo.state.value.balanceSats == 0uL) {
            _state.update { it.copy(error = R.string.other__pay_insufficient_spending) }
            return@launch
        }
        _state.update { it.copy(busy = true, error = null) }
        val result = when (state.page) {
            SparkUsdPage.BRIDGE -> sparkRepo.quoteArbitrumBridge(bridgeAddress.value.orEmpty(), amount)
            else -> sparkRepo.quoteConversion(state.convertToUsd, amount)
        }
        result
            .onSuccess { quote ->
                val paysSats = quote.kind == SparkUsdQuote.Kind.TO_USD
                val affordable = !paysSats || quote.pay <= sparkRepo.state.value.balanceSats
                _state.update {
                    if (affordable) {
                        it.copy(busy = false, quote = quote)
                    } else {
                        it.copy(busy = false, error = R.string.other__pay_insufficient_spending)
                    }
                }
            }
            .onFailure { error -> _state.update { it.copy(busy = false, error = conversionError(error)) } }
    }

    fun confirmQuote() = viewModelScope.launch {
        val quote = _state.value.quote ?: return@launch
        if (_state.value.busy || _state.value.completed) return@launch
        _state.update { it.copy(busy = true, error = null) }
        sparkRepo.execute(quote)
            .onSuccess { _state.update { it.copy(busy = false, completed = true) } }
            .onFailure { error -> _state.update { it.copy(busy = false, error = conversionError(error)) } }
    }

    private fun conversionError(error: Throwable): Int = when {
        error is SdkException.InsufficientFunds -> R.string.other__pay_insufficient_spending
        error.message?.contains("insufficient", ignoreCase = true) == true -> R.string.other__pay_insufficient_spending
        else -> R.string.usdt__convert_failed_spark
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
                val message = when (error) {
                    is SparkUsdError -> R.string.usdt__error_address_spark
                    else -> R.string.usdt__error_network
                }
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
        if (amount > balance) return@update state.copy(error = R.string.usdt__error_balance_spark)
        state.copy(amount = amount, page = SparkUsdPage.REVIEW, error = null)
    }

    fun confirm() = viewModelScope.launch {
        val state = _state.value
        if (state.busy || state.completed || state.authenticationRequired) return@launch
        val amount = state.amount ?: return@launch
        if (amount > (wallet.value?.balance ?: 0uL)) {
            _state.update { it.copy(error = R.string.usdt__error_balance_spark) }
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
            .onSuccess { id -> _state.update { it.copy(busy = false, completed = true, sentTransferId = id) } }
            .onFailure { _state.update { it.copy(busy = false, error = R.string.usdt__send_failed_description) } }
    }
}

enum class SparkUsdPage { WALLET, RECEIVE, RECIPIENT, AMOUNT, REVIEW, CONVERT, BRIDGE }

@Stable
data class SparkUsdSendState(
    val page: SparkUsdPage = SparkUsdPage.WALLET,
    val receiveRequest: String? = null,
    val recipientInput: String = "",
    val recipient: String? = null,
    val amountInput: String = "",
    val amount: ULong? = null,
    val isAmountFixed: Boolean = false,
    val convertToUsd: Boolean = true,
    val quote: SparkUsdQuote? = null,
    val sentTransferId: String? = null,
    val busy: Boolean = false,
    val completed: Boolean = false,
    val authenticationRequired: Boolean = false,
    val error: Int? = null,
)
