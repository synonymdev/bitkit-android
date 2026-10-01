package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.description
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
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
        private const val THIRD_RING_PUBKY = "5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
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
    fun `ring identities are listed before their profiles resolve and each row looks up until it finds one`() = test {
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
        assertEquals(listOf(true, true), sut.uiState.value.identities.map { it.isLookingUp })

        val otherProfile = PubkyProfile.forDisplay(OTHER_RING_PUBKY, name = "Hal", imageUrl = null)
        otherLookup.complete(Result.success(otherProfile))
        advanceUntilIdle()
        assertEquals(listOf("3rsd...w5xg", "Hal"), sut.uiState.value.identities.map { it.name })
        assertEquals(listOf(true, false), sut.uiState.value.identities.map { it.isLookingUp })
        assertEquals(otherProfile, sut.uiState.value.identities.last().profile)

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
        assertEquals(listOf(false, false), sut.uiState.value.identities.map { it.isLookingUp })
        assertEquals("https://image", sut.uiState.value.identities.first().imageUrl)
    }

    @Test
    fun `rows stop showing their lookup when it finds nothing, fails or returns another key`() = test {
        val notFoundLookup = CompletableDeferred<Result<PubkyProfile?>>()
        val failedLookup = CompletableDeferred<Result<PubkyProfile?>>()
        val mismatchedLookup = CompletableDeferred<Result<PubkyProfile?>>()
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY, THIRD_RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(RING_PUBKY)).doSuspendableAnswer { notFoundLookup.await() }
        whenever(pubkyRepo.fetchDisplayProfile(OTHER_RING_PUBKY)).doSuspendableAnswer { failedLookup.await() }
        whenever(pubkyRepo.fetchDisplayProfile(THIRD_RING_PUBKY)).doSuspendableAnswer { mismatchedLookup.await() }
        createSut()
        advanceUntilIdle()

        assertEquals(listOf(true, true, true), sut.uiState.value.identities.map { it.isLookingUp })

        notFoundLookup.complete(Result.success(null))
        advanceUntilIdle()
        assertEquals(listOf(false, true, true), sut.uiState.value.identities.map { it.isLookingUp })

        failedLookup.complete(Result.failure(PubkyChoiceTestAppError("lookup failed")))
        advanceUntilIdle()
        assertEquals(listOf(false, false, true), sut.uiState.value.identities.map { it.isLookingUp })

        val ringProfile = PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = null)
        mismatchedLookup.complete(Result.success(ringProfile))
        advanceUntilIdle()
        assertEquals(
            persistentListOf(
                RingIdentity(pubky = RING_PUBKY),
                RingIdentity(pubky = OTHER_RING_PUBKY),
                RingIdentity(pubky = THIRD_RING_PUBKY),
            ),
            sut.uiState.value.identities,
        )
    }

    @Test
    fun `onIdentityClick hands only a profile found for the row's own key to adoption`() = test {
        val satoshi = PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = null)
        val failure = Result.failure<PubkyProfile?>(PubkyChoiceTestAppError("lookup failed"))
        listOf(
            RingRow("found", RING_PUBKY, Result.success(satoshi), rowName = "Satoshi", handoff = satoshi),
            RingRow("failed", RING_PUBKY, failure, rowName = "3rsd...w5xg", handoff = null),
            RingRow("another key", OTHER_RING_PUBKY, Result.success(satoshi), rowName = "1rsd...w5xy", handoff = null),
        ).forEach { case ->
            reset(pubkyRepo)
            setUp()
            whenever(pubkyRepo.ringIdentities()).thenReturn(Result.success(persistentListOf(case.pubky)))
            whenever(pubkyRepo.fetchDisplayProfile(case.pubky)).thenReturn(case.lookup)
            val handoffs = mutableListOf<PubkyProfile?>()
            whenever(pubkyRepo.adoptRingIdentity(any(), any())).doSuspendableAnswer {
                handoffs += it.getArgument<() -> PubkyProfile?>(1)()
                Result.success(case.handoff != null)
            }
            createSut()
            advanceUntilIdle()

            assertEquals(listOf(case.rowName), sut.uiState.value.identities.map { it.name }, case.name)
            assertEquals(case.handoff, sut.uiState.value.identities.single().profile, case.name)
            sut.onIdentityClick(case.pubky)
            advanceUntilIdle()

            verify(pubkyRepo, description(case.name)).adoptRingIdentity(eq(case.pubky), any())
            assertEquals(listOf(case.handoff), handoffs, case.name)
        }
    }

    @Test
    fun `onIdentityClick hands over a row profile that resolves during sign-in`() = test {
        val lookup = CompletableDeferred<Result<PubkyProfile?>>()
        val signIn = CompletableDeferred<Unit>()
        val handoffs = mutableListOf<PubkyProfile?>()
        whenever(pubkyRepo.ringIdentities()).thenReturn(Result.success(persistentListOf(RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(RING_PUBKY)).doSuspendableAnswer { lookup.await() }
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any())).doSuspendableAnswer {
            signIn.await()
            handoffs += it.getArgument<() -> PubkyProfile?>(1)()
            Result.success(true)
        }
        createSut()
        advanceUntilIdle()

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()
        val profile = PubkyProfile.forDisplay(RING_PUBKY, name = "Satoshi", imageUrl = null)
        lookup.complete(Result.success(profile))
        advanceUntilIdle()
        signIn.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf<PubkyProfile?>(profile), handoffs)
    }

    @Test
    fun `other rows still resolve after a failed adoption`() = test {
        val otherLookup = CompletableDeferred<Result<PubkyProfile?>>()
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(OTHER_RING_PUBKY)).doSuspendableAnswer { otherLookup.await() }
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any()))
            .thenReturn(Result.failure(PubkyChoiceTestAppError("adopt failed")))
        createSut()
        advanceUntilIdle()

        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()
        assertNull(sut.uiState.value.adoptingPubky)

        otherLookup.complete(Result.success(PubkyProfile.forDisplay(OTHER_RING_PUBKY, name = "Hal", imageUrl = null)))
        advanceUntilIdle()

        assertEquals(listOf("3rsd...w5xg", "Hal"), sut.uiState.value.identities.map { it.name })
    }

    @Test
    fun `onIdentityClick ignores further taps while an adoption is running`() = test {
        val finishAdoption = CompletableDeferred<Result<Boolean>>()
        val otherLookup = CompletableDeferred<Result<PubkyProfile?>>()
        whenever(pubkyRepo.ringIdentities())
            .thenReturn(Result.success(persistentListOf(RING_PUBKY, OTHER_RING_PUBKY)))
        whenever(pubkyRepo.fetchDisplayProfile(OTHER_RING_PUBKY)).doSuspendableAnswer { otherLookup.await() }
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any())).doSuspendableAnswer { finishAdoption.await() }
        createSut()
        advanceUntilIdle()

        sut.onIdentityClick(RING_PUBKY)
        sut.onIdentityClick(OTHER_RING_PUBKY)
        advanceUntilIdle()
        otherLookup.complete(Result.success(PubkyProfile.forDisplay(OTHER_RING_PUBKY, name = "Hal", imageUrl = null)))
        advanceUntilIdle()
        sut.onIdentityClick(OTHER_RING_PUBKY)
        sut.onIdentityClick(RING_PUBKY)
        advanceUntilIdle()

        assertEquals(RING_PUBKY, sut.uiState.value.adoptingPubky)
        assertEquals(listOf("3rsd...w5xg", "Hal"), sut.uiState.value.identities.map { it.name })
        finishAdoption.complete(Result.success(false))
        advanceUntilIdle()

        verify(pubkyRepo, times(1)).adoptRingIdentity(any(), any())
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
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any())).thenReturn(Result.success(true))
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
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any())).thenReturn(Result.success(true))
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
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any())).thenReturn(Result.success(true))
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
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any())).thenReturn(Result.success(false))
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
        whenever(pubkyRepo.adoptRingIdentity(eq(RING_PUBKY), any()))
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

private class RingRow(
    val name: String,
    val pubky: String,
    val lookup: Result<PubkyProfile?>,
    val rowName: String,
    val handoff: PubkyProfile?,
)

private class PubkyChoiceTestAppError(message: String) : AppError(message)
