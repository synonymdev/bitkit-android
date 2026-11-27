package to.bitkit.wear.ui.screens.settings

import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()

    private lateinit var sut: SettingsViewModel

    @Before
    fun setUp() {
        sut = SettingsViewModel(walletRepo)
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
