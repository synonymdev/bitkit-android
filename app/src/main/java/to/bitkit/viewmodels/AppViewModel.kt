package to.bitkit.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.synonym.bitkitcore.SortDirection
import com.synonym.paykit.PaymentRequestLifecycleState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.ClosureReason
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentFailureReason
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
import to.bitkit.domain.commands.NotifyChannelReady
import to.bitkit.domain.commands.NotifyChannelReadyHandler
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.env.Defaults
import to.bitkit.env.Env
import to.bitkit.ext.WatchResult
import to.bitkit.ext.amountSats
import to.bitkit.ext.callbackAmountMsats
import to.bitkit.ext.channelId
import to.bitkit.ext.claimableAtHeight
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.getSatsPerVByteFor
import to.bitkit.ext.isFixedAmount
import to.bitkit.ext.isTrezorUserCancellation
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.maxWithdrawableSat
import to.bitkit.ext.minSendableSat
import to.bitkit.ext.minWithdrawableSat
import to.bitkit.ext.rawId
import to.bitkit.ext.removeSpaces
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.setClipboardText
import to.bitkit.ext.supportPaymentRequest
import to.bitkit.ext.toHex
import to.bitkit.ext.toSendFailureDetails
import to.bitkit.ext.toUserMessage
import to.bitkit.ext.totalValue
import to.bitkit.ext.walletId
import to.bitkit.ext.watchUntil
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.models.FeeRate
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkyRingAuthCallback
import to.bitkit.models.PubkyRingAuthCallbackHandlingResult
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.models.SendFailureDetails
import to.bitkit.models.Suggestion
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.TransportType
import to.bitkit.models.USD
import to.bitkit.models.WalletScope
import to.bitkit.models.msatFloorOf
import to.bitkit.models.safe
import to.bitkit.models.sanitizedDeeplinkLogValue
import to.bitkit.models.toActivityFilter
import to.bitkit.models.toLdkNetwork
import to.bitkit.models.toTxType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LnurlPayInvoiceMismatchError
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.NodeEventUpdate
import to.bitkit.repositories.PaykitOnchainPaymentProofResolution
import to.bitkit.repositories.PaykitPaymentProofKind
import to.bitkit.repositories.PaykitPaymentProofRepo
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestCreation
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestError
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionId
import to.bitkit.repositories.PaymentPendingException
import to.bitkit.repositories.PendingPaymentNotification
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.PrivatePaykitPaymentContext
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.repositories.QuickPayPaymentFailedError
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.repositories.SamRockRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.services.AppUpdaterService
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.services.NodeServiceFgState
import to.bitkit.ui.Routes
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SubscriptionRoute
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.shared.toast.ToastQueueManager
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute
import to.bitkit.ui.theme.TRANSITION_SCREEN_MS
import to.bitkit.ui.utils.ScreenDeepLinks
import to.bitkit.usecases.FormatMoneyValue
import to.bitkit.usecases.RefreshContactPaykitReceiversUseCase
import to.bitkit.utils.AppError
import to.bitkit.utils.Bip21Utils
import to.bitkit.utils.Logger
import to.bitkit.utils.NetworkValidationHelper
import to.bitkit.utils.ServiceError
import to.bitkit.utils.jsonLogOf
import to.bitkit.utils.timedsheets.TimedSheetManager
import to.bitkit.utils.timedsheets.sheets.AppUpdateTimedSheet
import to.bitkit.utils.timedsheets.sheets.BackupTimedSheet
import to.bitkit.utils.timedsheets.sheets.HighBalanceTimedSheet
import to.bitkit.utils.timedsheets.sheets.NotificationsTimedSheet
import to.bitkit.utils.timedsheets.sheets.QuickPayTimedSheet
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
@HiltViewModel
class AppViewModel @Inject constructor(
    connectivityRepo: ConnectivityRepo,
    healthRepo: HealthRepo,
    toastManagerProvider: @JvmSuppressWildcards (CoroutineScope) -> ToastQueueManager,
    timedSheetManagerProvider: @JvmSuppressWildcards (CoroutineScope) -> TimedSheetManager,
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val lightningRepo: LightningRepo,
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val walletRepo: WalletRepo,
    private val hwWalletRepo: HwWalletRepo,
    private val backupRepo: BackupRepo,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo,
    private val activityRepo: ActivityRepo,
    private val preActivityMetadataRepo: PreActivityMetadataRepo,
    private val blocktankRepo: BlocktankRepo,
    private val appUpdaterService: AppUpdaterService,
    private val notifyPaymentReceivedHandler: NotifyPaymentReceivedHandler,
    private val notifyChannelReadyHandler: NotifyChannelReadyHandler,
    private val cacheStore: CacheStore,
    private val quickPayRepo: QuickPayRepo,
    private val transferRepo: TransferRepo,
    private val migrationService: MigrationService,
    private val coreService: CoreService,
    private val nodeServiceFgState: NodeServiceFgState,
    private val pubkyRepo: PubkyRepo,
    private val publicPaykitRepo: PublicPaykitRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
    private val paykitPaymentRequestRepo: PaykitPaymentRequestRepo,
    private val paykitPaymentProofRepo: PaykitPaymentProofRepo,
    private val refreshContactPaykitReceivers: RefreshContactPaykitReceiversUseCase,
    private val samRockRepo: SamRockRepo,
    private val appUpdateSheet: AppUpdateTimedSheet,
    private val backupSheet: BackupTimedSheet,
    private val notificationsSheet: NotificationsTimedSheet,
    private val quickPaySheet: QuickPayTimedSheet,
    private val highBalanceSheet: HighBalanceTimedSheet,
    private val formatMoneyValue: FormatMoneyValue,
    private val widgetsRepo: WidgetsRepo,
) : ViewModel() {
    val healthState = healthRepo.healthState

    val isOnline = connectivityRepo.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectivityState.CONNECTED)

    var splashVisible by mutableStateOf(true)
        private set

    val isGeoBlocked = lightningRepo.lightningState.map { it.isGeoBlocked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val forceCloseRemainingDuration = transferRepo.forceCloseRemainingDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _sendUiState = MutableStateFlow(SendUiState())
    val sendUiState = _sendUiState.asStateFlow()

    private val quickPayRequestIds = AtomicLong(0L)
    private val _quickPayData = MutableStateFlow<QuickPayRequest?>(null)
    val quickPayData = _quickPayData.asStateFlow()

    private val scanMutex = Mutex()

    @Volatile
    private var scheduledScan: ScheduledScan? = null

    private val deferredScanLock = Any()
    private var deferredScan: DeferredScan? = null

    private val _sendEffect = MutableSharedFlow<SendEffect>(extraBufferCapacity = 1)
    val sendEffect = _sendEffect.asSharedFlow()
    private fun setSendEffect(effect: SendEffect) = viewModelScope.launch { _sendEffect.emit(effect) }

    private val _mainScreenEffect = MutableSharedFlow<MainScreenEffect>(extraBufferCapacity = 1)
    val mainScreenEffect = _mainScreenEffect.asSharedFlow()
    private fun mainScreenEffect(effect: MainScreenEffect) = viewModelScope.launch { _mainScreenEffect.emit(effect) }

    private val sendEvents = MutableSharedFlow<SendEvent>()
    private var amountContinuePending = false
    private var fundingSourceSwitchPending = false
    private var onchainSendRefreshJob: Job? = null

    fun setSendEvent(event: SendEvent) {
        when (event) {
            SendEvent.AmountContinue -> {
                if (amountContinuePending) return
                amountContinuePending = true
            }

            SendEvent.PaymentMethodSwitch -> {
                if (fundingSourceSwitchPending) return
                fundingSourceSwitchPending = true
            }

            else -> Unit
        }
        viewModelScope.launch { sendEvents.emit(event) }
    }

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated = _isAuthenticated.asStateFlow()

    private val _pendingScreenDeepLink = MutableStateFlow<Uri?>(null)
    val pendingScreenDeepLink = _pendingScreenDeepLink.asStateFlow()

    private val _showForgotPinSheet = MutableStateFlow(false)
    val showForgotPinSheet = _showForgotPinSheet.asStateFlow()

    private val _currentSheet: MutableStateFlow<Sheet?> = MutableStateFlow(null)
    val currentSheet = _currentSheet.asStateFlow()
    val pendingPaymentRequests = paykitPaymentRequestRepo.pendingRequests
    val paymentRequestHistory = paykitPaymentRequestRepo.paymentRequestHistory
    val eligiblePaymentRequestTargets = paykitPaymentRequestRepo.eligibleTargets
    val isCreatingPaymentRequest = paykitPaymentRequestRepo.isCreatingRequest
    private val _rejectingPaymentRequestIds = MutableStateFlow<Set<PaykitPaymentRequestId>>(emptySet())
    val rejectingPaymentRequestIds = _rejectingPaymentRequestIds.asStateFlow()
    private val _isAcceptingSubscription = MutableStateFlow(false)
    val isAcceptingSubscription = _isAcceptingSubscription.asStateFlow()
    private val _isRetryingInitialSubscriptionPayment = MutableStateFlow(false)
    val isRetryingInitialSubscriptionPayment = _isRetryingInitialSubscriptionPayment.asStateFlow()
    val subscriptions = paykitPaymentRequestRepo.subscriptions
    val pubkyContacts = pubkyRepo.contacts
    private var sheetTransitionJob: Job? = null
    private var paymentRequestSheetTransitionJob: Job? = null
    private var queuedPairingCodeRequestId: Long? = null
    private var receiveSheetContext: ReceiveSheetContext? = null

    private data class ReceiveSheetContext(
        val sheet: Sheet.Receive,
        val bolt11: String,
        val onchainAddress: String,
    )

    private val processedPaymentsLock = Any()
    private val processedPayments = mutableSetOf<String>()
    private val contactPaymentContextLock = Any()
    private var activeContactPaymentContext: ContactPaymentContext? = null
    private val pendingContactPaymentContexts = mutableMapOf<String, ContactPaymentContext>()
    private var requestedPaymentRequestId: PaykitPaymentRequestId? = null
    private var preparedContactPaymentContext: ContactPaymentContext? = null
    private var requestedPaymentRequestIdentity: String? = null
    private var requestedPaymentRequestTags: ImmutableList<String> = persistentListOf()
    private var uncertainOnchainPaymentRequestId: PaykitPaymentRequestId? = null
    private var isPresentingPaymentRequest = false
    private var paymentRequestPresentationGeneration = 0L
    private var activePaymentRequestPresentationGeneration: Long? = null
    private var paymentRequestIdentity: String? = null
    private var isPaymentRequestIdentityActivating = false
    private var isSubmittingPaymentRequest = false
    private var paykitPaymentRequestPollingJob: Job? = null
    private var initialPaykitPaymentRequestPollingJob: Job? = null
    private val paymentRequestPresentationRetryAttempts = mutableMapOf<PaykitPaymentRequestId, Int>()
    private val paymentRequestPresentationRetryJobs = mutableMapOf<PaykitPaymentRequestId, Job>()
    private val timedSheetManager = timedSheetManagerProvider(viewModelScope).apply {
        registerSheet(appUpdateSheet)
        registerSheet(backupSheet)
        registerSheet(notificationsSheet)
        registerSheet(quickPaySheet)
        registerSheet(highBalanceSheet)
    }
    private var isCompletingMigration = false
    private var addressValidationJob: Job? = null
    private var lastPrivatePaykitContactKeys: Set<String> = emptySet()
    private val isPaykitEnabled = settingsStore.isPaykitEnabled
        .map { PaykitFeatureFlags.isUiEnabled(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowForgotPin(value: Boolean) {
        _showForgotPinSheet.value = value
    }

    fun setIsAuthenticated(value: Boolean) {
        _isAuthenticated.value = value
        if (value) flushDeferredScan()
    }

    val pinAttemptsRemaining = keychain.pinAttemptsRemaining()
        .map { attempts -> attempts ?: Env.PIN_ATTEMPTS }
        .stateIn(viewModelScope, SharingStarted.Lazily, Env.PIN_ATTEMPTS)

    fun addTagToSelected(newTag: String) {
        _sendUiState.update {
            it.copy(
                selectedTags = (it.selectedTags + newTag).distinct().toImmutableList()
            )
        }
        viewModelScope.launch {
            settingsStore.addLastUsedTag(newTag)
        }
    }

    fun removeTag(tag: String) {
        _sendUiState.update {
            it.copy(
                selectedTags = it.selectedTags.filterNot { tagItem -> tagItem == tag }.toImmutableList()
            )
        }
    }

    init {
        viewModelScope.launch {
            ToastEventBus.events.collect {
                toast(it)
            }
        }
        viewModelScope.launch {
            // Delays are required for auth check on launch functionality
            delay(AUTH_CHECK_INITIAL_DELAY_MS)
            resetIsAuthenticatedState()
            delay(AUTH_CHECK_SPLASH_DELAY_MS)
            splashVisible = false
        }
        viewModelScope.launch {
            lightningRepo.updateGeoBlockState()
        }
        viewModelScope.launch {
            quickPayRepo.unhandledFailures.collect {
                notifyPaymentFailed((it as? QuickPayPaymentFailedError)?.reason)
            }
        }
        viewModelScope.launch {
            hwWalletRepo.receivedTxs.collect { tx ->
                showTransactionSheet(
                    NewTransactionSheetDetails(
                        type = NewTransactionSheetType.ONCHAIN,
                        direction = NewTransactionSheetDirection.RECEIVED,
                        paymentHashOrTxId = tx.txid,
                        activityId = tx.txid,
                        activityWalletId = tx.walletId,
                        sats = tx.sats.toLong(),
                    ),
                )
            }
        }
        viewModelScope.launch {
            hwWalletRepo.pairingCodeRequestId.collect { requestId ->
                if (requestId != null) {
                    showPairingCodeSheet(requestId)
                } else {
                    queuedPairingCodeRequestId = null
                    val shouldFlush = _currentSheet.value is Sheet.Hardware &&
                        (_currentSheet.value as? Sheet.Hardware)?.route is HardwareRoute.PairCode
                    _currentSheet.update { sheet ->
                        if (sheet is Sheet.Hardware && sheet.route is HardwareRoute.PairCode) null else sheet
                    }
                    if (shouldFlush) flushDeferredScan()
                }
            }
        }
        viewModelScope.launch {
            widgetsRepo.refreshEnabledWidgets()
        }
        viewModelScope.launch {
            timedSheetManager.currentSheet.collect { sheetType ->
                if (sheetType != null) {
                    if (!isHighPrioritySheet(_currentSheet.value)) {
                        showSheet(Sheet.TimedSheet(sheetType))
                    }
                } else {
                    val shouldFlush = _currentSheet.value is Sheet.TimedSheet
                    _currentSheet.update { current ->
                        if (current is Sheet.TimedSheet) null else current
                    }
                    if (shouldFlush) flushDeferredScan()
                }
            }
        }
        viewModelScope.launch {
            pubkyRepo.sessionRestorationFailed.collect { failed ->
                if (failed) {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__session_expired),
                    )
                    pubkyRepo.clearSessionRestorationFailed()
                }
            }
        }
        observeReceiveSheetInvoice()
        observeLdkNodeEvents()
        observeLightningUsableChannels()
        observePublicPaykitEndpoints()
        observePublicPaykitInvoiceExpiry()
        observePrivatePaykitContacts()
        observePaykitPaymentRequestConnectivity()
        observeInitialPaykitLinkBursts()
        observeIncomingPaykitPaymentRequests()
        observePaykitOnchainPaymentResolution()
        observeSendEvents()
        viewModelScope.launch {
            checkCriticalAppUpdate()
        }
        viewModelScope.launch {
            migrationService.isShowingMigrationLoading.collect { isShowing ->
                if (isShowing) {
                    @Suppress("SwallowedException")
                    try {
                        withTimeout(MIGRATION_LOADING_TIMEOUT_MS) {
                            migrationService.isShowingMigrationLoading.first { !it }
                        }
                    } catch (e: TimeoutCancellationException) {
                        val timeoutSecs = MIGRATION_LOADING_TIMEOUT_MS / 1000
                        Logger.warn("Migration loading timeout (${timeoutSecs}s), dismissing", context = TAG)
                        migrationService.setShowingMigrationLoading(false)
                    }
                } else {
                    if (migrationService.needsPostMigrationSync()) {
                        ToastEventBus.send(
                            type = Toast.ToastType.WARNING,
                            title = context.getString(R.string.migration__network_required_title),
                            description = context.getString(R.string.migration__network_required_msg),
                        )
                    }
                }
            }
        }
    }

    private fun observeLdkNodeEvents() {
        viewModelScope.launch {
            lightningRepo.nodeEventUpdates.collect { handleLdkEvent(it) }
        }
    }

    private fun observeReceiveSheetInvoice() {
        viewModelScope.launch {
            walletRepo.walletState
                .map { it.bolt11 }
                .distinctUntilChanged()
                .collect { bolt11 ->
                    if (bolt11.isEmpty()) return@collect
                    val receiveSheet = _currentSheet.value as? Sheet.Receive ?: return@collect
                    receiveSheetContext = receiveSheetContext
                        ?.takeIf { it.sheet === receiveSheet }
                        ?.copy(bolt11 = bolt11)
                        ?: ReceiveSheetContext(receiveSheet, bolt11, walletRepo.getOnchainAddress())
                }
        }
    }

    private fun observeLightningUsableChannels() {
        viewModelScope.launch {
            var hadUsableChannels = false
            lightningRepo.lightningState
                .map { state -> state.channels.any { it.isUsable } }
                .distinctUntilChanged()
                .collect { hasUsableChannels ->
                    if (hasUsableChannels && !hadUsableChannels) {
                        refreshPaykitEndpointsAfterChannelAvailabilityChanged(
                            reason = "channel usable",
                            forceRefreshLightning = true,
                        )
                    }
                    hadUsableChannels = hasUsableChannels
                }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observePublicPaykitEndpoints() {
        viewModelScope.launch {
            walletRepo.walletState
                .map { it.onchainAddress }
                .distinctUntilChanged()
                .debounce(PUBLIC_PAYKIT_SYNC_DEBOUNCE)
                .collect { refreshPublicPaykitEndpointsIfEnabled() }
        }
    }

    private fun observePublicPaykitInvoiceExpiry() {
        viewModelScope.launch {
            settingsStore.data
                .map { it.sharesPublicPaykitEndpoints to it.publicPaykitBolt11ExpiresAtMillis }
                .distinctUntilChanged()
                .collectLatest { (sharesPublicPaykitEndpoints, expiresAtMillis) ->
                    if (!sharesPublicPaykitEndpoints || expiresAtMillis <= 0) return@collectLatest

                    val refreshAtMillis = expiresAtMillis - PUBLIC_PAYKIT_BOLT11_REFRESH_WINDOW.inWholeMilliseconds
                    val delayMillis = (refreshAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
                    delay(delayMillis.milliseconds)
                    refreshPublicPaykitEndpointsIfEnabled()
                }
        }
    }

    fun refreshPublicPaykitEndpoints() {
        viewModelScope.launch { refreshPublicPaykitEndpointsIfEnabled() }
    }

    fun refreshPrivatePaykitEndpoints() {
        viewModelScope.launch { refreshPrivatePaykitEndpointsIfEnabled("foreground") }
    }

    private suspend fun refreshPublicPaykitEndpointsIfEnabled(forceRefreshLightning: Boolean = false) {
        val settings = settingsStore.data.first()
        if (!isPaykitEnabled.value || !settings.sharesPublicPaykitEndpoints) return

        val onchainAddress = walletRepo.walletState.value.onchainAddress
        if (onchainAddress.isBlank() && !lightningRepo.canReceive()) return

        publicPaykitRepo.syncCurrentPublishedEndpoints(forceRefreshLightning = forceRefreshLightning)
            .onFailure { Logger.warn("Failed to refresh public Paykit endpoints", it, context = TAG) }
    }

    private fun observePrivatePaykitContacts() {
        viewModelScope.launch {
            combine(
                pubkyRepo.publicKey,
                pubkyRepo.contacts,
                pubkyRepo.contactsLoadVersion,
                settingsStore.isPaykitEnabled.map { PaykitFeatureFlags.isUiEnabled(it) },
            ) { publicKey, contacts, contactsLoadVersion, isPaykitEnabled ->
                PaykitContactSyncState(
                    publicKey = publicKey,
                    contactKeys = contacts.map { it.publicKey }.toSet(),
                    contactsLoaded = contactsLoadVersion > 0L,
                    isPaykitEnabled = isPaykitEnabled,
                )
            }
                .distinctUntilChanged()
                .collect(::synchronizePaykitContacts)
        }
    }

    private suspend fun synchronizePaykitContacts(state: PaykitContactSyncState) {
        if (!state.isPaykitEnabled || state.publicKey == null) {
            isPaymentRequestIdentityActivating = true
            lastPrivatePaykitContactKeys = emptySet()
            resetPaykitPresentationState(
                dismissActiveRequest = paymentRequestIdentity != null,
                preserveRequestedPaymentRequest = paymentRequestIdentity == null,
            )
            paymentRequestIdentity = null
            try {
                paykitPaymentRequestRepo.clear()
            } finally {
                val remainsUnavailable = !isPaykitEnabled.value || pubkyRepo.publicKey.value == null
                if (remainsUnavailable) isPaymentRequestIdentityActivating = false
            }
            return
        }

        val identityChanged = !PubkyPublicKeyFormat.matches(paymentRequestIdentity, state.publicKey)
        if (identityChanged) {
            paykitPaymentProofRepo.clearOnchainPaymentResolutions()
            resetPaykitPresentationState(
                dismissActiveRequest = paymentRequestIdentity != null,
                preserveRequestedPaymentRequest = paymentRequestIdentity == null,
            )
        }

        isPaymentRequestIdentityActivating = true
        try {
            paykitPaymentRequestRepo.activate(state.publicKey)
            if (!PubkyPublicKeyFormat.matches(pubkyRepo.publicKey.value, state.publicKey)) return
            paymentRequestIdentity = state.publicKey
            refreshPrivateOnlyPaykitReceiverMarker("contact sync")
            if (!state.contactsLoaded) return

            val removedKeys = lastPrivatePaykitContactKeys - state.contactKeys
            removedKeys.forEach {
                privatePaykitRepo.removeSavedContact(it)
                    .onFailure { error ->
                        Logger.warn(
                            "Failed to remove private Paykit contact '${PubkyPublicKeyFormat.redacted(it)}'",
                            error,
                            context = TAG,
                        )
                    }
            }

            privatePaykitRepo.prepareSavedContacts(state.contactKeys)
                .onFailure { Logger.warn("Failed to prepare private Paykit contacts", it, context = TAG) }
            privatePaykitRepo.pruneUnsavedContactState(state.contactKeys)
                .onFailure { Logger.warn("Failed to prune private Paykit contact state", it, context = TAG) }
            privatePaykitRepo.startInitialLinkBurst(state.contactKeys, "contact sync")
            if (!PubkyPublicKeyFormat.matches(pubkyRepo.publicKey.value, state.publicKey)) return
            refreshIncomingPaykitPaymentRequests()
            refreshPaymentRequestTargets(force = true)
            lastPrivatePaykitContactKeys = state.contactKeys
        } finally {
            if (PubkyPublicKeyFormat.matches(pubkyRepo.publicKey.value, state.publicKey)) {
                isPaymentRequestIdentityActivating = false
            }
        }
        presentNextIncomingPaykitPaymentRequest()
    }

    private suspend fun refreshPrivatePaykitEndpointsIfEnabled(
        reason: String,
        forceRefreshLightning: Boolean = false,
    ) {
        val contactKeys = pubkyRepo.contacts.value.map { it.publicKey }
        retryPendingPaykitEndpointRemoval(contactKeys, reason)

        if (!isPaykitEnabled.value) return

        refreshPrivateOnlyPaykitReceiverMarker(reason)
        privatePaykitRepo.reconcileReservedReceiveIndexes()
            .onFailure {
                Logger.warn("Failed to reconcile private Paykit receive indexes for '$reason'", it, context = TAG)
            }
        privatePaykitRepo.refreshKnownSavedContactEndpoints(reason, forceRefreshLightning = forceRefreshLightning)
        privatePaykitRepo.startInitialLinkBurst(contactKeys, reason)
        refreshIncomingPaykitPaymentRequests()
        refreshPaymentRequestTargets(force = true)
    }

    private fun observePaykitPaymentRequestConnectivity() {
        viewModelScope.launch {
            isOnline
                .drop(1)
                .filter { it == ConnectivityState.CONNECTED }
                .collect { refreshPrivatePaykitEndpointsIfEnabled("network restored") }
        }
    }

    private fun observeInitialPaykitLinkBursts() {
        viewModelScope.launch {
            privatePaykitRepo.initialLinkBurstStarted.collect { startInitialPaykitPaymentRequestPolling() }
        }
    }

    private fun observePaykitOnchainPaymentResolution() {
        viewModelScope.launch {
            paykitPaymentProofRepo.onchainPaymentResolutions.collect { resolutions ->
                resolutions.forEach(::handlePaykitOnchainPaymentResolution)
            }
        }
    }

    private fun handlePaykitOnchainPaymentResolution(resolution: PaykitOnchainPaymentProofResolution) {
        if (!PubkyPublicKeyFormat.matches(pubkyRepo.publicKey.value, resolution.identity)) return
        paykitPaymentProofRepo.consumeOnchainPaymentResolution(resolution)
        val resolvesCurrentPayment = uncertainOnchainPaymentRequestId == resolution.requestId
        if (!resolvesCurrentPayment) {
            synchronizeResolvedPaykitOnchainPayment(resolution, updateSendDetails = false)
            return
        }
        uncertainOnchainPaymentRequestId = null
        if (
            _currentSheet.value !is Sheet.Send ||
            _sendUiState.value.incomingPaymentRequestId != resolution.requestId
        ) {
            synchronizeResolvedPaykitOnchainPayment(resolution, updateSendDetails = false)
            return
        }
        onSendSuccess(
            NewTransactionSheetDetails(
                type = NewTransactionSheetType.ONCHAIN,
                direction = NewTransactionSheetDirection.SENT,
                paymentHashOrTxId = resolution.transactionId,
                sats = _sendUiState.value.amount.toLong(),
                isLoadingDetails = true,
            )
        )
        synchronizeResolvedPaykitOnchainPayment(resolution, updateSendDetails = true)
    }

    private fun synchronizeResolvedPaykitOnchainPayment(
        resolution: PaykitOnchainPaymentProofResolution,
        updateSendDetails: Boolean,
    ) {
        viewModelScope.launch {
            lightningRepo.sync()
            activityRepo.syncActivities()
            activityRepo.setContact(
                contactPublicKey = resolution.requestId.counterparty,
                forPaymentId = resolution.transactionId,
                syncLdkPayments = false,
            ).onFailure {
                Logger.warn("Failed to associate a resolved Paykit payment with its contact", it, context = TAG)
            }
            if (updateSendDetails) {
                _successSendUiState.update { it.copy(isLoadingDetails = false) }
            }
        }
    }

    private suspend fun refreshIncomingPaykitPaymentRequests(): Boolean {
        if (!isPaykitEnabled.value || pubkyRepo.publicKey.value == null || !walletRepo.walletExists()) return false
        paykitPaymentProofRepo.reconcile()
        val previousRequests = paykitPaymentRequestRepo.pendingRequests.value
        return paykitPaymentRequestRepo.refresh().fold(
            onSuccess = {
                presentNextIncomingPaykitPaymentRequest()
                paykitPaymentRequestRepo.pendingRequests.value != previousRequests
            },
            onFailure = { false },
        )
    }

    private suspend fun refreshPaymentRequestTargets(force: Boolean = false) {
        if (!isPaykitEnabled.value || pubkyRepo.publicKey.value == null || !walletRepo.walletExists()) return
        paykitPaymentRequestRepo.refreshEligibleTargets(
            savedPublicKeys = pubkyRepo.contacts.value.map { it.publicKey },
            force = force,
        )
    }

    fun startPaykitPaymentRequestPolling() {
        if (paykitPaymentRequestPollingJob?.isActive == true) return

        paykitPaymentRequestPollingJob = viewModelScope.launch {
            var refreshIntervalIndex = 0
            while (true) {
                delay(PAYKIT_PAYMENT_REQUEST_REFRESH_INTERVALS[refreshIntervalIndex])
                privatePaykitRepo.refreshKnownSavedContactEndpoints("payment request polling")
                val requestsChanged = refreshIncomingPaykitPaymentRequests()
                refreshPaymentRequestTargets(force = true)
                refreshIntervalIndex = if (requestsChanged) {
                    0
                } else {
                    (refreshIntervalIndex + 1).coerceAtMost(PAYKIT_PAYMENT_REQUEST_REFRESH_INTERVALS.lastIndex)
                }
            }
        }
        startInitialPaykitPaymentRequestPolling()
    }

    fun synchronizeSubscriptionNotifications(enabled: Boolean) {
        paykitPaymentRequestRepo.synchronizeSubscriptionNotifications(enabled)
    }

    fun onPaykitSubscriptionNotificationTapped(
        payerIdentity: String?,
        requestId: PaykitPaymentRequestId? = null,
    ) {
        if (payerIdentity != null && requestId != null) {
            val currentIdentity = pubkyRepo.publicKey.value
            if (currentIdentity != null && !PubkyPublicKeyFormat.matches(currentIdentity, payerIdentity)) return
            invalidatePaymentRequestPresentation()
            requestedPaymentRequestId = requestId
            requestedPaymentRequestIdentity = payerIdentity
            requestedPaymentRequestTags = persistentListOf()
        }
        viewModelScope.launch {
            refreshIncomingPaykitPaymentRequests()
        }
    }

    fun stopPaykitPaymentRequestPolling() {
        paykitPaymentRequestPollingJob?.cancel()
        paykitPaymentRequestPollingJob = null
        initialPaykitPaymentRequestPollingJob?.cancel()
        initialPaykitPaymentRequestPollingJob = null
        clearPaymentRequestPresentationRetries()
    }

    private fun startInitialPaykitPaymentRequestPolling() {
        if (paykitPaymentRequestPollingJob?.isActive != true) return
        initialPaykitPaymentRequestPollingJob?.cancel()
        initialPaykitPaymentRequestPollingJob = viewModelScope.launch {
            refreshIncomingPaykitPaymentRequests()
            refreshPaymentRequestTargets()
            INITIAL_PAYKIT_SYNC_RETRY_DELAYS.forEach {
                delay(it)
                refreshIncomingPaykitPaymentRequests()
                refreshPaymentRequestTargets()
            }
        }
    }

    private fun observeIncomingPaykitPaymentRequests() {
        viewModelScope.launch {
            currentSheet.collect { sheet ->
                if (sheet == null) {
                    presentNextIncomingPaykitPaymentRequest()
                }
            }
        }
        viewModelScope.launch {
            paykitPaymentRequestRepo.pendingRequests.drop(1).collect { requests ->
                retainPaymentRequestPresentationState(requests)
                val activeRequest = activeIncomingPaymentRequest() ?: return@collect
                if (
                    !isSubmittingPaymentRequest &&
                    currentSheet.value is Sheet.Send &&
                    requests.none { it.id == activeRequest.id }
                ) {
                    hideSheet()
                }
            }
        }
    }

    fun onSheetVisible(sheet: Sheet?) {
        if (sheet is Sheet.Subscription && sheet.route is SubscriptionRoute.Review) {
            subscription(sheet.route.id)?.let { subscription ->
                viewModelScope.launch {
                    paykitPaymentRequestRepo.markSubscriptionProposalPresented(subscription)
                }
            }
            return
        }
        if (sheet !is Sheet.Send || currentSheet.value !is Sheet.Send) return
        val request = activeIncomingPaymentRequest() ?: return
        viewModelScope.launch {
            if (currentSheet.value !is Sheet.Send || activeIncomingPaymentRequest()?.id != request.id) return@launch
            if (paykitPaymentRequestRepo.markPresented(request)) {
                paymentRequestPresentationGeneration++
                clearRequestedPaymentRequest()
                clearPaymentRequestPresentationRetry(request.id)
            }
        }
    }

    private suspend fun presentNextIncomingPaykitPaymentRequest() {
        if (isPresentingPaymentRequest || isPaymentRequestPresentationBlocked()) return
        if (requestedPaymentRequestId == null) {
            paykitPaymentRequestRepo.automaticSubscriptionProposals().firstOrNull()?.let {
                showSheet(Sheet.Subscription(SubscriptionRoute.Review(it.id)))
                return
            }
        }
        val requests = paymentRequestsForPresentation() ?: return
        val generation = paymentRequestPresentationGeneration
        isPresentingPaymentRequest = true
        activePaymentRequestPresentationGeneration = generation
        var stopped = false
        try {
            for (request in requests) {
                if (presentIncomingPaymentRequestOrStop(request, generation)) {
                    stopped = true
                    break
                }
            }
        } finally {
            isPresentingPaymentRequest = false
            activePaymentRequestPresentationGeneration = null
        }

        if (
            stopped &&
            generation != paymentRequestPresentationGeneration &&
            !isPaymentRequestPresentationBlocked()
        ) {
            presentNextIncomingPaykitPaymentRequest()
        }
    }

    private fun paymentRequestsForPresentation(): List<PaykitPaymentRequest>? {
        val requestedId = requestedPaymentRequestId
        return if (requestedId == null) {
            paykitPaymentRequestRepo.automaticPendingRequests().filter { request ->
                !paykitPaymentRequestRepo.isProcessing(request) &&
                    paymentRequestPresentationRetryJobs[request.id]?.isActive != true
            }.takeIf { it.isNotEmpty() }
        } else {
            when {
                !requestedPaymentRequestTargetsCurrentIdentity() -> {
                    invalidatePaymentRequestPresentation()
                    clearRequestedPaymentRequest()
                    null
                }
                paymentRequestPresentationRetryJobs[requestedId]?.isActive == true -> null
                else -> paykitPaymentRequestRepo.pendingRequest(requestedId)?.let(::listOf) ?: run {
                    invalidatePaymentRequestPresentation()
                    clearRequestedPaymentRequest()
                    null
                }
            }
        }
    }

    private fun requestedPaymentRequestTargetsCurrentIdentity(): Boolean {
        val requestedIdentity = requestedPaymentRequestIdentity ?: return true
        val currentIdentity = pubkyRepo.publicKey.value ?: return false
        return PubkyPublicKeyFormat.matches(currentIdentity, requestedIdentity)
    }

    private suspend fun presentIncomingPaymentRequestOrStop(
        request: PaykitPaymentRequest,
        generation: Long,
    ): Boolean {
        val result = privatePaykitRepo.beginPaymentRequest(request).getOrNull()
        if (!isCurrentPaymentRequestPresentation(request, generation) || isPaymentRequestPresentationBlocked()) {
            return true
        }
        if (!paykitPaymentRequestRepo.isPending(request)) {
            if (requestedPaymentRequestId == request.id) {
                invalidatePaymentRequestPresentation()
                clearRequestedPaymentRequest()
            }
            return false
        }
        if (result !is PublicPaykitPaymentResult.Opened) {
            deferPaymentRequestPresentation(request)
            return false
        }

        openContactPayment(
            paymentRequest = result.paymentRequest,
            publicKey = request.counterparty,
            privatePaymentContext = result.privatePaymentContext,
            incomingPaymentRequest = request,
            selectedTags = requestedPaymentRequestTags.takeIf { requestedPaymentRequestId == request.id }
                ?: persistentListOf(),
        )
        return true
    }

    private fun isCurrentPaymentRequestPresentation(request: PaykitPaymentRequest, generation: Long): Boolean =
        activePaymentRequestPresentationGeneration == generation &&
            paymentRequestPresentationGeneration == generation &&
            !paykitPaymentRequestRepo.isProcessing(request) &&
            (requestedPaymentRequestId?.let { it == request.id } ?: true)

    private fun deferPaymentRequestPresentation(request: PaykitPaymentRequest) {
        val attempt = paymentRequestPresentationRetryAttempts[request.id] ?: 0
        val retryDelay = PAYKIT_PAYMENT_REQUEST_PRESENTATION_RETRY_DELAYS.getOrNull(attempt)
            ?: if (requestedPaymentRequestId == request.id) {
                Logger.warn(
                    "Giving up requested payment request presentation after '${attempt + 1}' attempts",
                    context = TAG,
                )
                paymentRequestPresentationGeneration++
                clearRequestedPaymentRequest()
                showSheet(Sheet.PaymentRequests)
                viewModelScope.launch {
                    paykitPaymentRequestRepo.markPresented(request)
                }
                return
            } else {
                PAYKIT_PAYMENT_REQUEST_REFRESH_INTERVALS.last()
            }
        paymentRequestPresentationRetryAttempts[request.id] =
            (attempt + 1).coerceAtMost(PAYKIT_PAYMENT_REQUEST_PRESENTATION_RETRY_DELAYS.size)
        if (attempt == 0 && requestedPaymentRequestId == request.id) {
            toast(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.wallet__payment_request),
                description = context.getString(R.string.wallet__payment_request_waiting_for_details),
            )
        }
        paymentRequestPresentationRetryJobs.remove(request.id)?.cancel()
        paymentRequestPresentationRetryJobs[request.id] = viewModelScope.launch {
            delay(retryDelay)
            paymentRequestPresentationRetryJobs.remove(request.id)
            presentNextIncomingPaykitPaymentRequest()
        }
    }

    private fun retainPaymentRequestPresentationState(requests: List<PaykitPaymentRequest>) {
        val requestIds = requests.mapTo(mutableSetOf()) { it.id }
        paymentRequestPresentationRetryAttempts.keys.retainAll(requestIds)
        paymentRequestPresentationRetryJobs.keys.filter { it !in requestIds }.forEach {
            paymentRequestPresentationRetryJobs.remove(it)?.cancel()
        }
        if (requestedPaymentRequestId?.let { it !in requestIds } == true) {
            invalidatePaymentRequestPresentation()
            clearRequestedPaymentRequest()
        }
    }

    private fun clearPaymentRequestPresentationRetry(requestId: PaykitPaymentRequestId) {
        paymentRequestPresentationRetryAttempts.remove(requestId)
        paymentRequestPresentationRetryJobs.remove(requestId)?.cancel()
    }

    private fun clearPaymentRequestPresentationRetries() {
        paymentRequestPresentationRetryJobs.values.forEach { it.cancel() }
        paymentRequestPresentationRetryJobs.clear()
        paymentRequestPresentationRetryAttempts.clear()
    }

    private fun resetPaykitPresentationState(
        dismissActiveRequest: Boolean,
        preserveRequestedPaymentRequest: Boolean,
    ) {
        invalidatePaymentRequestPresentation(dismissActiveRequest)
        clearPaymentRequestPresentationRetries()
        if (!preserveRequestedPaymentRequest) clearRequestedPaymentRequest()
        paymentRequestSheetTransitionJob?.cancel()
        paymentRequestSheetTransitionJob = null
    }

    private fun clearRequestedPaymentRequest() {
        requestedPaymentRequestId = null
        requestedPaymentRequestIdentity = null
        requestedPaymentRequestTags = persistentListOf()
    }

    private fun invalidatePaymentRequestPresentation(dismissActiveRequest: Boolean = false) {
        paymentRequestPresentationGeneration++
        scheduledScan
            ?.takeIf { it.contactPaymentContext?.incomingPaymentRequest != null }
            ?.job
            ?.cancel()
        synchronized(deferredScanLock) {
            if (deferredScan?.contactPaymentContext?.incomingPaymentRequest != null) {
                deferredScan = null
            }
        }
        if (dismissActiveRequest && activeIncomingPaymentRequest() != null) {
            if (currentSheet.value is Sheet.Send) {
                hideSheet(shouldFlushDeferredScan = false)
            } else {
                clearActiveContactPaymentContext()
            }
        }
    }

    private suspend fun refreshPrivateOnlyPaykitReceiverMarker(reason: String) {
        val settings = settingsStore.data.first()
        if (!settings.sharesPrivatePaykitEndpoints || settings.sharesPublicPaykitEndpoints) return
        if (pubkyRepo.publicKey.value == null) return

        publicPaykitRepo.syncLocalReceiverMarker()
            .onFailure {
                Logger.warn("Failed to refresh private Paykit receiver marker for '$reason'", it, context = TAG)
            }
    }

    private suspend fun retryPendingPaykitEndpointRemoval(contactKeys: Collection<String>, reason: String) {
        val settings = settingsStore.data.first()
        if (settings.publicPaykitCleanupPending) {
            if (settings.sharesPublicPaykitEndpoints) {
                publicPaykitRepo.syncCurrentPublishedEndpoints()
                    .onSuccess {
                        settingsStore.update { it.copy(publicPaykitCleanupPending = false) }
                    }
                    .onFailure {
                        Logger.warn("Failed to retry public Paykit endpoint sync for '$reason'", it, context = TAG)
                    }
            } else {
                publicPaykitRepo.syncPublishedEndpoints(publish = false)
                    .onSuccess {
                        settingsStore.update { it.copy(publicPaykitCleanupPending = false) }
                    }
                    .onFailure {
                        Logger.warn("Failed to retry public Paykit endpoint removal for '$reason'", it, context = TAG)
                    }
            }
        }

        privatePaykitRepo.retryPendingEndpointRemoval(contactKeys)
            .onFailure {
                Logger.warn("Failed to retry private Paykit endpoint removal for '$reason'", it, context = TAG)
            }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleLdkEvent(update: NodeEventUpdate) {
        val event = update.event
        if (!walletRepo.walletExists()) return
        Logger.debug("LDK-node event received in $TAG: ${jsonLogOf(event)}", context = TAG)

        val receiveSheetToClose = receiveSheetContext?.takeIf { context ->
            val matchesSettledRequest = when (event) {
                is Event.PaymentReceived -> update.settledReceiveInvoice?.bolt11 == context.bolt11
                is Event.OnchainTransactionReceived -> update.settledReceiveAddress?.address == context.onchainAddress
                else -> false
            }
            matchesSettledRequest &&
                _currentSheet.value === context.sheet
        }

        viewModelScope.launch {
            runCatching {
                when (event) {
                    is Event.BalanceChanged -> handleBalanceChanged()
                    is Event.ChannelClosed -> handleChannelClosed(event)
                    is Event.ChannelPending -> handleChannelPending()
                    is Event.ChannelReady -> handleChannelReady(event)
                    is Event.OnchainTransactionConfirmed -> handleOnchainTransactionConfirmed(event)
                    is Event.OnchainTransactionEvicted -> handleOnchainTransactionEvicted(event)
                    is Event.OnchainTransactionReceived -> handleOnchainTransactionReceived(event, receiveSheetToClose)
                    is Event.OnchainTransactionReorged -> handleOnchainTransactionReorged(event)
                    is Event.OnchainTransactionReplaced -> handleOnchainTransactionReplaced(event)
                    is Event.PaymentClaimable -> Unit
                    is Event.PaymentFailed -> handlePaymentFailed(event)
                    is Event.PaymentForwarded -> Unit
                    is Event.PaymentReceived -> handlePaymentReceived(event, receiveSheetToClose)
                    is Event.PaymentSuccessful -> handlePaymentSuccessful(event)
                    is Event.ProbeFailed -> Unit
                    is Event.ProbeSuccessful -> Unit
                    is Event.SpliceFailed -> Unit
                    is Event.SplicePending -> Unit
                    is Event.SyncCompleted -> handleSyncCompleted()
                    is Event.SyncProgress -> Unit
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Logger.error("LDK event handler error", e, context = TAG)
            }
        }
    }

    private suspend fun handleBalanceChanged() {
        walletRepo.syncBalances()
        transferRepo.syncTransferStates()
    }

    private suspend fun handleChannelReady(event: Event.ChannelReady) {
        refreshPaykitEndpointsAfterChannelAvailabilityChanged("channel ready")
        notifyChannelReady(event)
        delay(PAYKIT_CHANNEL_USABILITY_REFRESH_DELAY_MS)
        refreshPaykitEndpointsAfterChannelAvailabilityChanged(
            reason = "channel ready delayed",
            forceRefreshLightning = true,
        )
    }

    private suspend fun refreshPaykitEndpointsAfterChannelAvailabilityChanged(
        reason: String,
        forceRefreshLightning: Boolean = false,
    ) {
        transferRepo.syncTransferStates()
        walletRepo.syncBalances()
        refreshPublicPaykitEndpointsIfEnabled(forceRefreshLightning = forceRefreshLightning)
        refreshPrivatePaykitEndpointsIfEnabled(reason, forceRefreshLightning = forceRefreshLightning)
    }

    private suspend fun handleChannelPending() = transferRepo.syncTransferStates()

    private suspend fun handleChannelClosed(event: Event.ChannelClosed) {
        val reason = event.reason
        if (reason != null) {
            val (isCounterpartyClose, isForceClose) = classifyClosureReason(reason)
            if (isCounterpartyClose) {
                createTransferForCounterpartyClose(event.channelId, isForceClose)
                showSheet(Sheet.ConnectionClosed)
            }
        }
        transferRepo.syncTransferStates()
        walletRepo.syncBalances()
        refreshPublicPaykitEndpointsIfEnabled()
        refreshPrivatePaykitEndpointsIfEnabled("channel closed")
    }

    private suspend fun createTransferForCounterpartyClose(channelId: String, isForceClose: Boolean) {
        val transferType = if (isForceClose) TransferType.FORCE_CLOSE else TransferType.COOP_CLOSE

        val balances = lightningRepo.getBalancesAsync().getOrNull()
        val lightningBalance = balances?.lightningBalances?.find { it.channelId() == channelId }
        var channelBalance = lightningBalance?.amountSats() ?: 0uL

        if (channelBalance == 0uL) {
            val closedChannels = runCatching {
                coreService.activity.closedChannels(SortDirection.DESC)
            }.getOrNull()
            channelBalance = closedChannels
                ?.firstOrNull { it.channelId == channelId }
                ?.channelValueSats ?: 0uL
        }

        if (channelBalance > 0uL) {
            transferRepo.createTransfer(
                type = transferType,
                amountSats = channelBalance.toLong(),
                channelId = channelId,
                claimableAtHeight = lightningBalance?.claimableAtHeight(),
            )
        }
    }

    private fun classifyClosureReason(reason: ClosureReason): Pair<Boolean, Boolean> {
        return when (reason) {
            is ClosureReason.CounterpartyForceClosed -> true to true
            is ClosureReason.CommitmentTxConfirmed -> true to true
            is ClosureReason.CounterpartyInitiatedCooperativeClosure -> true to false
            is ClosureReason.CounterpartyCoopClosedUnfundedChannel -> true to false
            else -> false to false
        }
    }

    private suspend fun handleSyncCompleted() {
        val isShowingLoading = migrationService.isShowingMigrationLoading.value
        val isRestoringRemote = migrationService.isRestoringFromRNRemoteBackup.value
        val needsPostMigrationSync = migrationService.needsPostMigrationSync()
        val pendingPrune = settingsStore.data.first().pendingRestoreAddressTypePrune

        when {
            (isShowingLoading || needsPostMigrationSync) && !isCompletingMigration -> completeMigration()
            isRestoringRemote -> completeRNRemoteBackupRestore()
            pendingPrune -> {
                settingsStore.update { it.copy(pendingRestoreAddressTypePrune = false) }
                delay(POST_RESTORE_PRUNE_DELAY_MS)
                lightningRepo.pruneEmptyAddressTypesAfterRestore()
                walletRepo.debounceSyncByEvent()
            }

            !isShowingLoading && !needsPostMigrationSync && !isCompletingMigration -> walletRepo.debounceSyncByEvent()
            else -> Unit
        }

        privatePaykitRepo.reconcileReceivedPayments()
            .onFailure {
                Logger.warn("Failed to reconcile private Paykit invoices", it, context = TAG)
            }
        privatePaykitRepo.handleOnchainActivity()
            .onFailure {
                Logger.warn("Failed to reconcile private Paykit on-chain activity", it, context = TAG)
            }
    }

    private suspend fun completeRNRemoteBackupRestore() {
        val channelMigration = buildChannelMigrationIfAvailable()

        if (channelMigration != null) {
            lightningRepo.stop().onFailure {
                Logger.error("Failed to stop node during remote restore restart", it, context = TAG)
            }
            delay(REMOTE_RESTORE_NODE_RESTART_DELAY_MS)
            lightningRepo.start(channelMigration = channelMigration, shouldRetry = false)
                .onSuccess {
                    migrationService.consumePendingChannelMigration()
                    walletRepo.syncNodeAndWallet()
                    walletRepo.syncBalances()
                }
                .onFailure { e ->
                    Logger.error("Failed to restart node after remote restore: $e", e, context = TAG)
                }
        }

        lightningRepo.getPayments().onSuccess { activityRepo.syncLdkNodePayments(it) }
        migrationService.reapplyMetadataAfterSync()
        activityRepo.syncActivities()
        walletRepo.syncBalances()

        if (migrationService.canCleanupAfterMigration) {
            migrationService.cleanupAfterMigration()
            migrationService.setRestoringFromRNRemoteBackup(false)
            migrationService.setShowingMigrationLoading(false)
        } else {
            Logger.info("Post-migration sync incomplete (remote restore), will retry on next sync", context = TAG)
            migrationService.setShowingMigrationLoading(false)
        }
    }

    private fun buildChannelMigrationIfAvailable(): ChannelDataMigration? {
        val migration = migrationService.peekPendingChannelMigration() ?: return null
        return ChannelDataMigration(
            channelManager = migration.channelManager.map { it.toUByte() },
            channelMonitors = migration.channelMonitors.map { monitor -> monitor.map { it.toUByte() } },
        )
    }

    private suspend fun completeMigration() {
        if (isCompletingMigration) return
        isCompletingMigration = true

        runCatching {
            lightningRepo.getPayments().onSuccess { payments ->
                activityRepo.syncLdkNodePayments(payments)
            }.onFailure { e ->
                Logger.warn("Failed to get payments during migration: $e", e, context = TAG)
            }
            activityRepo.markAllUnseenActivitiesAsSeen()

            migrationService.consumePendingChannelMigration()

            walletRepo.syncNodeAndWallet()
                .onSuccess { finishMigrationSuccessfully() }
                .onFailure { e ->
                    Logger.warn("Sync failed during migration: $e", e, context = TAG)
                    finishMigrationWithFallbackSync()
                }
        }.onFailure { e ->
            Logger.error("Migration completion error: $e", e, context = TAG)
            finishMigrationWithError()
        }.also {
            isCompletingMigration = false
        }
    }

    private suspend fun finishMigrationSuccessfully() {
        lightningRepo.getPayments().onSuccess { payments ->
            activityRepo.syncLdkNodePayments(payments)
        }
        transferRepo.syncTransferStates()
        migrationService.reapplyMetadataAfterSync()

        if (migrationService.canCleanupAfterMigration) {
            migrationService.cleanupAfterMigration()
            migrationService.setShowingMigrationLoading(false)
            delay(MIGRATION_AUTH_RESET_DELAY_MS)
            resetIsAuthenticatedStateInternal()
        } else {
            Logger.info("Post-migration sync incomplete, will retry on next sync", context = TAG)
            migrationService.setShowingMigrationLoading(false)
        }
    }

    private suspend fun finishMigrationWithFallbackSync() {
        walletRepo.syncBalances()
        lightningRepo.getPayments().onSuccess { payments ->
            activityRepo.syncLdkNodePayments(payments)
        }
        transferRepo.syncTransferStates()
        migrationService.reapplyMetadataAfterSync()

        if (migrationService.canCleanupAfterMigration) {
            migrationService.cleanupAfterMigration()
            migrationService.setShowingMigrationLoading(false)
            delay(MIGRATION_AUTH_RESET_DELAY_MS)
            resetIsAuthenticatedStateInternal()
        } else {
            Logger.info("Post-migration sync incomplete (fallback), will retry on next sync", context = TAG)
            migrationService.setShowingMigrationLoading(false)
        }
    }

    private suspend fun finishMigrationWithError() {
        migrationService.setShowingMigrationLoading(false)
        delay(MIGRATION_AUTH_RESET_DELAY_MS)
        resetIsAuthenticatedStateInternal()
        toast(
            type = Toast.ToastType.ERROR,
            title = "Migration Warning",
            description = "Migration completed but node restart failed. Please restart the app."
        )
    }

    private suspend fun handleOnchainTransactionConfirmed(event: Event.OnchainTransactionConfirmed) {
        activityRepo.handleOnchainTransactionConfirmed(event.txid, event.details)
    }

    private suspend fun handleOnchainTransactionEvicted(event: Event.OnchainTransactionEvicted) {
        activityRepo.handleOnchainTransactionEvicted(event.txid)
        notifyTransactionRemoved(event)
    }

    private suspend fun handleOnchainTransactionReceived(
        event: Event.OnchainTransactionReceived,
        receiveSheetToClose: ReceiveSheetContext?,
    ) {
        closeSettledReceiveSheet(receiveSheetToClose)
        val addresses = event.details.outputs.mapNotNull { it.scriptpubkeyAddress }
        val contactPublicKey = privatePaykitRepo.contactPublicKeyForPrivateOnchainAddresses(addresses)
        notifyPaymentReceived(event)
        if (contactPublicKey != null) {
            activityRepo.setContact(
                contactPublicKey = contactPublicKey,
                forPaymentId = event.txid,
                syncLdkPayments = false,
            )
        }
        privatePaykitRepo.handleOnchainActivity(addresses)
            .onFailure {
                Logger.warn("Failed to rotate private Paykit address for '${event.txid}'", it, context = TAG)
            }
    }

    private suspend fun handleOnchainTransactionReorged(event: Event.OnchainTransactionReorged) {
        activityRepo.handleOnchainTransactionReorged(event.txid)
        privatePaykitRepo.handleOnchainActivity()
            .onFailure {
                Logger.warn("Failed to refresh private Paykit after reorg", it, context = TAG)
            }
        notifyTransactionUnconfirmed()
    }

    private suspend fun handleOnchainTransactionReplaced(event: Event.OnchainTransactionReplaced) {
        // If the replaced transaction was just boosted via RBF from within the app, we already show a
        // dedicated boost success toast; suppress the generic "transaction replaced" toast to avoid
        // flakiness/noise (notably in E2E flows).
        val shouldSuppressReplacedToast = activityRepo
            .getOnchainActivityByTxId(event.txid)
            ?.let { it.isBoosted && it.txType == PaymentType.SENT } == true

        activityRepo.handleOnchainTransactionReplaced(event.txid, event.conflicts)
        privatePaykitRepo.handleOnchainActivity()
            .onFailure {
                Logger.warn("Failed to refresh private Paykit after replacement", it, context = TAG)
            }
        if (!shouldSuppressReplacedToast) {
            notifyTransactionReplaced(event)
        }
    }

    private suspend fun handlePaymentFailed(event: Event.PaymentFailed) {
        val outcome = quickPayRepo.signalCompletion(
            paymentId = event.paymentId,
            paymentHash = event.paymentHash,
            success = false,
            failureReason = event.reason,
        )
        val paymentHash = event.paymentHash ?: outcome.invoicePaymentHash ?: event.paymentId
        if (paymentHash != null) {
            viewModelScope.launch {
                paykitPaymentProofRepo.failLightningPayment(paymentHash)
                refreshIncomingPaykitPaymentRequests()
            }
            refreshPaymentActivity(paymentHash)
            if (pendingPaymentRepo.isPending(paymentHash)) {
                clearPendingContactPaymentContext(paymentHash)
                pendingPaymentRepo.resolve(PendingPaymentResolution.Failure(paymentHash, event.reason))
                if (shouldNotifyPendingResolution(paymentHash)) {
                    notifyPendingPaymentFailed()
                }
                return
            }
            if (outcome.sessionNotified) return
            if (closeActiveSendForFailedPayment(paymentHash, event.reason)) return
        }
        notifyPaymentFailed(event.reason)
    }

    private fun shouldNotifyPendingResolution(paymentHash: String): Boolean {
        if (isQuickPayHandling(paymentHash)) return false
        if (_quickPayData.value != null && _currentSheet.value !is Sheet.Send) return true
        return _currentSheet.value !is Sheet.Send || !pendingPaymentRepo.isActive(paymentHash)
    }

    private fun closeActiveSendForFailedPayment(paymentHash: String, reason: PaymentFailureReason?): Boolean {
        val activePaymentHash = _sendUiState.value.decodedInvoice?.paymentHash?.toHex()
        if (_currentSheet.value !is Sheet.Send || activePaymentHash != paymentHash) return false

        setSendEffect(
            SendEffect.NavigateToError(
                reason.toSendFailureDetails(
                    context = context,
                    paymentRequest = _sendUiState.value.currentLightningPaymentRequest(),
                )
            )
        )
        return true
    }

    private fun SendUiState.currentLightningPaymentRequest(): String? {
        if (payMethod != SendMethod.LIGHTNING) return null
        return decodedInvoice?.bolt11 ?: (lnurl as? LnurlParams.LnurlPay)?.data?.supportPaymentRequest()
    }

    private suspend fun handlePaymentReceived(
        event: Event.PaymentReceived,
        receiveSheetToClose: ReceiveSheetContext?,
    ) {
        event.paymentHash.let { paymentHash ->
            closeSettledReceiveSheet(receiveSheetToClose)
            activityRepo.notifyPaymentActivityChanged()
            privatePaykitRepo.contactPublicKeyForPrivateInvoicePaymentHash(paymentHash)?.let { publicKey ->
                activityRepo.setContact(
                    contactPublicKey = publicKey,
                    forPaymentId = paymentHash,
                    syncLdkPayments = false,
                )
            }
            publicPaykitRepo.refreshPublishedBolt11ForPayment(paymentHash)
                .onFailure {
                    Logger.warn(
                        "Failed to refresh public Paykit invoice for '$paymentHash'",
                        it,
                        context = TAG,
                    )
                }
            privatePaykitRepo.handleReceivedPayment(paymentHash)
                .onFailure {
                    Logger.warn(
                        "Failed to rotate private Paykit invoice for '$paymentHash'",
                        it,
                        context = TAG,
                    )
                }
        }
        notifyPaymentReceived(event)
    }

    private fun closeSettledReceiveSheet(context: ReceiveSheetContext?) {
        if (context == null) return
        if (receiveSheetContext !== context || _currentSheet.value !== context.sheet) return
        hideSheet()
    }

    private suspend fun handlePaymentSuccessful(event: Event.PaymentSuccessful) {
        val paymentHash = event.paymentHash
        viewModelScope.launch {
            paykitPaymentProofRepo.completeLightningPayment(paymentHash, event.paymentPreimage)
            refreshIncomingPaykitPaymentRequests()
        }
        val isQuickPay = quickPayRepo.signalCompletion(
            paymentId = event.paymentId,
            paymentHash = paymentHash,
            success = true,
            feePaidMsat = event.feePaidMsat,
        ).wasQuickPay
        refreshPaymentActivity(paymentHash)
        if (!pendingPaymentRepo.isPending(paymentHash)) {
            notifyPaymentSentOnLightning(event)
            return
        }
        syncContactForActivity(paymentHash)
        val amountWithFeeSats = quickPaySettledAmountSats(paymentHash, isQuickPay, event.feePaidMsat)
        pendingPaymentRepo.resolve(
            PendingPaymentResolution.Success(
                paymentHash = paymentHash,
                amountWithFeeSats = amountWithFeeSats,
            ),
        )
        if (shouldNotifyPendingResolution(paymentHash)) {
            notifyPendingPaymentSucceeded()
        }
    }

    private fun isQuickPayHandling(paymentHash: String): Boolean {
        if (_quickPayData.value == null || _currentSheet.value !is Sheet.Send) return false
        return _sendUiState.value.decodedInvoice?.paymentHash?.toHex() == paymentHash
    }

    private suspend fun refreshPaymentActivity(paymentHash: String) {
        runSuspendCatching { activityRepo.handlePaymentEvent(paymentHash) }.onFailure {
            Logger.warn("Failed to refresh payment activity for '$paymentHash'", it, context = TAG)
        }
    }

    private suspend fun quickPaySettledAmountSats(
        paymentHash: String,
        isQuickPay: Boolean,
        feePaidMsat: ULong?,
    ): Long? {
        if (!isQuickPay) return null
        val principal = (
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHash,
                type = ActivityFilter.LIGHTNING,
                txType = PaymentType.SENT,
                retry = true,
            ).getOrNull() as? Activity.Lightning
            )?.v1?.value
        return principal?.let {
            (it.safe() + msatFloorOf(feePaidMsat ?: 0u).safe()).toLong()
        }
    }

    // region Notifications

    private suspend fun notifyChannelReady(event: Event.ChannelReady) {
        val command = NotifyChannelReady.Command(event = event)
        val result = notifyChannelReadyHandler(command).getOrNull()
        if (result is NotifyChannelReady.Result.ShowSheet) {
            showTransactionSheet(result.sheet)
            return
        }
        if (result is NotifyChannelReady.Result.Duplicate) return
        toast(
            type = Toast.ToastType.LIGHTNING,
            title = context.getString(R.string.lightning__channel_opened_title),
            description = context.getString(R.string.lightning__channel_opened_msg),
            testTag = "SpendingBalanceReadyToast",
        )
    }

    private suspend fun notifyTransactionRemoved(event: Event.OnchainTransactionEvicted) {
        if (activityRepo.wasTransactionReplaced(event.txid)) return
        toast(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.wallet__toast_transaction_removed_title),
            description = context.getString(R.string.wallet__toast_transaction_removed_description),
            testTag = "TransactionRemovedToast",
        )
    }

    private suspend fun notifyPaymentReceived(event: Event) {
        if (migrationService.isShowingMigrationLoading.value || migrationService.needsPostMigrationSync()) {
            return
        }
        val command = NotifyPaymentReceived.Command.from(event) ?: return
        val result = notifyPaymentReceivedHandler(command).getOrNull()
        if (result !is NotifyPaymentReceived.Result.ShowSheet) return
        notifyPaymentReceivedHandler.present(command) { showTransactionSheet(result.sheet) }
    }

    private fun notifyTransactionUnconfirmed() = toast(
        type = Toast.ToastType.WARNING,
        title = context.getString(R.string.wallet__toast_transaction_unconfirmed_title),
        description = context.getString(R.string.wallet__toast_transaction_unconfirmed_description),
        testTag = "TransactionUnconfirmedToast",
    )

    private suspend fun notifyTransactionReplaced(event: Event.OnchainTransactionReplaced) {
        val isReceive = activityRepo.isReceivedTransaction(event.txid)
        toast(
            type = Toast.ToastType.INFO,
            title = when (isReceive) {
                true -> R.string.wallet__toast_received_transaction_replaced_title
                else -> R.string.wallet__toast_transaction_replaced_title
            }.let { context.getString(it) },
            description = when (isReceive) {
                true -> R.string.wallet__toast_received_transaction_replaced_description
                else -> R.string.wallet__toast_transaction_replaced_description
            }.let { context.getString(it) },
            testTag = when (isReceive) {
                true -> "ReceivedTransactionReplacedToast"
                else -> "TransactionReplacedToast"
            },
        )
    }

    private fun notifyPendingPaymentSucceeded() = PendingPaymentNotification.success(context).let {
        toast(
            type = Toast.ToastType.LIGHTNING,
            title = it.title,
            description = it.body,
            testTag = "PendingPaymentSucceededToast",
        )
    }

    private fun notifyPendingPaymentFailed() = PendingPaymentNotification.error(context).let {
        toast(
            type = Toast.ToastType.ERROR,
            title = it.title,
            description = it.body,
            testTag = "PendingPaymentFailedToast",
        )
    }

    private fun notifyPaymentFailed(reason: PaymentFailureReason? = null) = toast(
        type = Toast.ToastType.ERROR,
        title = context.getString(R.string.wallet__toast_payment_failed_title),
        description = reason.toUserMessage(context),
        testTag = "PaymentFailedToast",
    )

    private suspend fun notifyPaymentSentOnLightning(event: Event.PaymentSuccessful): Result<Activity> {
        val paymentHash = event.paymentHash
        // TODO Temporary solution while LDK node doesn't return the sent value in the event
        return activityRepo.findActivityByPaymentId(
            paymentHashOrTxId = paymentHash,
            type = ActivityFilter.LIGHTNING,
            txType = PaymentType.SENT,
            retry = true
        ).onSuccess { activity ->
            onSendSuccess(
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.LIGHTNING,
                    direction = NewTransactionSheetDirection.SENT,
                    paymentHashOrTxId = event.paymentHash,
                    sats = activity.totalValue().toLong(),
                ),
            )
        }.onFailure {
            Logger.warn("Failed displaying sheet for event: $event", it, context = TAG)
        }
    }

    // endregion

    // region send

    @Suppress("CyclomaticComplexMethod")
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
                    SendEvent.AmountContinue -> try {
                        onAmountContinue()
                    } finally {
                        amountContinuePending = false
                    }
                    SendEvent.PaymentMethodSwitch -> try {
                        onPaymentMethodSwitch()
                    } finally {
                        fundingSourceSwitchPending = false
                        _sendUiState.update { state -> state.copy(isFundingSourceLoading = false) }
                    }

                    is SendEvent.CoinSelectionContinue -> onCoinSelectionContinue(it.utxos)

                    is SendEvent.CommentChange -> onCommentChange(it.value)

                    SendEvent.SpeedAndFee -> {
                        if (_sendUiState.value.onchainFeeUi.estimates.isEmpty()) {
                            viewModelScope.launch {
                                refreshOnchainFeeUi()
                                setSendEffect(SendEffect.NavigateToFee)
                            }
                        } else {
                            setSendEffect(SendEffect.NavigateToFee)
                        }
                    }
                    SendEvent.SwipeToPay -> onSwipeToPay()
                    SendEvent.StartInitialSubscriptionPayment -> onStartInitialSubscriptionPayment()
                    SendEvent.CancelInitialSubscriptionPayment -> onCancelInitialSubscriptionPayment()
                    is SendEvent.ConfirmAmountWarning -> onConfirmAmountWarning(it.warning)
                    SendEvent.DismissAmountWarning -> onDismissAmountWarning()
                    SendEvent.EstimateMaxRoutingFee -> viewModelScope.launch {
                        estimateMaxAmountRoutingFee()
                    }

                    SendEvent.PayConfirmed -> onConfirmPay()
                    SendEvent.ClearPayConfirmation -> _sendUiState.update { s -> s.copy(shouldConfirmPay = false) }
                    SendEvent.BackToAmount -> setSendEffect(SendEffect.PopBack(SendRoute.Amount))
                    SendEvent.NavToAddress -> setSendEffect(SendEffect.NavigateToAddress)
                    SendEvent.Contacts -> setSendEffect(
                        if (isPaykitEnabled.value) SendEffect.NavigateToContacts else SendEffect.NavigateToComingSoon
                    )
                }
            }
        }
    }

    private val isMainScanner get() = currentSheet.value !is Sheet.Send

    private val activeHardwareWalletId: String?
        get() = (currentSheet.value as? Sheet.Send)?.hardwareWalletId

    private fun onEnterManuallyClick() {
        clearActiveContactPaymentContext()
        resetAddressInput()
        setSendEffect(SendEffect.NavigateToAddress)
    }

    private fun resetAddressInput() {
        addressValidationJob?.cancel()
        _sendUiState.update { state ->
            state.copy(
                addressInput = "",
                isAddressInputValid = false,
            )
        }
    }

    private fun onAddressChange(value: String) {
        val valueWithoutSpaces = value.removeSpaces()

        // Update text immediately, reset validity until validation completes
        _sendUiState.update {
            it.copy(
                addressInput = valueWithoutSpaces,
                isAddressInputValid = false,
            )
        }

        // Cancel pending validation
        addressValidationJob?.cancel()

        // Skip validation for empty input
        if (valueWithoutSpaces.isEmpty()) return

        if (valueWithoutSpaces.startsWith("$PUBKYAUTH_SCHEME://", ignoreCase = true)) return

        if (PubkyPublicKeyFormat.normalized(valueWithoutSpaces) != null) {
            if (isPaykitEnabled.value) {
                _sendUiState.update { it.copy(isAddressInputValid = true) }
            }
            return
        }

        // Start debounced validation
        addressValidationJob = viewModelScope.launch {
            delay(ADDRESS_VALIDATION_DEBOUNCE_MS)
            validateAddressWithFeedback(valueWithoutSpaces)
        }
    }

    private suspend fun validateAddressWithFeedback(input: String) = withContext(bgDispatcher) {
        // TODO Workaround for https://github.com/synonymdev/bitkit-core/issues/63
        if (Bip21Utils.isDuplicatedBip21(input)) {
            showAddressValidationError(
                titleRes = R.string.other__scan_err_decoding,
                descriptionRes = R.string.other__scan__error__generic,
                testTag = "DuplicatedBip21Toast",
            )
            return@withContext
        }

        val scanResult = runCatching { coreService.decode(input.removeLightningSchemes()) }

        if (scanResult.isFailure) {
            showAddressValidationError(
                titleRes = R.string.other__scan_err_decoding,
                descriptionRes = R.string.other__scan__error__generic,
                testTag = "InvalidAddressToast",
            )
            return@withContext
        }

        when (val decoded = scanResult.getOrNull()) {
            is Scanner.OnChain -> validateOnChainAddress(decoded.invoice)
            is Scanner.Lightning -> if (activeHardwareWalletId == null) {
                validateLightningInvoice(decoded.invoice)
            } else {
                showHardwareOnchainOnlyValidationError()
            }
            else -> if (activeHardwareWalletId == null) {
                _sendUiState.update { it.copy(isAddressInputValid = true) }
            } else {
                showHardwareOnchainOnlyValidationError()
            }
        }
    }

    private suspend fun validateLightningInvoice(invoice: LightningInvoice) {
        if (invoice.isExpired) {
            showAddressValidationError(
                titleRes = R.string.other__scan_err_decoding,
                descriptionRes = R.string.other__scan__error__expired,
                testTag = "ExpiredLightningToast",
            )
            return
        }

        if (invoice.amountSatoshis > 0uL) {
            lightningRepo.syncState()
            if (!lightningRepo.canSend(invoice.amountSatoshis)) {
                val maxSendLightning = walletRepo.balanceState.value.maxSendLightningSats
                val shortfall = invoice.amountSatoshis.safe() - maxSendLightning.safe()
                showAddressValidationError(
                    titleRes = R.string.other__pay_insufficient_spending,
                    descriptionRes = R.string.other__pay_insufficient_spending_amount_description,
                    descriptionArgs = mapOf("amount" to formatMoneyValue(shortfall)),
                    testTag = "InsufficientSpendingToast",
                )
                return
            }
        }

        _sendUiState.update { it.copy(isAddressInputValid = true) }
    }

    private suspend fun validateOnChainAddress(invoice: OnChainInvoice) {
        val validatedAddress = runCatching { coreService.validateBitcoinAddress(invoice.address) }
            .getOrElse {
                showAddressValidationError(
                    titleRes = R.string.other__scan_err_decoding,
                    descriptionRes = R.string.wallet__error_invalid_bitcoin_address,
                    testTag = "InvalidAddressToast",
                )
                return
            }

        if (NetworkValidationHelper.isNetworkMismatch(validatedAddress.network.toLdkNetwork(), Env.network)) {
            showAddressValidationError(
                titleRes = R.string.other__scan_err_decoding,
                descriptionRes = R.string.other__scan__error__generic,
                testTag = "InvalidAddressToast",
            )
            return
        }

        val hardwareWalletId = activeHardwareWalletId
        if (hardwareWalletId == null) {
            extractViableLightningInvoice(invoice.params)?.let { lnInvoice ->
                _sendUiState.update {
                    it.copy(
                        isAddressInputValid = true,
                        isUnified = true,
                        decodedInvoice = lnInvoice,
                        payMethod = SendMethod.LIGHTNING,
                    )
                }
                updateCanSwitchWallet()
                return
            }
        }

        val selectedMaxSendOnchain = hardwareWalletId?.let {
            hardwareMaxSpendable(it, invoice.address, _sendUiState.value.speed)
        } ?: walletRepo.balanceState.value.maxSendOnchainSats
        val maxSendOnchain = maximumAvailableOnchainSats(selectedMaxSendOnchain, hardwareWalletId)

        if (maxSendOnchain == 0uL) {
            showAddressValidationError(
                titleRes = R.string.other__pay_insufficient_savings,
                descriptionRes = R.string.other__pay_insufficient_savings_description,
                testTag = "InsufficientSavingsToast",
            )
            return
        }

        if (invoice.amountSatoshis > 0uL && invoice.amountSatoshis > maxSendOnchain) {
            val shortfall = invoice.amountSatoshis - maxSendOnchain
            showAddressValidationError(
                titleRes = R.string.other__pay_insufficient_savings,
                descriptionRes = R.string.other__pay_insufficient_savings_amount_description,
                descriptionArgs = mapOf("amount" to formatMoneyValue(shortfall)),
                testTag = "InsufficientSavingsToast",
            )
            return
        }

        _sendUiState.update { it.copy(isAddressInputValid = true) }
    }

    private fun showHardwareOnchainOnlyValidationError() {
        showAddressValidationError(
            titleRes = R.string.hardware__send_onchain_only_title,
            descriptionRes = R.string.hardware__send_onchain_only_text,
            testTag = "HardwareOnchainOnlyToast",
        )
    }

    private suspend fun extractViableLightningInvoice(params: Map<String, String>?): LightningInvoice? =
        params?.get("lightning")?.let { bolt11 ->
            runSuspendCatching { coreService.decode(bolt11) }.getOrNull()
                ?.let { it as? Scanner.Lightning }
                ?.invoice
                ?.takeIf { lnInv ->
                    if (lnInv.isExpired) {
                        Logger.debug(
                            "Lightning invoice expired in unified URI, defaulting to onchain-only",
                            context = TAG
                        )
                        return@takeIf false
                    }
                    lightningRepo.waitForUsableChannels()
                    val canSend = lightningRepo.canSend(lnInv.amountSatoshis.coerceAtLeast(1u))
                    if (!canSend) {
                        val nodeState = lightningRepo.lightningState.value.nodeLifecycleState
                        if (nodeState is NodeLifecycleState.Stopped) {
                            Logger.debug(
                                "Node stopped, optimistically including LN invoice in unified QR",
                                context = TAG,
                            )
                            return@takeIf true
                        }
                        Logger.debug(
                            "Cannot pay unified invoice using LN, defaulting to onchain-only",
                            context = TAG,
                        )
                    }
                    return@takeIf canSend
                }
        }

    private fun showAddressValidationError(
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int,
        descriptionArgs: Map<String, String> = emptyMap(),
        testTag: String? = null,
    ) {
        _sendUiState.update { it.copy(isAddressInputValid = false) }
        var description = context.getString(descriptionRes)
        descriptionArgs.forEach { (key, value) ->
            description = description.replace("{$key}", value)
        }
        toast(
            type = Toast.ToastType.ERROR,
            title = context.getString(titleRes),
            description = description,
            testTag = testTag,
        )
    }

    private fun launchScan(
        source: ScanSource,
        data: String,
        startDelay: Duration = Duration.ZERO,
        routePubkyKeys: Boolean = false,
        contactPaymentContext: ContactPaymentContext? = null,
        preserveUntilComplete: Boolean = false,
    ): Job? {
        if (!_isAuthenticated.value) {
            enqueueDeferredScan(
                source = source,
                data = data,
                startDelay = startDelay,
                routePubkyKeys = routePubkyKeys,
                contactPaymentContext = contactPaymentContext,
            )
            return null
        }

        val normalized = data.removeLightningSchemes()
        val scanId = scanLogId(data)

        val scheduled = scheduledScan
        val isSameActiveScan = normalized == scheduled?.normalizedInput &&
            scheduled.job.isActive &&
            (scheduled.contactPaymentContext == contactPaymentContext || contactPaymentContext == null)
        if (isSameActiveScan) {
            Logger.info("Skipping duplicate scan from '${source.label}': '$scanId'", context = TAG)
            return null
        }

        if (scheduled?.job?.isActive == true && scheduled.mustComplete) {
            enqueueDeferredScan(source, data, startDelay, routePubkyKeys, contactPaymentContext)
            return null
        }

        val previousJob = scheduled?.job
        val nextJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            scanMutex.withLock {
                setActiveContactPaymentContext(contactPaymentContext)
                if (startDelay > Duration.ZERO) delay(startDelay)
                handleScan(data, routePubkyKeys)
            }
        }
        val nextScheduledScan = ScheduledScan(
            job = nextJob,
            normalizedInput = normalized,
            contactPaymentContext = contactPaymentContext,
            mustComplete = preserveUntilComplete,
        )

        scheduledScan = nextScheduledScan
        nextJob.invokeOnCompletion {
            if (scheduledScan === nextScheduledScan) scheduledScan = null
            if (nextJob.isCancelled) return@invokeOnCompletion
            viewModelScope.launch { flushDeferredScan() }
        }

        Logger.debug("Starting scan from '${source.label}': '$scanId'", context = TAG)
        nextJob.start()
        previousJob?.let {
            Logger.info("Cancelling prior scan for new '${source.label}': '$scanId'", context = TAG)
            it.cancel()
        }
        return nextJob
    }

    private fun scanLogId(data: String): String {
        val scanLogInput = SamRockSetupRequest.sanitizedDescription(data.removeLightningSchemes()) ?: data
        return if (scanLogInput.length > SCAN_LOG_ID_MAX_LENGTH) {
            "${scanLogInput.take(SCAN_LOG_ID_AFFIX_LENGTH)}…${scanLogInput.takeLast(SCAN_LOG_ID_AFFIX_LENGTH)}"
        } else {
            scanLogInput
        }
    }

    private fun enqueueDeferredScan(
        source: ScanSource,
        data: String,
        startDelay: Duration,
        routePubkyKeys: Boolean,
        contactPaymentContext: ContactPaymentContext?,
    ) {
        val scanId = scanLogId(data)
        val normalized = data.removeLightningSchemes()
        synchronized(deferredScanLock) {
            val queued = deferredScan
            if (queued?.data?.removeLightningSchemes() == normalized) {
                if (contactPaymentContext != null) {
                    deferredScan = DeferredScan(
                        source = source,
                        data = data,
                        startDelay = startDelay,
                        routePubkyKeys = routePubkyKeys,
                        contactPaymentContext = contactPaymentContext,
                    )
                    return
                }
                Logger.info("Skipping duplicate queued scan from '${source.label}': '$scanId'", context = TAG)
                return
            }
            if (queued != null) {
                Logger.warn(
                    "Replacing deferred scan from '${queued.source.label}': '${scanLogId(queued.data)}'",
                    context = TAG,
                )
            }
            deferredScan = DeferredScan(
                source = source,
                data = data,
                startDelay = startDelay,
                routePubkyKeys = routePubkyKeys,
                contactPaymentContext = contactPaymentContext,
            )
        }
        Logger.info("Queuing '${source.label}' scan for deferred handling: '$scanId'", context = TAG)
    }

    private fun isScanPendingOrActive(): Boolean {
        if (scheduledScan?.job?.isActive == true) return true
        return synchronized(deferredScanLock) { deferredScan != null }
    }

    private fun isPaymentRequestPresentationBlocked() = isPaymentRequestIdentityActivating ||
        !_isAuthenticated.value ||
        currentSheet.value != null ||
        sheetTransitionJob?.isActive == true ||
        paymentRequestSheetTransitionJob?.isActive == true ||
        hasActiveContactPaymentContext() ||
        isScanPendingOrActive()

    private fun flushDeferredScan() {
        if (!_isAuthenticated.value) return
        if (scheduledScan?.job?.isActive == true) return
        if (sheetTransitionJob?.isActive == true) return
        if (_currentSheet.value != null) return

        val pending = synchronized(deferredScanLock) {
            deferredScan.also { deferredScan = null }
        } ?: run {
            viewModelScope.launch { presentNextIncomingPaykitPaymentRequest() }
            return
        }

        launchScan(
            source = pending.source,
            data = pending.data,
            startDelay = pending.startDelay,
            routePubkyKeys = pending.routePubkyKeys,
            contactPaymentContext = pending.contactPaymentContext,
            preserveUntilComplete = true,
        )
    }

    private fun onAddressContinue(data: String) {
        clearActiveContactPaymentContext()
        launchScan(source = ScanSource.ADDRESS_CONTINUE, data = data, routePubkyKeys = true)
    }

    private suspend fun onAmountChange(amount: ULong) {
        _sendUiState.update {
            it.copy(
                amount = amount,
                isAmountInputValid = validateAmount(amount),
                confirmedWarnings = persistentListOf(),
            )
        }
        updateCanSwitchWallet()
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
        onchainSendRefreshJob?.cancel()
        val previous = _sendUiState.value
        _sendUiState.update {
            it.copy(
                payMethod = SendMethod.ONCHAIN,
                speed = speed,
                onchainFeeUi = it.onchainFeeUi.copy(isLoading = true),
            )
        }
        setSendEffect(SendEffect.PopBack(SendRoute.Confirm))
        viewModelScope.launch {
            val shouldResetUtxos = when (settingsStore.data.first().coinSelectAuto) {
                true -> {
                    val currentSatsPerVByte = previous.feeRates?.getSatsPerVByteFor(previous.speed)
                    val newSatsPerVByte = previous.feeRates?.getSatsPerVByteFor(speed)
                    currentSatsPerVByte != newSatsPerVByte
                }

                else -> false
            }
            val wasHardwareMax = previous.hardwareWalletId != null &&
                previous.amount > 0uL &&
                previous.amount == previous.hardwareAvailableSats
            val hardwareAvailableSats = previous.hardwareWalletId?.let { walletId ->
                hardwareMaxSpendable(walletId, previous.address, speed)
            } ?: previous.hardwareAvailableSats
            _sendUiState.update {
                it.copy(
                    amount = if (wasHardwareMax) hardwareAvailableSats else it.amount,
                    hardwareAvailableSats = hardwareAvailableSats,
                    selectedUtxos = if (shouldResetUtxos) null else it.selectedUtxos,
                )
            }
            updateCanSwitchWallet()
            refreshOnchainSendIfNeeded() ?: updateOnchainFeeUi { it.copy(isLoading = false) }
        }
    }

    private fun updateCanSwitchWallet() {
        val state = _sendUiState.value
        val canSwitchWallet = if (state.hardwareWalletId != null || !state.isUnified) {
            false
        } else {
            val amount = state.amount
            val balance = walletRepo.balanceState.value
            amount > Defaults.dustLimit.toULong() &&
                amount <= balance.maxSendOnchainSats &&
                amount <= balance.maxSendLightningSats
        }
        _sendUiState.update {
            it.copy(
                canSwitchWallet = canSwitchWallet,
                canSwitchFundingSource = availableFundingSources(state).size > 1,
            )
        }
    }

    private suspend fun onPaymentMethodSwitch() {
        val current = _sendUiState.value
        val sources = availableFundingSources(current)
        if (sources.size < 2) return
        onchainSendRefreshJob?.cancel()
        val selected = current.selectedFundingSource()
        val selectedIndex = sources.indexOf(selected).takeIf { it >= 0 } ?: 0
        val nextSource = sources[(selectedIndex + 1) % sources.size]
        _sendUiState.update {
            it.copy(isFundingSourceLoading = nextSource is SendFundingSource.Hardware)
        }
        when (nextSource) {
            SendFundingSource.Spending -> {
                _sendUiState.update {
                    it.copy(
                        payMethod = SendMethod.LIGHTNING,
                        hardwareWalletId = null,
                        hardwareWalletName = null,
                        hardwareAvailableSats = 0uL,
                        lightningFeeSats = 0,
                        selectedUtxos = null,
                        confirmedWarnings = persistentListOf(),
                        isAmountInputValid = validateAmount(it.amount, SendMethod.LIGHTNING),
                    )
                }
                estimateLightningRoutingFeesIfNeeded()
            }

            SendFundingSource.Savings -> {
                _sendUiState.update {
                    it.copy(
                        payMethod = SendMethod.ONCHAIN,
                        hardwareWalletId = null,
                        hardwareWalletName = null,
                        hardwareAvailableSats = 0uL,
                        selectedUtxos = null,
                        confirmedWarnings = persistentListOf(),
                    )
                }
                refreshOnchainSendIfNeeded()?.join()
            }

            is SendFundingSource.Hardware -> selectHardwareFundingSource(nextSource.walletId, current)
        }
        _sendUiState.update {
            it.copy(
                isAmountInputValid = validateAmount(it.amount),
            )
        }
        updateCanSwitchWallet()
    }

    private suspend fun selectHardwareFundingSource(walletId: String, current: SendUiState) {
        val initialAvailable = hardwareEstimatedAvailable(walletId, current.speed)
        val walletName = hwWalletRepo.wallets.value.find { it.id == walletId }?.name
        _sendUiState.update {
            it.copy(
                payMethod = SendMethod.ONCHAIN,
                hardwareWalletId = walletId,
                hardwareWalletName = walletName,
                hardwareAvailableSats = initialAvailable,
                isAmountInputValid = it.amount > Defaults.dustLimit.toULong() && it.amount <= initialAvailable,
                selectedUtxos = null,
                confirmedWarnings = persistentListOf(),
                onchainFeeUi = it.onchainFeeUi.copy(isLoading = true),
            )
        }
        val available = hardwareMaxSpendable(walletId, current.address, current.speed)
        _sendUiState.update {
            if (it.hardwareWalletId != walletId) return@update it
            it.copy(hardwareAvailableSats = available)
        }
        refreshOnchainSendIfNeeded()?.join()
    }

    private suspend fun selectHardwareFundingSourceForAmount(amount: ULong): Boolean {
        val state = _sendUiState.value
        for (wallet in hwWalletRepo.wallets.value) {
            val available = hardwareMaxSpendable(wallet.id, state.address, state.speed)
            if (amount > available) continue
            _sendUiState.update {
                it.copy(
                    payMethod = SendMethod.ONCHAIN,
                    hardwareWalletId = wallet.id,
                    hardwareWalletName = wallet.name,
                    hardwareAvailableSats = available,
                    isAmountInputValid = true,
                    selectedUtxos = null,
                    confirmedWarnings = persistentListOf(),
                )
            }
            updateCanSwitchWallet()
            refreshOnchainSendIfNeeded()
            return true
        }
        return false
    }

    fun switchToLightning() {
        viewModelScope.launch {
            _sendUiState.update {
                it.copy(
                    payMethod = SendMethod.LIGHTNING,
                    hardwareWalletId = null,
                    hardwareWalletName = null,
                    hardwareAvailableSats = 0uL,
                    lightningFeeSats = 0,
                    isAmountInputValid = validateAmount(it.amount, SendMethod.LIGHTNING),
                    confirmedWarnings = persistentListOf(),
                )
            }
            estimateLightningRoutingFeesIfNeeded()
        }
    }

    private suspend fun onAmountContinue() {
        if (_sendUiState.value.isLoading) return
        _sendUiState.update {
            it.copy(
                selectedUtxos = null,
            )
        }

        if (
            _sendUiState.value.hardwareWalletId == null &&
            _sendUiState.value.payMethod != SendMethod.LIGHTNING &&
            !settingsStore.data.first().coinSelectAuto
        ) {
            setSendEffect(SendEffect.NavigateToCoinSelection)
            return
        }

        val lnurl = _sendUiState.value.lnurl
        if (lnurl is LnurlParams.LnurlPay) {
            val minSendable = lnurl.data.minSendableSat()
            if (_sendUiState.value.amount < minSendable) {
                toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.wallet__lnurl_pay__error_min__title),
                    description = context.getString(R.string.wallet__lnurl_pay__error_min__description)
                        .replace("{amount}", formatMoneyValue(minSendable)),
                    testTag = "LnurlPayAmountTooLowToast",
                )
                return
            }
        }
        if (lnurl is LnurlParams.LnurlWithdraw) {
            setSendEffect(SendEffect.NavigateToWithdrawConfirm)
            return
        }

        _sendUiState.update { it.copy(isLoading = true) }
        try {
            if (!prepareHardwareSendFee()) return
            refreshOnchainSendIfNeeded()
            estimateLightningRoutingFeesIfNeeded()
        } finally {
            _sendUiState.update { it.copy(isLoading = false) }
        }
        updateCanSwitchWallet()

        setSendEffect(SendEffect.NavigateToConfirm)
    }

    private suspend fun prepareHardwareSendFee(): Boolean {
        val state = _sendUiState.value
        val walletId = state.hardwareWalletId ?: return true
        val satsPerVByte = state.feeRates
            ?.getSatsPerVByteFor(state.speed)
            ?.toULong()
            ?.takeIf { it > 0uL }
            ?: HW_SEND_FALLBACK_SATS_PER_VBYTE
        val miningFeeSats = hwWalletRepo.estimateFundingMiningFee(
            walletId = walletId,
            address = state.address,
            sats = state.amount,
            satsPerVByte = satsPerVByte,
        ).getOrElse { error ->
            toast(error)
            return false
        }
        updateOnchainFeeUi {
            it.copy(
                rate = FeeRate.fromSpeed(state.speed),
                sats = miningFeeSats.toLong(),
                isLoading = false,
            )
        }
        return true
    }

    private suspend fun onCoinSelectionContinue(utxos: List<SpendableUtxo>) {
        _sendUiState.update {
            it.copy(selectedUtxos = utxos.toImmutableList())
        }
        refreshOnchainFeeUi()
        setSendEffect(SendEffect.NavigateToConfirm)
    }

    private suspend fun validateAmount(
        amount: ULong,
        payMethod: SendMethod = _sendUiState.value.payMethod,
    ): Boolean {
        if (amount == 0uL) return false

        return when (payMethod) {
            SendMethod.LIGHTNING -> {
                val maxSendable = maxSendableLightningSats()
                when (val lnurl = _sendUiState.value.lnurl) {
                    null -> amount <= maxSendable && lightningRepo.canSend(amount)
                    is LnurlParams.LnurlWithdraw -> amount <= lnurl.data.maxWithdrawableSat()
                    is LnurlParams.LnurlPay -> {
                        val maxSat = lnurl.data.maxSendableSat()
                        amount <= maxSat && amount <= maxSendable && lightningRepo.canSend(amount)
                    }
                }
            }

            SendMethod.ONCHAIN -> {
                val maxSendable = _sendUiState.value.hardwareAvailableSats
                    .takeIf { _sendUiState.value.hardwareWalletId != null }
                    ?: walletRepo.balanceState.value.maxSendOnchainSats
                amount > Defaults.dustLimit.toULong() && amount <= maxSendable
            }
        }
    }

    private fun maxSendableLightningSats(): ULong {
        val max = walletRepo.balanceState.value.maxSendLightningSats
        val fee = _sendUiState.value.estimatedRoutingFee
        return max.safe() - fee.safe()
    }

    private fun onPasteClick() {
        clearActiveContactPaymentContext()
        val data = context.getClipboardText()?.trim()
        if (data.isNullOrBlank()) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.wallet__send_clipboard_empty_title),
                description = context.getString(R.string.wallet__send_clipboard_empty_text),
            )
            return
        }
        launchScan(source = ScanSource.PASTE, data = data, routePubkyKeys = true)
    }

    private fun onScanClick() {
        clearActiveContactPaymentContext()
        setSendEffect(SendEffect.NavigateToScan)
    }

    fun onScanResult(
        data: String,
        startDelay: Duration = Duration.ZERO,
        routePubkyKeys: Boolean = false,
        contactPaymentContext: ContactPaymentContext? = null,
    ) {
        launchScan(
            source = ScanSource.SCAN_RESULT,
            data = data,
            startDelay = startDelay,
            routePubkyKeys = routePubkyKeys,
            contactPaymentContext = contactPaymentContext,
        )
    }

    fun openContactPayment(
        paymentRequest: String,
        publicKey: String,
        privatePaymentContext: PrivatePaykitPaymentContext? = null,
        incomingPaymentRequest: PaykitPaymentRequest? = null,
        isInitialSubscriptionPayment: Boolean = false,
        selectedTags: ImmutableList<String> = persistentListOf(),
    ): Job? {
        val context = ContactPaymentContext(
            publicKey = publicKey,
            privatePaymentContext = privatePaymentContext,
            incomingPaymentRequest = incomingPaymentRequest,
            isInitialSubscriptionPayment = isInitialSubscriptionPayment,
            selectedTags = selectedTags,
        )
        return launchScan(
            source = ScanSource.SCAN_RESULT,
            data = paymentRequest,
            contactPaymentContext = context,
        )
    }

    fun preserveContactPaymentContext(paymentHash: String) {
        synchronized(contactPaymentContextLock) {
            val context = activeContactPaymentContext
            if (context != null) {
                pendingContactPaymentContexts[paymentHash] = context
                activeContactPaymentContext = null
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun handleScan(
        result: String,
        routePubkyKeys: Boolean,
    ) = withContext(bgDispatcher) {
        val contactPaymentProfile = activeContactPaymentProfile()
        val incomingPaymentRequest = activeIncomingPaymentRequest()
        val isPaymentRequest = incomingPaymentRequest != null
        // always reset state on new scan
        resetSendState(
            contactPaymentProfile = contactPaymentProfile,
            isPaymentRequest = isPaymentRequest,
            isSubscriptionPayment = incomingPaymentRequest?.billingPeriod != null,
            isInitialSubscriptionPayment = synchronized(contactPaymentContextLock) {
                activeContactPaymentContext?.isInitialSubscriptionPayment == true
            },
            incomingPaymentRequestId = incomingPaymentRequest?.id,
            selectedTags = synchronized(contactPaymentContextLock) {
                activeContactPaymentContext?.selectedTags ?: persistentListOf()
            },
        )
        resetQuickPay()

        val fromMainScanner = isMainScanner
        val input = result.removeLightningSchemes()

        // TODO Workaround for https://github.com/synonymdev/bitkit-core/issues/63
        if (Bip21Utils.isDuplicatedBip21(input)) {
            hideSheet()
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__scan_err_decoding),
                description = context.getString(R.string.other__scan__error__generic),
                testTag = "DuplicatedBip21Toast",
            )
            clearActiveContactPaymentContext()
            return@withContext
        }

        SamRockSetupRequest.parse(input)?.let {
            handleSamRockSetup(it)
            return@withContext
        }

        if (SamRockSetupRequest.isProtocolUrl(input)) {
            handleInvalidSamRockSetup(input)
            return@withContext
        }

        if (input.startsWith("$PUBKYAUTH_SCHEME://", ignoreCase = true)) {
            clearActiveContactPaymentContext()
            if (!fromMainScanner) {
                hideSheet()
                toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.other__qr_error_header),
                    description = context.getString(R.string.other__qr_error_text),
                )
            } else if (isPaykitEnabled.value) {
                handlePubkyAuth(input)
            } else {
                hideSheet()
                toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.other__scan_err_decoding),
                    description = context.getString(R.string.other__scan__error__generic),
                )
            }
            return@withContext
        }

        if (routePubkyKeys && isPaykitEnabled.value) {
            val route = resolvePastedPubkyRoute(
                input = input,
                ownPublicKey = pubkyRepo.publicKey.value,
                contacts = pubkyRepo.contacts.value,
                isPaykitEnabled = isPaykitEnabled.value,
            )

            if (route != null) {
                clearActiveContactPaymentContext()
                if (currentSheet.value is Sheet.Send) hideSheet()
                mainScreenEffect(MainScreenEffect.Navigate(route))
                if (route is Routes.ContactDetail) {
                    refreshContactPaykitReceivers(route.publicKey)
                }
                return@withContext
            }
        }

        val safeLogInput = SamRockSetupRequest.sanitizedDescription(input) ?: input
        val scan = runSuspendCatching { coreService.decode(input) }
            .onFailure { Logger.error("Failed to decode scan data: '$safeLogInput'", it, context = TAG) }
            .onSuccess { Logger.info("Handling decoded scan data: $it", context = TAG) }
            .getOrNull()

        handleDecodedScan(scan, input, fromMainScanner)
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handleDecodedScan(
        scan: Scanner?,
        input: String,
        fromMainScanner: Boolean,
    ) {
        if (activeHardwareWalletId != null && scan != null && scan !is Scanner.OnChain) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.hardware__send_onchain_only_title),
                description = context.getString(R.string.hardware__send_onchain_only_text),
            )
            clearActiveContactPaymentContext()
            return
        }

        when (scan) {
            is Scanner.OnChain -> onScanOnchain(scan.invoice, input, fromMainScanner)
            is Scanner.Lightning -> onScanLightning(scan.invoice, input, fromMainScanner)
            is Scanner.LnurlPay -> onScanLnurlPay(scan.data, fromMainScanner)
            is Scanner.LnurlWithdraw -> handleNonPaymentScan { onScanLnurlWithdraw(scan.data, fromMainScanner) }
            is Scanner.LnurlAuth -> handleNonPaymentScan { onScanLnurlAuth(scan.data, fromMainScanner) }
            is Scanner.LnurlChannel -> handleNonPaymentScan { onScanLnurlChannel(scan.data) }
            is Scanner.NodeId -> handleNonPaymentScan { onScanNodeId(scan) }
            is Scanner.Gift -> handleNonPaymentScan { onScanGift(scan.code, scan.amount) }
            else -> {
                hideSheet()
                Logger.warn(
                    if (scan == null) "Failed to decode scan data" else "Received unhandled scan data '$scan'",
                    context = TAG,
                )
                toast(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.other__qr_error_header),
                    description = context.getString(R.string.other__qr_error_text),
                )
                clearActiveContactPaymentContext()
            }
        }
    }

    private suspend fun handleSamRockSetup(setup: SamRockSetupRequest) {
        clearActiveContactPaymentContext()

        if (!setup.requestsBitcoinOnchain) {
            hideSheet()
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.btcpay__unsupported_title),
                description = context.getString(R.string.btcpay__unsupported_text),
                testTag = "BTCPayUnsupportedToast",
            )
            return
        }

        showSheet(Sheet.BTCPayConnection(setup))
    }

    private suspend fun handleInvalidSamRockSetup(input: String) {
        clearActiveContactPaymentContext()
        hideSheet()
        val descriptionRes = when {
            SamRockSetupRequest.isPublicHttpProtocolUrl(input) -> R.string.btcpay__unsupported_http_text
            else -> R.string.btcpay__invalid_link_text
        }
        toast(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.btcpay__unsupported_title),
            description = context.getString(descriptionRes),
            testTag = "BTCPayInvalidSetupToast",
        )
    }

    private suspend fun handleNonPaymentScan(action: suspend () -> Unit) {
        clearActiveContactPaymentContext()
        action()
    }

    fun clearActiveContactPaymentContext(retryIncomingRequest: Boolean = true) {
        val interruptedRequest = synchronized(contactPaymentContextLock) {
            val request = activeContactPaymentContext?.incomingPaymentRequest
            activeContactPaymentContext = null
            preparedContactPaymentContext = null
            request
        }
        if (interruptedRequest == null) return

        if (!retryIncomingRequest) {
            paymentRequestPresentationGeneration++
            if (requestedPaymentRequestId == interruptedRequest.id) {
                requestedPaymentRequestId = null
            }
            clearPaymentRequestPresentationRetry(interruptedRequest.id)
            viewModelScope.launch { paykitPaymentRequestRepo.markPresented(interruptedRequest) }
            return
        }

        if (
            requestedPaymentRequestId == interruptedRequest.id ||
            paykitPaymentRequestRepo.automaticPendingRequests().any { it.id == interruptedRequest.id }
        ) {
            deferPaymentRequestPresentation(interruptedRequest)
        }
        isSubmittingPaymentRequest = false
    }

    private fun setActiveContactPaymentContext(context: ContactPaymentContext?) {
        synchronized(contactPaymentContextLock) {
            if (activeContactPaymentContext != context) preparedContactPaymentContext = null
            activeContactPaymentContext = context
        }
    }

    private fun clearPendingContactPaymentContext(paymentHash: String) {
        synchronized(contactPaymentContextLock) {
            pendingContactPaymentContexts.remove(paymentHash)
        }
    }

    private fun hasActiveContactPaymentContext() = synchronized(contactPaymentContextLock) {
        activeContactPaymentContext != null
    }

    private fun activeContactPaymentPublicKey() = synchronized(contactPaymentContextLock) {
        activeContactPaymentContext?.publicKey
    }

    private fun activeIncomingPaymentRequest() = synchronized(contactPaymentContextLock) {
        activeContactPaymentContext?.incomingPaymentRequest
    }

    private fun activeContactPaymentProfile(): PubkyProfile? {
        val publicKey = activeContactPaymentPublicKey() ?: return null
        return pubkyRepo.contacts.value.firstOrNull {
            PubkyPublicKeyFormat.matches(it.publicKey, publicKey)
        } ?: PubkyProfile.placeholder(publicKey)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun onScanOnchain(
        invoice: OnChainInvoice,
        scanResult: String,
        fromMainScanner: Boolean,
    ) {
        val validatedAddress = runCatching { coreService.validateBitcoinAddress(invoice.address) }
            .getOrElse {
                hideSheet()
                toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.other__scan_err_decoding),
                    description = context.getString(R.string.wallet__error_invalid_bitcoin_address),
                    testTag = "InvalidAddressToast",
                )
                clearActiveContactPaymentContext()
                return
            }

        if (NetworkValidationHelper.isNetworkMismatch(validatedAddress.network.toLdkNetwork(), Env.network)) {
            hideSheet()
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__scan_err_decoding),
                description = context.getString(R.string.other__scan__error__generic),
                testTag = "InvalidAddressToast",
            )
            clearActiveContactPaymentContext()
            return
        }
        val hardwareWalletId = activeHardwareWalletId
        val selectedMaxSendOnchain = if (hardwareWalletId != null) {
            hardwareMaxSpendable(hardwareWalletId, invoice.address, _sendUiState.value.speed)
        } else {
            walletRepo.balanceState.value.maxSendOnchainSats
        }
        val maxSendOnchain = maximumAvailableOnchainSats(selectedMaxSendOnchain, hardwareWalletId)

        val incomingPaymentRequest = activeIncomingPaymentRequest()
        val lnInvoice = if (hardwareWalletId == null) {
            extractViableLightningInvoice(invoice.params)?.takeIf {
                incomingPaymentRequest?.acceptsLightningInvoiceAmountSats(it.amountSatoshis) != false
            }
        } else {
            null
        }
        val amount = incomingPaymentRequest?.amountSats
            ?: lnInvoice?.amountSatoshis?.takeIf { it > 0uL }
            ?: invoice.amountSatoshis
        _sendUiState.update {
            it.copy(
                address = invoice.address,
                addressInput = scanResult,
                isAddressInputValid = true,
                amount = amount,
                isUnified = hardwareWalletId == null && lnInvoice != null &&
                    amount <= maxSendOnchain && maxSendOnchain > 0u,
                decodedInvoice = lnInvoice,
                payMethod = if (hardwareWalletId != null) {
                    SendMethod.ONCHAIN
                } else {
                    lnInvoice?.let { SendMethod.LIGHTNING } ?: SendMethod.ONCHAIN
                },
                hardwareWalletId = hardwareWalletId,
                hardwareAvailableSats = selectedMaxSendOnchain.takeIf { hardwareWalletId != null } ?: 0uL,
            )
        }
        updateCanSwitchWallet()

        if (incomingPaymentRequest != null) {
            if (lnInvoice != null) {
                lightningRepo.waitForUsableChannels()
                if (!lightningRepo.canSend(amount) && amount <= maxSendOnchain) {
                    _sendUiState.update { it.copy(payMethod = SendMethod.ONCHAIN) }
                }
            }
            if (
                !validateAmount(amount) &&
                _sendUiState.value.payMethod == SendMethod.ONCHAIN &&
                _sendUiState.value.hardwareWalletId == null
            ) {
                selectHardwareFundingSourceForAmount(amount)
            }
            if (!validateAmount(amount)) {
                val isLightning = _sendUiState.value.payMethod == SendMethod.LIGHTNING
                val maxSendable = if (isLightning) {
                    walletRepo.balanceState.value.maxSendLightningSats
                } else {
                    maxSendOnchain
                }
                val shortfall = amount.safe() - maxSendable.safe()
                toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(
                        if (isLightning) {
                            R.string.other__pay_insufficient_spending
                        } else {
                            R.string.other__pay_insufficient_savings
                        }
                    ),
                    description = context.getString(
                        if (isLightning) {
                            R.string.other__pay_insufficient_spending_amount_description
                        } else {
                            R.string.other__pay_insufficient_savings_amount_description
                        }
                    ).replace(
                        "{amount}",
                        formatMoneyValue(shortfall),
                    ),
                )
                clearActiveContactPaymentContext(retryIncomingRequest = false)
                return
            }

            _sendUiState.update { it.copy(isAmountInputValid = validateAmount(amount)) }
            navigateToSendRoute(fromMainScanner, SendRoute.Confirm, SendEffect.NavigateToConfirm)
            refreshOnchainSendIfNeeded()
            estimateLightningRoutingFeesIfNeeded()
            return
        }

        val lnAmountSats = lnInvoice?.amountSatoshis ?: 0u
        if (lnAmountSats > 0u) {
            _sendUiState.update { it.copy(isAmountInputValid = true) }
            Logger.info("Found amount in unified invoice, checking QuickPay conditions", context = TAG)

            val quickPayHandled = handleQuickPayIfApplicable(
                amountSats = lnAmountSats,
                invoice = lnInvoice,
                fromMainScanner = fromMainScanner,
            )
            if (quickPayHandled) return

            navigateToSendRoute(fromMainScanner, SendRoute.Confirm, SendEffect.NavigateToConfirm)
            refreshOnchainSendIfNeeded()
            estimateLightningRoutingFeesIfNeeded()
            return
        }

        // Check on-chain balance before proceeding to amount screen
        if (maxSendOnchain == 0uL && _sendUiState.value.payMethod == SendMethod.ONCHAIN) {
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__pay_insufficient_savings),
                description = context.getString(R.string.other__pay_insufficient_savings_description),
                testTag = "InsufficientSavingsToast",
            )
            clearActiveContactPaymentContext()
            return
        }

        // Check if on-chain invoice amount exceeds available balance
        if (
            invoice.amountSatoshis > 0uL &&
            invoice.amountSatoshis > maxSendOnchain &&
            _sendUiState.value.payMethod == SendMethod.ONCHAIN
        ) {
            val shortfall = invoice.amountSatoshis - maxSendOnchain
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__pay_insufficient_savings),
                description = context.getString(R.string.other__pay_insufficient_savings_amount_description)
                    .replace("{amount}", formatMoneyValue(shortfall)),
                testTag = "InsufficientSavingsToast",
            )
            clearActiveContactPaymentContext()
            return
        }

        Logger.info(
            when (amount > 0u) {
                true -> "Found amount in invoice, proceeding to edit amount"
                else -> "No amount found in invoice, proceeding to enter amount"
            },
            context = TAG,
        )

        navigateToSendRoute(fromMainScanner, SendRoute.Amount, SendEffect.NavigateToAmount)
    }

    private suspend fun hardwareMaxSpendable(
        walletId: String,
        address: String,
        speed: TransactionSpeed,
    ): ULong {
        val satsPerVByte = hardwareSatsPerVByte(speed)
        return hwWalletRepo.maxSpendableFunding(
            walletId = walletId,
            address = address,
            satsPerVByte = satsPerVByte,
        ).getOrElse {
            hardwareEstimatedAvailable(walletId, speed)
        }
    }

    private fun hardwareEstimatedAvailable(
        walletId: String,
        speed: TransactionSpeed,
        feeRates: FeeRates? = _sendUiState.value.feeRates,
    ): ULong {
        val balance = hwWalletRepo.wallets.value.find { it.id == walletId }?.fundingBalanceSats ?: 0uL
        val reserve = HW_SEND_FALLBACK_TX_VBYTES.safe() * hardwareSatsPerVByte(speed, feeRates).safe()
        return balance.safe() - reserve.safe()
    }

    private fun hardwareSatsPerVByte(
        speed: TransactionSpeed,
        feeRates: FeeRates? = _sendUiState.value.feeRates,
    ): ULong =
        feeRates
            ?.getSatsPerVByteFor(speed)
            ?.toULong()
            ?.takeIf { it > 0uL }
            ?: HW_SEND_FALLBACK_SATS_PER_VBYTE

    private fun maximumHardwareFundingBalanceSats(): ULong =
        hwWalletRepo.wallets.value.maxOfOrNull { it.fundingBalanceSats } ?: 0uL

    private fun maximumAvailableOnchainSats(selectedMax: ULong, hardwareWalletId: String?): ULong =
        if (hardwareWalletId == null) maxOf(selectedMax, maximumHardwareFundingBalanceSats()) else selectedMax

    private fun availableFundingSources(state: SendUiState): List<SendFundingSource> = buildList {
        if (state.isUnified || state.decodedInvoice != null) add(SendFundingSource.Spending)
        if (state.address.isNotEmpty()) {
            add(SendFundingSource.Savings)
            hwWalletRepo.wallets.value.forEach { wallet ->
                if (wallet.fundingBalanceSats > 0uL || wallet.id == state.hardwareWalletId) {
                    add(SendFundingSource.Hardware(wallet.id))
                }
            }
        }
    }

    private fun SendUiState.selectedFundingSource(): SendFundingSource = when {
        hardwareWalletId != null -> SendFundingSource.Hardware(hardwareWalletId)
        payMethod == SendMethod.LIGHTNING -> SendFundingSource.Spending
        else -> SendFundingSource.Savings
    }

    private suspend fun onScanLightning(
        invoice: LightningInvoice,
        scanResult: String,
        fromMainScanner: Boolean,
    ) {
        if (invoice.isExpired) {
            hideSheet()
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__scan_err_decoding),
                description = context.getString(R.string.other__scan__error__expired),
                testTag = "ExpiredLightningToast",
            )
            clearActiveContactPaymentContext()
            return
        }

        val incomingPaymentRequest = activeIncomingPaymentRequest()
        if (incomingPaymentRequest?.acceptsLightningInvoiceAmountSats(invoice.amountSatoshis) == false) {
            rejectMismatchedPaymentRequest()
            return
        }

        val amount = incomingPaymentRequest?.amountSats ?: invoice.amountSatoshis
        val quickPayHandled = handleQuickPayIfApplicable(
            amountSats = amount,
            invoice = invoice,
            fromMainScanner = fromMainScanner,
        )
        if (quickPayHandled) return

        lightningRepo.waitForUsableChannels()
        if (!lightningRepo.canSend(amount)) {
            val maxSendLightning = walletRepo.balanceState.value.maxSendLightningSats
            val shortfall = amount.safe() - maxSendLightning.safe()
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__pay_insufficient_spending),
                description = context.getString(R.string.other__pay_insufficient_spending_amount_description)
                    .replace("{amount}", formatMoneyValue(shortfall)),
                testTag = "InsufficientSpendingToast",
            )
            clearActiveContactPaymentContext(retryIncomingRequest = false)
            hideSheet()
            return
        }

        _sendUiState.update {
            it.copy(
                amount = amount,
                addressInput = scanResult,
                isAddressInputValid = true,
                isAmountInputValid = true,
                decodedInvoice = invoice,
                payMethod = SendMethod.LIGHTNING,
            )
        }

        if (amount > 0uL) {
            Logger.info("Found amount in invoice, proceeding with payment", context = TAG)

            navigateToSendRoute(fromMainScanner, SendRoute.Confirm, SendEffect.NavigateToConfirm)
            return
        }
        Logger.info("No amount found in invoice, proceeding to enter amount", context = TAG)

        navigateToSendRoute(fromMainScanner, SendRoute.Amount, SendEffect.NavigateToAmount)
    }

    private suspend fun onScanLnurlPay(data: LnurlPayData, fromMainScanner: Boolean) {
        Logger.debug("LNURL: $data", context = TAG)

        val isFixed = data.isFixedAmount()
        val displaySats = data.minSendableSat()
        val incomingAmount = activeIncomingPaymentRequest()?.amountSats
        if (incomingAmount != null && incomingAmount !in displaySats..data.maxSendableSat()) {
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__lnurl_pay_error),
                description = context.getString(R.string.other__scan__error__generic),
            )
            clearActiveContactPaymentContext()
            return
        }
        val paymentAmount = incomingAmount ?: displaySats

        lightningRepo.waitForUsableChannels()
        if (!lightningRepo.canSend(paymentAmount.coerceAtLeast(1u))) {
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__lnurl_pay_error),
                description = context.getString(R.string.other__lnurl_pay_error_no_capacity),
            )
            clearActiveContactPaymentContext(retryIncomingRequest = false)
            hideSheet()
            return
        }

        val initialAmount = incomingAmount ?: if (isFixed) displaySats else 0u

        _sendUiState.update {
            it.copy(
                amount = initialAmount,
                isAmountInputValid = initialAmount > 0uL,
                payMethod = SendMethod.LIGHTNING,
                lnurl = LnurlParams.LnurlPay(data),
            )
        }

        if (isFixed || incomingAmount != null) {
            Logger.info("Found fixed amount '$displaySats' sats in lnurlPay, proceeding with payment", context = TAG)

            val quickPayHandled = handleQuickPayIfApplicable(
                amountSats = initialAmount,
                lnurlPay = data,
                fromMainScanner = fromMainScanner,
            )
            if (quickPayHandled) return

            navigateToSendRoute(fromMainScanner, SendRoute.Confirm, SendEffect.NavigateToConfirm)
            return
        }

        Logger.info("No amount found in lnurlPay, proceeding to enter amount manually", context = TAG)
        navigateToSendRoute(fromMainScanner, SendRoute.Amount, SendEffect.NavigateToAmount)
    }

    private suspend fun onScanLnurlWithdraw(data: LnurlWithdrawData, fromMainScanner: Boolean) {
        Logger.debug("LNURL: $data", context = TAG)

        val isFixed = data.isFixedAmount()
        val minWithdrawable = data.minWithdrawableSat()
        val maxWithdrawable = data.maxWithdrawableSat()

        if (!isFixed && minWithdrawable > maxWithdrawable) {
            hideSheet()
            toast(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.other__lnurl_withdr_error),
                description = context.getString(R.string.other__lnurl_withdr_error_minmax)
            )
            return
        }

        val displayAmount = minWithdrawable

        _sendUiState.update {
            it.copy(
                payMethod = SendMethod.LIGHTNING,
                amount = displayAmount,
                lnurl = LnurlParams.LnurlWithdraw(data = data)
            )
        }

        if (isFixed || minWithdrawable == maxWithdrawable) {
            delay(TRANSITION_SCREEN_MS)
            navigateToSendRoute(
                fromMainScanner,
                SendRoute.WithdrawConfirm,
                SendEffect.NavigateToWithdrawConfirm,
            )
            return
        }

        navigateToSendRoute(fromMainScanner, SendRoute.Amount, SendEffect.NavigateToAmount)
    }

    private suspend fun onScanLnurlAuth(data: LnurlAuthData, fromMainScanner: Boolean) {
        Logger.debug("LNURL: $data", context = TAG)
        if (!fromMainScanner) {
            hideSheet()
            delay(TRANSITION_SCREEN_MS)
        }
        showSheet(Sheet.LnurlAuth(domain = data.domain, lnurl = data.uri, k1 = data.k1))
    }

    private fun navigateToSendRoute(
        fromMainScanner: Boolean,
        route: SendRoute,
        effect: SendEffect,
    ) {
        if (fromMainScanner) {
            showSheet(Sheet.Send(route))
            return
        }

        setSendEffect(effect)
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
        fromMainScanner: Boolean,
        lnurlPay: LnurlPayData? = null,
        invoice: LightningInvoice? = null,
    ): Boolean {
        if (hasActiveContactPaymentContext()) return false
        val invoiceHash = invoice?.paymentHash?.toHex()?.takeIf { it.isNotBlank() }
        val open = invoiceHash != null && quickPayRepo.hasOpen(invoiceHash)
        if (!open && !canApplyQuickPay(amountSats)) return false

        Logger.info("Using QuickPay for '$amountSats' sats", context = TAG)

        val quickPayData: QuickPayData = when {
            lnurlPay != null -> {
                QuickPayData.LnurlPay(
                    sats = amountSats,
                    data = lnurlPay,
                )
            }

            else -> {
                val decodedInvoice = requireNotNull(invoice)
                QuickPayData.Bolt11(sats = amountSats, bolt11 = decodedInvoice.bolt11)
            }
        }

        _quickPayData.update {
            QuickPayRequest(id = quickPayRequestIds.incrementAndGet(), data = quickPayData)
        }

        if (lnurlPay != null) {
            _sendUiState.update {
                it.copy(
                    amount = amountSats,
                    payMethod = SendMethod.LIGHTNING,
                    lnurl = LnurlParams.LnurlPay(lnurlPay),
                )
            }
        } else if (invoice != null) {
            _sendUiState.update {
                it.copy(
                    amount = amountSats,
                    addressInput = invoice.bolt11,
                    isAddressInputValid = true,
                    decodedInvoice = invoice,
                    payMethod = SendMethod.LIGHTNING,
                )
            }
        }

        Logger.debug("QuickPayData: $quickPayData", context = TAG)

        navigateToSendRoute(fromMainScanner, SendRoute.QuickPay, SendEffect.NavigateToQuickPay)
        return true
    }

    private suspend fun canApplyQuickPay(amountSats: ULong): Boolean {
        if (hasActiveContactPaymentContext()) return false
        return quickPayRepo.canApply(amountSats).getOrDefault(false)
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
        if (!_sendUiState.value.isAmountInputValid) return
        viewModelScope.launch {
            val amount = _sendUiState.value.amount

            handleSanityChecks(amount)
            if (_sendUiState.value.showSanityWarningDialog != null) return@launch // await for dialog UI interaction

            _sendUiState.update { it.copy(shouldConfirmPay = true) }
        }
    }

    private fun onStartInitialSubscriptionPayment() {
        if (!_sendUiState.value.initialSubscriptionPaymentAutoStartPending) return
        _sendUiState.update { it.copy(initialSubscriptionPaymentAutoStartPending = false) }
        onSwipeToPay()
    }

    private fun onCancelInitialSubscriptionPayment() {
        if (!_sendUiState.value.isInitialSubscriptionPayment) return
        val contactPaymentContext = synchronized(contactPaymentContextLock) { activeContactPaymentContext }
        handlePaymentPreparationFailure(PaykitPaymentRequestError.RequestUnavailable, contactPaymentContext)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun handleSanityChecks(amountSats: ULong) {
        if (_sendUiState.value.showSanityWarningDialog != null) return

        val settings = settingsStore.data.first()
        val balanceToCheck = when (_sendUiState.value.payMethod) {
            SendMethod.ONCHAIN -> {
                _sendUiState.value.hardwareAvailableSats
                    .takeIf { _sendUiState.value.hardwareWalletId != null }
                    ?: walletRepo.balanceState.value.maxSendOnchainSats
            }
            SendMethod.LIGHTNING -> walletRepo.balanceState.value.maxSendLightningSats
        }
        if (
            amountSats > BigDecimal.valueOf(balanceToCheck.toLong())
                .times(BigDecimal(MAX_BALANCE_FRACTION)).toLong().toUInt() &&
            SanityWarning.OVER_HALF_BALANCE !in _sendUiState.value.confirmedWarnings
        ) {
            _sendUiState.update {
                it.copy(showSanityWarningDialog = SanityWarning.OVER_HALF_BALANCE)
            }
            return
        }

        val amountInUsd = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrNull() ?: return
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

        val totalFee = if (_sendUiState.value.hardwareWalletId != null) {
            _sendUiState.value.onchainFeeUi.sats?.toULong() ?: return
        } else {
            lightningRepo.calculateTotalFee(
                amountSats = amountSats,
                address = _sendUiState.value.address,
                speed = _sendUiState.value.speed,
                utxosToSpend = _sendUiState.value.selectedUtxos,
            ).getOrNull() ?: return
        }

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

        val feeInUsd = currencyRepo.convertSatsToFiat(totalFee.toLong(), USD).getOrNull() ?: return
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

    @Suppress("LongMethod", "ReturnCount")
    private suspend fun proceedWithPayment(contactPaymentContext: ContactPaymentContext?) {
        delay(SCREEN_TRANSITION_DELAY) // wait for screen transitions when applicable

        if (!validateIncomingPaymentRequest(contactPaymentContext)) return

        val incomingPaymentRequest = contactPaymentContext?.incomingPaymentRequest
        val preparedPaymentProofRequest = preparePaymentProof(incomingPaymentRequest).getOrNull()

        consumePrivatePaymentListIfNeeded(contactPaymentContext).onFailure {
            cancelPaymentProofPreparation(preparedPaymentProofRequest)
            handlePaymentPreparationFailure(it, contactPaymentContext)
            return
        }

        acceptIncomingPaymentRequestIfNeeded(contactPaymentContext).onFailure {
            cancelPaymentProofPreparation(preparedPaymentProofRequest)
            handlePaymentPreparationFailure(it, contactPaymentContext)
            return
        }

        val amount = _sendUiState.value.amount

        val lnurl = _sendUiState.value.lnurl
        val isLnurlPay = lnurl is LnurlParams.LnurlPay

        if (isLnurlPay) {
            val amountMsats = lnurl.data.callbackAmountMsats(amount)
            lightningRepo.fetchLnurlInvoice(
                data = lnurl.data,
                amountMsats = amountMsats,
                comment = _sendUiState.value.comment.takeIf { it.isNotEmpty() },
            ).onSuccess { invoice ->
                _sendUiState.update {
                    it.copy(decodedInvoice = invoice)
                }
            }.onFailure {
                cancelPaymentProofPreparation(preparedPaymentProofRequest)
                val message = getLnurlInvoiceFetchErrorMessage(it)
                handlePaymentPreparationFailure(Exception(message), contactPaymentContext)
                return
            }
        }

        when (_sendUiState.value.payMethod) {
            SendMethod.ONCHAIN -> proceedWithOnchainPayment(
                incomingPaymentRequest,
                preparedPaymentProofRequest,
                contactPaymentContext,
                amount,
            )

            SendMethod.LIGHTNING -> proceedWithLightningPayment(
                incomingPaymentRequest,
                preparedPaymentProofRequest,
                amount,
            )
        }
    }

    private suspend fun proceedWithOnchainPayment(
        incomingPaymentRequest: PaykitPaymentRequest?,
        preparedPaymentProofRequest: PaykitPaymentRequest?,
        contactPaymentContext: ContactPaymentContext?,
        amount: ULong,
    ) {
        val address = _sendUiState.value.address
        val tags = _sendUiState.value.selectedTags
        var proofRequest = preparedPaymentProofRequest
        var onchainPaymentStarted = false
        sendOnchain(
            address = address,
            amount = amount,
            tags = tags,
            beforeSendAttempt = {
                if (preparedPaymentProofRequest != null) {
                    markOnchainPaymentStarted(incomingPaymentRequest, address).getOrThrow()
                    onchainPaymentStarted = true
                }
            },
            onBroadcast = { txId ->
                proofRequest = null
                completeOnchainPaymentProofInBackground(incomingPaymentRequest, txId)
            },
        ).onSuccess { txId ->
            Logger.info("Onchain send result txid: $txId", context = TAG)
            onSendSuccess(
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.ONCHAIN,
                    direction = NewTransactionSheetDirection.SENT,
                    paymentHashOrTxId = txId,
                    sats = amount.toLong(),
                    isLoadingDetails = true,
                )
            )
            lightningRepo.sync()
            activityRepo.syncActivities()
            _successSendUiState.update { it.copy(isLoadingDetails = false) }
        }.onFailure { error ->
            handleOnchainPaymentFailure(
                error = error,
                paymentStarted = onchainPaymentStarted,
                incomingPaymentRequest = incomingPaymentRequest,
                preparedPaymentProofRequest = proofRequest,
                contactPaymentContext = contactPaymentContext,
            )
        }
    }

    private suspend fun handleOnchainPaymentFailure(
        error: Throwable,
        paymentStarted: Boolean,
        incomingPaymentRequest: PaykitPaymentRequest?,
        preparedPaymentProofRequest: PaykitPaymentRequest?,
        contactPaymentContext: ContactPaymentContext?,
    ) {
        val amount = _sendUiState.value.amount
        if (paymentStarted && !error.isDefiniteOnchainPreBroadcastFailure()) {
            Logger.warn("On-chain payment outcome is uncertain after send started", error, context = TAG)
            uncertainOnchainPaymentRequestId = incomingPaymentRequest?.id
            paykitPaymentProofRepo.onchainPaymentResolutions.value
                .firstOrNull { it.requestId == incomingPaymentRequest?.id }
                ?.let(::handlePaykitOnchainPaymentResolution)
            if (uncertainOnchainPaymentRequestId == null) return
            setSendEffect(
                SendEffect.NavigateToPending(
                    paymentHash = incomingPaymentRequest?.paymentRequestId.orEmpty(),
                    amount = amount.toLong(),
                    observeResolution = false,
                )
            )
            return
        }
        if (paymentStarted) {
            incomingPaymentRequest?.let { paykitPaymentProofRepo.failOnchainPayment(it) }
        }
        cancelPaymentProofPreparation(preparedPaymentProofRequest)
        Logger.error("Error sending onchain payment", error, context = TAG)
        if (contactPaymentContext?.isInitialSubscriptionPayment == true) {
            setSendEffect(SendEffect.NavigateToError(error.toSendFailureDetails(context, _sendUiState.value.address)))
        } else {
            toast(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.wallet__error_sending_title),
                description = error.message ?: context.getString(R.string.common__error_body),
            )
            hideSheet()
        }
    }

    private suspend fun proceedWithLightningPayment(
        incomingPaymentRequest: PaykitPaymentRequest?,
        preparedPaymentProofRequest: PaykitPaymentRequest?,
        amount: ULong,
    ) {
        val decodedInvoice = requireNotNull(_sendUiState.value.decodedInvoice)
        val paymentAmount = if (decodedInvoice.amountSatoshis > 0uL) null else amount
        val displayAmountSats = decodedInvoice.amountSatoshis.takeIf { it > 0uL } ?: amount
        var proofRequest = preparedPaymentProofRequest
        var createdMetadataPaymentId: String? = null
        val paymentHash = decodedInvoice.paymentHash.toHex()
        associateLightningPaymentProof(incomingPaymentRequest, paymentHash).onFailure {
            cancelPaymentProofPreparation(proofRequest)
            proofRequest = null
        }

        val tags = _sendUiState.value.selectedTags
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

        sendLightning(decodedInvoice.bolt11, paymentAmount).onSuccess { actualPaymentHash ->
            proofRequest = null
            Logger.info("Lightning send result payment hash: $actualPaymentHash", context = TAG)
            onSendSuccess(
                NewTransactionSheetDetails(
                    type = NewTransactionSheetType.LIGHTNING,
                    direction = NewTransactionSheetDirection.SENT,
                    paymentHashOrTxId = actualPaymentHash,
                    sats = displayAmountSats.toLong(),
                ),
            )
        }.onFailure { error ->
            if (error is PaymentPendingException) {
                proofRequest = null
                Logger.info("Lightning payment pending", context = TAG)
                pendingPaymentRepo.track(error.paymentHash)
                preserveContactPaymentContext(error.paymentHash)
                refreshIncomingPaykitPaymentRequests()
                setSendEffect(SendEffect.NavigateToPending(error.paymentHash, displayAmountSats.toLong()))
                return@onFailure
            }
            paykitPaymentProofRepo.failLightningPayment(paymentHash)
            cancelPaymentProofPreparation(proofRequest)
            createdMetadataPaymentId?.let { preActivityMetadataRepo.deletePreActivityMetadata(it) }
            Logger.error("Error sending lightning payment", error, context = TAG)
            val failure = when (error) {
                is LightningPaymentFailedError -> error.reason.toSendFailureDetails(context, error.paymentRequest)
                else -> error.toSendFailureDetails(context, _sendUiState.value.currentLightningPaymentRequest())
            }
            setSendEffect(SendEffect.NavigateToError(failure))
        }
    }

    private suspend fun prepareContactPayment(contactPaymentContext: ContactPaymentContext?): Boolean {
        if (isPreparedContactPayment(contactPaymentContext)) return true
        if (!validateIncomingPaymentRequest(contactPaymentContext)) return false

        consumePrivatePaymentListIfNeeded(contactPaymentContext).onFailure {
            handlePaymentPreparationFailure(it, contactPaymentContext)
            return false
        }
        acceptIncomingPaymentRequestIfNeeded(contactPaymentContext).onFailure {
            handlePaymentPreparationFailure(it, contactPaymentContext)
            return false
        }
        synchronized(contactPaymentContextLock) {
            if (activeContactPaymentContext == contactPaymentContext) {
                preparedContactPaymentContext = contactPaymentContext
            }
        }
        return true
    }

    private fun isPreparedContactPayment(contactPaymentContext: ContactPaymentContext?): Boolean =
        contactPaymentContext != null &&
            synchronized(contactPaymentContextLock) { preparedContactPaymentContext == contactPaymentContext }

    private suspend fun preparePaymentProof(request: PaykitPaymentRequest?): Result<PaykitPaymentRequest?> {
        if (request == null) return Result.success(null)
        val preparation = paymentProofPreparation()
        return paykitPaymentProofRepo.prepare(
            request = request,
            paymentEndpointIdentifier = preparation.endpointIdentifier,
            kind = preparation.kind,
        ).map { request }
    }

    private suspend fun associateLightningPaymentProof(
        request: PaykitPaymentRequest?,
        paymentHash: String,
    ): Result<Unit> = request?.let {
        paykitPaymentProofRepo.associateLightningPayment(
            request = it,
            paymentHash = paymentHash,
            paymentEndpointIdentifier = paymentProofPreparation().endpointIdentifier,
        )
    }
        ?: Result.success(Unit)

    private fun completeOnchainPaymentProofInBackground(request: PaykitPaymentRequest?, txId: String) {
        val paymentRequest = request ?: return
        val endpointIdentifier = paymentProofPreparation().endpointIdentifier
        viewModelScope.launch {
            paykitPaymentProofRepo.completeOnchainPayment(
                request = paymentRequest,
                txid = txId,
                paymentEndpointIdentifier = endpointIdentifier,
            )
            if (paymentRequest.billingPeriod != null) refreshIncomingPaykitPaymentRequests()
        }
    }

    private suspend fun markOnchainPaymentStarted(
        request: PaykitPaymentRequest?,
        address: String,
        walletId: String = WalletScope.default,
    ): Result<Unit> = request?.let { paykitPaymentProofRepo.markOnchainPaymentStarted(it, address, walletId) }
        ?: Result.success(Unit)

    private suspend fun cancelPaymentProofPreparation(request: PaykitPaymentRequest?) {
        request?.let { paykitPaymentProofRepo.cancelPreparation(it) }
    }

    private fun paymentProofPreparation(): PaymentProofPreparation {
        val methodId = when (_sendUiState.value.payMethod) {
            SendMethod.ONCHAIN -> PublicPaykitRepo.onchainMethodId(_sendUiState.value.address)
            SendMethod.LIGHTNING -> if (_sendUiState.value.lnurl is LnurlParams.LnurlPay) {
                MethodId.Lnurl
            } else {
                MethodId.Bolt11
            }
        }
        return PaymentProofPreparation(
            endpointIdentifier = methodId.rawValue,
            kind = if (methodId.isOnchain) PaykitPaymentProofKind.Onchain else PaykitPaymentProofKind.Lightning,
        )
    }

    private suspend fun hasMismatchedIncomingPaymentRequest(contactPaymentContext: ContactPaymentContext?): Boolean {
        val incomingPaymentRequest = contactPaymentContext?.incomingPaymentRequest ?: return false
        if (!incomingPaymentRequest.acceptsPaymentAmount(_sendUiState.value.amount)) return true
        if (_sendUiState.value.payMethod != SendMethod.LIGHTNING) return false

        val lightningInvoice = _sendUiState.value.decodedInvoice ?: return false
        return !incomingPaymentRequest.acceptsLightningInvoice(lightningInvoice)
    }

    private suspend fun PaykitPaymentRequest.acceptsLightningInvoice(invoice: LightningInvoice): Boolean =
        withContext(bgDispatcher) {
            val amountMsats = runCatching { Bolt11Invoice.fromStr(invoice.bolt11).amountMilliSatoshis() }
                .getOrElse { return@withContext false }
            acceptsLightningInvoiceAmountMsats(amountMsats)
        }

    private suspend fun validateIncomingPaymentRequest(
        contactPaymentContext: ContactPaymentContext?,
    ): Boolean {
        val incomingPaymentRequest = contactPaymentContext?.incomingPaymentRequest
        if (
            (_sendUiState.value.isPaymentRequest && incomingPaymentRequest == null) ||
            hasMismatchedIncomingPaymentRequest(contactPaymentContext)
        ) {
            rejectMismatchedPaymentRequest()
            return false
        }
        if (incomingPaymentRequest != null && !paykitPaymentRequestRepo.isPending(incomingPaymentRequest)) {
            toast(PaykitPaymentRequestError.RequestUnavailable)
            hideSheet()
            return false
        }
        return true
    }

    private fun rejectMismatchedPaymentRequest() {
        toast(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.wallet__toast_payment_failed_title),
            description = context.getString(R.string.wallet__payment_request_mismatch),
            testTag = "PaymentFailedToast",
        )
        hideSheet()
    }

    private fun getLnurlInvoiceFetchErrorMessage(error: Throwable): String = when (error) {
        is LnurlPayInvoiceMismatchError -> context.getString(R.string.lightning__order_state__payment_canceled)
        else -> context.getString(R.string.wallet__error_lnurl_invoice_fetch)
    }

    fun onConfirmWithdraw() {
        _sendUiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val lnurl = _sendUiState.value.lnurl as? LnurlParams.LnurlWithdraw

            if (lnurl == null) {
                setSendEffect(SendEffect.NavigateToWithdrawError)
                return@launch
            }

            val invoice = if (lnurl.data.isFixedAmount()) {
                lightningRepo.createInvoiceMsats(
                    amountMsats = lnurl.data.maxWithdrawable,
                    description = lnurl.data.defaultDescription,
                    expirySeconds = LNURL_WITHDRAW_EXPIRY_SEC,
                )
            } else {
                val withdrawAmountSats = _sendUiState.value.amount.coerceAtLeast(
                    msatFloorOf(lnurl.data.minWithdrawable ?: 0u)
                )
                _sendUiState.update { it.copy(amount = withdrawAmountSats) }
                lightningRepo.createInvoice(
                    amountSats = withdrawAmountSats,
                    description = lnurl.data.defaultDescription,
                    expirySeconds = LNURL_WITHDRAW_EXPIRY_SEC,
                )
            }.getOrNull()

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
        val details = _transactionSheet.value
        details.activityId?.let {
            hideNewTransactionSheet()
            mainScreenEffect(
                MainScreenEffect.Navigate(
                    Routes.ActivityDetail(it, details.activityWalletId)
                )
            )
            return
        }

        val activityType = _transactionSheet.value.type.toActivityFilter()
        val txType = _transactionSheet.value.direction.toTxType()
        val paymentHashOrTxId = _transactionSheet.value.paymentHashOrTxId ?: return
        val activityWalletId = _transactionSheet.value.activityWalletId ?: WalletScope.default
        _transactionSheet.update { it.copy(isLoadingDetails = true) }
        viewModelScope.launch {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHashOrTxId,
                type = activityType,
                txType = txType,
                retry = activityWalletId == WalletScope.default,
                walletId = activityWalletId,
            ).onSuccess { activity ->
                hideNewTransactionSheet()
                _transactionSheet.update { it.copy(isLoadingDetails = false) }
                val nextRoute = Routes.ActivityDetail(activity.rawId(), activity.walletId())
                mainScreenEffect(MainScreenEffect.Navigate(nextRoute))
            }.onFailure { e ->
                Logger.error(msg = "Activity not found", context = TAG)
                toast(e)
                _transactionSheet.update { it.copy(isLoadingDetails = false) }
            }
        }
    }

    fun onClickSendDetail() {
        val activityType = _successSendUiState.value.type.toActivityFilter()
        val txType = _successSendUiState.value.direction.toTxType()
        val paymentHashOrTxId = _successSendUiState.value.paymentHashOrTxId ?: return
        val activityWalletId = _successSendUiState.value.activityWalletId ?: WalletScope.default
        _successSendUiState.update { it.copy(isLoadingDetails = true) }
        viewModelScope.launch {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHashOrTxId,
                type = activityType,
                txType = txType,
                retry = activityWalletId == WalletScope.default,
                walletId = activityWalletId,
            ).onSuccess { activity ->
                hideSheet()
                _successSendUiState.update { it.copy(isLoadingDetails = false) }
                val nextRoute = Routes.ActivityDetail(activity.rawId(), activity.walletId())
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
        beforeSendAttempt: suspend () -> Unit = {},
        onBroadcast: suspend (Txid) -> Unit = {},
    ): Result<Txid> {
        var broadcastTxId: Txid? = null
        return lightningRepo.sendOnChain(
            address = address,
            sats = amount,
            speed = _sendUiState.value.speed,
            utxosToSpend = _sendUiState.value.selectedUtxos,
            isMaxAmount = _sendUiState.value.payMethod == SendMethod.ONCHAIN &&
                amount == walletRepo.balanceState.value.maxSendOnchainSats,
            tags = tags,
            beforeSendAttempt = beforeSendAttempt,
            onBroadcast = {
                broadcastTxId = it
                onBroadcast(it)
            },
        ).recoverCatching { broadcastTxId ?: throw it }
    }

    private suspend fun sendLightning(
        bolt11: String,
        amount: ULong? = null,
    ): Result<PaymentId> {
        return lightningRepo.payInvoice(bolt11 = bolt11, sats = amount).onSuccess { hash ->
            // Wait until matching payment event is received (with timeout for hold invoices)
            val result = lightningRepo.nodeEvents.watchUntil(LightningRepo.SEND_LN_TIMEOUT) {
                when (it) {
                    is Event.PaymentSuccessful if it.paymentHash == hash -> WatchResult.Complete(Result.success(hash))
                    is Event.PaymentFailed if it.paymentHash == hash -> WatchResult.Complete(
                        Result.failure(
                            LightningPaymentFailedError(reason = it.reason, paymentRequest = bolt11)
                        )
                    )

                    else -> WatchResult.Continue()
                }
            }
            return result ?: Result.failure(PaymentPendingException(hash))
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

    fun resetQuickPay() = _quickPayData.update { null }

    fun navigateToActivity(activityRawId: String) {
        viewModelScope.launch {
            hideSheet()
            mainScreenEffect(MainScreenEffect.Navigate(Routes.ActivityDetail(activityRawId)))
        }
    }

    fun navigateToReportIssue(prefillMessage: String) {
        viewModelScope.launch {
            hideSheet()
            mainScreenEffect(MainScreenEffect.Navigate(Routes.ReportIssue(prefillMessage)))
        }
    }

    /** Reselect utxos for current amount & speed then refresh fees using updated utxos */
    private fun refreshOnchainSendIfNeeded(): Job? {
        val currentState = _sendUiState.value
        if (currentState.payMethod != SendMethod.ONCHAIN ||
            currentState.amount == 0uL ||
            currentState.address.isEmpty()
        ) {
            return null
        }

        updateOnchainFeeUi { it.copy(isLoading = true) }
        onchainSendRefreshJob?.cancel()
        val job = viewModelScope.launch(bgDispatcher, start = CoroutineStart.LAZY) {
            // preselect utxos for deterministic fee estimation
            if (
                currentState.hardwareWalletId == null &&
                settingsStore.data.first().coinSelectAuto &&
                currentState.selectedUtxos == null
            ) {
                lightningRepo.getFeeRateForSpeed(currentState.speed, currentState.feeRates)
                    .mapCatching { satsPerVByte ->
                        lightningRepo.determineUtxosToSpend(
                            sats = currentState.amount,
                            satsPerVByte = satsPerVByte,
                        )
                    }
                    .onSuccess { utxos ->
                        _sendUiState.update { it.copy(selectedUtxos = utxos?.toImmutableList()) }
                    }
            }
            refreshOnchainFeeUi()
        }
        onchainSendRefreshJob = job
        job.invokeOnCompletion {
            if (onchainSendRefreshJob === job) onchainSendRefreshJob = null
        }
        job.start()
        return job
    }

    private suspend fun refreshOnchainFeeUi() = withContext(bgDispatcher) {
        val currentState = _sendUiState.value
        updateOnchainFeeUi { it.copy(isLoading = true) }

        val speeds = listOf(
            TransactionSpeed.Fast,
            TransactionSpeed.Medium,
            TransactionSpeed.Slow,
            when (val speed = currentState.speed) {
                is TransactionSpeed.Custom -> speed
                else -> TransactionSpeed.Custom(0u)
            }
        )

        val estimates = coroutineScope {
            speeds.map { speed ->
                async {
                    val rate = FeeRate.fromSpeed(speed)
                    val fee = if (currentState.feeRates?.getSatsPerVByteFor(speed) != 0u) getFeeEstimate(speed) else 0
                    rate to fee
                }
            }.awaitAll().toMap()
        }
        val rate = FeeRate.fromSpeed(currentState.speed)

        updateOnchainFeeUi {
            OnchainFeeUi(
                rate = rate,
                sats = estimates[rate]?.takeIf { sats -> sats > 0 },
                estimates = estimates.toImmutableMap(),
                isLoading = false,
            )
        }
    }

    private fun updateOnchainFeeUi(transform: (OnchainFeeUi) -> OnchainFeeUi) {
        _sendUiState.update {
            it.copy(onchainFeeUi = transform(it.onchainFeeUi))
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
                    lightningFeeSats = fee.toLong(),
                    lastLightningFee = fee.toLong(),
                )
            }
        }
    }

    private suspend fun estimateMaxAmountRoutingFee() {
        val currentState = _sendUiState.value
        if (currentState.payMethod != SendMethod.LIGHTNING) return

        val decodedInvoice = currentState.decodedInvoice ?: return
        val bolt11 = decodedInvoice.bolt11

        val maxSendLightning = walletRepo.balanceState.value.maxSendLightningSats
        if (maxSendLightning == 0uL) {
            _sendUiState.update { it.copy(estimatedRoutingFee = 0uL) }
            return
        }

        val buffer = 2uL
        val amountToEstimate = maxSendLightning.safe() - buffer.safe()

        val feeResult = lightningRepo.estimateRoutingFeesForAmount(
            bolt11 = bolt11,
            amountSats = amountToEstimate
        )

        feeResult.onSuccess { fee ->
            _sendUiState.update {
                it.copy(estimatedRoutingFee = fee + buffer)
            }
        }.onFailure { e ->
            Logger.error("Failed to estimate routing fee for max amount", e, context = TAG)
            _sendUiState.update { it.copy(estimatedRoutingFee = 0uL) }
        }
    }

    private suspend fun getFeeEstimate(speed: TransactionSpeed? = null): Long {
        val currentState = _sendUiState.value
        val hardwareWalletId = currentState.hardwareWalletId
        if (hardwareWalletId != null) {
            val selectedSpeed = speed ?: currentState.speed
            val satsPerVByte = currentState.feeRates?.getSatsPerVByteFor(selectedSpeed) ?: 0u
            if (satsPerVByte == 0u) return 0
            return hwWalletRepo.estimateFundingMiningFee(
                walletId = hardwareWalletId,
                address = currentState.address,
                sats = currentState.amount,
                satsPerVByte = satsPerVByte.toULong(),
            ).getOrNull()?.toLong() ?: 0
        }
        return lightningRepo.calculateTotalFee(
            amountSats = currentState.amount,
            address = currentState.address,
            speed = speed ?: currentState.speed,
            utxosToSpend = currentState.selectedUtxos,
            feeRates = currentState.feeRates,
        ).getOrDefault(0u).toLong()
    }

    suspend fun resetSendState(
        contactPaymentProfile: PubkyProfile? = null,
        isPaymentRequest: Boolean = false,
        hardwareWalletId: String? = activeHardwareWalletId,
        isSubscriptionPayment: Boolean = false,
        isInitialSubscriptionPayment: Boolean = false,
        incomingPaymentRequestId: PaykitPaymentRequestId? = null,
        selectedTags: ImmutableList<String> = persistentListOf(),
    ) {
        addressValidationJob?.cancel()
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
                onchainFeeUi = OnchainFeeUi(rate = FeeRate.fromSpeed(speed)),
                contactPaymentProfile = contactPaymentProfile,
                isPaymentRequest = isPaymentRequest,
                hardwareWalletId = hardwareWalletId,
                hardwareWalletName = hardwareWalletId?.let { walletId ->
                    hwWalletRepo.wallets.value.find { it.id == walletId }?.name
                },
                hardwareAvailableSats = hardwareWalletId?.let { walletId ->
                    hardwareEstimatedAvailable(walletId, speed, rates)
                } ?: 0uL,
                isFundingSourceLoading = it.isFundingSourceLoading,
                isSubscriptionPayment = isSubscriptionPayment,
                isInitialSubscriptionPayment = isInitialSubscriptionPayment,
                initialSubscriptionPaymentAutoStartPending = isInitialSubscriptionPayment,
                incomingPaymentRequestId = incomingPaymentRequestId,
                selectedTags = selectedTags,
            )
        }
    }
    // endregion

    // region TxSheet
    private var _isTransactionSheetEnabled = true

    private val _transactionSheet = MutableStateFlow(NewTransactionSheetDetails.EMPTY)
    val transactionSheet = _transactionSheet.asStateFlow()

    private val _successSendUiState = MutableStateFlow(
        NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.SENT,
        )
    )

    val successSendUiState = _successSendUiState.asStateFlow()

    fun enabledTransactionSheet(enabled: Boolean) {
        _isTransactionSheetEnabled = enabled
    }

    fun showTransactionSheet(
        details: NewTransactionSheetDetails,
    ) = viewModelScope.launch {
        if (backupRepo.isRestoring.value) return@launch

        if (!_isTransactionSheetEnabled) {
            Logger.verbose("NewTransactionSheet blocked by isNewTransactionSheetEnabled=false", context = TAG)
            return@launch
        }

        _transactionSheet.update { details }
    }

    fun hideNewTransactionSheet() {
        _transactionSheet.update { NewTransactionSheetDetails.EMPTY }
    }

    fun consumePaymentReceivedInBackground() = viewModelScope.launch(bgDispatcher) {
        val details = cacheStore.data.first().backgroundReceive ?: return@launch
        cacheStore.clearBackgroundReceive()
        showTransactionSheet(details)
    }

    fun isForegroundServiceRunning(): Boolean = nodeServiceFgState.isForegroundServiceRunning
    // endregion

    // region Sheets
    private var scanResultHandler: ((String) -> Unit)? = null

    fun showScannerSheet(
        isPubkyScan: Boolean = false,
        onResult: ((String) -> Unit)? = null,
    ) {
        scanResultHandler = onResult
        showSheet(Sheet.QrScanner(isPubkyScan = isPubkyScan))
    }

    fun onScannerSheetResult(data: String) {
        val handler = scanResultHandler
        val shouldHandleAsProtocol = SamRockSetupRequest.isProtocolUrl(data.removeLightningSchemes())
        scanResultHandler = null
        hideSheet(shouldFlushDeferredScan = false)
        if (handler != null && !shouldHandleAsProtocol) {
            viewModelScope.launch {
                delay(SCREEN_TRANSITION_DELAY)
                handler(data)
                flushDeferredScan()
            }
        } else {
            launchScan(
                source = ScanSource.SCANNER_SHEET,
                data = data,
                startDelay = SCREEN_TRANSITION_DELAY,
                routePubkyKeys = true,
            )
        }
    }

    fun hideScannerSheet() {
        scanResultHandler = null
        hideSheet()
    }

    fun connectBTCPay(setup: SamRockSetupRequest) {
        viewModelScope.launch {
            updateBTCPayConnectionSheet(setup) {
                it.copy(
                    isConnecting = true,
                    errorText = null,
                )
            }

            samRockRepo.registerBitcoinOnchain(setup)
                .onSuccess {
                    hideSheet()
                    toast(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.btcpay__success_title),
                        description = context.getString(R.string.btcpay__success_description),
                        testTag = "BTCPayConnectedToast",
                    )
                }
                .onFailure {
                    val description = it.message ?: context.getString(R.string.btcpay__request_error)
                    updateBTCPayConnectionSheet(setup) {
                        it.copy(
                            isConnecting = false,
                            errorText = description,
                        )
                    }
                    toast(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.btcpay__error_title),
                        description = description,
                        testTag = "BTCPayConnectionErrorToast",
                    )
                }
        }
    }

    private fun updateBTCPayConnectionSheet(
        setup: SamRockSetupRequest,
        update: (Sheet.BTCPayConnection) -> Sheet.BTCPayConnection,
    ) {
        _currentSheet.update {
            val sheet = it as? Sheet.BTCPayConnection ?: return@update it
            if (sheet.setup == setup) update(sheet) else sheet
        }
    }

    fun showSheet(sheetType: Sheet) {
        val previousJob = sheetTransitionJob
        val nextJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            receiveSheetContext = null
            _currentSheet.value?.let {
                _currentSheet.update { null }
                delay(SCREEN_TRANSITION_DELAY)
            }
            receiveSheetContext = (sheetType as? Sheet.Receive)?.let { receiveSheet ->
                ReceiveSheetContext(
                    sheet = receiveSheet,
                    bolt11 = walletRepo.getBolt11(),
                    onchainAddress = walletRepo.getOnchainAddress(),
                )
            }
            _currentSheet.update { sheetType }
        }
        sheetTransitionJob = nextJob
        nextJob.invokeOnCompletion {
            if (sheetTransitionJob === nextJob) sheetTransitionJob = null
        }
        previousJob?.cancel()
        nextJob.start()
    }

    fun hideSheet() = hideSheet(shouldFlushDeferredScan = true)

    private fun hideSheet(shouldFlushDeferredScan: Boolean) {
        if (
            shouldFlushDeferredScan &&
            _currentSheet.value == null &&
            paymentRequestSheetTransitionJob?.isActive == true
        ) {
            return
        }
        if (_currentSheet.value is Sheet.Send) {
            cancelHardwarePaymentRequestIfNeeded()
            resetQuickPay()
            quickPayRepo.detachAll()
        }
        scanResultHandler = null
        receiveSheetContext = null
        sheetTransitionJob?.cancel()
        sheetTransitionJob = null
        clearActiveContactPaymentContext()
        when {
            currentSheet.value is Sheet.TimedSheet -> {
                // Only dismiss if manager still has a sheet (user initiated)
                // If manager already cleared it, just update our state
                if (timedSheetManager.currentSheet.value != null) {
                    dismissTimedSheet()
                } else {
                    _currentSheet.update { null }
                }
            }

            else -> _currentSheet.update { null }
        }
        showQueuedPairingCodeSheet()
        if (shouldFlushDeferredScan) flushDeferredScan()
    }

    // endregion

    // region Toasts
    private val toastManager = toastManagerProvider(viewModelScope)
    val currentToast: StateFlow<Toast?> = toastManager.currentToast

    fun toast(
        type: Toast.ToastType,
        title: String,
        description: String? = null,
        autoHide: Boolean = true,
        visibilityTime: Long = Toast.VISIBILITY_TIME_DEFAULT,
        testTag: String? = null,
    ) {
        toastManager.enqueue(
            Toast(
                type = type,
                title = title,
                description = description,
                autoHide = autoHide,
                visibilityTime = visibilityTime,
                testTag = testTag,
            )
        )
    }

    fun toast(error: Throwable) {
        if (error.isTrezorUserCancellation()) return
        toast(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.common__error),
            description = error.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.common__error_body),
        )
    }

    fun toast(toast: Toast) {
        toast(
            type = toast.type,
            title = toast.title,
            description = toast.description,
            autoHide = toast.autoHide,
            visibilityTime = toast.visibilityTime,
            testTag = toast.testTag,
        )
    }

    fun hideToast() = toastManager.dismissCurrentToast()

    fun pauseToast() = toastManager.pauseCurrentToast()

    fun resumeToast() = toastManager.resumeCurrentToast()
    // endregion

    // region security
    private suspend fun resetIsAuthenticatedStateInternal() {
        val settings = settingsStore.data.first()
        val needsAuth = settings.isPinEnabled
        _isAuthenticated.value = !needsAuth
        if (!needsAuth) flushDeferredScan()
    }

    fun resetIsAuthenticatedState() {
        viewModelScope.launch {
            resetIsAuthenticatedStateInternal()
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

    suspend fun canDecodeClipboard(text: String): Boolean = withContext(bgDispatcher) {
        SamRockSetupRequest.isProtocolUrl(text) || runCatching { coreService.decode(text) }.isSuccess
    }

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
                    confirmedWarnings = (it.confirmedWarnings + warning).toImmutableList()
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
        if (isSubmittingPaymentRequest) return

        val contactPaymentContext = synchronized(contactPaymentContextLock) { activeContactPaymentContext }
        if (_sendUiState.value.isPaymentRequest && contactPaymentContext?.incomingPaymentRequest == null) {
            rejectMismatchedPaymentRequest()
            return
        }

        isSubmittingPaymentRequest = contactPaymentContext?.incomingPaymentRequest != null
        if (_sendUiState.value.hardwareWalletId != null) {
            _sendUiState.update { it.copy(shouldConfirmPay = false) }
            setSendEffect(SendEffect.NavigateToHardwareSign)
            return
        }
        viewModelScope.launch {
            try {
                _sendUiState.update { it.copy(shouldConfirmPay = false) }
                proceedWithPayment(contactPaymentContext)
            } finally {
                isSubmittingPaymentRequest = false
            }
        }
    }

    suspend fun prepareHardwareContactPayment(): Boolean {
        val contactPaymentContext = synchronized(contactPaymentContextLock) { activeContactPaymentContext }
        if (isPreparedContactPayment(contactPaymentContext)) return true

        val incomingPaymentRequest = contactPaymentContext?.incomingPaymentRequest
        val preparedPaymentProofRequest = preparePaymentProof(incomingPaymentRequest).getOrNull()
        if (!prepareContactPayment(contactPaymentContext)) {
            cancelPaymentProofPreparation(preparedPaymentProofRequest)
            return false
        }
        if (preparedPaymentProofRequest != null) {
            val walletId = _sendUiState.value.hardwareWalletId ?: WalletScope.default
            markOnchainPaymentStarted(incomingPaymentRequest, _sendUiState.value.address, walletId).onFailure {
                synchronized(contactPaymentContextLock) {
                    if (preparedContactPaymentContext == contactPaymentContext) preparedContactPaymentContext = null
                }
                cancelPaymentProofPreparation(preparedPaymentProofRequest)
                handlePaymentPreparationFailure(it, contactPaymentContext)
                return false
            }
        }
        return true
    }

    fun completeHardwareContactPayment(txId: String) {
        val incomingPaymentRequest = synchronized(contactPaymentContextLock) {
            activeContactPaymentContext?.incomingPaymentRequest
        }
        completeOnchainPaymentProofInBackground(incomingPaymentRequest, txId)
    }

    fun onHardwareSignCancelled() {
        cancelHardwarePaymentRequestIfNeeded()
    }

    private fun cancelHardwarePaymentRequestIfNeeded() {
        val request = synchronized(contactPaymentContextLock) {
            activeContactPaymentContext
                ?.takeIf { it == preparedContactPaymentContext && _sendUiState.value.hardwareWalletId != null }
                ?.incomingPaymentRequest
        }
        if (request == null) {
            isSubmittingPaymentRequest = false
            return
        }

        viewModelScope.launch {
            try {
                paykitPaymentProofRepo.failOnchainPayment(request)
            } finally {
                isSubmittingPaymentRequest = false
            }
        }
    }

    fun onSendSuccess(
        details: NewTransactionSheetDetails,
        allowDuplicateHash: Boolean = false,
        walletId: String = WalletScope.default,
        navigate: Boolean = true,
    ) {
        details.paymentHashOrTxId?.let {
            val isNewPayment = synchronized(processedPaymentsLock) {
                processedPayments.add(it)
            }
            when {
                isNewPayment -> syncContactForActivity(it, walletId)
                !allowDuplicateHash -> {
                    Logger.debug("Skipped duplicate processed payment '$it'", context = TAG)
                    return
                }
            }
        }

        _successSendUiState.update { details }
        if (navigate) setSendEffect(SendEffect.PaymentSuccess)
    }

    private fun syncContactForActivity(
        paymentHashOrTxId: String,
        walletId: String = WalletScope.default,
    ) {
        val contactContext = synchronized(contactPaymentContextLock) {
            val pendingContext = pendingContactPaymentContexts.remove(paymentHashOrTxId)
            val context = pendingContext ?: activeContactPaymentContext
            if (pendingContext == null && context != null) {
                activeContactPaymentContext = null
            }
            if (preparedContactPaymentContext == context) preparedContactPaymentContext = null
            context
        }
        isSubmittingPaymentRequest = false
        contactContext ?: return

        viewModelScope.launch {
            activityRepo.setContact(
                contactPublicKey = contactContext.publicKey,
                forPaymentId = paymentHashOrTxId,
                syncLdkPayments = walletId == WalletScope.default,
                walletId = walletId,
            )
        }
    }

    private suspend fun consumePrivatePaymentListIfNeeded(context: ContactPaymentContext?): Result<Unit> {
        context ?: return Result.success(Unit)
        val privatePaymentContext = context.privatePaymentContext ?: return Result.success(Unit)
        return privatePaykitRepo.consumePrivatePaymentList(context.publicKey, privatePaymentContext)
    }

    private suspend fun acceptIncomingPaymentRequestIfNeeded(context: ContactPaymentContext?): Result<Unit> {
        val request = context?.incomingPaymentRequest ?: return Result.success(Unit)
        return paykitPaymentRequestRepo.accept(request)
    }

    fun showPaymentRequests() {
        showSheet(Sheet.PaymentRequests)
        viewModelScope.launch { refreshPaymentRequestTargets(force = true) }
    }

    fun subscription(id: PaykitSubscriptionId): PaykitSubscription? =
        paykitPaymentRequestRepo.subscriptions.value.firstOrNull { it.id == id }

    fun subscriptionAcceptedAt(id: PaykitSubscriptionId) =
        subscription(id)?.let(paykitPaymentRequestRepo::acceptedAt)

    suspend fun acceptSubscriptionAndStartPayment(
        displayedSubscription: PaykitSubscription,
    ): Result<Boolean> {
        if (!_isAcceptingSubscription.compareAndSet(false, true)) {
            return Result.failure<Boolean>(PaykitPaymentRequestError.OperationInProgress).onFailure(::toast)
        }

        return try {
            runSuspendCatching {
                val subscription = subscription(displayedSubscription.id)
                    ?.takeIf { it == displayedSubscription }
                    ?: throw PaykitPaymentRequestError.RequestUnavailable
                val acceptedDueRequest = paykitPaymentRequestRepo.accept(subscription).getOrThrow()
                if (acceptedDueRequest == null) {
                    val accepted = subscription(displayedSubscription.id)
                    if (accepted?.lifecycleState != PaymentRequestLifecycleState.ACTIVE_RECURRING) {
                        throw PaykitPaymentRequestError.RequestUnavailable
                    }
                    return@runSuspendCatching false
                }

                val resolution = privatePaykitRepo.beginPaymentRequestWaitingForUpdatedList(acceptedDueRequest)
                    .getOrElse { error ->
                        showInitialSubscriptionPaymentFailure(acceptedDueRequest, error)
                        return@runSuspendCatching true
                    }
                if (resolution !is PublicPaykitPaymentResult.Opened) {
                    showInitialSubscriptionPaymentFailure(
                        acceptedDueRequest,
                        PaykitPaymentRequestError.RequestUnavailable,
                    )
                    return@runSuspendCatching true
                }
                val scanJob = openContactPayment(
                    paymentRequest = resolution.paymentRequest,
                    publicKey = acceptedDueRequest.counterparty,
                    privatePaymentContext = resolution.privatePaymentContext,
                    incomingPaymentRequest = acceptedDueRequest,
                    isInitialSubscriptionPayment = true,
                )
                scanJob?.join()
                sheetTransitionJob?.join()
                if (_currentSheet.value !is Sheet.Send) {
                    paykitPaymentRequestRepo.markPresented(acceptedDueRequest)
                    val error = PaykitPaymentRequestError.RequestUnavailable
                    val failure = error.toSendFailureDetails(
                        context,
                        _sendUiState.value.currentLightningPaymentRequest()
                    )
                    showSheet(Sheet.Send(SendRoute.errorFromFailure(failure)))
                }
                true
            }.onFailure(::toast)
        } finally {
            _isAcceptingSubscription.update { false }
        }
    }

    private suspend fun showInitialSubscriptionPaymentFailure(
        request: PaykitPaymentRequest,
        error: Throwable,
    ) {
        val paymentContext = ContactPaymentContext(
            publicKey = request.counterparty,
            incomingPaymentRequest = request,
            isInitialSubscriptionPayment = true,
        )
        setActiveContactPaymentContext(paymentContext)
        resetSendState(
            contactPaymentProfile = activeContactPaymentProfile(),
            isPaymentRequest = true,
            isSubscriptionPayment = true,
            isInitialSubscriptionPayment = true,
            incomingPaymentRequestId = request.id,
        )
        paykitPaymentRequestRepo.markPresented(request)
        val failure = error.toSendFailureDetails(context, paymentRequest = null)
        if (_currentSheet.value is Sheet.Send) {
            setSendEffect(SendEffect.NavigateToError(failure))
        } else {
            showSheet(Sheet.Send(SendRoute.errorFromFailure(failure)))
        }
    }

    suspend fun cancelSubscription(id: PaykitSubscriptionId): Result<Unit> {
        val subscription = subscription(id) ?: return Result.failure(PaykitPaymentRequestError.RequestUnavailable)
        return paykitPaymentRequestRepo.cancel(subscription)
            .onFailure(::toast)
    }

    fun openIncomingPaymentRequest(id: PaykitPaymentRequestId) {
        openIncomingPaymentRequestWithTags(id, emptyList())
    }

    fun openIncomingPaymentRequestWithTags(id: PaykitPaymentRequestId, tags: List<String>) {
        val request = paykitPaymentRequestRepo.pendingRequest(id) ?: return
        if (paykitPaymentRequestRepo.isProcessing(request) || requestedPaymentRequestId != null) {
            toast(PaykitPaymentRequestError.OperationInProgress)
            return
        }
        invalidatePaymentRequestPresentation()
        clearPaymentRequestPresentationRetry(id)
        requestedPaymentRequestId = id
        requestedPaymentRequestTags = tags.filter(String::isNotBlank).distinct().toImmutableList()

        if (
            _currentSheet.value is Sheet.PaymentRequests ||
            _currentSheet.value is Sheet.Subscription ||
            _currentSheet.value is Sheet.Send
        ) {
            hideSheet(shouldFlushDeferredScan = false)
            paymentRequestSheetTransitionJob?.cancel()
            val job = viewModelScope.launch {
                delay(SCREEN_TRANSITION_DELAY)
                paymentRequestSheetTransitionJob = null
                presentNextIncomingPaykitPaymentRequest()
            }
            paymentRequestSheetTransitionJob = job
        } else {
            viewModelScope.launch { presentNextIncomingPaykitPaymentRequest() }
        }
    }

    fun rejectIncomingPaymentRequest(request: PaykitPaymentRequest) {
        if (requestedPaymentRequestId == request.id || request.id in _rejectingPaymentRequestIds.value) {
            toast(PaykitPaymentRequestError.OperationInProgress)
            return
        }
        _rejectingPaymentRequestIds.update { it + request.id }
        viewModelScope.launch {
            try {
                paykitPaymentRequestRepo.reject(request).onFailure(::toast)
            } finally {
                _rejectingPaymentRequestIds.update { it - request.id }
            }
        }
    }

    fun retryIncomingPaymentRequest(id: PaykitPaymentRequestId) {
        if (_sendUiState.value.isInitialSubscriptionPayment && _currentSheet.value is Sheet.Send) {
            retryInitialSubscriptionPaymentInCurrentSheet(id, _sendUiState.value.selectedTags)
            return
        }
        clearActiveContactPaymentContext()
        viewModelScope.launch {
            refreshIncomingPaykitPaymentRequests()
            openIncomingPaymentRequestWithTags(id, _sendUiState.value.selectedTags)
        }
    }

    private fun retryInitialSubscriptionPaymentInCurrentSheet(
        id: PaykitPaymentRequestId,
        tags: ImmutableList<String>,
    ) {
        if (!_isRetryingInitialSubscriptionPayment.compareAndSet(false, true)) {
            toast(PaykitPaymentRequestError.OperationInProgress)
            return
        }
        clearActiveContactPaymentContext()
        viewModelScope.launch {
            try {
                refreshIncomingPaykitPaymentRequests()
                val request = paykitPaymentRequestRepo.pendingRequest(id) ?: run {
                    toast(PaykitPaymentRequestError.RequestUnavailable)
                    return@launch
                }
                val resolution = privatePaykitRepo.beginPaymentRequestWaitingForUpdatedList(request).getOrElse {
                    showInitialSubscriptionPaymentFailure(request, it)
                    return@launch
                }
                if (resolution !is PublicPaykitPaymentResult.Opened) {
                    showInitialSubscriptionPaymentFailure(request, PaykitPaymentRequestError.RequestUnavailable)
                    return@launch
                }
                val scanJob = openContactPayment(
                    paymentRequest = resolution.paymentRequest,
                    publicKey = request.counterparty,
                    privatePaymentContext = resolution.privatePaymentContext,
                    incomingPaymentRequest = request,
                    isInitialSubscriptionPayment = true,
                    selectedTags = tags,
                )
                scanJob?.join()
            } finally {
                _isRetryingInitialSubscriptionPayment.update { false }
            }
        }
    }

    suspend fun dismissIncomingPaymentRequest(request: PaykitPaymentRequest): Result<Unit> {
        if (requestedPaymentRequestId == request.id || request.id in _rejectingPaymentRequestIds.value) {
            return Result.failure<Unit>(PaykitPaymentRequestError.OperationInProgress).onFailure(::toast)
        }
        _rejectingPaymentRequestIds.update { it + request.id }
        return try {
            paykitPaymentRequestRepo.dismiss(request).onFailure(::toast)
        } finally {
            _rejectingPaymentRequestIds.update { it - request.id }
        }
    }

    private suspend fun createPaymentRequest(
        draft: PaykitPaymentRequestDraft,
        target: PaykitPaymentRequestTarget,
    ): Result<PaykitPaymentRequestCreation> = paykitPaymentRequestRepo.propose(
        draft = draft,
        target = target,
        savedPublicKeys = pubkyRepo.contacts.value.map { it.publicKey },
    )

    fun createPaymentRequest(
        draft: PaykitPaymentRequestDraft,
        target: PaykitPaymentRequestTarget,
        onCreated: (PaykitPaymentRequest) -> Unit,
    ) {
        val sourceReceiveSheet = currentSheet.value as? Sheet.Receive
        viewModelScope.launch {
            createPaymentRequest(draft, target)
                .onSuccess { creation ->
                    val creatorIsCurrent = PubkyPublicKeyFormat.matches(
                        creation.creatorIdentity,
                        pubkyRepo.publicKey.value,
                    )
                    if (creation.wasPublishedToActiveState && creatorIsCurrent) {
                        onCreated(creation.request)
                    } else {
                        if (sourceReceiveSheet != null && currentSheet.value === sourceReceiveSheet) hideSheet()
                        toast(
                            type = Toast.ToastType.INFO,
                            title = context.getString(R.string.wallet__payment_request),
                            description = context.getString(R.string.wallet__payment_request_queued_description),
                            testTag = "PaymentRequestQueuedToast",
                        )
                    }
                }
                .onFailure(::toast)
        }
    }

    private fun handlePaymentPreparationFailure(error: Throwable, contactPaymentContext: ContactPaymentContext?) {
        if (contactPaymentContext?.isInitialSubscriptionPayment == true) {
            setSendEffect(
                SendEffect.NavigateToError(
                    error.toSendFailureDetails(context, _sendUiState.value.currentLightningPaymentRequest())
                )
            )
        } else {
            toast(error)
            hideSheet()
        }
    }

    fun handleDeeplinkIntent(intent: Intent) {
        if (intent.action !in DEEPLINK_ACTIONS) return
        intent.data?.let { uri ->
            Logger.debug("Received deeplink '${uri.toString().sanitizedDeeplinkLogValue()}'", context = TAG)
            processDeeplink(uri)
        }
    }

    fun onUsbDeviceAttached(
        deviceId: String? = null,
        deviceModel: String = "",
    ) {
        hwWalletRepo.onTransportRestored(TransportType.USB)
        deviceId ?: return

        viewModelScope.launch {
            if (hwWalletRepo.hasKnownDevice(deviceId)) return@launch
            if (isHighPrioritySheet(_currentSheet.value)) return@launch
            if (_currentSheet.value is Sheet.Hardware) return@launch

            showSheet(
                Sheet.Hardware(
                    route = HardwareRoute.Found(
                        deviceId = deviceId,
                        deviceModel = deviceModel,
                    ),
                )
            )
        }
    }

    fun submitPairingCode(code: String) = hwWalletRepo.submitPairingCode(code)

    fun cancelPairingCode() = hwWalletRepo.cancelPairingCode()

    /**
     * The device asks for its one-time pairing code mid-connect, which can happen on
     * any screen via silent reconnects, so the sheet is shown app-wide. High-priority
     * sheets are not interrupted: the pairing sheet waits until the active sheet closes.
     */
    private fun showPairingCodeSheet(requestId: Long) {
        if (isHighPrioritySheet(_currentSheet.value)) {
            queuedPairingCodeRequestId = requestId
            return
        }

        // The Connect Hardware flow drives pair-code requests through its own NavHost. An app-wide
        // PairCode sheet has no connect back stack to preserve, so replace its start route when the
        // device requests a new code and force the input state to reset.
        val currentSheet = _currentSheet.value
        if (currentSheet is Sheet.Hardware) {
            if (currentSheet.route is HardwareRoute.PairCode && currentSheet.route.requestId != requestId) {
                _currentSheet.update { Sheet.Hardware(route = HardwareRoute.PairCode(requestId)) }
            }
            return
        }

        queuedPairingCodeRequestId = null
        showSheet(Sheet.Hardware(route = HardwareRoute.PairCode(requestId)))
    }

    private fun showQueuedPairingCodeSheet() {
        val requestId = queuedPairingCodeRequestId ?: return
        if (!hwWalletRepo.needsPairingCode.value) {
            queuedPairingCodeRequestId = null
            return
        }

        showPairingCodeSheet(requestId)
    }

    private fun isHighPrioritySheet(sheet: Sheet?) = sheet is Sheet.Gift ||
        sheet is Sheet.Send ||
        sheet is Sheet.BTCPayConnection ||
        sheet is Sheet.LnurlAuth ||
        sheet is Sheet.Pin ||
        sheet is Sheet.PubkyAuth

    fun clearPendingPubkyImport() {
        viewModelScope.launch {
            pubkyRepo.clearPendingImport()
        }
    }

    private fun processDeeplink(uri: Uri) = viewModelScope.launch {
        val value = uri.toString()
        if (SamRockSetupRequest.isProtocolUrl(value)) {
            if (!walletRepo.walletExists()) return@launch

            launchScan(source = ScanSource.DEEPLINK, data = value, startDelay = SCREEN_TRANSITION_DELAY)
            return@launch
        }

        if (ScreenDeepLinks.isScreenDeepLink(uri)) {
            if (!ScreenDeepLinks.shouldQueue(settingsStore.data.first().isDevModeEnabled)) {
                Logger.warn("Ignoring screen deeplink, not queued", context = TAG)
                return@launch
            }

            _pendingScreenDeepLink.value = uri
            return@launch
        }

        if (uri.isRecoveryModeDeeplink()) {
            lightningRepo.setRecoveryMode(enabled = true)
            delay(SCREEN_TRANSITION_DELAY)
            mainScreenEffect(
                MainScreenEffect.Navigate(
                    route = Routes.RecoveryMode,
                    clearStack = true,
                )
            )
            return@launch
        }

        PubkyRingAuthCallback.parse(uri)?.let {
            if (!isPaykitEnabled.value) return@launch
            handlePubkyRingAuthCallback(it)
            return@launch
        }

        if (uri.scheme == PUBKYAUTH_SCHEME) {
            if (!isPaykitEnabled.value) return@launch
            handlePubkyAuth(uri.toString())
            return@launch
        }

        if (!walletRepo.walletExists()) return@launch

        launchScan(source = ScanSource.DEEPLINK, data = value, startDelay = SCREEN_TRANSITION_DELAY)
    }

    fun consumeScreenDeepLink() {
        _pendingScreenDeepLink.value = null
    }

    private fun Uri.isRecoveryModeDeeplink(): Boolean {
        val normalizedScheme = scheme?.lowercase()
        if (normalizedScheme != BITKIT_SCHEME) return false

        return host == RECOVERY_MODE_DEEPLINK ||
            pathSegments.singleOrNull() == RECOVERY_MODE_DEEPLINK
    }

    private suspend fun handlePubkyAuth(authUrl: String) {
        if (pubkyRepo.publicKey.value == null) {
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.pubky_auth__no_identity),
                description = context.getString(R.string.pubky_auth__no_identity_desc),
            )
            return
        }

        if (!pubkyRepo.hasSecretKey()) {
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = context.getString(R.string.profile__auth_approval_ring_only),
            )
            return
        }
        showSheet(Sheet.PubkyAuth(authUrl))
    }

    private suspend fun handlePubkyRingAuthCallback(callback: PubkyRingAuthCallback) {
        when (val result = pubkyRepo.handleAuthCallback(callback)) {
            is PubkyRingAuthCallbackHandlingResult.TrustedError -> {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__auth_error_title),
                    description = result.message ?: context.getString(R.string.other__qr_error_text),
                )
            }
            PubkyRingAuthCallbackHandlingResult.Handled,
            PubkyRingAuthCallbackHandlingResult.Ignored,
            -> Unit
        }
    }

    // TODO Temporary fix while these schemes can't be decoded https://github.com/synonymdev/bitkit-core/issues/70
    private fun String.removeLightningSchemes(): String = LIGHTNING_SCHEME_PATTERNS.fold(this) { acc, regex ->
        acc.replace(regex, "")
    }

    fun checkTimedSheets() = timedSheetManager.onHomeScreenEntered()

    fun onHomeResumed() {
        checkTimedSheets()
        hwWalletRepo.onAppForegrounded()
        viewModelScope.launch {
            refreshIncomingPaykitPaymentRequests()
            refreshPaymentRequestTargets(force = true)
        }
    }

    fun onLeftHome() = timedSheetManager.onHomeScreenExited()

    fun dismissTimedSheet() = timedSheetManager.dismissCurrentSheet()

    private suspend fun checkCriticalAppUpdate() = withContext(bgDispatcher) {
        if (Env.isDebug) return@withContext

        delay(SCREEN_TRANSITION_DELAY)

        runCatching {
            val androidReleaseInfo = appUpdaterService.getReleaseInfo().platforms.android
            val currentBuildNumber = BuildConfig.VERSION_CODE

            if (androidReleaseInfo.buildNumber <= currentBuildNumber) return@withContext

            if (androidReleaseInfo.isCritical) {
                mainScreenEffect(
                    MainScreenEffect.Navigate(
                        route = Routes.CriticalUpdate,
                        clearStack = true,
                    )
                )
            }
        }.onFailure { e ->
            Logger.warn("Failure fetching new releases", e, context = TAG)
        }
    }

    companion object {
        private const val TAG = "AppViewModel"
        private val LIGHTNING_SCHEME_PATTERNS = listOf("lightning", "lnurl", "lnurlw", "lnurlc", "lnurlp")
            .map { Regex("^$it:", RegexOption.IGNORE_CASE) }
        private const val SEND_AMOUNT_WARNING_THRESHOLD = 100.0
        private const val TEN_USD = 10
        private const val MAX_BALANCE_FRACTION = 0.5
        private const val MAX_FEE_AMOUNT_RATIO = 0.5
        private const val HW_SEND_FALLBACK_TX_VBYTES = 1_200uL
        private const val HW_SEND_FALLBACK_SATS_PER_VBYTE = 3uL
        private val SCREEN_TRANSITION_DELAY = TRANSITION_SCREEN_MS.milliseconds
        private const val MIGRATION_LOADING_TIMEOUT_MS = 120_000L
        private const val POST_RESTORE_PRUNE_DELAY_MS = 30_000L
        private const val MIGRATION_AUTH_RESET_DELAY_MS = 500L
        private const val REMOTE_RESTORE_NODE_RESTART_DELAY_MS = 500L
        private const val AUTH_CHECK_INITIAL_DELAY_MS = 1000L
        private const val AUTH_CHECK_SPLASH_DELAY_MS = 500L
        private const val ADDRESS_VALIDATION_DEBOUNCE_MS = 1000L
        private const val PAYKIT_CHANNEL_USABILITY_REFRESH_DELAY_MS = 5_000L
        private val PAYKIT_PAYMENT_REQUEST_REFRESH_INTERVALS = listOf(30.seconds, 60.seconds, 120.seconds)
        private val INITIAL_PAYKIT_SYNC_RETRY_DELAYS = List(14) { 2.seconds }
        private val PAYKIT_PAYMENT_REQUEST_PRESENTATION_RETRY_DELAYS = List(14) { 2.seconds }
        private val PUBLIC_PAYKIT_SYNC_DEBOUNCE = 1.seconds
        private val PUBLIC_PAYKIT_BOLT11_REFRESH_WINDOW = 30.minutes
        private const val BITKIT_SCHEME = "bitkit"
        private const val PUBKYAUTH_SCHEME = "pubkyauth"
        private const val RECOVERY_MODE_DEEPLINK = "recovery-mode"

        /** Max characters kept in a scan log id before truncating. */
        private const val SCAN_LOG_ID_MAX_LENGTH = 24

        /** Characters kept on each side of a truncated scan log id. */
        private const val SCAN_LOG_ID_AFFIX_LENGTH = 11

        private val LNURL_WITHDRAW_EXPIRY_SEC = 1.hours.inWholeSeconds.toUInt()

        /** Intent actions carrying a deeplink URI: browsers and apps send VIEW, NFC tag taps send NDEF_DISCOVERED. */
        internal val DEEPLINK_ACTIONS = setOf(Intent.ACTION_VIEW, NfcAdapter.ACTION_NDEF_DISCOVERED)
    }
}

