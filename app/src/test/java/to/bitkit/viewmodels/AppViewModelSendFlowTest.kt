package to.bitkit.viewmodels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import app.cash.turbine.test
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.models.BalanceState
import to.bitkit.models.PubkyProfile
import to.bitkit.models.SamRockPaymentMethod
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.models.TransactionSpeed
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
import to.bitkit.repositories.PaymentPendingException
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.repositories.SamRockRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.services.ActivityService
import to.bitkit.services.AppUpdaterService
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.Routes
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.shared.toast.ToastQueueManager
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.usecases.FormatMoneyValue
import to.bitkit.utils.AppError
import to.bitkit.utils.timedsheets.TimedSheetManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
    private val cacheStore = mock<CacheStore>()
    private val transferRepo = mock<TransferRepo>()
    private val migrationService = mock<MigrationService>()
    private val coreService = mock<CoreService>()
    private val activityService = mock<ActivityService>()
    private val keychain = mock<Keychain>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val publicPaykitRepo = mock<PublicPaykitRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val samRockRepo = mock<SamRockRepo>()
    private val widgetsRepo = mock<WidgetsRepo>()
    private val formatMoneyValue = mock<FormatMoneyValue>()
    private val clipboardManager = mock<ClipboardManager>()
    private val toastManager = mock<ToastQueueManager>()

    private val balanceState = MutableStateFlow(BalanceState())
    private val settingsData = MutableStateFlow(SettingsData())
    private val isPaykitEnabled = MutableStateFlow(false)
    private val walletState = MutableStateFlow(WalletState())
    private val nodeEvents = MutableSharedFlow<Event>()
    private val pubkyPublicKey = MutableStateFlow<String?>(null)
    private val pubkyContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    private val pubkyContactsLoadVersion = MutableStateFlow(0L)
    private val testPublicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

    private val timedSheetManager = mock<TimedSheetManager>()

    @Before
    fun setUp() {
        stubRepositories()
        sut = createViewModel()
    }

    private fun stubRepositories() {
        whenever(context.getString(any())).thenReturn("")
        whenever(context.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(clipboardManager)
        whenever(connectivityRepo.isOnline).thenReturn(MutableStateFlow(ConnectivityState.CONNECTED))
        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(mock()))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever(hwWalletRepo.receivedTxs).thenReturn(MutableSharedFlow())
        whenever(coreService.activity).thenReturn(activityService)
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(walletRepo.walletExists()).thenReturn(true)
        stubSettingsStore()
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState()))
        whenever { blocktankRepo.refreshInfo() }.thenReturn(Result.success(Unit))
        whenever(timedSheetManager.currentSheet).thenReturn(MutableStateFlow(null))
        whenever(migrationService.isShowingMigrationLoading).thenReturn(MutableStateFlow(false))
        whenever { migrationService.needsPostMigrationSync() }.thenReturn(false)
        whenever { migrationService.isMigrationChecked() }.thenReturn(true)
        whenever { widgetsRepo.refreshEnabledWidgets() }.thenReturn(Unit)
        whenever { lightningRepo.updateGeoBlockState() }.thenReturn(Unit)
        whenever(pubkyRepo.sessionRestorationFailed).thenReturn(MutableStateFlow(false))
        whenever(pubkyRepo.publicKey).thenReturn(pubkyPublicKey)
        whenever(pubkyRepo.contacts).thenReturn(pubkyContacts)
        whenever(pubkyRepo.contactsLoadVersion).thenReturn(pubkyContactsLoadVersion)
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
        whenever { privatePaykitRepo.contactPublicKeyForPrivateOnchainAddresses(any<Collection<String>>()) }
            .thenReturn(null)
        whenever { privatePaykitRepo.discardRemoteLightningEndpoints(any(), any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.discardRemoteOnchainEndpoints(any(), any()) }
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
        cacheStore = cacheStore,
        transferRepo = transferRepo,
        migrationService = migrationService,
        coreService = coreService,
        publicPaykitRepo = publicPaykitRepo,
        privatePaykitRepo = privatePaykitRepo,
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

    @Test
    fun `onUsbDeviceAttached forwards to the hardware wallet repo`() = test {
        sut.onUsbDeviceAttached()

        verify(hwWalletRepo).onTransportRestored()
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
        whenever(pubkyRepo.hasSecretKey()).thenReturn(true)
        val authUrl = "pubkyauth://auth?caps=/pub/paykit/v0/:rw"

        sut.handleDeeplinkIntent(Intent(Intent.ACTION_VIEW, authUrl.toUri()))
        advanceUntilIdle()

        assertEquals(Sheet.PubkyAuth(authUrl), sut.currentSheet.value)
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
        nodeEvents.emit(
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
    }

    @Test
    fun `pending contact lightning failure clears context`() = test {
        val paymentHash = "pending_hash"
        whenever(pendingPaymentRepo.isPending(paymentHash)).thenReturn(true)
        whenever(pendingPaymentRepo.isActive(paymentHash)).thenReturn(false)
        advanceUntilIdle()

        setPendingContactPaymentContext(paymentHash, "pubkycontact")
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                reason = null,
            ),
        )
        advanceUntilIdle()

        verify(pendingPaymentRepo).resolve(PendingPaymentResolution.Failure(paymentHash))
        assertNull(pendingContactPaymentContext(paymentHash))
    }

    @Test
    fun `active lightning send failure hides send sheet`() = test {
        val bolt11 = "lnbcrt1activefailure"
        val paymentHash = "010203"
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

        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                reason = null,
            ),
        )
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
        enableQuickPay(thresholdSats = 1000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.showScannerSheet()
        sut.onScannerSheetResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `lightning scan uses QuickPay when enabled`() = test {
        val bolt11 = "lnbcrt1quickpay"
        enableQuickPay(thresholdSats = 1000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.onScanResult(bolt11)
        advanceUntilIdle()

        assertEquals(QuickPayData.Bolt11(sats = 500u, bolt11 = bolt11), sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.QuickPay), sut.currentSheet.value)
    }

    @Test
    fun `contact lightning payment skips QuickPay and opens confirm`() = test {
        val bolt11 = "lnbcrt1contact"
        enableQuickPay(thresholdSats = 1000u)
        stubLightningScan(bolt11 = bolt11, amountSats = 500u)

        sut.openContactPayment(paymentRequest = bolt11, publicKey = "pubkycontact")
        advanceUntilIdle()

        assertNull(sut.quickPayData.value)
        assertEquals(Sheet.Send(SendRoute.Confirm), sut.currentSheet.value)
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
    fun `private onchain contact payment discards remote address after send`() = test {
        val address = "bcrt1qprivatecontact"
        val contactKey = "pubkycontact"
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
        setActiveContactPaymentContext(contactKey)
        setSendState(
            SendUiState(
                address = address,
                amount = 1000u,
                payMethod = SendMethod.ONCHAIN,
                speed = TransactionSpeed.Medium,
            ),
        )

        confirmCurrentPayment()

        verify(privatePaykitRepo).discardRemoteOnchainEndpoints(contactKey, setOf(address))
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

        verify(privatePaykitRepo, never()).discardRemoteOnchainEndpoints(any(), any())
    }

    @Test
    fun `private lightning contact payment discards remote invoice after send`() = test {
        val bolt11 = "lnbcrt1privatecontact"
        val paymentHash = "payment_hash"
        val contactKey = "pubkycontact"
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null)).thenReturn(Result.success(paymentHash))
        setActiveContactPaymentContext(contactKey)
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
        nodeEvents.emit(
            Event.PaymentSuccessful(
                paymentId = "payment_id",
                paymentHash = paymentHash,
                paymentPreimage = "preimage",
                feePaidMsat = 10uL,
            ),
        )
        advanceUntilIdle()

        verify(privatePaykitRepo).discardRemoteLightningEndpoints(contactKey, setOf(paymentHash))
    }

    @Test
    fun `private lightning pending payment discards remote invoice`() = test {
        val bolt11 = "lnbcrt1pending"
        val paymentHash = "pending_hash"
        val contactKey = "pubkycontact"
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null))
            .thenReturn(Result.failure(PaymentPendingException(paymentHash)))
        setActiveContactPaymentContext(contactKey)
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

        verify(privatePaykitRepo).discardRemoteLightningEndpoints(contactKey, setOf(paymentHash))
    }

    @Test
    fun `private lightning duplicate payment discards decoded invoice`() = test {
        val bolt11 = "lnbcrt1duplicate"
        val contactKey = "pubkycontact"
        balanceState.value = BalanceState(maxSendLightningSats = 100_000u)
        whenever(lightningRepo.payInvoice(bolt11 = bolt11, sats = null))
            .thenReturn(Result.failure(AppError("DuplicatePayment")))
        setActiveContactPaymentContext(contactKey)
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

        verify(privatePaykitRepo).discardRemoteLightningEndpoints(contactKey, setOf("010203"))
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
        nodeEvents.emit(
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

        nodeEvents.emit(
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

        nodeEvents.emit(
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
    fun `private Paykit waits for contacts load before pruning`() = test {
        enablePaykitUi()
        advanceUntilIdle()
        clearInvocations(privatePaykitRepo)

        pubkyPublicKey.value = testPublicKey
        advanceUntilIdle()

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
            publicKey = "pubkycytinw71a3ge1esmzj5e53hsr3jtj6t4pogpgr6k75w9mzmyokzo",
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
        whenever { publicPaykitRepo.syncPublishedEndpoints(publish = false) }.thenReturn(Result.success(Unit))

        sut.refreshPrivatePaykitEndpoints()
        advanceUntilIdle()

        verify(publicPaykitRepo).syncPublishedEndpoints(publish = false)
        assertFalse(settingsData.value.publicPaykitCleanupPending)
        verify(privatePaykitRepo).retryPendingEndpointRemoval(emptyList())
        verify(privatePaykitRepo, never()).reconcileReservedReceiveIndexes()
    }

    @Test
    fun `private Paykit refresh clears stale public cleanup when public sharing is enabled`() = test {
        isPaykitEnabled.value = true
        settingsData.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitCleanupPending = true,
        )

        sut.refreshPrivatePaykitEndpoints()
        advanceUntilIdle()

        verify(publicPaykitRepo, never()).syncPublishedEndpoints(publish = false)
        assertFalse(settingsData.value.publicPaykitCleanupPending)
        verify(privatePaykitRepo).retryPendingEndpointRemoval(emptyList())
    }

    private suspend fun TestScope.confirmCurrentPayment() {
        sut.setSendEvent(SendEvent.SwipeToPay)
        advanceUntilIdle()
        sut.setSendEvent(SendEvent.PayConfirmed)
        advanceUntilIdle()
    }

    private fun enableQuickPay(thresholdSats: ULong) {
        settingsData.value = SettingsData(isQuickPayEnabled = true, quickPayAmount = 5)
        whenever(currencyRepo.convertFiatToSats(5.0, "USD")).thenReturn(Result.success(thresholdSats))
    }

    private suspend fun stubLightningScan(bolt11: String, amountSats: ULong) {
        whenever(coreService.decode(bolt11)).thenReturn(Scanner.Lightning(lightningInvoice(bolt11, amountSats)))
        whenever(lightningRepo.canSend(amountSats)).thenReturn(true)
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
        whenever { publicPaykitRepo.syncCurrentPublishedEndpoints(any(), any()) }.thenReturn(Result.success(Unit))
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

    private fun recoveryModeIntent(): Intent {
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

    @Suppress("UNCHECKED_CAST")
    private fun setPendingContactPaymentContext(paymentHash: String, publicKey: String) {
        val field = AppViewModel::class.java.getDeclaredField("pendingContactPaymentContexts")
        field.isAccessible = true
        val contexts = field.get(sut) as MutableMap<String, ContactPaymentContext>
        contexts[paymentHash] = ContactPaymentContext(publicKey)
    }

    private fun setActiveContactPaymentContext(publicKey: String) {
        val field = AppViewModel::class.java.getDeclaredField("activeContactPaymentContext")
        field.isAccessible = true
        field.set(sut, ContactPaymentContext(publicKey))
    }

    private fun activeContactPaymentContext(): ContactPaymentContext? {
        val field = AppViewModel::class.java.getDeclaredField("activeContactPaymentContext")
        field.isAccessible = true
        return field.get(sut) as ContactPaymentContext?
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
}

private const val SAMROCK_SETUP_URL =
    "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
