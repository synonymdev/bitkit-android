package to.bitkit.ui.screens.contacts

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactImportOverviewViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val isImportingContacts = MutableStateFlow(false)
    private val pendingImportProfile = MutableStateFlow<PubkyProfile?>(null)
    private val pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())

    @Test
    fun `missing pending import redirects to pay contacts`() = test {
        stubPendingImport(profile = null, contacts = emptyList())
        val sut = createSut()

        advanceUntilIdle()

        assertTrue(sut.uiState.value.shouldRedirectToPayContacts)
    }

    @Test
    fun `importAll completes once the import succeeds`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"), createProfile(publicKey = "pubkybob"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        whenever(pubkyRepo.importContacts(contacts)).thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.shouldRedirectToPayContacts)

        sut.importAll()
        advanceUntilIdle()

        verify(pubkyRepo).importContacts(contacts)
        assertTrue(sut.uiState.value.shouldRedirectToPayContacts)
    }

    @Test
    fun `an import started from select completes the overview once it succeeds`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"), createProfile(publicKey = "pubkybob"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        val sut = createSut()
        advanceUntilIdle()
        isImportingContacts.value = true
        advanceUntilIdle()

        pendingImportProfile.value = null
        pendingImportContacts.value = emptyList()
        isImportingContacts.value = false
        advanceUntilIdle()

        assertTrue(sut.uiState.value.shouldRedirectToPayContacts)
    }

    @Test
    fun `an import started from select that fails leaves the overview open`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        val sut = createSut()
        advanceUntilIdle()
        isImportingContacts.value = true
        advanceUntilIdle()

        isImportingContacts.value = false
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isImporting)
        assertFalse(sut.uiState.value.shouldRedirectToPayContacts)
    }

    @Test
    fun `select and import all are ignored while an import runs`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        isImportingContacts.value = true
        val sut = createSut()
        val effects = mutableListOf<ContactImportOverviewEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isImporting)

        sut.navigateToSelect()
        sut.importAll()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        verify(pubkyRepo, never()).importContacts(any())

        isImportingContacts.value = false
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isImporting)
        sut.navigateToSelect()
        advanceUntilIdle()
        assertEquals(listOf<ContactImportOverviewEffect>(ContactImportOverviewEffect.NavigateToSelect), effects)

        effectsJob.cancel()
    }

    @Test
    fun `onBackClick clears pending import and navigates back`() = test {
        stubPendingImport(
            profile = createProfile(publicKey = "pubkyself"),
            contacts = listOf(createProfile(publicKey = "pubkyalice")),
        )
        val sut = createSut()

        val effects = mutableListOf<ContactImportOverviewEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        sut.onBackClick()
        advanceUntilIdle()

        verify(pubkyRepo).clearPendingImport()
        assertEquals(ContactImportOverviewEffect.NavigateBack, effects.last())

        effectsJob.cancel()
    }

    private fun createSut() = ContactImportOverviewViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
    )

    private fun stubPendingImport(profile: PubkyProfile?, contacts: List<PubkyProfile>) {
        pendingImportProfile.value = profile
        pendingImportContacts.value = contacts
        whenever(pubkyRepo.pendingImportProfile).thenReturn(pendingImportProfile)
        whenever(pubkyRepo.pendingImportContacts).thenReturn(pendingImportContacts)
        whenever(pubkyRepo.isImportingContacts).thenReturn(isImportingContacts)
    }

    private fun createProfile(publicKey: String) = PubkyProfile(
        publicKey = publicKey,
        name = publicKey,
        bio = "",
        imageUrl = null,
        links = emptyList(),
        tags = emptyList(),
        status = null,
    )
}