private enum class ScanSource(val label: String) {
    PASTE("paste"),
    SCAN_RESULT("scan result"),
    SCANNER_SHEET("scanner sheet"),
    ADDRESS_CONTINUE("address continue"),
    DEEPLINK("deeplink"),
}

private data class ScheduledScan(
    val job: Job,
    val normalizedInput: String,
    val contactPaymentContext: ContactPaymentContext?,
    val mustComplete: Boolean,
)

private data class DeferredScan(
    val source: ScanSource,
    val data: String,
    val startDelay: Duration,
    val routePubkyKeys: Boolean,
    val contactPaymentContext: ContactPaymentContext?,
)

// region send contract
@Stable
data class SendUiState(
    val address: String = "",
    val bolt11: String? = null,
    val addressInput: String = "",
    val isAddressInputValid: Boolean = false,
    val amount: ULong = 0u,
    val isAmountInputValid: Boolean = false,
    val isUnified: Boolean = false,
    val canSwitchWallet: Boolean = false,
    val canSwitchFundingSource: Boolean = false,
    val isFundingSourceLoading: Boolean = false,
    val payMethod: SendMethod = SendMethod.ONCHAIN,
    val selectedTags: ImmutableList<String> = persistentListOf(),
    val decodedInvoice: LightningInvoice? = null,
    val showSanityWarningDialog: SanityWarning? = null,
    val confirmedWarnings: ImmutableList<SanityWarning> = persistentListOf(),
    val shouldConfirmPay: Boolean = false,
    val selectedUtxos: ImmutableList<SpendableUtxo>? = null,
    val lnurl: LnurlParams? = null,
    val isLoading: Boolean = false,
    val speed: TransactionSpeed = TransactionSpeed.default(),
    val comment: String = "",
    val feeRates: FeeRates? = null,
    val onchainFeeUi: OnchainFeeUi = OnchainFeeUi(),
    val lightningFeeSats: Long? = null,
    val estimatedRoutingFee: ULong = 0uL,
    val lastLightningFee: Long = 0L,
    val contactPaymentProfile: PubkyProfile? = null,
    val isPaymentRequest: Boolean = false,
    val hardwareWalletId: String? = null,
    val hardwareWalletName: String? = null,
    val hardwareAvailableSats: ULong = 0uL,
    val isSubscriptionPayment: Boolean = false,
    val isInitialSubscriptionPayment: Boolean = false,
    val initialSubscriptionPaymentAutoStartPending: Boolean = false,
    val incomingPaymentRequestId: PaykitPaymentRequestId? = null,
)

