package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyChoiceViewModelTest : BaseUnitTest() {
    companion object {
        private const val RING_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val OTHER_RING_PUBKY = "1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
    }

    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val isAuthenticated = MutableStateFlow(false)
    private val pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())

    private lateinit var sut: PubkyChoiceViewModel

    @Before
    fun setUp() {
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization Failed")
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(pubkyRepo.isAuthenticated).thenReturn(isAuthenticated)
        whenever(pubkyRepo.pendingImportContacts).thenReturn(pendingImportContacts)
        whenever { pubkyRepo.ringIdentities() }.thenReturn(Result.success(persistentListOf()))
        whenever { pubkyRepo.prepareImport() }.thenReturn(Result.success(Unit))
        whenever { pubkyRepo.fetchDisplayProfile(any()) }.thenReturn(Result.success(null))
    }

    private fun createSut() {
        sut = PubkyChoiceViewModel(context = context, pubkyRepo = pubkyRepo)
    }

    @Test
    fun `ring identities are listed before their profiles resolve and update per row`() = test {
        val ringLookup = CompletableDeferred<Result<PubkyProfile?>>()
        val otherLookup = CompletableDeferred<Result<PubkyProfile?>>()
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(RING_PUBKY)).doSuspendableAnswer { ringLookup.await() }
        whenever(pubkyRepo.fetchDisplayProfile(OTHER_RING_PUBKY)).doSuspendableAnswer { otherLookup.await() }
        createSut()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertEquals(listOf("3rsd...w5xg", "1rsd...w5xy"), sut.uiState.value.identities.map { it.name })
        assertEquals(listOf("3RSD...W5XG", "1RSD...W5XY"), sut.uiState.value.identities.map { it.caption })

        val otherProfile = PubkyProfile.forDisplay(OTHER_RING_PUBKY, name = "Hal", imageUrl = null)
        otherLookup.complete(Result.success(otherProfile))
        advanceUntilIdle()
        assertEquals(listOf("3rsd...w5xg", "Hal"), sut.uiState.value.identities.map { it.name })

        val ringProfile = PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = "https://image")
        ringLookup.complete(Result.success(ringProfile))
        advanceUntilIdle()
        assertEquals(
            persistentListOf(
                RingIdentity(pubky = RING_PUBKY, profile = ringProfile),
                RingIdentity(pubky = OTHER_RING_PUBKY, profile = otherProfile),
            ),
            sut.uiState.value.identities,
        )
        assertEquals("https://image", sut.uiState.value.identities.first().imageUrl)
    }

    @Test
    fun `failed or mismatched profile lookups keep the truncated key and hand no profile to adoption`() = test {
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(RING_PUBKY))
            .thenReturn(Result.failure(PubkyChoiceTestAppError("lookup failed")))
        whenever(pubkyRepo.fetchDisplayProfile(OTHER_RING_PUBKY))
            .thenReturn(Result.success(PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = null)))
        whenever(pubkyRepo.adoptRingIdentity(any(), anyOrNull())).thenReturn(Result.success(false))
        createSut()
        advanceUntilIdle()

        assertEquals(listOf("3rsd...w5xg", "1rsd...w5xy"), sut.uiState.value.identities.map { it.name })
        assertTrue(sut.uiState.value.identities.all { it.profile == null })

        sut.onIdentityClick(OTHER_RING_PUBKY)
        advanceUntilIdle()

        verify(pubkyRepo).adoptRingIdentity(OTHER_RING_PUBKY, null)
    }

    @Test
    fun `onIdentityClick hands the row's resolved profile to adoption`() = test {
        val profile = PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = null)
        whenever(pubkyRepo.ringIdentities()).thenReturn(Result.success(persistentListOf(RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(RING_PUBKY)).thenReturn(Result.success(profile))
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY, profile)).thenReturn(Result.success(true))
        createSut()
        advanceUntilIdle()

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        verify(pubkyRepo).adoptRingIdentity(RING_PUBKY, profile)
    }

    @Test
    fun `onIdentityClick cancels pending profile lookups before adopting`() = test {
        val lookupCancelled = CompletableDeferred<Unit>()
        var lookupCancelledBeforeAdoption = false
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(OTHER_RING_PUBKY)).doSuspendableAnswer {
            try {
                awaitCancellation()
            } finally {
                lookupCancelled.complete(Unit)
            }
        }
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY, null)).doSuspendableAnswer {
            lookupCancelledBeforeAdoption = lookupCancelled.isCompleted
            Result.success(false)
        }
        createSut()
        advanceUntilIdle()
        assertFalse(lookupCancelled.isCompleted)

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertTrue(lookupCancelledBeforeAdoption)
        assertEquals(listOf("3rsd...w5xg", "1rsd...w5xy"), sut.uiState.value.identities.map { it.name })
    }

    @Test
    fun `onIdentityClick ignores further taps while an adoption is running`() = test {
        val finishAdoption = CompletableDeferred<Result<Boolean>>()
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY)))
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY, null)).doSuspendableAnswer { finishAdoption.await() }
        createSut()
        advanceUntilIdle()

        sut.onIdentityClick(RING_PUBKY)
        sut.onIdentityClick(OTHER_RING_PUBKY)
        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertEquals(RING_PUBKY, sut.uiState.value.adoptingPubky)
        assertEquals(2, sut.uiState.value.identities.size)
        finishAdoption.complete(Result.success(false))
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).adoptRingIdentity(any(), anyOrNull())
        assertNull(sut.uiState.value.adoptingPubky)
    }

    @Test
    fun `listing failure shows no identities`() = test {
        whenever(pubkyRepo.ringIdentities()).thenReturn(Result.failure(PubkyChoiceTestAppError("query failed")))
        createSut()

        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertTrue(sut.uiState.value.identities.isEmpty())
    }

    @Test
    fun `onIdentityClick continues to contact import when the adopted identity has follows`() = test {
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY)).thenReturn(Result.success(true))
        pendingImportContacts.value = listOf(PubkyProfile.placeholder("pubky$RING_PUBKY"))
        createSut()
        val effects = mutableListOf<PubkyChoiceEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertEquals(PubkyChoiceEffect.NavigateToContactImportOverview, effects.single())
        assertFalse(sut.uiState.value.navigateToProfile)
        assertNull(sut.uiState.value.adoptingPubky)
        effectsJob.cancel()
    }

    @Test
    fun `onIdentityClick continues to pay contacts when the adopted identity has no follows`() = test {
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY)).thenReturn(Result.success(true))
        createSut()
        val effects = mutableListOf<PubkyChoiceEffect>()
        val toasts = mutableListOf<Toast>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertEquals(PubkyChoiceEffect.NavigateToPayContacts, effects.single())
        assertFalse(sut.uiState.value.navigateToProfile)
        assertNull(sut.uiState.value.adoptingPubky)
        assertTrue(toasts.isEmpty())
        effectsJob.cancel()
        toastJob.cancel()
    }

    @Test
    fun `onIdentityClick toasts and continues to pay contacts when the follows lookup fails`() = test {
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY)).thenReturn(Result.success(true))
        whenever(pubkyRepo.prepareImport()).thenReturn(Result.failure(PubkyChoiceTestAppError("follows failed")))
        createSut()
        val effects = mutableListOf<PubkyChoiceEffect>()
        val toasts = mutableListOf<Toast>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertEquals(PubkyChoiceEffect.NavigateToPayContacts, effects.single())
        assertNull(sut.uiState.value.adoptingPubky)
        val toast = toasts.single()
        assertEquals(Toast.ToastType.ERROR, toast.type)
        assertEquals("Error", toast.title)
        assertEquals("follows failed", toast.description)
        effectsJob.cancel()
        toastJob.cancel()
    }

    @Test
    fun `onIdentityClick continues to profile creation when the adopted identity has no profile`() = test {
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY)).thenReturn(Result.success(false))
        createSut()
        val effects = mutableListOf<PubkyChoiceEffect>()
        val effectsJob = launch { sut.effects.collect { effects.add(it) } }

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertEquals(PubkyChoiceEffect.NavigateToCreateProfile, effects.single())
        assertFalse(sut.uiState.value.navigateToProfile)
        assertNull(sut.uiState.value.adoptingPubky)
        effectsJob.cancel()
    }

    @Test
    fun `onIdentityClick clears the adopting identity and toasts when adoption fails`() = test {
        whenever(pubkyRepo.adoptRingIdentity(RING_PUBKY))
            .thenReturn(Result.failure(PubkyChoiceTestAppError("adopt failed")))
        createSut()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertNull(sut.uiState.value.adoptingPubky)
        assertFalse(sut.uiState.value.navigateToProfile)
        assertEquals(1, toasts.size)
        toastJob.cancel()
    }

    @Test
    fun `session restoration redirects to profile when already authenticated`() = test {
        isAuthenticated.value = true
        createSut()

        advanceUntilIdle()

        assertTrue(sut.uiState.value.navigateToProfile)
    }

    @Test
    fun `clearProfileNavigation clears profile redirect`() = test {
        createSut()
        isAuthenticated.value = true
        advanceUntilIdle()

        sut.clearProfileNavigation()

        assertFalse(sut.uiState.value.navigateToProfile)
    }
}

private class PubkyChoiceTestAppError(message: String) : AppError(message)
