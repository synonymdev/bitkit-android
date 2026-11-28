package to.bitkit.wear.ui

import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()

    @Before
    fun setUp() {
        whenever(walletRepo.isSupported).thenReturn(true)
        whenever { walletRepo.recover() }.thenReturn(Result.success(Unit))
        whenever { walletRepo.isRemovedNoticePending() }.thenReturn(false)
        whenever { walletRepo.hasWallet() }.thenReturn(false)
    }

    @Test
    fun `the first screen is onboarding without a wallet and home with one`() = test {
        assertEquals(Routes.ONBOARDING, StartViewModel(walletRepo).uiState.value.startRoute)

        whenever(walletRepo.hasWallet()).thenReturn(true)
        assertEquals(Routes.HOME, StartViewModel(walletRepo).uiState.value.startRoute)

        whenever(walletRepo.isRemovedNoticePending()).thenReturn(true)
        assertEquals(Routes.WALLET_REMOVED, StartViewModel(walletRepo).uiState.value.startRoute)
    }

    @Test
    fun `an unsupported network never touches the wallet storage`() = test {
        whenever(walletRepo.isSupported).thenReturn(false)

        assertEquals(Routes.UNSUPPORTED, StartViewModel(walletRepo).uiState.value.startRoute)
        verify(walletRepo, never()).recover()
    }

    @Test
    fun `a failed recovery picks no screen and shows the error, even with a wallet stored`() = test {
        whenever(walletRepo.recover()).thenReturn(Result.failure(SparkError.WipeFailed("dir")))
        whenever(walletRepo.hasWallet()).thenReturn(true)

        val state = StartViewModel(walletRepo).uiState.value

        assertNull(state.startRoute)
        assertTrue(state.isRecoveryFailed)
        assertFalse(state.isStarting)
        assertFalse(state.isLoading)
    }

    @Test
    fun `retrying a failed recovery picks the screen once it succeeds`() = test {
        whenever(walletRepo.recover()).thenReturn(Result.failure(SparkError.WipeFailed("dir")))
        val sut = StartViewModel(walletRepo)

        sut.onRetryClick()
        assertTrue(sut.uiState.value.isRecoveryFailed)

        whenever(walletRepo.recover()).thenReturn(Result.success(Unit))
        sut.onRetryClick()

        assertEquals(Routes.ONBOARDING, sut.uiState.value.startRoute)
        assertFalse(sut.uiState.value.isRecoveryFailed)
    }

    @Test
    fun `the window regaining focus reads the lock state`() = test {
        StartViewModel(walletRepo).onFocusGain()

        verify(walletRepo).refreshLock()
    }
}
