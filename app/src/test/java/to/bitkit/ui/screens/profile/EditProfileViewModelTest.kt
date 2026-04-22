package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()

    @Test
    fun `updateLinkUrl should update existing profile link`() = test {
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(createProfile()))
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(TEST_PUBLIC_KEY))

        val sut = EditProfileViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
        )
        advanceUntilIdle()

        sut.updateLinkUrl(0, "https://updated.example.com")

        assertEquals("https://updated.example.com", sut.uiState.value.links.first().url)
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

    companion object {
        private const val TEST_PUBLIC_KEY = "pubkyalice"
    }
}
