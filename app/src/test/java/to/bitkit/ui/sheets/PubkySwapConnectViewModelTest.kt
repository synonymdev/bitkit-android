package to.bitkit.ui.sheets

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkySwapNetwork
import to.bitkit.models.PubkySwapProvider
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySwapError
import to.bitkit.repositories.PubkySwapRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PubkySwapConnectViewModelTest : BaseUnitTest() {
    companion object {
        private const val IDENTITY = "pubky8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"
        private const val PENDING_SWAPS = "Pending swaps must finish first."
        private val PROVIDER = PubkySwapProvider(
            publicKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy",
            network = PubkySwapNetwork.REGTEST,
        )
        private val OTHER_PROVIDER = PubkySwapProvider(
            publicKey = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo",
            network = PubkySwapNetwork.REGTEST,
        )
    }

    private val context = mock<Context>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val pubkySwapRepo = mock<PubkySwapRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val identity = MutableStateFlow<String?>(IDENTITY)
    private val savedProvider = MutableStateFlow<PubkySwapProvider?>(null)
    private lateinit var sut: PubkySwapConnectViewModel

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("")
        whenever(context.getString(R.string.pubky_swap__error_pending_swaps)).thenReturn(PENDING_SWAPS)
        whenever(pubkyRepo.publicKey).thenReturn(identity)
        whenever { pubkyRepo.fetchDisplayProfile(any()) }.thenReturn(Result.success(null))
        whenever(settingsStore.pubkySwapProvider).thenReturn(savedProvider)
        whenever(settingsStore.isPaykitEnabled).thenReturn(MutableStateFlow(true))
        sut = PubkySwapConnectViewModel(
            context = context,
            pubkyRepo = pubkyRepo,
            pubkySwapRepo = pubkySwapRepo,
            settingsStore = settingsStore,
        )
    }

    @Test
    fun `load shows the identity and the provider it replaces`() = test {
        savedProvider.value = OTHER_PROVIDER

        sut.load(PROVIDER)

        val state = sut.uiState.value
        assertEquals(PROVIDER, state.provider)
        assertEquals(IDENTITY, state.identity)
        assertEquals(OTHER_PROVIDER, state.replacedProvider)
    }

    @Test
    fun `load does not show the saved provider as replaced`() = test {
        savedProvider.value = PROVIDER

        sut.load(PROVIDER)

        assertNull(sut.uiState.value.replacedProvider)
    }

    @Test
    fun `connect dismisses the sheet once connected`() = test {
        whenever { pubkySwapRepo.connect(PROVIDER) }.thenReturn(Result.success(Unit))
        sut.load(PROVIDER)

        sut.effects.test {
            sut.connect()

            assertEquals(PubkySwapConnectEffect.Dismiss, awaitItem())
        }
    }

    @Test
    fun `connect shows why a provider switch was refused`() = test {
        whenever { pubkySwapRepo.connect(PROVIDER) }.thenReturn(Result.failure(PubkySwapError.PendingSwaps))
        sut.load(PROVIDER)

        sut.connect()

        assertEquals(PENDING_SWAPS, sut.uiState.value.errorText)
        assertFalse(sut.uiState.value.isConnecting)
    }

    @Test
    fun `setUpProfile keeps the provider for after onboarding`() = test {
        identity.value = null
        sut.load(PROVIDER)

        sut.effects.test {
            sut.setUpProfile()

            assertEquals(PubkySwapConnectEffect.SetUpProfile, awaitItem())
        }
        verify(pubkySwapRepo).setPendingConnection(PROVIDER)
        verify(pubkySwapRepo, never()).connect(any())
    }
}
