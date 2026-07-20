package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.usecases.RefreshContactPaykitReceiversUseCase
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AddContactViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val publicPaykitRepo: PublicPaykitRepo = mock()
    private val refreshContactPaykitReceivers = mock<RefreshContactPaykitReceiversUseCase>()

    @Test
    fun `self add failure should show dedicated error`() = test {
        whenever(context.getString(R.string.contacts__add_error_self)).thenReturn("self error")
        whenever(pubkyRepo.fetchContactProfile(any()))
            .thenReturn(Result.failure(PubkyContactError.CannotAddSelf))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals("self error", sut.uiState.value.error)
        assertNull(sut.uiState.value.fetchedProfile)
    }

    @Test
    fun `invalid format failure should show dedicated error`() = test {
        whenever(context.getString(R.string.contacts__add_error_invalid_key)).thenReturn("invalid key")
        whenever(pubkyRepo.fetchContactProfile(any()))
            .thenReturn(Result.failure(PubkyContactError.InvalidFormat))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals("invalid key", sut.uiState.value.error)
        assertNull(sut.uiState.value.fetchedProfile)
    }

    @Test
    fun `existing contact failure should show dedicated error`() = test {
        whenever(context.getString(R.string.contacts__add_error_existing)).thenReturn("existing contact")
        whenever(pubkyRepo.fetchContactProfile(any()))
            .thenReturn(Result.failure(PubkyContactError.AlreadyExists))
        whenever { refreshContactPaykitReceivers(TEST_PUBLIC_KEY) }.thenReturn(Result.success(Unit))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals("existing contact", sut.uiState.value.error)
        assertNull(sut.uiState.value.fetchedProfile)
        verify(refreshContactPaykitReceivers).invoke(TEST_PUBLIC_KEY)
    }

    @Test
    fun `successful fetch should populate profile`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).thenReturn(Result.success(false))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals(profile, sut.uiState.value.fetchedProfile)
        assertNull(sut.uiState.value.error)
    }

    @Test
    fun `saving emits saved contact key`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).thenReturn(Result.success(false))
        whenever { pubkyRepo.addContact(TEST_PUBLIC_KEY, profile) }.thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.saveContact()
            advanceUntilIdle()

            assertEquals(AddContactEffect.ContactSaved(TEST_PUBLIC_KEY), awaitItem())
        }
    }

    private fun createSut(publicKey: String = TEST_PUBLIC_KEY): AddContactViewModel {
        return AddContactViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            publicPaykitRepo = publicPaykitRepo,
            refreshContactPaykitReceivers = refreshContactPaykitReceivers,
            savedStateHandle = SavedStateHandle(mapOf("publicKey" to publicKey)),
        )
    }

    companion object {
        private const val TEST_PUBLIC_KEY = "pubkytest-contact"
    }
}
