package to.bitkit.wear.ui.screens.settings

import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsViewModelTest : BaseUnitTest() {
    private val settingsStore = mock<SettingsStore>()
    private val walletRepo = mock<WalletRepo>()

    private lateinit var sut: SettingsViewModel

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings(primaryDisplay = PrimaryDisplay.FIAT)))
        sut = SettingsViewModel(settingsStore, walletRepo)
    }

    @Test
    fun `reflects the stored primary display`() {
        assertTrue(sut.uiState.value.isFiatPrimary)
    }

    @Test
    fun `toggling fiat off stores bitcoin as primary`() = test {
        sut.onFiatPrimaryChange(false)

        verify(settingsStore).setPrimaryDisplay(PrimaryDisplay.BITCOIN)
    }

    @Test
    fun `removing the wallet asks first and wipes only on confirm`() = test {
        whenever(walletRepo.wipe()).thenReturn(Result.success(Unit))

        sut.onRemoveClick()
        assertTrue(sut.uiState.value.isRemoveDialogVisible)
        verify(walletRepo, never()).wipe()

        sut.effects.test {
            sut.onRemoveConfirm()
            assertEquals(SettingsEffect.WalletRemoved, awaitItem())
        }
        assertFalse(sut.uiState.value.isRemoveDialogVisible)
        assertFalse(sut.uiState.value.isRemoving)
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
