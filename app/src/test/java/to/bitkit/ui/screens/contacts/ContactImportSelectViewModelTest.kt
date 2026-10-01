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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactImportSelectViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val isImportingContacts = MutableStateFlow(false)

    @Test
    fun `missing pending import redirects to pay contacts`() = test {
        stubPendingImport(profile = null, contacts = emptyList())
        val sut = createSut()

        advanceUntilIdle()

        assertTrue(sut.uiState.value.shouldRedirectToPayContacts)
    }

    @Test
    fun `importSelected clears pending import when nothing is selected`() = test {
        stubPendingImport(
            profile = createProfile(publicKey = "pubkyself"),
            contacts = listOf(createProfile(publicKey = "pubkyalice")),
        )
        val sut = createSut()

        val effects = mutableListOf<ContactImportSelectEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        sut.selectNone()
        sut.importSelected()
        advanceUntilIdle()

        verify(pubkyRepo).clearPendingImport()
        assertEquals(ContactImportSelectEffect.ImportComplete, effects.last())

        effectsJob.cancel()
    }

    @Test
    fun `importSelected success clears pending import and completes`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"), createProfile(publicKey = "pubkybob"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        whenever(pubkyRepo.importContacts(contacts)).thenReturn(Result.success(Unit))
        val sut = createSut()

        val effects = mutableListOf<ContactImportSelectEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        sut.importSelected()
        advanceUntilIdle()

        verify(pubkyRepo).clearPendingImport()
        assertEquals(ContactImportSelectEffect.ImportComplete, effects.last())

        effectsJob.cancel()
    }

    @Test
    fun `continue is ignored while an import runs`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        isImportingContacts.value = true
        val sut = createSut()
        val effects = mutableListOf<ContactImportSelectEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isImporting)

        sut.importSelected()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        verify(pubkyRepo, never()).importContacts(any())
        verify(pubkyRepo, never()).clearPendingImport()

        effectsJob.cancel()
    }

    private fun createSut() = ContactImportSelectViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
    )

    private fun stubPendingImport(profile: PubkyProfile?, contacts: List<PubkyProfile>) {
        whenever(pubkyRepo.pendingImportProfile).thenReturn(MutableStateFlow(profile))
        whenever(pubkyRepo.pendingImportContacts).thenReturn(MutableStateFlow(contacts))
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