@Immutable
data class OnchainFeeUi(
    val rate: FeeRate = FeeRate.fromSpeed(TransactionSpeed.default()),
    val sats: Long? = null,
    val estimates: ImmutableMap<FeeRate, Long> = persistentMapOf(),
    val isLoading: Boolean = false,
)

enum class SanityWarning(@StringRes val message: Int, val testTag: String) {
    VALUE_OVER_100_USD(R.string.wallet__send_dialog1, "SendDialog1"),
    OVER_HALF_BALANCE(R.string.wallet__send_dialog2, "SendDialog2"),
    FEE_OVER_HALF_VALUE(R.string.wallet__send_dialog3, "SendDialog3"),
    FEE_OVER_10_USD(R.string.wallet__send_dialog4, "SendDialog4"),
    // TODO SendDialog5 https://github.com/synonymdev/bitkit/blob/master/src/screens/Wallets/Send/ReviewAndSend.tsx#L457-L466
}

enum class SendMethod { ONCHAIN, LIGHTNING }

private sealed interface SendFundingSource {
    data object Spending : SendFundingSource
    data object Savings : SendFundingSource
    data class Hardware(val walletId: String) : SendFundingSource
}

data class ContactPaymentContext(
    val publicKey: String,
    val privatePaymentContext: PrivatePaykitPaymentContext? = null,
    val incomingPaymentRequest: PaykitPaymentRequest? = null,
    val isInitialSubscriptionPayment: Boolean = false,
    val selectedTags: ImmutableList<String> = persistentListOf(),
)

