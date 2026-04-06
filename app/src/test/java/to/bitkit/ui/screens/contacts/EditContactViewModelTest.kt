package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditContactViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()

    @Test
    fun `missing local contact triggers refresh path`() = test {
        val contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(pubkyRepo.loadContacts()).thenReturn(Unit)

        createSut()
        advanceUntilIdle()

        verify(pubkyRepo).loadContacts()
    }

    @Test
    fun `contact arriving from repo populates form`() = test {
        val contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(pubkyRepo.loadContacts()).thenReturn(Unit)
        val sut = createSut()

        advanceUntilIdle()
        contacts.value = listOf(createContact())
        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isMissing)
        assertEquals("Alice", state.name)
        assertEquals("Hello", state.bio)
        assertEquals("https://example.com/avatar.jpg", state.imageUrl)
        assertEquals(listOf("Website"), state.links.map { it.label })
        assertEquals(listOf("friend"), state.tags)
    }

    @Test
    fun `contact still missing after refresh produces missing state`() = test {
        val contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(pubkyRepo.loadContacts()).thenReturn(Unit)
        val sut = createSut()

        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isMissing)
        assertEquals("", state.name)
    }

    private fun createSut(publicKey: String = TEST_PUBLIC_KEY): EditContactViewModel {
        return EditContactViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            savedStateHandle = SavedStateHandle(mapOf("publicKey" to publicKey)),
        )
    }

    private fun createContact(publicKey: String = TEST_PUBLIC_KEY) = PubkyProfile(
        publicKey = publicKey,
        name = "Alice",
        bio = "Hello",
        imageUrl = "https://example.com/avatar.jpg",
        links = listOf(PubkyProfileLink("Website", "https://example.com")),
        tags = listOf("friend"),
        status = null,
    )

    companion object {
        private const val TEST_PUBLIC_KEY = "pubkytest-contact"
    }
}
