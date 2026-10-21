package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CreateProfileViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()

    private lateinit var sut: CreateProfileViewModel

    @Before
    fun setUp() {
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization Failed")
        whenever(context.getString(R.string.profile__create_error)).thenReturn("Create failed")
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.deriveKeys() }.thenReturn(Result.success("pubkyalice" to "secret"))
        whenever { pubkyRepo.hasStoredSecretKey() }.thenReturn(false)
        whenever { pubkyRepo.fetchRemoteProfile(any()) }.thenReturn(Result.success(null))

        sut = CreateProfileViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
        )
    }

    @Test
    fun `save should emit success effect without toast`() = test {
        whenever(pubkyRepo.createIdentity(any(), any(), any(), any(), any())).thenReturn(Result.success(Unit))

        val effects = mutableListOf<CreateProfileEffect>()
        val toasts = mutableListOf<Toast>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.onNameChange("Alice")
        advanceUntilIdle()

        sut.save()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertEquals(CreateProfileEffect.CreateSuccess, effects.single())
        assertTrue(toasts.isEmpty())

        effectsJob.cancel()
        toastJob.cancel()
    }

    @Test
    fun `save should not publish after a failed lookup for a signed-in pubky`() = test {
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyalice"))
        whenever(pubkyRepo.fetchRemoteProfile(any())).thenReturn(Result.failure(CreateProfileTestAppError("timeout")))
        sut = CreateProfileViewModel(context = context, pubkyRepo = pubkyRepo)

        sut.onNameChange("Alice")
        advanceUntilIdle()
        sut.save()
        advanceUntilIdle()

        verify(pubkyRepo, never()).createIdentity(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `save should not publish after a failed lookup for a stored pubky`() = test {
        whenever(pubkyRepo.hasStoredSecretKey()).thenReturn(true)
        whenever(pubkyRepo.fetchRemoteProfile(any())).thenReturn(Result.failure(CreateProfileTestAppError("timeout")))
        sut = CreateProfileViewModel(context = context, pubkyRepo = pubkyRepo)

        sut.onNameChange("Alice")
        advanceUntilIdle()
        sut.save()
        advanceUntilIdle()

        verify(pubkyRepo, never()).createIdentity(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `save should still create a new pubky when the lookup fails before sign-up`() = test {
        whenever(pubkyRepo.fetchRemoteProfile(any()))
            .thenReturn(Result.failure(CreateProfileTestAppError("no homeserver")))
        whenever(pubkyRepo.createIdentity(any(), any(), any(), any(), anyOrNull())).thenReturn(Result.success(Unit))
        sut = CreateProfileViewModel(context = context, pubkyRepo = pubkyRepo)

        sut.onNameChange("Alice")
        advanceUntilIdle()
        sut.save()
        advanceUntilIdle()

        verify(pubkyRepo).createIdentity(any(), any(), any(), any(), anyOrNull())
    }
}

private class CreateProfileTestAppError(message: String) : AppError(message)
