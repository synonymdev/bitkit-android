package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.env.Env
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.OfflineInvoiceDetails
import to.bitkit.services.OfflineReceiveInvoiceParser
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import to.bitkit.services.UnavailableOfflineReceiveService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineReceiveRepoTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val lightningState = MutableStateFlow(LightningState(nodeLifecycleState = NodeLifecycleState.Running))
    private val request = OfflineReceiveRequest("request", 1_000uL, "Dinner")
    private val service = FakeOfflineReceiveService()
    private val invoiceParser = mock<OfflineReceiveInvoiceParser>()
    private val invoiceDetails = OfflineInvoiceDetails(
        amountMsat = 1_000_000uL,
        description = "Dinner",
        network = Env.network,
        timestampSeconds = 2_000_000_000uL,
        expirySeconds = 3_600uL,
        paymentHash = "hash",
    )
    private lateinit var sut: OfflineReceiveRepo

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever { walletRepo.inboundLiquiditySats() }.thenReturn(1_000uL)
        whenever(invoiceParser.parse("prepared-ffor-invoice")).thenReturn(invoiceDetails)
        sut = OfflineReceiveRepo(service, walletRepo, lightningRepo, invoiceParser, testDispatcher)
    }

    @Test
    fun `eligibility requires a positive amount within current liquidity`() = test {
        assertFalse(sut.canReceive(0uL).getOrThrow())
        assertFalse(sut.canReceive(1_001uL).getOrThrow())
        assertTrue(sut.canReceive(1_000uL).getOrThrow())
        assertEquals(listOf(1_000uL), service.eligibilityRequests)
    }

    @Test
    fun `eligibility requires the node online for activation`() = test {
        lightningState.value = LightningState()

        assertFalse(sut.canReceive(1_000uL).getOrThrow())
        assertTrue(service.eligibilityRequests.isEmpty())
    }

    @Test
    fun `preparation rechecks liquidity before calling provider`() = test {
        assertTrue(sut.canReceive(1_000uL).getOrThrow())
        whenever(walletRepo.inboundLiquiditySats()).thenReturn(999uL)

        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())
        assertEquals(0, service.prepareCalls)
    }

    @Test
    fun `preparation preserves exact amount and description`() = test {
        val result = sut.prepareInvoice(request).getOrThrow()

        assertEquals(service.invoice, result)
        assertEquals(1, service.prepareCalls)
        assertEquals(listOf(1_000uL), service.eligibilityRequests)
    }

    @Test
    fun `preparation rejects unavailable provider without ordinary fallback`() = test {
        service.available = false

        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())
        assertEquals(0, service.prepareCalls)
    }

    @Test
    fun `preparation rejects mismatched and expired provider invoices`() = test {
        listOf(
            service.invoice.copy(amountSats = 999uL),
            service.invoice.copy(description = "Other"),
            service.invoice.copy(bolt11 = ""),
            service.invoice.copy(expiresAtMillis = 0),
        ).forEach {
            service.invoice = it
            assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())
        }
    }

    @Test
    fun `production provider cannot issue offline invoices with existing bindings`() = test {
        val provider = UnavailableOfflineReceiveService()

        assertFalse(provider.canReceive(1_000uL).getOrThrow())
        assertIs<OfflineReceiveUnavailable>(provider.prepareInvoice(request).exceptionOrNull())
    }

    @Test
    fun `preparation validates encoded invoice amount network hash description and expiry`() = test {
        val otherNetwork = if (Env.network == Network.BITCOIN) Network.REGTEST else Network.BITCOIN
        listOf(
            invoiceDetails.copy(amountMsat = null),
            invoiceDetails.copy(amountMsat = 999_999uL),
            invoiceDetails.copy(network = otherNetwork),
            invoiceDetails.copy(paymentHash = "different-hash"),
            invoiceDetails.copy(description = "Other"),
            invoiceDetails.copy(expirySeconds = 3_601uL),
            invoiceDetails.copy(expirySeconds = ULong.MAX_VALUE),
        ).forEach {
            whenever(invoiceParser.parse("prepared-ffor-invoice")).thenReturn(it)
            assertTrue(sut.prepareInvoice(request).isFailure)
        }
    }

    @Test
    fun `preparation rejects malformed invoice parsing`() = test {
        whenever(invoiceParser.parse("prepared-ffor-invoice")).thenThrow(IllegalArgumentException("Invalid signature"))

        assertTrue(sut.prepareInvoice(request).isFailure)
    }

    @Test
    fun `failed preparation retries identical operation after liquidity reservation`() = test {
        service.failNext = true
        assertTrue(sut.prepareInvoice(request).isFailure)
        whenever(walletRepo.inboundLiquiditySats()).thenReturn(0uL)

        assertTrue(sut.prepareInvoice(request).isSuccess)
        assertEquals(listOf(request, request), service.preparationRequests)
        assertEquals(listOf(1_000uL), service.eligibilityRequests)
    }

    @Test
    fun `operation identity cannot be reused with different parameters`() = test {
        assertTrue(sut.prepareInvoice(request).isSuccess)

        assertTrue(sut.prepareInvoice(request.copy(description = "Other")).isFailure)
        assertEquals(1, service.prepareCalls)
    }

    private class FakeOfflineReceiveService : OfflineReceiveService {
        var available = true
        var prepareCalls = 0
        var failNext = false
        val eligibilityRequests = mutableListOf<ULong>()
        val preparationRequests = mutableListOf<OfflineReceiveRequest>()
        var invoice = PreparedOfflineInvoice("prepared-ffor-invoice", 1_000uL, "Dinner", 2_000_003_600_000L, "hash")

        override suspend fun canReceive(amountSats: ULong): Result<Boolean> {
            eligibilityRequests += amountSats
            return Result.success(available)
        }

        override suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> {
            prepareCalls++
            preparationRequests += request
            if (failNext) {
                failNext = false
                return Result.failure(OfflineReceiveUnavailable())
            }
            return Result.success(invoice)
        }
    }
}
