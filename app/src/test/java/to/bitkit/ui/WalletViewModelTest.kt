package to.bitkit.ui

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDataMigration
import org.lightningdevkit.ldknode.PeerDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.hasPendingWalletRestore
import to.bitkit.ext.of
import to.bitkit.models.BalanceState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.NodeStartYieldedToStopError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.services.BoltzService
import to.bitkit.services.MigrationService
import to.bitkit.services.PendingChannelMigration
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.WalletViewModel
import to.bitkit.viewmodels.canProceedWithoutRestore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest : BaseUnitTest() {
    private lateinit var sut: WalletViewModel

    private val context = mock<Context>()
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val recoveryKeychain = mock<Keychain>()
    private lateinit var connectionRecovery: WalletConnectionRecovery
    private var currentBinding = "test-wallet-binding"
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
        whenever(walletRepo.refreshReceiveState()).thenReturn(Result.success(Unit))
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(lightningRepo.stop()).thenReturn(Result.success(Unit))
        whenever(migrationService.isMigrationChecked()).thenReturn(true)
        whenever(migrationService.isChannelRecoveryChecked()).thenReturn(true)
        whenever(migrationService.isRestoringFromRNRemoteBackup).thenReturn(MutableStateFlow(false))
        whenever(migrationService.isShowingMigrationLoading).thenReturn(MutableStateFlow(false))
        whenever(migrationService.tryFetchMigrationPeersFromBackup()).thenReturn(emptyList())
        whenever { migrationService.getRNRemoteBackupTimestamp() }.thenReturn(null)
        whenever { backupRepo.hasPendingWalletRestore() }.thenReturn(false)
        whenever(connectivityRepo.isOnline).thenReturn(isOnline)
        whenever(boltzService.events).thenReturn(MutableSharedFlow())
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever { lightningRepo.getFeeRateForSpeed(any(), anyOrNull()) }.thenReturn(Result.success(1uL))

        connectionRecovery = connectionRecoveryFixture(settingsStore, recoveryKeychain) { currentBinding }
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
            connectionRecovery = connectionRecovery,
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
        verify(walletRepo).refreshReceiveState()
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
    fun `restoreWallet should arm the received sheet hold before the node syncs`() = test {
        // The node starts replaying historical txs as soon as the restore begins, so the hold has to
        // be armed here rather than when the user taps continue.
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))
        val settingsData = stubSettingsUpdate()

        sut.restoreWallet("test_mnemonic", null)
        advanceUntilIdle()

        assertTrue(settingsData.value.pendingRestoreActivitySeen)
        assertTrue(settingsData.value.pendingRestoreActivitySeenSince > 0)
    }

    @Test
    fun `restoreWallet should release the received sheet hold when the restore fails`() = test {
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.failure(AppError("restore failed")))
        val settingsData = stubSettingsUpdate()

        sut.restoreWallet("test_mnemonic", null)
        advanceUntilIdle()

        assertFalse(settingsData.value.pendingRestoreActivitySeen)
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

        verify(backupRepo, never()).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `onBackupRestoreSuccess should reset restoreState`() = test {
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        walletState.value = walletState.value.copy(walletExists = true)
        sut.restoreWallet("mnemonic", "passphrase")
        assertEquals(RestoreState.InProgress.Wallet, sut.restoreState.value)

        sut.onRestoreContinue()

        assertEquals(RestoreState.Settled, sut.restoreState.value)
    }

    @Test
    fun `onRestoreContinue should request address type pruning`() = test {
        val settingsData = stubSettingsUpdate()

        sut.onRestoreContinue()
        advanceUntilIdle()

        assertTrue(settingsData.value.pendingRestoreAddressTypePrune)
    }

    @Test
    fun `onRestoreContinue should skip address type pruning when backup had monitored types`() = test {
        whenever(settingsStore.restoredMonitoredTypesFromBackup).thenReturn(true)
        val settingsData = stubSettingsUpdate()

        sut.onRestoreContinue()
        advanceUntilIdle()

        assertFalse(settingsData.value.pendingRestoreAddressTypePrune)
    }

    @Test
    fun `onRestoreContinue should not arm the received sheet hold, the restore already did`() = test {
        // Regression: arming it here left the node replaying historical txs before the hold existed.
        val settingsData = stubSettingsUpdate()

        sut.onRestoreContinue()
        advanceUntilIdle()

        assertFalse(settingsData.value.pendingRestoreActivitySeen)
    }

    @Test
    fun `failed backup restore can retry without restarting node`() = test {
        val testError = AppError("restore failed")
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).thenReturn(
            Result.failure(testError),
            Result.success(emptySet()),
        )
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1, hasPendingWalletRestore = false), sut.restoreState.value)

        sut.onBackupRestoreRetry()
        advanceUntilIdle()

        assertEquals(RestoreState.Completed(), sut.restoreState.value)
        verify(backupRepo, times(2)).performFullRestoreFromLatestBackup(any(), anyOrNull())
        verify(lightningRepo, never()).restartNode()
    }

    @Test
    fun `replacement cancels editor opening without leaving it busy`() = test {
        val reading = CompletableDeferred<Unit>()
        whenever(settingsStore.data).thenReturn(
            flow {
                reading.complete(Unit)
                awaitCancellation()
            }
        )
        sut.openConnectionRecovery()
        reading.await()
        connectionRecovery.withIdentityChange { }
        advanceUntilIdle()
        val cancelled = checkNotNull(sut.connectionEditor.value)
        assertFalse(cancelled.busy)
        assertEquals(null, cancelled.owner)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        sut.retryRecoveryConnection()
        advanceUntilIdle()
        assertFalse(checkNotNull(sut.connectionEditor.value).busy)
        assertTrue(sut.connectionEditor.value?.owner != null)
    }

    @Test
    fun `replacement cancels editor save without retaining its old owner`() = test {
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.editRecoveryElectrum("ssl://electrum.example.com:50002")
        val validating = CompletableDeferred<Unit>()
        whenever(lightningRepo.validateElectrumServer(any())).doSuspendableAnswer {
            validating.complete(Unit)
            awaitCancellation()
        }
        sut.saveRecoveryConnection(electrum = true)
        validating.await()
        connectionRecovery.withIdentityChange { }
        advanceUntilIdle()
        val cancelled = checkNotNull(sut.connectionEditor.value)
        assertFalse(cancelled.busy)
        assertEquals(null, cancelled.owner)
        assertFalse(cancelled.saved)
        sut.retryRecoveryConnection()
        advanceUntilIdle()
        assertTrue(sut.connectionEditor.value?.owner != null)
        verify(lightningRepo, never()).restartNode(anyOrNull(), any())
    }

    @Test
    fun `cancelled save cleanup cannot overwrite a newly opened editor`() = test {
        sut.openConnectionRecovery()
        advanceUntilIdle()
        val original = checkNotNull(sut.connectionEditor.value)
        sut.editRecoveryElectrum("ssl://electrum.example.com:50002")
        val validating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(lightningRepo.validateElectrumServer(any())).doSuspendableAnswer {
            validating.complete(Unit)
            withContext(NonCancellable) { release.await() }
            Result.success(Unit)
        }
        sut.saveRecoveryConnection(electrum = true)
        validating.await()
        sut.closeConnectionRecovery()
        sut.openConnectionRecovery()
        runCurrent()
        val replacement = checkNotNull(sut.connectionEditor.value)
        assertTrue(replacement.id != original.id)
        assertTrue(replacement.busy)
        release.complete(Unit)
        advanceUntilIdle()
        val completed = checkNotNull(sut.connectionEditor.value)
        assertEquals(replacement.id, completed.id)
        assertFalse(completed.busy)
        assertFalse(completed.saved)
        assertTrue(completed.owner != null)
        assertEquals(null, completed.error)
    }

    @Test
    fun `ordinary startup failure can proceed only after affirmative marker clearance`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        repeat(2) {
            sut.onRestoreRetry()
            advanceUntilIdle()
        }
        val failure = checkNotNull(sut.connectionStartupError.value)
        assertEquals(2, sut.restoreState.value.retryCount())
        assertTrue(canProceedWithoutRestore(sut.restoreState.value, failure.canProceedWithoutRestore))
        var dismissed = 0
        sut.onProceedWithoutRestore { dismissed++ }
        advanceUntilIdle()
        assertEquals(1, dismissed)
        assertEquals(null, sut.connectionStartupError.value)
        assertEquals(RestoreState.Settled, sut.restoreState.value)
    }

    @Test
    fun `pending or unreadable markers never grant ordinary startup clearance`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        for (unreadable in listOf(false, true)) {
            whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer {
                if (unreadable) throw AppError("keychain unavailable") else true
            }
            sut.onRestoreRetry()
            advanceUntilIdle()
            assertFalse(checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
            var dismissed = false
            sut.onProceedWithoutRestore { dismissed = true }
            advanceUntilIdle()
            assertFalse(dismissed)
        }
        verify(lightningRepo, never()).stop()
    }

    @Test
    fun `failed node stop cannot acknowledge proceeding without restore`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        sut.onRestoreRetry()
        advanceUntilIdle()
        val failure = sut.connectionStartupError.value
        lightningState.value = lightningState.value.copy(
            nodeLifecycleState = NodeLifecycleState.ErrorStarting(AppError("storage"))
        )
        whenever(lightningRepo.stop()).thenReturn(Result.failure(AppError("stop persistence failed")))
        var dismissed = false
        sut.onProceedWithoutRestore { dismissed = true }
        advanceUntilIdle()
        assertFalse(dismissed)
        assertEquals(failure, sut.connectionStartupError.value)
    }

    @Test
    fun `marker appearing during proceed delay retains failure and prevents dismissal`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        sut.onRestoreRetry()
        advanceUntilIdle()
        val failure = sut.connectionStartupError.value
        lightningState.value = lightningState.value.copy(
            nodeLifecycleState = NodeLifecycleState.ErrorStarting(AppError("storage"))
        )
        var pending = false
        whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer { pending }
        var dismissed = false
        sut.onProceedWithoutRestore { dismissed = true }
        runCurrent()
        pending = true
        advanceUntilIdle()
        assertFalse(dismissed)
        assertEquals(failure?.message, sut.connectionStartupError.value?.message)
        assertFalse(checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
        verify(lightningRepo).stop()
    }

    @Test
    fun `replacement during failure marker read cannot publish old clearance`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        var reads = 0
        val reading = CompletableDeferred<Unit>()
        whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer {
            reads++
            if (reads > 1) {
                reading.complete(Unit)
                awaitCancellation()
            }
            false
        }
        ToastEventBus.events.test {
            sut.onRestoreRetry()
            reading.await()
            connectionRecovery.withIdentityChange { }
            advanceUntilIdle()
            assertEquals(null, sut.connectionStartupError.value)
            assertFalse(sut.connectionRetryBusy.value)
            expectNoEvents()
        }
    }

    @Test
    fun `replacement drains proceed stop without old dismissal`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        sut.onRestoreRetry()
        advanceUntilIdle()
        val failure = sut.connectionStartupError.value
        lightningState.value = lightningState.value.copy(
            nodeLifecycleState = NodeLifecycleState.ErrorStarting(AppError("storage"))
        )
        val stopping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(lightningRepo.stop()).doSuspendableAnswer {
            stopping.complete(Unit)
            withContext(NonCancellable) { release.await() }
            Result.success(Unit)
        }
        var dismissed = false
        var replaced = false
        sut.onProceedWithoutRestore { dismissed = true }
        stopping.await()
        val replacement = launch { connectionRecovery.withIdentityChange { replaced = true } }
        runCurrent()
        assertFalse(replaced)
        release.complete(Unit)
        replacement.join()
        advanceUntilIdle()
        assertTrue(replaced)
        assertFalse(dismissed)
        assertEquals(failure, sut.connectionStartupError.value)
        sut.onProceedWithoutRestore { dismissed = true }
        advanceUntilIdle()
        assertFalse(dismissed)
    }

    @Test
    fun `editor retry reports one toast and inline error without stale underlying clearance`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        sut.openConnectionRecovery()
        advanceUntilIdle()
        ToastEventBus.events.test {
            sut.retryRecoveryConnection()
            advanceUntilIdle()
            assertEquals("storage unavailable", awaitItem().description)
            expectNoEvents()
            assertEquals("storage unavailable", sut.connectionEditor.value?.error)
            assertFalse(checkNotNull(sut.connectionEditor.value).busy)
            assertTrue(checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
            sut.closeConnectionRecovery()
            assertTrue(checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
        }
    }

    @Test
    fun `editor control and cancellation release busy state without toast or clearance`() = test {
        ToastEventBus.events.test {
            for (error in listOf(NodeStartYieldedToStopError(null), CancellationException("cancelled"))) {
                sut.openConnectionRecovery()
                advanceUntilIdle()
                whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.failure(error))
                sut.retryRecoveryConnection()
                advanceUntilIdle()
                assertFalse(checkNotNull(sut.connectionEditor.value).busy)
                assertFalse(sut.connectionRetryBusy.value)
                assertEquals(null, sut.connectionStartupError.value)
                expectNoEvents()
                sut.closeConnectionRecovery()
            }
        }
    }

    @Test
    fun `changed credential owner invalidates editor and permits explicit reopening`() = test {
        sut.openConnectionRecovery()
        advanceUntilIdle()
        val originalOwner = checkNotNull(sut.connectionEditor.value?.owner)
        currentBinding = "replacement-wallet-binding"
        ToastEventBus.events.test {
            sut.retryRecoveryConnection()
            advanceUntilIdle()
            val invalidated = checkNotNull(sut.connectionEditor.value)
            assertFalse(invalidated.busy)
            assertEquals(null, invalidated.owner)
            assertTrue(invalidated.error.orEmpty().contains("wallet changed"))
            assertEquals(null, sut.connectionStartupError.value)
            expectNoEvents()
        }
        sut.retryRecoveryConnection()
        advanceUntilIdle()
        val reopened = checkNotNull(sut.connectionEditor.value)
        assertFalse(reopened.busy)
        assertTrue(reopened.owner != null && reopened.owner !== originalOwner)
        verify(lightningRepo, never()).restartNode(anyOrNull(), any())
    }

    @Test
    fun `each real keychain restore marker separately denies startup clearance`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        val markers = mutableSetOf<String>()
        whenever(recoveryKeychain.exists(any())).thenAnswer { markers.contains(it.getArgument<String>(0)) }
        whenever(
            backupRepo.hasPendingWalletRestore()
        ).doSuspendableAnswer { recoveryKeychain.hasPendingWalletRestore() }
        for (pending in listOf(
            setOf(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name),
            setOf(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name),
            setOf(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name, Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name),
            emptySet(),
        )) {
            markers.clear()
            markers.addAll(pending)
            sut.onRestoreRetry()
            advanceUntilIdle()
            assertEquals(pending.isEmpty(), checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
        }
    }

    @Test
    fun `unavailable final marker read revokes proceed without dismissing`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("storage unavailable")))
        sut.onRestoreRetry()
        advanceUntilIdle()
        lightningState.value = lightningState.value.copy(
            nodeLifecycleState = NodeLifecycleState.ErrorStarting(AppError("storage"))
        )
        var reads = 0
        whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer {
            if (++reads > 1) throw AppError("keychain unavailable")
            false
        }
        var dismissed = false
        sut.onProceedWithoutRestore { dismissed = true }
        advanceUntilIdle()
        assertFalse(dismissed)
        assertFalse(checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
    }

    @Test
    fun `admitted editor cancellation permits explicit fresh owner capture`() = test {
        val starting = CompletableDeferred<Unit>()
        whenever(lightningRepo.restartNode(anyOrNull(), any())).doSuspendableAnswer {
            starting.complete(Unit)
            awaitCancellation()
        }
        sut.openConnectionRecovery()
        advanceUntilIdle()
        val originalOwner = sut.connectionEditor.value?.owner
        sut.retryRecoveryConnection()
        starting.await()
        connectionRecovery.withIdentityChange { }
        advanceUntilIdle()
        val cancelled = checkNotNull(sut.connectionEditor.value)
        assertFalse(cancelled.busy)
        assertEquals(null, cancelled.owner)
        sut.retryRecoveryConnection()
        advanceUntilIdle()
        assertTrue(sut.connectionEditor.value?.owner != null && sut.connectionEditor.value?.owner !== originalOwner)
        verify(lightningRepo, times(1)).restartNode(anyOrNull(), any())
    }

    @Test
    fun `newer retry prevents held older failure from publishing`() = test {
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        )
            .thenReturn(Result.failure(AppError("older failure")))
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer {
            if (first) {
                first = false
                reading.complete(Unit)
                release.await()
            }
            false
        }
        ToastEventBus.events.test {
            sut.start()
            reading.await()
            sut.onRestoreRetry()
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(null, sut.connectionStartupError.value)
            expectNoEvents()
        }
    }

    @Test
    fun `control failure retry has no ordinary toast or clearance`() = test {
        ToastEventBus.events.test {
            for (error in listOf(NodeStartYieldedToStopError(null), CancellationException("cancelled"))) {
                whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.failure(error))
                sut.onRestoreRetry()
                advanceUntilIdle()
                assertEquals(null, sut.connectionStartupError.value)
                assertFalse(sut.connectionRetryBusy.value)
                expectNoEvents()
            }
        }
    }

    @Test
    fun `unknown restore state is not affirmative proceed permission`() {
        assertFalse(canProceedWithoutRestore(RestoreState.Initial, null))
        assertFalse(canProceedWithoutRestore(RestoreState.Retry(2), null))
        assertFalse(canProceedWithoutRestore(RestoreState.BackupFailed(2), null))
        assertTrue(canProceedWithoutRestore(RestoreState.BackupFailed(2, false), null))
        assertFalse(canProceedWithoutRestore(RestoreState.BackupFailed(2, false), false))
        assertTrue(canProceedWithoutRestore(RestoreState.Retry(2), true))
    }

    @Test
    fun `ordinary node startup failure emits one error toast`() = test {
        whenever(walletRepo.walletExists()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()),
        ).thenReturn(Result.failure(AppError("node storage unavailable")))

        ToastEventBus.events.test {
            sut.start()
            advanceUntilIdle()
            assertEquals("node storage unavailable", awaitItem().description)
            expectNoEvents()
        }
        assertFalse(shouldPresentWalletRecovery(false, sut.restoreState.value, sut.connectionStartupError.value))
    }

    @Test
    fun `foreground failure remains restricted when required recovery is pending or unreadable`() = test {
        whenever(walletRepo.walletExists()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()),
        ).thenReturn(Result.failure(AppError("node unavailable")))
        for (unreadable in listOf(false, true)) {
            whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer {
                if (unreadable) throw AppError("keychain unavailable") else true
            }
            sut.start()
            advanceUntilIdle()
            val failure = checkNotNull(sut.connectionStartupError.value)
            assertFalse(failure.canProceedWithoutRestore)
            assertTrue(shouldPresentWalletRecovery(false, sut.restoreState.value, failure))
        }
    }

    @Test
    fun `foreground failure with a saved correction retains the recovery editor route`() = test {
        stubRecoveryRecord()
        savePendingCorrection()
        sut.closeConnectionRecovery()
        whenever(walletRepo.walletExists()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()),
        ).thenReturn(Result.failure(AppError("node unavailable")))

        whenever(lightningRepo.restartNode(anyOrNull(), any()))
            .thenReturn(Result.failure(AppError("node unavailable")))

        sut.start()
        advanceUntilIdle()

        val failure = checkNotNull(sut.connectionStartupError.value)
        assertTrue(failure.canProceedWithoutRestore)
        assertTrue(shouldPresentWalletRecovery(false, sut.restoreState.value, failure))
        assertTrue(recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0) != null)
    }

    @Test
    fun `unreadable correction during foreground startup keeps recovery reachable`() = test {
        whenever(walletRepo.walletExists()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        whenever(recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0))
            .thenAnswer { throw AppError("correction unavailable") }

        sut.start()
        advanceUntilIdle()

        assertTrue(shouldPresentWalletRecovery(false, sut.restoreState.value, sut.connectionStartupError.value))
        verify(lightningRepo, never())
            .start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `ordinary node retry failure emits one error toast per attempt`() = test {
        whenever(
            lightningRepo.restartNode(anyOrNull(), any())
        ).thenReturn(Result.failure(AppError("node storage unavailable")))

        ToastEventBus.events.test {
            repeat(2) {
                sut.onRestoreRetry()
                advanceUntilIdle()
                assertEquals("node storage unavailable", awaitItem().description)
                expectNoEvents()
            }
        }
    }

    @Test
    fun `node restore retry remains separate from backup recovery`() = test {
        sut.onRestoreRetry()
        advanceUntilIdle()

        assertEquals(RestoreState.Retry(1), sut.restoreState.value)
        verify(lightningRepo).restartNode()
        verify(backupRepo, never()).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `node retry preserves pending backup recovery and increments retry count`() = test {
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("backup still unavailable")))
        walletState.value = walletState.value.copy(walletExists = true)
        lightningState.value = lightningState.value.copy(
            nodeLifecycleState = NodeLifecycleState.ErrorStarting(AppError("start failed")),
        )
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1), sut.restoreState.value)

        sut.onRestoreRetry()
        advanceUntilIdle()

        assertEquals(RestoreState.BackupFailed(2), sut.restoreState.value)
        verify(lightningRepo).restartNode()
        verify(backupRepo).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `onProceedWithoutRestore should exit failed backup restore without stopping running node`() = test {
        val testError = AppError("restore failed")
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.failure(testError))
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1, hasPendingWalletRestore = false), sut.restoreState.value)

        sut.onProceedWithoutRestore(onDone = {})
        advanceUntilIdle()

        assertEquals(RestoreState.Settled, sut.restoreState.value)
        verify(lightningRepo, never()).stop()
    }

    @Test
    fun `proceed cannot dismiss pending wallet recovery`() = test {
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        advanceUntilIdle()
        val pendingState = sut.restoreState.value
        var dismissed = false

        repeat(3) {
            sut.onProceedWithoutRestore { dismissed = true }
            advanceUntilIdle()
        }

        assertEquals(pendingState, sut.restoreState.value)
        assertFalse(dismissed)
        verify(lightningRepo, never()).stop()
    }

    @Test
    fun `post restore profile failure allows proceeding only while safety markers stay clear`() = test {
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        whenever(pubkyRepo.initialize()).doSuspendableAnswer { throw AppError("profile cache unavailable") }
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1, hasPendingWalletRestore = false), sut.restoreState.value)
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(2, hasPendingWalletRestore = false), sut.restoreState.value)
        assertEquals(null, sut.connectionStartupError.value)

        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        var dismissed = false
        sut.onProceedWithoutRestore { dismissed = true }
        advanceUntilIdle()
        assertFalse(dismissed)
        assertTrue(sut.restoreState.value is RestoreState.BackupFailed)

        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(false)
        sut.onProceedWithoutRestore { dismissed = true }
        advanceUntilIdle()
        assertTrue(dismissed)
        assertEquals(RestoreState.Settled, sut.restoreState.value)
        verify(backupRepo, times(2)).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `failed safety marker inspection keeps restore failure closed`() = test {
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.failure(AppError("offline")))
        whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer { throw AppError("keychain unavailable") }
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1), sut.restoreState.value)
        var dismissed = false
        sut.onProceedWithoutRestore { dismissed = true }
        advanceUntilIdle()
        assertFalse(dismissed)
        assertEquals(RestoreState.BackupFailed(1), sut.restoreState.value)
    }

    @Test
    fun `overlapping backup retries perform one restore`() = test {
        val finishRestore = CompletableDeferred<Unit>()
        var calls = 0
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).doSuspendableAnswer {
            calls++
            finishRestore.await()
            Result.success(emptySet())
        }
        sut.onBackupRestoreRetry()
        sut.onBackupRestoreRetry()
        runCurrent()
        try {
            assertEquals(1, calls)
            assertEquals(RestoreState.InProgress.Metadata, sut.restoreState.value)
        } finally {
            finishRestore.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(RestoreState.Completed(), sut.restoreState.value)
    }

    @Test
    fun `pending wallet restore is offered after wallet state reloads`() = test {
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)

        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()

        assertEquals(RestoreState.BackupFailed(1), sut.restoreState.value)
        verify(backupRepo, never()).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `newer RN restore still restores USDT recovery and never falls back after a USDT failure`() = test {
        whenever(migrationService.getRNRemoteBackupTimestamp()).thenReturn(2uL)
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(
            backupRepo.restoreUsdtRecoveryAfterLegacyMigration(anyOrNull()),
        ).thenReturn(Result.failure(AppError("VSS offline")))
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1, hasPendingWalletRestore = false), sut.restoreState.value)
        verify(migrationService).restoreFromRNRemoteBackup()
        verify(backupRepo).restoreUsdtRecoveryAfterLegacyMigration(anyOrNull())
        verify(backupRepo, never()).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `pending wallet restore retry bypasses newer RN backup`() = test {
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        whenever { migrationService.getRNRemoteBackupTimestamp() }.thenReturn(2uL)
        whenever(backupRepo.getLatestBackupTime()).thenReturn(null)
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()

        sut.onBackupRestoreRetry()
        advanceUntilIdle()

        assertEquals(RestoreState.Completed(), sut.restoreState.value)
        verify(backupRepo).performFullRestoreFromLatestBackup(any(), anyOrNull())
        verify(migrationService, never()).restoreFromRNRemoteBackup()
    }

    @Test
    fun `cancelled wallet restore retry clears transient backup gate`() = test {
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull()))
            .thenReturn(Result.failure(CancellationException("cancelled")))
        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()

        val job = sut.onBackupRestoreRetry()
        job.join()

        assertTrue(job.isCancelled)
        verify(backupRepo).setRestorePending(true)
        verify(backupRepo).setRestorePending(false)
    }

    @Test
    fun `restore state should transition as expected`() = test {
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        assertEquals(RestoreState.Initial, sut.restoreState.value)

        sut.restoreWallet("mnemonic", "passphrase")
        assertEquals(RestoreState.InProgress.Wallet, sut.restoreState.value)

        walletState.value = walletState.value.copy(walletExists = true)
        assertEquals(RestoreState.Completed(), sut.restoreState.value)

        sut.onRestoreContinue()
        assertEquals(RestoreState.Settled, sut.restoreState.value)
    }

    @Test
    fun `restore should hold ordinary backups from its start until it completes`() = test {
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))

        sut.restoreWallet("mnemonic", "passphrase")

        // The node starts and syncs from here, so the gate must already be closed.
        verify(backupRepo).setRestorePending(true)
        verify(backupRepo, never()).setRestorePending(false)

        walletState.value = walletState.value.copy(walletExists = true)
        advanceUntilIdle()

        assertEquals(RestoreState.Completed(), sut.restoreState.value)
        inOrder(backupRepo) {
            verify(backupRepo).setRestorePending(true)
            verify(backupRepo).setRestorePending(false)
        }
    }

    @Test
    fun `restore should release ordinary backups when the wallet is never created`() = test {
        whenever(walletRepo.restoreWallet(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("restore failed")))

        sut.restoreWallet("mnemonic", "passphrase")
        advanceUntilIdle()

        // Nothing reaches the restore itself, so the gate would otherwise hold until it expires.
        verify(backupRepo).setRestorePending(false)
    }

    @Test
    fun `backup restore should reinitialize pubky state after metadata restore`() = test {
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))

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
            connectionRecovery = connectionRecovery,
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
            connectionRecovery = connectionRecovery,
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
            connectionRecovery = connectionRecovery,
        )

        // Trigger restore to put state in non-idle
        testSut.restoreWallet("mnemonic", null)
        assertEquals(RestoreState.InProgress.Wallet, testSut.restoreState.value)

        testSut.start()
        advanceUntilIdle()

        verify(testWalletRepo, never()).refreshBip21()
    }

    @Test
    fun `start waits out an rn remote restore instead of opening the node`() = test {
        val testWalletRepo: WalletRepo = mock()
        val testLightningRepo: LightningRepo = mock()
        val testWalletState = MutableStateFlow(WalletState(walletExists = true))

        whenever(migrationService.isRestoringFromRNRemoteBackup).thenReturn(MutableStateFlow(true))
        whenever(testWalletRepo.walletState).thenReturn(testWalletState)
        whenever(testWalletRepo.balanceState).thenReturn(balanceState)
        whenever(testWalletRepo.walletExists()).thenReturn(true)
        whenever(testWalletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))
        whenever(testLightningRepo.lightningState).thenReturn(lightningState)
        whenever(testLightningRepo.isRecoveryMode).thenReturn(isRecoveryMode)
        stubSuccessfulNodeStart(testLightningRepo)

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
            connectionRecovery = connectionRecovery,
        )

        testSut.restoreWallet("mnemonic", null)
        testSut.start()
        advanceTimeBy(31.seconds)

        verifyNodeNotStarted(testLightningRepo)

        testSut.onRestoreContinue()
        advanceUntilIdle()

        verifyNodeStarted(testLightningRepo)
    }

    @Test
    fun `restore retry rebuilds the node with the pending channel file`() = test {
        val pending = PendingChannelMigration(channelManager = byteArrayOf(1), channelMonitors = listOf(byteArrayOf(2)))
        whenever(migrationService.peekPendingChannelMigration()).thenReturn(pending)
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))

        sut.onRestoreRetry()
        advanceUntilIdle()

        verifyNodeNotStarted(lightningRepo)
        val migration = argumentCaptor<ChannelDataMigration>()
        verify(lightningRepo).restartNode(migration.capture(), eq(0))
        assertEquals(listOf(1.toUByte()), migration.firstValue.channelManager)
        assertEquals(listOf(listOf(2.toUByte())), migration.firstValue.channelMonitors)
        inOrder(migrationService) {
            verify(migrationService).lockChannelMigration()
            verify(migrationService).peekPendingChannelMigration()
            verify(migrationService).consumePendingChannelMigration()
            verify(migrationService).unlockChannelMigration()
        }
    }

    @Test
    fun `restore retry keeps pending channels when restart fails`() = test {
        val pending = PendingChannelMigration(channelManager = byteArrayOf(1), channelMonitors = listOf(byteArrayOf(2)))
        whenever(migrationService.peekPendingChannelMigration()).thenReturn(pending)
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.failure(AppError("restart failed")))

        sut.onRestoreRetry()
        advanceUntilIdle()

        verifyNodeNotStarted(lightningRepo)
        verify(lightningRepo).restartNode(anyOrNull(), any())
        verify(migrationService, never()).consumePendingChannelMigration()
        verify(migrationService).unlockChannelMigration()
    }

    @Test
    fun `restore retry keeps pending channels when restart yields to background stop`() = test {
        val pending = PendingChannelMigration(channelManager = byteArrayOf(1), channelMonitors = listOf(byteArrayOf(2)))
        whenever(migrationService.peekPendingChannelMigration()).thenReturn(pending)
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(
            Result.failure(NodeStartYieldedToStopError(AppError("start failed"))),
        )

        sut.onRestoreRetry()
        advanceUntilIdle()

        verifyNodeNotStarted(lightningRepo)
        verify(lightningRepo).restartNode(anyOrNull(), any())
        verify(migrationService, never()).consumePendingChannelMigration()
        verify(migrationService).unlockChannelMigration()
    }

    @Test
    fun `rn local migration start holds the channel migration lock until consumption`() = test {
        advanceUntilIdle()
        val pending = PendingChannelMigration(channelManager = byteArrayOf(1), channelMonitors = listOf(byteArrayOf(2)))
        whenever(migrationService.isMigrationChecked()).thenReturn(false)
        whenever(migrationService.hasRNWalletData()).thenReturn(true)
        whenever(migrationService.peekPendingChannelMigration()).thenReturn(pending)
        whenever(walletRepo.walletExists()).thenReturn(true)
        stubSuccessfulNodeStart(lightningRepo)

        WalletViewModel(
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
            connectionRecovery = connectionRecovery,
        )
        advanceUntilIdle()

        verifyNodeStarted(lightningRepo)
        inOrder(migrationService) {
            verify(migrationService).lockChannelMigration()
            verify(migrationService).peekPendingChannelMigration()
            verify(migrationService).consumePendingChannelMigration()
            verify(migrationService).unlockChannelMigration()
        }
    }

    private suspend fun stubSuccessfulNodeStart(repo: LightningRepo) {
        whenever(
            repo.start(
                any(),
                anyOrNull(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(Result.success(Unit))
    }

    private suspend fun verifyNodeNotStarted(repo: LightningRepo) {
        verify(
            repo,
            never(),
        ).start(
            any(),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )
    }

    private suspend fun verifyNodeStarted(repo: LightningRepo) {
        verify(repo).start(
            any(),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )
    }

    private fun stubSettingsUpdate(): MutableStateFlow<SettingsData> {
        val settingsData = MutableStateFlow(SettingsData())
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsData.value = transform(settingsData.value)
            Unit
        }
        return settingsData
    }

    @Test
    fun `leaving recovery editor during required restore does not cancel the coordinator`() = test {
        val restoring = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).doSuspendableAnswer {
            restoring.complete(Unit)
            releaseRestore.await()
            Result.success(emptySet())
        }
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.retryRecoveryConnection()
        restoring.await()
        sut.closeConnectionRecovery()
        assertEquals(null, sut.connectionEditor.value)
        releaseRestore.complete(Unit)
        advanceUntilIdle()
        assertEquals(RestoreState.Completed(), sut.restoreState.value)
        verify(backupRepo).performFullRestoreFromLatestBackup(any(), anyOrNull())
    }

    @Test
    fun `unavailable correction on restart is a reachable fail closed restore error`() = test {
        whenever(
            recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0)
        ).thenAnswer { throw AppError("unavailable") }
        sut.openConnectionRecovery()
        advanceUntilIdle()
        assertFalse(checkNotNull(sut.connectionEditor.value).busy)
        assertEquals("unavailable", sut.connectionEditor.value?.error)
        assertEquals(null, sut.connectionEditor.value?.owner)
        verify(
            lightningRepo,
            never()
        ).start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `saving Electrum does not require broken RGS or discard its unsaved draft`() = test {
        val stored = MutableStateFlow(
            SettingsData(electrumServer = "ssl://old.example:50002", rgsServerUrl = "https://old-rgs.example")
        )
        var record: String? = null
        whenever(settingsStore.data).thenReturn(stored)
        whenever(settingsStore.update(any())).doSuspendableAnswer {
            stored.value = it.getArgument<(SettingsData) -> SettingsData>(0)(stored.value)
        }
        whenever(recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0)).thenAnswer { record }
        whenever(recoveryKeychain.upsertString(any(), any(), any())).thenAnswer {
            record = it.getArgument(1)
            Unit
        }
        whenever(lightningRepo.validateElectrumServer(any())).thenReturn(Result.success(Unit))
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.editRecoveryElectrum("ssl://chosen.example:50002")
        sut.editRecoveryRgs("https://draft-rgs.example")
        sut.saveRecoveryConnection(electrum = true)
        advanceUntilIdle()
        assertTrue(checkNotNull(sut.connectionEditor.value).saved)
        assertEquals("ssl://chosen.example:50002", stored.value.electrumServer)
        assertEquals("https://old-rgs.example", stored.value.rgsServerUrl)
        assertEquals("https://draft-rgs.example", sut.connectionEditor.value?.rgs)
        verify(lightningRepo, never()).validateRgsUrl(any())
        verify(lightningRepo, never()).restartNode(anyOrNull(), any())
    }

    @Test
    fun `connection recovery outer retry applies saved settings after editor closes`() = test {
        val stored = MutableStateFlow(SettingsData())
        var record: String? = null
        whenever(settingsStore.data).thenReturn(stored)
        whenever(settingsStore.update(any())).doSuspendableAnswer {
            stored.value = it.getArgument<(SettingsData) -> SettingsData>(0)(stored.value)
        }
        whenever(recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0)).thenAnswer { record }
        whenever(recoveryKeychain.upsertString(any(), any(), any())).thenAnswer {
            record = it.getArgument(1)
            Unit
        }
        whenever(lightningRepo.validateElectrumServer(any())).thenReturn(Result.success(Unit))
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull()))
            .thenReturn(Result.success(emptySet()))
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.editRecoveryElectrum("ssl://corrected.example:50002")
        sut.saveRecoveryConnection(electrum = true)
        advanceUntilIdle()
        sut.closeConnectionRecovery()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        verify(lightningRepo).restartNode(anyOrNull(), eq(0))
        verify(backupRepo).performFullRestoreFromLatestBackup(any(), anyOrNull())
        assertEquals(RestoreState.Completed(), sut.restoreState.value)
    }

    @Test
    fun `connection recovery cancellation preserves wallet state observation`() = test {
        val restoring = CompletableDeferred<Unit>()
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).doSuspendableAnswer {
            restoring.complete(Unit)
            awaitCancellation()
        }
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        restoring.await()
        connectionRecovery.withIdentityChange { }
        walletState.value = walletState.value.copy(walletExists = false)
        runCurrent()
        assertFalse(sut.walletExists)
    }

    @Test
    fun `connection recovery cancelled restore does not strand a newly created wallet`() = test {
        val restoring = CompletableDeferred<Unit>()
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).doSuspendableAnswer {
            restoring.complete(Unit)
            awaitCancellation()
        }
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        restoring.await()
        connectionRecovery.withIdentityChange { }
        walletState.value = walletState.value.copy(walletExists = false)
        runCurrent()
        whenever(walletRepo.createWallet(anyOrNull())).thenReturn(Result.success(Unit))
        sut.createWallet(null)
        walletState.value = walletState.value.copy(walletExists = true)
        runCurrent()
        assertEquals(RestoreState.Initial, sut.restoreState.value)
        assertTrue(shouldFinishWalletInitialization(NodeLifecycleState.Running, sut.restoreState.value, false))
    }
    private suspend fun stubRecoveryRecord(): MutableStateFlow<SettingsData> {
        val stored = MutableStateFlow(SettingsData())
        var record: String? = null
        whenever(settingsStore.data).thenReturn(stored)
        whenever(settingsStore.update(any())).doSuspendableAnswer {
            stored.value = it.getArgument<(SettingsData) -> SettingsData>(0)(stored.value)
        }
        whenever(recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0)).thenAnswer { record }
        whenever(recoveryKeychain.upsertString(any(), any(), any())).thenAnswer {
            record = it.getArgument(1)
            Unit
        }
        whenever(recoveryKeychain.delete(WalletConnectionRecovery.KEY, 0)).thenAnswer {
            record = null
            Unit
        }
        whenever(lightningRepo.validateElectrumServer(any())).thenReturn(Result.success(Unit))
        return stored
    }

    @Test
    fun `saved correction restarts a running node once while required restore stays pending`() = test {
        stubRecoveryRecord()
        whenever(recoveryKeychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)).thenReturn(true)
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("vss down")))
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()),
        ).thenReturn(Result.success(Unit))
        whenever(walletRepo.walletExists()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        advanceUntilIdle()
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.editRecoveryElectrum("ssl://corrected.example:50002")
        sut.saveRecoveryConnection(electrum = true)
        advanceUntilIdle()
        sut.closeConnectionRecovery()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        verify(lightningRepo, times(1)).restartNode(anyOrNull(), any())

        repeat(3) {
            sut.start()
            advanceUntilIdle()
            sut.onBackupRestoreRetry()
            advanceUntilIdle()
        }

        verify(lightningRepo, times(1)).restartNode(anyOrNull(), any())
    }

    @Test
    fun `wallet removal clears startup failure before the next wallet restore`() = test {
        whenever(lightningRepo.restartNode(anyOrNull(), any()))
            .thenReturn(Result.failure(AppError("storage unavailable")))
        walletState.value = walletState.value.copy(walletExists = true)
        sut.onRestoreRetry()
        advanceUntilIdle()
        assertTrue(sut.connectionStartupError.value != null)
        walletState.value = walletState.value.copy(walletExists = false)
        runCurrent()

        val releaseRestore = CompletableDeferred<Unit>()
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.success(Unit))
        whenever(backupRepo.getLatestBackupTime()).thenReturn(1uL)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).doSuspendableAnswer {
            releaseRestore.await()
            Result.success(emptySet())
        }
        sut.restoreWallet("mnemonic", null)
        walletState.value = walletState.value.copy(walletExists = true)
        runCurrent()

        assertEquals(null, sut.connectionStartupError.value)
        releaseRestore.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `backup only retry with saved correction counts one retry`() = test {
        stubRecoveryRecord()
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        whenever(pubkyRepo.initialize()).doSuspendableAnswer { throw AppError("profile cache unavailable") }
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        assertEquals(RestoreState.BackupFailed(1, hasPendingWalletRestore = false), sut.restoreState.value)
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.editRecoveryElectrum("ssl://corrected.example:50002")
        sut.saveRecoveryConnection(electrum = true)
        advanceUntilIdle()
        sut.closeConnectionRecovery()

        sut.onBackupRestoreRetry()
        advanceUntilIdle()

        assertEquals(RestoreState.BackupFailed(2, hasPendingWalletRestore = false), sut.restoreState.value)
    }

    private suspend fun TestScope.savePendingCorrection(url: String = "ssl://corrected.example:50002") {
        sut.openConnectionRecovery()
        advanceUntilIdle()
        sut.editRecoveryElectrum(url)
        sut.saveRecoveryConnection(electrum = true)
        advanceUntilIdle()
        sut.closeConnectionRecovery()
    }

    private suspend fun TestScope.preparePendingCorrection() {
        stubRecoveryRecord()
        whenever(recoveryKeychain.exists(Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name)).thenReturn(true)
        whenever(backupRepo.hasPendingWalletRestore()).thenReturn(true)
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("vss down")))
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        walletState.value = walletState.value.copy(walletExists = true)
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        advanceUntilIdle()
        savePendingCorrection()
    }

    @Test
    fun `connection retry keeps recovery visible while required download is pending`() = test {
        preparePendingCorrection()
        val finishRestore = CompletableDeferred<Unit>()
        whenever(backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())).doSuspendableAnswer {
            finishRestore.await()
            Result.success(emptySet())
        }

        sut.onRestoreRetry()
        runCurrent()
        try {
            assertEquals(NodeLifecycleState.Running, lightningState.value.nodeLifecycleState)
            assertEquals(null, sut.connectionStartupError.value)
            assertEquals(RestoreState.InProgress.Metadata, sut.restoreState.value)
            assertTrue(shouldPresentWalletRecovery(false, sut.restoreState.value, sut.connectionStartupError.value))
            assertFalse(shouldFinishWalletInitialization(NodeLifecycleState.Running, sut.restoreState.value, false))
        } finally {
            finishRestore.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(RestoreState.Completed(), sut.restoreState.value)
        assertFalse(shouldPresentWalletRecovery(false, sut.restoreState.value, sut.connectionStartupError.value))
    }

    @Test
    fun `cold retry retains recovery while initial marker observation is delayed`() = test {
        runCurrent()
        val finishObservation = CompletableDeferred<Unit>()
        val finishRestart = CompletableDeferred<Unit>()
        var markerReads = 0
        whenever(backupRepo.hasPendingWalletRestore()).doSuspendableAnswer {
            if (markerReads++ == 0) finishObservation.await()
            true
        }
        whenever(walletRepo.walletExists()).thenReturn(true)
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()),
        ).thenReturn(Result.failure(AppError("node unavailable")))
        whenever(lightningRepo.restartNode(anyOrNull(), any())).doSuspendableAnswer {
            finishRestart.await()
            Result.success(Unit)
        }
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        walletState.value = walletState.value.copy(walletExists = true)
        runCurrent()
        try {
            sut.start()
            runCurrent()
            assertEquals(RestoreState.Initial, sut.restoreState.value)
            assertTrue(checkNotNull(sut.connectionStartupError.value).requiresRecovery)

            sut.onRestoreRetry()
            runCurrent()

            verify(lightningRepo).restartNode(anyOrNull(), any())
            assertEquals(RestoreState.Initial, sut.restoreState.value)
            assertTrue(shouldPresentWalletRecovery(false, sut.restoreState.value, sut.connectionStartupError.value))
        } finally {
            finishObservation.complete(Unit)
            finishRestart.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(RestoreState.Completed(), sut.restoreState.value)
        assertNull(sut.connectionStartupError.value)
        assertFalse(shouldPresentWalletRecovery(false, sut.restoreState.value, sut.connectionStartupError.value))
    }

    @Test
    fun `new correction rebuilds the node again while required restore remains pending`() = test {
        preparePendingCorrection()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        savePendingCorrection("ssl://new-correction.example:50002")
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        verify(lightningRepo, times(2)).restartNode(anyOrNull(), any())
    }

    @Test
    fun `same seed replacement cannot reuse an earlier lifecycle connection acknowledgement`() = test {
        preparePendingCorrection()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        connectionRecovery.withIdentityChange { }
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        verify(lightningRepo, times(2)).restartNode(anyOrNull(), any())
    }

    @Test
    fun `failed node restart does not acknowledge the saved correction`() = test {
        preparePendingCorrection()
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(
            Result.failure(AppError("node failed")),
            Result.success(Unit),
        )
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        verify(lightningRepo, times(2)).restartNode(anyOrNull(), any())
    }

    @Test
    fun `local followup failure does not forget the successful node configuration`() = test {
        preparePendingCorrection()
        whenever(walletRepo.syncBalances()).doSuspendableAnswer { throw AppError("balance unavailable") }
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        verify(lightningRepo, times(1)).restartNode(anyOrNull(), any())
    }

    @Test
    fun `optional profile failure cannot replay a completed correction over later settings`() = test {
        val stored = stubRecoveryRecord()
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        whenever(pubkyRepo.initialize()).doSuspendableAnswer { throw AppError("profile unavailable") }
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        whenever(
            lightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()),
        ).thenReturn(Result.success(Unit))
        whenever(walletRepo.walletExists()).thenReturn(true)
        walletState.value = walletState.value.copy(walletExists = true)
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        advanceUntilIdle()
        savePendingCorrection()
        repeat(2) {
            sut.onBackupRestoreRetry()
            advanceUntilIdle()
        }
        var proceeded = false
        sut.onProceedWithoutRestore { proceeded = true }
        advanceUntilIdle()
        assertTrue(proceeded)
        settingsStore.update { it.copy(electrumServer = "ssl://later-settings.example:50002") }
        sut.start()
        advanceUntilIdle()
        assertEquals("ssl://later-settings.example:50002", stored.value.electrumServer)
    }

    @Test
    fun `proceed retains an unapplied correction until explicit retry`() = test {
        stubRecoveryRecord()
        whenever(
            backupRepo.performFullRestoreFromLatestBackup(any(), anyOrNull())
        ).thenReturn(Result.success(emptySet()))
        whenever(pubkyRepo.initialize()).doSuspendableAnswer { throw AppError("profile unavailable") }
        lightningState.value = lightningState.value.copy(nodeLifecycleState = NodeLifecycleState.Running)
        repeat(2) {
            sut.onBackupRestoreRetry()
            advanceUntilIdle()
        }
        savePendingCorrection()
        var proceeded = false
        sut.onProceedWithoutRestore { proceeded = true }
        advanceUntilIdle()
        assertFalse(proceeded)
        assertTrue(recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0) != null)
        verify(recoveryKeychain, never()).delete(WalletConnectionRecovery.KEY, 0)
        assertFalse(checkNotNull(sut.connectionStartupError.value).canProceedWithoutRestore)
        whenever(lightningRepo.restartNode(anyOrNull(), any())).thenReturn(Result.success(Unit))
        sut.onBackupRestoreRetry()
        advanceUntilIdle()
        sut.onProceedWithoutRestore { proceeded = true }
        advanceUntilIdle()
        assertTrue(proceeded)
        assertEquals(null, recoveryKeychain.loadString(WalletConnectionRecovery.KEY, 0))
    }
}
