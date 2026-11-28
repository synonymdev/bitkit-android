package to.bitkit.wear.ui.screens.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.repositories.SettingsSyncRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val settingsSyncRepo = mock<SettingsSyncRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val settings = MutableStateFlow(WatchSettings())

    private lateinit var sut: SettingsViewModel

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        sut = SettingsViewModel(walletRepo, settingsSyncRepo, settingsStore)
    }

    @Test
    fun `the row shows when the phone's settings were last imported`() = test {
        assertNull(sut.uiState.value.phoneSyncedAtMs)

        settings.update { it.copy(phoneSyncedAtMs = 1_000L) }

        assertEquals(1_000L, sut.uiState.value.phoneSyncedAtMs)
    }

    @Test
    fun `a tap on the row imports the phone's settings once at a time`() = test {
        val gate = CompletableDeferred<Result<Unit>>()
        whenever { settingsSyncRepo.sync() }.doSuspendableAnswer { gate.await() }

        sut.onSyncClick()
        sut.onSyncClick()

        assertTrue(sut.uiState.value.isSyncing)
        verify(settingsSyncRepo, times(1)).sync()

        gate.complete(Result.failure(SparkError.WipeInProgress()))

        assertFalse(sut.uiState.value.isSyncing)
    }

    @Test
    fun `removing the wallet asks first and wipes only on confirm`() = test {
        whenever(walletRepo.wipe()).thenReturn(Result.success(Unit))

        sut.onRemoveClick()
        assertTrue(sut.uiState.value.isRemoveDialogVisible)
        verify(walletRepo, never()).wipe()

        sut.onRemoveConfirm()

        verify(walletRepo).wipe()
        assertFalse(sut.uiState.value.isRemoveDialogVisible)
        assertFalse(sut.uiState.value.isRemoving)
        assertFalse(sut.uiState.value.isRemoveFailed)
    }

    @Test
    fun `a failed removal stays on settings and shows the failure`() = test {
        whenever(walletRepo.wipe()).thenReturn(Result.failure(SparkError.WipeFailed("dir")))

        sut.onRemoveClick()
        sut.onRemoveConfirm()

        assertTrue(sut.uiState.value.isRemoveFailed)
        assertFalse(sut.uiState.value.isRemoving)
    }
}
