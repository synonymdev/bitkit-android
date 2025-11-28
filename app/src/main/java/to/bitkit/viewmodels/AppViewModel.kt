package to.bitkit.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavOptions
import androidx.navigation.navOptions
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.FeeRates
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.SpendableUtxo
import org.lightningdevkit.ldknode.Txid
import to.bitkit.BuildConfig
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.resetPin
import to.bitkit.di.BgDispatcher
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.env.Env
import to.bitkit.ext.WatchResult
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.maxWithdrawableSat
import to.bitkit.ext.minSendableSat
import to.bitkit.ext.minWithdrawableSat
import to.bitkit.ext.rawId
import to.bitkit.ext.removeSpaces
import to.bitkit.ext.setClipboardText
import to.bitkit.ext.toHex
import to.bitkit.ext.totalValue
import to.bitkit.ext.watchUntil
import to.bitkit.models.FeeRate
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Suggestion
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.toActivityFilter
import to.bitkit.models.toTxType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.AppUpdaterService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.Routes
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.theme.TRANSITION_SCREEN_MS
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject

@Suppress("LongParameterList")
@HiltViewModel
class AppViewModel @Inject constructor(
    connectivityRepo: ConnectivityRepo,
    healthRepo: HealthRepo,
    @param:ApplicationContext private val context: Context,
    @param:BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val backupRepo: BackupRepo,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    private val activityRepo: ActivityRepo,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val blocktankRepo: BlocktankRepo,
    private val appUpdaterService: AppUpdaterService,
    private val notifyPaymentReceivedHandler: NotifyPaymentReceivedHandler,
    private val cacheStore: CacheStore,
) : ViewModel() {
    val healthState = healthRepo.healthState

    val isOnline = connectivityRepo.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectivityState.CONNECTED)

    var splashVisible by mutableStateOf(true)
        private set

    val isGeoBlocked = lightningRepo.lightningState.map { it.isGeoBlocked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    private val processedPayments = mutableSetOf<String>()

    private var timedSheetsScope: CoroutineScope? = null
    private var timedSheetQueue: List<TimedSheetType> = emptyList()
    private var currentTimedSheet: TimedSheetType? = null

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
        viewModelScope.launch {
            lightningRepo.updateGeoBlockState()
        }

        observeLdkNodeEvents()
        observeSendEvents()

        viewModelScope.launch {
            walletRepo.balanceState.collect {
                checkTimedSheets()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun observeLdkNodeEvents() {
        viewModelScope.launch {
            ldkNodeEventBus.events.collect { event ->
                if (!walletRepo.walletExists()) return@collect

                runCatching {
                    when (event) {
                        is Event.BalanceChanged -> handleBalanceChanged()
                        is Event.SyncCompleted -> handleSyncCompleted()
                        is Event.ChannelClosed -> Unit
                        is Event.ChannelPending -> Unit
                        is Event.ChannelReady -> notifyChannelReady(event)
                        is Event.OnchainTransactionConfirmed -> handleOnchainTransactionConfirmed(event)
                        is Event.OnchainTransactionEvicted -> handleOnchainTransactionEvicted(event)
                        is Event.OnchainTransactionReceived -> handleOnchainTransactionReceived(event)
                        is Event.OnchainTransactionReorged -> handleOnchainTransactionReorged(event)
                        is Event.OnchainTransactionReplaced -> handleOnchainTransactionReplaced(event)
                        is Event.PaymentClaimable -> Unit
                        is Event.PaymentFailed -> handlePaymentFailed(event)
                        is Event.PaymentForwarded -> Unit
                        is Event.PaymentReceived -> handlePaymentReceived(event)
                        is Event.PaymentSuccessful -> handlePaymentSuccessful(event)
                        is Event.SyncProgress -> Unit
                    }
                }.onFailure { e ->
                    Logger.error("LDK event handler error", e, context = TAG)
                }
            }
        }
    }

    private fun handleBalanceChanged() {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.syncBalances()
        }
    }

    private fun handleSyncCompleted() {
        viewModelScope.launch(bgDispatcher) {
            walletRepo.syncNodeAndWallet()
        }
    }

    private fun handleOnchainTransactionConfirmed(event: Event.OnchainTransactionConfirmed) {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.handleOnchainTransactionConfirmed(event.txid, event.details)
        }
    }

    private fun handleOnchainTransactionEvicted(event: Event.OnchainTransactionEvicted) {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.handleOnchainTransactionEvicted(event.txid)
        }
        notifyTransactionRemoved(event)
    }

    private fun handleOnchainTransactionReceived(event: Event.OnchainTransactionReceived) {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.handleOnchainTransactionReceived(event.txid, event.details)
        }
        if (event.details.amountSats > 0) {
            val sats = event.details.amountSats.toULong()
            viewModelScope.launch {
                delay(DELAY_FOR_ACTIVITY_SYNC_MS)
                val shouldShow = activityRepo.shouldShowReceivedSheet(event.txid, sats)
                if (shouldShow) {
                    notifyPaymentReceived(event)
                }
            }
        }
    }

    private fun handleOnchainTransactionReorged(event: Event.OnchainTransactionReorged) {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.handleOnchainTransactionReorged(event.txid)
        }
        notifyTransactionUnconfirmed()
    }

    private fun handleOnchainTransactionReplaced(event: Event.OnchainTransactionReplaced) {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.handleOnchainTransactionReplaced(event.txid, event.conflicts)
        }
        notifyTransactionReplaced(event)
    }

    private fun handlePaymentFailed(event: Event.PaymentFailed) {
        event.paymentHash?.let { paymentHash ->
            viewModelScope.launch(bgDispatcher) {
                activityRepo.handlePaymentEvent(paymentHash)
            }
        }
        notifyPaymentFailed()
    }

    private fun handlePaymentReceived(event: Event.PaymentReceived) {
        event.paymentHash?.let { paymentHash ->
            viewModelScope.launch(bgDispatcher) {
                activityRepo.handlePaymentEvent(paymentHash)
            }
        }
        notifyPaymentReceived(event)
    }

    private fun handlePaymentSuccessful(event: Event.PaymentSuccessful) {
        event.paymentHash?.let { paymentHash ->
            viewModelScope.launch(bgDispatcher) {
                activityRepo.handlePaymentEvent(paymentHash)
            }
        }
        viewModelScope.launch {
            notifyPaymentSentOnLightning(event)
        }
    }

    private fun notifyPaymentFailed() = toast(
        type = Toast.ToastType.ERROR,
        title = context.getString(R.string.wallet__toast_payment_failed_title),
        description = context.getString(R.string.wallet__toast_payment_failed_description),
        testTag = "PaymentFailedToast",
    )

    private fun notifyTransactionUnconfirmed() = toast(
        type = Toast.ToastType.WARNING,
        title = context.getString(R.string.wallet__toast_transaction_unconfirmed_title),
        description = context.getString(R.string.wallet__toast_transaction_unconfirmed_description),
        testTag = "TransactionUnconfirmedToast",
    )

    private fun notifyTransactionRemoved(event: Event.OnchainTransactionEvicted) = viewModelScope.launch {
        if (!activityRepo.wasTransactionReplaced(event.txid)) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.wallet__toast_transaction_removed_title),
                description = context.getString(R.string.wallet__toast_transaction_removed_description),
                testTag = "TransactionRemovedToast",
            )
        }
    }

    private fun notifyTransactionReplaced(event: Event.OnchainTransactionReplaced) = viewModelScope.launch {
        if (activityRepo.isReceivedTransaction(event.txid)) {
            toast(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.wallet__toast_received_transaction_replaced_title),
                description = context.getString(R.string.wallet__toast_received_transaction_replaced_description),
                testTag = "ReceivedTransactionReplacedToast",
            )
        } else {
            toast(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.wallet__toast_transaction_replaced_title),
                description = context.getString(R.string.wallet__toast_transaction_replaced_description),
                testTag = "TransactionReplacedToast",
            )
        }
    }

    private suspend fun notifyPaymentSentOnLightning(event: Event.PaymentSuccessful): Result<Activity> {
        val paymentHash = event.paymentHash
        // TODO Temporary solution while LDK node doesn't return the sent value in the event
        return activityRepo.findActivityByPaymentId(
            paymentHashOrTxId = paymentHash,
            type = ActivityFilter.LIGHTNING,
            txType = PaymentType.SENT,
            retry = true
        ).onSuccess { activity ->
            handlePaymentSuccess(
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.LIGHTNING,
                    direction = NewTransactionSheetDirection.SENT,
                    paymentHashOrTxId = event.paymentHash,
                    sats = activity.totalValue().toLong(),
                ),
            )
        }.onFailure { e ->
            Logger.warn("Failed displaying sheet for event: $event", e)
        }
    }

    private suspend fun notifyChannelReady(event: Event.ChannelReady): Any {
        val channel = lightningRepo.getChannels()?.find { it.channelId == event.channelId }
        val cjitEntry = channel?.let { blocktankRepo.getCjitEntry(it) }
        return if (cjitEntry != null) {
            val amount = channel.amountOnClose.toLong()
            showNewTransactionSheet(
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.LIGHTNING,
                    direction = NewTransactionSheetDirection.RECEIVED,
                    sats = amount,
                ),
            )
            activityRepo.insertActivityFromCjit(cjitEntry = cjitEntry, channel = channel)
        } else {
            toast(
                type = Toast.ToastType.LIGHTNING,
                title = context.getString(R.string.lightning__channel_opened_title),
                description = context.getString(R.string.lightning__channel_opened_msg),
            )
        }
    }

    private fun notifyPaymentReceived(event: Event) {
        val command = NotifyPaymentReceived.Command.from(event) ?: return

        viewModelScope.launch(bgDispatcher) {
            // Skip lightning payment events replayed by ldk-node on startup
            if (command is NotifyPaymentReceived.Command.Lightning) {
                val cachedId = cacheStore.data.first().lastLightningPaymentId
                if (command.paymentHashOrTxId == cachedId) {
                    Logger.debug("Skipping replayed Lightning payment: ${command.paymentHashOrTxId}", context = TAG)
                    return@launch
                }
                cacheStore.setLastLightningPayment(command.paymentHashOrTxId)
            }

            notifyPaymentReceivedHandler(command).onSuccess { result ->
                if (result is NotifyPaymentReceived.Result.ShowSheet) {
                    showNewTransactionSheet(result.details)
                }
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

                    is SendEvent.AmountChange -> onAmountChange(it.amount)
                    SendEvent.AmountReset -> resetAmountInput()
                    SendEvent.AmountContinue -> onAmountContinue()
                    SendEvent.PaymentMethodSwitch -> onPaymentMethodSwitch()

                    is SendEvent.CoinSelectionContinue -> onCoinSelectionContinue(it.utxos)

                    is SendEvent.CommentChange -> onCommentChange(it.value)

                    SendEvent.SpeedAndFee -> setSendEffect(SendEffect.NavigateToFee)
                    SendEvent.SwipeToPay -> onSwipeToPay()
                    is SendEvent.ConfirmAmountWarning -> onConfirmAmountWarning(it.warning)
                    SendEvent.DismissAmountWarning -> onDismissAmountWarning()
                    SendEvent.PayConfirmed -> onConfirmPay()
                    SendEvent.ClearPayConfirmation -> _sendUiState.update { it.copy(shouldConfirmPay = false) }
                    SendEvent.BackToAmount -> setSendEffect(SendEffect.PopBack(SendRoute.Amount))
                    SendEvent.NavToAddress -> setSendEffect(SendEffect.NavigateToAddress)
                }
            }
        }
    }

    private val isMainScanner get() = currentSheet.value !is Sheet.Send

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

    private suspend fun onAmountChange(amount: ULong) {
        _sendUiState.update {
            it.copy(
                amount = amount,
                isAmountInputValid = validateAmount(amount),
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

    fun onSelectSpeed(speed: TransactionSpeed) {
        if (speed is TransactionSpeed.Custom && speed.satsPerVByte == 0u) {
            setSendEffect(SendEffect.NavigateToFeeCustom)
        } else {
            setTransactionSpeed(speed)
        }
    }

    fun setTransactionSpeed(speed: TransactionSpeed) {
        viewModelScope.launch {
            val state = _sendUiState.value
            val shouldResetUtxos = when (settingsStore.data.first().coinSelectAuto) {
                true -> {
                    val currentSatsPerVByte = state.feeRates?.getSatsPerVByteFor(state.speed)
                    val newSatsPerVByte = state.feeRates?.getSatsPerVByteFor(speed)
                    currentSatsPerVByte != newSatsPerVByte
                }

                else -> false
            }
            val fee = when (speed is TransactionSpeed.Custom) {
                true -> getFeeEstimate(speed)
                else -> state.fees.getOrDefault(FeeRate.fromSpeed(speed), 0)
            }
            _sendUiState.update {
                it.copy(
                    speed = speed,
                    fee = SendFee.OnChain(fee),
                    selectedUtxos = if (shouldResetUtxos) null else it.selectedUtxos,
                )
            }
            refreshOnchainSendIfNeeded()
            setSendEffect(SendEffect.PopBack(SendRoute.Confirm))
        }
    }

    private suspend fun onPaymentMethodSwitch() {
        val nextPaymentMethod = when (_sendUiState.value.payMethod) {
            SendMethod.ONCHAIN -> SendMethod.LIGHTNING
            SendMethod.LIGHTNING -> SendMethod.ONCHAIN
        }
        _sendUiState.update {
            it.copy(
                payMethod = nextPaymentMethod,
                isAmountInputValid = validateAmount(it.amount, nextPaymentMethod),
            )
        }
    }

    private suspend fun onAmountContinue() {
        _sendUiState.update {
            it.copy(
                selectedUtxos = null,
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

        _sendUiState.update { it.copy(isLoading = true) }
        refreshOnchainSendIfNeeded()
        estimateLightningRoutingFeesIfNeeded()
        _sendUiState.update { it.copy(isLoading = false) }

        setSendEffect(SendEffect.NavigateToConfirm)
    }

    private suspend fun onCoinSelectionContinue(utxos: List<SpendableUtxo>) {
        _sendUiState.update {
            it.copy(selectedUtxos = utxos)
        }
        refreshFeeEstimates()
        setSendEffect(SendEffect.NavigateToConfirm)
    }

    private suspend fun validateAmount(
        amount: ULong,
        payMethod: SendMethod = _sendUiState.value.payMethod,
    ): Boolean {
        if (amount == 0uL) return false

        return when (payMethod) {
            SendMethod.LIGHTNING -> when (val lnurl = _sendUiState.value.lnurl) {
                null -> lightningRepo.canSend(amount)
                is LnurlParams.LnurlWithdraw -> amount < lnurl.data.maxWithdrawableSat()
                is LnurlParams.LnurlPay -> {
                    val minSat = lnurl.data.minSendableSat()
                    val maxSat = lnurl.data.maxSendableSat()

                    amount in minSat..maxSat && lightningRepo.canSend(amount)
                }
            }

            SendMethod.ONCHAIN -> amount > Env.TransactionDefaults.dustLimit.toULong()
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

    private suspend fun handleScan(result: String) = withContext(bgDispatcher) {
        // always reset state on new scan
        resetSendState()
        resetQuickPayData()

        val scan = runCatching { decode(result) }
            .onFailure { Logger.error("Failed to decode scan data: '$result'", it, context = TAG) }
            .onSuccess { Logger.info("Handling decoded scan data: $it", context = TAG) }
            .getOrNull()

        when (scan) {
            is Scanner.OnChain -> onScanOnchain(scan.invoice, result)
            is Scanner.Lightning -> onScanLightning(scan.invoice, result)
            is Scanner.LnurlPay -> onScanLnurlPay(scan.data)
            is Scanner.LnurlWithdraw -> onScanLnurlWithdraw(scan.data)
            is Scanner.LnurlAuth -> onScanLnurlAuth(scan.data)
            is Scanner.LnurlChannel -> onScanLnurlChannel(scan.data)
            is Scanner.NodeId -> onScanNodeId(scan)
            is Scanner.Gift -> onScanGift(scan.code, scan.amount)
            else -> {
                Logger.warn("Unhandled scan data: $scan", context = TAG)
                toast(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.other__scan_err_decoding),
                    description = context.getString(R.string.other__scan_err_interpret_title),
                )
            }
        }
    }

    private suspend fun onScanOnchain(invoice: OnChainInvoice, scanResult: String) {
        val lnInvoice: LightningInvoice? = invoice.params?.get("lightning")?.let { bolt11 ->
            runCatching { decode(bolt11) }.getOrNull()
                ?.let { it as? Scanner.Lightning }
                ?.invoice
                ?.takeIf { invoice ->
                    val canSend = lightningRepo.canSend(invoice.amountSatoshis.coerceAtLeast(1u))
                    if (!canSend) {
                        Logger.debug("Cannot pay unified invoice using LN, defaulting to onchain-only", context = TAG)
                    }
                    return@takeIf canSend
                }
        }
        _sendUiState.update {
            it.copy(
                address = invoice.address,
                addressInput = scanResult,
                isAddressInputValid = true,
                amount = invoice.amountSatoshis,
                isUnified = lnInvoice != null,
                decodedInvoice = lnInvoice,
                payMethod = lnInvoice?.let { SendMethod.LIGHTNING } ?: SendMethod.ONCHAIN,
            )
        }

        val lnAmountSats = lnInvoice?.amountSatoshis ?: 0u
        if (lnAmountSats > 0u) {
            Logger.info("Found amount in unified invoice, checking QuickPay conditions", context = TAG)

            val quickPayHandled = handleQuickPayIfApplicable(
                amountSats = lnAmountSats,
                invoice = lnInvoice,
            )
            if (quickPayHandled) return

            refreshOnchainSendIfNeeded()
            if (isMainScanner) {
                showSheet(Sheet.Send(SendRoute.Confirm))
            } else {
                setSendEffect(SendEffect.NavigateToConfirm)
            }
            return
        }

        Logger.info(
            when (invoice.amountSatoshis > 0u) {
                true -> "Found amount in invoice, proceeding to edit amount"
                else -> "No amount found in invoice, proceeding to enter amount"
            },
            context = TAG,
        )

        if (isMainScanner) {
            showSheet(Sheet.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private suspend fun onScanLightning(invoice: LightningInvoice, scanResult: String) {
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

        if (!lightningRepo.canSend(invoice.amountSatoshis)) {
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
                addressInput = scanResult,
                isAddressInputValid = true,
                decodedInvoice = invoice,
                payMethod = SendMethod.LIGHTNING,
            )
        }

        if (invoice.amountSatoshis > 0uL) {
            Logger.info("Found amount in invoice, proceeding with payment", context = TAG)

            if (isMainScanner) {
                showSheet(Sheet.Send(SendRoute.Confirm))
            } else {
                setSendEffect(SendEffect.NavigateToConfirm)
            }
            return
        }
        Logger.info("No amount found in invoice, proceeding to enter amount", context = TAG)

        if (isMainScanner) {
            showSheet(Sheet.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private suspend fun onScanLnurlPay(data: LnurlPayData) {
        Logger.debug("LNURL: $data", context = TAG)

        val minSendable = data.minSendableSat()
        val maxSendable = data.maxSendableSat()

        if (!lightningRepo.canSend(minSendable)) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__lnurl_pay_error),
                description = context.getString(R.string.other__lnurl_pay_error_no_capacity),
            )
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
            Logger.info("Found amount $$minSendable in lnurlPay, proceeding with payment", context = TAG)

            val quickPayHandled = handleQuickPayIfApplicable(amountSats = minSendable, lnurlPay = data)
            if (quickPayHandled) return

            if (isMainScanner) {
                showSheet(Sheet.Send(SendRoute.Confirm))
            } else {
                setSendEffect(SendEffect.NavigateToConfirm)
            }
            return
        }

        Logger.info("No amount found in lnurlPay, proceeding to enter amount manually", context = TAG)
        if (isMainScanner) {
            showSheet(Sheet.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private fun onScanLnurlWithdraw(data: LnurlWithdrawData) {
        Logger.debug("LNURL: $data", context = TAG)

        val minWithdrawable = data.minWithdrawableSat()
        val maxWithdrawable = data.maxWithdrawableSat()

        if (minWithdrawable > maxWithdrawable) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__lnurl_withdr_error),
                description = context.getString(R.string.other__lnurl_withdr_error_minmax)
            )
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
            showSheet(Sheet.Send(SendRoute.Amount))
        } else {
            setSendEffect(SendEffect.NavigateToAmount)
        }
    }

    private suspend fun onScanLnurlAuth(data: LnurlAuthData) {
        Logger.debug("LNURL: $data", context = TAG)
        if (!isMainScanner) {
            hideSheet()
            delay(TRANSITION_SCREEN_MS)
        }
        showSheet(Sheet.LnurlAuth(domain = data.domain, lnurl = data.uri, k1 = data.k1))
    }

    fun requestLnurlAuth(callback: String, k1: String, domain: String) {
        viewModelScope.launch {
            lightningRepo.requestLnurlAuth(
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
        Logger.debug("LNURL: $data", context = TAG)
        hideSheet() // hide scan sheet if opened
        mainScreenEffect(
            MainScreenEffect.Navigate(
                Routes.LnurlChannel(uri = data.uri, callback = data.callback, k1 = data.k1)
            )
        )
    }

    private fun onScanNodeId(data: Scanner.NodeId) {
        // TODO uncomment when bitkit-core is no longer hardcoding MAINNET as network
        //  or remove this check altogether if it's not possible to implement it reliably in rust.
        //  see: https://github.com/synonymdev/bitkit-core/blob/fc432888016a1bf61aa9bfbee908513e9a33f9b9/src/modules/scanner/implementation.rs#L77
        // val network = data.network
        // val appNetwork = Env.network.toCoreNetworkType()
        // if (network != appNetwork) {
        //     toast(
        //         type = Toast.ToastType.WARNING,
        //         title = context.getString(R.string.other__qr_error_network_header),
        //         description = context.getString(R.string.other__qr_error_network_text)
        //             .replace("{selectedNetwork}", appNetwork.name)
        //             .replace("{dataNetwork}", network.name),
        //     )
        //     return
        // }
        hideSheet() // hide scan sheet if opened
        val nextRoute = Routes.ExternalConnection(data.url)
        mainScreenEffect(MainScreenEffect.Navigate(nextRoute))
    }

    private fun onScanGift(code: String, amount: ULong) {
        hideSheet() // hide scan sheet if opened
        showSheet(Sheet.Gift(code = code, amount = amount))
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
            Logger.info("Using QuickPay: $amountSats sats <= $quickPayAmountSats sats threshold", context = TAG)

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

            Logger.debug("QuickPayData: $quickPayData", context = TAG)

            if (isMainScanner) {
                showSheet(Sheet.Send(SendRoute.QuickPay))
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
                amount = 0u,
                isAmountInputValid = false,
            )
        }
    }

    private fun onSwipeToPay() {
        Logger.debug("Swipe to pay event, checking send confirmation conditions", context = TAG)
        viewModelScope.launch {
            val amount = _sendUiState.value.amount

            handleSanityChecks(amount)
            if (_sendUiState.value.showSanityWarningDialog != null) return@launch // await for dialog UI interaction

            _sendUiState.update { it.copy(shouldConfirmPay = true) }
        }
    }

    private suspend fun handleSanityChecks(amountSats: ULong) {
        if (_sendUiState.value.showSanityWarningDialog != null) return

        val settings = settingsStore.data.first()

        if (
            amountSats > BigDecimal.valueOf(walletRepo.balanceState.value.totalSats.toLong())
                .times(BigDecimal(MAX_BALANCE_FRACTION)).toLong().toUInt() &&
            SanityWarning.OVER_HALF_BALANCE !in _sendUiState.value.confirmedWarnings
        ) {
            _sendUiState.update {
                it.copy(showSanityWarningDialog = SanityWarning.OVER_HALF_BALANCE)
            }
            return
        }

        val amountInUsd = currencyRepo.convertSatsToFiat(amountSats.toLong(), "USD").getOrNull() ?: return
        if (
            amountInUsd.value > BigDecimal(SEND_AMOUNT_WARNING_THRESHOLD) &&
            settings.enableSendAmountWarning &&
            SanityWarning.VALUE_OVER_100_USD !in _sendUiState.value.confirmedWarnings
        ) {
            _sendUiState.update {
                it.copy(showSanityWarningDialog = SanityWarning.VALUE_OVER_100_USD)
            }
            return
        }

        if (_sendUiState.value.payMethod != SendMethod.ONCHAIN) return

        val totalFee = lightningRepo.calculateTotalFee(
            amountSats = amountSats,
            address = _sendUiState.value.address,
            speed = _sendUiState.value.speed,
            utxosToSpend = _sendUiState.value.selectedUtxos,
        ).getOrNull() ?: return

        if (
            totalFee > BigDecimal.valueOf(
                amountSats.toLong()
            ).times(BigDecimal(MAX_FEE_AMOUNT_RATIO)).toLong().toUInt() &&
            SanityWarning.FEE_OVER_HALF_VALUE !in _sendUiState.value.confirmedWarnings
        ) {
            _sendUiState.update {
                it.copy(showSanityWarningDialog = SanityWarning.FEE_OVER_HALF_VALUE)
            }
            return
        }

        val feeInUsd = currencyRepo.convertSatsToFiat(totalFee.toLong(), "USD").getOrNull() ?: return
        if (
            feeInUsd.value > BigDecimal(TEN_USD) &&
            SanityWarning.FEE_OVER_10_USD !in _sendUiState.value.confirmedWarnings
        ) {
            _sendUiState.update {
                it.copy(showSanityWarningDialog = SanityWarning.FEE_OVER_10_USD)
            }
            return
        }

        _sendUiState.update {
            it.copy(showSanityWarningDialog = null)
        }
    }

    private suspend fun proceedWithPayment() {
        delay(SCREEN_TRANSITION_DELAY_MS) // wait for screen transitions when applicable

        val amount = _sendUiState.value.amount

        val lnurl = _sendUiState.value.lnurl
        val isLnurlPay = lnurl is LnurlParams.LnurlPay

        if (isLnurlPay) {
            lightningRepo.fetchLnurlInvoice(
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
                // TODO validate early, validate network & address types, showing detailed errors
                val validatedAddress = runCatching { validateBitcoinAddress(address) }
                    .getOrElse { e ->
                        Logger.error("Invalid bitcoin send address: '$address'", e, context = TAG)
                        toast(Exception("Invalid bitcoin send address"))
                        hideSheet()
                        return
                    }

                val tags = _sendUiState.value.selectedTags

                sendOnchain(validatedAddress.address, amount, tags = tags)
                    .onSuccess { txId ->
                        Logger.info("Onchain send result txid: $txId", context = TAG)
                        handlePaymentSuccess(
                            NewTransactionSheetDetails(
                                type = NewTransactionSheetType.ONCHAIN,
                                direction = NewTransactionSheetDirection.SENT,
                                paymentHashOrTxId = txId,
                                sats = amount.toLong(),
                            )
                        )
                        lightningRepo.sync()
                    }.onFailure { e ->
                        Logger.error(msg = "Error sending onchain payment", e = e, context = TAG)
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

                val tags = _sendUiState.value.selectedTags
                var createdMetadataPaymentId: String? = null

                // Extract payment hash from invoice for pre-activity metadata
                val paymentHash = decodedInvoice.paymentHash.toHex()

                // Create pre-activity metadata before sending
                if (tags.isNotEmpty()) {
                    preActivityMetadataRepo.savePreActivityMetadata(
                        id = paymentHash,
                        paymentHash = paymentHash,
                        address = _sendUiState.value.address,
                        isReceive = false,
                        tags = tags,
                    ).onSuccess {
                        createdMetadataPaymentId = paymentHash
                    }
                }

                sendLightning(bolt11, paymentAmount).onSuccess { actualPaymentHash ->
                    Logger.info("Lightning send result payment hash: $actualPaymentHash", context = TAG)
                    handlePaymentSuccess(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.SENT,
                            paymentHashOrTxId = actualPaymentHash,
                            sats = paymentAmount.toLong(), // TODO Add fee when available
                        ),
                    )
                }.onFailure { e ->
                    // Delete pre-activity metadata on failure
                    if (createdMetadataPaymentId != null) {
                        preActivityMetadataRepo.deletePreActivityMetadata(createdMetadataPaymentId)
                    }
                    Logger.error("Error sending lightning payment", e, context = TAG)
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

            val invoice = lightningRepo.createInvoice(
                amountSats = _sendUiState.value.amount,
                description = lnurl.data.defaultDescription,
                expirySeconds = 3600u,
            ).getOrNull()

            if (invoice == null) {
                setSendEffect(SendEffect.NavigateToWithdrawError)
                return@launch
            }

            lightningRepo.requestLnurlWithdraw(
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
            }.onFailure {
                _sendUiState.update { it.copy(isLoading = false) }
                setSendEffect(SendEffect.NavigateToWithdrawError)
            }
        }
    }

    fun onClickActivityDetail() {
        val activityType = _newTransaction.value.type.toActivityFilter()
        val txType = _newTransaction.value.direction.toTxType()
        val paymentHashOrTxId = _newTransaction.value.paymentHashOrTxId ?: return
        _newTransaction.update { it.copy(isLoadingDetails = true) }
        viewModelScope.launch(bgDispatcher) {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHashOrTxId,
                type = activityType,
                txType = txType,
                retry = true
            ).onSuccess { activity ->
                hideNewTransactionSheet()
                _newTransaction.update { it.copy(isLoadingDetails = false) }
                val nextRoute = Routes.ActivityDetail(activity.rawId())
                mainScreenEffect(MainScreenEffect.Navigate(nextRoute))
            }.onFailure { e ->
                Logger.error(msg = "Activity not found", context = TAG)
                toast(e)
                _newTransaction.update { it.copy(isLoadingDetails = false) }
            }
        }
    }

    fun onClickSendDetail() {
        val activityType = _successSendUiState.value.type.toActivityFilter()
        val txType = _successSendUiState.value.direction.toTxType()
        val paymentHashOrTxId = _successSendUiState.value.paymentHashOrTxId ?: return
        _successSendUiState.update { it.copy(isLoadingDetails = true) }
        viewModelScope.launch(bgDispatcher) {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHashOrTxId,
                type = activityType,
                txType = txType,
                retry = true
            ).onSuccess { activity ->
                hideSheet()
                _successSendUiState.update { it.copy(isLoadingDetails = false) }
                val nextRoute = Routes.ActivityDetail(activity.rawId())
                mainScreenEffect(MainScreenEffect.Navigate(nextRoute))
            }.onFailure { e ->
                Logger.error(msg = "Activity not found", context = TAG)
                toast(e)
                _successSendUiState.update { it.copy(isLoadingDetails = false) }
            }
        }
    }

    private suspend fun sendOnchain(
        address: String,
        amount: ULong,
        tags: List<String> = emptyList(),
    ): Result<Txid> {
        return lightningRepo.sendOnChain(
            address = address,
            sats = amount,
            speed = _sendUiState.value.speed,
            utxosToSpend = _sendUiState.value.selectedUtxos,
            isMaxAmount = _sendUiState.value.payMethod == SendMethod.ONCHAIN &&
                amount == walletRepo.balanceState.value.maxSendOnchainSats,
            tags = tags,
        )
    }

    private suspend fun sendLightning(
        bolt11: String,
        amount: ULong? = null,
    ): Result<PaymentId> {
        return lightningRepo.payInvoice(bolt11 = bolt11, sats = amount).onSuccess { hash ->
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

    fun clearClipboardForAutoRead() {
        viewModelScope.launch {
            val isAutoReadClipboardEnabled = settingsStore.data.first().enableAutoReadClipboard
            if (isAutoReadClipboardEnabled) {
                context.setClipboardText("")
            }
        }
    }

    fun resetQuickPayData() = _quickPayData.update { null }

    /** Reselect utxos for current amount & speed then refresh fees using updated utxos */
    private fun refreshOnchainSendIfNeeded() {
        val currentState = _sendUiState.value
        if (currentState.payMethod != SendMethod.ONCHAIN ||
            currentState.amount == 0uL ||
            currentState.address.isEmpty()
        ) {
            return
        }

        // refresh in background
        viewModelScope.launch(bgDispatcher) {
            // preselect utxos for deterministic fee estimation
            if (settingsStore.data.first().coinSelectAuto && currentState.selectedUtxos == null) {
                lightningRepo.getFeeRateForSpeed(currentState.speed, currentState.feeRates)
                    .mapCatching { satsPerVByte ->
                        lightningRepo.determineUtxosToSpend(
                            sats = currentState.amount,
                            satsPerVByte = satsPerVByte.toUInt(),
                        )
                    }
                    .onSuccess { utxos ->
                        _sendUiState.update {
                            it.copy(selectedUtxos = utxos)
                        }
                    }
            }
            refreshFeeEstimates()
        }
    }

    private suspend fun refreshFeeEstimates() = withContext(bgDispatcher) {
        val currentState = _sendUiState.value

        val speeds = listOf(
            TransactionSpeed.Fast,
            TransactionSpeed.Medium,
            TransactionSpeed.Slow,
            when (val speed = currentState.speed) {
                is TransactionSpeed.Custom -> speed
                else -> TransactionSpeed.Custom(0u)
            }
        )

        var currentFee = 0L
        val feesMap = coroutineScope {
            speeds.map { speed ->
                async {
                    val rate = FeeRate.fromSpeed(speed)
                    val fee = if (currentState.feeRates?.getSatsPerVByteFor(speed) != 0u) getFeeEstimate(speed) else 0

                    if (speed == currentState.speed) {
                        currentFee = fee
                    }

                    rate to fee
                }
            }.awaitAll().toMap()
        }

        _sendUiState.update {
            it.copy(
                fees = feesMap,
                fee = SendFee.OnChain(currentFee),
            )
        }
    }

    private suspend fun estimateLightningRoutingFeesIfNeeded() {
        val currentState = _sendUiState.value
        if (currentState.payMethod != SendMethod.LIGHTNING) return
        val decodedInvoice = currentState.decodedInvoice ?: return

        val feeResult = if (decodedInvoice.amountSatoshis > 0uL) {
            lightningRepo.estimateRoutingFees(decodedInvoice.bolt11)
        } else {
            lightningRepo.estimateRoutingFeesForAmount(decodedInvoice.bolt11, currentState.amount)
        }

        feeResult.onSuccess { fee ->
            _sendUiState.update {
                it.copy(
                    fee = SendFee.Lightning(fee.toLong())
                )
            }
        }
    }

    private suspend fun getFeeEstimate(speed: TransactionSpeed? = null): Long {
        val currentState = _sendUiState.value
        return lightningRepo.calculateTotalFee(
            amountSats = currentState.amount,
            address = currentState.address,
            speed = speed ?: currentState.speed,
            utxosToSpend = currentState.selectedUtxos,
            feeRates = currentState.feeRates,
        ).getOrDefault(0u).toLong()
    }

    suspend fun resetSendState() {
        val speed = settingsStore.data.first().defaultTransactionSpeed
        val rates = let {
            // Refresh blocktank info to get latest fee rates
            blocktankRepo.refreshInfo()
            blocktankRepo.blocktankState.value.info?.onchain?.feeRates
        }

        _sendUiState.update {
            SendUiState(
                speed = speed,
                feeRates = rates,
            )
        }
    }
    // endregion

    // region TxSheet
    private var _isNewTransactionSheetEnabled = true
    private val _showNewTransaction = MutableStateFlow(false)
    val showNewTransaction: StateFlow<Boolean> = _showNewTransaction.asStateFlow()

    private val _newTransaction = MutableStateFlow(
        NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
        )
    )

    val newTransaction = _newTransaction.asStateFlow()

    private val _successSendUiState = MutableStateFlow(
        NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.SENT,
        )
    )

    val successSendUiState = _successSendUiState.asStateFlow()

    fun setNewTransactionSheetEnabled(enabled: Boolean) {
        _isNewTransactionSheetEnabled = enabled
    }

    fun showNewTransactionSheet(
        details: NewTransactionSheetDetails,
    ) = viewModelScope.launch {
        if (backupRepo.isRestoring.value) return@launch

        if (!_isNewTransactionSheetEnabled) {
            Logger.verbose("NewTransactionSheet blocked by isNewTransactionSheetEnabled=false", context = TAG)
            return@launch
        }

        hideSheet()

        _showNewTransaction.update { true }
        _newTransaction.update { details }
    }

    fun hideNewTransactionSheet() = _showNewTransaction.update { false }
    // endregion

    // region Sheets
    private val _currentSheet: MutableStateFlow<Sheet?> = MutableStateFlow(null)
    val currentSheet = _currentSheet.asStateFlow()

    fun showSheet(sheetType: Sheet) {
        viewModelScope.launch {
            _currentSheet.value?.let {
                _currentSheet.update { null }
                delay(SCREEN_TRANSITION_DELAY_MS)
            }
            _currentSheet.update { sheetType }
        }
    }

    fun hideSheet() {
        if (currentSheet.value is Sheet.TimedSheet && currentTimedSheet != null) {
            dismissTimedSheet()
        } else {
            _currentSheet.update { null }
        }
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
        testTag: String? = null,
    ) {
        currentToast = Toast(
            type = type,
            title = title,
            description = description,
            autoHide = autoHide,
            visibilityTime = visibilityTime,
            testTag = testTag,
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

    private fun onConfirmAmountWarning(warning: SanityWarning) {
        viewModelScope.launch {
            _sendUiState.update {
                it.copy(
                    showSanityWarningDialog = null,
                    confirmedWarnings = it.confirmedWarnings + warning
                )
            }
        }
        onSwipeToPay()
    }

    private fun onDismissAmountWarning() {
        _sendUiState.update {
            it.copy(showSanityWarningDialog = null)
        }
    }

    private fun onConfirmPay() {
        Logger.debug("Payment checks confirmed, proceeding…", context = TAG)
        viewModelScope.launch {
            _sendUiState.update { it.copy(shouldConfirmPay = false) }
            proceedWithPayment()
        }
    }

    fun handlePaymentSuccess(details: NewTransactionSheetDetails) {
        details.paymentHashOrTxId?.let {
            if (!processedPayments.add(it)) {
                Logger.debug("Payment $it already processed, skipping duplicate", context = TAG)
                return
            }
        }

        _successSendUiState.update { details }
        setSendEffect(SendEffect.PaymentSuccess(details))
    }

    fun handleDeeplinkIntent(intent: Intent) {
        intent.data?.let { uri ->
            Logger.debug("Received deeplink: $uri")
            processDeeplink(uri)
        }
    }

    private fun processDeeplink(uri: Uri) = viewModelScope.launch {
        if (uri.toString().contains("recovery-mode")) {
            lightningRepo.setRecoveryMode(enabled = true)
            delay(SCREEN_TRANSITION_DELAY_MS)
            mainScreenEffect(
                MainScreenEffect.Navigate(
                    route = Routes.RecoveryMode,
                    navOptions = navOptions {
                        popUpTo(0) { inclusive = true }
                    }
                )
            )
            return@launch
        }

        if (!walletRepo.walletExists()) return@launch

        val data = uri.toString()
        delay(SCREEN_TRANSITION_DELAY_MS)
        handleScan(data.removeLightningSchemes())
    }

    // Todo Temporaary fix while these schemes can't be decoded
    private fun String.removeLightningSchemes(): String {
        return this
            .replace("lnurl:", "")
            .replace("lnurlw:", "")
            .replace("lnurlc:", "")
            .replace("lnurlp:", "")
    }

    fun checkTimedSheets() {
        if (backupRepo.isRestoring.value) return

        if (currentTimedSheet != null || timedSheetQueue.isNotEmpty()) {
            Logger.debug("Timed sheet already active, skipping check")
            return
        }

        if (backupRepo.isRestoring.value) return

        timedSheetsScope?.cancel()
        timedSheetsScope = CoroutineScope(bgDispatcher + SupervisorJob())
        timedSheetsScope?.launch {
            delay(CHECK_DELAY_MILLIS)

            if (currentTimedSheet != null || timedSheetQueue.isNotEmpty()) {
                Logger.debug("Timed sheet became active during delay, skipping")
                return@launch
            }

            val eligibleSheets = TimedSheetType.entries
                .filter { shouldDisplaySheet(it) }
                .sortedByDescending { it.priority }

            if (eligibleSheets.isNotEmpty()) {
                Logger.debug(
                    "Building timed sheet queue: ${eligibleSheets.joinToString { it.name }}",
                    context = "Timed sheet"
                )
                timedSheetQueue = eligibleSheets
                currentTimedSheet = eligibleSheets.first()
                showSheet(Sheet.TimedSheet(eligibleSheets.first()))
            } else {
                Logger.debug("No timed sheet eligible, skipping", context = "Timed sheet")
            }
        }
    }

    fun onLeftHome() {
        Logger.debug("Left home, skipping timed sheet check")
        timedSheetsScope?.cancel()
        timedSheetsScope = null
    }

    fun dismissTimedSheet(skipQueue: Boolean = false) {
        Logger.debug("dismissTimedSheet called", context = "Timed sheet")

        val currentQueue = timedSheetQueue
        val currentSheet = currentTimedSheet

        if (currentQueue.isEmpty() || currentSheet == null) {
            clearTimedSheets()
            return
        }

        viewModelScope.launch {
            val currentTime = Clock.System.now().toEpochMilliseconds()

            when (currentSheet) {
                TimedSheetType.BACKUP -> {
                    settingsStore.update { it.copy(backupWarningIgnoredMillis = currentTime) }
                }

                TimedSheetType.HIGH_BALANCE -> {
                    settingsStore.update {
                        it.copy(
                            balanceWarningTimes = it.balanceWarningTimes + 1,
                            balanceWarningIgnoredMillis = currentTime
                        )
                    }
                }

                TimedSheetType.APP_UPDATE -> Unit

                TimedSheetType.NOTIFICATIONS -> {
                    settingsStore.update { it.copy(notificationsIgnoredMillis = currentTime) }
                }

                TimedSheetType.QUICK_PAY -> {
                    settingsStore.update { it.copy(quickPayIntroSeen = true) }
                }
            }
        }

        if (skipQueue) {
            clearTimedSheets()
            return
        }

        val currentIndex = currentQueue.indexOf(currentSheet)
        val nextIndex = currentIndex + 1

        if (nextIndex < currentQueue.size) {
            Logger.debug("Moving to next timed sheet in queue: ${currentQueue[nextIndex].name}")
            currentTimedSheet = currentQueue[nextIndex]
            showSheet(Sheet.TimedSheet(currentQueue[nextIndex]))
        } else {
            Logger.debug("Timed sheet queue exhausted")
            clearTimedSheets()
        }
    }

    private fun clearTimedSheets() {
        currentTimedSheet = null
        timedSheetQueue = emptyList()
        hideSheet()
    }

    private suspend fun shouldDisplaySheet(sheet: TimedSheetType): Boolean = when (sheet) {
        TimedSheetType.APP_UPDATE -> checkAppUpdate()
        TimedSheetType.BACKUP -> checkBackupSheet()
        TimedSheetType.NOTIFICATIONS -> checkNotificationSheet()
        TimedSheetType.QUICK_PAY -> checkQuickPaySheet()
        TimedSheetType.HIGH_BALANCE -> checkHighBalance()
    }

    private suspend fun checkQuickPaySheet(): Boolean {
        val settings = settingsStore.data.first()
        if (settings.quickPayIntroSeen || settings.isQuickPayEnabled) return false
        val shouldShow = walletRepo.balanceState.value.totalLightningSats > 0U
        return shouldShow
    }

    private suspend fun checkNotificationSheet(): Boolean {
        val settings = settingsStore.data.first()
        if (settings.notificationsGranted) return false
        if (walletRepo.balanceState.value.totalLightningSats == 0UL) return false

        return checkTimeout(
            lastIgnoredMillis = settings.notificationsIgnoredMillis,
            intervalMillis = ONE_WEEK_ASK_INTERVAL_MILLIS
        )
    }

    private suspend fun checkBackupSheet(): Boolean {
        val settings = settingsStore.data.first()
        if (settings.backupVerified) return false

        val hasBalance = walletRepo.balanceState.value.totalSats > 0U
        if (!hasBalance) return false

        return checkTimeout(
            lastIgnoredMillis = settings.backupWarningIgnoredMillis,
            intervalMillis = ONE_DAY_ASK_INTERVAL_MILLIS
        )
    }

    private suspend fun checkAppUpdate(): Boolean = withContext(bgDispatcher) {
        try {
            val androidReleaseInfo = appUpdaterService.getReleaseInfo().platforms.android
            val currentBuildNumber = BuildConfig.VERSION_CODE

            if (androidReleaseInfo.buildNumber <= currentBuildNumber) return@withContext false

            if (androidReleaseInfo.isCritical) {
                mainScreenEffect(
                    MainScreenEffect.Navigate(
                        route = Routes.CriticalUpdate,
                        navOptions = navOptions {
                            popUpTo(0) { inclusive = true }
                        }
                    )
                )
                return@withContext false
            }

            return@withContext true
        } catch (e: Exception) {
            Logger.warn("Failure fetching new releases", e = e)
            return@withContext false
        }
    }

    private suspend fun checkHighBalance(): Boolean {
        val settings = settingsStore.data.first()

        val totalOnChainSats = walletRepo.balanceState.value.totalSats
        val balanceUsd = satsToUsd(totalOnChainSats) ?: return false
        val thresholdReached = balanceUsd > BigDecimal(BALANCE_THRESHOLD_USD)

        if (!thresholdReached) {
            settingsStore.update { it.copy(balanceWarningTimes = 0) }
            return false
        }

        val belowMaxWarnings = settings.balanceWarningTimes < MAX_WARNINGS

        return checkTimeout(
            lastIgnoredMillis = settings.balanceWarningIgnoredMillis,
            intervalMillis = ONE_DAY_ASK_INTERVAL_MILLIS,
            additionalCondition = belowMaxWarnings
        )
    }

    private fun checkTimeout(
        lastIgnoredMillis: Long,
        intervalMillis: Long,
        additionalCondition: Boolean = true,
    ): Boolean {
        if (!additionalCondition) return false

        val currentTime = Clock.System.now().toEpochMilliseconds()
        val isTimeOutOver = lastIgnoredMillis == 0L ||
            (currentTime - lastIgnoredMillis > intervalMillis)
        return isTimeOutOver
    }

    private fun satsToUsd(sats: ULong): BigDecimal? {
        val converted = currencyRepo.convertSatsToFiat(sats = sats.toLong(), currency = "USD").getOrNull()
        return converted?.value
    }

    companion object {
        private const val TAG = "AppViewModel"
        private const val SEND_AMOUNT_WARNING_THRESHOLD = 100.0
        private const val TEN_USD = 10
        private const val MAX_BALANCE_FRACTION = 0.5
        private const val MAX_FEE_AMOUNT_RATIO = 0.5
        private const val SCREEN_TRANSITION_DELAY_MS = 300L

        /**How high the balance must be to show this warning to the user (in USD)*/
        private const val BALANCE_THRESHOLD_USD = 500L
        private const val MAX_WARNINGS = 3

        /** how long this prompt will be hidden if user taps Later*/
        private const val ONE_DAY_ASK_INTERVAL_MILLIS = 1000 * 60 * 60 * 24L

        /** how long this prompt will be hidden if user taps Later*/
        private const val ONE_WEEK_ASK_INTERVAL_MILLIS = ONE_DAY_ASK_INTERVAL_MILLIS * 7L

        /**How long user needs to stay on the home screen before he see this prompt*/
        private const val CHECK_DELAY_MILLIS = 2000L

        /** Delay to allow activity sync before checking if received sheet should be shown */
        private const val DELAY_FOR_ACTIVITY_SYNC_MS = 500L
    }
}

// region send contract
data class SendUiState(
    val address: String = "",
    val bolt11: String? = null,
    val addressInput: String = "",
    val isAddressInputValid: Boolean = false,
    val amount: ULong = 0u,
    val isAmountInputValid: Boolean = false,
    val isUnified: Boolean = false,
    val payMethod: SendMethod = SendMethod.ONCHAIN,
    val selectedTags: List<String> = listOf(),
    val decodedInvoice: LightningInvoice? = null,
    val showSanityWarningDialog: SanityWarning? = null,
    val confirmedWarnings: List<SanityWarning> = listOf(),
    val shouldConfirmPay: Boolean = false,
    val selectedUtxos: List<SpendableUtxo>? = null,
    val lnurl: LnurlParams? = null,
    val isLoading: Boolean = false,
    val speed: TransactionSpeed = TransactionSpeed.default(),
    val comment: String = "",
    val feeRates: FeeRates? = null,
    val fee: SendFee? = null,
    val fees: Map<FeeRate, Long> = emptyMap(),
)

enum class SanityWarning(@StringRes val message: Int, val testTag: String) {
    VALUE_OVER_100_USD(R.string.wallet__send_dialog1, "SendDialog1"),
    OVER_HALF_BALANCE(R.string.wallet__send_dialog2, "SendDialog2"),
    FEE_OVER_HALF_VALUE(R.string.wallet__send_dialog3, "SendDialog3"),
    FEE_OVER_10_USD(R.string.wallet__send_dialog4, "SendDialog4"),
    // TODO SendDialog5 https://github.com/synonymdev/bitkit/blob/master/src/screens/Wallets/Send/ReviewAndSend.tsx#L457-L466
}

sealed class SendFee(open val value: Long) {
    data class OnChain(override val value: Long) : SendFee(value)
    data class Lightning(override val value: Long) : SendFee(value)
}

enum class SendMethod { ONCHAIN, LIGHTNING }

sealed class SendEffect {
    data class PopBack(val route: SendRoute) : SendEffect()
    data object NavigateToAddress : SendEffect()
    data object NavigateToAmount : SendEffect()
    data object NavigateToScan : SendEffect()
    data object NavigateToConfirm : SendEffect()
    data object NavigateToWithdrawConfirm : SendEffect()
    data object NavigateToWithdrawError : SendEffect()
    data object NavigateToCoinSelection : SendEffect()
    data object NavigateToQuickPay : SendEffect()
    data object NavigateToFee : SendEffect()
    data object NavigateToFeeCustom : SendEffect()
    data class PaymentSuccess(val sheet: NewTransactionSheetDetails? = null) : SendEffect()
}

sealed class MainScreenEffect {
    data class Navigate(
        val route: Routes,
        val navOptions: NavOptions? = null,
    ) : MainScreenEffect()

    data object WipeWallet : MainScreenEffect()
    data class ProcessClipboardAutoRead(val data: String) : MainScreenEffect()
}

sealed interface SendEvent {
    data object EnterManually : SendEvent
    data object Paste : SendEvent
    data object Scan : SendEvent

    data object AddressReset : SendEvent
    data class AddressChange(val value: String) : SendEvent
    data class AddressContinue(val data: String) : SendEvent

    data object AmountReset : SendEvent
    data object AmountContinue : SendEvent
    data class AmountChange(val amount: ULong) : SendEvent

    data class CoinSelectionContinue(val utxos: List<SpendableUtxo>) : SendEvent

    data class CommentChange(val value: String) : SendEvent

    data object SwipeToPay : SendEvent
    data object SpeedAndFee : SendEvent
    data object PaymentMethodSwitch : SendEvent
    data class ConfirmAmountWarning(val warning: SanityWarning) : SendEvent
    data object DismissAmountWarning : SendEvent
    data object PayConfirmed : SendEvent
    data object ClearPayConfirmation : SendEvent
    data object BackToAmount : SendEvent
    data object NavToAddress : SendEvent
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
