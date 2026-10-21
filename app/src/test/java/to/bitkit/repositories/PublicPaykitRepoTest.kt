package to.bitkit.repositories

import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.EndpointSyncChange
import com.synonym.paykit.EndpointSyncReport
import com.synonym.paykit.PublicationStatus
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
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitPublicContactPaymentResolution
import to.bitkit.services.PaykitResolvedPaymentEndpoint
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
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
    private val addressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
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
    private val settingsFlow = MutableStateFlow(SettingsData())

    private lateinit var sut: PublicPaykitRepo

    @Before
    fun setUp() = test {
        sut = createRepo()
        settingsFlow.value = SettingsData(sharesPublicPaykitEndpoints = true, publicPaykitUsdtEnabled = false)
        publicKey.value = "pubkyself"
        isRestoringSession.value = false

        whenever(pubkyRepo.publicKey).thenReturn(publicKey)
        whenever(pubkyRepo.isRestoringSession).thenReturn(isRestoringSession)
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(NOW_MILLIS))
        whenever(paykitSdkService.syncPublicEndpoints(any(), any())).thenReturn(syncReport())
        whenever { addressReservationRepo.currentOrRotatedPublicAddress(any()) }.thenReturn(Result.success("bc1ptest"))
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
    fun `zero contact registry failures retry the private capability without publishing endpoints`() = test {
        val privateRepo = mock<PrivatePaykitRepo>()
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(emptyList()))
        whenever(pubkyRepo.currentSignIn()).thenReturn(PubkySignIn("pubkyself", 0L))
        whenever(pubkyRepo.isCurrent(any())).thenReturn(true)
        whenever(privateRepo.hasPrivatePaymentAccess()).thenReturn(true)
        whenever(privateRepo.isProfileDeletionInProgress).thenReturn(MutableStateFlow(false))
        whenever(privateRepo.enableSharingAndPrepareSavedContacts(any<() -> Collection<String>>(), any()))
            .thenReturn(Result.success(Unit))
        whenever(privateRepo.disableSharingAndPruneUnsavedContactState(any<Collection<String>>(), any()))
            .thenReturn(Result.success(Unit))
        val sharing = ContactPaymentSettingsRepo(settingsStore, sut, privateRepo, pubkyRepo, testDispatcher)
        var registryAvailable = false
        whenever(paykitSdkService.syncPaykitApp(any(), any())).doSuspendableAnswer {
            if (!registryAvailable) throw AppError("registry unavailable")
            Unit
        }
        whenever(paykitSdkService.syncPaykitApp(any(), any(), any())).doSuspendableAnswer {
            val admission = it.getArgument<suspend () -> Boolean>(2)
            if (!admission()) return@doSuspendableAnswer false
            if (!registryAvailable) throw AppError("registry unavailable")
            true
        }
        for (privateEnabled in listOf(false, true)) {
            registryAvailable = false
            settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = privateEnabled)

            assertTrue(sharing.setEnabled(privateEnabled).isFailure)
            assertTrue(settingsFlow.value.publicPaykitCleanupPending)
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
            assertEquals(privateEnabled, settingsFlow.value.sharesPrivatePaykitEndpoints)

            registryAvailable = true
            clearInvocations(paykitSdkService)
            val signIn = checkNotNull(pubkyRepo.currentSignIn())
            sut.syncPublishedEndpoints(false, Priority.Ordered) { pubkyRepo.isCurrent(signIn) }.getOrThrow()

            verifyBlocking(paykitSdkService) {
                syncPaykitApp(eq(privateEnabled), eq(Priority.Ordered), any())
            }
            verifyBlocking(paykitSdkService) { syncPublicEndpoints(eq(emptyList()), any()) }
            assertFalse(settingsFlow.value.publicPaykitCleanupPending)
            assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        }
        verifyNoInteractions(addressReservationRepo, lightningRepo)
    }

    @Test
    fun `syncCurrentPublishedEndpoints configures SDK public endpoints`() = test {
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
    fun `older publication after an acknowledged replacement keeps the replacement pending`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitOnchainEnabled = false,
            publicPaykitUsdtEnabled = false,
            publicPaykitBolt11 = "lnbc1paid",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
        )
        stubPublicInvoice("lnbc1replacement", byteArrayOf(4, 5, 6))
        val appSyncStarted = CompletableDeferred<Unit>()
        val resumeAppSync = CompletableDeferred<Unit>()
        whenever(paykitSdkService.syncPaykitApp(any(), any(), any())).doSuspendableAnswer {
            appSyncStarted.complete(Unit)
            resumeAppSync.await()
            true
        }
        val older = async { sut.syncCurrentPublishedEndpoints() }
        appSyncStarted.await()
        sut.refreshPublishedBolt11ForPayment("010203").getOrThrow()
        assertEquals("lnbc1replacement", settingsFlow.value.publicPaykitBolt11)
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
        resumeAppSync.complete(Unit)
        older.await().getOrThrow()

        val endpoints = argumentCaptor<List<Endpoint>>()
        verifyBlocking(paykitSdkService, times(2)) { syncPublicEndpoints(endpoints.capture(), any()) }
        assertEquals(listOf("lnbc1replacement", "lnbc1paid"), endpoints.allValues.map { it.single().value })
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        sut.syncCurrentPublishedEndpoints().getOrThrow()
        verifyBlocking(paykitSdkService, times(3)) { syncPublicEndpoints(endpoints.capture(), any()) }
        assertEquals("lnbc1replacement", endpoints.lastValue.single().value)
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `syncCurrentPublishedEndpoints waits only for session restoration and remains cancellable`() = test {
        isRestoringSession.value = true
        publicKey.value = null
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
        verifyBlocking(paykitSdkService) { syncPaykitApp(eq(false), eq(Priority.Ordered), any()) }
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(any(), any()) }
        verifyBlocking(pubkyRepo, never()) { currentPublicKey() }
        verifyBlocking(pubkyRepo, never()) { awaitInitialization() }
        verifyBlocking(pubkyRepo, never()) { initialize() }
    }

    @Test
    fun `syncPublishedEndpoints uses the reserved public onchain endpoint`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitUsdtEnabled = false,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        whenever { addressReservationRepo.currentOrRotatedPublicAddress(any()) }.thenReturn(Result.success("bc1qfresh"))

        val result = sut.syncPublishedEndpoints(publish = true)

        assertTrue(result.isSuccess)
        val captor = argumentCaptor<List<Endpoint>>()
        verifyBlocking(addressReservationRepo) { currentOrRotatedPublicAddress("pubkyself") }
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(captor.capture(), any()) }
        assertEquals(listOf(MethodId.P2wpkh), captor.firstValue.map { it.methodId })
        assertEquals("bc1qfresh", captor.firstValue.single().value)
    }

    @Test
    fun `syncPublishedEndpoints does not publish endpoints when app registration fails`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitUsdtEnabled = false,
            publicPaykitLightningEnabled = false,
            publicPaykitOnchainEnabled = true,
        )
        val appError = RuntimeException("app registration failed")
        whenever { paykitSdkService.syncPaykitApp(eq(false), eq(Priority.Ordered), any()) }
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
    fun `intentional public removal does not require the sharing preference to be disabled first`() = test {
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            publicPaykitBolt11 = "lnbc1old",
            publicPaykitCleanupPending = true,
        )

        sut.syncPublishedEndpoints(false).getOrThrow()

        verifyBlocking(paykitSdkService) { syncPublicEndpoints(eq(emptyList()), any()) }
        verifyBlocking(paykitSdkService) { syncPaykitApp(false, Priority.Ordered) }
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertEquals("", settingsFlow.value.publicPaykitBolt11)
        assertFalse(settingsFlow.value.publicPaykitCleanupPending)
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

    private fun createRepo(
        pubkyRepo: PubkyRepo = this.pubkyRepo,
        lightningRepo: LightningRepo = this.lightningRepo,
        coreService: CoreService = this.coreService,
        paykitSdkService: PaykitSdkService = this.paykitSdkService,
        settingsStore: SettingsStore = this.settingsStore,
        clock: Clock = this.clock,
    ) = PublicPaykitRepo(
        ioDispatcher = testDispatcher,
        pubkyRepo = pubkyRepo,
        addressReservationRepo = addressReservationRepo,
        lightningRepo = lightningRepo,
        coreService = coreService,
        usdtRepo = usdtRepo,
        paykitSdkService = paykitSdkService,
        settingsStore = settingsStore,
        clock = clock,
    )

    @Test
    fun `reservation failure prevents publication`() = test {
        settingsFlow.value = settingsFlow.value.copy(publicPaykitLightningEnabled = false)
        whenever(addressReservationRepo.currentOrRotatedPublicAddress(any()))
            .thenReturn(Result.failure(AppError("reservation storage unavailable")))

        assertTrue(sut.syncCurrentPublishedEndpoints().isFailure)
        verifyNoInteractions(paykitSdkService)
    }

    @Test
    fun `explicit publication API cannot enable sharing by itself`() = test {
        settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        sut.syncPublishedEndpoints(publish = true).getOrThrow()
        verifyNoInteractions(addressReservationRepo, paykitSdkService)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
    }

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
