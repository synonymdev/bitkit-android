package to.bitkit.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.WatchResult
import to.bitkit.ext.removeSpaces
import to.bitkit.ext.watchUntil
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.services.LightningService
import to.bitkit.services.ScannerService
import to.bitkit.services.hasLightingParam
import to.bitkit.services.lightningParam
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.screens.wallets.send.SendRoute
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.ActivityFilter
import uniffi.bitkitcore.LightningInvoice
import uniffi.bitkitcore.OnChainInvoice
import uniffi.bitkitcore.PaymentType
import uniffi.bitkitcore.Scanner
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val keychain: Keychain,
    private val scannerService: ScannerService,
    private val lightningService: LightningService,
    private val coreService: CoreService,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    var splashVisible by mutableStateOf(true)
        private set

    var isGeoBlocked by mutableStateOf<Boolean?>(null)
        private set

    private val _sendUiState = MutableStateFlow(SendUiState())
    val sendUiState = _sendUiState.asStateFlow()

    private val _sendEffect = MutableSharedFlow<SendEffect>(replay = 0, extraBufferCapacity = 1)
    val sendEffect = _sendEffect.asSharedFlow()
    private fun setSendEffect(effect: SendEffect) = viewModelScope.launch { _sendEffect.emit(effect) }

    private val sendEvents = MutableSharedFlow<SendEvent>()
    fun setSendEvent(event: SendEvent) = viewModelScope.launch { sendEvents.emit(event) }

    val showEmptyState: StateFlow<Boolean> = settingsStore.showEmptyState
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setShowEmptyState(value: Boolean) {
        viewModelScope.launch {
            settingsStore.setShowEmptyState(value)
        }
    }

    val hasSeenSpendingIntro: StateFlow<Boolean> = settingsStore.hasSeenSpendingIntro
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setHasSeenSpendingIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.setHasSeenSpendingIntro(value)
        }
    }

    val hasSeenSavingsIntro: StateFlow<Boolean> = settingsStore.hasSeenSavingsIntro
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setHasSeenSavingsIntro(value: Boolean) {
        viewModelScope.launch {
            settingsStore.setHasSeenSavingsIntro(value)
        }
    }

    fun addTagToSelected(newTag: String) {
        _sendUiState.update {
            it.copy(
                selectedTags = (it.selectedTags + newTag).distinct()
            )
        }
    }

    fun removeTag(tag: String) {
        _sendUiState.update {
            it.copy(
                selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }
            )
        }
    }

    private var scan: Scanner? = null

    init {
        viewModelScope.launch {
            ToastEventBus.events.collect {
                toast(it.type, it.title, it.description, it.autoHide, it.visibilityTime)
            }
        }
        viewModelScope.launch {
            delay(1500)
            splashVisible = false
        }

        observeLdkNodeEvents()
        observeSendEvents()
        checkGeoStatus()
    }

    private fun observeLdkNodeEvents() {
        viewModelScope.launch {
            ldkNodeEventBus.events.collect { event ->
                try {
                    when (event) {
                        is Event.PaymentReceived -> {
                            showNewTransactionSheet(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.LIGHTNING,
                                    direction = NewTransactionSheetDirection.RECEIVED,
                                    sats = (event.amountMsat / 1000u).toLong(),
                                )
                            )
                        }

                        is Event.ChannelPending -> Unit // Only relevant for channels to external nodes

                        is Event.ChannelReady -> {
                            // TODO: handle ONLY cjit as payment received. This makes it look like any channel confirmed is a received payment.
                            val channel = lightningService.channels?.find { it.channelId == event.channelId }
                            if (channel != null) {
                                showNewTransactionSheet(
                                    NewTransactionSheetDetails(
                                        type = NewTransactionSheetType.LIGHTNING,
                                        direction = NewTransactionSheetDirection.RECEIVED,
                                        sats = (channel.inboundCapacityMsat / 1000u).toLong(),
                                    )
                                )
                            } else {
                                toast(
                                    type = Toast.ToastType.ERROR,
                                    title = "Channel opened",
                                    description = "Ready to send"
                                )
                            }
                        }

                        is Event.ChannelClosed -> {
                            toast(
                                type = Toast.ToastType.LIGHTNING,
                                title = "Channel closed",
                                description = "Balance moved from spending to savings"
                            )
                        }

                        is Event.PaymentSuccessful -> {
                            showNewTransactionSheet(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.LIGHTNING,
                                    direction = NewTransactionSheetDirection.SENT,
                                    sats = ((event.feePaidMsat ?: 0u) / 1000u).toLong(),
                                )
                            )
                        }

                        is Event.PaymentClaimable -> Unit
                        is Event.PaymentFailed -> Unit
                    }
                } catch (e: Exception) {
                    Logger.error("LDK event handler error", e)
                }
            }
        }
    }

    private fun checkGeoStatus() {
        viewModelScope.launch {
            try {
                isGeoBlocked = coreService.checkGeoStatus()
            } catch (e: Throwable) {
                Logger.error("Failed to check geo status: ${e.message}", context = "GeoCheck")
            }
        }
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
                    SendEvent.BackSpaceClick -> onClickBackspace()
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
        val valueWithoutSpaces = value.removeSpaces()
        viewModelScope.launch {
            val result = runCatching { scannerService.decode(valueWithoutSpaces) }
            _sendUiState.update {
                it.copy(
                    addressInput = valueWithoutSpaces,
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
        val newInput = if (_sendUiState.value.amountInput == "0") value else _sendUiState.value.amountInput + value
        _sendUiState.update {
            it.copy(
                amountInput = newInput,
                isAmountInputValid = validateAmount(newInput)
            )
        }
    }

    private fun onClickBackspace() {
        val newInput = if (_sendUiState.value.amountInput.length <= 1) "0" else _sendUiState.value.amountInput.dropLast(1)
        _sendUiState.update {
            it.copy(
                amountInput = newInput,
                isAmountInputValid = validateAmount(newInput)
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
            Logger.error("No data in clipboard")
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
            .onFailure { Logger.error("Failed to decode input data", it) }
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
                    Logger.info("Found amount in invoice, proceeding with payment")

                    if (isMainScanner) {
                        showSheet(BottomSheetType.Send(SendRoute.ReviewAndSend))
                    } else {
                        setSendEffect(SendEffect.NavigateToReview)
                    }
                    return
                }
                Logger.info("No amount found in invoice, proceeding entering amount manually")
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
                    Logger.info("Found amount in invoice, proceeding with payment")

                    if (isMainScanner) {
                        showSheet(BottomSheetType.Send(SendRoute.ReviewAndSend))
                    } else {
                        setSendEffect(SendEffect.NavigateToReview)
                    }
                } else {
                    Logger.info("No amount found in invoice, proceeding entering amount manually")
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
                Logger.warn("Unhandled invoice type: $scan")
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
                        attachTagsToActivity(paymentHashOrTxId = txId, type = ActivityFilter.ONCHAIN)
                        Logger.info("Onchain send result txid: $txId")
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
                        Logger.error("Error sending onchain payment", result.exceptionOrNull())
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
                        Logger.info("Lightning send result payment hash: $paymentHash")
                        attachTagsToActivity(paymentHashOrTxId = paymentHash, type = ActivityFilter.LIGHTNING)
                        setSendEffect(SendEffect.PaymentSuccess())
                        resetSendState()
                    } else {
                        // TODO error UI
                        Logger.error("Error sending lightning payment", result.exceptionOrNull())
                    }
                }
            }
        }
    }

    private fun attachTagsToActivity(paymentHashOrTxId: String?, type: ActivityFilter) {
        val tags = _sendUiState.value.selectedTags
        Logger.debug("attachTagsToActivity $tags")
        if (tags.isEmpty()) {
            Logger.debug("selectedTags empty")
            return
        }

        if (paymentHashOrTxId == null) {
            Logger.error(msg = "null paymentHashOrTxId")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val activity = coreService.activity.get(filter = type, txType = PaymentType.SENT, limit = 1u).firstOrNull()

            if (activity == null) {
                Logger.error(msg = "Activity not found")
                return@launch
            }

            when (activity) {
                is Activity.Lightning -> {
                    if (paymentHashOrTxId == activity.v1.id) {
                        coreService.activity.appendTags(
                            toActivityId = activity.v1.id,
                            tags = tags
                        ).onFailure {
                            Logger.error("Error attaching tags $tags")
                        }
                    } else {
                        Logger.error("Different activity id. Expected: $paymentHashOrTxId found: ${activity.v1.id}")
                    }
                }

                is Activity.Onchain -> {
                    if (paymentHashOrTxId == activity.v1.txId) {
                        coreService.activity.appendTags(
                            toActivityId = activity.v1.id,
                            tags = tags
                        )
                    } else {
                        Logger.error("Different txId. Expected: $paymentHashOrTxId found: ${activity.v1.txId}")
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

    private suspend fun sendLightning(
        bolt11: String,
        amount: ULong? = null,
    ): Result<PaymentId> {
        return try {
            val hash = lightningService.send(bolt11 = bolt11, amount)

            // Wait until matching payment event is received
            val result = ldkNodeEventBus.events.watchUntil { event ->
                when (event) {
                    is Event.PaymentSuccessful -> {
                        if (event.paymentHash == hash) {
                            WatchResult.Complete(Result.success(hash))
                        } else {
                            WatchResult.Continue()
                        }
                    }

                    is Event.PaymentFailed -> {
                        if (event.paymentHash == hash) {
                            val error = Exception(event.reason?.name ?: "Unknown payment failure reason")
                            WatchResult.Complete(Result.failure(error))
                        } else {
                            WatchResult.Continue()
                        }
                    }

                    else -> WatchResult.Continue()
                }
            }
            result
        } catch (e: Exception) {
            toast(
                type = Toast.ToastType.ERROR,
                title = "Error Sending",
                description = e.message ?: "Unknown error"
            )
            Result.failure(e)
        }
    }

    private fun getMinOnchainTx(): ULong {
        // TODO implement min tx size
        return 600uL
    }

    fun resetSendState() {
        _sendUiState.value = SendUiState()
        scan = null
    }
    // endregion

    // region TxSheet
    var isNewTransactionSheetEnabled = true
        private set

    var showNewTransaction by mutableStateOf(false)
        private set

    var newTransaction by mutableStateOf(
        NewTransactionSheetDetails(
            NewTransactionSheetType.LIGHTNING,
            NewTransactionSheetDirection.RECEIVED,
            0
        )
    )

    fun setNewTransactionSheetEnabled(enabled: Boolean) {
        isNewTransactionSheetEnabled = enabled
    }

    fun showNewTransactionSheet(details: NewTransactionSheetDetails) {
        if (!isNewTransactionSheetEnabled) {
            Logger.debug("NewTransactionSheet display blocked by isNewTransactionSheetEnabled=false")
            return
        }

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
        description: String? = null,
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
    val selectedTags: List<String> = listOf(),
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
    data object BackSpaceClick : SendEvent()

    data object SwipeToPay : SendEvent()
    data object SpeedAndFee : SendEvent()
    data object PaymentMethodSwitch : SendEvent()
}
// endregion