private data class PaymentProofPreparation(
    val endpointIdentifier: String,
    val kind: PaykitPaymentProofKind,
)

private data class PaykitContactSyncState(
    val publicKey: String?,
    val contactKeys: Set<String>,
    val contactsLoaded: Boolean,
    val isPaykitEnabled: Boolean,
)

sealed class SendEffect {
    data class PopBack(val route: SendRoute) : SendEffect()
    data object NavigateToAddress : SendEffect()
    data object NavigateToAmount : SendEffect()
    data object NavigateToScan : SendEffect()
    data object NavigateToConfirm : SendEffect()
    data object NavigateToHardwareSign : SendEffect()
    data object NavigateToWithdrawConfirm : SendEffect()
    data object NavigateToWithdrawError : SendEffect()
    data object NavigateToCoinSelection : SendEffect()
    data object NavigateToQuickPay : SendEffect()
    data object NavigateToFee : SendEffect()
    data object NavigateToFeeCustom : SendEffect()
    data object NavigateToContacts : SendEffect()
    data object NavigateToComingSoon : SendEffect()
    data object PaymentSuccess : SendEffect()
    data class NavigateToError(val failure: SendFailureDetails) : SendEffect()
    data class NavigateToPending(
        val paymentHash: String,
        val amount: Long,
        val observeResolution: Boolean = true,
    ) : SendEffect()
}

