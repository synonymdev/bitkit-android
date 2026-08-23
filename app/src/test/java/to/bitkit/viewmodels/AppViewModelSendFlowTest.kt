package to.bitkit.viewmodels

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import androidx.core.net.toUri
import app.cash.turbine.test
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.TransactionDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.check
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.App
import to.bitkit.CurrentActivity
import to.bitkit.R
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.domain.commands.NotifyChannelReadyHandler
import to.bitkit.domain.commands.NotifyPaymentReceived
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.models.BalanceState
import to.bitkit.models.HwWalletReceivedTx
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.PubkyProfile
import to.bitkit.models.SamRockPaymentMethod
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.models.SendFailureDetails
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransportType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.NodeEventUpdate
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaymentPendingException
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.PrivatePaykitPaymentContext
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.repositories.QuickPayCompletionKind
import to.bitkit.repositories.QuickPayCompletionOutcome
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.repositories.SamRockRepo
import to.bitkit.repositories.SettledReceiveAddress
import to.bitkit.repositories.SettledReceiveInvoice
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.services.ActivityService
import to.bitkit.services.AppUpdaterService
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.services.NodeServiceFgState
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.Routes
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.TimedSheetType
import to.bitkit.ui.shared.toast.ToastQueueManager
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute
import to.bitkit.ui.theme.TRANSITION_SCREEN_MS
import to.bitkit.ui.utils.ScreenDeepLinks
import to.bitkit.usecases.FormatMoneyValue
import to.bitkit.usecases.RefreshContactPaykitReceiversUseCase
import to.bitkit.utils.AppError
import to.bitkit.utils.timedsheets.TimedSheetManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import com.synonym.bitkitcore.Activity as BitkitActivity

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass")
class AppViewModelSendFlowTest : BaseUnitTest() {

    private lateinit var sut: AppViewModel

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val connectivityRepo = mock<ConnectivityRepo>()
    private val healthRepo = mock<HealthRepo>()
    private val pendingPaymentRepo = mock<PendingPaymentRepo>()
    private val backupRepo = mock<BackupRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val appUpdaterService = mock<AppUpdaterService>()
    private val notifyPaymentReceivedHandler = mock<NotifyPaymentReceivedHandler>()
    private val notifyChannelReadyHandler = mock<NotifyChannelReadyHandler>()
    private val cacheStore = mock<CacheStore>()
    private val quickPayRepo = mock<QuickPayRepo>()
    private val transferRepo = mock<TransferRepo>()
    private val migrationService = mock<MigrationService>()
    private val coreService = mock<CoreService>()
    private val nodeServiceFgState = NodeServiceFgState()
    private val activityService = mock<ActivityService>()
    private val keychain = mock<Keychain>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val publicPaykitRepo = mock<PublicPaykitRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val paykitPaymentRequestRepo = mock<PaykitPaymentRequestRepo>()
    private val samRockRepo = mock<SamRockRepo>()
    private val widgetsRepo = mock<WidgetsRepo>()
    private val formatMoneyValue = mock<FormatMoneyValue>()
    private val refreshContactPaykitReceivers = mock<RefreshContactPaykitReceiversUseCase>()
    private val clipboardManager = mock<ClipboardManager>()
    private val toastManager = mock<ToastQueueManager>()

    private val balanceState = MutableStateFlow(BalanceState())
    private val hwReceivedTxs = MutableSharedFlow<HwWalletReceivedTx>()
    private val needsPairingCode = MutableStateFlow(false)
    private val pairingCodeRequestId = MutableStateFlow<Long?>(null)
    private val settingsData = MutableStateFlow(SettingsData())
    private val isPaykitEnabled = MutableStateFlow(false)
    private val walletState = MutableStateFlow(WalletState())
    private val nodeEventUpdates = MutableSharedFlow<NodeEventUpdate>()
    private val pubkyPublicKey = MutableStateFlow<String?>(null)
    private val pubkyContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    private val pubkyContactsLoadVersion = MutableStateFlow(0L)
    private val pendingPaykitPaymentRequests = MutableStateFlow<List<PaykitPaymentRequest>>(emptyList())
    private val testPublicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

    private val timedSheetManager = mock<TimedSheetManager>()
    private val timedSheetType = MutableStateFlow<TimedSheetType?>(null)

    @Before
    fun setUp() {
        timedSheetType.value = null
        stubRepositories()
        sut = createViewModel()
    }

    @After
    fun tearDown() {
        sut.stopPaykitPaymentRequestPolling()
        App.currentActivity = null
    }

    @Suppress("LongMethod")
    private fun stubRepositories() {
        whenever(context.getString(any())).thenReturn("")
        whenever(context.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(clipboardManager)
        whenever(connectivityRepo.isOnline).thenReturn(MutableStateFlow(ConnectivityState.CONNECTED))
        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(mock()))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(lightningRepo.nodeEventUpdates).thenReturn(nodeEventUpdates)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEventUpdates.map { it.event })
        whenever(hwWalletRepo.receivedTxs).thenReturn(hwReceivedTxs)
        whenever(hwWalletRepo.needsPairingCode).thenReturn(needsPairingCode)
        whenever(hwWalletRepo.pairingCodeRequestId).thenReturn(pairingCodeRequestId)
        whenever(coreService.activity).thenReturn(activityService)
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(walletRepo.getBolt11()).thenAnswer { walletState.value.bolt11 }
        whenever(walletRepo.getOnchainAddress()).thenAnswer { walletState.value.onchainAddress }
        whenever(walletRepo.walletExists()).thenReturn(true)
        whenever(backupRepo.isRestoring).thenReturn(MutableStateFlow(false))
        stubSettingsStore()
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever { quickPayRepo.canApply(any<ULong>()) }.thenReturn(Result.success(false))
        whenever { quickPayRepo.hasOpen(any()) }.thenReturn(false)
        whenever {
            quickPayRepo.signalCompletion(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        }.thenReturn(QuickPayCompletionOutcome.None)
        whenever { activityRepo.findActivityByPaymentId(any(), any(), any(), any()) }
            .thenReturn(Result.failure(Exception("activity not found")))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever { blocktankRepo.refreshInfo() }.thenReturn(Result.success(Unit))
        whenever(timedSheetManager.currentSheet).thenReturn(timedSheetType)
        whenever(migrationService.isShowingMigrationLoading).thenReturn(MutableStateFlow(false))
        whenever { migrationService.needsPostMigrationSync() }.thenReturn(false)
        whenever { migrationService.isMigrationChecked() }.thenReturn(true)
        whenever { widgetsRepo.refreshEnabledWidgets() }.thenReturn(Unit)
        whenever { lightningRepo.updateGeoBlockState() }.thenReturn(Unit)
        whenever(pubkyRepo.sessionRestorationFailed).thenReturn(MutableStateFlow(false))
        whenever(pubkyRepo.publicKey).thenReturn(pubkyPublicKey)
        whenever(pubkyRepo.contacts).thenReturn(pubkyContacts)
        whenever { refreshContactPaykitReceivers(any()) }.thenReturn(Result.success(Unit))
        whenever { publicPaykitRepo.syncLocalReceiverMarker(anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(Unit))
        whenever(pubkyRepo.contactsLoadVersion).thenReturn(pubkyContactsLoadVersion)
        whenever(paykitPaymentRequestRepo.pendingRequests).thenReturn(pendingPaykitPaymentRequests)
        whenever(paykitPaymentRequestRepo.isPending(any())).thenReturn(true)
        whenever(privatePaykitRepo.initialLinkBurstStarted).thenReturn(MutableSharedFlow())
        whenever { privatePaykitRepo.prepareSavedContacts(any<Collection<String>>(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.pruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.refreshKnownSavedContactEndpoints(any(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.reconcileReservedReceiveIndexes() }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.retryPendingEndpointRemoval(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.removeSavedContact(any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.reconcileReceivedPayments() }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.handleOnchainActivity(any<Collection<String>>()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.contactPublicKeyForPrivateInvoicePaymentHash(any()) }
            .thenReturn(null)
        whenever { publicPaykitRepo.refreshPublishedBolt11ForPayment(any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.handleReceivedPayment(any()) }
            .thenReturn(Result.success(Unit))
        whenever { notifyPaymentReceivedHandler(any()) }
            .thenReturn(Result.success(NotifyPaymentReceived.Result.Skip))
        whenever { notifyPaymentReceivedHandler.present(any(), any(), any()) }.thenAnswer {
            val canPresent = it.getArgument<() -> Boolean>(1)
            if (!canPresent()) {
                false
            } else {
                it.getArgument<() -> Unit>(2).invoke()
                true
            }
        }
        whenever { privatePaykitRepo.contactPublicKeyForPrivateOnchainAddresses(any<Collection<String>>()) }
            .thenReturn(null)
        whenever { privatePaykitRepo.discardRemoteLightningEndpoints(any(), any()) }
            .thenReturn(Result.success(Unit))
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull()))
            .thenReturn(Result.failure(Exception("not mocked")))
        whenever { lightningRepo.calculateTotalFee(any(), anyOrNull(), any(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(100uL))
        whenever { lightningRepo.getFeeRateForSpeed(any(), anyOrNull()) }
            .thenReturn(Result.success(2u))
        whenever(lightningRepo.canSend(any())).thenReturn(true)
        whenever(toastManager.currentToast).thenReturn(MutableStateFlow(null))
    }

    private fun stubSettingsStore() {
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever(settingsStore.isPaykitEnabled).thenReturn(isPaykitEnabled)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsData.value = transform(settingsData.value)
            Unit
        }
    }

    private fun createViewModel() = AppViewModel(
        connectivityRepo = connectivityRepo,
        healthRepo = healthRepo,
        toastManagerProvider = { toastManager },
        timedSheetManagerProvider = { timedSheetManager },
        context = context,
        bgDispatcher = testDispatcher,
        keychain = keychain,
        lightningRepo = lightningRepo,
        pendingPaymentRepo = pendingPaymentRepo,
        walletRepo = walletRepo,
        hwWalletRepo = hwWalletRepo,
        backupRepo = backupRepo,
        settingsStore = settingsStore,
        currencyRepo = currencyRepo,
        activityRepo = activityRepo,
        preActivityMetadataRepo = preActivityMetadataRepo,
        blocktankRepo = blocktankRepo,
        appUpdaterService = appUpdaterService,
        notifyPaymentReceivedHandler = notifyPaymentReceivedHandler,
        notifyChannelReadyHandler = notifyChannelReadyHandler,
        cacheStore = cacheStore,
        quickPayRepo = quickPayRepo,
        transferRepo = transferRepo,
        migrationService = migrationService,
        coreService = coreService,
        nodeServiceFgState = nodeServiceFgState,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
        paykitPaymentRequestRepo = paykitPaymentRequestRepo,
        refreshContactPaykitReceivers = refreshContactPaykitReceivers,
        samRockRepo = samRockRepo,
        appUpdateSheet = mock(),
        backupSheet = mock(),
        notificationsSheet = mock(),
        quickPaySheet = mock(),
        highBalanceSheet = mock(),
        formatMoneyValue = formatMoneyValue,
        widgetsRepo = widgetsRepo,
        pubkyRepo = pubkyRepo,
    )

    private suspend fun emitNodeEvent(
        event: Event,
        settledReceiveInvoice: SettledReceiveInvoice? = null,
        settledReceiveAddress: SettledReceiveAddress? = null,
    ) {
        nodeEventUpdates.emit(
            NodeEventUpdate(
                event = event,
                settledReceiveInvoice = settledReceiveInvoice,
                settledReceiveAddress = settledReceiveAddress,
            ),
        )
    }

    @Test
    fun `onUsbDeviceAttached forwards to the hardware wallet repo`() = test {
        sut.onUsbDeviceAttached()

        verify(hwWalletRepo).onTransportRestored(TransportType.USB)
    }

    @Test
    fun `onHomeResumed forwards app foreground to the hardware wallet repo`() = test {
        sut.onHomeResumed()

        verify(hwWalletRepo).onAppForegrounded()
    }

    @Test
    fun `payment requests refresh immediately and periodically only while polling is active`() = test {
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))
        runCurrent()
        clearInvocations(paykitPaymentRequestRepo)

        sut.startPaykitPaymentRequestPolling()
        try {
            runCurrent()

            verify(paykitPaymentRequestRepo).refresh()
            clearInvocations(paykitPaymentRequestRepo)

            advanceTimeBy(30.seconds.inWholeMilliseconds)
            runCurrent()

            verify(paykitPaymentRequestRepo, atLeast(2)).refresh()
            clearInvocations(paykitPaymentRequestRepo)

            advanceTimeBy(59.seconds.inWholeMilliseconds)
            runCurrent()
            verify(paykitPaymentRequestRepo, never()).refresh()

            advanceTimeBy(1.seconds.inWholeMilliseconds)
            runCurrent()
            verify(paykitPaymentRequestRepo).refresh()
        } finally {
            sut.stopPaykitPaymentRequestPolling()
        }

        clearInvocations(paykitPaymentRequestRepo)
        advanceTimeBy(120.seconds.inWholeMilliseconds)
        runCurrent()

        verify(paykitPaymentRequestRepo, never()).refresh()
    }

    @Test
    fun `payment request waiting for a newer private list is retried after backoff`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val bolt11 = "lnbcrt1updatedpaymentrequest"
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 8uL)
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))
        whenever(privatePaykitRepo.beginPaymentRequest(request)).thenReturn(
            Result.success(PublicPaykitPaymentResult.WaitingForUpdatedPaymentList),
            Result.success(
                PublicPaykitPaymentResult.Opened(
                    paymentRequest = bolt11,
                    privatePaymentContext = privateContext,
                ),
            ),
        )
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)
        whenever(lightningRepo.canSend(request.amountSats)).thenReturn(true)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        runCurrent()

