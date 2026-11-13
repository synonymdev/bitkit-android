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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPaySettingsViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val settingsStore: SettingsStore = mock()
    private val quickPayRepo: QuickPayRepo = mock()
    private val settings = MutableStateFlow(
        SettingsData(isQuickPayEnabled = false, quickPayAmount = 5, quickPayDailyLimitMultiplier = 5),
    )

    private lateinit var sut: QuickPaySettingsViewModel

    @Before
    fun setUp() {
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.common__error_body)).thenReturn("Unknown error")
        whenever(settingsStore.data).thenReturn(settings)
        listOf(
            QuickPayChange.Enabled(true),
            QuickPayChange.Amount(10),
            QuickPayChange.Amount(50),
            QuickPayChange.DailyLimitMultiplier(50),
        ).forEach {
            whenever { quickPayRepo.requestSettingsChange(it) }.thenReturn(Result.success(authorization(it)))
        }
        whenever { quickPayRepo.applyAuthorizedSettingsChange(any()) }.thenReturn(Result.success(Unit))
        sut = QuickPaySettingsViewModel(context, settingsStore, quickPayRepo)
    }

    @Test
    fun `a gated change waits for the check and saves once it passes`() = test {
        sut.setQuickPayEnabled(true)

        assertEquals(authorization(QuickPayChange.Enabled(true)), loadedState().authorization)
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())

        sut.onAuthorized()

        verify(quickPayRepo).applyAuthorizedSettingsChange(authorization(QuickPayChange.Enabled(true)))
        assertNull(loadedState().authorization)
    }

    @Test
    fun `a change saved at once opens no check`() = test {
        whenever(quickPayRepo.requestSettingsChange(any())).thenReturn(Result.success(null))

        sut.setQuickPayAmount(1)

        verify(quickPayRepo).requestSettingsChange(QuickPayChange.Amount(1))
        assertNull(loadedState().authorization)
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())
    }

    @Test
    fun `a cancelled check saves nothing`() = test {
        sut.setQuickPayAmount(50)
        sut.onAuthorizationCancelled()

        assertNull(loadedState().authorization)
        verify(quickPayRepo).cancelSettingsChange()
        sut.onAuthorized()
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())
    }

    @Test
    fun `a check that arrives after cancelling opens nothing and is dropped`() = test {
        val pending = authorization(QuickPayChange.Enabled(true))
        val result = CompletableDeferred<Result<QuickPayAuthorization?>>()
        whenever(quickPayRepo.requestSettingsChange(QuickPayChange.Enabled(true))).doSuspendableAnswer {
            result.await()
        }

        sut.setQuickPayEnabled(true)
        sut.onAuthorizationCancelled()
        result.complete(Result.success(pending))
        runCurrent()

        assertNull(loadedState().authorization)
        verify(quickPayRepo).cancelSettingsChange(pending)
        sut.onAuthorized()
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())
    }

    @Test
    fun `a late or repeated verification saves nothing`() = test {
        sut.onAuthorized()
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())

        sut.setQuickPayAmount(10)
        sut.onAuthorized()
        sut.onAuthorized()

        verify(quickPayRepo).applyAuthorizedSettingsChange(authorization(QuickPayChange.Amount(10)))
    }

    @Test
    fun `a later raise replaces the open check, as it does in the repo`() = test {
        sut.setQuickPayAmount(10)
        sut.setQuickPayDailyLimitMultiplier(50)

        assertEquals(QuickPayChange.DailyLimitMultiplier(50), loadedState().authorization?.change)

        sut.onAuthorized()

        verify(quickPayRepo).applyAuthorizedSettingsChange(authorization(QuickPayChange.DailyLimitMultiplier(50)))
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(authorization(QuickPayChange.Amount(10)))
    }

    @Test
    fun `a change saved at once closes the open check, which the repo already dropped`() = test {
        whenever(quickPayRepo.requestSettingsChange(QuickPayChange.Amount(1))).thenReturn(Result.success(null))
        sut.setQuickPayAmount(50)

        sut.setQuickPayAmount(1)

        assertNull(loadedState().authorization)
        verify(quickPayRepo, never()).cancelSettingsChange(anyOrNull())
        sut.onAuthorized()
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())
    }

    @Test
    fun `quick changes reach the repo in tap order, so the last free change closes the earlier raise`() = test {
        val raise = authorization(QuickPayChange.Amount(50))
        val raiseResult = CompletableDeferred<Result<QuickPayAuthorization?>>()
        whenever(quickPayRepo.requestSettingsChange(QuickPayChange.Amount(50))).doSuspendableAnswer {
            raiseResult.await()
        }
        whenever(quickPayRepo.requestSettingsChange(QuickPayChange.Amount(1))).thenReturn(Result.success(null))

        sut.setQuickPayAmount(50)
        sut.setQuickPayAmount(1)

        verify(quickPayRepo, never()).requestSettingsChange(QuickPayChange.Amount(1))

        raiseResult.complete(Result.success(raise))
        runCurrent()

        inOrder(quickPayRepo) {
            verify(quickPayRepo).requestSettingsChange(QuickPayChange.Amount(50))
            verify(quickPayRepo).requestSettingsChange(QuickPayChange.Amount(1))
        }
        assertNull(loadedState().authorization)
        sut.onAuthorized()
        verify(quickPayRepo, never()).applyAuthorizedSettingsChange(any())
    }

    @Test
    fun `a failed request opens no check and shows an error`() = test {
        whenever(quickPayRepo.requestSettingsChange(any())).thenReturn(Result.failure(AppError("write failed")))

        val toast = collectToast { sut.setQuickPayEnabled(true) }

        assertNull(loadedState().authorization)
        assertErrorToast(toast)
    }

    @Test
    fun `a failed save after the check closes the check and shows an error`() = test {
        whenever(quickPayRepo.applyAuthorizedSettingsChange(any())).thenReturn(Result.failure(AppError("write failed")))
        sut.setQuickPayEnabled(true)

        val toast = collectToast { sut.onAuthorized() }

        verify(quickPayRepo).applyAuthorizedSettingsChange(authorization(QuickPayChange.Enabled(true)))
        assertNull(loadedState().authorization)
        assertErrorToast(toast)
    }

    @Test
    fun `ui state mirrors the stored settings`() = test {
        settings.value = settings.value.copy(isQuickPayEnabled = true, quickPayAmount = 10)

        val state = loadedState()

        assertTrue(state.isQuickPayEnabled)
        assertEquals(10, state.quickPayAmount)
        assertEquals(5, state.quickPayDailyLimitMultiplier)
    }

    private fun authorization(change: QuickPayChange) = QuickPayAuthorization(change, useBiometrics = true)

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
