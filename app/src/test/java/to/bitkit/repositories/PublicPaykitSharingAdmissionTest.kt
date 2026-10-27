package to.bitkit.repositories

import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.EndpointSyncReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.UsdtPreference
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.idleSparkRepo
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val lightningRepo = mock<LightningRepo>()
    private val coreService = mock<CoreService>()
    private val isRestoringSession = MutableStateFlow(false)
    private val settingsFlow = MutableStateFlow(SettingsData())
    private val usdtRepo = mock<UsdtRepo> {
        on { state }.thenReturn(MutableStateFlow(UsdtWalletState()))
    }
    private lateinit var sut: PublicPaykitRepo

    @Before
    fun setUp() = test {
        sut = PublicPaykitRepo(
            ioDispatcher = testDispatcher,
            pubkyRepo = pubkyRepo,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
            paykitSdkService = paykitSdkService,
            settingsStore = settingsStore,
            clock = Clock.System,
            usdtRepo = usdtRepo,
            sparkRepo = idleSparkRepo(),
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

    @After
    fun tearDown() {
        PublicPaykitRepo.lightningRouteHintsValidator = null
    }

    @Test
    fun `USDT preparation failure allows Bitcoin publication and retries until recovery or disable`() = test {
        whenever(walletRepo.refreshReusableReceiveAddress()).thenReturn(Result.success(Unit))
        whenever(paykitSdkService.syncPublicEndpoints(any(), any()))
            .thenReturn(EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList()))
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
            publicPaykitUsdtEnabled = true,
            usdtPreference = UsdtPreference.ENABLED,
        )
        UsdtEndpointTestWallet(testDispatcher).use { wallet ->
            whenever(usdtRepo.paymentEndpoint()).doSuspendableAnswer { wallet.repo.paymentEndpoint() }
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)

            wallet.available = true
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertFalse(settingsFlow.value.publicPaykitCleanupPending)
            wallet.derivationAvailable = false
            sut.syncCurrentPublishedEndpoints().getOrThrow()

            wallet.passphrase = "other wallet"
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)
            wallet.derivationAvailable = true
            sut.syncCurrentPublishedEndpoints().getOrThrow()

            settingsFlow.value = settingsFlow.value.copy(publicPaykitUsdtEnabled = false)
            wallet.available = false
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertFalse(settingsFlow.value.publicPaykitCleanupPending)
            val endpoints = argumentCaptor<List<Endpoint>>()
            verifyBlocking(paykitSdkService, times(6)) { syncPublicEndpoints(endpoints.capture(), any()) }
            assertEquals(
                listOf(
                    null,
                    UsdtEndpointTestWallet.ADDRESS,
                    UsdtEndpointTestWallet.ADDRESS,
                    null,
                    UsdtEndpointTestWallet.OTHER_ADDRESS,
                    null,
                ),
                endpoints.allValues.map { list -> list.firstOrNull { it.methodId == MethodId.UsdtArbitrum }?.value },
            )
            assertTrue(endpoints.allValues.all { list -> list.any { it.methodId == MethodId.P2tr } })
        }
        settingsFlow.value = settingsFlow.value.copy(publicPaykitUsdtEnabled = true)
        whenever(usdtRepo.paymentEndpoint()).thenReturn(Result.failure(CancellationException()))
        assertFailsWith<CancellationException> { sut.syncCurrentPublishedEndpoints() }
    }

    @Test
    fun `Lightning renewal publishes while unavailable USDT remains pending`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitOnchainEnabled = false,
            publicPaykitUsdtEnabled = true,
            usdtPreference = UsdtPreference.ENABLED,
            publicPaykitBolt11 = "ln-old",
            publicPaykitBolt11PaymentHash = "01",
            publicPaykitBolt11ExpiresAtMillis = 1L,
        )
        whenever(lightningRepo.canReceive()).thenReturn(true)
        PublicPaykitRepo.lightningRouteHintsValidator = { true }
        whenever(lightningRepo.createInvoice(null, "", 86_400u)).thenReturn(Result.success("ln-new"))
        whenever(coreService.decode("ln-new")).thenReturn(Scanner.Lightning(invoice("ln-new")))
        whenever(usdtRepo.paymentEndpoint()).thenReturn(Result.failure(AppError("USDT unavailable")))

        whenever(paykitSdkService.syncPublicEndpoints(any(), any()))
            .thenReturn(EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList()))
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        assertEquals("ln-new", settingsFlow.value.publicPaykitBolt11)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)

        val usdt = Endpoint(
            methodId = MethodId.UsdtArbitrum,
            value = "0x1111111111111111111111111111111111111111",
            rawPayload = "{}",
        )
        whenever(usdtRepo.paymentEndpoint()).thenReturn(Result.success(usdt))
        whenever(paykitSdkService.syncPublicEndpoints(any(), any()))
            .thenReturn(EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList()))
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        val endpoints = argumentCaptor<List<Endpoint>>()
        verifyBlocking(paykitSdkService, times(2)) { syncPublicEndpoints(endpoints.capture(), any()) }
        assertEquals(listOf("ln-new"), endpoints.firstValue.map { it.value })
        assertEquals(listOf("ln-new", usdt.value), endpoints.secondValue.map { it.value })
        verifyBlocking(lightningRepo, times(1)) { createInvoice(null, "", 86_400u) }
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `deferred public lightning refresh survives skipped failed and superseded publication`() = test {
        val signIn = PubkySignIn("pubkyself", 0L)
        whenever(pubkyRepo.isCurrent(any())).thenReturn(true)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        PublicPaykitRepo.lightningRouteHintsValidator = { true }
        for (outcome in listOf("skipped", "failed", "superseded")) {
            clearInvocations(lightningRepo)
            settingsFlow.value = SettingsData(
                sharesPublicPaykitEndpoints = true,
                publicPaykitOnchainEnabled = false,
                publicPaykitUsdtEnabled = false,
                publicPaykitBolt11 = "ln-cached",
                publicPaykitBolt11PaymentHash = "01",
                publicPaykitBolt11ExpiresAtMillis = Long.MAX_VALUE,
            )
            whenever(lightningRepo.createInvoice(null, "", 86_400u))
                .thenReturn(Result.success("ln-first"), Result.success("ln-second"))
            for (bolt11 in listOf("ln-first", "ln-second")) {
                whenever(coreService.decode(bolt11)).thenReturn(Scanner.Lightning(invoice(bolt11)))
            }
            var calls = 0
            val published = mutableListOf<List<String>>()
            whenever(paykitSdkService.syncPublicEndpoints(any(), any())).doSuspendableAnswer {
                calls += 1
                if (calls == 1) {
                    if (outcome == "skipped") return@doSuspendableAnswer null
                    if (outcome == "failed") throw PublicPaykitError.PublicationFailed
                    sut.requestLightningRefresh(signIn)
                }
                published.add(it.getArgument<List<Endpoint>>(0).map { endpoint -> endpoint.value })
                EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList())
            }
            sut.requestLightningRefresh(signIn)
            assertTrue(sut.hasPendingLightningRefresh)
            val first = sut.syncCurrentPublishedEndpoints()
            assertEquals(outcome == "failed", first.isFailure, outcome)
            assertTrue(sut.hasPendingLightningRefresh, outcome)
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertFalse(sut.hasPendingLightningRefresh, outcome)
            val expectedInvoice = if (outcome == "superseded") "ln-second" else "ln-first"
            assertEquals(listOf(expectedInvoice), published.last(), outcome)
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertEquals(listOf(expectedInvoice), published.last(), outcome)
            verifyBlocking(lightningRepo, times(if (outcome == "superseded") 2 else 1)) {
                createInvoice(null, "", 86_400u)
            }
        }
    }

    @Test
    fun `initial public refresh waits for its saved identity and binds its first restored sign in`() = test {
        for (outcome in listOf("restore", "replace before", "replace after")) {
            var ownsIdentity = true
            var signIn: PubkySignIn? = null
            whenever(pubkyRepo.currentSignIn()).thenAnswer { signIn }
            whenever(pubkyRepo.isCurrent(any())).thenAnswer {
                val token = it.getArgument<PubkySignIn>(0)
                token.publicKey == signIn?.publicKey && token.generation == signIn?.generation
            }
            whenever(lightningRepo.canReceive()).thenReturn(true)
            PublicPaykitRepo.lightningRouteHintsValidator = { true }
            settingsFlow.value = SettingsData(
                sharesPublicPaykitEndpoints = true,
                publicPaykitOnchainEnabled = false,
                publicPaykitUsdtEnabled = false,
                publicPaykitBolt11 = "ln-cached",
                publicPaykitBolt11PaymentHash = "01",
                publicPaykitBolt11ExpiresAtMillis = Long.MAX_VALUE,
            )
            whenever(lightningRepo.createInvoice(null, "", 86_400u)).thenReturn(Result.success("ln-refreshed"))
            whenever(coreService.decode("ln-refreshed")).thenReturn(Scanner.Lightning(invoice("ln-refreshed")))
            sut.requestLightningRefresh(null) { ownsIdentity }
            repeat(2) {
                sut.syncCurrentPublishedEndpoints(canPublish = { signIn != null }).getOrThrow()
                assertTrue(sut.hasPendingLightningRefresh, outcome)
                assertEquals("ln-cached", settingsFlow.value.publicPaykitBolt11, outcome)
            }
            if (outcome == "replace before") ownsIdentity = false
            signIn = PubkySignIn("pubkyself", 1L)
            var publicationFails = true
            whenever(paykitSdkService.syncPublicEndpoints(any(), any())).doSuspendableAnswer {
                if (publicationFails) throw PublicPaykitError.PublicationFailed
                EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList())
            }
            assertTrue(sut.syncCurrentPublishedEndpoints().isFailure)
            assertEquals(outcome != "replace before", sut.hasPendingLightningRefresh, outcome)
            if (outcome == "replace after") {
                signIn = PubkySignIn("pubkyself", 2L)
                settingsFlow.value = settingsFlow.value.copy(publicPaykitBolt11 = "ln-replacement")
                assertFalse(sut.hasPendingLightningRefresh)
            }
            publicationFails = false
            sut.syncCurrentPublishedEndpoints().getOrThrow()
            assertEquals(
                when (outcome) {
                    "restore" -> "ln-refreshed"
                    "replace before" -> "ln-cached"
                    else -> "ln-replacement"
                },
                settingsFlow.value.publicPaykitBolt11,
                outcome,
            )
            assertFalse(sut.hasPendingLightningRefresh, outcome)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `overlapping refreshes prepare one invoice each and reuse it after publication failure`() = test {
        val signIn = PubkySignIn("pubkyself", 0L)
        whenever(pubkyRepo.currentSignIn()).thenReturn(signIn)
        whenever(pubkyRepo.isCurrent(any())).thenReturn(true)
        whenever(lightningRepo.canReceive()).thenReturn(true)
        PublicPaykitRepo.lightningRouteHintsValidator = { true }
        settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true, publicPaykitOnchainEnabled = false)
        val preparing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var invoiceCount = 0
        whenever(lightningRepo.createInvoice(null, "", 86_400u)).doSuspendableAnswer {
            invoiceCount += 1
            if (invoiceCount == 1) {
                preparing.complete(Unit)
                resume.await()
            }
            Result.success("ln-$invoiceCount")
        }
        for (bolt11 in listOf("ln-1", "ln-2")) {
            whenever(coreService.decode(bolt11)).thenReturn(Scanner.Lightning(invoice(bolt11)))
        }
        var publicationFails = true
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).doSuspendableAnswer {
            if (publicationFails) throw PublicPaykitError.PublicationFailed
            EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList())
        }
        sut.requestLightningRefresh(signIn)
        val first = async { sut.syncCurrentPublishedEndpoints() }
        preparing.await()
        val duplicate = async { sut.syncCurrentPublishedEndpoints() }
        runCurrent()
        sut.requestLightningRefresh(signIn)
        val newer = async { sut.syncCurrentPublishedEndpoints() }
        resume.complete(Unit)
        assertTrue(first.await().isFailure)
        assertTrue(duplicate.await().isFailure)
        assertTrue(newer.await().isFailure)
        assertEquals(2, invoiceCount)
        assertTrue(sut.hasPendingLightningRefresh)

        publicationFails = false
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        assertEquals(2, invoiceCount)
        assertEquals("ln-2", settingsFlow.value.publicPaykitBolt11)
        assertFalse(sut.hasPendingLightningRefresh)
    }

    @Test
    fun `deferred public lightning refresh does not transfer to a replacement session`() = test {
        val original = PubkySignIn("pubkyself", 0L)
        var generation = 0L
        whenever(pubkyRepo.isCurrent(any())).thenAnswer { it.getArgument<PubkySignIn>(0).generation == generation }
        whenever(lightningRepo.canReceive()).thenReturn(true)
        PublicPaykitRepo.lightningRouteHintsValidator = { true }
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitOnchainEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitBolt11 = "ln-replacement-session",
            publicPaykitBolt11PaymentHash = "01",
            publicPaykitBolt11ExpiresAtMillis = Long.MAX_VALUE,
        )
        whenever(paykitSdkService.syncPublicEndpoints(any(), any()))
            .thenReturn(EndpointSyncReport(published = emptyList(), removed = emptyList(), failed = emptyList()))
        sut.requestLightningRefresh(original)
        generation += 1
        assertFalse(sut.hasPendingLightningRefresh)
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        verifyBlocking(lightningRepo, never()) { createInvoice(any(), any(), any()) }
        assertEquals("ln-replacement-session", settingsFlow.value.publicPaykitBolt11)
    }

    private fun invoice(bolt11: String) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = byteArrayOf(1),
        amountSatoshis = 0uL,
        timestampSeconds = 0uL,
        expirySeconds = 86_400uL,
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )

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
