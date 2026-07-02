package to.bitkit.ui.screens.profile

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()

    @Test
    fun `signOut marks profile recovery before signing out`() = test {
        whenever(pubkyRepo.signOut()).thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.signOut()
            advanceUntilIdle()

            assertEquals(ProfileEffect.SignedOut, awaitItem())
        }
        inOrder(privatePaykitRepo, pubkyRepo).apply {
            verify(privatePaykitRepo).removePublishedEndpointsForCleanup(any())
            verify(pubkyRepo).signOut()
            verify(privatePaykitRepo).closeAndClear()
        }
    }

    @Test
    fun `signOut continues when private cleanup fails`() = test {
        val sut = createSut()
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.failure(ProfileTestAppError("cleanup failed")))
        advanceUntilIdle()

        sut.effects.test {
            sut.signOut()
            advanceUntilIdle()

            assertEquals(ProfileEffect.SignedOut, awaitItem())
        }
        verify(pubkyRepo).signOut()
        verify(privatePaykitRepo).closeAndClear()
    }

    @Test
    fun `signOut clears local Paykit state when Pubky sign out fails`() = test {
        val sut = createSut()
        whenever { pubkyRepo.signOut() }.thenReturn(Result.failure(ProfileTestAppError("sign out failed")))
        advanceUntilIdle()

        sut.signOut()
        advanceUntilIdle()

        verify(privatePaykitRepo).closeAndClear()
    }

    private fun createSut(): ProfileViewModel {
        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyalice"))
        whenever(pubkyRepo.isLoadingProfile).thenReturn(MutableStateFlow(false))
        whenever { pubkyRepo.loadProfile() }.thenReturn(Unit)
        whenever { pubkyRepo.signOut() }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.closeAndClear() }.thenReturn(Result.success(Unit))

        return ProfileViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            privatePaykitRepo = privatePaykitRepo,
        )
    }
}

private class ProfileTestAppError(message: String) : AppError(message)