        sut.onHomeResumed()
        runCurrent()
        assertNull(sut.currentSheet.value)
        verify(privatePaykitRepo).beginPaymentRequest(request)
        clearInvocations(privatePaykitRepo)

        advanceTimeBy(29.seconds.inWholeMilliseconds)
        runCurrent()
        verify(privatePaykitRepo, never()).beginPaymentRequest(request)

        advanceTimeBy(1.seconds.inWholeMilliseconds)
        runCurrent()

        verify(privatePaykitRepo).beginPaymentRequest(request)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `unresolvable payment request retries are bounded`() = test {
        val request = paymentRequest()
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))
        whenever(privatePaykitRepo.beginPaymentRequest(request))
            .thenReturn(Result.success(PublicPaykitPaymentResult.WaitingForUpdatedPaymentList))
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        runCurrent()

        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(20.minutes.inWholeMilliseconds)
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        verify(privatePaykitRepo, times(5)).beginPaymentRequest(request)
    }

    @Test
    fun `active contact payment prevents presenting another payment request`() = test {
        val activeRequest = paymentRequest()
        val pendingRequest = activeRequest.copy(paymentRequestId = "next-request")
        setActiveContactPaymentContext(testPublicKey, incomingPaymentRequest = activeRequest)
        pendingPaykitPaymentRequests.value = listOf(pendingRequest)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))
        runCurrent()

        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(30.seconds.inWholeMilliseconds)
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        verify(privatePaykitRepo, never()).beginPaymentRequest(pendingRequest)
        assertEquals(activeRequest, activeContactPaymentContext()?.incomingPaymentRequest)
    }

    @Test
    fun `dismissing a payment request presents the next request with its context`() = test {
        sut.setIsAuthenticated(true)
        val firstRequest = paymentRequest()
        val secondRequest = firstRequest.copy(paymentRequestId = "next-request")
        val firstInvoice = "lnbcrt1firstpendingrequest"
        val secondInvoice = "lnbcrt1secondpendingrequest"
        stubOpenedPaymentRequest(firstRequest, firstInvoice)
        stubOpenedPaymentRequest(secondRequest, secondInvoice, privateListIndex = 8uL)
        stubLightningScan(bolt11 = firstInvoice, amountSats = 0u)
        stubLightningScan(bolt11 = secondInvoice, amountSats = 0u)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        pendingPaykitPaymentRequests.value = listOf(firstRequest, secondRequest)
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.onHomeResumed()
        sut.currentSheet.first {
            it is Sheet.Send && activeContactPaymentContext()?.incomingPaymentRequest == firstRequest
        }

        sut.setIsAuthenticated(false)
        sut.hideSheet()
        runCurrent()

        assertNull(sut.currentSheet.value)
        verify(privatePaykitRepo, never()).beginPaymentRequest(secondRequest)

        sut.setIsAuthenticated(true)
        sut.currentSheet.first {
            it is Sheet.Send && activeContactPaymentContext()?.incomingPaymentRequest == secondRequest
        }

        verify(privatePaykitRepo).beginPaymentRequest(firstRequest)
        verify(privatePaykitRepo).beginPaymentRequest(secondRequest)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
    }

    @Test
    fun `request removed during endpoint resolution is not presented`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        stubOpenedPaymentRequest(request, "lnbcrt1stale")
        whenever(paykitPaymentRequestRepo.isPending(request)).thenReturn(false)
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.onHomeResumed()
        runCurrent()

        verify(privatePaykitRepo).beginPaymentRequest(request)
        assertNull(sut.currentSheet.value)
        assertNull(activeContactPaymentContext())
    }

    @Test
    fun `contact payment opened during request resolution is not overwritten`() = test {
        val request = paymentRequest()
        val manualContext = ContactPaymentContext("pubkymanual")
        whenever(privatePaykitRepo.beginPaymentRequest(request)).thenAnswer {
            setActiveContactPaymentContext(manualContext.publicKey)
            PublicPaykitPaymentResult.Opened(
                paymentRequest = "lnbcrt1incoming",
                privatePaymentContext = PrivatePaykitPaymentContext("bitkit/server", 7uL),
            )
        }
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(30.seconds.inWholeMilliseconds)
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        assertEquals(manualContext, activeContactPaymentContext())
        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `payment request waits for scan started during endpoint resolution`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val requestInvoice = "lnbcrt1resolvedrequest"
        val scanInvoice = "lnbcrt1concurrentscan"
        val resolutionStarted = CompletableDeferred<Unit>()
        val resumeResolution = CompletableDeferred<Unit>()
        val scanStarted = CompletableDeferred<Unit>()
        val resumeScan = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.beginPaymentRequest(request)).doSuspendableAnswer {
            resolutionStarted.complete(Unit)
            resumeResolution.await()
            Result.success(
                PublicPaykitPaymentResult.Opened(
                    paymentRequest = requestInvoice,
                    privatePaymentContext = PrivatePaykitPaymentContext("bitkit/server", 7uL),
                ),
            )
        }
        whenever(coreService.decode(scanInvoice)).doSuspendableAnswer {
            scanStarted.complete(Unit)
            resumeScan.await()
            Scanner.Lightning(lightningInvoice(scanInvoice, 500u))
        }
        stubLightningScan(bolt11 = requestInvoice, amountSats = 0u)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.onHomeResumed()
        resolutionStarted.await()

        sut.onScanResult(scanInvoice)
        scanStarted.await()
        resumeResolution.complete(Unit)
        runCurrent()

        assertNull(sut.currentSheet.value)
        assertNull(activeContactPaymentContext())

        resumeScan.complete(Unit)
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(500u, sut.sendUiState.value.amount)
        assertFalse(sut.sendUiState.value.isPaymentRequest)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)

        sut.hideSheet()
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.isPaymentRequest }

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
        verify(privatePaykitRepo, times(2)).beginPaymentRequest(request)
    }

    @Test
    fun `new scan does not inherit an active payment request context`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val requestInvoice = "lnbcrt1interruptedrequest"
        val replacementInvoice = "lnbcrt1replacementscan"
        val requestScanStarted = CompletableDeferred<Unit>()
        val holdRequestScan = CompletableDeferred<Unit>()
        stubOpenedPaymentRequest(request, requestInvoice)
        whenever(coreService.decode(requestInvoice)).doSuspendableAnswer {
            requestScanStarted.complete(Unit)
            holdRequestScan.await()
            Scanner.Lightning(lightningInvoice(requestInvoice, 0u))
        }
        stubLightningScan(bolt11 = replacementInvoice, amountSats = request.amountSats)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        pendingPaykitPaymentRequests.value = listOf(request)
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.onHomeResumed()
        requestScanStarted.await()
        sut.onScanResult(replacementInvoice)
        sut.currentSheet.first { it is Sheet.Send }
        sut.sendUiState.first { it.addressInput == replacementInvoice }

        assertFalse(sut.sendUiState.value.isPaymentRequest)
        assertNull(activeContactPaymentContext())
        assertEquals(replacementInvoice, sut.sendUiState.value.addressInput)

        runCurrent()
        holdRequestScan.complete(Unit)
        sut.hideSheet()
        runCurrent()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
        verify(privatePaykitRepo, times(2)).beginPaymentRequest(request)
        assertEquals(request, activeContactPaymentContext()?.incomingPaymentRequest)
    }

    @Test
    fun `same active scan restarts when payment request context changes`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val bolt11 = "lnbcrt1sameactivescan"
        val firstScanStarted = CompletableDeferred<Unit>()
        val holdFirstScan = CompletableDeferred<Unit>()
        var decodeCount = 0
        whenever(coreService.decode(bolt11)).doSuspendableAnswer {
            decodeCount += 1
            if (decodeCount == 1) {
                firstScanStarted.complete(Unit)
                holdFirstScan.await()
            }
            Scanner.Lightning(lightningInvoice(bolt11, 0u))
        }
        whenever(lightningRepo.canSend(request.amountSats)).thenReturn(true)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)

        sut.onScanResult(bolt11)
        firstScanStarted.await()
        sut.openContactPayment(
            paymentRequest = bolt11,
            publicKey = testPublicKey,
            incomingPaymentRequest = request,
        )
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(2, decodeCount)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
        assertEquals(request, activeContactPaymentContext()?.incomingPaymentRequest)
    }

    @Test
    fun `same context-free scan does not downgrade an active payment request`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val bolt11 = "lnbcrt1paykitduplicateresult"
        val scanStarted = CompletableDeferred<Unit>()
        val finishScan = CompletableDeferred<Unit>()
        var decodeCount = 0
        whenever(coreService.decode(bolt11)).doSuspendableAnswer {
            decodeCount += 1
            scanStarted.complete(Unit)
            finishScan.await()
            Scanner.Lightning(lightningInvoice(bolt11, 0u))
        }
        whenever(lightningRepo.canSend(request.amountSats)).thenReturn(true)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)

        sut.openContactPayment(
            paymentRequest = bolt11,
            publicKey = testPublicKey,
            incomingPaymentRequest = request,
        )
        scanStarted.await()
        sut.onScanResult(bolt11)
        finishScan.complete(Unit)
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(1, decodeCount)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
        assertEquals(request, activeContactPaymentContext()?.incomingPaymentRequest)
    }

    @Test
    fun `latest scan waits for prior cancellation and skips intermediate replacement`() = test {
        sut.setIsAuthenticated(true)
        val firstInvoice = "lnbcrt1firstcanceledscan"
        val intermediateInvoice = "lnbcrt1intermediatescan"
        val latestInvoice = "lnbcrt1latestscan"
        val firstDecodeStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        var intermediateDecodeCount = 0
        var latestDecodeCount = 0
        whenever(coreService.decode(firstInvoice)).doSuspendableAnswer {
            firstDecodeStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    delay(1.seconds)
                }
            }
        }
        whenever(coreService.decode(intermediateInvoice)).doSuspendableAnswer {
            intermediateDecodeCount += 1
            Scanner.Lightning(lightningInvoice(intermediateInvoice, 500u))
        }
        whenever(coreService.decode(latestInvoice)).doSuspendableAnswer {
            latestDecodeCount += 1
            Scanner.Lightning(lightningInvoice(latestInvoice, 600u))
        }
        whenever(lightningRepo.canSend(any())).thenReturn(true)
        balanceState.value = BalanceState(
            maxSendLightningSats = 100_000u,
            maxSendOnchainSats = 100_000u,
        )

        sut.onScanResult(firstInvoice)
        firstDecodeStarted.await()
        sut.onScanResult(intermediateInvoice)
        cleanupStarted.await()
        sut.onScanResult(latestInvoice)

        assertEquals(0, intermediateDecodeCount)
        assertEquals(0, latestDecodeCount)

        advanceTimeBy(1.seconds.inWholeMilliseconds)
        sut.sendUiState.first { it.addressInput == latestInvoice }

        assertEquals(0, intermediateDecodeCount)
        assertEquals(1, latestDecodeCount)
        assertEquals(latestInvoice, sut.sendUiState.value.addressInput)
        assertEquals(600u, sut.sendUiState.value.amount)
        assertNull(activeContactPaymentContext())
        verify(toastManager, never()).enqueue(any())
    }

    @Test
    fun `payment request retries when a blocking scan finishes without a sheet`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val requestInvoice = "lnbcrt1requestafterfailedscan"
        val scanInput = "invalid-active-scan"
        val resolutionStarted = CompletableDeferred<Unit>()
        val resumeResolution = CompletableDeferred<Unit>()
        val scanStarted = CompletableDeferred<Unit>()
        val resumeScan = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.beginPaymentRequest(request)).doSuspendableAnswer {
            resolutionStarted.complete(Unit)
            resumeResolution.await()
            Result.success(
                PublicPaykitPaymentResult.Opened(
                    paymentRequest = requestInvoice,
                    privatePaymentContext = PrivatePaykitPaymentContext("bitkit/server", 7uL),
                ),
            )
        }
        whenever(coreService.decode(scanInput)).doSuspendableAnswer {
            scanStarted.complete(Unit)
            resumeScan.await()
            throw AppError("Invalid scan")
        }
        stubLightningScan(bolt11 = requestInvoice, amountSats = 0u)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        pendingPaykitPaymentRequests.value = listOf(request)
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.onHomeResumed()
        resolutionStarted.await()
        sut.onScanResult(scanInput)
        scanStarted.await()
        resumeResolution.complete(Unit)
        runCurrent()

        verify(privatePaykitRepo).beginPaymentRequest(request)
        assertNull(sut.currentSheet.value)

        resumeScan.complete(Unit)
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.isPaymentRequest }

        verify(privatePaykitRepo, times(2)).beginPaymentRequest(request)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
    }

    @Test
    fun `payment request waits for an active scan and its send sheet`() = test {
        sut.setIsAuthenticated(true)
        val request = paymentRequest()
        val requestInvoice = "lnbcrt1requestafterscan"
        val scanInvoice = "lnbcrt1activescan"
        val scanStarted = CompletableDeferred<Unit>()
        val resumeScan = CompletableDeferred<Unit>()
        whenever(coreService.decode(scanInvoice)).doSuspendableAnswer {
            scanStarted.complete(Unit)
            resumeScan.await()
            Scanner.Lightning(lightningInvoice(scanInvoice, 500u))
        }
        stubOpenedPaymentRequest(request, requestInvoice)
        stubLightningScan(bolt11 = requestInvoice, amountSats = 0u)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)

        sut.onScanResult(scanInvoice)
        scanStarted.await()
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))
        sut.onHomeResumed()
        runCurrent()

        verify(privatePaykitRepo, never()).beginPaymentRequest(request)

        resumeScan.complete(Unit)
        sut.currentSheet.first { it is Sheet.Send }

        verify(privatePaykitRepo, never()).beginPaymentRequest(request)

        sut.hideSheet()
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.isPaymentRequest }

        verify(privatePaykitRepo).beginPaymentRequest(request)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
    }

    @Test
    fun `payment request waits for a queued locked scan and its send sheet`() = test {
        val request = paymentRequest()
        val requestInvoice = "lnbcrt1requestafterlockedscan"
        val scanInvoice = "lnbcrt1queuedlockedscan"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubOpenedPaymentRequest(request, requestInvoice)
        stubLightningScan(bolt11 = requestInvoice, amountSats = 0u)
        stubLightningScan(bolt11 = scanInvoice, amountSats = 500u)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)

        sut.onScanResult(scanInvoice)
        runCurrent()
        pendingPaykitPaymentRequests.value = listOf(request)
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))
        sut.onHomeResumed()
        runCurrent()

        verify(privatePaykitRepo, never()).beginPaymentRequest(request)

        sut.setIsAuthenticated(true)
        sut.currentSheet.first { it is Sheet.Send }

        assertFalse(sut.sendUiState.value.isPaymentRequest)
        verify(privatePaykitRepo, never()).beginPaymentRequest(request)

        sut.hideSheet()
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.isPaymentRequest }

        verify(privatePaykitRepo).beginPaymentRequest(request)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
    }

    @Test
    fun `unavailable request does not starve a later payable request`() = test {
        val unavailableRequest = paymentRequest()
        val payableRequest = unavailableRequest.copy(paymentRequestId = "payable-request")
        val bolt11 = "lnbcrt1payablerequest"
        whenever(privatePaykitRepo.beginPaymentRequest(unavailableRequest))
            .thenReturn(Result.success(PublicPaykitPaymentResult.NoEndpoint))
        stubOpenedPaymentRequest(payableRequest, bolt11)
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        pendingPaykitPaymentRequests.value = listOf(unavailableRequest, payableRequest)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(30.seconds.inWholeMilliseconds)
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        verify(privatePaykitRepo).beginPaymentRequest(unavailableRequest)
        verify(privatePaykitRepo).beginPaymentRequest(payableRequest)
        assertEquals(payableRequest, activeContactPaymentContext()?.incomingPaymentRequest)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `cancelled request resolution releases the presentation guard`() = test {
        val request = paymentRequest()
        whenever(privatePaykitRepo.beginPaymentRequest(request)).thenThrow(CancellationException())
        pendingPaykitPaymentRequests.value = listOf(request)
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(30.seconds.inWholeMilliseconds)
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        assertFalse(isPresentingPaymentRequest())
    }

    @Test
    fun `hardware received tx details navigate directly to hardware activity`() = test {
        val txId = "hardware-tx"

        sut.mainScreenEffect.test {
            advanceUntilIdle()
            hwReceivedTxs.emit(HwWalletReceivedTx(txid = txId, sats = 21uL, walletId = "hardware-wallet"))
            advanceUntilIdle()

            assertEquals(txId, sut.transactionSheet.value.activityId)
            sut.onClickActivityDetail()

            assertEquals(
                MainScreenEffect.Navigate(Routes.ActivityDetail(txId, "hardware-wallet")),
                awaitItem(),
            )
        }
        verify(activityRepo, never()).findActivityByPaymentId(any(), any(), any(), any())
    }

    @Test
    fun `pairing code request shows and hides the pair device sheet`() = test {
        needsPairingCode.value = true
        pairingCodeRequestId.value = 1L
        advanceUntilIdle()

        assertEquals(Sheet.Hardware(route = HardwareRoute.PairCode(1L)), sut.currentSheet.value)

        needsPairingCode.value = false
        pairingCodeRequestId.value = null
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `new app-wide pairing request replaces the stale pair code route`() = test {
        needsPairingCode.value = true
        pairingCodeRequestId.value = 1L
        advanceUntilIdle()

        pairingCodeRequestId.value = 2L
        advanceUntilIdle()

        assertEquals(Sheet.Hardware(route = HardwareRoute.PairCode(2L)), sut.currentSheet.value)
    }

    @Test
    fun `pairing code request does not interrupt a high priority sheet`() = test {
        sut.showSheet(Sheet.Pin())
        advanceUntilIdle()

        needsPairingCode.value = true
        pairingCodeRequestId.value = 1L
        advanceUntilIdle()

        assertEquals(Sheet.Pin(), sut.currentSheet.value)
    }

    @Test
    fun `pairing code request shows after high priority sheet closes`() = test {
        sut.showSheet(Sheet.Pin())
        advanceUntilIdle()

        needsPairingCode.value = true
        pairingCodeRequestId.value = 1L
        advanceUntilIdle()

        sut.hideSheet()
        advanceUntilIdle()

        assertEquals(Sheet.Hardware(route = HardwareRoute.PairCode(1L)), sut.currentSheet.value)
    }

    @Test
    fun `submitPairingCode forwards to the hardware wallet repo`() = test {
        sut.submitPairingCode("123456")

        verify(hwWalletRepo).submitPairingCode("123456")
    }

    @Test
    fun `cancelPairingCode forwards to the hardware wallet repo`() = test {
        sut.cancelPairingCode()

        verify(hwWalletRepo).cancelPairingCode()
    }

    @Test
    fun `canSwitchWallet is false when not unified`() = test {
        sut.setSendEvent(SendEvent.AmountChange(1000u))
        advanceUntilIdle()

        assertFalse(sut.sendUiState.value.canSwitchWallet)
    }

    @Test
    fun `canSwitchWallet is false when amount is zero`() = test {
        setUnifiedState(amount = 0u)
        advanceUntilIdle()

        assertFalse(sut.sendUiState.value.canSwitchWallet)
    }

    @Test
    fun `scan SamRock setup opens BTCPay connection sheet without core decode`() = test {
        sut.onScanResult(
            "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
        )
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertEquals("secret", sheet.setup.otp)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `scan lightning-only SamRock setup shows unsupported toast without sheet`() = test {
        sut.onScanResult(
            "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-ln&otp=secret"
        )
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(toastManager).enqueue(
            check {
                assertEquals("BTCPayUnsupportedToast", it.testTag)
            }
        )
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `Bitkit SamRock deeplink opens BTCPay connection sheet without core decode`() = test {
        sut.handleDeeplinkIntent(samRockIntent(samRockDeepLink(SAMROCK_SETUP_URL)))
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertEquals("secret", sheet.setup.otp)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `Bitkit SamRock deeplink containing recovery mode text opens BTCPay sheet`() = test {
        sut.handleDeeplinkIntent(
            samRockIntent(
                samRockDeepLink(
                    "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=recovery-mode"
                )
            )
        )
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertEquals("recovery-mode", sheet.setup.otp)
        verify(lightningRepo, never()).setRecoveryMode(true)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `screen namespace recovery-mode never enables recovery mode`() = test {
        settingsData.value = SettingsData(isDevModeEnabled = true)

        sut.handleDeeplinkIntent(screenIntent("recovery-mode"))
        advanceUntilIdle()

        verify(lightningRepo, never()).setRecoveryMode(true)
    }

    @Test
    fun `legacy recovery-mode deeplink still enables recovery mode`() = test {
        sut.handleDeeplinkIntent(legacyRecoveryModeIntent())
        advanceUntilIdle()

        verify(lightningRepo).setRecoveryMode(true)
    }

    @Test
    fun `screen deeplink is dropped when dev mode is off`() = test {
        settingsData.value = SettingsData(isDevModeEnabled = false)

        sut.handleDeeplinkIntent(screenIntent("settings"))
        advanceUntilIdle()

        assertNull(sut.pendingScreenDeepLink.value)
    }

    @Test
    fun `screen deeplink is held for replay only on debug when dev mode is on`() = test {
        settingsData.value = SettingsData(isDevModeEnabled = true)

        sut.handleDeeplinkIntent(screenIntent("settings"))
        advanceUntilIdle()

        if (ScreenDeepLinks.isEnabled) {
            assertNotNull(sut.pendingScreenDeepLink.value)
        } else {
            assertNull(sut.pendingScreenDeepLink.value)
        }
    }

    @Test
    fun `public http Bitkit SamRock deeplink shows setup error without core decode`() = test {
        sut.handleDeeplinkIntent(
            samRockIntent(
                samRockDeepLink(
                    "http://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
                )
            )
        )
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(toastManager).enqueue(
            check {
                assertEquals("BTCPayInvalidSetupToast", it.testTag)
            }
        )
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `unsupported Bitkit SamRock deeplink shows unsupported toast without sheet`() = test {
        sut.handleDeeplinkIntent(
            samRockIntent(
                samRockDeepLink(
                    "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btcln&otp=secret"
                )
            )
        )
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(toastManager).enqueue(
            check {
                assertEquals("BTCPayUnsupportedToast", it.testTag)
            }
        )
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `Bitkit SamRock deeplink is ignored when wallet does not exist`() = test {
        whenever(walletRepo.walletExists()).thenReturn(false)

        sut.handleDeeplinkIntent(samRockIntent(samRockDeepLink(SAMROCK_SETUP_URL)))
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(coreService, never()).decode(any())
        verify(toastManager, never()).enqueue(any())
    }

    @Test
    fun `recovery mode deeplink enables recovery mode`() = test {
        sut.handleDeeplinkIntent(recoveryModeIntent())
        advanceUntilIdle()

        verify(lightningRepo).setRecoveryMode(true)
    }

    @Test
    fun `deeplink from NFC tag tap is processed`() = test {
        sut.handleDeeplinkIntent(recoveryModeIntent(action = NfcAdapter.ACTION_NDEF_DISCOVERED))
        advanceUntilIdle()

        verify(lightningRepo).setRecoveryMode(true)
    }

    @Test
    fun `intent without deeplink action is ignored`() = test {
        sut.handleDeeplinkIntent(recoveryModeIntent(action = Intent.ACTION_MAIN))
        advanceUntilIdle()

        verify(lightningRepo, never()).setRecoveryMode(any())
    }

    @Test
    fun `connectBTCPay hides sheet and shows success toast`() = test {
        val setup = samRockSetupRequest()
        whenever(samRockRepo.registerBitcoinOnchain(setup)).thenReturn(Result.success(Unit))
        sut.showSheet(Sheet.BTCPayConnection(setup))
        advanceUntilIdle()

        sut.connectBTCPay(setup)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(toastManager).enqueue(
            check {
                assertEquals("BTCPayConnectedToast", it.testTag)
            }
        )
    }

    @Test
    fun `connectBTCPay failure keeps sheet and shows error toast`() = test {
        val setup = samRockSetupRequest()
        whenever(samRockRepo.registerBitcoinOnchain(setup)).thenReturn(Result.failure(AppError("failed")))
        sut.showSheet(Sheet.BTCPayConnection(setup))
        advanceUntilIdle()

        sut.connectBTCPay(setup)
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertFalse(sheet.isConnecting)
        assertEquals("failed", sheet.errorText)
        verify(toastManager).enqueue(
            check {
                assertEquals("BTCPayConnectionErrorToast", it.testTag)
            }
        )
    }

    @Test
    fun `canDecodeClipboard accepts SamRock setup without core decode`() = test {
        val setupUrl = "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
        clearInvocations(coreService)

        assertTrue(sut.canDecodeClipboard(setupUrl))
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `canDecodeClipboard accepts invalid SamRock setup without core decode`() = test {
        val setupUrl = "http://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
        clearInvocations(coreService)

        assertTrue(sut.canDecodeClipboard(setupUrl))
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `paste SamRock setup opens BTCPay connection sheet without core decode`() = test {
        val clipData = mock<ClipData>()
        val item = mock<ClipData.Item>()
        whenever(item.text).thenReturn(SAMROCK_SETUP_URL)
        whenever(clipData.getItemAt(0)).thenReturn(item)
        whenever(clipboardManager.primaryClip).thenReturn(clipData)
        clearInvocations(coreService)

        sut.setSendEvent(SendEvent.Paste)
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertEquals("secret", sheet.setup.otp)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `paste empty clipboard shows warning without core decode`() = test {
        whenever(clipboardManager.primaryClip).thenReturn(null)
        clearInvocations(coreService)

        sut.setSendEvent(SendEvent.Paste)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(toastManager).enqueue(any())
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `scanner sheet SamRock result opens BTCPay connection sheet without core decode`() = test {
        clearInvocations(coreService)

        sut.showScannerSheet()
        advanceUntilIdle()
        assertTrue(sut.currentSheet.value is Sheet.QrScanner)

        sut.onScannerSheetResult(SAMROCK_SETUP_URL)
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertEquals("secret", sheet.setup.otp)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `scanner sheet handler is bypassed for SamRock result`() = test {
        var handled = false
        clearInvocations(coreService)

        sut.showScannerSheet {
            handled = true
        }
        advanceUntilIdle()
        assertTrue(sut.currentSheet.value is Sheet.QrScanner)

        sut.onScannerSheetResult(SAMROCK_SETUP_URL)
        advanceUntilIdle()

        val sheet = sut.currentSheet.value
        assertTrue(sheet is Sheet.BTCPayConnection)
        assertFalse(handled)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `canSwitchWallet is false when amount equals dust limit`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 100_000u,
            maxSendLightningSats = 100_000u,
        )
        setUnifiedState(amount = 546u)
        advanceUntilIdle()

        assertFalse(sut.sendUiState.value.canSwitchWallet)
    }

    @Test
    fun `canSwitchWallet is true when amount above dust limit and within both balances`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 100_000u,
            maxSendLightningSats = 100_000u,
        )
        setUnifiedState(amount = 1000u)
        advanceUntilIdle()

        assertTrue(sut.sendUiState.value.canSwitchWallet)
    }

    @Test
    fun `canSwitchWallet is false when amount exceeds onchain balance`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 500u,
            maxSendLightningSats = 100_000u,
        )
        setUnifiedState(amount = 1000u)
        advanceUntilIdle()

        assertFalse(sut.sendUiState.value.canSwitchWallet)
    }

    @Test
    fun `manual address continue routes pubky to add contact`() = test {
        enablePaykitUi()
        advanceUntilIdle()

        sut.mainScreenEffect.test {
            sut.setSendEvent(SendEvent.AddressContinue(testPublicKey))

            assertEquals(MainScreenEffect.Navigate(Routes.AddContact(testPublicKey)), awaitItem())
        }
    }

    @Test
    fun `existing contact scan refreshes Paykit receivers`() = test {
        enablePaykitUi()
        pubkyContacts.value = listOf(
            PubkyProfile(
                publicKey = testPublicKey,
                name = "Bob",
                bio = "",
                imageUrl = null,
                links = emptyList(),
                status = null,
            ),
        )
        advanceUntilIdle()

        sut.mainScreenEffect.test {
            sut.onScanResult(testPublicKey, routePubkyKeys = true)

            assertEquals(MainScreenEffect.Navigate(Routes.ContactDetail(testPublicKey)), awaitItem())
            advanceUntilIdle()
        }

        verify(refreshContactPaykitReceivers).invoke(testPublicKey)
    }

    @Test
    fun `manual address input accepts pubky without decode error`() = test {
        enablePaykitUi()
        advanceUntilIdle()

        sut.setSendEvent(SendEvent.AddressChange(testPublicKey))
        advanceUntilIdle()

        assertEquals(testPublicKey, sut.sendUiState.value.addressInput)
        assertTrue(sut.sendUiState.value.isAddressInputValid)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `manual address input rejects pubky when Paykit UI is disabled`() = test {
        sut.setSendEvent(SendEvent.AddressChange(testPublicKey))
        advanceUntilIdle()

        assertEquals(testPublicKey, sut.sendUiState.value.addressInput)
        assertFalse(sut.sendUiState.value.isAddressInputValid)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `contact button routes to coming soon when Paykit UI is disabled`() = test {
        sut.sendEffect.test {
            sut.setSendEvent(SendEvent.Contacts)

            assertEquals(SendEffect.NavigateToComingSoon, awaitItem())
        }
    }

    @Test
    fun `contact button routes to contact select when Paykit UI is enabled`() = test {
        enablePaykitUi()
        advanceUntilIdle()

        sut.sendEffect.test {
            sut.setSendEvent(SendEvent.Contacts)

            assertEquals(SendEffect.NavigateToContacts, awaitItem())
        }
    }

    @Test
    fun `pubky auth deeplink is ignored when Paykit UI is disabled`() = test {
        val intent = Intent(Intent.ACTION_VIEW, "pubkyauth://auth?caps=/pub/paykit/v0/:rw".toUri())

        sut.handleDeeplinkIntent(intent)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(pubkyRepo, never()).hasSecretKey()
    }

    @Test
    fun `pubky ring callback deeplink is ignored when Paykit UI is disabled`() = test {
        val intent = Intent(Intent.ACTION_VIEW, "bitkit://pubky-auth/success".toUri())

        sut.handleDeeplinkIntent(intent)
        advanceUntilIdle()

        verify(pubkyRepo, never()).handleAuthCallback(any())
    }

    @Test
    fun `pubky auth deeplink shows approval sheet when Paykit UI is enabled`() = test {
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"

        sut.handleDeeplinkIntent(Intent(Intent.ACTION_VIEW, authUrl.toUri()))
        advanceUntilIdle()

        assertEquals(Sheet.PubkyAuth(authUrl), sut.currentSheet.value)
    }

    @Test
    fun `pubky auth deeplink shows identity required toast without a Pubky identity`() = test {
        enablePaykitUi()
        whenever(context.getString(R.string.pubky_auth__no_identity)).thenReturn("Pubky Identity Required")
        whenever(context.getString(R.string.pubky_auth__no_identity_desc)).thenReturn("Create a Pubky identity")
        advanceUntilIdle()

        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"
        sut.handleDeeplinkIntent(Intent(Intent.ACTION_VIEW, authUrl.toUri()))
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(pubkyRepo, never()).hasSecretKey()
        verify(toastManager).enqueue(
            check {
                assertEquals("Pubky Identity Required", it.title)
                assertEquals("Create a Pubky identity", it.description)
            }
        )
    }

    @Test
    fun `pubky auth deeplink keeps Ring-only guidance for an imported identity`() = test {
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(pubkyRepo.hasSecretKey()).thenReturn(false)
        whenever(context.getString(R.string.profile__auth_approval_ring_only)).thenReturn("Use Ring")
        advanceUntilIdle()

        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"
        sut.handleDeeplinkIntent(Intent(Intent.ACTION_VIEW, authUrl.toUri()))
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(toastManager).enqueue(
            check {
                assertEquals("Use Ring", it.title)
                assertNull(it.description)
            }
        )
    }

    @Test
    fun `global scanner sheet accepts pubky auth when Paykit UI is enabled`() = test {
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"

        sut.showScannerSheet()
        advanceUntilIdle()
        assertEquals(Sheet.QrScanner(), sut.currentSheet.value)

        sut.onScannerSheetResult(authUrl)
        advanceUntilIdle()

        assertEquals(Sheet.PubkyAuth(authUrl), sut.currentSheet.value)
    }

    @Test
    fun `send paste rejects pubky auth`() = test {
        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"
        val clipData = mock<ClipData>()
        val item = mock<ClipData.Item>()
        whenever(item.text).thenReturn(authUrl)
        whenever(clipData.getItemAt(0)).thenReturn(item)
        whenever(clipboardManager.primaryClip).thenReturn(clipData)
        sut.showSheet(Sheet.Send())
        advanceUntilIdle()

        sut.setSendEvent(SendEvent.Paste)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(pubkyRepo, never()).hasSecretKey()
        verify(coreService, never()).decode(any())
        verify(toastManager).enqueue(any())
    }

    @Test
    fun `send scanner rejects pubky auth`() = test {
        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"
        sut.showSheet(Sheet.Send())
        advanceUntilIdle()

        sut.onScanResult(authUrl)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(pubkyRepo, never()).hasSecretKey()
        verify(coreService, never()).decode(any())
        verify(toastManager).enqueue(any())
    }

    @Test
    fun `manual address input rejects pubky auth without decoding`() = test {
        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"

        sut.setSendEvent(SendEvent.AddressChange(authUrl))
        advanceUntilIdle()

        assertEquals(authUrl, sut.sendUiState.value.addressInput)
        assertFalse(sut.sendUiState.value.isAddressInputValid)
        verify(coreService, never()).decode(any())
    }

    @Test
    fun `pubky routing dismisses send sheet before navigation`() = test {
        enablePaykitUi()
        advanceUntilIdle()

        sut.showSheet(Sheet.Send())
        advanceUntilIdle()

        sut.mainScreenEffect.test {
            sut.setSendEvent(SendEvent.AddressContinue(testPublicKey))

            assertEquals(MainScreenEffect.Navigate(Routes.AddContact(testPublicKey)), awaitItem())
            assertNull(sut.currentSheet.value)
        }
    }

    @Test
    fun `canSwitchWallet is false when amount exceeds lightning balance`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 100_000u,
            maxSendLightningSats = 500u,
        )
        setUnifiedState(amount = 1000u)
        advanceUntilIdle()

        assertFalse(sut.sendUiState.value.canSwitchWallet)
    }

    @Test
    fun `switch from lightning to onchain resets confirmedWarnings`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 100_000u,
            maxSendLightningSats = 100_000u,
        )
        setUnifiedState(amount = 1000u, payMethod = SendMethod.LIGHTNING)
        sut.setSendEvent(SendEvent.ConfirmAmountWarning(SanityWarning.VALUE_OVER_100_USD))
        advanceUntilIdle()

        assertTrue(sut.sendUiState.value.confirmedWarnings.isNotEmpty())

        sut.setSendEvent(SendEvent.PaymentMethodSwitch)
        advanceUntilIdle()

        assertTrue(sut.sendUiState.value.confirmedWarnings.isEmpty())
    }

    @Test
    fun `switch from onchain to lightning sets fee to Lightning zero`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 100_000u,
            maxSendLightningSats = 100_000u,
        )
        setUnifiedState(amount = 1000u, payMethod = SendMethod.ONCHAIN)
        advanceUntilIdle()

        sut.setSendEvent(SendEvent.PaymentMethodSwitch)
        advanceUntilIdle()

        assertEquals(SendMethod.LIGHTNING, sut.sendUiState.value.payMethod)
        assertEquals(SendFee.Lightning(0), sut.sendUiState.value.fee)
    }

    @Test
    fun `switch does nothing when not unified`() = test {
        sut.setSendEvent(SendEvent.AmountChange(1000u))
        advanceUntilIdle()

        val before = sut.sendUiState.value.payMethod
        sut.setSendEvent(SendEvent.PaymentMethodSwitch)
        advanceUntilIdle()

        assertEquals(before, sut.sendUiState.value.payMethod)
    }

    @Test
    fun `pending contact lightning success tags activity`() = test {
        val contactKey = "pubkycontact"
        val paymentHash = "pending_hash"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(true)
        whenever(pendingPaymentRepo.isActive(paymentHash)).thenReturn(false)
        whenever(activityRepo.setContact(contactKey, paymentHash)).thenReturn(Result.success(Unit))
        advanceUntilIdle()

        setPendingContactPaymentContext(paymentHash, contactKey)
        emitNodeEvent(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10uL,
            ),
        )
        advanceUntilIdle()

        verify(pendingPaymentRepo).resolve(PendingPaymentResolution.Success(paymentHash))
        verify(activityRepo).setContact(contactPublicKey = contactKey, forPaymentId = paymentHash)
        verify(quickPayRepo).signalCompletion(
            paymentId = "payment_id",
            paymentHash = paymentHash,
            success = true,
            feePaidMsat = 10uL,
            failureReason = null,
        )
    }

    @Test
    fun `pending contact lightning failure clears context`() = test {
        val paymentHash = "pending_hash"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(true)
        whenever(pendingPaymentRepo.isActive(paymentHash)).thenReturn(false)
        advanceUntilIdle()

        setPendingContactPaymentContext(paymentHash, "pubkycontact")
        emitNodeEvent(
            Event.PaymentFailed(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                reason = PaymentFailureReason.RETRIES_EXHAUSTED,
            ),
        )
        advanceUntilIdle()

        verify(pendingPaymentRepo).resolve(
            PendingPaymentResolution.Failure(
                paymentHash = paymentHash,
                reason = PaymentFailureReason.RETRIES_EXHAUSTED,
            )
        )
        verify(quickPayRepo).signalCompletion(
            paymentId = "payment_id",
            paymentHash = paymentHash,
            success = false,
            feePaidMsat = null,
            failureReason = PaymentFailureReason.RETRIES_EXHAUSTED,
        )
        assertNull(pendingContactPaymentContext(paymentHash))
    }

    @Test
    fun `PaymentFailed with null hash still resolves pending`() = test {
        val paymentHash = "pending_hash"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(true)
        whenever(pendingPaymentRepo.isActive(paymentHash)).thenReturn(false)
        advanceUntilIdle()

        emitNodeEvent(
            Event.PaymentFailed(
                paymentId = paymentHash,
                paymentHash = null,
                reason = PaymentFailureReason.RETRIES_EXHAUSTED,
            ),
        )
        advanceUntilIdle()

        verify(pendingPaymentRepo).resolve(
            PendingPaymentResolution.Failure(
                paymentHash = paymentHash,
                reason = PaymentFailureReason.RETRIES_EXHAUSTED,
            ),
        )
        verify(quickPayRepo).signalCompletion(
            paymentId = paymentHash,
            paymentHash = null,
            success = false,
            feePaidMsat = null,
            failureReason = PaymentFailureReason.RETRIES_EXHAUSTED,
        )
    }

    @Test
    fun `PaymentFailed releases disk reservation when not pending`() = test {
        val paymentHash = "restart_hash"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(false)

        emitNodeEvent(
            Event.PaymentFailed(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                reason = PaymentFailureReason.RETRIES_EXHAUSTED,
            ),
        )
        advanceUntilIdle()

        verify(quickPayRepo).signalCompletion(
            paymentId = "payment_id",
            paymentHash = paymentHash,
            success = false,
            feePaidMsat = null,
            failureReason = PaymentFailureReason.RETRIES_EXHAUSTED,
        )
        verify(pendingPaymentRepo, never()).resolve(any())
    }

    @Test
    fun `PaymentSuccessful clears disk reservation when not pending`() = test {
        val paymentHash = "restart_ok"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(false)
        whenever {
            quickPayRepo.signalCompletion(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        }.thenReturn(
            QuickPayCompletionOutcome(
                kind = QuickPayCompletionKind.SETTLED_SUCCESS,
                invoicePaymentHash = paymentHash,
            ),
        )

        emitNodeEvent(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10uL,
            ),
        )
        advanceUntilIdle()

        verify(quickPayRepo).signalCompletion(
            paymentId = "payment_id",
            paymentHash = paymentHash,
            success = true,
            feePaidMsat = 10uL,
            failureReason = null,
        )
        verify(pendingPaymentRepo, never()).resolve(any())
    }

    @Test
    fun `pending confirm lightning success keeps invoice amount`() = test {
        val paymentHash = "pending_confirm_hash"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(true)
        whenever(pendingPaymentRepo.isActive(paymentHash)).thenReturn(false)
        whenever {
            quickPayRepo.signalCompletion(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        }.thenReturn(QuickPayCompletionOutcome.None)
        advanceUntilIdle()

        emitNodeEvent(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10uL,
            ),
        )
        advanceUntilIdle()

        verify(pendingPaymentRepo).resolve(PendingPaymentResolution.Success(paymentHash))
        verify(activityRepo, never()).findActivityByPaymentId(any(), any(), any(), any())
    }

    @Test
    fun `pending quickpay lightning success includes settled amount`() = test {
        val paymentHash = "pending_quickpay_hash"
        val activityV1 = mock<LightningActivity> {
            on { value } doReturn 500u
            on { fee } doReturn 0u
        }
        val activity = mock<BitkitActivity.Lightning> { on { v1 } doReturn activityV1 }
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(true)
        whenever(pendingPaymentRepo.isActive(paymentHash)).thenReturn(false)
        whenever {
            quickPayRepo.signalCompletion(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        }.thenReturn(
            QuickPayCompletionOutcome(
                kind = QuickPayCompletionKind.SETTLED_SUCCESS,
                invoicePaymentHash = paymentHash,
            ),
        )
        whenever { activityRepo.findActivityByPaymentId(any(), any(), any(), any()) }
            .thenReturn(Result.success(activity))
        advanceUntilIdle()

        emitNodeEvent(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10_000uL,
            ),
        )
        advanceUntilIdle()

        verify(pendingPaymentRepo).resolve(
            PendingPaymentResolution.Success(
                paymentHash = paymentHash,
                amountWithFeeSats = 510L,
            ),
        )
        verify(quickPayRepo).signalCompletion(
            paymentId = "payment_id",
            paymentHash = paymentHash,
            success = true,
            feePaidMsat = 10_000uL,
            failureReason = null,
        )
    }

    @Test
    fun `active lightning send failure navigates to failure screen`() = test {
        val bolt11 = "lnbcrt1activefailure"
        val paymentHash = "010203"
        val errorMessage = "Bitkit could not find a route"
        whenever(context.getString(R.string.wallet__payment_route_not_found)).thenReturn(errorMessage)
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(false)
        setSendState(
            SendUiState(
                address = bolt11,
                amount = 1000u,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 1000u),
            ),
        )
        sut.showSheet(Sheet.Send())
        advanceUntilIdle()

        sut.sendEffect.test {
            emitNodeEvent(
                Event.PaymentFailed(
                    paymentId = "payment_id",
                    paymentHash = paymentHash,
                    reason = PaymentFailureReason.ROUTE_NOT_FOUND,
                ),
            )
            advanceUntilIdle()

            assertEquals(
                SendEffect.NavigateToError(
                    SendFailureDetails(
                        message = errorMessage,
                        failureType = "routeNotFound",
                        resetRoutingCachesOnRetry = true,
                        paymentRequest = bolt11,
                    )
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `in-flight QuickPay failure does not navigate to confirm error`() = test {
        val bolt11 = "lnbcrt1quickpayfail"
        enableQuickPay()
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)
        sut.onScanResult(bolt11)
        advanceUntilIdle()

        sut.sendEffect.test {
            emitNodeEvent(
                Event.PaymentFailed(
                    paymentId = "payment_id",
                    paymentHash = "010203",
                    reason = PaymentFailureReason.ROUTE_NOT_FOUND,
                ),
            )
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun `confirm failure still navigates after QuickPay fallback`() = test {
        val bolt11 = "lnbcrt1quickpayfallback"
        val errorMessage = "Bitkit could not find a route"
        whenever(context.getString(R.string.wallet__payment_route_not_found)).thenReturn(errorMessage)
        enableQuickPay()
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)
        sut.onScanResult(bolt11)
        advanceUntilIdle()
        sut.resetQuickPay()

        sut.sendEffect.test {
            emitNodeEvent(
                Event.PaymentFailed(
                    paymentId = "payment_id",
                    paymentHash = "010203",
                    reason = PaymentFailureReason.ROUTE_NOT_FOUND,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                SendEffect.NavigateToError(
                    SendFailureDetails(
                        message = errorMessage,
                        failureType = "routeNotFound",
                        resetRoutingCachesOnRetry = true,
                        paymentRequest = bolt11,
                    )
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `received lightning payment closes the active receive sheet after wallet invoice is cleared`() = test {
        walletState.value = WalletState(bolt11 = "settled-invoice")
        sut.showSheet(Sheet.Receive())
        advanceUntilIdle()
        walletState.value = WalletState(bolt11 = "")
        advanceUntilIdle()

        emitNodeEvent(
            Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice("settled-invoice"),
        )
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        verify(activityRepo).notifyPaymentActivityChanged()
        verify(activityRepo, never()).handlePaymentEvent("payment-hash")
        verify(notifyPaymentReceivedHandler).invoke(any())
    }

    @Test
    fun `received unrelated lightning payment preserves the active receive sheet`() = test {
        val sheet = Sheet.Receive()
        sut.showSheet(sheet)
        advanceUntilIdle()

        emitNodeEvent(
            Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "unrelated-payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
        )
        advanceUntilIdle()

        assertEquals(sheet, sut.currentSheet.value)
        verify(activityRepo).notifyPaymentActivityChanged()
        verify(activityRepo, never()).handlePaymentEvent("unrelated-payment-hash")
    }

    @Test
    fun `received onchain payment closes the receive sheet for the settled address`() = test {
        val address = "bcrt1qsettled"
        walletState.value = WalletState(onchainAddress = address)
        sut.showSheet(Sheet.Receive())
        advanceUntilIdle()

        emitNodeEvent(
            event = Event.OnchainTransactionReceived(
                txid = "txid",
                details = TransactionDetails(amountSats = 1_000L, inputs = emptyList(), outputs = emptyList()),
            ),
            settledReceiveAddress = SettledReceiveAddress(address),
        )
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `received onchain payment preserves a receive sheet for another address`() = test {
        val sheet = Sheet.Receive()
        walletState.value = WalletState(onchainAddress = "bcrt1qcurrent")
        sut.showSheet(sheet)
        advanceUntilIdle()

        emitNodeEvent(
            event = Event.OnchainTransactionReceived(
                txid = "txid",
                details = TransactionDetails(amountSats = 1_000L, inputs = emptyList(), outputs = emptyList()),
            ),
            settledReceiveAddress = SettledReceiveAddress("bcrt1qold"),
        )
        advanceUntilIdle()

        assertTrue(sut.currentSheet.value === sheet)
    }

    @Test
    fun `received onchain payment preserves a replacement receive sheet`() = test {
        val processingStarted = CompletableDeferred<Unit>()
        val resumeProcessing = CompletableDeferred<Unit>()
        whenever(privatePaykitRepo.contactPublicKeyForPrivateOnchainAddresses(any<Collection<String>>()))
            .doSuspendableAnswer {
                processingStarted.complete(Unit)
                resumeProcessing.await()
                null
            }
        val settledAddress = "bcrt1qsettled"
        walletState.value = WalletState(onchainAddress = settledAddress)
        sut.showSheet(Sheet.Receive())
        advanceUntilIdle()

        emitNodeEvent(
            event = Event.OnchainTransactionReceived(
                txid = "txid",
                details = TransactionDetails(amountSats = 1_000L, inputs = emptyList(), outputs = emptyList()),
            ),
            settledReceiveAddress = SettledReceiveAddress(settledAddress),
        )
        processingStarted.await()
        assertNull(sut.currentSheet.value)

        walletState.value = WalletState(onchainAddress = "bcrt1qreplacement")
        val replacementSheet = Sheet.Receive()
        sut.showSheet(replacementSheet)
        advanceUntilIdle()
        resumeProcessing.complete(Unit)
        advanceUntilIdle()

        assertTrue(sut.currentSheet.value === replacementSheet)
    }

    @Test
    fun `received lightning payment preserves a non-receive sheet`() = test {
        val sheet = Sheet.Pin()
        walletState.value = WalletState(bolt11 = "settled-invoice")
        sut.showSheet(sheet)
        advanceUntilIdle()

        emitNodeEvent(
            Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice("settled-invoice"),
        )
        advanceUntilIdle()

        assertEquals(sheet, sut.currentSheet.value)
    }

    @Test
    fun `received lightning payment is claimed by the UI while foregrounded`() = test {
        val details = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            paymentHashOrTxId = "payment-hash",
            sats = 1L,
        )
        whenever(notifyPaymentReceivedHandler(any()))
            .thenReturn(Result.success(NotifyPaymentReceived.Result.ShowSheet(details)))
        whenever(notifyPaymentReceivedHandler.present(any(), any(), any())).thenAnswer {
            it.getArgument<() -> Unit>(2).invoke()
            true
        }
        App.currentActivity = CurrentActivity().also { it.onActivityStarted(mock<Activity>()) }

        emitNodeEvent(
            Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
        )
        advanceUntilIdle()

        verify(notifyPaymentReceivedHandler).present(any(), any(), any())
        assertEquals(details, sut.transactionSheet.value)
    }

    @Test
    fun `received lightning payment uses the UI handoff after the app backgrounds`() = test {
        val details = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            paymentHashOrTxId = "payment-hash",
            sats = 1L,
        )
        whenever(notifyPaymentReceivedHandler(any()))
            .thenReturn(Result.success(NotifyPaymentReceived.Result.ShowSheet(details)))
        whenever(notifyPaymentReceivedHandler.present(any(), any(), any())).thenAnswer {
            it.getArgument<() -> Unit>(2).invoke()
            true
        }
        App.currentActivity = CurrentActivity()

        emitNodeEvent(
            Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
        )
        advanceUntilIdle()

        verify(notifyPaymentReceivedHandler).present(any(), any(), any())
        assertEquals(details, sut.transactionSheet.value)
    }

    @Test
    fun `received lightning payment skips the sheet when the service owns the presentation`() = test {
        val details = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            paymentHashOrTxId = "payment-hash",
            sats = 1L,
        )
        whenever(notifyPaymentReceivedHandler(any()))
            .thenReturn(Result.success(NotifyPaymentReceived.Result.ShowSheet(details)))
        whenever(notifyPaymentReceivedHandler.present(any(), any(), any())).thenReturn(false)
        App.currentActivity = CurrentActivity()

        emitNodeEvent(
            Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
        )
        advanceUntilIdle()

        verify(notifyPaymentReceivedHandler).present(any(), any(), any())
        assertEquals(NewTransactionSheetDetails.EMPTY, sut.transactionSheet.value)
    }

    @Test
    fun `received lightning payment preserves a receive sheet opened during activity sync`() = test {
        val processingStarted = CompletableDeferred<Unit>()
        val resumeProcessing = CompletableDeferred<Unit>()
        whenever(activityRepo.notifyPaymentActivityChanged()).doSuspendableAnswer {
            processingStarted.complete(Unit)
            resumeProcessing.await()
        }
        walletState.value = WalletState(bolt11 = "settled-invoice")
        sut.showSheet(Sheet.Receive())
        advanceUntilIdle()

        emitNodeEvent(
            event = Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice("settled-invoice"),
        )
        processingStarted.await()

        walletState.value = WalletState(bolt11 = "replacement-invoice")
        val replacementSheet = Sheet.Receive()
        sut.showSheet(replacementSheet)
        advanceUntilIdle()
        resumeProcessing.complete(Unit)
        advanceUntilIdle()

        assertTrue(sut.currentSheet.value === replacementSheet)
    }

    @Test
    fun `received lightning payment dismisses the settled receive sheet before activity sync`() = test {
        val processingStarted = CompletableDeferred<Unit>()
        val resumeProcessing = CompletableDeferred<Unit>()
        whenever(activityRepo.notifyPaymentActivityChanged()).doSuspendableAnswer {
            processingStarted.complete(Unit)
            resumeProcessing.await()
        }
        walletState.value = WalletState(bolt11 = "settled-invoice")
        val receiveSheet = Sheet.Receive()
        sut.showSheet(receiveSheet)
        advanceUntilIdle()

        emitNodeEvent(
            event = Event.PaymentReceived(
                paymentId = "payment-id",
                paymentHash = "payment-hash",
                amountMsat = 1_000uL,
                customRecords = emptyList(),
            ),
            settledReceiveInvoice = SettledReceiveInvoice("settled-invoice"),
        )
        processingStarted.await()
        assertNull(sut.currentSheet.value)

        walletState.value = WalletState(bolt11 = "replacement-invoice")
        advanceUntilIdle()
        resumeProcessing.complete(Unit)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `preserveContactPaymentContext moves active context to pending`() = test {
        val paymentHash = "pending_hash"
        val contactKey = "pubkycontact"
        setActiveContactPaymentContext(contactKey)

        sut.preserveContactPaymentContext(paymentHash)

        assertNull(activeContactPaymentContext())
        assertEquals(contactKey, pendingContactPaymentContext(paymentHash)?.publicKey)
    }

    @Test
    fun `main scanner lightning scan opens send sheet`() = test {
        val bolt11 = "lnbcrt1scanner"
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.showScannerSheet()
        sut.onScannerSheetResult(bolt11)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `scanner result does not discard a queued locked scan`() = test {
        val queuedInvoice = "lnbcrt1queuedbeforescanner"
        val scannerInvoice = "lnbcrt1scannerauthresult"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = queuedInvoice, amountSats = 500u)
        stubLightningScan(bolt11 = scannerInvoice, amountSats = 600u)

        sut.onScanResult(queuedInvoice)
        sut.showScannerSheet()
        advanceUntilIdle()
        sut.setIsAuthenticated(true)

        sut.onScannerSheetResult(scannerInvoice)
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(600u, sut.sendUiState.value.amount)

        sut.hideSheet()
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.amount == 500uL }

        assertEquals(500u, sut.sendUiState.value.amount)
    }

    @Test
    fun `custom scanner result flushes a queued locked scan`() = test {
        val queuedInvoice = "lnbcrt1queuedbeforecustomscanner"
        val customResult = "pubky-custom-result"
        var receivedResult: String? = null
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = queuedInvoice, amountSats = 500u)

        sut.onScanResult(queuedInvoice)
        sut.showScannerSheet(onResult = { receivedResult = it })
        advanceUntilIdle()
        sut.setIsAuthenticated(true)

        sut.onScannerSheetResult(customResult)
        advanceUntilIdle()
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(customResult, receivedResult)
        assertEquals(500u, sut.sendUiState.value.amount)
    }

    @Test
    fun `main scanner zero amount lightning scan opens amount sheet`() = test {
        val bolt11 = "lnbcrt1zeroamount"
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)

        sut.showScannerSheet()
        sut.onScannerSheetResult(bolt11)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Amount), sut.currentSheet.value)
    }

    @Test
    fun `main scanner lightning scan opens QuickPay when enabled`() = test {
        val bolt11 = "lnbcrt1scannerquickpay"
        enableQuickPay()
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.showScannerSheet()
        sut.onScannerSheetResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(SendMethod.LIGHTNING, sut.sendUiState.value.payMethod)
        assertEquals(bolt11, sut.sendUiState.value.decodedInvoice?.bolt11)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `lightning scan uses QuickPay when enabled`() = test {
        val bolt11 = "lnbcrt1quickpay"
        enableQuickPay()
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(SendMethod.LIGHTNING, sut.sendUiState.value.payMethod)
        assertEquals(bolt11, sut.sendUiState.value.decodedInvoice?.bolt11)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `hiding send sheet clears quickPayData`() = test {
        val bolt11 = "lnbcrt1quickpayhide"
        enableQuickPay()
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.onScanResult(bolt11)
        advanceUntilIdle()
        sut.hideSheet()

        assertNull(sut.quickPayData.value)
    }

    @Test
    fun `lightning scan uses QuickPay when PIN is required for payments under daily cap`() = test {
        val bolt11 = "lnbcrt1quickpaypin"
        enableQuickPay()
        settingsData.value = settingsData.value.copy(
            isPinEnabled = true,
            isPinForPaymentsEnabled = true,
        )
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)
        sut.setIsAuthenticated(true)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `lightning scan uses QuickPay when PIN is on without PIN for payments`() = test {
        val bolt11 = "lnbcrt1quickpayunlocked"
        enableQuickPay()
        settingsData.value = settingsData.value.copy(isPinEnabled = true)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)
        sut.setIsAuthenticated(true)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `lightning scan skips QuickPay when daily spend cap is exceeded`() = test {
        val bolt11 = "lnbcrt1quickpaycap"
        enableQuickPay(canApply = false)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)
        sut.setIsAuthenticated(true)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertNull(sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `lightning scan uses QuickPay when hash is already open`() = test {
        val bolt11 = "lnbcrt1quickpayopen"
        enableQuickPay(canApply = false)
        whenever { quickPayRepo.hasOpen(any()) }.thenReturn(true)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)
        sut.setIsAuthenticated(true)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `QuickPay eligible scan remains deferred until authenticated`() = test {
        val bolt11 = "lnbcrt1lockedscan"
        enableQuickPay()
        settingsData.value = settingsData.value.copy(
            isPinEnabled = true,
            isPinForPaymentsEnabled = true,
        )
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        assertNull(sut.quickPayData.value)
        verify(coreService, never()).decode(bolt11)

        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
        verify(coreService).decode(bolt11)
    }

    @Test
    fun `payment deeplink is queued until authenticated when PIN is enabled`() = test {
        val bolt11 = "lnbcrt1lockeddeeplink"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.handleDeeplinkIntent(Intent(Intent.ACTION_VIEW, "lightning:$bolt11".toUri()))
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)

        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `latest locked scan replaces earlier input`() = test {
        val first = "lnbcrt1lockedfirst"
        val second = "lnbcrt1lockedsecond"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = first, amountSats = 500u)
        stubLightningScan(bolt11 = second, amountSats = 600u)

        sut.openContactPayment(paymentRequest = first, publicKey = "pubkyfirst")
        sut.onScanResult(second)
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        assertNull(activeContactPaymentContext())
        verify(coreService, never()).decode(any())

        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertEquals(600u, sut.sendUiState.value.amount)
        assertNull(activeContactPaymentContext())
        verify(coreService, never()).decode(first)
        verify(coreService).decode(second)

        sut.hideSheet()
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `duplicate locked scans are queued once`() = test {
        val bolt11 = "lnbcrt1lockeddup"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.onScanResult(bolt11)
        sut.onScanResult(bolt11)
        advanceUntilIdle()

        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)

        sut.hideSheet()
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `locked contact scan restores its payment context`() = test {
        val contact = "lnbcrt1lockedcontact"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = contact, amountSats = 600u)

        sut.openContactPayment(paymentRequest = contact, publicKey = "pubkycontact")
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)

        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertEquals(600u, sut.sendUiState.value.amount)
        assertEquals(ContactPaymentContext("pubkycontact"), activeContactPaymentContext())
    }

    @Test
    fun `duplicate locked scan keeps incoming payment request context`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1lockedrequest"
        settingsData.value = SettingsData(isPinEnabled = true)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)

        sut.onScanResult(bolt11)
        sut.openContactPayment(
            paymentRequest = bolt11,
            publicKey = testPublicKey,
            incomingPaymentRequest = request,
        )
        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
        assertEquals(request, activeContactPaymentContext()?.incomingPaymentRequest)
    }

    @Test
    fun `context-free locked duplicate does not replace incoming payment request`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1lockedrequestduplicate"
        settingsData.value = SettingsData(isPinEnabled = true)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)

        sut.openContactPayment(
            paymentRequest = bolt11,
            publicKey = testPublicKey,
            incomingPaymentRequest = request,
        )
        sut.onScanResult(bolt11)
        sut.setIsAuthenticated(true)
        sut.currentSheet.first { it is Sheet.Send }

        assertTrue(sut.sendUiState.value.isPaymentRequest)
        assertEquals(request, activeContactPaymentContext()?.incomingPaymentRequest)
    }

    @Test
    fun `new scan waits for an active locked replay`() = test {
        val lockedInvoice = "lnbcrt1activelockedreplay"
        val newInvoice = "lnbcrt1afteractivelockedreplay"
        val lockedScanStarted = CompletableDeferred<Unit>()
        val finishLockedScan = CompletableDeferred<Unit>()
        var newScanDecodeCount = 0
        settingsData.value = SettingsData(isPinEnabled = true)
        whenever(coreService.decode(lockedInvoice)).doSuspendableAnswer {
            lockedScanStarted.complete(Unit)
            finishLockedScan.await()
            Scanner.Lightning(lightningInvoice(lockedInvoice, 500u))
        }
        whenever(coreService.decode(newInvoice)).doSuspendableAnswer {
            newScanDecodeCount += 1
            Scanner.Lightning(lightningInvoice(newInvoice, 600u))
        }
        whenever(lightningRepo.canSend(any())).thenReturn(true)

        sut.onScanResult(lockedInvoice)
        sut.setIsAuthenticated(true)
        lockedScanStarted.await()
        sut.onScanResult(newInvoice)
        runCurrent()

        assertEquals(0, newScanDecodeCount)

        finishLockedScan.complete(Unit)
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(500u, sut.sendUiState.value.amount)

        sut.hideSheet()
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.amount == 600uL }

        assertEquals(1, newScanDecodeCount)
    }

    @Test
    fun `deferred scan waits for an active sheet transition`() = test {
        val firstInvoice = "lnbcrt1sheettransitionfirst"
        val secondInvoice = "lnbcrt1sheettransitionsecond"
        val firstScanStarted = CompletableDeferred<Unit>()
        val finishFirstScan = CompletableDeferred<Unit>()
        var secondScanDecodeCount = 0
        settingsData.value = SettingsData(isPinEnabled = true)
        whenever(coreService.decode(firstInvoice)).doSuspendableAnswer {
            firstScanStarted.complete(Unit)
            finishFirstScan.await()
            Scanner.Lightning(lightningInvoice(firstInvoice, 500u))
        }
        whenever(coreService.decode(secondInvoice)).doSuspendableAnswer {
            secondScanDecodeCount += 1
            Scanner.Lightning(lightningInvoice(secondInvoice, 600u))
        }
        whenever(lightningRepo.canSend(any())).thenReturn(true)

        sut.onScanResult(firstInvoice)
        sut.setIsAuthenticated(true)
        firstScanStarted.await()
        sut.showSheet(Sheet.ConnectionClosed)
        sut.onScanResult(secondInvoice)

        finishFirstScan.complete(Unit)
        runCurrent()

        assertNull(sut.currentSheet.value)
        assertEquals(0, secondScanDecodeCount)
        assertEquals(500u, sut.sendUiState.value.amount)

        advanceTimeBy(TRANSITION_SCREEN_MS)
        runCurrent()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertEquals(0, secondScanDecodeCount)
        assertEquals(500u, sut.sendUiState.value.amount)

        sut.hideSheet()
        sut.currentSheet.first { it is Sheet.Send && sut.sendUiState.value.amount == 600uL }

        assertEquals(1, secondScanDecodeCount)
    }

    @Test
    fun `active locked replay ignores the same scan`() = test {
        val bolt11 = "lnbcrt1duplicatelockedreplay"
        val scanStarted = CompletableDeferred<Unit>()
        val finishScan = CompletableDeferred<Unit>()
        var decodeCount = 0
        settingsData.value = SettingsData(isPinEnabled = true)
        whenever(coreService.decode(bolt11)).doSuspendableAnswer {
            decodeCount += 1
            scanStarted.complete(Unit)
            finishScan.await()
            Scanner.Lightning(lightningInvoice(bolt11, 500u))
        }
        whenever(lightningRepo.canSend(any())).thenReturn(true)

        sut.onScanResult(bolt11)
        sut.setIsAuthenticated(true)
        scanStarted.await()
        sut.onScanResult(bolt11)
        finishScan.complete(Unit)
        sut.currentSheet.first { it is Sheet.Send }

        assertEquals(1, decodeCount)

        sut.hideSheet()
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        assertEquals(1, decodeCount)
    }

    @Test
    fun `queued scan flushes after timed sheet dismisses`() = test {
        val bolt11 = "lnbcrt1lockedtimed"
        settingsData.value = SettingsData(isPinEnabled = true)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        timedSheetType.value = TimedSheetType.BACKUP
        advanceUntilIdle()

        assertTrue(sut.currentSheet.value is Sheet.TimedSheet)

        sut.onScanResult(bolt11)
        sut.setIsAuthenticated(true)
        advanceUntilIdle()

        assertTrue(sut.currentSheet.value is Sheet.TimedSheet)

        timedSheetType.value = null
        advanceUntilIdle()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `contact lightning payment skips QuickPay and opens confirm`() = test {
        val bolt11 = "lnbcrt1contact"
        enableQuickPay()
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.openContactPayment(paymentRequest = bolt11, publicKey = "pubkycontact")
        advanceUntilIdle()

        assertNull(sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `contact lightning payment skips QuickPay even when hash is open`() = test {
        val bolt11 = "lnbcrt1contactopen"
        enableQuickPay()
        whenever { quickPayRepo.hasOpen(any()) }.thenReturn(true)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.openContactPayment(paymentRequest = bolt11, publicKey = "pubkycontact")
        advanceUntilIdle()

        assertNull(sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
    }

    @Test
    fun `incoming payment request opens the existing confirm flow with its fixed amount`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1paymentrequest"
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)
        whenever(lightningRepo.canSend(request.amountSats)).thenReturn(true)
        val privateContext = stubOpenedPaymentRequest(request, bolt11)
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        pendingPaykitPaymentRequests.value = listOf(request)
        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(30.seconds.inWholeMilliseconds)
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
        assertEquals(request.amountSats, sut.sendUiState.value.amount)
        assertTrue(sut.sendUiState.value.isPaymentRequest)
        assertEquals(
            ContactPaymentContext(testPublicKey, privateContext, request),
            activeContactPaymentContext(),
        )
    }

    @Test
    fun `incoming payment request closes when it is no longer pending`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1removedpaymentrequest"
        enablePaykitUi()
        pubkyPublicKey.value = testPublicKey
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)
        stubOpenedPaymentRequest(request, bolt11)
        whenever(paykitPaymentRequestRepo.refresh()).thenReturn(Result.success(Unit))

        pendingPaykitPaymentRequests.value = listOf(request)
        sut.startPaykitPaymentRequestPolling()
        advanceTimeBy(30.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)

        pendingPaykitPaymentRequests.value = emptyList()
        runCurrent()
        sut.stopPaykitPaymentRequestPolling()

        assertNull(sut.currentSheet.value)
        assertNull(activeContactPaymentContext())

        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()

        verify(paykitPaymentRequestRepo, never()).accept(any())
        verify(privatePaykitRepo, never()).consumePrivatePaymentList(any(), any())
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `duplicate payment request confirmation submits only once`() = test {
        val address = "bcrt1qpaymentrequest"
        val request = paymentRequest()
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        balanceState.value = BalanceState(maxSendOnchainSats = 100_000u)
        whenever(paykitPaymentRequestRepo.accept(request)).thenReturn(Result.success(Unit))
        whenever(privatePaykitRepo.consumePrivatePaymentList(testPublicKey, privateContext))
            .thenReturn(Result.success(Unit))
        whenever {
            lightningRepo.sendOnChain(
                address = address,
                sats = request.amountSats,
                speed = TransactionSpeed.Medium,
                utxosToSpend = null,
                isMaxAmount = false,
                tags = emptyList(),
            )
        }.thenReturn(Result.success("txid"))
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = address,
                amount = request.amountSats,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
                isPaymentRequest = true,
            ),
        )

        sut.setSendEvent(SendEvent.PayConfirmed)
        runCurrent()
        sut.setSendEvent(SendEvent.PayConfirmed)
        runCurrent()
        advanceUntilIdle()

        verify(paykitPaymentRequestRepo).accept(request)
        verify(privatePaykitRepo).consumePrivatePaymentList(testPublicKey, privateContext)
        verify(lightningRepo).sendOnChain(
            address = address,
            sats = request.amountSats,
            speed = TransactionSpeed.Medium,
            utxosToSpend = null,
            isMaxAmount = false,
            tags = emptyList(),
        )
    }

    @Test
    fun `incoming payment request rejects a mismatched fixed invoice before confirmation`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1mismatchedconfirmation"
        stubLightningScan(bolt11 = bolt11, amountSats = 2_501uL)

        sut.openContactPayment(
            paymentRequest = bolt11,
            publicKey = testPublicKey,
            privatePaymentContext = PrivatePaykitPaymentContext("bitkit/server", 7uL),
            incomingPaymentRequest = request,
        )
        advanceUntilIdle()

        assertNull(sut.currentSheet.value)
        assertNull(activeContactPaymentContext())
    }

    @Test
    fun `manual send path clears stale contact context`() = test {
        setActiveContactPaymentContext("pubkycontact")

        sut.setSendEvent(SendEvent.EnterManually)
        advanceUntilIdle()

        assertNull(activeContactPaymentContext())
    }

    @Test
    fun `address continue clears stale contact context before decoding`() = test {
        val bolt11 = "lnbcrt1manual"
        setActiveContactPaymentContext("pubkycontact")
        stubLightningScan(bolt11 = bolt11, amountSats = 0u)

        sut.setSendEvent(SendEvent.AddressContinue(bolt11))
        advanceUntilIdle()

        assertNull(activeContactPaymentContext())
        assertNull(sut.sendUiState.value.contactPaymentProfile)
    }

    @Test
    fun `private onchain contact payment consumes private list before send`() = test {
        val address = "bcrt1qprivatecontact"
        val contactKey = "pubkycontact"
        val privateContext = PrivatePaykitPaymentContext("bitkit/wallet", 7uL)
        balanceState.value = BalanceState(maxSendOnchainSats = 100_000u)
        whenever {
            lightningRepo.sendOnChain(
                address = address,
                sats = 1000u,
                speed = TransactionSpeed.Medium,
                utxosToSpend = null,
                isMaxAmount = false,
                tags = emptyList(),
            )
        }.thenReturn(Result.success("txid"))
        whenever(privatePaykitRepo.consumePrivatePaymentList(contactKey, privateContext))
            .thenReturn(Result.success(Unit))
        setActiveContactPaymentContext(contactKey, privateContext)
        setSendState(
            SendUiState(
                address = address,
                amount = 1000u,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
            ),
        )

        confirmCurrentPayment()

        verify(privatePaykitRepo).consumePrivatePaymentList(contactKey, privateContext)
    }

    @Test
    fun `incoming payment request consumes its private list before it is accepted`() = test {
        val address = "bcrt1qpaymentrequest"
        val request = paymentRequest()
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        balanceState.value = BalanceState(maxSendOnchainSats = 100_000u)
        whenever(paykitPaymentRequestRepo.accept(request)).thenReturn(Result.success(Unit))
        whenever(privatePaykitRepo.consumePrivatePaymentList(testPublicKey, privateContext))
            .thenReturn(Result.success(Unit))
        whenever {
            lightningRepo.sendOnChain(
                address = address,
                sats = request.amountSats,
                speed = TransactionSpeed.Medium,
                utxosToSpend = null,
                isMaxAmount = false,
                tags = emptyList(),
            )
        }.thenReturn(Result.success("txid"))
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = address,
                amount = request.amountSats,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
                isPaymentRequest = true,
            ),
        )

        confirmCurrentPayment()

        inOrder(privatePaykitRepo, paykitPaymentRequestRepo).apply {
            verify(privatePaykitRepo).consumePrivatePaymentList(testPublicKey, privateContext)
            verify(paykitPaymentRequestRepo).accept(request)
        }
    }

    @Test
    fun `incoming payment request is not accepted when private list consumption fails`() = test {
        val address = "bcrt1qpaymentrequest"
        val request = paymentRequest()
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        balanceState.value = BalanceState(maxSendOnchainSats = 100_000u)
        whenever(privatePaykitRepo.consumePrivatePaymentList(testPublicKey, privateContext))
            .thenReturn(Result.failure(IllegalStateException("Payment list already consumed")))
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = address,
                amount = request.amountSats,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
                isPaymentRequest = true,
            ),
        )

        confirmCurrentPayment()

        verify(paykitPaymentRequestRepo, never()).accept(any())
        verify(lightningRepo, never()).sendOnChain(
            address = any(),
            sats = any(),
            speed = anyOrNull(),
            utxosToSpend = anyOrNull(),
            feeRates = anyOrNull(),
            isTransfer = any(),
            channelId = anyOrNull(),
            isMaxAmount = any(),
            tags = any(),
        )
    }

    @Test
    fun `incoming payment request rejects mismatched fixed invoice amount before acceptance`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1mismatchedrequest"
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = bolt11,
                amount = request.amountSats,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 2_501uL),
                isPaymentRequest = true,
            ),
        )

        confirmCurrentPayment()

        verify(paykitPaymentRequestRepo, never()).accept(any())
        verify(privatePaykitRepo, never()).consumePrivatePaymentList(any(), any())
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `incoming payment request rejects changed onchain amount before acceptance`() = test {
        val request = paymentRequest()
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = "bcrt1qchangedrequest",
                amount = 2_501uL,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
                isPaymentRequest = true,
            ),
        )

        confirmCurrentPayment()

        verify(paykitPaymentRequestRepo, never()).accept(any())
        verify(privatePaykitRepo, never()).consumePrivatePaymentList(any(), any())
        verify(lightningRepo, never()).sendOnChain(
            address = any(),
            sats = any(),
            speed = anyOrNull(),
            utxosToSpend = anyOrNull(),
            feeRates = anyOrNull(),
            isTransfer = any(),
            channelId = anyOrNull(),
            isMaxAmount = any(),
            tags = any(),
        )
    }

    @Test
    fun `incoming payment request rejects changed amount for amountless invoice`() = test {
        val request = paymentRequest()
        val bolt11 = "lnbcrt1changedrequest"
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = bolt11,
                amount = 2_501uL,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 0uL),
                isPaymentRequest = true,
            ),
        )

        confirmCurrentPayment()

        verify(paykitPaymentRequestRepo, never()).accept(any())
        verify(privatePaykitRepo, never()).consumePrivatePaymentList(any(), any())
        verify(lightningRepo, never()).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `expired incoming payment request is not submitted`() = test {
        val address = "bcrt1qexpiredrequest"
        val request = paymentRequest()
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", 7uL)
        whenever(paykitPaymentRequestRepo.isPending(request)).thenReturn(false)
        setActiveContactPaymentContext(testPublicKey, privateContext, request)
        setSendState(
            SendUiState(
                address = address,
                amount = request.amountSats,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
                isPaymentRequest = true,
            ),
        )

        confirmCurrentPayment()

        verify(paykitPaymentRequestRepo, never()).accept(any())
        verify(privatePaykitRepo, never()).consumePrivatePaymentList(any(), any())
        verify(lightningRepo, never()).sendOnChain(
            address = any(),
            sats = any(),
            speed = anyOrNull(),
            utxosToSpend = anyOrNull(),
            feeRates = anyOrNull(),
            isTransfer = any(),
            channelId = anyOrNull(),
            isMaxAmount = any(),
            tags = any(),
        )
    }

    @Test
    fun `non-contact onchain payment does not discard private endpoint`() = test {
        val address = "bcrt1qpublicpayment"
        balanceState.value = BalanceState(maxSendOnchainSats = 100_000u)
        whenever {
            lightningRepo.sendOnChain(
                address = address,
                sats = 1000u,
                speed = TransactionSpeed.Medium,
                utxosToSpend = null,
                isMaxAmount = false,
                tags = emptyList(),
            )
        }.thenReturn(Result.success("txid"))
        setSendState(
            SendUiState(
                address = address,
                amount = 1000u,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
            ),
        )

        confirmCurrentPayment()
    }

    @Test
    fun `private lightning contact payment consumes private list before send`() = test {
        val bolt11 = "lnbcrt1privatecontact"
        val paymentHash = "payment_hash"
        val contactKey = "pubkycontact"
        val privateContext = PrivatePaykitPaymentContext("bitkit/wallet", 7uL)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null)).thenReturn(Result.success(paymentHash))
        whenever(privatePaykitRepo.consumePrivatePaymentList(contactKey, privateContext))
            .thenReturn(Result.success(Unit))
        setActiveContactPaymentContext(contactKey, privateContext)
        setSendState(
            SendUiState(
                address = bolt11,
                amount = 1000u,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 1000u),
            ),
        )

        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()
        emitNodeEvent(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10uL,
            ),
        )
        advanceUntilIdle()

        verify(privatePaykitRepo).consumePrivatePaymentList(contactKey, privateContext)
    }

    @Test
    fun `private lightning pending payment consumes private list`() = test {
        val bolt11 = "lnbcrt1pending"
        val paymentHash = "pending_hash"
        val contactKey = "pubkycontact"
        val privateContext = PrivatePaykitPaymentContext("bitkit/wallet", 7uL)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null))
            .thenReturn(Result.failure(PaymentPendingException(paymentHash)))
        whenever(privatePaykitRepo.consumePrivatePaymentList(contactKey, privateContext))
            .thenReturn(Result.success(Unit))
        setActiveContactPaymentContext(contactKey, privateContext)
        setSendState(
            SendUiState(
                address = bolt11,
                amount = 1000u,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 1000u),
            ),
        )

        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()

        verify(privatePaykitRepo).consumePrivatePaymentList(contactKey, privateContext)
    }

    @Test
    fun `private lightning duplicate payment consumes private list`() = test {
        val bolt11 = "lnbcrt1duplicate"
        val contactKey = "pubkycontact"
        val privateContext = PrivatePaykitPaymentContext("bitkit/wallet", 7uL)
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null))
            .thenReturn(Result.failure(AppError("DuplicatePayment")))
        whenever(privatePaykitRepo.consumePrivatePaymentList(contactKey, privateContext))
            .thenReturn(Result.success(Unit))
        setActiveContactPaymentContext(contactKey, privateContext)
        setSendState(
            SendUiState(
                address = bolt11,
                amount = 1000u,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 1000u),
            ),
        )

        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()

        verify(privatePaykitRepo).consumePrivatePaymentList(contactKey, privateContext)
    }

    @Test
    fun `non-contact lightning payment does not discard private invoice`() = test {
        val bolt11 = "lnbcrt1public"
        val paymentHash = "payment_hash"
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null)).thenReturn(Result.success(paymentHash))
        setSendState(
            SendUiState(
                address = bolt11,
                amount = 1000u,
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = lightningInvoice(bolt11, amountSats = 1000u),
            ),
        )

        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()
        emitNodeEvent(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10uL,
            ),
        )
        advanceUntilIdle()

        verify(privatePaykitRepo, never()).discardRemoteLightningEndpoints(any(), any())
    }

    @Test
    fun `channel ready refreshes public Paykit endpoints when sharing enabled`() = test {
        enablePublicPaykitSharing()
        advanceUntilIdle()
        clearInvocations(publicPaykitRepo)

        emitNodeEvent(
            Event.ChannelReady(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                fundingTxo = null,
            ),
        )
        advanceUntilIdle()

        verify(publicPaykitRepo).syncCurrentPublishedEndpoints(forceRefreshLightning = false)
        verify(publicPaykitRepo).syncCurrentPublishedEndpoints(forceRefreshLightning = true)
    }

    @Test
    fun `channel closed refreshes public Paykit endpoints when sharing enabled`() = test {
        enablePublicPaykitSharing()
        advanceUntilIdle()
        clearInvocations(publicPaykitRepo)

        emitNodeEvent(
            Event.ChannelClosed(
                channelId = "testChannelId",
                userChannelId = "testUserChannelId",
                counterpartyNodeId = null,
                reason = null,
            ),
        )
        advanceUntilIdle()

        verify(publicPaykitRepo).syncCurrentPublishedEndpoints(forceRefreshLightning = false)
    }

    @Test
    fun `amount change clears confirmedWarnings`() = test {
        setUnifiedState(amount = 1000u)
        sut.setSendEvent(SendEvent.ConfirmAmountWarning(SanityWarning.VALUE_OVER_100_USD))
        advanceUntilIdle()

        assertTrue(sut.sendUiState.value.confirmedWarnings.isNotEmpty())

        sut.setSendEvent(SendEvent.AmountChange(2000u))
        advanceUntilIdle()

        assertTrue(sut.sendUiState.value.confirmedWarnings.isEmpty())
    }

    @Test
    fun `refreshFeeEstimates preserves lightning fee when payMethod is LIGHTNING`() = test {
        val lightningFee = SendFee.Lightning(42)
        setUnifiedState(amount = 1000u, payMethod = SendMethod.LIGHTNING, fee = lightningFee)
        advanceUntilIdle()

        sut.setSendEvent(SendEvent.SpeedAndFee)
        advanceUntilIdle()

        val currentFee = sut.sendUiState.value.fee
        assertEquals(lightningFee, currentFee)
    }

    @Test
    fun `lastLightningFee persists after switching to onchain`() = test {
        balanceState.value = BalanceState(
            maxSendOnchainSats = 100_000u,
            maxSendLightningSats = 100_000u,
        )
        setUnifiedState(
            amount = 1000u,
            payMethod = SendMethod.LIGHTNING,
            fee = SendFee.Lightning(42),
            lastLightningFee = 42L,
        )
        advanceUntilIdle()

        sut.setTransactionSpeed(TransactionSpeed.Medium)
        advanceUntilIdle()

        assertEquals(SendMethod.ONCHAIN, sut.sendUiState.value.payMethod)
        assertEquals(42L, sut.sendUiState.value.lastLightningFee)
    }

    @Test
    fun `lastLightningFee is zero initially`() = test {
        assertEquals(0L, sut.sendUiState.value.lastLightningFee)
    }

    @Test
    fun `private Paykit refreshes marker but waits for contacts load before pruning`() = test {
        enablePaykitUi()
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        advanceUntilIdle()
        clearInvocations(privatePaykitRepo, publicPaykitRepo)

        pubkyPublicKey.value = testPublicKey
        advanceUntilIdle()

        verify(publicPaykitRepo).syncLocalReceiverMarker()
        verify(privatePaykitRepo, never()).prepareSavedContacts(any<Collection<String>>(), any())
        verify(privatePaykitRepo, never()).pruneUnsavedContactState(any<Collection<String>>())

        pubkyContactsLoadVersion.value = 1L
        advanceUntilIdle()

        verify(privatePaykitRepo).prepareSavedContacts(any<Collection<String>>(), any())
        verify(privatePaykitRepo).pruneUnsavedContactState(any<Collection<String>>())
    }

    @Test
    fun `private Paykit removes stale contact without duplicate load version cleanup`() = test {
        enablePaykitUi()
        advanceUntilIdle()

        val contact = PubkyProfile(
            publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
            name = "Bob",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            status = null,
        )
        pubkyPublicKey.value = testPublicKey
        pubkyContacts.value = listOf(contact)
        pubkyContactsLoadVersion.value = 1L
        advanceUntilIdle()
        clearInvocations(privatePaykitRepo)

        pubkyContacts.value = emptyList()
        pubkyContactsLoadVersion.value = 2L
        advanceUntilIdle()

        verify(privatePaykitRepo).removeSavedContact(contact.publicKey)
        verify(privatePaykitRepo).prepareSavedContacts(emptySet<String>(), false)
        verify(privatePaykitRepo).pruneUnsavedContactState(emptySet<String>())
    }

    @Test
    fun `private Paykit refresh retries public cleanup while UI is disabled`() = test {
        settingsData.value = SettingsData(publicPaykitCleanupPending = true)
        whenever(publicPaykitRepo.syncPublishedEndpoints(publish = false)).thenReturn(Result.success(Unit))

        sut.refreshPrivatePaykitEndpoints()
        advanceUntilIdle()

        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        assertFalse(settingsData.value.publicPaykitCleanupPending)
        verify(privatePaykitRepo).retryPendingEndpointRemoval(emptyList())
        verify(privatePaykitRepo, never()).reconcileReservedReceiveIndexes()
    }

    @Test
    fun `private Paykit refresh reconciles pending public state when sharing is enabled`() = test {
        isPaykitEnabled.value = true
        settingsData.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitCleanupPending = true,
        )
        whenever(publicPaykitRepo.syncCurrentPublishedEndpoints()).thenReturn(Result.success(Unit))

        sut.refreshPrivatePaykitEndpoints()
        advanceUntilIdle()

        verify(publicPaykitRepo).syncCurrentPublishedEndpoints()
        assertFalse(settingsData.value.publicPaykitCleanupPending)
        verify(privatePaykitRepo).retryPendingEndpointRemoval(emptyList())
    }

    @Test
    fun `private Paykit refresh republishes marker for private-only sharing`() = test {
        isPaykitEnabled.value = true
        pubkyPublicKey.value = testPublicKey
        settingsData.value = SettingsData(sharesPrivatePaykitEndpoints = true)

        sut.refreshPrivatePaykitEndpoints()
        advanceUntilIdle()

        verify(publicPaykitRepo).syncLocalReceiverMarker()
    }

    private suspend fun TestScope.confirmCurrentPayment() {
        sut.setSendEvent(SendEvent.SwipeToPay)
        advanceUntilIdle()
        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()
    }

    private fun enableQuickPay(canApply: Boolean = true) {
        settingsData.value = SettingsData(isQuickPayEnabled = true, quickPayAmount = 5)
        whenever { quickPayRepo.canApply(any<ULong>()) }.thenReturn(Result.success(canApply))
    }

    private suspend fun stubLightningScan(bolt11: String, amountSats: ULong) {
        whenever { coreService.decode(bolt11) }
            .thenReturn(Scanner.Lightning(lightningInvoice(bolt11, amountSats)))
        whenever(lightningRepo.canSend(amountSats)).thenReturn(true)
    }

    private suspend fun stubOpenedPaymentRequest(
        request: PaykitPaymentRequest,
        paymentRequest: String,
        privateListIndex: ULong = 7uL,
    ): PrivatePaykitPaymentContext {
        val privateContext = PrivatePaykitPaymentContext("bitkit/server", privateListIndex)
        whenever { privatePaykitRepo.beginPaymentRequest(request) }.thenReturn(
            Result.success(
                PublicPaykitPaymentResult.Opened(
                    paymentRequest = paymentRequest,
                    privatePaymentContext = privateContext,
                ),
            ),
        )
        return privateContext
    }

    private fun lightningInvoice(bolt11: String, amountSats: ULong) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = byteArrayOf(1, 2, 3),
        amountSatoshis = amountSats,
        timestampSeconds = 0u,
        expirySeconds = 86_400u,
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )

    private suspend fun enablePublicPaykitSharing() {
        whenever(publicPaykitRepo.syncCurrentPublishedEndpoints(any(), any())).thenReturn(Result.success(Unit))
        walletState.value = WalletState(onchainAddress = "bc1qtest")
        isPaykitEnabled.value = true
        settingsData.value = SettingsData(sharesPublicPaykitEndpoints = true)
    }

    private fun enablePaykitUi() {
        isPaykitEnabled.value = true
    }

    private fun samRockSetupRequest() = SamRockSetupRequest(
        postUrl = "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
        storeId = "store",
        otp = "secret",
        requestedMethods = setOf(SamRockPaymentMethod.BTC_ONCHAIN),
        hasUnknownMethods = false,
        hostDisplayName = "btcpay.example.com",
        logDescription = "https://btcpay.example.com/plugins/store/samrock/protocol",
    )

    private fun screenIntent(vararg segments: String): Intent {
        val uri = mock<Uri> {
            on { toString() }.thenReturn("bitkit://screen/${segments.joinToString("/")}")
            on { scheme }.thenReturn("bitkit")
            on { host }.thenReturn("screen")
            on { pathSegments }.thenReturn(segments.toList())
        }
        return mock {
            on { action }.thenReturn(Intent.ACTION_VIEW)
            on { data }.thenReturn(uri)
        }
    }

    private fun legacyRecoveryModeIntent(): Intent {
        val uri = mock<Uri> {
            on { toString() }.thenReturn("bitkit://recovery-mode")
            on { scheme }.thenReturn("bitkit")
            on { host }.thenReturn("recovery-mode")
            on { pathSegments }.thenReturn(emptyList())
        }
        return mock {
            on { action }.thenReturn(Intent.ACTION_VIEW)
            on { data }.thenReturn(uri)
        }
    }

    private fun samRockIntent(url: String): Intent {
        val uri = mock<Uri> {
            on { toString() }.thenReturn(url)
            on { scheme }.thenReturn("https")
            on { host }.thenReturn("btcpay.example.com")
            on { path }.thenReturn("/plugins/store/samrock/protocol")
        }
        return mock {
            on { action }.thenReturn(Intent.ACTION_VIEW)
            on { data }.thenReturn(uri)
        }
    }

    private fun samRockDeepLink(setupUrl: String): String {
        return "bitkit://btcpay/samrock?url=${setupUrl.urlEncode()}"
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun recoveryModeIntent(action: String = Intent.ACTION_VIEW): Intent {
        val uri = mock<Uri> {
            on { toString() }.thenReturn("bitkit://recovery-mode")
            on { scheme }.thenReturn("bitkit")
            on { host }.thenReturn("recovery-mode")
            on { pathSegments }.thenReturn(emptyList())
        }
        return mock {
            on { this.action }.thenReturn(action)
            on { data }.thenReturn(uri)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPendingContactPaymentContext(paymentHash: String, publicKey: String) {
        val field = AppViewModel::class.java.getDeclaredField("pendingContactPaymentContexts")
        field.isAccessible = true
        val contexts = field.get(sut) as MutableMap<String, ContactPaymentContext>
        contexts[paymentHash] = ContactPaymentContext(publicKey)
    }

    private fun setActiveContactPaymentContext(
        publicKey: String,
        privatePaymentContext: PrivatePaykitPaymentContext? = null,
        incomingPaymentRequest: PaykitPaymentRequest? = null,
    ) {
        val field = AppViewModel::class.java.getDeclaredField("activeContactPaymentContext")
        field.isAccessible = true
        field.set(sut, ContactPaymentContext(publicKey, privatePaymentContext, incomingPaymentRequest))
    }

    private fun activeContactPaymentContext(): ContactPaymentContext? {
        val field = AppViewModel::class.java.getDeclaredField("activeContactPaymentContext")
        field.isAccessible = true
        return field.get(sut) as ContactPaymentContext?
    }

    private fun isPresentingPaymentRequest(): Boolean {
        val field = AppViewModel::class.java.getDeclaredField("isPresentingPaymentRequest")
        field.isAccessible = true
        return field.getBoolean(sut)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingContactPaymentContext(paymentHash: String): ContactPaymentContext? {
        val field = AppViewModel::class.java.getDeclaredField("pendingContactPaymentContexts")
        field.isAccessible = true
        val contexts = field.get(sut) as MutableMap<String, ContactPaymentContext>
        return contexts[paymentHash]
    }

    @Suppress("UNCHECKED_CAST")
    private fun setSendState(state: SendUiState) {
        val field = AppViewModel::class.java.getDeclaredField("_sendUiState")
        field.isAccessible = true
        (field.get(sut) as MutableStateFlow<SendUiState>).value = state
    }

    private fun setUnifiedState(
        amount: ULong = 0u,
        payMethod: SendMethod = SendMethod.LIGHTNING,
        fee: SendFee? = null,
        lastLightningFee: Long = 0L,
    ) {
        setSendState(
            SendUiState(
                address = "bcrt1qtest",
                amount = amount,
                isUnified = true,
                payMethod = payMethod,
                fee = fee,
                lastLightningFee = lastLightningFee,
                confirmedWarnings = persistentListOf(),
                speed = TransactionSpeed.Medium,
            )
        )
        // Trigger updateCanSwitchWallet via reflection
        val method = AppViewModel::class.java.getDeclaredMethod("updateCanSwitchWallet")
        method.isAccessible = true
        method.invoke(sut)
    }

    private fun paymentRequest() = PaykitPaymentRequest(
        paymentRequestId = "request-id",
        counterparty = testPublicKey,
        counterpartyReceiverPath = "bitkit/server",
        amountValue = "0.000025",
        amountSats = 2_500uL,
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf("lightning_bolt11"),
    )
}

private const val SAMROCK_SETUP_URL =
    "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
