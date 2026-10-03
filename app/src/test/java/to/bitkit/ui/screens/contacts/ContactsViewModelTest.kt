package to.bitkit.ui.screens.contacts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.usecases.RefreshContactPaykitLinkUseCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest : BaseUnitTest() {
    private val pubkyRepo: PubkyRepo = mock()
    private val refreshContactPaykitLink: RefreshContactPaykitLinkUseCase = mock()
    private val contacts = MutableStateFlow<List<PubkyProfile>>(emptyList())
    private val isLoadingContacts = MutableStateFlow(false)
    private val contactsLoadVersion = MutableStateFlow(0L)

    @Before
    fun setUp() {
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(pubkyRepo.isLoadingContacts).thenReturn(isLoadingContacts)
        whenever(pubkyRepo.contactsLoadVersion).thenReturn(contactsLoadVersion)
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.displayName).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.displayImageUri).thenReturn(MutableStateFlow(null))
    }

    @Test
    fun `full screen loading shows only until the saved records first load`() = test {
        val sut = ContactsViewModel(pubkyRepo, refreshContactPaykitLink)
        backgroundScope.launch { sut.uiState.collect {} }
        isLoadingContacts.value = true
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isLoading)
        assertFalse(sut.uiState.value.isEmpty)

        contactsLoadVersion.value = 1L
        isLoadingContacts.value = false
        advanceUntilIdle()
        isLoadingContacts.value = true
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertTrue(sut.uiState.value.isEmpty)

        contacts.value = listOf(PubkyProfile.placeholder("pubkyalice"))
        advanceUntilIdle()
        assertEquals(listOf("pubkyalice"), sut.uiState.value.contacts.map { it.publicKey })
    }
}
