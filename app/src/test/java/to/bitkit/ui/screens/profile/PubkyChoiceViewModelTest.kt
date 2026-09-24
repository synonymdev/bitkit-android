package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
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
    }

    private fun createSut() {
        sut = PubkyChoiceViewModel(context = context, pubkyRepo = pubkyRepo)
    }

    @Test
    fun `ring identities are listed with their remote profile`() = test {
        whenever(pubkyRepo.ringIdentities()).thenReturn(Result.success(persistentListOf(RING_PUBKY)))
        whenever(pubkyRepo.fetchRemoteProfile(RING_PUBKY)).thenReturn(
            Result.success(PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = "https://image"))
        )
        createSut()

        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
        assertEquals(
            persistentListOf(
                RingIdentity(
                    pubky = RING_PUBKY,
                    caption = "3RSD...W5XG",
                    name = "Satoshi",
                    imageUrl = "https://image",
                )
            ),
            sut.uiState.value.identities,
        )
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
