package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.usecases.RefreshContactPaykitLinkUseCase
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddContactViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val publicPaykitRepo: PublicPaykitRepo = mock()
    private val refreshContactPaykitLink = mock<RefreshContactPaykitLinkUseCase>()

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
        whenever { refreshContactPaykitLink(TEST_PUBLIC_KEY) }.thenReturn(Result.success(Unit))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals("existing contact", sut.uiState.value.error)
        assertNull(sut.uiState.value.fetchedProfile)
        verify(refreshContactPaykitLink).invoke(TEST_PUBLIC_KEY)
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
    fun `profile can be saved while public payment endpoint lookup is suspended`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        val endpoint = CompletableDeferred<Result<Boolean>>()
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).doSuspendableAnswer { endpoint.await() }
        whenever(pubkyRepo.addContact(TEST_PUBLIC_KEY, profile)).thenReturn(Result.success(Unit))
        whenever(refreshContactPaykitLink(TEST_PUBLIC_KEY)).thenReturn(Result.success(Unit))
        val sut = createSut()
        try {
            assertEquals(profile, sut.uiState.value.fetchedProfile)
            assertFalse(sut.uiState.value.isLoading)
            assertFalse(sut.uiState.value.hasPublicPaymentEndpoint)
            sut.effects.test {
                sut.saveContact()
                assertEquals(AddContactEffect.ContactSaved(TEST_PUBLIC_KEY), awaitItem())
                verify(pubkyRepo).addContact(TEST_PUBLIC_KEY, profile)
            }
        } finally {
            endpoint.complete(Result.success(false))
        }
    }

    @Test
    fun `late endpoint result cannot replace a newer contact preview`() = test {
        val oldProfile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        val newProfile = PubkyProfile.placeholder("pubky-new-contact")
        val endpoint = CompletableDeferred<Result<Boolean>>()
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(oldProfile))
        whenever(pubkyRepo.fetchContactProfile(newProfile.publicKey)).thenReturn(Result.success(newProfile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            withContext(NonCancellable) { endpoint.await() }
        }
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(newProfile.publicKey)).thenReturn(Result.success(false))
        val sut = createSut()
        sut.fetchProfile(newProfile.publicKey)
        endpoint.complete(Result.success(true))
        advanceUntilIdle()

        assertEquals(newProfile, sut.uiState.value.fetchedProfile)
        assertFalse(sut.uiState.value.hasPublicPaymentEndpoint)
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `leaving preview ignores a non cancellable endpoint response`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        val endpoint = CompletableDeferred<Result<Boolean>>()
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            withContext(NonCancellable) { endpoint.await() }
        }
        val sut = createSut()
        val store = ViewModelStore().apply { put("preview", sut) }
        store.clear()
        endpoint.complete(Result.success(true))
        advanceUntilIdle()

        assertEquals(profile, sut.uiState.value.fetchedProfile)
        assertFalse(sut.uiState.value.hasPublicPaymentEndpoint)
    }

    @Test
    fun `endpoint completion cannot clear an in flight save`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        val endpoint = CompletableDeferred<Result<Boolean>>()
        val save = CompletableDeferred<Result<Unit>>()
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).doSuspendableAnswer { endpoint.await() }
        whenever(pubkyRepo.addContact(TEST_PUBLIC_KEY, profile)).doSuspendableAnswer { save.await() }
        whenever(refreshContactPaykitLink(TEST_PUBLIC_KEY)).thenReturn(Result.success(Unit))
        val sut = createSut()
        sut.effects.test {
            sut.saveContact()
            sut.saveContact()
            endpoint.complete(Result.success(true))
            advanceUntilIdle()
            try {
                assertTrue(sut.uiState.value.isLoading)
                assertTrue(sut.uiState.value.hasPublicPaymentEndpoint)
            } finally {
                save.complete(Result.success(Unit))
            }
            assertEquals(AddContactEffect.ContactSaved(TEST_PUBLIC_KEY), awaitItem())
            verify(pubkyRepo).addContact(TEST_PUBLIC_KEY, profile)
        }
    }

    @Test
    fun `failed endpoint lookup keeps the loaded profile available to save`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY))
            .thenReturn(Result.failure(AppError("Pubky unavailable")))

        val sut = createSut()
        advanceUntilIdle()

        assertEquals(profile, sut.uiState.value.fetchedProfile)
        assertFalse(sut.uiState.value.isLoading)
        assertFalse(sut.uiState.value.hasPublicPaymentEndpoint)
        assertNull(sut.uiState.value.error)
    }

    @Test
    fun `saving emits saved contact key`() = test {
        val profile = PubkyProfile.placeholder(TEST_PUBLIC_KEY)
        whenever(pubkyRepo.fetchContactProfile(TEST_PUBLIC_KEY)).thenReturn(Result.success(profile))
        whenever(publicPaykitRepo.hasPayablePublicEndpoint(TEST_PUBLIC_KEY)).thenReturn(Result.success(false))
        whenever { pubkyRepo.addContact(TEST_PUBLIC_KEY, profile) }.thenReturn(Result.success(Unit))
        whenever { refreshContactPaykitLink(TEST_PUBLIC_KEY) }.thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        sut.effects.test {
            sut.saveContact()
            advanceUntilIdle()

            assertEquals(AddContactEffect.ContactSaved(TEST_PUBLIC_KEY), awaitItem())
            verify(refreshContactPaykitLink).invoke(TEST_PUBLIC_KEY)
        }
    }

    private fun createSut(publicKey: String = TEST_PUBLIC_KEY): AddContactViewModel {
        return AddContactViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            publicPaykitRepo = publicPaykitRepo,
            refreshContactPaykitLink = refreshContactPaykitLink,
            savedStateHandle = SavedStateHandle(mapOf("publicKey" to publicKey)),
        )
    }

    companion object {
        private const val TEST_PUBLIC_KEY = "pubkytest-contact"
    }
}
