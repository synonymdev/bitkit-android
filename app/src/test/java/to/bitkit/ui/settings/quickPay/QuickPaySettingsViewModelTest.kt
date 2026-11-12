package to.bitkit.ui.settings.quickPay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.QuickPayAuthorization
import to.bitkit.repositories.QuickPayChange
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPaySettingsViewModelTest : BaseUnitTest() {
    private val settingsStore: SettingsStore = mock()
    private val quickPayRepo: QuickPayRepo = mock()
    private val settings = MutableStateFlow(
        SettingsData(isQuickPayEnabled = false, quickPayAmount = 5, quickPayDailyLimitMultiplier = 5),
    )

    private lateinit var sut: QuickPaySettingsViewModel

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        listOf(
            QuickPayChange.Enabled(true),
            QuickPayChange.Amount(10),
            QuickPayChange.Amount(50),
            QuickPayChange.DailyLimitMultiplier(50),
        ).forEach {
            whenever { quickPayRepo.requestSettingsChange(it) }
                .thenReturn(Result.success(QuickPayAuthorization(it, useBiometrics = true)))
        }
        whenever { quickPayRepo.applySettingsChange(any()) }.thenReturn(Result.success(Unit))
        sut = QuickPaySettingsViewModel(settingsStore, quickPayRepo)
    }

    @Test
    fun `a gated change waits for the check and saves once it passes`() = test {
        sut.setQuickPayEnabled(true)

        assertEquals(
            QuickPayAuthorization(QuickPayChange.Enabled(true), useBiometrics = true),
            loadedState().authorization,
        )
        verify(quickPayRepo, never()).applySettingsChange(any())

        sut.onAuthorized()

        verify(quickPayRepo).applySettingsChange(QuickPayChange.Enabled(true))
        assertNull(loadedState().authorization)
    }

    @Test
    fun `a change saved at once opens no check`() = test {
        whenever(quickPayRepo.requestSettingsChange(any())).thenReturn(Result.success(null))

        sut.setQuickPayAmount(1)

        verify(quickPayRepo).requestSettingsChange(QuickPayChange.Amount(1))
        assertNull(loadedState().authorization)
        verify(quickPayRepo, never()).applySettingsChange(any())
    }

    @Test
    fun `a cancelled check saves nothing`() = test {
        sut.setQuickPayAmount(50)
        sut.onAuthorizationCancelled()

        assertNull(loadedState().authorization)
        sut.onAuthorized()
        verify(quickPayRepo, never()).applySettingsChange(any())
    }

    @Test
    fun `a late or repeated verification saves nothing`() = test {
        sut.onAuthorized()
        verify(quickPayRepo, never()).applySettingsChange(any())

        sut.setQuickPayAmount(10)
        sut.onAuthorized()
        sut.onAuthorized()

        verify(quickPayRepo).applySettingsChange(QuickPayChange.Amount(10))
    }

    @Test
    fun `a second raise while a check is open is ignored`() = test {
        sut.setQuickPayAmount(10)
        sut.setQuickPayDailyLimitMultiplier(50)

        assertEquals(QuickPayChange.Amount(10), loadedState().authorization?.change)

        sut.onAuthorized()

        verify(quickPayRepo).applySettingsChange(QuickPayChange.Amount(10))
        verify(quickPayRepo, never()).applySettingsChange(QuickPayChange.DailyLimitMultiplier(50))
    }

    @Test
    fun `a failed request opens no check`() = test {
        whenever(quickPayRepo.requestSettingsChange(any())).thenReturn(Result.failure(AppError("write failed")))

        sut.setQuickPayEnabled(true)

        assertNull(loadedState().authorization)
    }

    @Test
    fun `a failed save after the check closes the check`() = test {
        whenever(quickPayRepo.applySettingsChange(any())).thenReturn(Result.failure(AppError("write failed")))
        sut.setQuickPayEnabled(true)

        sut.onAuthorized()

        verify(quickPayRepo).applySettingsChange(QuickPayChange.Enabled(true))
        assertNull(loadedState().authorization)
    }

    @Test
    fun `ui state mirrors the stored settings`() = test {
        settings.value = settings.value.copy(isQuickPayEnabled = true, quickPayAmount = 10)

        val state = loadedState()

        assertTrue(state.isQuickPayEnabled)
        assertEquals(10, state.quickPayAmount)
        assertEquals(5, state.quickPayDailyLimitMultiplier)
    }

    private fun TestScope.loadedState(): QuickPaySettingsUiState {
        backgroundScope.launch { sut.uiState.collect {} }
        runCurrent()
        return sut.uiState.value
    }
}
