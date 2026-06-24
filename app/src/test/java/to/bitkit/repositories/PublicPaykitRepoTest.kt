package to.bitkit.repositories

import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.EndpointSyncChange
import com.synonym.paykit.EndpointSyncReport
import com.synonym.paykit.PaymentEndpointSource
import com.synonym.paykit.PublicationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitContactPaymentResolution
import to.bitkit.services.PaykitResolvedPaymentEndpoint
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
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

    private val publicKey = MutableStateFlow<String?>("pubkyself")
    private val walletState = MutableStateFlow(WalletState())
    private val settingsFlow = MutableStateFlow(SettingsData())

    private lateinit var sut: PublicPaykitRepo

    @Before
    fun setUp() = test {
        sut = createRepo()
        settingsFlow.value = SettingsData()
        publicKey.value = "pubkyself"
        walletState.value = WalletState()

        whenever(pubkyRepo.publicKey).thenReturn(publicKey)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(NOW_MILLIS))
        whenever(paykitSdkService.syncPublicEndpoints(any())).thenReturn(syncReport())
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
    fun `syncCurrentPublishedEndpoints configures SDK public endpoints`() = test {
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        stubPublicInvoice("lnbc1public", byteArrayOf(1, 2, 3))

        val result = sut.syncCurrentPublishedEndpoints()

        assertTrue(result.isSuccess)
        assertEquals("lnbc1public", settingsFlow.value.publicPaykitBolt11)
        assertEquals("010203", settingsFlow.value.publicPaykitBolt11PaymentHash)

        val captor = argumentCaptor<List<Endpoint>>()
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(captor.capture()) }
        assertEquals(
            listOf(MethodId.Bolt11, MethodId.P2tr),
            captor.firstValue.map { it.methodId },
        )
    }

    @Test
    fun `syncPublishedEndpoints remove clears SDK public endpoints and metadata`() = test {
        settingsFlow.value = SettingsData(
            publicPaykitBolt11 = "lnbc1old",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
            publicPaykitCleanupPending = true,
        )

        val result = sut.syncPublishedEndpoints(publish = false)

        assertTrue(result.isSuccess)
        assertEquals("", settingsFlow.value.publicPaykitBolt11)
        assertEquals(false, settingsFlow.value.publicPaykitCleanupPending)
        verifyBlocking(paykitSdkService) { syncPublicEndpoints(emptyList()) }
    }

    @Test
    fun `syncCurrentPublishedEndpoints returns publication failed when SDK sync fails`() = test {
        walletState.value = WalletState(onchainAddress = "bc1ptest")
        whenever(paykitSdkService.syncPublicEndpoints(any())).thenReturn(
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
        settingsFlow.value = SettingsData(publicPaykitOnchainEnabled = false)
        whenever(lightningRepo.canReceive()).thenReturn(false)

        val error = sut.syncCurrentPublishedEndpoints(requireEndpoint = true).exceptionOrNull()

        assertEquals(PublicPaykitError.NoSupportedEndpoint, error)
        verifyBlocking(paykitSdkService, never()) { syncPublicEndpoints(any()) }
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

        assertEquals(PublicPaykitPaymentResult.Opened(PUBLIC_BOLT11), result)
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
        paykitSdkService = paykitSdkService,
        settingsStore = settingsStore,
        clock = clock,
    )

    private fun resolution(vararg endpoints: PaykitResolvedPaymentEndpoint) = PaykitContactPaymentResolution(
        payableEndpoints = endpoints.toList(),
    )

    private fun resolvedEndpoint(
        methodId: MethodId,
        value: String,
    ): PaykitResolvedPaymentEndpoint {
        return PaykitResolvedPaymentEndpoint(
            counterparty = "pubkycontact",
            source = PaymentEndpointSource.PUBLIC_PAYMENT_ENDPOINT,
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
