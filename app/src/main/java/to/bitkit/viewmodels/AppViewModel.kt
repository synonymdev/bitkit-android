package to.bitkit.viewmodels

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlAuthData
import com.synonym.bitkitcore.LnurlChannelData
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData
import com.synonym.bitkitcore.OnChainInvoice
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.decode
import com.synonym.bitkitcore.validateBitcoinAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.resetPin
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.WatchResult
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.maxWithdrawableSat
import to.bitkit.ext.minSendableSat
import to.bitkit.ext.minWithdrawableSat
import to.bitkit.ext.rawId
import to.bitkit.ext.removeSpaces
import to.bitkit.ext.setClipboardText
import to.bitkit.ext.watchUntil
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Suggestion
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.toActivityFilter
import to.bitkit.models.toCoreNetworkType
import to.bitkit.models.toTxType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.screens.wallets.send.SendRoute
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject

private const val SEND_AMOUNT_WARNING_THRESHOLD = 100.0

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val lightningService: LightningRepo,
    private val walletRepo: WalletRepo,
    private val coreService: CoreService,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    private val activityRepo: ActivityRepo,
    connectivityRepo: ConnectivityRepo,
    healthRepo: HealthRepo,
) : ViewModel() {
    val healthState = healthRepo.healthState

    val isOnline = connectivityRepo.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectivityState.CONNECTED)

    var splashVisible by mutableStateOf(true)
        private set

    var isGeoBlocked by mutableStateOf<Boolean?>(null)
        private set

    private val _sendUiState = MutableStateFlow(SendUiState())
    val sendUiState = _sendUiState.asStateFlow()

    private val _quickPayData = MutableStateFlow<QuickPayData?>(null)
    val quickPayData = _quickPayData.asStateFlow()

    private val _sendEffect = MutableSharedFlow<SendEffect>(extraBufferCapacity = 1)
    val sendEffect = _sendEffect.asSharedFlow()
    private fun setSendEffect(effect: SendEffect) = viewModelScope.launch { _sendEffect.emit(effect) }

    private val _mainScreenEffect = MutableSharedFlow<MainScreenEffect>(extraBufferCapacity = 1)
    val mainScreenEffect = _mainScreenEffect.asSharedFlow()
    private fun mainScreenEffect(effect: MainScreenEffect) = viewModelScope.launch { _mainScreenEffect.emit(effect) }

    private val sendEvents = MutableSharedFlow<SendEvent>()
    fun setSendEvent(event: SendEvent) = viewModelScope.launch { sendEvents.emit(event) }

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated = _isAuthenticated.asStateFlow()

    private val _showForgotPinSheet = MutableStateFlow(false)
    val showForgotPinSheet = _showForgotPinSheet.asStateFlow()

    fun setShowForgotPin(value: Boolean) {
        _showForgotPinSheet.value = value
    }

    fun setIsAuthenticated(value: Boolean) {
        _isAuthenticated.value = value
    }

    val pinAttemptsRemaining = keychain.pinAttemptsRemaining()
        .map { attempts -> attempts ?: Env.PIN_ATTEMPTS }
        .stateIn(viewModelScope, SharingStarted.Lazily, Env.PIN_ATTEMPTS)

    fun addTagToSelected(newTag: String) {
        _sendUiState.update {
            it.copy(
                selectedTags = (it.selectedTags + newTag).distinct()
            )
        }
        viewModelScope.launch {
            settingsStore.addLastUsedTag(newTag)
        }
    }

    fun removeTag(tag: String) {
        _sendUiState.update {
            it.copy(
                selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }
            )
        }
    }

    init {
        viewModelScope.launch {
            ToastEventBus.events.collect {
                toast(it.type, it.title, it.description, it.autoHide, it.visibilityTime)
            }
        }
        viewModelScope.launch {
            // Delays are required for auth check on launch functionality
            delay(1000)
            resetIsAuthenticatedState()
            delay(500)
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
                            handleTags(event)
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
                            val channel = lightningService.getChannels()?.find { it.channelId == event.channelId }
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
                            // TODO: fee is not the sats sent. Need to get this amount from elsewhere like send flow or something.
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
                        is Event.PaymentForwarded -> Unit
                    }
                } catch (e: Exception) {
                    Logger.error("LDK event handler error", e)
                }
            }
        }
    }

    private suspend fun handleTags(event: Event.PaymentReceived) {
        val tags = walletRepo.searchInvoice(txId = event.paymentHash).getOrNull()?.tags.orEmpty()
        activityRepo.addTagsToTransaction(
            paymentHashOrTxId = event.paymentHash,
            type = ActivityFilter.LIGHTNING,
            txType = PaymentType.RECEIVED,
            tags = tags
        )
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
                    SendEvent.Paste -> onPasteClick()
                    SendEvent.Scan -> onScanClick()

                    is SendEvent.AddressChange -> onAddressChange(it.value)
                    SendEvent.AddressReset -> resetAddressInput()
                    is SendEvent.AddressContinue -> onAddressContinue(it.data)

                    is SendEvent.AmountChange -> onAmountChange(it.value)
                    SendEvent.AmountReset -> resetAmountInput()
                    is SendEvent.AmountContinue -> onAmountContinue(it.amount)
                    SendEvent.PaymentMethodSwitch -> onPaymentMethodSwitch()

                    is SendEvent.CoinSelectionContinue -> onCoinSelectionContinue(it.utxos)

                    is SendEvent.CommentChange -> onCommentChange(it.value)

                    SendEvent.SpeedAndFee -> toast(Exception("Coming soon: Speed and Fee"))
                    SendEvent.SwipeToPay -> onSwipeToPay()
                    SendEvent.ConfirmAmountWarning -> onConfirmAmountWarning()
                    SendEvent.DismissAmountWarning -> onDismissAmountWarning()
                    SendEvent.PayConfirmed -> onConfirmPay()
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
            val result = runCatching { decode(valueWithoutSpaces) }
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
            handleScan(data)
        }
    }

    private fun onAmountChange(value: String) {
        _sendUiState.update {
            it.copy(
                amountInput = value,
                isAmountInputValid = validateAmount(value)
            )
        }
    }

    private fun onCommentChange(comment: String) {
        // Apply maxLength from lnurlPay commentAllowed
        val maxLength = (_sendUiState.value.lnurl as? LnurlParams.LnurlPay)?.data?.commentAllowed ?: 0u
        val trimmed = comment.take(maxLength.toInt())
        _sendUiState.update {
            it.copy(comment = trimmed)
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

    private suspend fun onAmountContinue(amount: String) {
        _sendUiState.update {
            it.copy(
                amount = amount.toULongOrNull() ?: 0u,
            )
        }
        if (_sendUiState.value.payMethod != SendMethod.LIGHTNING && !settingsStore.data.first().coinSelectAuto) {
            setSendEffect(SendEffect.NavigateToCoinSelection)
            return
        }

        val lnurl = _sendUiState.value.lnurl
        if (lnurl is LnurlParams.LnurlWithdraw) {
            setSendEffect(SendEffect.NavigateToWithdrawConfirm)
            return
        }

        setSendEffect(SendEffect.NavigateToReview)
    }

    private fun onCoinSelectionContinue(utxos: List<SpendableUtxo>) {
        _sendUiState.update {
            it.copy(selectedUtxos = utxos)
        }
        setSendEffect(SendEffect.NavigateToReview)
    }

    private fun validateAmount(
        value: String,
        payMethod: SendMethod = _sendUiState.value.payMethod,
    ): Boolean {
        if (value.isBlank()) return false
        val amount = value.toULongOrNull() ?: return false

        val lnurl = _sendUiState.value.lnurl

        val isValidLNAmount = when (lnurl) {
            null -> lightningService.canSend(amount)
            is LnurlParams.LnurlPay -> {
                val minSat = lnurl.data.minSendableSat()
                val maxSat = lnurl.data.maxSendableSat()

                amount in minSat..maxSat && lightningService.canSend(amount)
            }

            is LnurlParams.LnurlWithdraw -> {
                amount < lnurl.data.maxWithdrawableSat()
            }
        }

        return when (payMethod) {
            SendMethod.ONCHAIN -> amount > getMinOnchainTx()
            else -> isValidLNAmount && amount > 0uL
        }
    }

    private fun onPasteClick() {
        val data = context.getClipboardText()?.trim()
        if (data.isNullOrBlank()) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.wallet__send_clipboard_empty_title),
                description = context.getString(R.string.wallet__send_clipboard_empty_text),
            )
            return
        }
        viewModelScope.launch {
            handleScan(data)
        }
    }

    private fun onScanClick() {
        setSendEffect(SendEffect.NavigateToScan)
    }

    fun onScanResult(data: String, delayMs: Long = 0) {
        viewModelScope.launch {
            delay(delayMs)
            handleScan(data)
        }
    }

    private suspend fun handleScan(result: String) {
        val scan = runCatching { decode(result) }
            .onFailure { Logger.error("Failed to decode scan result: '$result'", it) }
            .onSuccess { Logger.info("Handling scan data: $it") }
            .getOrNull()

        // always reset state on new scan
        resetSendState()
        resetQuickPayData()

        when (scan) {
            is Scanner.OnChain -> onScanOnchain(scan.invoice)
            is Scanner.Lightning -> onScanLightning(scan.invoice)
            is Scanner.LnurlPay -> onScanLnurlPay(scan.data)
            is Scanner.LnurlWithdraw -> onScanLnurlWithdraw(scan.data)
            is Scanner.LnurlAuth -> onScanLnurlAuth(scan.data, result)
            is Scanner.LnurlChannel -> onScanLnurlChannel(scan.data)
            is Scanner.NodeId -> onScanNodeId(scan)
            else -> {
                Logger.warn("Unhandled scan data: $scan")
                toast(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.other__scan_err_decoding),
                    description = context.getString(R.string.other__scan_err_interpret_title),
                )
            }
        }
    }

    private suspend fun onScanOnchain(invoice: OnChainInvoice) {
        val lnInvoice: LightningInvoice? = invoice.params?.get("lightning")?.let { bolt11 ->
            val decoded = runCatching { decode(bolt11) }.getOrNull()
            (decoded as? Scanner.Lightning)?.invoice
        }
        _sendUiState.update {
            it.copy(
                address = invoice.address,
                amount = invoice.amountSatoshis,
                isUnified = invoice.params?.containsKey("lightning") == true,
                decodedInvoice = lnInvoice,
                payMethod = lnInvoice?.let { SendMethod.LIGHTNING } ?: SendMethod.ONCHAIN,
            )
        }
        val isLnInvoiceWithAmount = lnInvoice?.amountSatoshis?.takeIf { it > 0uL } != null
        if (isLnInvoiceWithAmount) {
            Logger.info("Found amount in unified invoice, checking QuickPay conditions")

            val quickPayHandled = handleQuickPayIfApplicable(
                amountSats = lnInvoice.amountSatoshis,
                invoice = lnInvoice,
            )
            if (quickPayHandled) return

            if (isMainScanner) {
                showSheet(BottomSheetType.Send(SendRoute.ReviewAndSend))
            } else {
                setSendEffect(SendEffect.NavigateToReview)
            }
            return
        }
        Logger.info("No amount found in invoice, proceeding to enter amount manually")
        resetAmountInput()

        if (isMainScanner) {
            showSheet(BottomSheetType.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private suspend fun onScanLightning(invoice: LightningInvoice) {
        if (invoice.isExpired) {
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__scan_err_decoding),
                description = context.getString(R.string.other__scan__error__expired),
            )
            return
        }

        val quickPayHandled = handleQuickPayIfApplicable(amountSats = invoice.amountSatoshis, invoice = invoice)
        if (quickPayHandled) return

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
            return
        }
        Logger.info("No amount found in invoice, proceeding to enter amount manually")
        resetAmountInput()

        if (isMainScanner) {
            showSheet(BottomSheetType.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private suspend fun onScanLnurlPay(data: LnurlPayData) {
        Logger.debug("LNURL: $data")

        val minSendable = data.minSendableSat()
        val maxSendable = data.maxSendableSat()

        if (!lightningService.canSend(minSendable)) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__lnurl_pay_error),
                description = context.getString(R.string.other__lnurl_pay_error_no_capacity),
            )
            resetSendState()
            return
        }

        _sendUiState.update {
            it.copy(
                amount = minSendable,
                payMethod = SendMethod.LIGHTNING,
                lnurl = LnurlParams.LnurlPay(data),
            )
        }

        val hasAmount = minSendable == maxSendable && minSendable > 0u
        if (hasAmount) {
            Logger.info("Found amount $$minSendable in lnurlPay, proceeding with payment")

            val quickPayHandled = handleQuickPayIfApplicable(amountSats = minSendable, lnurlPay = data)
            if (quickPayHandled) return

            if (isMainScanner) {
                showSheet(BottomSheetType.Send(SendRoute.ReviewAndSend))
            } else {
                setSendEffect(SendEffect.NavigateToReview)
            }
            return
        }

        Logger.info("No amount found in lnurlPay, proceeding to enter amount manually")
        if (isMainScanner) {
            showSheet(BottomSheetType.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private fun onScanLnurlWithdraw(data: LnurlWithdrawData) {
        Logger.debug("LNURL: $data")

        val minWithdrawable = data.minWithdrawableSat()
        val maxWithdrawable = data.maxWithdrawableSat()

        if (minWithdrawable > maxWithdrawable) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__lnurl_withdr_error),
                description = context.getString(R.string.other__lnurl_withdr_error_minmax)
            )
            resetSendState()
            return
        }

        _sendUiState.update {
            it.copy(
                payMethod = SendMethod.LIGHTNING,
                amount = minWithdrawable,
                lnurl = LnurlParams.LnurlWithdraw(data = data)
            )
        }

        if (minWithdrawable == maxWithdrawable) {
            setSendEffect(SendEffect.NavigateToWithdrawConfirm)
            return
        }

        if (isMainScanner) {
            showSheet(BottomSheetType.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private fun onScanLnurlAuth(data: LnurlAuthData, lnurl: String) {
        Logger.debug("LNURL: $data")

        val domain = runCatching { data.uri.toUri().host }.getOrDefault(data.uri).orEmpty().trim()

        showSheet(BottomSheetType.LnurlAuth(domain = domain, lnurl = lnurl, k1 = data.k1))
    }

    fun requestLnurlAuth(callback: String, k1: String, domain: String) {
        viewModelScope.launch {
            // TODO pass callback and domain from bitkit-core when updated to accept decoded callback and return domain
            lightningService.requestLnurlAuth(
                callback = callback,
                k1 = k1,
                domain = domain,
            ).onFailure {
                toast(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.other__lnurl_auth_error),
                    description = context.getString(R.string.other__lnurl_auth_error_msg)
                        .replace("{raw}", it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName),
                )
            }.onSuccess {
                toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.other__lnurl_auth_success_title),
                    description = when (domain.isNotBlank()) {
                        true -> context.getString(R.string.other__lnurl_auth_success_msg_domain)
                            .replace("{domain}", domain)

                        else -> context.getString(R.string.other__lnurl_auth_success_msg_no_domain)
                    },
                )
            }
            hideSheet()
        }
    }

    private fun onScanLnurlChannel(data: LnurlChannelData) {
        Logger.debug("LNURL: $data")
        hideSheet() // hide scan sheet if opened
        mainScreenEffect(
            MainScreenEffect.Navigate(
                Routes.LnurlChannel(uri = data.uri, callback = data.callback, k1 = data.k1)
            )
        )
    }

    private fun onScanNodeId(data: Scanner.NodeId) {
        val (url, network) = data
        val appNetwork = Env.network.toCoreNetworkType()
        if (network != appNetwork) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__qr_error_network_header),
                description = context.getString(R.string.other__qr_error_network_text)
                    .replace("{selectedNetwork}", appNetwork.name)
                    .replace("{dataNetwork}", network.name),
            )
            return
        }
        hideSheet() // hide scan sheet if opened
        val nextRoute = Routes.ExternalConnection(url)
        mainScreenEffect(MainScreenEffect.Navigate(nextRoute))
    }

    private suspend fun handleQuickPayIfApplicable(
        amountSats: ULong,
        lnurlPay: LnurlPayData? = null,
        invoice: LightningInvoice? = null,
    ): Boolean {
        val settings = settingsStore.data.first()
        if (!settings.isQuickPayEnabled || amountSats == 0uL) {
            return false
        }

        val quickPayAmountSats = currencyRepo.convertFiatToSats(settings.quickPayAmount.toDouble(), "USD").getOrNull()
            ?: return false

        if (amountSats <= quickPayAmountSats) {
            Logger.info("Using QuickPay: $amountSats sats <= $quickPayAmountSats sats threshold")

            val quickPayData: QuickPayData = when {
                lnurlPay != null -> {
                    QuickPayData.LnurlPay(sats = amountSats, callback = lnurlPay.callback)
                }

                else -> {
                    val decodedInvoice = requireNotNull(invoice)
                    QuickPayData.Bolt11(sats = amountSats, bolt11 = decodedInvoice.bolt11)
                }
            }

            _quickPayData.update { quickPayData }

            Logger.debug("QuickPayData: $quickPayData")

            if (isMainScanner) {
                showSheet(BottomSheetType.Send(SendRoute.QuickPay))
            } else {
                setSendEffect(SendEffect.NavigateToQuickPay)
            }
            return true
        }

        return false
    }

    private fun resetAmountInput() {
        _sendUiState.update { state ->
            state.copy(
                amountInput = state.amount.toString(),
                isAmountInputValid = validateAmount(state.amount.toString()),
            )
        }
    }

    private fun onSwipeToPay() {
        Logger.debug("Swipe to pay event, checking send confirmation conditions")
        viewModelScope.launch {
            val amount = _sendUiState.value.amount

            handleSanityChecks(amount)
            if (_sendUiState.value.showAmountWarningDialog != null) return@launch // await for dialog UI interaction

            _sendUiState.update { it.copy(shouldConfirmPay = true) }
        }
    }

    private suspend fun handleSanityChecks(amountSats: ULong) {
        if (_sendUiState.value.showAmountWarningDialog != null) return

        val settings = settingsStore.data.first()
        val amountInUsd = currencyRepo.convertSatsToFiat(amountSats.toLong(), "USD").getOrNull() ?: return
        if (amountInUsd.value > BigDecimal(SEND_AMOUNT_WARNING_THRESHOLD) && settings.enableSendAmountWarning) {
            _sendUiState.update {
                it.copy(showAmountWarningDialog = AmountWarning.VALUE_OVER_100_USD)
            }
            return
        }

        if (amountSats > BigDecimal.valueOf(walletRepo.balanceState.value.totalSats.toLong())
                .times(BigDecimal(0.5)).toLong().toUInt()
        ) {
            _sendUiState.update {
                it.copy(showAmountWarningDialog = AmountWarning.OVER_HALF_BALANCE)
            }
            return
        }

        if (_sendUiState.value.payMethod != SendMethod.ONCHAIN) return

        val totalFee = lightningService.calculateTotalFee(
            amountSats = amountSats,
            address = _sendUiState.value.address,
            speed = _sendUiState.value.speed,
            utxosToSpend = _sendUiState.value.utxosToSpend
        ).getOrNull() ?: return

        if (totalFee > BigDecimal.valueOf(amountSats.toLong())
                .times(BigDecimal(0.5)).toLong().toUInt()
        ) {
            _sendUiState.update {
                it.copy(showAmountWarningDialog = AmountWarning.FEE_OVER_HALF_VALUE)
            }
            return
        }

        val feeInUsd = currencyRepo.convertSatsToFiat(amountSats.toLong(), "USD").getOrNull() ?: return
        if (feeInUsd.value > BigDecimal(10)) {
            _sendUiState.update {
                it.copy(showAmountWarningDialog = AmountWarning.FEE_OVER_10_USD)
            }
            return
        }

        _sendUiState.update {
            it.copy(showAmountWarningDialog = null)
        }
    }

    private suspend fun proceedWithPayment() {
        delay(300) // wait for screen transitions when applicable

        val amount = _sendUiState.value.amount

        val lnurl = _sendUiState.value.lnurl
        val isLnurlPay = lnurl is LnurlParams.LnurlPay

        if (isLnurlPay) {
            lightningService.fetchLnurlInvoice(
                callbackUrl = lnurl.data.callback,
                amountSats = amount,
                comment = _sendUiState.value.comment.takeIf { it.isNotEmpty() },
            ).onSuccess { invoice ->
                _sendUiState.update {
                    it.copy(decodedInvoice = invoice)
                }
            }.onFailure {
                toast(Exception("Error fetching lnurl invoice"))
                hideSheet()
                return
            }
        }

        when (_sendUiState.value.payMethod) {
            SendMethod.ONCHAIN -> {
                val address = _sendUiState.value.address
                val validatedAddress = runCatching { validateBitcoinAddress(address) }
                    .getOrElse { e ->
                        Logger.error("Invalid bitcoin send address: '$address'", e)
                        toast(Exception("Invalid bitcoin send address"))
                        hideSheet()
                        return
                    }

                sendOnchain(validatedAddress.address, amount)
                    .onSuccess { txId ->
                        val tags = _sendUiState.value.selectedTags
                        activityRepo.addTagsToTransaction(
                            paymentHashOrTxId = txId,
                            type = ActivityFilter.ONCHAIN,
                            txType = PaymentType.SENT,
                            tags = tags
                        )
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
                    }.onFailure { e ->
                        Logger.error(msg = "Error sending onchain payment", e = e)
                        toast(
                            type = Toast.ToastType.ERROR,
                            title = "Error Sending",
                            description = e.message ?: "Unknown error"
                        )
                        hideSheet()
                    }
            }

            SendMethod.LIGHTNING -> {
                val decodedInvoice = requireNotNull(_sendUiState.value.decodedInvoice)
                val bolt11 = decodedInvoice.bolt11

                // Determine if we should override amount
                val paymentAmount = decodedInvoice.amountSatoshis.takeIf { it > 0uL } ?: amount

                sendLightning(bolt11, paymentAmount).onSuccess { paymentHash ->
                    Logger.info("Lightning send result payment hash: $paymentHash")
                    val tags = _sendUiState.value.selectedTags
                    activityRepo.addTagsToTransaction(
                        paymentHashOrTxId = paymentHash,
                        type = ActivityFilter.LIGHTNING,
                        txType = PaymentType.SENT,
                        tags = tags
                    )
                    setSendEffect(SendEffect.PaymentSuccess())
                }.onFailure { e ->
                    Logger.error("Error sending lightning payment", e)
                    toast(e)
                    hideSheet()
                }
            }
        }
    }

    fun onConfirmWithdraw() {
        _sendUiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val lnurl = _sendUiState.value.lnurl as? LnurlParams.LnurlWithdraw

            if (lnurl == null) {
                resetSendState()
                setSendEffect(SendEffect.NavigateToWithdrawError)
                return@launch
            }

            _sendUiState.update {
                it.copy(
                    amount = it.amount.coerceAtLeast(
                        (lnurl.data.minWithdrawable ?: 0u) / 1000u
                    )
                )
            }

            val invoice = lightningService.createInvoice(
                amountSats = _sendUiState.value.amount,
                description = lnurl.data.defaultDescription,
                expirySeconds = 3600u,
            ).getOrNull()

            if (invoice == null) {
                resetSendState()
                setSendEffect(SendEffect.NavigateToWithdrawError)
                return@launch
            }

            lightningService.requestLnurlWithdraw(
                k1 = lnurl.data.k1,
                callback = lnurl.data.callback,
                paymentRequest = invoice
            ).onSuccess {
                toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.other__lnurl_withdr_success_title),
                    description = context.getString(R.string.other__lnurl_withdr_success_msg),
                )
                hideSheet()
                _sendUiState.update { it.copy(isLoading = false) }
                mainScreenEffect(MainScreenEffect.Navigate(Routes.Home))
                resetSendState()
            }.onFailure {
                _sendUiState.update { it.copy(isLoading = false) }
                setSendEffect(SendEffect.NavigateToWithdrawError)
            }
        }
    }

    fun onClickActivityDetail() {
        val filter = newTransaction.type.toActivityFilter()
        val paymentType = newTransaction.direction.toTxType()

        viewModelScope.launch(bgDispatcher) {
            val activity = coreService.activity.get(filter = filter, txType = paymentType, limit = 1u).firstOrNull()

            if (activity == null) {
                Logger.error(msg = "Activity not found")
                return@launch
            }

            val nextRoute = Routes.ActivityDetail(activity.rawId())
            mainScreenEffect(MainScreenEffect.Navigate(nextRoute))
        }
    }

    private suspend fun sendOnchain(address: String, amount: ULong): Result<Txid> {
        val utxos = _sendUiState.value.selectedUtxos
        return lightningService.sendOnChain(
            address = address,
            sats = amount,
            utxosToSpend = utxos,
        )
    }

    private suspend fun sendLightning(
        bolt11: String,
        amount: ULong? = null,
    ): Result<PaymentId> {
        return lightningService.payInvoice(bolt11 = bolt11, sats = amount).onSuccess { hash ->
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
            return result
        }
    }

    private fun getMinOnchainTx(): ULong {
        return Env.TransactionDefaults.dustLimit.toULong()
    }

    fun clearClipboardForAutoRead() {
        viewModelScope.launch {
            val isAutoReadClipboardEnabled = settingsStore.data.first().enableAutoReadClipboard
            if (isAutoReadClipboardEnabled) {
                context.setClipboardText("")
            }
        }
    }

    fun resetQuickPayData() = _quickPayData.update { null }

    fun resetSendState() {
        _sendUiState.value = SendUiState()
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

    // region security
    fun resetIsAuthenticatedState() {
        viewModelScope.launch {
            val settings = settingsStore.data.first()
            val needsAuth = settings.isPinEnabled && settings.isPinOnLaunchEnabled
            if (!needsAuth) {
                _isAuthenticated.value = true
            }
        }
    }

    fun validatePin(pin: String): Boolean {
        val storedPin = keychain.loadString(Keychain.Key.PIN.name)
        val isValid = storedPin == pin

        if (isValid) {
            viewModelScope.launch {
                keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, Env.PIN_ATTEMPTS.toString())
            }
            return true
        }

        viewModelScope.launch(bgDispatcher) {
            val newAttempts = pinAttemptsRemaining.value - 1
            keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, newAttempts.toString())

            if (newAttempts <= 0) {
                toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.security__wiped_title),
                    description = context.getString(R.string.security__wiped_message),
                )
                delay(250) // small delay for UI feedback
                mainScreenEffect(MainScreenEffect.WipeWallet)
            }
        }
        return false
    }

    fun addPin(pin: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(isPinOnLaunchEnabled = true) }
            settingsStore.addDismissedSuggestion(Suggestion.SECURE)
        }
        editPin(pin)
    }

    fun editPin(newPin: String) {
        viewModelScope.launch(bgDispatcher) {
            settingsStore.update { it.copy(isPinEnabled = true) }
            keychain.upsertString(Keychain.Key.PIN.name, newPin)
            keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, Env.PIN_ATTEMPTS.toString())
        }
    }

    fun removePin() {
        viewModelScope.launch(bgDispatcher) {
            settingsStore.update {
                it.resetPin()
            }
            keychain.delete(Keychain.Key.PIN.name)
            keychain.upsertString(Keychain.Key.PIN_ATTEMPTS_REMAINING.name, Env.PIN_ATTEMPTS.toString())
        }
    }
    // endregion

    fun onClipboardAutoRead(data: String) {
        viewModelScope.launch {
            mainScreenEffect(MainScreenEffect.ProcessClipboardAutoRead(data))
        }
    }

    private fun onConfirmAmountWarning() {
        viewModelScope.launch {
            _sendUiState.update {
                it.copy(
                    showAmountWarningDialog = null,
                    shouldConfirmPay = true,
                )
            }
        }
    }

    private fun onDismissAmountWarning() {
        _sendUiState.update {
            it.copy(showAmountWarningDialog = null)
        }
    }

    private fun onConfirmPay() {
        Logger.debug("Payment checks confirmed, proceeding…")
        viewModelScope.launch {
            _sendUiState.update { it.copy(shouldConfirmPay = false) }
            proceedWithPayment()
        }
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
    val isUnified: Boolean = false,
    val payMethod: SendMethod = SendMethod.ONCHAIN,
    val selectedTags: List<String> = listOf(),
    val decodedInvoice: LightningInvoice? = null,
    val showAmountWarningDialog: AmountWarning? = null,
    val shouldConfirmPay: Boolean = false,
    val selectedUtxos: List<SpendableUtxo>? = null,
    val lnurl: LnurlParams? = null,
    val isLoading: Boolean = false,
    val speed: TransactionSpeed? = null,
    val utxosToSpend: List<SpendableUtxo>? = null,
    val comment: String = "",
)

