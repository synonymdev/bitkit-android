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
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Tag.APP
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.services.LightningService
import to.bitkit.services.ScannerService
import to.bitkit.services.hasLightingParam
import to.bitkit.services.lightningParam
import to.bitkit.ui.shared.toast.ToastEventBus
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
                    SendEvent.EnterManually -> onEnterManuallyClick()
                    is SendEvent.Paste -> onPasteInvoice(it.data)
                    is SendEvent.Scan -> onScanSuccess(it.data)

                    is SendEvent.AddressChange -> onAddressChange(it.value)
                    SendEvent.AddressReset -> resetAddressInput()
                    is SendEvent.AddressContinue -> onAddressContinue(it.data)

                    is SendEvent.AmountChange -> onAmountChange(it.value)
                    SendEvent.AmountReset -> resetAmountInput()
                    is SendEvent.AmountContinue -> onAmountContinue(it.amount)
                    SendEvent.PaymentMethodSwitch -> onPaymentMethodSwitch()

                    SendEvent.SpeedAndFee -> ToastEventBus.send(Exception("Coming soon: Speed and Fee"))
                    SendEvent.SwipeToPay -> onPay()
                }
            }
        }
    }

    private fun onEnterManuallyClick() {
        resetAddressInput()
        setEffect(SendEffect.NavigateToAddress)
    }

    private fun resetAddressInput() {
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
            handleScannedData(data)
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
            handleScannedData(data)
        }
    }

    private fun onScanSuccess(data: String) {
        viewModelScope.launch {
            handleScannedData(data)
        }
    }

    private suspend fun handleScannedData(data: String) {
        val scan = runCatching { scannerService.decode(data) }
            .onFailure { Log.e(APP, "Failed to decode input data", it) }
            .getOrNull()
        this.scan = scan

        when (scan) {
            is Scanner.OnChain -> {
                val invoice: OnChainInvoice = scan.invoice
                val lnInvoice: LightningInvoice? = invoice.lightningParam()?.let { bolt11 ->
                    val decoded = runCatching { scannerService.decode(bolt11) }.getOrNull()
                    val lightningInvoice = (decoded as? Scanner.Lightning)?.invoice ?: return@let null

                    if (lightningService.canSend(lightningInvoice.amountSatoshis)) {
                        return@let lightningInvoice
                    } else {
                        // Ignore lighting
                        return@let null
                    }
                }
                _uiState.update {
                    it.copy(
                        address = invoice.address,
                        bolt11 = invoice.lightningParam(),
                        amount = invoice.amountSatoshis,
                        isUnified = invoice.hasLightingParam(),
                        decodedInvoice = lnInvoice,
                        payMethod = lnInvoice?.let { SendMethod.LIGHTNING } ?: SendMethod.ONCHAIN,
                    )
                }
                val isLnInvoiceWithAmount = lnInvoice?.amountSatoshis != null && lnInvoice.amountSatoshis > 0uL
                if (isLnInvoiceWithAmount) {
                    Log.i(APP, "Found amount in invoice, proceeding with payment")
                    setEffect(SendEffect.NavigateToReview)
                    return
                }
                Log.i(APP, "No amount found in invoice, proceeding entering amount manually")
                resetAmountInput()
                setEffect(SendEffect.NavigateToAmount)
            }

            is Scanner.Lightning -> {
                val invoice: LightningInvoice = scan.invoice
                if (invoice.isExpired) {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Invoice Expired",
                        description = "This invoice has expired."
                    )
                    return
                }
                if (!lightningService.canSend(invoice.amountSatoshis)) {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Insufficient Funds",
                        description = "You do not have enough funds to send this payment."
                    )
                    return
                }

                _uiState.update {
                    it.copy(
                        amount = invoice.amountSatoshis,
                        bolt11 = data,
                        description = invoice.description.orEmpty(),
                        decodedInvoice = invoice,
                        payMethod = SendMethod.LIGHTNING,
                    )
                }
                if (invoice.amountSatoshis > 0uL) {
                    Log.i(APP, "Found amount in invoice, proceeding with payment")
                    setEffect(SendEffect.NavigateToReview)
                } else {
                    Log.i(APP, "No amount found in invoice, proceeding entering amount manually")
                    resetAmountInput()
                    setEffect(SendEffect.NavigateToAmount)
                }
            }

            null -> {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Error",
                    description = "Error decoding data"
                )
            }

            else -> {
                Log.w(APP, "Unhandled invoice type:: $data")
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Unsupported",
                    description = "This type of invoice is not supported yet"
                )
            }
        }
    }

    private fun resetAmountInput() {
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
                        val txId = result.getOrNull()
                        Log.i(APP, "Onchain send result txid: $txId")
                        setEffect(
                            SendEffect.PaymentSuccess(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.ONCHAIN,
                                    direction = NewTransactionSheetDirection.SENT,
                                    sats = amount.toLong(),
                                )
                            )
                        )
                    } else {
                        // TODO error UI
                        Log.e(APP, "Error sending onchain payment", result.exceptionOrNull())
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
                        val paymentHash = result.getOrNull()
                        Log.i(APP, "Lightning send result payment hash: $paymentHash")
                        setEffect(SendEffect.PaymentSuccess())
                    } else {
                        // TODO error UI
                        Log.e(APP, "Error sending lightning payment", result.exceptionOrNull())
                    }
                }
            }
        }
    }

    private suspend fun sendOnchain(address: String, amount: ULong): Result<Txid> {
        return runCatching { lightningService.send(address = address, amount) }
            .onFailure {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Error Sending",
                    description = it.message ?: "Unknown error"
                )
            }
    }

    private suspend fun sendLightning(bolt11: String, amount: ULong? = null): Result<PaymentId> {
        return runCatching { lightningService.send(bolt11 = bolt11, amount) }
            .onFailure {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Error Sending",
                    description = it.message ?: "Unknown error"
                )
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
