package to.bitkit.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Tag.APP
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.services.LightningService
import to.bitkit.services.ScannerService
import to.bitkit.services.hasLightingParam
import to.bitkit.services.lightningParam
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.screens.wallets.send.SendRoute
import to.bitkit.ui.shared.toast.ToastEventBus
import uniffi.bitkitcore.LightningInvoice
import uniffi.bitkitcore.OnChainInvoice
import uniffi.bitkitcore.Scanner
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val keychain: Keychain,
    private val scannerService: ScannerService,
    private val lightningService: LightningService,
) : ViewModel() {
    var uiState by mutableStateOf(AppUiState())
        private set

    private val _sendUiState = MutableStateFlow(SendUiState())
    val sendUiState = _sendUiState.asStateFlow()

    private val _sendEffect = MutableSharedFlow<SendEffect>(replay = 0, extraBufferCapacity = 1)
    val sendEffect = _sendEffect.asSharedFlow()
    private fun setSendEffect(effect: SendEffect) = viewModelScope.launch { _sendEffect.emit(effect) }

    private val sendEvents = MutableSharedFlow<SendEvent>()
    fun setSendEvent(event: SendEvent) = viewModelScope.launch { sendEvents.emit(event) }

    private var scan: Scanner? = null

    init {
        viewModelScope.launch {
            keychain.observeExists(Keychain.Key.BIP39_MNEMONIC).collect { walletExists ->
                uiState = uiState.copy(walletExists = walletExists)
            }
        }

        viewModelScope.launch {
            ToastEventBus.events.collect {
                toast(it.type, it.title, it.description, it.autoHide, it.visibilityTime)
            }
        }

        observeSendEvents()
    }

    // region send
    private fun observeSendEvents() {
        viewModelScope.launch {
            sendEvents.collect {
                when (it) {
                    SendEvent.EnterManually -> onEnterManuallyClick()
                    is SendEvent.Paste -> onPasteInvoice(it.data)
                    is SendEvent.Scan -> onScanClick()

                    is SendEvent.AddressChange -> onAddressChange(it.value)
                    SendEvent.AddressReset -> resetAddressInput()
                    is SendEvent.AddressContinue -> onAddressContinue(it.data)

                    is SendEvent.AmountChange -> onAmountChange(it.value)
                    SendEvent.AmountReset -> resetAmountInput()
                    is SendEvent.AmountContinue -> onAmountContinue(it.amount)
                    SendEvent.PaymentMethodSwitch -> onPaymentMethodSwitch()

                    SendEvent.SpeedAndFee -> toast(Exception("Coming soon: Speed and Fee"))
                    SendEvent.SwipeToPay -> onPay()
                }
            }
        }
    }

    private val isMainScanner get() = currentSheet.value !is BottomSheetType.Send

    private fun onEnterManuallyClick() {
        resetAddressInput()
        setSendEffect(SendEffect.NavigateToAddress)
    }

    private fun resetAddressInput() {
        _sendUiState.update { state ->
            state.copy(
                addressInput = "",
                isAddressInputValid = false,
            )
        }
    }

    private fun onAddressChange(value: String) {
        viewModelScope.launch {
            val result = runCatching { scannerService.decode(value) }
            _sendUiState.update {
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
        _sendUiState.update {
            it.copy(
                amountInput = value,
                isAmountInputValid = isAmountValid,
            )
        }
    }

    private fun onPaymentMethodSwitch() {
        val nextPaymentMethod = when (_sendUiState.value.payMethod) {
            SendMethod.ONCHAIN -> SendMethod.LIGHTNING
            SendMethod.LIGHTNING -> SendMethod.ONCHAIN
        }
        _sendUiState.update {
            it.copy(
                payMethod = nextPaymentMethod,
                isAmountInputValid = validateAmount(it.amountInput, nextPaymentMethod),
            )
        }
    }

    private fun onAmountContinue(amount: String) {
        _sendUiState.update {
            it.copy(
                amount = amount.toULongOrNull() ?: 0u,
            )
        }
        setSendEffect(SendEffect.NavigateToReview)
    }

    private fun validateAmount(
        value: String,
        payMethod: SendMethod = _sendUiState.value.payMethod,
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

    private fun onScanClick() {
        setSendEffect(SendEffect.NavigateToScan)
    }

    fun onScanSuccess(data: String, onResultDelay: Long = 0) {
        viewModelScope.launch {
            delay(onResultDelay)
            handleScannedData(data)
        }
    }

    private suspend fun handleScannedData(uri: String) {
        val scan = runCatching { scannerService.decode(uri) }
            .onFailure { Log.e(APP, "Failed to decode input data", it) }
            .getOrNull()
        this.scan = scan

        when (scan) {
            is Scanner.OnChain -> {
                val invoice: OnChainInvoice = scan.invoice
                val lnInvoice: LightningInvoice? = invoice.lightningParam()?.let { bolt11 ->
                    val decoded = runCatching { scannerService.decode(bolt11) }.getOrNull()
                    val lightningInvoice = (decoded as? Scanner.Lightning)?.invoice
                    lightningInvoice?.takeIf { lightningService.canSend(it.amountSatoshis) }
                }
                _sendUiState.update {
                    it.copy(
                        address = invoice.address,
                        bolt11 = invoice.lightningParam(),
                        amount = invoice.amountSatoshis,
                        isUnified = invoice.hasLightingParam(),
                        decodedInvoice = lnInvoice,
                        payMethod = lnInvoice?.let { SendMethod.LIGHTNING } ?: SendMethod.ONCHAIN,
                    )
                }
                val isLnInvoiceWithAmount = lnInvoice?.amountSatoshis?.takeIf { it > 0uL } != null
                if (isLnInvoiceWithAmount) {
                    Log.i(APP, "Found amount in invoice, proceeding with payment")

                    if (isMainScanner) {
                        showSheet(BottomSheetType.Send(SendRoute.ReviewAndSend))
                    } else {
                        setSendEffect(SendEffect.NavigateToReview)
                    }
                    return
                }
                Log.i(APP, "No amount found in invoice, proceeding entering amount manually")
                resetAmountInput()

                if (isMainScanner) {
                    showSheet(BottomSheetType.Send(SendRoute.Amount))
                } else {
                    setSendEffect(SendEffect.NavigateToAmount)
                }
            }

            is Scanner.Lightning -> {
                val invoice: LightningInvoice = scan.invoice
                if (invoice.isExpired) {
                    toast(
                        type = Toast.ToastType.ERROR,
                        title = "Invoice Expired",
                        description = "This invoice has expired."
                    )
                    return
                }
                if (!lightningService.canSend(invoice.amountSatoshis)) {
                    toast(
                        type = Toast.ToastType.ERROR,
                        title = "Insufficient Funds",
                        description = "You do not have enough funds to send this payment."
                    )
                    return
                }

                _sendUiState.update {
                    it.copy(
                        amount = invoice.amountSatoshis,
                        bolt11 = uri,
                        description = invoice.description.orEmpty(),
                        decodedInvoice = invoice,
                        payMethod = SendMethod.LIGHTNING,
                    )
                }
                if (invoice.amountSatoshis > 0uL) {
                    Log.i(APP, "Found amount in invoice, proceeding with payment")

                    if (isMainScanner) {
                        showSheet(BottomSheetType.Send(SendRoute.ReviewAndSend))
                    } else {
                        setSendEffect(SendEffect.NavigateToReview)
                    }
                } else {
                    Log.i(APP, "No amount found in invoice, proceeding entering amount manually")
                    resetAmountInput()

                    if (isMainScanner) {
                        showSheet(BottomSheetType.Send(SendRoute.Amount))
                    } else {
                        setSendEffect(SendEffect.NavigateToAmount)
                    }
                }
            }

            null -> {
                toast(
                    type = Toast.ToastType.ERROR,
                    title = "Error",
                    description = "Error decoding data"
                )
            }

            else -> {
                Log.w(APP, "Unhandled invoice type: $scan")
                toast(
                    type = Toast.ToastType.ERROR,
                    title = "Unsupported",
                    description = "This type of invoice is not supported yet"
                )
            }
        }
    }

    private fun resetAmountInput() {
        _sendUiState.update { state ->
            state.copy(
                amountInput = state.amount.toString(),
                isAmountInputValid = validateAmount(state.amount.toString()),
            )
        }
    }

    private fun onPay() {
        viewModelScope.launch {
            val amount = _sendUiState.value.amount
            when (_sendUiState.value.payMethod) {
                SendMethod.ONCHAIN -> {
                    val address = _sendUiState.value.address
                    val validatedAddress = runCatching { scannerService.validateBitcoinAddress(address) }
                        .getOrNull()
                        ?: return@launch // TODO show error
                    val result = sendOnchain(validatedAddress.address, amount)
                    if (result.isSuccess) {
                        val txId = result.getOrNull()
                        Log.i(APP, "Onchain send result txid: $txId")
                        setSendEffect(
                            SendEffect.PaymentSuccess(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.ONCHAIN,
                                    direction = NewTransactionSheetDirection.SENT,
                                    sats = amount.toLong(),
                                )
                            )
                        )
                        resetSendState()
                    } else {
                        // TODO error UI
                        Log.e(APP, "Error sending onchain payment", result.exceptionOrNull())
                    }
                }

                SendMethod.LIGHTNING -> {
                    val bolt11 = _sendUiState.value.bolt11 ?: return@launch // TODO show error
                    // Determine if we should override amount
                    val decodedInvoice = _sendUiState.value.decodedInvoice
                    val invoiceAmount = decodedInvoice?.amountSatoshis?.takeIf { it > 0uL } ?: amount
                    val paymentAmount = if (decodedInvoice?.amountSatoshis != null) invoiceAmount else null
                    val result = sendLightning(bolt11, paymentAmount)
                    if (result.isSuccess) {
                        val paymentHash = result.getOrNull()
                        Log.i(APP, "Lightning send result payment hash: $paymentHash")
                        setSendEffect(SendEffect.PaymentSuccess())
                        resetSendState()
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
                toast(
                    type = Toast.ToastType.ERROR,
                    title = "Error Sending",
                    description = it.message ?: "Unknown error"
                )
            }
    }

    private suspend fun sendLightning(bolt11: String, amount: ULong? = null): Result<PaymentId> {
        return runCatching { lightningService.send(bolt11 = bolt11, amount) }
            .onFailure {
                toast(
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

    fun resetSendState() {
        _sendUiState.value = SendUiState()
    }
    // endregion

    // region TxSheet
    var showNewTransaction by mutableStateOf(false)
        private set

    var newTransaction by mutableStateOf(
        NewTransactionSheetDetails(
            NewTransactionSheetType.LIGHTNING,
            NewTransactionSheetDirection.RECEIVED,
            0
        )
    )

    fun showNewTransactionSheet(details: NewTransactionSheetDetails) {
        newTransaction = details
        showNewTransaction = true
    }

    fun hideNewTransactionSheet() {
        showNewTransaction = false
    }
    // endregion

    // region Sheets
    var currentSheet = mutableStateOf<BottomSheetType?>(null)
        private set

    fun showSheet(sheetType: BottomSheetType) {
        currentSheet.value = sheetType
    }

    fun hideSheet() {
        currentSheet.value = null
    }
    // endregion

    // region Toasts
    var currentToast by mutableStateOf<Toast?>(null)
        private set

    fun toast(
        type: Toast.ToastType,
        title: String,
        description: String,
        autoHide: Boolean = true,
        visibilityTime: Long = Toast.VISIBILITY_TIME_DEFAULT,
    ) {
        currentToast = Toast(
            type = type,
            title = title,
            description = description,
            autoHide = autoHide,
            visibilityTime = visibilityTime
        )
        if (autoHide) {
            viewModelScope.launch {
                delay(visibilityTime)
                currentToast = null
            }
        }
    }

    fun toast(error: Throwable) {
        toast(type = Toast.ToastType.ERROR, title = "Error", description = error.message ?: "Unknown error")
    }

    fun hideToast() {
        currentToast = null
    }
    // endregion

    fun loadMnemonic(): String? {
        return keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
    }
}

data class AppUiState(
    val walletExists: Boolean? = null,
)

// region send contract
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
    data object NavigateToScan : SendEffect()
    data object NavigateToReview : SendEffect()
    data class PaymentSuccess(val sheet: NewTransactionSheetDetails? = null) : SendEffect()
}

sealed class SendEvent {
    data object EnterManually : SendEvent()
    data class Paste(val data: String) : SendEvent()
    data object Scan : SendEvent()

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
