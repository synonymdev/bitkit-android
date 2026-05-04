package to.bitkit.repositories

import com.synonym.paykit.FfiPaymentEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicPaykitRepoTest : BaseUnitTest() {
    @Test
    fun `syncCurrentPublishedEndpoints sets desired endpoints and removes obsolete endpoints`() = test {
        val pubkyRepo = mock<PubkyRepo>()
        val walletRepo = mock<WalletRepo>()
        val lightningRepo = mock<LightningRepo>()
        val coreService = mock<CoreService>()
        val sut = PublicPaykitRepo(
            ioDispatcher = testDispatcher,
            pubkyRepo = pubkyRepo,
            walletRepo = walletRepo,
            lightningRepo = lightningRepo,
            coreService = coreService,
        )

        whenever(pubkyRepo.publicKey).thenReturn(MutableStateFlow("pubkyself"))
        whenever(walletRepo.walletState).thenReturn(
            MutableStateFlow(
                WalletState(
                    onchainAddress = "bc1ptest",
                    bolt11 = "lnbc1test",
                ),
            ),
        )
        whenever(pubkyRepo.setPaymentEndpoint(any(), any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.removePaymentEndpoint(any())).thenReturn(Result.success(Unit))
        whenever(pubkyRepo.getPaymentList("pubkyself")).thenReturn(
            Result.success(
                listOf(
                    paymentEntry(MethodId.Bolt11, "lnbc1old"),
                    paymentEntry(MethodId.P2pkh, "1obsolete"),
                ),
            ),
        )

        val result = sut.syncCurrentPublishedEndpoints()

        assertTrue(result.isSuccess)
        inOrder(pubkyRepo) {
            verify(pubkyRepo).setPaymentEndpoint(MethodId.Bolt11.rawValue, """{"value":"lnbc1test"}""")
            verify(pubkyRepo).setPaymentEndpoint(MethodId.P2tr.rawValue, """{"value":"bc1ptest"}""")
            verify(pubkyRepo).getPaymentList("pubkyself")
            verify(pubkyRepo).removePaymentEndpoint(MethodId.P2pkh.rawValue)
        }
        verify(pubkyRepo, never()).removePaymentEndpoint(MethodId.Bolt11.rawValue)
        verify(pubkyRepo, never()).removePaymentEndpoint(MethodId.P2tr.rawValue)
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

    private fun paymentEntry(methodId: MethodId, value: String) = FfiPaymentEntry(
        methodId = methodId.rawValue,
        endpointData = """{"value":"$value"}""",
    )
}
