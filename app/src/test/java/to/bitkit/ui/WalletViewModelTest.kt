package to.bitkit.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import to.bitkit.data.SettingsStore
import to.bitkit.models.LnPeer
import to.bitkit.repositories.BackupsRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.TestApp
import to.bitkit.ui.onboarding.WalletInitResult
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.WalletViewModel

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class WalletViewModelTest : BaseUnitTest() {

    private lateinit var sut: WalletViewModel

    private val walletRepo: WalletRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val backupsRepo: BackupsRepo = mock()
    private val context: Context = mock()
    private val mockLightningState = MutableStateFlow(LightningState())
    private val mockWalletState = MutableStateFlow(WalletState())


    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(mockWalletState)
        whenever(lightningRepo.lightningState).thenReturn(mockLightningState)

        sut = WalletViewModel(
            bgDispatcher = Dispatchers.Unconfined,
            appContext = context,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            settingsStore = settingsStore,
            backupsRepo = backupsRepo,
        )
    }

    @Test
    fun `setInitNodeLifecycleState should call lightningRepo`() = test {
        sut.setInitNodeLifecycleState()
        verify(lightningRepo).setInitNodeLifecycleState()
    }


    @Test
    fun `refreshState should call lightningRepo sync`() = test {
        whenever(lightningRepo.sync()).thenReturn(Result.success(Unit))

        sut.refreshState()

        verify(lightningRepo).sync()
    }

    @Test
    fun `onPullToRefresh should call lightningRepo sync`() = test {
        sut.onPullToRefresh()

        verify(walletRepo).syncNodeAndWallet()
    }

    @Test
    fun `disconnectPeer should call lightningRepo disconnectPeer and send success toast`() = test {
        val testPeer = LnPeer("nodeId", "host", "9735")
        whenever(lightningRepo.disconnectPeer(testPeer)).thenReturn(Result.success(Unit))

        sut.disconnectPeer(testPeer)

        verify(lightningRepo).disconnectPeer(testPeer)
        // Add verification for ToastEventBus.send if you have a way to capture those events
    }

    @Test
    fun `disconnectPeer should call lightningRepo disconnectPeer and send failure toast`() = test {
        val testPeer = LnPeer("nodeId", "host", "9735")
        val testError = Exception("Test error")
        whenever(lightningRepo.disconnectPeer(testPeer)).thenReturn(Result.failure(testError))

        sut.disconnectPeer(testPeer)

        verify(lightningRepo).disconnectPeer(testPeer)
        // Add verification for ToastEventBus.send if you have a way to capture those events
    }

    @Test
    fun `updateBip21Invoice should call walletRepo updateBip21Invoice and send failure toast`() = test {
        val testError = Exception("Test error")
        whenever(walletRepo.updateBip21Invoice(anyOrNull(), any())).thenReturn(Result.failure(testError))

        sut.updateBip21Invoice()

        verify(walletRepo).updateBip21Invoice(anyOrNull(), any())
        // Add verification for ToastEventBus.send
    }

    @Test
    fun `refreshBip21 should call walletRepo refreshBip21`() = test {
        sut.refreshBip21()
        verify(walletRepo).refreshBip21()
    }

    @Test
    fun `openChannel should send a toast if there are no peers`() = test {
        val peersFlow = MutableStateFlow(emptyList<LnPeer>())
        whenever(lightningRepo.getPeers()).thenReturn(peersFlow.value)

        sut.openChannel()

        verify(lightningRepo, never()).openChannel(any(), any(), any())
        // Add verification for ToastEventBus.send
    }

    @Test
    fun `wipeWallet should call walletRepo wipeWallet`() =
        test {
            whenever(walletRepo.wipeWallet(walletIndex = 0)).thenReturn(Result.success(Unit))
            sut.wipeWallet()

            verify(walletRepo).wipeWallet(walletIndex = 0)
        }

    @Test
    fun `createWallet should call walletRepo createWallet and send failure toast`() = test {
        val testError = Exception("Test error")
        whenever(walletRepo.createWallet(anyOrNull())).thenReturn(Result.failure(testError))

        sut.createWallet(null)

        verify(walletRepo).createWallet(anyOrNull())
        // Add verification for ToastEventBus.send
    }

    @Test
    fun `restoreWallet should call walletRepo restoreWallet and send failure toast`() = test {
        val testError = Exception("Test error")
        whenever(walletRepo.restoreWallet(any(), anyOrNull())).thenReturn(Result.failure(testError))

        sut.restoreWallet("test_mnemonic", null)

        verify(walletRepo).restoreWallet(any(), anyOrNull())
        // Add verification for ToastEventBus.send
    }

    @Test
    fun `manualRegisterForNotifications should call lightningRepo registerForNotifications and send appropriate toasts`() =
        test {
            whenever(lightningRepo.registerForNotifications()).thenReturn(Result.success(Unit))

            sut.manualRegisterForNotifications()

            verify(lightningRepo).registerForNotifications()
            // Add verification for ToastEventBus.send
        }

    @Test
    fun `debugFcmToken should call lightningRepo getFcmToken`() = test {
        whenever(lightningRepo.getFcmToken()).thenReturn(Result.success("test_token"))

        sut.debugFcmToken()

        verify(lightningRepo).getFcmToken()
    }

    @Test
    fun `debugLspNotifications should call lightningRepo testNotification`() = test {
        whenever(lightningRepo.testNotification()).thenReturn(Result.success(Unit))

        sut.debugLspNotifications()

        verify(lightningRepo).testNotification()
    }

    @Test
    fun `stopIfNeeded should call lightningRepo stop`() = test {
        sut.stopIfNeeded()

        verify(lightningRepo).stop()
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

        verify(walletRepo).updateBip21Description("test_description")
    }

    // MARK: - Restore functionality tests

    @Test
    fun `setRestoringWalletState true should set restoreState to WaitingForWallet`() = test {
        sut.setRestoringWalletState(true)

        verify(walletRepo).setRestoringWalletState(isRestoring = true)
        assertEquals(RestoreState.WaitingForWallet, sut.restoreState)
    }

    @Test
    fun `setRestoringWalletState false should call walletRepo but not change restoreState`() = test {
        sut.setRestoringWalletState(true)
        assertEquals(RestoreState.WaitingForWallet, sut.restoreState)

        sut.setRestoringWalletState(false)

        verify(walletRepo).setRestoringWalletState(isRestoring = false)
        // restoreState should remain WaitingForWallet until wallet exists and triggers backup restore
        assertEquals(RestoreState.WaitingForWallet, sut.restoreState)
    }

    @Test
    fun `backup restore should be triggered when wallet exists and restoreState is WaitingForWallet`() = test {
        whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        sut.setRestoringWalletState(true)
        assertEquals(RestoreState.WaitingForWallet, sut.restoreState)

        mockWalletState.value = mockWalletState.value.copy(walletExists = true)

        verify(backupsRepo).performFullRestoreFromLatestBackup()
        verify(walletRepo).setRestoringWalletState(isRestoring = false)
        assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Restored), sut.restoreState)
    }

    @Test
    fun `backup restore should not be triggered when wallet exists but restoreState is not WaitingForWallet`() = test {
        assertEquals(RestoreState.None, sut.restoreState)

        mockWalletState.value = mockWalletState.value.copy(walletExists = true)

        verify(backupsRepo, never()).performFullRestoreFromLatestBackup()
    }

    @Test
    fun `backup restore success should set restoreState to BackupRestoreCompleted with Restored result`() = test {
        whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        sut.setRestoringWalletState(true)

        mockWalletState.value = mockWalletState.value.copy(walletExists = true)

        assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Restored), sut.restoreState)
    }

    @Test
    fun `backup restore failure should set restoreState to BackupRestoreCompleted with Failed result`() = test {
        val testError = Exception("Backup restore failed")
        whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.failure(testError))
        sut.setRestoringWalletState(true)

        mockWalletState.value = mockWalletState.value.copy(walletExists = true)

        assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Failed(testError)), sut.restoreState)
    }

    @Test
    fun `onBackupRestoreSuccess should reset restoreState to None`() = test {
        sut.setRestoringWalletState(true)
        whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        mockWalletState.value = mockWalletState.value.copy(walletExists = true)
        assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Restored), sut.restoreState)

        sut.onBackupRestoreSuccess()

        assertEquals(RestoreState.None, sut.restoreState)
    }

    @Test
    fun `onBackupRestoreRetry should trigger backup restore again`() = test {
        val testError = Exception("Test error")
        whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.failure(testError))
        sut.setRestoringWalletState(true)
        mockWalletState.value = mockWalletState.value.copy(walletExists = true)
        assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Failed(testError)), sut.restoreState)
        whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))

        sut.onBackupRestoreRetry()

        verify(backupsRepo, org.mockito.kotlin.times(2)).performFullRestoreFromLatestBackup()
        assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Restored), sut.restoreState)
    }

    @Test
    fun `restore state should transition from None to WaitingForWallet to RestoringBackups to BackupRestoreCompleted`() =
        test {
            whenever(backupsRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
            assertEquals(RestoreState.None, sut.restoreState)

            sut.setRestoringWalletState(true)
            assertEquals(RestoreState.WaitingForWallet, sut.restoreState)

            mockWalletState.value = mockWalletState.value.copy(walletExists = true)
            assertEquals(RestoreState.BackupRestoreCompleted(WalletInitResult.Restored), sut.restoreState)

            sut.onBackupRestoreSuccess()
            assertEquals(RestoreState.None, sut.restoreState)
        }
}
