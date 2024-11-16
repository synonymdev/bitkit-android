package to.bitkit.ui.screens.wallets.send

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Tag.APP
import to.bitkit.ext.toast
import to.bitkit.services.LightningService
import to.bitkit.services.ScannerService
import uniffi.bitkitcore.Scanner
import javax.inject.Inject

@HiltViewModel
class SendViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val scannerService: ScannerService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SendUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<SendEffect>(replay = 0, extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()
    private fun setEffect(effect: SendEffect) = viewModelScope.launch { _effect.emit(effect) }

    private val events = MutableSharedFlow<SendEvent>()
    fun setEvent(event: SendEvent) = viewModelScope.launch { events.emit(event) }

    private var _scanResult: Scanner? = null

    init {
        observeEvents()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect {
                when (it) {
                    SendEvent.Contact -> toast("Coming soon: Contact")
                    SendEvent.EnterManually -> onEnterManuallyClick()
                    is SendEvent.AddressChange -> onAddressChange(it.value)
                    SendEvent.AddressReset -> resetAddress()
                    is SendEvent.AddressContinue -> onAddressContinue(it.data)
                    is SendEvent.AmountChange -> onAmountChange(it.value)
                    SendEvent.AmountReset -> resetAmount()
                    is SendEvent.AmountContinue -> onAmountContinue(it.amount)
                    is SendEvent.Paste -> onPasteInvoice(it.data)
                    is SendEvent.Scan -> onScanSuccess(it.data)
                    SendEvent.SpeedAndFee -> toast("Coming soon: Speed and Fee")
                    SendEvent.SwipeToPay -> onPay()
                }
            }
        }
    }

    private fun onEnterManuallyClick() {
        resetAddress()
        setEffect(SendEffect.NavigateToAddress)
    }

    private fun resetAddress() {
        _uiState.update { state ->
            state.copy(
                addressInput = "",
                isAddressInputValid = false,
            )
        }
    }

    private fun onAddressChange(value: String) {
        viewModelScope.launch {
            val result = runCatching { scannerService.decode(value) }
            _uiState.update {
                it.copy(
                    addressInput = value,
                    isAddressInputValid = result.isSuccess,
                )
            }
        }
    }

    private fun onAmountChange(value: String) {
        val isAmountValid = validateAmount(value)
        _uiState.update {
            it.copy(
                amountInput = value,
                isAmountInputValid = isAmountValid,
            )
        }
    }

    private fun validateAmount(value: String): Boolean {
        if (value.isBlank()) return false
        val amount = value.toULongOrNull() ?: return false
        return amount > 0u
    }

    private fun onPasteInvoice(data: String) {
        if (data.isBlank()) {
            Log.e(APP, "No data in clipboard")
            return
        }
        viewModelScope.launch {
            val scan = decodeDataOrNull(data)
            handleData(scan)
        }
    }

    private fun handleData(scan: Scanner?) {
        when (scan) {
            is Scanner.OnChain -> {
                _uiState.update {
                    it.copy(
                        address = scan.invoice.address,
                        amount = scan.invoice.amountSatoshis,
                        label = scan.invoice.label.orEmpty(),
                        message = scan.invoice.message.orEmpty(),
                    )
                }
                resetAmount()
                setEffect(SendEffect.NavigateToAmount)
            }

            is Scanner.Lightning -> {
                toast("Lightning coming soon: ${scan.invoice}")
            }

            null -> {
                // TODO implement
                toast("Error decoding data")
            }

            else -> {
                // TODO implement
                toast("Coming soon…")
            }
        }
    }

    private suspend fun decodeDataOrNull(data: String): Scanner? {
        val result = runCatching { scannerService.decode(data) }
            .onFailure { Log.e(APP, "Failed to decode input data", it) }
            .getOrNull()

        _scanResult = result
        return result
    }

    private suspend fun sendOnchain(address: String, amount: ULong) {
        runCatching { lightningService.send(address = address, amount) }
            .onFailure { withContext(uiThread) { toast("Error sending: $it") } }
    }

    private suspend fun sendLightning(bolt11: String, amount: ULong? = null) {
        runCatching { lightningService.send(bolt11 = bolt11, amount) }
            .onFailure { withContext(uiThread) { toast("Error sending: $it") } }
    }

    private fun onScanSuccess(data: String) {
        viewModelScope.launch {
            val scan = decodeDataOrNull(data)
            handleData(scan)
        }
    }

    private fun onAddressContinue(data: String) {
        viewModelScope.launch {
            val scan = decodeDataOrNull(data)
            handleData(scan)
        }
    }

    private fun resetAmount() {
        _uiState.update { state ->
            state.copy(
                amountInput = "",
                isAmountInputValid = false,
            )
        }
    }

    private fun onAmountContinue(amount: String) {
        _uiState.update {
            it.copy(
                amount = amount.toULongOrNull() ?: 0u,
            )
        }
        setEffect(SendEffect.NavigateToReview)
    }

    private fun onPay() {
        viewModelScope.launch {
            val address = uiState.value.address
            val amount = uiState.value.amount
            sendOnchain(address, amount)
            withContext(uiThread) { toast("Sent success. TODO: handler") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.w(APP, "🚫 SendViewModel cleared")
    }
}

// region contract
data class SendUiState(
    val address: String = "",
    val addressInput: String = "",
    val isAddressInputValid: Boolean = false,
    val amount: ULong = 0u,
    val amountInput: String = "",
    val isAmountInputValid: Boolean = false,
    val label: String = "",
    val message: String = "",
)

sealed class SendEffect {
    data object NavigateToAddress : SendEffect()
    data object NavigateToAmount : SendEffect()
    data object NavigateToReview : SendEffect()
}

sealed class SendEvent {
    data object Contact : SendEvent()
    data object EnterManually : SendEvent()
    data class Paste(val data: String) : SendEvent()
    data class Scan(val data: String) : SendEvent()

    data object AddressReset : SendEvent()
    data class AddressChange(val value: String) : SendEvent()
    data class AddressContinue(val data: String) : SendEvent()

    data object AmountReset : SendEvent()
    data class AmountContinue(val amount: String) : SendEvent()
    data class AmountChange(val value: String) : SendEvent()

    data object SwipeToPay : SendEvent()
    data object SpeedAndFee : SendEvent()
}
// endregion
