package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
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
        whenever { pubkyRepo.deriveKeys() }.thenReturn(Result.success("pubkyalice" to "secret"))
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
}
