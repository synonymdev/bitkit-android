package to.bitkit.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PeerDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsStore
import to.bitkit.ext.from
import to.bitkit.models.BalanceState
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.SyncSource
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.WalletViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest : BaseUnitTest() {
    private lateinit var sut: WalletViewModel

    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val backupRepo = mock<BackupRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()

    private val lightningState = MutableStateFlow(LightningState())
    private val walletState = MutableStateFlow(WalletState())
    private val balanceState = MutableStateFlow(BalanceState())
    private val isRecoveryMode = MutableStateFlow(false)

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(lightningRepo.lightningState).thenReturn(lightningState)

        sut = WalletViewModel(
            bgDispatcher = testDispatcher,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
        )
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
        val testPeer = PeerDetails.from("nodeId", "host", "9735")
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
        assertEquals(RestoreState.Initial, sut.restoreState)

        walletState.value = walletState.value.copy(walletExists = true)

        verify(backupRepo, never()).performFullRestoreFromLatestBackup()
    }

    @Test
    fun `onBackupRestoreSuccess should reset restoreState`() = test {
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        walletState.value = walletState.value.copy(walletExists = true)
        sut.restoreWallet("mnemonic", "passphrase")
        assertEquals(RestoreState.InProgress.Wallet, sut.restoreState)

        sut.onRestoreContinue()

        assertEquals(RestoreState.Settled, sut.restoreState)
    }

    @Test
    fun `proceedWithoutRestore should exit restore flow`() = test {
        val testError = Exception("Test error")
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.failure(testError))
        sut.restoreWallet("mnemonic", "passphrase")
        walletState.value = walletState.value.copy(walletExists = true)
        assertEquals(RestoreState.Completed, sut.restoreState)

        sut.proceedWithoutRestore(onDone = {})
        advanceUntilIdle()
        assertEquals(RestoreState.Settled, sut.restoreState)
    }

    @Test
    fun `restore state should transition as expected`() = test {
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        assertEquals(RestoreState.Initial, sut.restoreState)

        sut.restoreWallet("mnemonic", "passphrase")
        assertEquals(RestoreState.InProgress.Wallet, sut.restoreState)

        walletState.value = walletState.value.copy(walletExists = true)
        assertEquals(RestoreState.Completed, sut.restoreState)

        sut.onRestoreContinue()
        assertEquals(RestoreState.Settled, sut.restoreState)
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
        whenever(testLightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(Unit))

        val testSut = WalletViewModel(
            bgDispatcher = testDispatcher,
            walletRepo = testWalletRepo,
            lightningRepo = testLightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
        )

        assertEquals(RestoreState.Initial, testSut.restoreState)
        assertEquals(true, testSut.walletExists)

        testSut.start()
        advanceUntilIdle()

        verify(testLightningRepo).start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(testWalletRepo).refreshBip21()
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
        whenever(testLightningRepo.start(any(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(Unit))

        val testSut = WalletViewModel(
            bgDispatcher = testDispatcher,
            walletRepo = testWalletRepo,
            lightningRepo = testLightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
            blocktankRepo = blocktankRepo,
        )

        // Trigger restore to put state in non-idle
        testSut.restoreWallet("mnemonic", null)
        assertEquals(RestoreState.InProgress.Wallet, testSut.restoreState)

        testSut.start()
        advanceUntilIdle()

        verify(testWalletRepo, never()).refreshBip21()
    }
}