enum class AmountWarning(@StringRes val message: Int) {
    VALUE_OVER_100_USD(R.string.wallet__send_dialog1),
    OVER_HALF_BALANCE(R.string.wallet__send_dialog2),
    FEE_OVER_HALF_VALUE(R.string.wallet__send_dialog3),
    FEE_OVER_10_USD(R.string.wallet__send_dialog4),
}

enum class SendMethod { ONCHAIN, LIGHTNING }

sealed class SendEffect {
    data object NavigateToAddress : SendEffect()
    data object NavigateToAmount : SendEffect()
    data object NavigateToScan : SendEffect()
    data object NavigateToReview : SendEffect()
    data object NavigateToWithdrawConfirm : SendEffect()
    data object NavigateToWithdrawError : SendEffect()
    data object NavigateToCoinSelection : SendEffect()
    data object NavigateToQuickPay : SendEffect()
    data class PaymentSuccess(val sheet: NewTransactionSheetDetails? = null) : SendEffect()
}

sealed class MainScreenEffect {
    data class Navigate(val route: Routes) : MainScreenEffect()
    data object WipeWallet : MainScreenEffect()
    data class ProcessClipboardAutoRead(val data: String) : MainScreenEffect()
}

sealed class SendEvent {
    data object EnterManually : SendEvent()
    data object Paste : SendEvent()
    data object Scan : SendEvent()

    data object AddressReset : SendEvent()
    data class AddressChange(val value: String) : SendEvent()
    data class AddressContinue(val data: String) : SendEvent()

    data object AmountReset : SendEvent()
    data class AmountContinue(val amount: String) : SendEvent()
    data class AmountChange(val value: String) : SendEvent()

    data class CoinSelectionContinue(val utxos: List<SpendableUtxo>) : SendEvent()

    data class CommentChange(val value: String) : SendEvent()

    data object SwipeToPay : SendEvent()
    data object SpeedAndFee : SendEvent()
    data object PaymentMethodSwitch : SendEvent()
    data object ConfirmAmountWarning : SendEvent()
    data object DismissAmountWarning : SendEvent()
    data object PayConfirmed : SendEvent()
}

sealed interface LnurlParams {
    data class LnurlPay(val data: LnurlPayData) : LnurlParams
    data class LnurlWithdraw(val data: LnurlWithdrawData) : LnurlParams
}

sealed interface QuickPayData {
    val sats: ULong

    data class Bolt11(override val sats: ULong, val bolt11: String) : QuickPayData
    data class LnurlPay(override val sats: ULong, val callback: String) : QuickPayData
}
// endregion
