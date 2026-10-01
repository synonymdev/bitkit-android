package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
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
    fun `updateLinkUrl should update existing contact link`() = test {
        val contacts = MutableStateFlow<List<PubkyProfile>>(listOf(createContact()))
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(pubkyRepo.loadContacts()).thenReturn(Unit)
        val sut = createSut()

        advanceUntilIdle()
        sut.updateLinkUrl(0, "https://updated.example.com")

        assertEquals("https://updated.example.com", sut.uiState.value.links.first().url)
    }

    @Test
    fun `a profile update of this or another contact keeps unsaved edits`() = test {
        val alice = createContact()
        val bob = createContact().copy(publicKey = "pubkyother", name = "Bob")
        listOf(
            Triple("same contact", listOf(alice), listOf(alice.copy(name = "Alice Resolved", bio = "Updated"))),
            Triple("another contact", listOf(alice, bob), listOf(alice, bob.copy(name = "Bob Resolved"))),
        ).forEach { (case, initial, updated) ->
            val contacts = MutableStateFlow(initial)
            whenever(pubkyRepo.contacts).thenReturn(contacts)
            val sut = createSut()
            advanceUntilIdle()

            sut.onNameChange("Alice Edited")
            contacts.value = updated
            advanceUntilIdle()

            assertEquals("Alice Edited", sut.uiState.value.name, case)
            assertEquals("Hello", sut.uiState.value.bio, case)
        }
    }

    @Test
    fun `form waits for the profile of a contact still showing its label`() = test {
        val labelOnly = PubkyProfile.forDisplay(TEST_PUBLIC_KEY, "Alice", imageUrl = null)
        val contacts = MutableStateFlow(listOf(labelOnly))
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        val lookup = CompletableDeferred<Unit>()
        whenever(pubkyRepo.resolvePendingContactProfile(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            lookup.await()
            contacts.value = listOf(createContact())
        }
        val sut = createSut()
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isLoading)
        lookup.complete(Unit)
        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Hello", state.bio)
        assertEquals("https://example.com/avatar.jpg", state.imageUrl)
        assertEquals(listOf("Website"), state.links.map { it.label })
    }

    @Test
    fun `a contact whose profile lookup failed shows its label in the form and saves`() = test {
        whenever(context.getString(any())).thenReturn("")
        val labelOnly = PubkyProfile.forDisplay(TEST_PUBLIC_KEY, "Alice", imageUrl = null)
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(labelOnly)))
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isMissing)
        assertEquals("Alice", sut.uiState.value.name)
        sut.onBioChange("Met at a meetup")
        sut.save()
        advanceUntilIdle()

        verify(pubkyRepo).updateContact(TEST_PUBLIC_KEY, "Alice", "Met at a meetup", null, emptyList(), emptyList())
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
