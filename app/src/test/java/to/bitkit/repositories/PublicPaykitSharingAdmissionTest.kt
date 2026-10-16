package to.bitkit.repositories

import com.synonym.paykit.EndpointSyncReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PublicPaykitSharingAdmissionTest : BaseUnitTest() {
    private val pubkyRepo = mock<PubkyRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val paykitSdkService = mock<PaykitSdkService>()
    private val settingsStore = mock<SettingsStore>()
    private val isRestoringSession = MutableStateFlow(false)
    private val settingsFlow = MutableStateFlow(SettingsData())
    private lateinit var sut: PublicPaykitRepo

    @Before
    fun setUp() = test {
        sut = PublicPaykitRepo(
            ioDispatcher = testDispatcher,
            pubkyRepo = pubkyRepo,
            walletRepo = walletRepo,
            lightningRepo = mock(),
            coreService = mock(),
            paykitSdkService = paykitSdkService,
            settingsStore = settingsStore,
            clock = Clock.System,
            usdtRepo = mock {
                on { state }.thenReturn(MutableStateFlow(UsdtWalletState()))
            },
        )
        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyself"))
        whenever(pubkyRepo.isRestoringSession).thenReturn(isRestoringSession)
        whenever(walletRepo.walletState).thenReturn(MutableStateFlow(WalletState(onchainAddress = "bc1ptest")))
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(settingsStore.update(any())).thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `public sharing disabled during restoration preserves pending cleanup without preparing endpoints`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitCleanupPending = true,
            publicPaykitBolt11 = "lnbc1previous",
        )
        isRestoringSession.value = true
        val publication = async { sut.syncCurrentPublishedEndpoints() }
        runCurrent()
        settingsFlow.value = settingsFlow.value.copy(sharesPublicPaykitEndpoints = false)
        isRestoringSession.value = false
        publication.await().getOrThrow()

        verifyNoInteractions(paykitSdkService)
        verifyBlocking(walletRepo, never()) { refreshReusableReceiveAddress() }
        assertEquals("lnbc1previous", settingsFlow.value.publicPaykitBolt11)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
    }

    @Test
    fun `public sharing disable fences preparation and queued writes without discarding admitted cleanup`() = test {
        for (stage in listOf("preparation", "queued", "admitted")) {
            clearInvocations(paykitSdkService)
            settingsFlow.value = SettingsData(
                sharesPublicPaykitEndpoints = true,
                publicPaykitLightningEnabled = false,
                publicPaykitUsdtEnabled = false,
                publicPaykitCleanupPending = true,
            )
            val reached = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var published = false
            whenever(walletRepo.refreshReusableReceiveAddress()).doSuspendableAnswer {
                if (stage == "preparation") {
                    reached.complete(Unit)
                    resume.await()
                }
                Result.success(Unit)
            }
            whenever(paykitSdkService.syncPublicEndpoints(any(), any())).doSuspendableAnswer {
                if (stage == "queued") {
                    reached.complete(Unit)
                    resume.await()
                }
                if (!it.getArgument<suspend () -> Boolean>(1)()) return@doSuspendableAnswer null
                published = true
                if (stage == "admitted") {
                    reached.complete(Unit)
                    resume.await()
                }
                EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList())
            }
            val publication = async { sut.syncCurrentPublishedEndpoints() }
            reached.await()
            settingsFlow.value = settingsFlow.value.copy(sharesPublicPaykitEndpoints = false)
            resume.complete(Unit)
            publication.await().getOrThrow()

            assertEquals(stage == "admitted", published, stage)
            assertTrue(settingsFlow.value.publicPaykitCleanupPending, stage)
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints, stage)
        }
    }
}
