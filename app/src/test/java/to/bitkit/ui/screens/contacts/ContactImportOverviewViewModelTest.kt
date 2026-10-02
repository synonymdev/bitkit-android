package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.forEachCase
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactImportOverviewViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val isImportingContacts = MutableStateFlow(false)
    private val contactImportVersion = MutableStateFlow(0L)
    private val pendingImportProfile = MutableStateFlow<PubkyProfile?>(null)
    private val pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())

    @Test
    fun `pending import blocks duplicate requests and clears progress after cancellation`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"))
        stubPendingImport(createProfile(publicKey = "pubkyself"), contacts)
        val pending = CompletableDeferred<Result<Unit>>()
        whenever(pubkyRepo.importContacts(contacts)).doSuspendableAnswer { pending.await() }
        val sut = createSut()
        advanceUntilIdle()

        sut.importAll()
        sut.importAll()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isImporting)
        verify(pubkyRepo).importContacts(contacts)

        pending.cancel()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isImporting)
    }

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
    fun `an import started from select completes the overview only once it succeeds`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"), createProfile(publicKey = "pubkybob"))
        listOf(
            ImportEnd("succeeds", succeeds = true, clearsPending = true),
            ImportEnd("fails", succeeds = false, clearsPending = false),
            ImportEnd("pending cleared without a success", succeeds = false, clearsPending = true),
        ).forEachCase({ it.case }) { (case, succeeds, clearsPending) ->
            reset(pubkyRepo)
            isImportingContacts.value = false
            stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
            val sut = createSut()
            advanceUntilIdle()
            isImportingContacts.value = true
            advanceUntilIdle()

            if (clearsPending) {
                pendingImportProfile.value = null
                pendingImportContacts.value = emptyList()
            }
            if (succeeds) contactImportVersion.value++
            isImportingContacts.value = false
            advanceUntilIdle()

            assertFalse(sut.uiState.value.isImporting, case)
            assertEquals(succeeds, sut.uiState.value.shouldRedirectToPayContacts, case)
            sut.viewModelScope.cancel()
        }
    }

    @Test
    fun `back during import all keeps the pending import and never opens pay contacts`() = test {
        val contacts = listOf(createProfile(publicKey = "pubkyalice"), createProfile(publicKey = "pubkybob"))
        whenever(context.getString(any())).thenReturn("Error")
        listOf(
            "fails" to Result.failure(AppError("Storage unavailable")),
            "succeeds" to Result.success(Unit),
        ).forEachCase({ it.first }) { (case, result) ->
            reset(pubkyRepo)
            isImportingContacts.value = false
            stubPendingImport(profile = createProfile(publicKey = "pubkyself"), contacts = contacts)
            whenever(pubkyRepo.clearPendingImport()).thenAnswer {
                pendingImportProfile.value = null
                pendingImportContacts.value = emptyList()
            }
            val save = CompletableDeferred<Result<Unit>>()
            whenever(pubkyRepo.importContacts(contacts)).doSuspendableAnswer {
                isImportingContacts.value = true
                save.await().also {
                    if (it.isSuccess) contactImportVersion.value++
                    isImportingContacts.value = false
                }
            }
            val sut = createSut()
            val effects = mutableListOf<ContactImportOverviewEffect>()
            val effectsJob = launch { sut.effects.collect { effects.add(it) } }
            advanceUntilIdle()

            sut.importAll()
            advanceUntilIdle()
            sut.onBackClick()
            advanceUntilIdle()
            assertEquals(listOf<ContactImportOverviewEffect>(ContactImportOverviewEffect.NavigateBack), effects, case)
            verify(pubkyRepo).discardPendingImport()
            verify(pubkyRepo, never()).clearPendingImport()

            save.complete(result)
            advanceUntilIdle()

            assertFalse(sut.uiState.value.isImporting, case)
            assertFalse(sut.uiState.value.shouldRedirectToPayContacts, case)
            effectsJob.cancel()
            sut.viewModelScope.cancel()
        }
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
    fun `onBackClick discards pending import and navigates back`() = test {
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

        verify(pubkyRepo).discardPendingImport()
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
        whenever(pubkyRepo.contactImportVersion).thenReturn(contactImportVersion)
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

    private data class ImportEnd(val case: String, val succeeds: Boolean, val clearsPending: Boolean)
}
