package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.sharing.SharedPubkyContract
import to.bitkit.data.sharing.SharedPubkyIdentity
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyChoiceViewModelTest : BaseUnitTest() {
    companion object {
        private const val WIRE_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val pendingImportContacts = MutableStateFlow<List<PubkyProfile>>(emptyList())

    @Before
    fun setUp() = runBlocking {
        whenever(context.getString(R.string.profile__choice_error)).thenReturn("Couldn't use this pubky")
        whenever(pubkyRepo.pendingImportContacts).thenReturn(pendingImportContacts)
        whenever(pubkyRepo.discoverRingIdentities()).thenReturn(Result.success(emptyList()))
        Unit
    }

    @Test
    fun `discovery resolves safe public profile data for Ring choices`() = test {
        val identity = ringIdentity()
        val profile = PubkyProfile.forDisplay(
            publicKey = SharedPubkyContract.toBitkitPubky(WIRE_PUBKY),
            name = "Satoshi",
            imageUrl = "pubky://avatar",
        )
        whenever(pubkyRepo.discoverRingIdentities()).thenReturn(Result.success(listOf(identity)))
        whenever(pubkyRepo.fetchRemoteProfile(profile.publicKey)).thenReturn(Result.success(profile))

        val sut = createSut()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isDiscovering)
        assertEquals(1, sut.uiState.value.identities.size)
        assertEquals(WIRE_PUBKY, sut.uiState.value.identities.single().pubky)
        assertEquals(profile, sut.uiState.value.identities.single().profile)
    }

    @Test
    fun `selection adopts exact Ring identity then opens contact overview`() = test {
        val identity = ringIdentity()
        pendingImportContacts.value = listOf(PubkyProfile.placeholder("pubky$WIRE_PUBKY"))
        whenever(pubkyRepo.discoverRingIdentities()).thenReturn(Result.success(listOf(identity)))
        whenever(pubkyRepo.fetchRemoteProfile("pubky$WIRE_PUBKY"))
            .thenReturn(Result.success(PubkyProfile.placeholder("pubky$WIRE_PUBKY")))
        whenever(pubkyRepo.adoptRingIdentity(identity)).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.prepareImport()).thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        val effects = mutableListOf<PubkyChoiceEffect>()
        val job = launch { sut.effects.collect(effects::add) }
        sut.selectRingIdentity(sut.uiState.value.identities.single())
        advanceUntilIdle()

        verify(pubkyRepo).adoptRingIdentity(identity)
        verify(pubkyRepo).prepareImport()
        assertEquals(
            listOf<PubkyChoiceEffect>(PubkyChoiceEffect.NavigateToContactImportOverview),
            effects,
        )
        assertNull(sut.uiState.value.selectedPubky)
        job.cancel()
    }

    @Test
    fun `selection with no contacts opens Pay Contacts`() = test {
        val identity = ringIdentity()
        whenever(pubkyRepo.discoverRingIdentities()).thenReturn(Result.success(listOf(identity)))
        whenever(pubkyRepo.fetchRemoteProfile("pubky$WIRE_PUBKY"))
            .thenReturn(Result.success(PubkyProfile.placeholder("pubky$WIRE_PUBKY")))
        whenever(pubkyRepo.adoptRingIdentity(identity)).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.prepareImport()).thenReturn(Result.success(Unit))
        val sut = createSut()
        advanceUntilIdle()

        val effects = mutableListOf<PubkyChoiceEffect>()
        val job = launch { sut.effects.collect(effects::add) }
        sut.selectRingIdentity(sut.uiState.value.identities.single())
        advanceUntilIdle()

        assertEquals(
            listOf<PubkyChoiceEffect>(PubkyChoiceEffect.NavigateToPayContacts),
            effects,
        )
        job.cancel()
    }

    @Test
    fun `failed selection clears loading and reports error`() = test {
        val identity = ringIdentity()
        val error = IllegalStateException("Credential mismatch")
        whenever(pubkyRepo.discoverRingIdentities()).thenReturn(Result.success(listOf(identity)))
        whenever(pubkyRepo.fetchRemoteProfile("pubky$WIRE_PUBKY"))
            .thenReturn(Result.success(PubkyProfile.placeholder("pubky$WIRE_PUBKY")))
        whenever(pubkyRepo.adoptRingIdentity(identity)).thenReturn(Result.failure(error))
        val sut = createSut()
        advanceUntilIdle()

        val toasts = mutableListOf<Toast>()
        val job = launch { ToastEventBus.events.collect(toasts::add) }
        sut.selectRingIdentity(sut.uiState.value.identities.single())
        advanceUntilIdle()

        assertNull(sut.uiState.value.selectedPubky)
        assertTrue(toasts.isNotEmpty())
        assertEquals("Couldn't use this pubky", toasts.last().title)
        assertEquals(error.message, toasts.last().description)
        job.cancel()
    }

    private fun createSut() = PubkyChoiceViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
    )

    private fun ringIdentity() = SharedPubkyIdentity(
        protocolVersion = SharedPubkyContract.PROTOCOL_VERSION,
        sourcePackage = SharedPubkyContract.RING_SOURCE,
        pubky = WIRE_PUBKY,
    )
}
