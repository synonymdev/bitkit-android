package to.bitkit.repositories

import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.EndpointSyncChange
import com.synonym.paykit.EndpointSyncReport
import com.synonym.paykit.PublicationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
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
import to.bitkit.services.PaykitPublicContactPaymentResolution
import to.bitkit.services.PaykitResolvedPaymentEndpoint
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PublicPaykitRepoTest : BaseUnitTest() {
    companion object {
        private const val NOW_MILLIS = 1_000L
        private const val PUBLIC_BOLT11_EXPIRY_SECONDS = 86_400u
        private const val PUBLIC_BOLT11 = "lnbcrt1public"
    }

    private val pubkyRepo = mock<PubkyRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val coreService = mock<CoreService>()
    private val paykitSdkService = mock<PaykitSdkService>()
    private val settingsStore = mock<SettingsStore>()
    private val clock = mock<Clock>()
    private val usdtRepo = mock<UsdtRepo> {
        on { state }.thenReturn(MutableStateFlow(UsdtWalletState()))
    }

    private val publicKey = MutableStateFlow<String?>("pubkyself")
    private val isRestoringSession = MutableStateFlow(false)
    private val walletState = MutableStateFlow(WalletState())
    private val settingsFlow = MutableStateFlow(SettingsData())

    private lateinit var sut: PublicPaykitRepo

    @Before
    fun setUp() = test {
        sut = createRepo()
        settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true, publicPaykitUsdtEnabled = false)
        publicKey.value = "pubkyself"
        isRestoringSession.value = false
        walletState.value = WalletState()

        whenever(pubkyRepo.publicKey).thenReturn(publicKey)
        whenever(pubkyRepo.isRestoringSession).thenReturn(isRestoringSession)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(NOW_MILLIS))
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).thenReturn(syncReport())
        whenever { walletRepo.refreshReusableReceiveAddress() }.thenReturn(Result.success(Unit))
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
        }
        PublicPaykitRepo.lightningRouteHintsValidator = { true }
    }

    @After
    fun tearDown() {
        PublicPaykitRepo.lightningRouteHintsValidator = null
    }

    @Test
    fun `optional USDT endpoint failure preserves bitcoin publication and propagates cancellation`() = test {
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
            publicPaykitUsdtEnabled = true,
            usdtPreference = UsdtPreference.ENABLED,
        )
        whenever(usdtRepo.paymentEndpoint()).thenReturn(Result.failure(AppError("USDT unavailable")))
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        val endpoints = argumentCaptor<List<Endpoint>>()
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(endpoints.capture(), any()) }
        assertEquals(listOf(MethodId.P2tr), endpoints.firstValue.map { it.methodId })

        whenever(usdtRepo.paymentEndpoint()).thenReturn(Result.failure(CancellationException()))
        assertFailsWith<CancellationException> { sut.syncCurrentPublishedEndpoints() }
    }

    @Test
    fun `private capability follows sharing preference`() = test {
        for (enabled in listOf(false, true)) {
            settingsFlow.value = settingsFlow.value.copy(sharesPrivatePaykitEndpoints = enabled)
            sut.syncPaykitApp().getOrThrow()
        }
        val capabilities = argumentCaptor<Boolean>()
        verifyBlocking(paykitSdkService, times(2)) { syncPaykitApp(capabilities.capture(), eq(Priority.Ordered)) }
        assertEquals(listOf(false, true), capabilities.allValues)
    }

    @Test
    fun `syncCurrentPublishedEndpoints configures SDK public endpoints`() = test {
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        stubPublicInvoice("lnbc1public", byteArrayOf(1, 2, 3))

        val result = sut.syncCurrentPublishedEndpoints()

        assertTrue(result.isSuccess)
        assertEquals("lnbc1public", settingsFlow.value.publicPaykitBolt11)
        assertEquals("010203", settingsFlow.value.publicPaykitBolt11PaymentHash)

        val captor = argumentCaptor<List<Endpoint>>()
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(captor.capture(), any()) }
        assertEquals(
            listOf(MethodId.Bolt11, MethodId.P2tr),
            captor.firstValue.map { it.methodId },
        )
    }

    @Test
    fun `deferred paid invoice replacement clears the cache before publication resumes`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitOnchainEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitBolt11 = "lnbc1paid",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
        )
        stubPublicInvoice("lnbc1replacement", byteArrayOf(4, 5, 6))

        sut.refreshPublishedBolt11ForPayment("unrelated", canPublish = { false }).getOrThrow()
        assertEquals("lnbc1paid", settingsFlow.value.publicPaykitBolt11)
        sut.refreshPublishedBolt11ForPayment("010203", canPublish = { false }).getOrThrow()
        assertEquals("", settingsFlow.value.publicPaykitBolt11)
        assertEquals("", settingsFlow.value.publicPaykitBolt11PaymentHash)
        verifyNoInteractions(paykitSdkService)

        sut.syncCurrentPublishedEndpoints().getOrThrow()
        val endpoints = argumentCaptor<List<Endpoint>>()
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(endpoints.capture(), any()) }
        assertEquals("lnbc1replacement", endpoints.firstValue.single().value)
        assertEquals("040506", settingsFlow.value.publicPaykitBolt11PaymentHash)
    }

    @Test
    fun `paid public invoice replacement checks deletion after invoice preparation`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitOnchainEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitBolt11 = "lnbc1paid",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
        )
        stubPublicInvoice("lnbc1replacement", byteArrayOf(4, 5, 6))
        val invoiceStarted = CompletableDeferred<Unit>()
        val resumeInvoice = CompletableDeferred<Unit>()
        whenever(lightningRepo.createInvoice(null, "", PUBLIC_BOLT11_EXPIRY_SECONDS)).doSuspendableAnswer {
            invoiceStarted.complete(Unit)
            resumeInvoice.await()
            Result.success("lnbc1replacement")
        }
        var deleting = false
        val replacement = async {
            sut.refreshPublishedBolt11ForPayment("010203", canPublish = { !deleting }).getOrThrow()
        }
        invoiceStarted.await()
        assertEquals("", settingsFlow.value.publicPaykitBolt11PaymentHash)
        deleting = true
        resumeInvoice.complete(Unit)
        replacement.await()

        verifyBlocking(paykitSdkService, never()) { syncPublicEndpoints(any(), any()) }
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        deleting = false
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(any(), any()) }
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `public publication rechecks admission after identity lookup without cancelling admitted writes`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitUsdtEnabled = false,
        )
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        publicKey.value = null
        val identityStarted = CompletableDeferred<Unit>()
        val resumeIdentity = CompletableDeferred<Unit>()
        whenever(pubkyRepo.currentPublicKey()).doSuspendableAnswer {
            identityStarted.complete(Unit)
            resumeIdentity.await()
            Result.success("pubkyself")
        }
        var deleting = false
        val preparation = async { sut.syncCurrentPublishedEndpoints(canPublish = { !deleting }) }
        identityStarted.await()
        deleting = true
        resumeIdentity.complete(Unit)
        preparation.await().getOrThrow()
        verifyBlocking(paykitSdkService, never()) { syncPublicEndpoints(any(), any()) }

        publicKey.value = "pubkyself"
        deleting = false
        val writeStarted = CompletableDeferred<Unit>()
        val resumeWrite = CompletableDeferred<Unit>()
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).doSuspendableAnswer {
            writeStarted.complete(Unit)
            resumeWrite.await()
            syncReport()
        }
        val admittedWrite = async { sut.syncCurrentPublishedEndpoints(canPublish = { !deleting }) }
        writeStarted.await()
        deleting = true
        resumeWrite.complete(Unit)
        admittedWrite.await().getOrThrow()
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(any(), any()) }
    }

    @Test
    fun `paid public invoice replacement stays pending after failure until an admitted retry succeeds`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitBolt11PaymentHash = "paid",
        )
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        var offline = true
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).thenAnswer {
            if (offline) throw AppError("offline")
            syncReport()
        }
        assertTrue(sut.refreshPublishedBolt11ForPayment("paid").isFailure)
        assertEquals("", settingsFlow.value.publicPaykitBolt11PaymentHash)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)

        sut.syncCurrentPublishedEndpoints(canPublish = { false }).getOrThrow()
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(any(), any()) }
        offline = false
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
        verifyBlocking(paykitSdkService, times(2)) { syncPublicEndpoints(any(), any()) }
    }

    @Test
    fun `SDK admission skip keeps public invoice replacement pending`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitCleanupPending = true,
        )
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        lateinit var admission: suspend () -> Boolean
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).thenAnswer {
            admission = it.getArgument(1)
            null
        }
        var canPublish = true
        sut.syncCurrentPublishedEndpoints(canPublish = { canPublish }).getOrThrow()

        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(any(), any()) }
        assertTrue(admission())
        canPublish = false
        assertFalse(admission())
    }

    @Test
    fun `admitted public publication cannot acknowledge a later paid invoice invalidation`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitOnchainEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitBolt11 = "lnbc1paid",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
        )
        stubPublicInvoice("lnbc1paid", byteArrayOf(1, 2, 3))
        val writeStarted = CompletableDeferred<Unit>()
        val resumeWrite = CompletableDeferred<Unit>()
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).doSuspendableAnswer {
            writeStarted.complete(Unit)
            resumeWrite.await()
            syncReport()
        }
        val publication = async { sut.syncCurrentPublishedEndpoints() }
        writeStarted.await()
        sut.refreshPublishedBolt11ForPayment("010203", canPublish = { false }).getOrThrow()
        resumeWrite.complete(Unit)
        publication.await().getOrThrow()

        assertEquals("", settingsFlow.value.publicPaykitBolt11PaymentHash)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `syncCurrentPublishedEndpoints waits only for session restoration and remains cancellable`() = test {
        isRestoringSession.value = true
        publicKey.value = null
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )

        val cancelledPublication = async { sut.syncCurrentPublishedEndpoints() }
        assertFalse(cancelledPublication.isCompleted)
        cancelledPublication.cancelAndJoin()
        assertTrue(cancelledPublication.isCancelled)
        verifyNoInteractions(paykitSdkService)

        val publication = async { sut.syncCurrentPublishedEndpoints() }
        assertFalse(publication.isCompleted)
        verifyNoInteractions(paykitSdkService)
        publicKey.value = "pubkyself"
        isRestoringSession.value = false

        publication.await().getOrThrow()
        verifyBlocking(paykitSdkService) { syncPaykitApp(privatePaymentsEnabled = false, priority = Priority.Ordered) }
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(any(), any()) }
        verifyBlocking(pubkyRepo, never()) { currentPublicKey() }
        verifyBlocking(pubkyRepo, never()) { awaitInitialization() }
        verifyBlocking(pubkyRepo, never()) { initialize() }
    }

    @Test
    fun `syncPublishedEndpoints creates reusable onchain endpoint when cached address is blank`() = test {
        settingsFlow.value = SettingsData(
            publicPaykitUsdtEnabled = false,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        walletState.value = WalletState()
        whenever { walletRepo.refreshReusableReceiveAddress() }.thenAnswer {
            walletState.value = WalletState(onchainAddress = "bc1qfresh")
            Result.success(Unit)
        }

        val result = sut.syncPublishedEndpoints(publish = true)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<Endpoint>>()
        verifyBlocking(walletRepo) { refreshReusableReceiveAddress() }
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(captor.capture(), any()) }
        assertEquals(listOf(MethodId.P2wpkh), captor.firstValue.map { it.methodId })
        assertEquals("bc1qfresh", captor.firstValue.single().value)
    }

    @Test
    fun `syncPublishedEndpoints does not publish endpoints when app registration fails`() = test {
        settingsFlow.value = SettingsData(
            publicPaykitUsdtEnabled = false,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        val appError = RuntimeException("app registration failed")
        whenever { paykitSdkService.syncPaykitApp(privatePaymentsEnabled = false, priority = Priority.Ordered) }
            .thenThrow(appError)

        val error = sut.syncPublishedEndpoints(publish = true).exceptionOrNull()

        assertEquals(appError, error)
        verifyBlocking(paykitSdkService, never()) { syncPublicEndpoints(any(), any()) }
    }

    @Test
    fun `syncPublishedEndpoints remove clears SDK public endpoints and metadata`() = test {
        for (priority in listOf(Priority.Ordered, Priority.Interactive)) {
            clearInvocations(paykitSdkService)
            settingsFlow.value = SettingsData(
                publicPaykitUsdtEnabled = false,
                publicPaykitBolt11 = "lnbc1old",
                publicPaykitBolt11PaymentHash = "010203",
                publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
                publicPaykitCleanupPending = true,
            )

            val result = if (priority == Priority.Ordered) {
                sut.syncPublishedEndpoints(publish = false)
            } else {
                sut.syncPublishedEndpoints(publish = false, appSyncPriority = priority)
            }

            assertTrue(result.isSuccess)
            assertEquals("", settingsFlow.value.publicPaykitBolt11)
            assertEquals(false, settingsFlow.value.publicPaykitCleanupPending)
            inOrder(paykitSdkService) {
                verify(paykitSdkService).syncPublicEndpoints(eq(emptyList()), any())
                verify(paykitSdkService).syncPaykitApp(privatePaymentsEnabled = false, priority = priority)
            }
        }
    }

    @Test
    fun `syncPublishedEndpoints remove keeps cleanup pending when app capability update fails`() = test {
        settingsFlow.value = SettingsData(publicPaykitCleanupPending = true)
        val appError = RuntimeException("app registration failed")
        whenever { paykitSdkService.syncPaykitApp(privatePaymentsEnabled = false, priority = Priority.Ordered) }
            .thenThrow(appError)

        val error = sut.syncPublishedEndpoints(publish = false).exceptionOrNull()

        assertEquals(appError, error)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(eq(emptyList()), any()) }
    }

    @Test
    fun `syncPublishedEndpoints remove preserves both cleanup failures`() = test {
        val endpointError = RuntimeException("endpoint failed")
        val appError = RuntimeException("app registration failed")
        whenever { paykitSdkService.syncPublicEndpoints(eq(emptyList()), any()) }.thenThrow(endpointError)
        whenever { paykitSdkService.syncPaykitApp(privatePaymentsEnabled = false, priority = Priority.Interactive) }
            .thenThrow(appError)

        val error = sut.syncPublishedEndpoints(
            publish = false,
            appSyncPriority = Priority.Interactive,
        ).exceptionOrNull()

        assertEquals(endpointError, error)
        assertEquals(listOf(appError), error?.suppressedExceptions)
        inOrder(paykitSdkService) {
            verify(paykitSdkService).syncPublicEndpoints(eq(emptyList()), any())
            verify(paykitSdkService).syncPaykitApp(privatePaymentsEnabled = false, priority = Priority.Interactive)
        }
    }

    @Test
    fun `syncCurrentPublishedEndpoints returns publication failed when SDK sync fails`() = test {
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).thenReturn(
            syncReport(
                failed = listOf(
                    EndpointSyncChange(
                        identifier = MethodId.P2tr.rawValue,
                        status = PublicationStatus.PENDING_PUBLICATION,
                        error = "failed",
                    ),
                ),
            ),
        )

        val error = sut.syncCurrentPublishedEndpoints().exceptionOrNull()

        assertEquals(PublicPaykitError.PublicationFailed, error)
    }

    @Test
    fun `syncCurrentPublishedEndpoints returns NoSupportedEndpoint when endpoint is required`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitUsdtEnabled = false,
            publicPaykitOnchainEnabled = false,
        )
        whenever(lightningRepo.canReceive()).thenReturn(false)

        val error = sut.syncCurrentPublishedEndpoints(requireEndpoint = true).exceptionOrNull()

        assertEquals(PublicPaykitError.NoSupportedEndpoint, error)
        verifyBlocking(paykitSdkService, never()) { syncPublicEndpoints(any(), any()) }
    }

    @Test
    fun `beginPayment opens SDK resolved public endpoint`() = test {
        whenever {
            paykitSdkService.resolvePublicContactPayment("pubkycontact")
        }.thenReturn(
            resolution(
                resolvedEndpoint(
                    methodId = MethodId.Bolt11,
                    value = PUBLIC_BOLT11,
                ),
            ),
        )
        whenever(coreService.decode(PUBLIC_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PUBLIC_BOLT11, byteArrayOf(4, 5, 6))))

        val result = sut.beginPayment("pubkycontact").getOrThrow()

        assertEquals(
            PublicPaykitPaymentResult.Opened(
                PUBLIC_BOLT11,
                endpoints = listOf(
                    Endpoint(
                        MethodId.Bolt11,
                        PUBLIC_BOLT11,
                        rawPayload = PublicPaykitRepo.serializePayload(PUBLIC_BOLT11),
                        appId = "bitkit"
                    )
                )
            ),
            result
        )
    }

    @Test
    fun `beginPayment retains payable alternatives regardless of app order`() = test {
        val unavailableInvoice = "lnbcrt1unavailable"
        val endpoints = listOf(
            resolvedEndpoint(MethodId.Bolt11, unavailableInvoice),
            resolvedEndpoint(MethodId.Bolt11, PUBLIC_BOLT11).copy(appId = "another-wallet"),
        )
        whenever(coreService.decode(unavailableInvoice)).thenThrow(IllegalArgumentException("Invalid invoice"))
        whenever(coreService.decode(PUBLIC_BOLT11))
            .thenReturn(Scanner.Lightning(lightningInvoice(PUBLIC_BOLT11, byteArrayOf(4, 5, 6))))

        for (ordered in listOf(endpoints, endpoints.reversed())) {
            whenever { paykitSdkService.resolvePublicContactPayment("pubkycontact") }
                .thenReturn(resolution(*ordered.toTypedArray()))

            val result = sut.beginPayment("pubkycontact").getOrThrow()

            assertEquals(
                PublicPaykitPaymentResult.Opened(
                    PUBLIC_BOLT11,
                    endpoints = listOf(
                        Endpoint(
                            MethodId.Bolt11,
                            PUBLIC_BOLT11,
                            rawPayload = PublicPaykitRepo.serializePayload(PUBLIC_BOLT11),
                            appId = "another-wallet"
                        )
                    )
                ),
                result
            )
        }
    }

    @Test
    fun `payment launch results have reason specific incoming request failures`() {
        assertEquals(
            null,
            PublicPaykitPaymentResult.Opened(PUBLIC_BOLT11).incomingPaymentRequestFailureReason,
        )
        assertEquals(
            IncomingPaykitPaymentRequestFailureReason.NoSupportedEndpoint,
            PublicPaykitPaymentResult.NoEndpoint.incomingPaymentRequestFailureReason,
        )
        assertEquals(
            IncomingPaykitPaymentRequestFailureReason.EndpointNotPayable,
            PublicPaykitPaymentResult.NotOpened.incomingPaymentRequestFailureReason,
        )
        assertEquals(
            IncomingPaykitPaymentRequestFailureReason.PaymentDetailsPending,
            PublicPaykitPaymentResult.WaitingForUpdatedPaymentList.incomingPaymentRequestFailureReason,
        )
        assertEquals("presentation", IncomingPaykitPaymentRequestFailureReason.RequestExpired.category)
        assertEquals("request_expired", IncomingPaykitPaymentRequestFailureReason.RequestExpired.logValue)
    }

    @Suppress("LongParameterList")
    private fun createRepo(
        pubkyRepo: PubkyRepo = this.pubkyRepo,
        walletRepo: WalletRepo = this.walletRepo,
        lightningRepo: LightningRepo = this.lightningRepo,
        coreService: CoreService = this.coreService,
        paykitSdkService: PaykitSdkService = this.paykitSdkService,
        settingsStore: SettingsStore = this.settingsStore,
        clock: Clock = this.clock,
    ) = PublicPaykitRepo(
        ioDispatcher = testDispatcher,
        pubkyRepo = pubkyRepo,
        walletRepo = walletRepo,
        lightningRepo = lightningRepo,
        coreService = coreService,
        usdtRepo = usdtRepo,
        paykitSdkService = paykitSdkService,
        settingsStore = settingsStore,
        clock = clock,
    )

    private fun resolution(vararg endpoints: PaykitResolvedPaymentEndpoint) = PaykitPublicContactPaymentResolution(
        payableEndpoints = endpoints.toList(),
    )

    private fun resolvedEndpoint(
        methodId: MethodId,
        value: String,
    ): PaykitResolvedPaymentEndpoint {
        return PaykitResolvedPaymentEndpoint(
            appId = "bitkit",
            identifier = methodId.rawValue,
            payload = PublicPaykitRepo.serializePayload(value),
        )
    }

    private fun syncReport(
        failed: List<EndpointSyncChange> = emptyList(),
    ) = EndpointSyncReport(
        published = emptyList(),
        removed = emptyList(),
        failed = failed,
    )

    private fun freshExpiryMillis() = NOW_MILLIS + 1.hours.inWholeMilliseconds

    private suspend fun stubPublicInvoice(
        bolt11: String,
        paymentHash: ByteArray,
    ) {
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(
            lightningRepo.createInvoice(
                amountSats = null,
                description = "",
                expirySeconds = PUBLIC_BOLT11_EXPIRY_SECONDS,
            )
        ).thenReturn(Result.success(bolt11))
        whenever(coreService.decode(bolt11)).thenReturn(Scanner.Lightning(lightningInvoice(bolt11, paymentHash)))
    }

    private fun lightningInvoice(bolt11: String, paymentHash: ByteArray) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = paymentHash,
        amountSatoshis = 0uL,
        timestampSeconds = 0uL,
        expirySeconds = PUBLIC_BOLT11_EXPIRY_SECONDS.toULong(),
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )
}
