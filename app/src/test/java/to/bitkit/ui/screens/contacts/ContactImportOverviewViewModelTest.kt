package to.bitkit.ui.screens.contacts

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactImportOverviewViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()

    @Test
    fun `missing pending import redirects to pay contacts`() = test {
        stubPendingImport(profile = null, contacts = emptyList())
        val sut = createSut()

        advanceUntilIdle()

        assertTrue(sut.uiState.value.shouldRedirectToPayContacts)
    }

    @Test
    fun `importAll clears pending import and completes`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"), createProfile(publicKey = "pubkybob"))
        stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
        whenever(pubkyRepo.importContacts(contacts.map { it.publicKey })).thenReturn(Result.success(Unit))
        val sut = createSut()

        val effects = mutableListOf<ContactImportOverviewEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        sut.importAll()
        advanceUntilIdle()

        verify(pubkyRepo).clearPendingImport()
        assertEquals(ContactImportOverviewEffect.ImportComplete, effects.last())

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
        whenever(pubkyRepo.pendingImportProfile).thenReturn(MutableStateFlow(profile))
        whenever(pubkyRepo.pendingImportContacts).thenReturn(MutableStateFlow(contacts))
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
