package to.bitkit.ui.settings.quickPay

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.repositories.QuickPayAuthorization
import to.bitkit.repositories.QuickPayChange
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPaySettingsViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val settingsStore: SettingsStore = mock()
    private val quickPayRepo: QuickPayRepo = mock()
    private val settings = MutableStateFlow(
        SettingsData(isQuickPayEnabled = false, quickPayAmount = 5, quickPayDailyLimitMultiplier = 5),
    )
    private val pendingCheck = MutableStateFlow<QuickPayAuthorization?>(null)

    private lateinit var sut: QuickPaySettingsViewModel

    @Before
    fun setUp() {
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.common__error_body)).thenReturn("Unknown error")
        whenever(settingsStore.data).thenReturn(settings)
        whenever(quickPayRepo.pendingSettingsChange).thenReturn(pendingCheck)
        whenever(quickPayRepo.requestSettingsChange(any())).thenReturn(CompletableDeferred(Result.success(Unit)))
        whenever { quickPayRepo.applyAuthorizedSettingsChange(any()) }.thenReturn(Result.success(Unit))
        sut = QuickPaySettingsViewModel(context, settingsStore, quickPayRepo)
    }

    @Test
    fun `ui state mirrors the stored settings and the repo's open check`() = test {
        val check = QuickPayAuthorization(QuickPayChange.Amount(50), useBiometrics = true)
        settings.value = settings.value.copy(isQuickPayEnabled = true, quickPayAmount = 10)
        pendingCheck.value = check

        val state = loadedState()

        assertTrue(state.isQuickPayEnabled)
        assertEquals(10, state.quickPayAmount)
        assertEquals(5, state.quickPayDailyLimitMultiplier)
        assertSame(check, state.authorization)

        pendingCheck.value = null
        assertNull(loadedState().authorization)
    }

    @Test
    fun `changes reach the repo in tap order without waiting for earlier ones`() = test {
        whenever(quickPayRepo.requestSettingsChange(QuickPayChange.Amount(20))).thenReturn(CompletableDeferred())

        sut.setQuickPayAmount(20)
        sut.setQuickPayAmount(1)
        sut.setQuickPayEnabled(true)
        sut.setQuickPayDailyLimitMultiplier(50)

        inOrder(quickPayRepo) {
            verify(quickPayRepo).requestSettingsChange(QuickPayChange.Amount(20))
            verify(quickPayRepo).requestSettingsChange(QuickPayChange.Amount(1))
            verify(quickPayRepo).requestSettingsChange(QuickPayChange.Enabled(true))
            verify(quickPayRepo).requestSettingsChange(QuickPayChange.DailyLimitMultiplier(50))
        }
    }

    @Test
    fun `verifying saves the shown check and cancelling closes it in the repo`() = test {
        val check = QuickPayAuthorization(QuickPayChange.Enabled(true), useBiometrics = false)

        sut.onAuthorized(check)
        sut.onAuthorizationCancelled()

        verify(quickPayRepo).applyAuthorizedSettingsChange(check)
        verify(quickPayRepo).cancelSettingsChange()
    }

    @Test
    fun `a failed request shows an error`() = test {
        whenever(quickPayRepo.requestSettingsChange(any()))
            .thenReturn(CompletableDeferred(Result.failure(AppError("write failed"))))

        val toast = collectToast { sut.setQuickPayEnabled(true) }

        assertErrorToast(toast)
    }

    @Test
    fun `a failed save after the check shows an error`() = test {
        whenever(quickPayRepo.applyAuthorizedSettingsChange(any())).thenReturn(Result.failure(AppError("write failed")))
        val check = QuickPayAuthorization(QuickPayChange.Enabled(true), useBiometrics = false)

        val toast = collectToast { sut.onAuthorized(check) }

        assertErrorToast(toast)
    }

    private fun TestScope.collectToast(action: () -> Unit): Toast {
        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        runCurrent()
        action()
        runCurrent()
        collectJob.cancel()
        return toasts.single()
    }

    private fun assertErrorToast(toast: Toast) {
        assertEquals(Toast.ToastType.ERROR, toast.type)
        assertEquals("Error", toast.title)
        assertEquals("Unknown error", toast.description)
    }

    private fun TestScope.loadedState(): QuickPaySettingsUiState {
        backgroundScope.launch { sut.uiState.collect {} }
        runCurrent()
        return sut.uiState.value
    }
}
