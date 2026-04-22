package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AddContactViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()

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
    fun `successful fetch should populate profile`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals(profile, sut.uiState.value.fetchedProfile)
        assertNull(sut.uiState.value.error)
    }

    private fun createSut(publicKey: String = TEST_PUBLIC_KEY): AddContactViewModel {
        return AddContactViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            savedStateHandle = SavedStateHandle(mapOf("publicKey" to publicKey)),
        )
    }

    companion object {
        private const val TEST_PUBLIC_KEY = "pubkytest-contact"
    }
}
