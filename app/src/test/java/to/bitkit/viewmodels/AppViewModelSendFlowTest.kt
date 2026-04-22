package to.bitkit.viewmodels

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.domain.commands.NotifyPaymentReceivedHandler
import to.bitkit.models.BalanceState
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.services.AppUpdaterService
import to.bitkit.services.CoreService
import to.bitkit.services.MigrationService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastQueueManager
import to.bitkit.usecases.FormatMoneyValue
import to.bitkit.utils.timedsheets.TimedSheetManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelSendFlowTest : BaseUnitTest() {

    private lateinit var sut: AppViewModel

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
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
    private val keychain = mock<Keychain>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val widgetsRepo = mock<WidgetsRepo>()
    private val formatMoneyValue = mock<FormatMoneyValue>()

    private val balanceState = MutableStateFlow(BalanceState())

    private val timedSheetManager = mock<TimedSheetManager>()

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("")
        whenever(connectivityRepo.isOnline).thenReturn(MutableStateFlow(ConnectivityState.CONNECTED))
        whenever(healthRepo.healthState).thenReturn(MutableStateFlow(mock()))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(lightningRepo.nodeEvents).thenReturn(MutableSharedFlow())
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(walletRepo.walletState).thenReturn(MutableStateFlow(WalletState()))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(timedSheetManager.currentSheet).thenReturn(MutableStateFlow(null))
        whenever(migrationService.isShowingMigrationLoading).thenReturn(MutableStateFlow(false))
        whenever { migrationService.needsPostMigrationSync() }.thenReturn(false)
        whenever { migrationService.isMigrationChecked() }.thenReturn(true)
        whenever { widgetsRepo.refreshEnabledWidgets() }.thenReturn(Unit)
        whenever { lightningRepo.updateGeoBlockState() }.thenReturn(Unit)
        whenever(pubkyRepo.sessionRestorationFailed).thenReturn(MutableStateFlow(false))
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull()))
            .thenReturn(Result.failure(Exception("not mocked")))
        whenever { lightningRepo.calculateTotalFee(any(), anyOrNull(), any(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(100uL))
        whenever { lightningRepo.getFeeRateForSpeed(any(), anyOrNull()) }
            .thenReturn(Result.success(2u))
        whenever { lightningRepo.canSend(any(), any()) }.thenReturn(true)

        sut = AppViewModel(
            connectivityRepo = connectivityRepo,
            healthRepo = healthRepo,
            toastManagerProvider = { mock<ToastQueueManager>() },
            timedSheetManagerProvider = { timedSheetManager },
            context = context,
            bgDispatcher = testDispatcher,
            keychain = keychain,
            lightningRepo = lightningRepo,
            pendingPaymentRepo = pendingPaymentRepo,
            walletRepo = walletRepo,
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
            appUpdateSheet = mock(),
            backupSheet = mock(),
            notificationsSheet = mock(),
            quickPaySheet = mock(),
            highBalanceSheet = mock(),
            formatMoneyValue = formatMoneyValue,
            widgetsRepo = widgetsRepo,
            pubkyRepo = pubkyRepo,
        )
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
