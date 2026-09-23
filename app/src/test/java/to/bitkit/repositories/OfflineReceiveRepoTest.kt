package to.bitkit.repositories

import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineReceiveRepoTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val nodeEvents = MutableSharedFlow<NodeEventUpdate>()
    private val walletState = MutableStateFlow(
        WalletState(
            walletExists = true,
            onchainAddress = "address",
            selectedTags = persistentListOf("Dinner", "Friends"),
        )
    )
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
        payeePubkey = "payee",
    )
    private lateinit var sut: OfflineReceiveRepo

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(lightningRepo.nodeEventUpdates).thenReturn(nodeEvents)
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever { lightningRepo.listPaymentsOrNull() }.thenReturn(emptyList())
        whenever { preActivityMetadataRepo.upsertPreActivityMetadata(any()) }.thenReturn(Result.success(Unit))
        whenever { walletRepo.inboundLiquiditySats() }.thenReturn(1_000uL)
        whenever(invoiceParser.parse("prepared-ffor-invoice")).thenReturn(invoiceDetails)
        sut = OfflineReceiveRepo(
            service,
            walletRepo,
            lightningRepo,
            invoiceParser,
            preActivityMetadataRepo,
            testDispatcher,
        )
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

    @Test
    fun `confirmed matching payment clears prepared invoice and persists selected metadata once`() = test {
        val invoice = sut.prepareInvoice(request).getOrThrow()
        assertNull(sut.session.value.invoice)
        sut.showInvoice(invoice).getOrThrow()
        sut.showInvoice(invoice).getOrThrow()

        nodeEvents.emit(NodeEventUpdate(received("hash")))
        nodeEvents.emit(NodeEventUpdate(received("hash")))

        assertNull(sut.session.value.invoice)
        assertTrue(sut.session.value.isSettled)
        assertTrue(sut.showInvoice(invoice).isFailure)
        val metadata = argumentCaptor<List<PreActivityMetadata>>()
        verify(preActivityMetadataRepo, times(1)).upsertPreActivityMetadata(metadata.capture())
        assertEquals("hash", metadata.firstValue.single().paymentId)
        assertEquals("hash", metadata.firstValue.single().paymentHash)
        assertEquals(listOf("Dinner", "Friends"), metadata.firstValue.single().tags)
        assertTrue(metadata.firstValue.single().isReceive)
    }

    @Test
    fun `unrelated and claimable payments preserve prepared invoice without extra metadata writes`() = test {
        val invoice = sut.prepareInvoice(request).getOrThrow()
        sut.showInvoice(invoice).getOrThrow()

        nodeEvents.emit(NodeEventUpdate(received("unrelated")))
        nodeEvents.emit(NodeEventUpdate(Event.PaymentClaimable("hash", "hash", 1_000_000uL, null, emptyList())))

        assertEquals(invoice, sut.session.value.invoice)
        assertFalse(sut.session.value.isSettled)
        verify(preActivityMetadataRepo, times(1)).upsertPreActivityMetadata(any())
    }

    @Test
    fun `metadata persistence failure prevents QR exposure`() = test {
        val invoice = sut.prepareInvoice(request).getOrThrow()
        whenever(preActivityMetadataRepo.upsertPreActivityMetadata(any()))
            .thenReturn(Result.failure(IllegalStateException("Storage unavailable")))

        assertTrue(sut.showInvoice(invoice).isFailure)
        assertNull(sut.session.value.invoice)
    }

    @Test
    fun `settlement before retry reply is rejected using authoritative payment history`() = test {
        service.failNext = true
        assertTrue(sut.prepareInvoice(request).isFailure)
        service.reply = CompletableDeferred()
        val preparation = async { sut.prepareInvoice(request) }
        nodeEvents.emit(NodeEventUpdate(received("hash")))
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(settledPayment()))
        service.reply?.complete(Unit)

        assertTrue(preparation.await().isFailure)
        assertNull(sut.session.value.invoice)
        verify(preActivityMetadataRepo, times(0)).upsertPreActivityMetadata(any())
    }

    @Test
    fun `unpaid prepared voucher does not count as a settled payment`() = test {
        val unpaidVoucher = settledPayment().copy(status = PaymentStatus.PENDING)
        whenever(lightningRepo.listPaymentsOrNull()).thenReturn(listOf(unpaidVoucher))
        val invoice = sut.prepareInvoice(request).getOrThrow()

        assertNull(sut.session.value.invoice)
        assertFalse(sut.session.value.isSettled)
        verify(preActivityMetadataRepo, times(0)).upsertPreActivityMetadata(any())
        sut.showInvoice(invoice).getOrThrow()
        assertEquals(invoice, sut.session.value.invoice)
    }

    @Test
    fun `settlement during metadata persistence prevents stale QR exposure`() = test {
        val invoice = sut.prepareInvoice(request).getOrThrow()
        val saving = CompletableDeferred<Unit>()
        val finishSaving = CompletableDeferred<Unit>()
        whenever(preActivityMetadataRepo.upsertPreActivityMetadata(any())).doSuspendableAnswer {
            saving.complete(Unit)
            finishSaving.await()
            Result.success(Unit)
        }
        val display = async { sut.showInvoice(invoice) }
        saving.await()
        nodeEvents.emit(NodeEventUpdate(received("hash")))
        finishSaving.complete(Unit)

        assertTrue(display.await().isFailure)
        assertNull(sut.session.value.invoice)
    }

    @Test
    fun `settlement racing with payment lookup forces a fresh lookup before registration`() = test {
        val lookupStarted = CompletableDeferred<Unit>()
        val resumeLookup = CompletableDeferred<Unit>()
        var lookups = 0
        whenever(lightningRepo.listPaymentsOrNull()).doSuspendableAnswer {
            lookups++
            if (lookups == 1) {
                lookupStarted.complete(Unit)
                resumeLookup.await()
                emptyList()
            } else {
                listOf(settledPayment())
            }
        }
        val preparation = async { sut.prepareInvoice(request) }
        lookupStarted.await()
        nodeEvents.emit(NodeEventUpdate(received("hash")))
        resumeLookup.complete(Unit)

        assertTrue(preparation.await().isFailure)
        assertEquals(2, lookups)
        assertNull(sut.session.value.invoice)
    }

    @Test
    fun `wallet reset clears prepared state and prevents invoice reuse`() = test {
        val invoice = sut.prepareInvoice(request).getOrThrow()
        sut.showInvoice(invoice).getOrThrow()

        walletState.value = WalletState(walletExists = false)

        assertEquals(OfflineReceiveSession(), sut.session.value)
        assertTrue(sut.showInvoice(invoice).isFailure)
    }

    private fun received(hash: String) = Event.PaymentReceived(hash, hash, 1_000_000uL, emptyList())

    private fun settledPayment() = PaymentDetails(
        id = "hash",
        kind = PaymentKind.Bolt11("hash", null, null, "Dinner", "prepared-ffor-invoice"),
        amountMsat = 1_000_000uL,
        feePaidMsat = null,
        direction = PaymentDirection.INBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = 2_000_000_000uL,
    )

    private class FakeOfflineReceiveService : OfflineReceiveService {
        var available = true
        var prepareCalls = 0
        var failNext = false
        var reply: CompletableDeferred<Unit>? = null
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
            reply?.await()
            if (failNext) {
                failNext = false
                return Result.failure(OfflineReceiveUnavailable())
            }
            return Result.success(invoice)
        }
    }
}
