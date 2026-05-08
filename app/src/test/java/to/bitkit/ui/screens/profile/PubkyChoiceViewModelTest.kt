package to.bitkit.ui.screens.profile

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyChoiceViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val packageManager: PackageManager = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    private val authCancelEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private lateinit var sut: PubkyChoiceViewModel

    @Before
    fun setUp() {
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization Failed")
        whenever(pubkyRepo.pendingImportContacts).thenReturn(pendingImportContacts)
        whenever(pubkyRepo.authCancelEvents).thenReturn(authCancelEvents)
        sut = PubkyChoiceViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
        )
    }

    @Test
    fun `waitForApproval prepareImport failure clears loading and emits no navigation`() = test {
        whenever(pubkyRepo.completeAuthentication()).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.prepareImport()).thenReturn(Result.failure(RuntimeException("Import failed")))

        val effects = mutableListOf<PubkyChoiceEffect>()
        val toasts = mutableListOf<Toast>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.waitForApproval()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoadingAfterAuth)
        assertFalse(sut.uiState.value.isWaitingForRing)
        assertTrue(effects.isEmpty())
        assertTrue(toasts.isNotEmpty())
        assertEquals(Toast.ToastType.ERROR, toasts.last().type)
        assertEquals("Error", toasts.last().title)
        assertEquals("Import failed", toasts.last().description)

        effectsJob.cancel()
        toastJob.cancel()
    }

    @Test
    fun `startRingAuth shows dialog when Ring is not installed`() = test {
        whenever(packageManager.getLaunchIntentForPackage(PubkyChoiceViewModel.PUBKY_RING_PACKAGE))
            .thenReturn(null)

        val effects = mutableListOf<PubkyChoiceEffect>()
        val toasts = mutableListOf<Toast>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.startRingAuth()
        advanceUntilIdle()

        assertTrue(sut.uiState.value.showRingNotInstalledDialog)
        assertTrue(effects.isEmpty())
        assertTrue(toasts.isEmpty())
        verify(pubkyRepo, never()).startAuthentication()

        effectsJob.cancel()
        toastJob.cancel()
    }

    @Test
    fun `onRingLaunchFailed shows dialog without toast`() = test {
        val effects = mutableListOf<PubkyChoiceEffect>()
        val toasts = mutableListOf<Toast>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.onRingLaunchFailed()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isWaitingForRing)
        assertTrue(sut.uiState.value.showRingNotInstalledDialog)
        assertTrue(effects.isEmpty())
        assertTrue(toasts.isEmpty())
        verify(pubkyRepo).cancelAuthentication()

        effectsJob.cancel()
        toastJob.cancel()
    }
}
