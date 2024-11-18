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
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Tag.APP
import to.bitkit.ext.toast
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.services.LightningService
import to.bitkit.services.ScannerService
import to.bitkit.services.bolt11
import to.bitkit.services.supportsLightning
import uniffi.bitkitcore.LightningInvoice
import uniffi.bitkitcore.OnChainInvoice
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

    private var scan: Scanner? = null

    init {
        observeEvents()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect {
                when (it) {
                    SendEvent.Contact -> toast("Coming soon: Contact")
                    SendEvent.EnterManually -> onEnterManuallyClick()
                    is SendEvent.Paste -> onPasteInvoice(it.data)
                    is SendEvent.Scan -> onScanSuccess(it.data)

                    is SendEvent.AddressChange -> onAddressChange(it.value)
                    SendEvent.AddressReset -> resetAddress()
                    is SendEvent.AddressContinue -> onAddressContinue(it.data)

                    is SendEvent.AmountChange -> onAmountChange(it.value)
                    SendEvent.AmountReset -> resetAmount()
                    is SendEvent.AmountContinue -> onAmountContinue(it.amount)
                    SendEvent.PaymentMethodSwitch -> onPaymentMethodSwitch()

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

    private fun onAddressContinue(data: String) {
        viewModelScope.launch {
            decodeAndHandle(data)
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

    private fun onPaymentMethodSwitch() {
        val nextPaymentMethod = when (uiState.value.payMethod) {
            SendMethod.ONCHAIN -> SendMethod.LIGHTNING
            SendMethod.LIGHTNING -> SendMethod.ONCHAIN
        }
        _uiState.update {
            it.copy(
                payMethod = nextPaymentMethod,
                isAmountInputValid = validateAmount(it.amountInput, nextPaymentMethod),
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

    private fun validateAmount(
        value: String,
        payMethod: SendMethod = uiState.value.payMethod,
    ): Boolean {
        if (value.isBlank()) return false
        val amount = value.toULongOrNull() ?: return false
        return when (payMethod) {
            SendMethod.ONCHAIN -> amount > getMinOnchainTx()
            else -> amount > 0u
        }
    }

    private fun onPasteInvoice(data: String) {
        if (data.isBlank()) {
            Log.e(APP, "No data in clipboard")
            return
        }
        viewModelScope.launch {
            decodeAndHandle(data)
        }
    }

    private fun onScanSuccess(data: String) {
        viewModelScope.launch {
            decodeAndHandle(data)
        }
    }

    private suspend fun decodeAndHandle(data: String) {
        val scan = runCatching { scannerService.decode(data) }
            .onFailure { Log.e(APP, "Failed to decode input data", it) }
            .getOrNull()
        this.scan = scan

        when (scan) {
            is Scanner.OnChain -> {
                val invoice: OnChainInvoice = scan.invoice
                val lnInvoice: LightningInvoice? = invoice.bolt11()?.let { bolt11 ->
                    val decoded = runCatching { scannerService.decode(bolt11) }.getOrNull()
                    (decoded as? Scanner.Lightning)?.invoice
                }
                _uiState.update {
                    it.copy(
                        address = invoice.address,
                        bolt11 = invoice.bolt11(),
                        amount = invoice.amountSatoshis,
                        isUnified = invoice.supportsLightning(),
                        decodedInvoice = lnInvoice,
                        payMethod = lnInvoice?.let { SendMethod.LIGHTNING  } ?: SendMethod.ONCHAIN,
                    )
                }
                val isLnInvoiceWithAmount = lnInvoice?.amountSatoshis != null && lnInvoice.amountSatoshis > 0uL
                if (isLnInvoiceWithAmount) {
                    setEffect(SendEffect.NavigateToReview)
                    return
                }
                resetAmount()
                setEffect(SendEffect.NavigateToAmount)
            }

            is Scanner.Lightning -> {
                val invoice: LightningInvoice = scan.invoice
                _uiState.update {
                    it.copy(
                        amount = invoice.amountSatoshis,
                        bolt11 = data,
                        description = invoice.description.orEmpty(),
                        decodedInvoice = invoice,
                        payMethod = SendMethod.LIGHTNING,
                    )
                }
                if (invoice.amountSatoshis == 0uL) {
                    resetAmount()
                    setEffect(SendEffect.NavigateToAmount)
                } else {
                    setEffect(SendEffect.NavigateToReview)
                }
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

    private fun resetAmount() {
        _uiState.update { state ->
            state.copy(
                amountInput = "",
                isAmountInputValid = false,
            )
        }
    }

    private fun onPay() {
        viewModelScope.launch {
            val amount = uiState.value.amount
            when (uiState.value.payMethod) {
                SendMethod.ONCHAIN -> {
                    val address = uiState.value.address
                    val validatedAddress = runCatching { scannerService.validateBitcoinAddress(address) }
                        .getOrNull()
                        ?: return@launch // TODO show error
                    val result = sendOnchain(validatedAddress.address, amount)
                    if (result.isSuccess) {
                        setEffect(
                            SendEffect.PaymentSuccess(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.ONCHAIN,
                                    direction = NewTransactionSheetDirection.SENT,
                                    sats = amount.toLong(),
                                )
                            )
                        )
                    }
                }

                SendMethod.LIGHTNING -> {
                    val bolt11 = uiState.value.bolt11 ?: return@launch // TODO show error
                    // Determine if we should override amount
                    val decodedInvoice = uiState.value.decodedInvoice
                    val invoiceAmount = decodedInvoice?.amountSatoshis?.takeIf { it > 0uL } ?: amount
                    val paymentAmount = if (decodedInvoice?.amountSatoshis != null) invoiceAmount else null
                    val result = sendLightning(bolt11, paymentAmount)
                    if (result.isSuccess) {
                        setEffect(SendEffect.PaymentSuccess())
                    }
                }
            }
        }
    }

    private suspend fun sendOnchain(address: String, amount: ULong): Result<Txid> {
        return runCatching { lightningService.send(address = address, amount) }
            .onFailure {
                withContext(uiThread) { toast("Error sending: $it") }
            }
    }

    private suspend fun sendLightning(bolt11: String, amount: ULong? = null): Result<PaymentId> {
        return runCatching { lightningService.send(bolt11 = bolt11, amount) }
            .onFailure {
                withContext(uiThread) { toast("Error sending: $it") }
            }
    }

    private fun getMinOnchainTx(): ULong {
        // TODO implement min tx size
        return 600uL
    }

    override fun onCleared() {
        super.onCleared()
        Log.w(APP, "🚫 SendViewModel cleared")
    }
}

// region contract
data class SendUiState(
    val address: String = "",
    val bolt11: String? = null,
    val addressInput: String = "",
    val isAddressInputValid: Boolean = false,
    val amount: ULong = 0u,
    val amountInput: String = "",
    val isAmountInputValid: Boolean = false,
    val description: String = "",
    val isUnified: Boolean = false,
    val payMethod: SendMethod = SendMethod.ONCHAIN,
    val decodedInvoice: LightningInvoice? = null,
)

enum class SendMethod { ONCHAIN, LIGHTNING }

sealed class SendEffect {
    data object NavigateToAddress : SendEffect()
    data object NavigateToAmount : SendEffect()
    data object NavigateToReview : SendEffect()
    data class PaymentSuccess(val sheet: NewTransactionSheetDetails? = null) : SendEffect()
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
    data object PaymentMethodSwitch : SendEvent()
}
// endregion
