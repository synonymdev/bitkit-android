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
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPaySettingsViewModelTest : BaseUnitTest() {
    private val settingsStore: SettingsStore = mock()
    private val gatedSettings = SettingsData(
        isPinEnabled = true,
        isPinForPaymentsEnabled = true,
        isQuickPayEnabled = false,
        quickPayAmount = 5,
        quickPayDailyLimitMultiplier = 5,
    )
    private val settings = MutableStateFlow(gatedSettings)

    private lateinit var sut: QuickPaySettingsViewModel

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(settings)
        whenever { settingsStore.update(any()) }.thenAnswer {
            settings.value = it.getArgument<(SettingsData) -> SettingsData>(0)(settings.value)
            Unit
        }
        sut = QuickPaySettingsViewModel(settingsStore)
    }

    @Test
    fun `enabling asks for the pin first and saves nothing before it`() = test {
        settings.value = gatedSettings.copy(isBiometricEnabled = true)

        sut.setQuickPayEnabled(true)

        assertEquals(
            QuickPayAuthorization(QuickPayChange.Enabled(true), useBiometrics = true),
            loadedState().authorization,
        )
        assertFalse(settings.value.isQuickPayEnabled)
        verify(settingsStore, never()).update(any())

        sut.onAuthorized()

        assertTrue(settings.value.isQuickPayEnabled)
        assertNull(loadedState().authorization)
    }

    @Test
    fun `raising the per-payment amount asks for the pin first`() = test {
        sut.setQuickPayAmount(20)

        assertEquals(QuickPayChange.Amount(20), loadedState().authorization?.change)
        assertEquals(5, settings.value.quickPayAmount)

        sut.onAuthorized()

        assertEquals(20, settings.value.quickPayAmount)
    }

    @Test
    fun `raising the daily limit multiplier asks for the pin first`() = test {
        sut.setQuickPayDailyLimitMultiplier(10)

        assertEquals(QuickPayChange.DailyLimitMultiplier(10), loadedState().authorization?.change)
        assertEquals(5, settings.value.quickPayDailyLimitMultiplier)

        sut.onAuthorized()

        assertEquals(10, settings.value.quickPayDailyLimitMultiplier)
    }

    @Test
    fun `a cancelled check leaves every value unchanged`() = test {
        sut.setQuickPayAmount(50)
        sut.onAuthorizationCancelled()

        assertEquals(gatedSettings, settings.value)
        assertNull(loadedState().authorization)
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `a late or repeated verification saves nothing`() = test {
        sut.onAuthorized()
        verify(settingsStore, never()).update(any())

        sut.setQuickPayAmount(10)
        sut.onAuthorized()
        sut.onAuthorized()

        assertEquals(gatedSettings.copy(quickPayAmount = 10), settings.value)
        verify(settingsStore).update(any())
    }

    @Test
    fun `a second raise while a check is open is ignored`() = test {
        sut.setQuickPayAmount(10)
        sut.setQuickPayDailyLimitMultiplier(50)

        assertEquals(QuickPayChange.Amount(10), loadedState().authorization?.change)

        sut.onAuthorized()

        assertEquals(gatedSettings.copy(quickPayAmount = 10), settings.value)
    }

    @Test
    fun `an authorized change saves only its own setting`() = test {
        sut.setQuickPayDailyLimitMultiplier(50)
        settings.value = settings.value.copy(quickPayAmount = 1)

        sut.onAuthorized()

        assertEquals(gatedSettings.copy(quickPayAmount = 1, quickPayDailyLimitMultiplier = 50), settings.value)
    }

    @Test
    fun `disabling and lowering apply without a check`() = test {
        settings.value = gatedSettings.copy(isQuickPayEnabled = true, quickPayAmount = 20)

        sut.setQuickPayEnabled(false)
        sut.setQuickPayAmount(10)
        sut.setQuickPayDailyLimitMultiplier(3)

        assertNull(loadedState().authorization)
        assertEquals(
            gatedSettings.copy(isQuickPayEnabled = false, quickPayAmount = 10, quickPayDailyLimitMultiplier = 3),
            settings.value,
        )
    }

    @Test
    fun `raises apply without a check when the pin is off`() = test {
        settings.value = gatedSettings.copy(isPinEnabled = false)

        raiseAll()

        assertNull(loadedState().authorization)
        assertRaised()
    }

    @Test
    fun `raises apply without a check when payments need no pin`() = test {
        settings.value = gatedSettings.copy(isPinForPaymentsEnabled = false)

        raiseAll()

        assertNull(loadedState().authorization)
        assertRaised()
    }

    @Test
    fun `ui state mirrors the stored settings`() = test {
        settings.value = gatedSettings.copy(isQuickPayEnabled = true, quickPayAmount = 10)

        val state = loadedState()

        assertTrue(state.isQuickPayEnabled)
        assertEquals(10, state.quickPayAmount)
        assertEquals(5, state.quickPayDailyLimitMultiplier)
    }

    private fun raiseAll() {
        sut.setQuickPayEnabled(true)
        sut.setQuickPayAmount(50)
        sut.setQuickPayDailyLimitMultiplier(10)
    }

    private fun assertRaised() {
        assertTrue(settings.value.isQuickPayEnabled)
        assertEquals(50, settings.value.quickPayAmount)
        assertEquals(10, settings.value.quickPayDailyLimitMultiplier)
    }

    private fun TestScope.loadedState(): QuickPaySettingsUiState {
        backgroundScope.launch { sut.uiState.collect {} }
        runCurrent()
        return sut.uiState.value
    }
}