sealed class MainScreenEffect {
    data class Navigate(
        val route: Routes,
        val clearStack: Boolean = false,
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
    data object StartInitialSubscriptionPayment : SendEvent
    data object CancelInitialSubscriptionPayment : SendEvent
    data object SpeedAndFee : SendEvent
    data object PaymentMethodSwitch : SendEvent
    data class ConfirmAmountWarning(val warning: SanityWarning) : SendEvent
    data object EstimateMaxRoutingFee : SendEvent
    data object DismissAmountWarning : SendEvent
    data object PayConfirmed : SendEvent
    data object ClearPayConfirmation : SendEvent
    data object BackToAmount : SendEvent
    data object NavToAddress : SendEvent
    data object Contacts : SendEvent
}

private class LightningPaymentFailedError(
    val reason: PaymentFailureReason?,
    val paymentRequest: String?,
) : AppError(reason?.name)

private fun Throwable.isDefiniteOnchainPreBroadcastFailure(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .any {
            it is ServiceError.NodeNotSetup ||
                it is ServiceError.NodeNotStarted ||
                it is NodeException.NotRunning ||
                it is NodeException.OnchainTxCreationFailed ||
                it is NodeException.OnchainTxSigningFailed ||
                it is NodeException.InvalidAddress ||
                it is NodeException.InvalidAmount ||
                it is NodeException.InvalidNetwork ||
                it is NodeException.InvalidFeeRate ||
                it is NodeException.InsufficientFunds ||
                it is NodeException.CoinSelectionFailed ||
                it is NodeException.NoSpendableOutputs
        }

sealed interface LnurlParams {
    data class LnurlPay(val data: LnurlPayData) : LnurlParams
    data class LnurlWithdraw(val data: LnurlWithdrawData) : LnurlParams
}

@Stable
sealed interface QuickPayData {
    val sats: ULong

    @Stable
    data class Bolt11(override val sats: ULong, val bolt11: String) : QuickPayData

    @Stable
    data class LnurlPay(override val sats: ULong, val data: LnurlPayData) : QuickPayData
}

@Stable
data class QuickPayRequest(
    val id: Long,
    val data: QuickPayData,
)
// endregion

internal fun resolvePastedPubkyRoute(
    input: String,
    ownPublicKey: String?,
    contacts: List<PubkyProfile>,
    isPaykitEnabled: Boolean = true,
): Routes? {
    if (!isPaykitEnabled) return null

    val normalizedKey = PubkyPublicKeyFormat.normalized(input) ?: return null

    if (PubkyPublicKeyFormat.matches(normalizedKey, ownPublicKey)) {
        return Routes.Profile
    }

    if (contacts.any { PubkyPublicKeyFormat.matches(it.publicKey, normalizedKey) }) {
        return Routes.ContactDetail(normalizedKey)
    }

    return Routes.AddContact(normalizedKey)
}
