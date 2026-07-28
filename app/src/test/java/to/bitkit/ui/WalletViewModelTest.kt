package to.bitkit.ui

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PeerDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.of
import to.bitkit.models.BalanceState
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.services.BoltzService
import to.bitkit.services.MigrationService
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.WalletViewModel
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest : BaseUnitTest() {
    private lateinit var sut: WalletViewModel

    private val context = mock<Context>()
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val backupRepo = mock<BackupRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val migrationService = mock<MigrationService>()
    private val connectivityRepo = mock<ConnectivityRepo>()
    private val boltzService = mock<BoltzService>()

    private val lightningState = MutableStateFlow(LightningState())
    private val walletState = MutableStateFlow(WalletState())
    private val balanceState = MutableStateFlow(BalanceState())
    private val isRecoveryMode = MutableStateFlow(false)
    private val isOnline = MutableStateFlow(ConnectivityState.CONNECTED)

    @Before
    fun setUp() = runBlocking {
        whenever(context.getString(any())).thenReturn("")
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(migrationService.isMigrationChecked()).thenReturn(true)
        whenever(migrationService.isChannelRecoveryChecked()).thenReturn(true)
        whenever(migrationService.tryFetchMigrationPeersFromBackup()).thenReturn(emptyList())
        whenever { migrationService.getRNRemoteBackupTimestamp() }.thenReturn(null)
        whenever(connectivityRepo.isOnline).thenReturn(isOnline)
        whenever(boltzService.events).thenReturn(MutableSharedFlow())
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever { lightningRepo.getFeeRateForSpeed(any(), anyOrNull()) }.thenReturn(Result.success(1uL))

        sut = WalletViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
            pubkyRepo = pubkyRepo,
            migrationService = migrationService,
            connectivityRepo = connectivityRepo,
            boltzService = boltzService,
        )
    }

    @Test
    fun `ensureSwapUpdatesRunning should start updates when swaps are enabled`() = test {
        whenever(boltzService.isSwapSupported).thenReturn(true)
        whenever(boltzService.isSwapEnabled()).thenReturn(true)

        sut.ensureSwapUpdatesRunning()
        advanceUntilIdle()

        verify(boltzService).startUpdates(anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `ensureSwapUpdatesRunning should do nothing when swaps are unsupported`() = test {
        whenever(boltzService.isSwapSupported).thenReturn(false)

        sut.ensureSwapUpdatesRunning()
        advanceUntilIdle()

        verify(boltzService, never()).startUpdates(anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `ensureSwapUpdatesRunning should do nothing when swaps are disabled in dev settings`() = test {
        whenever(boltzService.isSwapSupported).thenReturn(true)
        whenever(boltzService.isSwapEnabled()).thenReturn(false)

        sut.ensureSwapUpdatesRunning()
        advanceUntilIdle()

        verify(boltzService, never()).startUpdates(anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `setInitNodeLifecycleState should call lightningRepo`() = test {
        sut.setInitNodeLifecycleState()
        verify(lightningRepo).setInitNodeLifecycleState()
    }

    @Test
    fun `refreshState should sync wallet`() = test {
        sut.refreshState()

        verify(walletRepo).syncNodeAndWallet()
    }

    @Test
    fun `refreshReceiveState should refresh receive state`() = test {
        sut.refreshReceiveState()

        verify(blocktankRepo).refreshInfo()
        verify(lightningRepo).updateGeoBlockState()
        verify(walletRepo).refreshBip21()
    }

    @Test
    fun `onPullToRefresh should sync wallet`() = test {
        sut.onPullToRefresh()

        verify(walletRepo).syncNodeAndWallet(SyncSource.MANUAL)
    }

    @Test
    fun `disconnectPeer should call lightningRepo disconnectPeer`() = test {
        val testPeer = PeerDetails.of("nodeId", "host", "9735")
        val testError = Exception("Test error")
        whenever(lightningRepo.disconnectPeer(testPeer)).thenReturn(Result.failure(testError))

        sut.disconnectPeer(testPeer)

        verify(lightningRepo).disconnectPeer(testPeer)
    }

    @Test
    fun `wipeWallet should call walletRepo wipeWallet`() = test {
        whenever(walletRepo.wipeWallet(walletIndex = 0)).thenReturn(Result.success(Unit))
        sut.wipeWallet()

        verify(walletRepo).wipeWallet(walletIndex = 0)
    }

    @Test
    fun `createWallet should call walletRepo createWallet`() = test {
        whenever(walletRepo.createWallet(anyOrNull())).thenReturn(Result.success(Unit))

        sut.createWallet(null)

        verify(walletRepo).createWallet(anyOrNull())
    }

    @Test
    fun `createWallet should call setInitNodeLifecycleState`() = test {
        whenever(walletRepo.createWallet(anyOrNull())).thenReturn(Result.success(Unit))

        sut.createWallet(null)

        verify(lightningRepo).setInitNodeLifecycleState()
    }

    @Test
    fun `restoreWallet should call walletRepo restoreWallet`() = test {
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))

        sut.restoreWallet("test_mnemonic", null)

        verify(walletRepo).restoreWallet(any(), anyOrNull())
    }

    @Test
    fun `restoreWallet should call setInitNodeLifecycleState`() = test {
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))

        sut.restoreWallet("test_mnemonic", null)

        verify(lightningRepo).setInitNodeLifecycleState()
    }

    @Test
    fun `addTagToSelected should call walletRepo addTagToSelected`() = test {
        sut.addTagToSelected("test_tag")

        verify(walletRepo).addTagToSelected("test_tag")
    }

    @Test
    fun `removeTag should call walletRepo removeTag`() = test {
        sut.removeTag("test_tag")

        verify(walletRepo).removeTag("test_tag")
    }

    @Test
    fun `updateBip21Description should call walletRepo updateBip21Description`() = test {
        sut.updateBip21Description("test_description")

        verify(walletRepo).setBip21Description("test_description")
    }

    @Test
    fun `backup restore should not be triggered when wallet exists while not restoring`() = test {
        assertEquals(RestoreState.Initial, sut.restoreState.value)

        walletState.value = walletState.value.copy(walletExists = true)

        verify(backupRepo, never()).performFullRestoreFromLatestBackup()
    }

    @Test
    fun `onBackupRestoreSuccess should reset restoreState`() = test {
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        walletState.value = walletState.value.copy(walletExists = true)
        sut.restoreWallet("mnemonic", "passphrase")
        assertEquals(RestoreState.InProgress.Wallet, sut.restoreState.value)

        sut.onRestoreContinue()

        assertEquals(RestoreState.Settled, sut.restoreState.value)
    }

    @Test
    fun `onProceedWithoutRestore should exit restore flow`() = test {
        val testError = Exception("Test error")
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.failure(testError))
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        assertEquals(RestoreState.Completed, sut.restoreState.value)

        sut.onProceedWithoutRestore(onDone = {})
        advanceUntilIdle()
        assertEquals(RestoreState.Settled, sut.restoreState.value)
    }

    @Test
    fun `restore state should transition as expected`() = test {
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        assertEquals(RestoreState.Initial, sut.restoreState.value)

        sut.restoreWallet("mnemonic", "passphrase")
        assertEquals(RestoreState.InProgress.Wallet, sut.restoreState.value)

        walletState.value = walletState.value.copy(walletExists = true)
        assertEquals(RestoreState.Completed, sut.restoreState.value)

        sut.onRestoreContinue()
        assertEquals(RestoreState.Settled, sut.restoreState.value)
    }

    @Test
    fun `backup restore should reinitialize pubky state after metadata restore`() = test {
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))

        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()

        verifyBlocking(pubkyRepo) { initialize() }
    }

    @Test
    fun `start should call refreshBip21 when restore state is idle`() = test {
        // Create fresh mocks for this test
        val testWalletRepo: WalletRepo = mock()
        val testLightningRepo: LightningRepo = mock()

        // Create a wallet state with walletExists = true
        val testWalletState = MutableStateFlow(WalletState(walletExists = true))

        // Set up mocks BEFORE creating SUT
        whenever(testWalletRepo.walletState).thenReturn(testWalletState)
        whenever(testWalletRepo.balanceState).thenReturn(balanceState)
        whenever(testWalletRepo.walletExists()).thenReturn(true)
        whenever(testLightningRepo.lightningState).thenReturn(lightningState)
        whenever(testLightningRepo.isRecoveryMode).thenReturn(isRecoveryMode)
        whenever(
            testLightningRepo.start(
                any(),
                anyOrNull(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).thenReturn(Result.success(Unit))

        val testSut = WalletViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            walletRepo = testWalletRepo,
            lightningRepo = testLightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
            pubkyRepo = pubkyRepo,
            migrationService = migrationService,
            connectivityRepo = connectivityRepo,
            boltzService = boltzService,
        )

        assertEquals(RestoreState.Initial, testSut.restoreState.value)
        assertEquals(true, testSut.walletExists)

        testSut.start()
        advanceUntilIdle()

        verify(testLightningRepo).start(
            any(),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
        )
        verify(testWalletRepo).refreshBip21()
    }

    // Regression: a start that short-circuits on the isStarting guard never reaches
    // LightningRepo.start, so it must cancel a deferred stop itself or the node stops while foregrounded
    @Test
    fun `foreground start cancels deferred stop while startup is active`() = test {
        val testWalletRepo: WalletRepo = mock()
        val testLightningRepo: LightningRepo = mock()
        val testWalletState = MutableStateFlow(WalletState(walletExists = true))

        whenever(testWalletRepo.walletState).thenReturn(testWalletState)
        whenever(testWalletRepo.balanceState).thenReturn(balanceState)
        whenever(testWalletRepo.walletExists()).thenReturn(true)
        whenever(testLightningRepo.lightningState).thenReturn(lightningState)
        whenever(testLightningRepo.isRecoveryMode).thenReturn(isRecoveryMode)

        val startEntered = CompletableDeferred<Unit>()
        val finishStart = CompletableDeferred<Unit>()
        whenever(
            testLightningRepo.start(
                any(),
                anyOrNull(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).doSuspendableAnswer {
            startEntered.complete(Unit)
            finishStart.await()
            Result.success(Unit)
        }

        val testSut = WalletViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            walletRepo = testWalletRepo,
            lightningRepo = testLightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
            pubkyRepo = pubkyRepo,
            migrationService = migrationService,
            connectivityRepo = connectivityRepo,
            boltzService = boltzService,
        )

        testSut.start()
        startEntered.await()
        testSut.stop()
        testSut.start()

        verify(testLightningRepo).stopDebounced()
        verify(testLightningRepo, times(2)).cancelPendingStop()

        finishStart.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `start should skip refreshBip21 when restore is in progress`() = test {
        // Create fresh mocks for this test
        val testWalletRepo: WalletRepo = mock()
        val testLightningRepo: LightningRepo = mock()

        // Create wallet state with walletExists = true so start() doesn't return early
        val testWalletState = MutableStateFlow(WalletState(walletExists = true))

        // Set up mocks BEFORE creating SUT
        whenever(testWalletRepo.walletState).thenReturn(testWalletState)
        whenever(testWalletRepo.balanceState).thenReturn(balanceState)
        whenever(testWalletRepo.walletExists()).thenReturn(true)
        whenever(testWalletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))
        whenever(testLightningRepo.lightningState).thenReturn(lightningState)
        whenever(testLightningRepo.isRecoveryMode).thenReturn(isRecoveryMode)
        whenever(
            testLightningRepo.start(
                any(),
                anyOrNull(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).thenReturn(Result.success(Unit))

        val testSut = WalletViewModel(
            context = context,
            bgDispatcher = testDispatcher,
            walletRepo = testWalletRepo,
            lightningRepo = testLightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
            pubkyRepo = pubkyRepo,
            migrationService = migrationService,
            connectivityRepo = connectivityRepo,
            boltzService = boltzService,
        )

        // Trigger restore to put state in non-idle
        testSut.restoreWallet("mnemonic", null)
        assertEquals(RestoreState.InProgress.Wallet, testSut.restoreState.value)

        testSut.start()
        advanceUntilIdle()

        verify(testWalletRepo, never()).refreshBip21()
    }
}
