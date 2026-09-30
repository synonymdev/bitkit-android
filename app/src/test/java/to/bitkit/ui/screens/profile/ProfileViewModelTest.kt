package to.bitkit.ui.screens.profile

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyCachedProfile
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()

    @Test
    fun `init loads the profile when none is loaded`() = test {
        createSut()
        advanceUntilIdle()

        verify(pubkyRepo).loadProfile()
    }

    @Test
    fun `init skips loading a profile already loaded for the current key`() = test {
        createSut(createProfile())
        advanceUntilIdle()

        verify(pubkyRepo, never()).loadProfile()
    }

    @Test
    fun `init loads the profile when the loaded one belongs to another key`() = test {
        createSut(createProfile().copy(publicKey = "pubkybob"))
        advanceUntilIdle()

        verify(pubkyRepo).loadProfile()
    }

    @Test
    fun `retry loads the profile even when it is already loaded`() = test {
        val sut = createSut(createProfile())
        advanceUntilIdle()

        sut.loadProfile()
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).loadProfile()
    }

    @Test
    fun `initial state is seeded from the repository`() = test {
        val profile = createProfile()
        val sut = createSut(profile = profile, isLoading = true)

        assertEquals(profile, sut.uiState.value.profile)
        assertEquals("pubkyalice", sut.uiState.value.publicKey)
        assertTrue(sut.uiState.value.isLoading)
    }

    @Test
    fun `cached profile is exposed while loading when its owner matches the public key`() = test {
        val cachedProfile = createCachedProfile(publicKey = "pubkyalice")
        val sut = createSut(isLoading = true, cachedProfile = cachedProfile)

        assertEquals(cachedProfile, sut.uiState.value.cachedProfile)
    }

    @Test
    fun `cached profile is hidden when its owner differs from the public key`() = test {
        val sut = createSut(isLoading = true, cachedProfile = createCachedProfile(publicKey = "pubkybob"))

        assertNull(sut.uiState.value.cachedProfile)
    }

    @Test
    fun `cached profile is hidden without a public key`() = test {
        val sut = createSut(publicKey = null, cachedProfile = createCachedProfile(publicKey = "pubkyalice"))

        assertNull(sut.uiState.value.cachedProfile)
    }

    @Test
    fun `cached profile keeps profile edits disabled until the profile loads`() = test {
        val sut = createSut(isLoading = true, cachedProfile = createCachedProfile(publicKey = "pubkyalice"))
        advanceUntilIdle()

        sut.addTag("Bitcoin")
        sut.removeTag("Founder")
        advanceUntilIdle()

        assertNull(sut.uiState.value.profile)
        verify(pubkyRepo, never()).saveProfile(any(), any(), any(), any(), any())
    }

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
    fun `signOut stops when private cleanup fails`() = test {
        val sut = createSut()
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.failure(ProfileTestAppError("cleanup failed")))
        advanceUntilIdle()

        sut.signOut()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isSigningOut)
        verify(pubkyRepo, never()).signOut()
        verify(privatePaykitRepo, never()).closeAndClear()
    }

    @Test
    fun `signOut preserves local Paykit state when Pubky sign out fails`() = test {
        val sut = createSut()
        whenever(pubkyRepo.signOut()).thenReturn(Result.failure(ProfileTestAppError("sign out failed")))
        advanceUntilIdle()

        sut.signOut()
        advanceUntilIdle()

        verify(privatePaykitRepo, never()).closeAndClear()
    }

    @Test
    fun `addTag saves the updated profile and closes the sheet`() = test {
        val sut = createSut(createProfile())
        advanceUntilIdle()

        sut.uiState.test {
            var state = awaitItem()
            while (state.profile == null) state = awaitItem()
            sut.showAddTagSheet()
            assertTrue(awaitItem().showAddTagSheet)

            sut.addTag("Bitcoin")
            assertFalse(awaitItem().showAddTagSheet)
        }
        advanceUntilIdle()
        verify(pubkyRepo).saveProfile(
            name = "Alice",
            bio = "Builder",
            links = listOf(PubkyProfileLink("Website", "https://example.com")),
            tags = listOf("Founder", "Bitcoin"),
            imageUrl = "https://example.com/avatar.png",
        )
    }

    @Test
    fun `adding an existing tag closes the sheet without saving`() = test {
        val sut = createSut(createProfile())
        advanceUntilIdle()

        sut.uiState.test {
            var state = awaitItem()
            while (state.profile == null) state = awaitItem()
            sut.showAddTagSheet()
            assertTrue(awaitItem().showAddTagSheet)

            sut.addTag("Founder")
            assertFalse(awaitItem().showAddTagSheet)
        }
        verify(pubkyRepo, never()).saveProfile(any(), any(), any(), any(), any())
    }

    @Test
    fun `removeTag saves the profile without the selected tag`() = test {
        val sut = createSut(createProfile(tags = listOf("Founder", "Bitcoin")))
        advanceUntilIdle()

        sut.removeTag("Founder")
        advanceUntilIdle()

        verify(pubkyRepo).saveProfile(
            name = eq("Alice"),
            bio = eq("Builder"),
            links = any(),
            tags = eq(listOf("Bitcoin")),
            imageUrl = eq("https://example.com/avatar.png"),
        )
    }

    @Test
    fun `rapid tag removals are serialized against the latest profile`() = test {
        val profileFlow = MutableStateFlow<PubkyProfile?>(
            createProfile(tags = listOf("Founder", "Bitcoin")),
        )
        val sut = createSut(profileFlow = profileFlow)
        whenever(pubkyRepo.saveProfile(any(), any(), any(), any(), any())).doSuspendableAnswer {
            val tags = it.getArgument<List<String>>(3)
            profileFlow.value = requireNotNull(profileFlow.value).copy(tags = tags)
            Result.success(Unit)
        }
        advanceUntilIdle()

        sut.removeTag("Founder")
        sut.removeTag("Bitcoin")
        advanceUntilIdle()

        inOrder(pubkyRepo).apply {
            verify(pubkyRepo).saveProfile(any(), any(), any(), eq(listOf("Bitcoin")), any())
            verify(pubkyRepo).saveProfile(any(), any(), any(), eq(emptyList()), any())
        }
        assertEquals(emptyList(), profileFlow.value?.tags)
    }

    @Test
    fun `double tap removal does not remove a neighboring tag`() = test {
        val profileFlow = MutableStateFlow<PubkyProfile?>(
            createProfile(tags = listOf("Founder", "Bitcoin")),
        )
        val sut = createSut(profileFlow = profileFlow)
        whenever(pubkyRepo.saveProfile(any(), any(), any(), any(), any())).doSuspendableAnswer {
            val tags = it.getArgument<List<String>>(3)
            profileFlow.value = requireNotNull(profileFlow.value).copy(tags = tags)
            Result.success(Unit)
        }
        advanceUntilIdle()

        sut.removeTag("Founder")
        sut.removeTag("Founder")
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).saveProfile(any(), any(), any(), eq(listOf("Bitcoin")), any())
        assertEquals(listOf("Bitcoin"), profileFlow.value?.tags)
    }

    @Test
    fun `failed tag save keeps the add sheet open for retry`() = test {
        val sut = createSut(createProfile())
        whenever(pubkyRepo.saveProfile(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(ProfileTestAppError("save failed")))
        advanceUntilIdle()

        sut.uiState.test {
            var state = awaitItem()
            while (state.profile == null) state = awaitItem()
            sut.showAddTagSheet()
            assertTrue(awaitItem().showAddTagSheet)

            sut.addTag("Bitcoin")
            advanceUntilIdle()

            assertTrue(sut.uiState.value.showAddTagSheet)
        }
    }

    private fun createSut(
        profile: PubkyProfile? = null,
        profileFlow: MutableStateFlow<PubkyProfile?> = MutableStateFlow(profile),
        publicKey: String? = "pubkyalice",
        isLoading: Boolean = false,
        cachedProfile: PubkyCachedProfile? = null,
    ): ProfileViewModel {
        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(pubkyRepo.profile).thenReturn(profileFlow)
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(publicKey))
        whenever(pubkyRepo.isLoadingProfile).thenReturn(MutableStateFlow(isLoading))
        whenever(pubkyRepo.cachedProfile).thenReturn(MutableStateFlow(cachedProfile))
        whenever { pubkyRepo.loadProfile() }.thenReturn(Unit)
        whenever { pubkyRepo.signOut() }.thenReturn(Result.success(Unit))
        whenever { pubkyRepo.saveProfile(any(), any(), any(), any(), any()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.removePublishedEndpointsForCleanup(any()) }
            .thenReturn(Result.success(Unit))
        whenever { privatePaykitRepo.closeAndClear() }.thenReturn(Result.success(Unit))

        return ProfileViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            privatePaykitRepo = privatePaykitRepo,
        )
    }

    private fun createCachedProfile(publicKey: String) = PubkyCachedProfile(
        publicKey = publicKey,
        name = "Alice",
        imageUri = "pubky://avatar",
    )

    private fun createProfile(tags: List<String> = listOf("Founder")) = PubkyProfile(
        publicKey = "pubkyalice",
        name = "Alice",
        bio = "Builder",
        imageUrl = "https://example.com/avatar.png",
        links = listOf(PubkyProfileLink("Website", "https://example.com")),
        tags = tags,
        status = null,
    )
}

private class ProfileTestAppError(message: String) : AppError(message)
