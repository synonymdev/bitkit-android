package to.bitkit.ui.screens.profile

import android.content.Context
import app.cash.turbine.test
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest : BaseUnitTest() {
    companion object {
        private const val TEST_PUBLIC_KEY = "pubkyalice"
    }

    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()

    @Test
    fun `updateLinkUrl should update existing profile link`() = test {
        val sut = createSut()
        advanceUntilIdle()

        sut.updateLinkUrl(0, "https://updated.example.com")

        assertEquals("https://updated.example.com", sut.uiState.value.links.first().url)
    }

    @Test
    fun `deleteProfile should emit success when repository delete succeeds`() = test {
        whenever(pubkyRepo.deleteProfileWithSessionRetry()).thenReturn(Result.success(Unit))

        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.deleteProfile()
            advanceUntilIdle()

            assertEquals(EditProfileEffect.DeleteSuccess, awaitItem())
        }
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        inOrder(privatePaykitRepo, pubkyRepo).apply {
            verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
            verify(pubkyRepo).deleteProfileWithSessionRetry()
            verify(privatePaykitRepo).closeAndClear()
        }
    }

    @Test
    fun `retryDeleteProfile should emit success when repository delete succeeds`() = test {
        whenever(pubkyRepo.deleteProfileWithSessionRetry()).thenReturn(Result.success(Unit))

        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.retryDeleteProfile()
            advanceUntilIdle()

            assertEquals(EditProfileEffect.DeleteSuccess, awaitItem())
        }
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        inOrder(privatePaykitRepo, pubkyRepo).apply {
            verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
            verify(pubkyRepo).deleteProfileWithSessionRetry()
            verify(privatePaykitRepo).closeAndClear()
        }
    }

    @Test
    fun `deleteProfile should show retry dialog when delete still fails`() = test {
        whenever(pubkyRepo.deleteProfileWithSessionRetry()).thenReturn(
            Result.failure(TestAppError("expired session")),
        )

        val sut = createSut()
        advanceUntilIdle()

        sut.deleteProfile()
        advanceUntilIdle()

        assertTrue(sut.uiState.value.showDeleteFailureDialog)
        assertFalse(sut.uiState.value.isSaving)
    }

    @Test
    fun `disconnectProfile should emit disconnect success`() = test {
        whenever(pubkyRepo.deleteProfileWithSessionRetry()).thenReturn(
            Result.failure(TestAppError("expired session")),
        )
        whenever(pubkyRepo.signOut()).thenReturn(Result.success(Unit))

        val sut = createSut()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()
        clearInvocations(privatePaykitRepo)

        sut.effects.test {
            sut.disconnectProfile()
            advanceUntilIdle()

            assertEquals(EditProfileEffect.DisconnectSuccess, awaitItem())
        }
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        inOrder(privatePaykitRepo, pubkyRepo).apply {
            verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
            verify(pubkyRepo).signOut()
            verify(privatePaykitRepo).closeAndClear()
        }
    }

    @Test
    fun `disconnectProfile stops when private cleanup fails`() = test {
        val sut = createSut()
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.failure(TestAppError("cleanup failed")))
        whenever(pubkyRepo.signOut()).thenReturn(Result.success(Unit))
        advanceUntilIdle()

        sut.disconnectProfile()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isSaving)
        verify(pubkyRepo, never()).signOut()
        verify(privatePaykitRepo, never()).closeAndClear()
    }

    @Test
    fun `disconnectProfile preserves local Paykit state when Pubky sign out fails`() = test {
        val sut = createSut()
        whenever(pubkyRepo.signOut()).thenReturn(Result.failure(TestAppError("sign out failed")))
        advanceUntilIdle()

        sut.disconnectProfile()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isSaving)
        verify(privatePaykitRepo, never()).closeAndClear()
    }

    @Test
    fun `deleteProfile should continue when private cleanup fails`() = test {
        val sut = createSut()
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.failure(TestAppError("cleanup failed")))
        whenever(pubkyRepo.deleteProfileWithSessionRetry()).thenReturn(Result.success(Unit))
        advanceUntilIdle()

        sut.effects.test {
            sut.deleteProfile()
            advanceUntilIdle()

            assertEquals(EditProfileEffect.DeleteSuccess, awaitItem())
        }
        assertFalse(sut.uiState.value.showDeleteFailureDialog)
        assertFalse(sut.uiState.value.isSaving)
        inOrder(privatePaykitRepo, pubkyRepo).apply {
            verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
            verify(pubkyRepo).deleteProfileWithSessionRetry()
            verify(privatePaykitRepo).closeAndClear()
        }
    }

    @Test
    fun `dismissDeleteFailureDialog should hide retry dialog`() = test {
        whenever(pubkyRepo.deleteProfileWithSessionRetry()).thenReturn(
            Result.failure(TestAppError("expired session")),
        )

        val sut = createSut()
        advanceUntilIdle()
        sut.deleteProfile()
        advanceUntilIdle()

        sut.dismissDeleteFailureDialog()

        assertFalse(sut.uiState.value.showDeleteFailureDialog)
    }

    private fun createSut(): EditProfileViewModel {
        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(createProfile()))
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(TEST_PUBLIC_KEY))
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.closeAndClear() }.thenReturn(Result.success(Unit))

        return EditProfileViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            privatePaykitRepo = privatePaykitRepo,
        )
    }

    private fun createProfile() = PubkyProfile(
        publicKey = TEST_PUBLIC_KEY,
        name = "Alice",
        bio = "Hello",
        imageUrl = "https://example.com/avatar.jpg",
        links = listOf(PubkyProfileLink("Website", "https://example.com")),
        tags = listOf("friend").toImmutableList(),
        status = null,
    )
}

private class TestAppError(message: String) : AppError(message)
