package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySignIn
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/** A tag change belongs to the Pubky sign-in it was made in, and stops quietly once that sign-in has ended. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelSignInTest : BaseUnitTest() {
    companion object {
        private const val TEST_PUBLIC_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val paykitPaymentRequestRepo: PaykitPaymentRequestRepo = mock()
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochSeconds(1_800_000_000)
    }
    private val signIn = PubkySignIn(publicKey = "pubkyowner", generation = 0)

    @Test
    fun `a tag change whose sign-in ends while the contact loads does not look it up or save`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        val contactLoad = CompletableDeferred<Unit>()
        whenever(pubkyRepo.resolvePendingContactProfile(TEST_PUBLIC_KEY)).doSuspendableAnswer { contactLoad.await() }
        val sut = createSut()
        val toasts = collectToasts()
        advanceUntilIdle()

        sut.addTag("Bitcoin")
        whenever(pubkyRepo.isCurrent(signIn)).thenReturn(false)
        contactLoad.complete(Unit)
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).resolvePendingContactProfile(TEST_PUBLIC_KEY)
        verify(pubkyRepo, never()).updateContact(any(), any(), any(), any(), anyOrNull(), any(), any())
        assertEquals(emptyList(), sut.uiState.value.tags)
        assertEquals(emptyList(), toasts)
    }

    @Test
    fun `a tag change whose sign-in ends during the profile lookup saves nothing`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact())))
        val lookup = CompletableDeferred<Unit>()
        var lookups = 0
        whenever(pubkyRepo.resolvePendingContactProfile(TEST_PUBLIC_KEY)).doSuspendableAnswer {
            lookups++
            if (lookups > 1) lookup.await()
        }
        val sut = createSut()
        val toasts = collectToasts()
        advanceUntilIdle()

        sut.addTag("Bitcoin")
        advanceUntilIdle()
        assertEquals(2, lookups)
        whenever(pubkyRepo.isCurrent(signIn)).thenReturn(false)
        lookup.complete(Unit)
        advanceUntilIdle()

        verify(pubkyRepo, never()).updateContact(any(), any(), any(), any(), anyOrNull(), any(), any())
        assertEquals(emptyList(), sut.uiState.value.tags)
        assertEquals(emptyList(), toasts)
    }

    @Test
    fun `a tag save that fails after its sign-in ended shows no toast`() = test {
        whenever(context.getString(any())).thenReturn("")
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(listOf(createContact(tags = listOf("Friend")))))
        val sut = createSut()
        var isSignInCurrent = true
        whenever(pubkyRepo.isCurrent(signIn)).thenAnswer { isSignInCurrent }
        whenever(pubkyRepo.updateContact(any(), any(), any(), any(), anyOrNull(), any(), any())).doSuspendableAnswer {
            isSignInCurrent = false
            Result.failure<Unit>(SignInTestError("signed out"))
        }
        val toasts = collectToasts()
        advanceUntilIdle()
        sut.showAddTagSheet()

        sut.addTag("Bitcoin")
        advanceUntilIdle()

        verify(pubkyRepo).updateContact(any(), any(), any(), any(), anyOrNull(), any(), any())
        assertEquals(listOf("Friend"), sut.uiState.value.tags)
        assertEquals(emptyList(), toasts)
    }

    private fun createSut(): ContactDetailViewModel {
        whenever(pubkyRepo.currentSignIn()).thenReturn(signIn)
        whenever(pubkyRepo.isCurrent(signIn)).thenReturn(true)
        whenever(paykitPaymentRequestRepo.eligibleTargets)
            .thenReturn(MutableStateFlow<List<PaykitPaymentRequestTarget>>(emptyList()))
        return ContactDetailViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            privatePaykitRepo = mock<PrivatePaykitRepo>(),
            paykitPaymentRequestRepo = paykitPaymentRequestRepo,
            clock = clock,
            savedStateHandle = SavedStateHandle(mapOf("publicKey" to TEST_PUBLIC_KEY)),
        )
    }

    private fun TestScope.collectToasts(): List<Toast> {
        val toasts = mutableListOf<Toast>()
        backgroundScope.launch { ToastEventBus.events.collect { toasts += it } }
        return toasts
    }

    private fun createContact(tags: List<String> = emptyList()) =
        PubkyProfile.placeholder(TEST_PUBLIC_KEY).copy(tags = tags)
}

private class SignInTestError(message: String) : AppError(message)
