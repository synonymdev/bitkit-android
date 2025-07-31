package to.bitkit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
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
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WalletState
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.TestApp
import to.bitkit.viewmodels.RestoreState
import to.bitkit.viewmodels.WalletViewModel

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class WalletViewModelTest : BaseUnitTest() {

    private lateinit var sut: WalletViewModel

    private val walletRepo: WalletRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val backupRepo: BackupRepo = mock()
    private val mockLightningState = MutableStateFlow(LightningState())
    private val mockWalletState = MutableStateFlow(WalletState())

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(mockWalletState)
        whenever(lightningRepo.lightningState).thenReturn(mockLightningState)

        sut = WalletViewModel(
            bgDispatcher = testDispatcher,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            settingsStore = settingsStore,
            backupRepo = backupRepo,
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
    fun `onPullToRefresh should sync wallet`() = test {
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

    @Test
    fun `backup restore should not be triggered when wallet exists while not restoring`() = test {
        assertEquals(RestoreState.NotRestoring, sut.restoreState)

        mockWalletState.value = mockWalletState.value.copy(walletExists = true)

        verify(backupRepo, never()).performFullRestoreFromLatestBackup()
    }

    @Test
    fun `onBackupRestoreSuccess should reset restoreState`() = test {
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        mockWalletState.value = mockWalletState.value.copy(walletExists = true)
        sut.setRestoringWalletState()
        assertEquals(RestoreState.RestoringWallet, sut.restoreState)

        sut.onRestoreContinue()

        assertEquals(RestoreState.NotRestoring, sut.restoreState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `proceedWithoutRestore should exit restore flow`() = test {
        val testError = Exception("Test error")
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.failure(testError))
        sut.setRestoringWalletState()
        mockWalletState.value = mockWalletState.value.copy(walletExists = true)
        assertEquals(RestoreState.BackupRestoreCompleted, sut.restoreState)

        sut.proceedWithoutRestore(onDone = {})
        advanceUntilIdle()
        assertEquals(RestoreState.NotRestoring, sut.restoreState)
    }

    @Test
    fun `restore state should transition as expected`() = test {
        whenever(backupRepo.performFullRestoreFromLatestBackup()).thenReturn(Result.success(Unit))
        assertEquals(RestoreState.NotRestoring, sut.restoreState)

        sut.setRestoringWalletState()
        assertEquals(RestoreState.RestoringWallet, sut.restoreState)

        mockWalletState.value = mockWalletState.value.copy(walletExists = true)
        assertEquals(RestoreState.BackupRestoreCompleted, sut.restoreState)

        sut.onRestoreContinue()
        assertEquals(RestoreState.NotRestoring, sut.restoreState)
    }
}
