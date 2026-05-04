package to.bitkit.repositories

import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.Scanner
import com.synonym.paykit.FfiPaymentEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PublicPaykitRepoTest : BaseUnitTest() {
    companion object {
        private const val NOW_MILLIS = 1_000L
    }

    @Test
    fun `syncCurrentPublishedEndpoints sets desired endpoints and removes obsolete bitkit endpoints`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val walletRepo = mock<WalletRepo>()
        val lightningRepo = mock<LightningRepo>()
        val coreService = mock<CoreService>()
        val (settingsStore, settingsFlow) = createSettingsStore()
        val sut = createRepo(
            pubkyRepo = pubkyRepo,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
            settingsStore = settingsStore,
        )

        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyself"))
        whenever(walletRepo.walletState).thenReturn(
            MutableStateFlow(
                WalletState(
                    onchainAddress = "bc1ptest",
                    bolt11 = "lnbc1user",
                ),
            ),
        )
        whenever(lightningRepo.canReceive()).thenReturn(true)
        stubPublicInvoice(lightningRepo, coreService, "lnbc1public", byteArrayOf(1, 2, 3))
        whenever(pubkyRepo.setPaymentEndpoint(any(), any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.removePaymentEndpoint(any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.getPaymentList("pubkyself")).thenReturn(
            Result.success(
                listOf(
                    paymentEntry(MethodId.Bolt11, "lnbc1old"),
                    paymentEntry(MethodId.Lnurl, "lnurl1external"),
                    paymentEntry(MethodId.P2pkh, "1obsolete"),
                ),
            ),
        )

        val result = sut.syncCurrentPublishedEndpoints()

        assertTrue(result.isSuccess)
        assertEquals("lnbc1public", settingsFlow.value.publicPaykitBolt11)
        assertEquals("010203", settingsFlow.value.publicPaykitBolt11PaymentHash)
        inOrder(pubkyRepo) {
            verify(pubkyRepo).setPaymentEndpoint(MethodId.Bolt11.rawValue, """{"value":"lnbc1public"}""")
            verify(pubkyRepo).setPaymentEndpoint(MethodId.P2tr.rawValue, """{"value":"bc1ptest"}""")
            verify(pubkyRepo).getPaymentList("pubkyself")
            verify(pubkyRepo).removePaymentEndpoint(MethodId.P2pkh.rawValue)
        }
        verify(pubkyRepo, never()).removePaymentEndpoint(MethodId.Lnurl.rawValue)
        verify(pubkyRepo, never()).removePaymentEndpoint(MethodId.Bolt11.rawValue)
        verify(pubkyRepo, never()).removePaymentEndpoint(MethodId.P2tr.rawValue)
    }

    @Test
    fun `syncPublishedEndpoints removes bitkit managed endpoints and preserves lnurl`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val (settingsStore, settingsFlow) = createSettingsStore(
            SettingsData(
                publicPaykitBolt11 = "lnbc1old",
                publicPaykitBolt11PaymentHash = "010203",
                publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
            ),
        )
        val sut = createRepo(pubkyRepo = pubkyRepo, settingsStore = settingsStore)

        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyself"))
        whenever(pubkyRepo.removePaymentEndpoint(any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.getPaymentList("pubkyself")).thenReturn(
            Result.success(
                listOf(
                    paymentEntry(MethodId.Bolt11, "lnbc1old"),
                    paymentEntry(MethodId.Lnurl, "lnurl1external"),
                    paymentEntry(MethodId.P2tr, "bc1pold"),
                ),
            ),
        )

        val result = sut.syncPublishedEndpoints(publish = false)

        assertTrue(result.isSuccess)
        assertEquals("", settingsFlow.value.publicPaykitBolt11)
        verify(pubkyRepo).removePaymentEndpoint(MethodId.Bolt11.rawValue)
        verify(pubkyRepo).removePaymentEndpoint(MethodId.P2tr.rawValue)
        verify(pubkyRepo, never()).removePaymentEndpoint(MethodId.Lnurl.rawValue)
    }

    @Test
    fun `syncCurrentPublishedEndpoints reuses fresh public bolt11`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val walletRepo = mock<WalletRepo>()
        val lightningRepo = mock<LightningRepo>()
        val coreService = mock<CoreService>()
        val clock = createClock()
        val (settingsStore) = createSettingsStore(
            SettingsData(
                publicPaykitBolt11 = "lnbc1cached",
                publicPaykitBolt11PaymentHash = "010203",
                publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
            ),
        )
        val sut = createRepo(
            pubkyRepo = pubkyRepo,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
            settingsStore = settingsStore,
            clock = clock,
        )

        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyself"))
        whenever(walletRepo.walletState).thenReturn(MutableStateFlow(WalletState()))
        whenever(lightningRepo.canReceive()).thenReturn(true)
        whenever(pubkyRepo.setPaymentEndpoint(any(), any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.getPaymentList("pubkyself")).thenReturn(Result.success(emptyList()))

        val result = sut.syncCurrentPublishedEndpoints()

        assertTrue(result.isSuccess)
        verify(lightningRepo, never()).createInvoice(amountSats = null, description = "", expirySeconds = 86_400u)
        verify(coreService, never()).decode(any())
        verify(pubkyRepo).setPaymentEndpoint(MethodId.Bolt11.rawValue, """{"value":"lnbc1cached"}""")
    }

    @Test
    fun `refreshPublishedBolt11ForPayment rotates paid public bolt11`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val walletRepo = mock<WalletRepo>()
        val lightningRepo = mock<LightningRepo>()
        val coreService = mock<CoreService>()
        val (settingsStore, settingsFlow) = createSettingsStore(
            SettingsData(
                sharesPublicPaykitEndpoints = true,
                publicPaykitBolt11 = "lnbc1old",
                publicPaykitBolt11PaymentHash = "010203",
                publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
            ),
        )
        val sut = createRepo(
            pubkyRepo = pubkyRepo,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
            settingsStore = settingsStore,
        )

        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyself"))
        whenever(walletRepo.walletState).thenReturn(MutableStateFlow(WalletState()))
        whenever(lightningRepo.canReceive()).thenReturn(true)
        stubPublicInvoice(lightningRepo, coreService, "lnbc1new", byteArrayOf(4, 5, 6))
        whenever(pubkyRepo.setPaymentEndpoint(any(), any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.getPaymentList("pubkyself")).thenReturn(
            Result.success(listOf(paymentEntry(MethodId.Bolt11, "lnbc1old"))),
        )

        val result = sut.refreshPublishedBolt11ForPayment("010203")

        assertTrue(result.isSuccess)
        assertEquals("lnbc1new", settingsFlow.value.publicPaykitBolt11)
        assertEquals("040506", settingsFlow.value.publicPaykitBolt11PaymentHash)
        verify(pubkyRepo).setPaymentEndpoint(MethodId.Bolt11.rawValue, """{"value":"lnbc1new"}""")
    }

    @Test
    fun `refreshPublishedBolt11ForPayment ignores unrelated payment hash`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val lightningRepo = mock<LightningRepo>()
        val coreService = mock<CoreService>()
        val (settingsStore) = createSettingsStore(
            SettingsData(
                sharesPublicPaykitEndpoints = true,
                publicPaykitBolt11 = "lnbc1old",
                publicPaykitBolt11PaymentHash = "010203",
                publicPaykitBolt11ExpiresAtMillis = freshExpiryMillis(),
            ),
        )
        val sut = createRepo(
            pubkyRepo = pubkyRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
            settingsStore = settingsStore,
        )

        val result = sut.refreshPublishedBolt11ForPayment("unrelated")

        assertTrue(result.isSuccess)
        verify(lightningRepo, never()).createInvoice(amountSats = null, description = "", expirySeconds = 86_400u)
        verify(pubkyRepo, never()).setPaymentEndpoint(any(), any())
    }

    @Test
    fun `syncCurrentPublishedEndpoints returns SessionNotActive when no pubky session exists`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val walletRepo = mock<WalletRepo>()
        val sut = createRepo(pubkyRepo = pubkyRepo, walletRepo = walletRepo)

        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.currentPublicKey()).thenReturn(Result.success(null))
        whenever(walletRepo.walletState).thenReturn(MutableStateFlow(WalletState(onchainAddress = "bc1ptest")))

        val error = sut.syncCurrentPublishedEndpoints().exceptionOrNull()

        assertEquals(PublicPaykitError.SessionNotActive, error)
        verify(pubkyRepo, never()).setPaymentEndpoint(any(), any())
    }

    @Test
    fun `parseEndpoint accepts Paykit JSON payloads`() {
        val endpoint = PublicPaykitRepo.parseEndpoint(
            methodId = "btc-lightning-bolt11",
            endpointData = """{"value":" lnbc1test ","min":"1","max":"10","extra":"ignored"}""",
        )

        assertEquals(MethodId.Bolt11, endpoint?.methodId)
        assertEquals("lnbc1test", endpoint?.value)
        assertEquals("1", endpoint?.min)
        assertEquals("10", endpoint?.max)
    }

    @Test
    fun `parseEndpoint rejects legacy lnurl pay id`() {
        val endpoint = PublicPaykitRepo.parseEndpoint(
            methodId = "btc-lightning-lnurl-pay",
            endpointData = """{"value":"lnurl1test"}""",
        )

        assertNull(endpoint)
    }

    @Test
    fun `parseEndpoint rejects raw string payloads`() {
        val endpoint = PublicPaykitRepo.parseEndpoint(
            methodId = "btc-bitcoin-p2wpkh",
            endpointData = "bc1qexampleaddress",
        )

        assertNull(endpoint)
    }

    @Test
    fun `parseEndpoint rejects unsupported method ids`() {
        val endpoint = PublicPaykitRepo.parseEndpoint(
            methodId = "btc-lightning-bolt12",
            endpointData = """{"value":"lni1test"}""",
        )

        assertNull(endpoint)
    }

    @Test
    fun `parseEndpoint accepts lnurl method id`() {
        val endpoint = PublicPaykitRepo.parseEndpoint(
            methodId = "btc-lightning-lnurl",
            endpointData = """{"value":"lnurl1test"}""",
        )

        assertEquals(MethodId.Lnurl, endpoint?.methodId)
        assertEquals("lnurl1test", endpoint?.value)
    }

    @Test
    fun `serializePayload trims and wraps value`() {
        assertEquals("""{"value":"bc1ptest"}""", PublicPaykitRepo.serializePayload(" bc1ptest "))
    }

    @Test
    fun `serializePayload rejects empty values`() {
        assertFailsWith<PublicPaykitError.InvalidPayload> {
            PublicPaykitRepo.serializePayload("   ")
        }
    }

    @Test
    fun `paymentRequest prefers bip21 with bolt11 when both are payable`() {
        val request = PublicPaykitRepo.paymentRequest(
            listOf(
                endpoint(MethodId.Bolt11, "lnbc1test"),
                endpoint(MethodId.P2tr, "bc1ptest"),
            ),
        )

        assertEquals("bitcoin:bc1ptest?lightning=lnbc1test", request)
    }

    @Test
    fun `paymentRequest encodes bolt11 query parameter`() {
        val request = PublicPaykitRepo.paymentRequest(
            listOf(
                endpoint(MethodId.Bolt11, "lnbc1test&label"),
                endpoint(MethodId.P2tr, "bc1ptest"),
            ),
        )

        assertEquals("bitcoin:bc1ptest?lightning=lnbc1test%26label", request)
    }

    @Test
    fun `paymentRequest prefers taproot among multiple onchain endpoints`() {
        val request = PublicPaykitRepo.paymentRequest(
            listOf(
                endpoint(MethodId.P2wpkh, "bc1qtest"),
                endpoint(MethodId.P2tr, "bc1ptest"),
            ),
        )

        assertEquals("bc1ptest", request)
    }

    @Test
    fun `paymentRequest falls back to preferred single endpoint`() {
        assertEquals(
            "lnbc1test",
            PublicPaykitRepo.paymentRequest(listOf(endpoint(MethodId.Bolt11, "lnbc1test"))),
        )
        assertEquals(
            "lnurl1test",
            PublicPaykitRepo.paymentRequest(listOf(endpoint(MethodId.Lnurl, "lnurl1test"))),
        )
        assertEquals(
            "bc1ptest",
            PublicPaykitRepo.paymentRequest(listOf(endpoint(MethodId.P2tr, "bc1ptest"))),
        )
    }

    @Test
    fun `paymentRequest prefers lnurl over onchain when bolt11 is unavailable`() {
        val request = PublicPaykitRepo.paymentRequest(
            listOf(
                endpoint(MethodId.P2tr, "bc1ptest"),
                endpoint(MethodId.Lnurl, "lnurl1test"),
            ),
        )

        assertEquals("lnurl1test", request)
    }

    @Test
    fun `paymentRequest returns empty string for empty endpoints`() {
        assertEquals("", PublicPaykitRepo.paymentRequest(emptyList()))
    }

    @Test
    fun `method ids match Paykit grammar`() {
        val pattern = Regex("^[a-z0-9]+-[a-z0-9]+-[a-z0-9]+$")

        MethodId.entries.forEach {
            assertTrue(pattern.matches(it.rawValue), "Invalid method id '${it.rawValue}'")
        }
    }

    @Test
    fun `onchainMethodId selects address method id`() {
        assertEquals(MethodId.P2tr, PublicPaykitRepo.onchainMethodId("bc1ptest"))
        assertEquals(MethodId.P2wpkh, PublicPaykitRepo.onchainMethodId("tb1qtest"))
        assertEquals(MethodId.P2sh, PublicPaykitRepo.onchainMethodId("2test"))
        assertEquals(MethodId.P2pkh, PublicPaykitRepo.onchainMethodId("1test"))
    }

    private fun endpoint(methodId: MethodId, value: String) = Endpoint(
        methodId = methodId,
        value = value,
        rawPayload = """{"value":"$value"}""",
    )

    @Suppress("LongParameterList")
    private fun createRepo(
        pubkyRepo: PubkyRepo = mock(),
        walletRepo: WalletRepo = mock(),
        lightningRepo: LightningRepo = mock(),
        coreService: CoreService = mock(),
        settingsStore: SettingsStore = createSettingsStore().first,
        clock: Clock = createClock(),
    ) = PublicPaykitRepo(
        ioDispatcher = testDispatcher,
        pubkyRepo = pubkyRepo,
        walletRepo = walletRepo,
        lightningRepo = lightningRepo,
        coreService = coreService,
        settingsStore = settingsStore,
        clock = clock,
    )

    private fun paymentEntry(methodId: MethodId, value: String) = FfiPaymentEntry(
        methodId = methodId.rawValue,
        endpointData = """{"value":"$value"}""",
    )

    private fun createSettingsStore(
        initial: SettingsData = SettingsData(),
    ): Pair<SettingsStore, MutableStateFlow<SettingsData>> {
        val settingsStore = mock<SettingsStore>()
        val flow = MutableStateFlow(initial)
        whenever(settingsStore.data).thenReturn(flow)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            flow.value = transform(flow.value)
            Unit
        }
        return settingsStore to flow
    }

    private fun createClock(): Clock {
        val clock = mock<Clock>()
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(NOW_MILLIS))
        return clock
    }

    private fun freshExpiryMillis() = NOW_MILLIS + 1.hours.inWholeMilliseconds

    private suspend fun stubPublicInvoice(
        lightningRepo: LightningRepo,
        coreService: CoreService,
        bolt11: String,
        paymentHash: ByteArray,
    ) {
        whenever(
            lightningRepo.createInvoice(amountSats = null, description = "", expirySeconds = 86_400u)
        ).thenReturn(Result.success(bolt11))
        whenever(coreService.decode(bolt11)).thenReturn(Scanner.Lightning(lightningInvoice(bolt11, paymentHash)))
    }

    private fun lightningInvoice(bolt11: String, paymentHash: ByteArray) = LightningInvoice(
        bolt11 = bolt11,
        paymentHash = paymentHash,
        amountSatoshis = 0u,
        timestampSeconds = 0u,
        expirySeconds = 86_400u,
        isExpired = false,
        description = "",
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
    )
}
