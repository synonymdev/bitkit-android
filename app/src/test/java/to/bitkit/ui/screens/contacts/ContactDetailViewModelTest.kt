package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
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

    @Test
    fun `adding a tag persists the updated contact and closes the sheet`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact(tags = listOf("Friend")))))
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.showAddTagSheet()
        sut.addTag("Bitcoin")
        advanceUntilIdle()

        assertEquals(listOf("Friend", "Bitcoin"), sut.uiState.value.tags)
        assertFalse(sut.uiState.value.showAddTagSheet)
        verify(pubkyRepo).updateContact(
            publicKey = eq(TEST_PUBLIC_KEY),
            name = any(),
            bio = any(),
            imageUrl = anyOrNull(),
            links = any(),
            tags = eq(listOf("Friend", "Bitcoin")),
        )
    }

    @Test
    fun `removing a tag persists the remaining contact tags`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(
            MutableStateFlow(listOf(createContact(tags = listOf("Friend", "Bitcoin")))),
        )
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.removeTag("Friend")
        advanceUntilIdle()

        assertEquals(listOf("Bitcoin"), sut.uiState.value.tags)
        verify(pubkyRepo).updateContact(
            publicKey = eq(TEST_PUBLIC_KEY),
            name = any(),
            bio = any(),
            imageUrl = anyOrNull(),
            links = any(),
            tags = eq(listOf("Bitcoin")),
        )
    }

    @Test
    fun `rapid tag removals use stable tag identity`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(
            MutableStateFlow(listOf(createContact(tags = listOf("Friend", "Bitcoin")))),
        )
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.removeTag("Friend")
        sut.removeTag("Bitcoin")
        advanceUntilIdle()

        inOrder(pubkyRepo).apply {
            verify(pubkyRepo).updateContact(any(), any(), any(), anyOrNull(), any(), eq(listOf("Bitcoin")))
            verify(pubkyRepo).updateContact(any(), any(), any(), anyOrNull(), any(), eq(emptyList()))
        }
        assertEquals(emptyList(), sut.uiState.value.tags)
    }

    @Test
    fun `double tag removal does not remove a neighboring tag`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(
            MutableStateFlow(listOf(createContact(tags = listOf("Friend", "Bitcoin")))),
        )
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.removeTag("Friend")
        sut.removeTag("Friend")
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).updateContact(
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            eq(listOf("Bitcoin")),
        )
        assertEquals(listOf("Bitcoin"), sut.uiState.value.tags)
    }

    @Test
    fun `failed tag addition stays open and can be retried`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact(tags = listOf("Friend")))))
        whenever(pubkyRepo.updateContact(any(), any(), any(), anyOrNull(), any(), any())).thenReturn(
            Result.failure(ContactDetailTestError("save failed")),
            Result.success(Unit),
        )
        val sut = createSut()
        advanceUntilIdle()
        sut.showAddTagSheet()

        sut.addTag("Bitcoin")
        advanceUntilIdle()

        assertTrue(sut.uiState.value.showAddTagSheet)
        assertEquals(listOf("Friend"), sut.uiState.value.tags)

        sut.addTag("Bitcoin")
        advanceUntilIdle()

        assertFalse(sut.uiState.value.showAddTagSheet)
        assertEquals(listOf("Friend", "Bitcoin"), sut.uiState.value.tags)
        verify(pubkyRepo, times(2)).updateContact(any(), any(), any(), anyOrNull(), any(), any())
    }

    private fun createSut() = ContactDetailViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepo,
        savedStateHandle = SavedStateHandle(mapOf("publicKey" to TEST_PUBLIC_KEY)),
    )

    private fun createContact(tags: List<String> = emptyList()) =
        PubkyProfile.placeholder(TEST_PUBLIC_KEY).copy(tags = tags)
}

private class ContactDetailTestError(message: String) : AppError(message)
