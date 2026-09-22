package to.bitkit.ui.screens.profile

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyChoiceViewModelTest : BaseUnitTest() {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val pubkyRepo: PubkyRepo = mock()
    private val isAuthenticated = MutableStateFlow(false)

    private lateinit var sut: PubkyChoiceViewModel

    @Before
    fun setUp() {
        whenever(pubkyRepo.isAuthenticated).thenReturn(isAuthenticated)
        whenever { pubkyRepo.ringIdentities() }.thenReturn(Result.success(persistentListOf()))
    }

    private fun createSut() {
        sut = PubkyChoiceViewModel(pubkyRepo = pubkyRepo)
    }

    @Test
    fun `ring identities are listed with their remote profile`() = test {
        whenever(pubkyRepo.ringIdentities()).thenReturn(Result.success(persistentListOf(RING_PUBKY)))
        whenever(pubkyRepo.fetchRemoteProfile(RING_PUBKY)).thenReturn(
            Result.success(PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = "https://image"))
        )
        createSut()

        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertEquals(
            persistentListOf(
                RingIdentity(
                    pubky = RING_PUBKY,
                    caption = "3RSD...W5XG",
                    name = "Satoshi",
                    imageUrl = "https://image",
                )
            ),
            sut.uiState.value.identities,
        )
    }

    @Test
    fun `listing failure shows no identities`() = test {
        whenever(pubkyRepo.ringIdentities()).thenReturn(Result.failure(RuntimeException("query failed")))
        createSut()

        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertTrue(sut.uiState.value.identities.isEmpty())
    }

    @Test
    fun `session restoration redirects to profile when already authenticated`() = test {
        isAuthenticated.value = true
        createSut()

        advanceUntilIdle()

        assertTrue(sut.uiState.value.navigateToProfile)
    }

    @Test
    fun `clearProfileNavigation clears profile redirect`() = test {
        createSut()
        isAuthenticated.value = true
        advanceUntilIdle()

        sut.clearProfileNavigation()

        assertFalse(sut.uiState.value.navigateToProfile)
    }
}
