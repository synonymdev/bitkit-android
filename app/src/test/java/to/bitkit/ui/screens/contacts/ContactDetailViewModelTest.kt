package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelTest : BaseUnitTest() {
    companion object {
        private const val TEST_PUBLIC_KEY = "pubkytest-contact"
    }

    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val privatePaykitRepo: PrivatePaykitRepo = mock()

    @Test
    fun `deleting contact emits deleted effect`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(pubkyRepo.removeContact(TEST_PUBLIC_KEY)).thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.showDeleteConfirmation()
            assertTrue(sut.uiState.value.showDeleteDialog)

            sut.deleteContact()
            advanceUntilIdle()

            verify(pubkyRepo).removeContact(TEST_PUBLIC_KEY)
            assertFalse(sut.uiState.value.showDeleteDialog)
            assertEquals(ContactDetailEffect.ContactDeleted, awaitItem())
        }
    }

    @Test
    fun `failed contact deletion restores loading state`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        whenever(pubkyRepo.removeContact(TEST_PUBLIC_KEY))
            .thenReturn(Result.failure(ContactDetailTestError("delete failed")))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.showDeleteConfirmation()
            sut.deleteContact()
            advanceUntilIdle()

            verify(pubkyRepo).removeContact(TEST_PUBLIC_KEY)
            assertFalse(sut.uiState.value.showDeleteDialog)
            assertFalse(sut.uiState.value.isLoading)
            expectNoEvents()
        }
    }

    private fun createSut() = ContactDetailViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepo,
        savedStateHandle = SavedStateHandle(mapOf("publicKey" to TEST_PUBLIC_KEY)),
    )

    private fun createContact() = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
}

private class ContactDetailTestError(message: String) : AppError(message)
