@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentProof
import com.synonym.bitkitcore.UsdtPaymentProofBinding
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.UsdtVerifiedPayment
import com.synonym.bitkitcore.UsdtWallet
import com.synonym.bitkitcore.usdtParsePaymentRequest
import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.ConversionRate
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PaymentConversionQuoteRecord
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.models.PaykitUsdt
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.UsdtService
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.ContactPaymentContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class PaykitUsdtPaymentRepoTest : BaseUnitTest() {
    private val identity = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    private val contact = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    private val address = "0x1111111111111111111111111111111111111111"
    private val keychain: Keychain = mock()
    private val sdk: PaykitSdkService = mock()
    private val proofs: PaykitPaymentProofRepo = mock()
    private val usdt: UsdtRepo = mock()
    private val service: UsdtService = mock()
    private val wallet: UsdtWallet = mock()
    private val clock = object : Clock { override fun now() = Instant.fromEpochSeconds(1_800_000_000) }
    private var stored: String? = null
    private lateinit var core: MockedStatic<*>
    private val request = PaykitPaymentRequest(
        paymentRequestId = "550e8400-e29b-41d4-a716-446655440000",
        counterparty = contact,
        amount = PaykitAmount(PaykitAsset.USD, 5u),
        paymentReference = "payment-reference",
        pricing = PaykitRequestPricing(PaymentConversion.Fixed(listOf(ConversionRate("usdt", "1")))),
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf(MethodId.UsdtArbitrum.rawValue),
    )
    private val quote = UsdtQuote(
        id = "quote",
        bridgeProvider = null,
        recipient = address,
        destination = UsdtDestination.ARBITRUM,
        amount = 50_000u,
        receivedAmount = 50_000u,
        maximumFee = 100u,
        expiresAt = 1_800_000_100u,
    )
    private val amount = PaykitAmount(PaykitAsset.USDT, 50_000u)

    @Before
    fun setUp() = test {
        core = Mockito.mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt"))
        core.`when`<UsdtPaymentRequest> { usdtParsePaymentRequest(address) }
            .thenReturn(UsdtPaymentRequest(address, null, null))
        val endpoint = PaykitUsdt.endpoint(address)
        whenever(keychain.loadString(any())).thenAnswer { stored }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer { stored = it.getArgument(1) }
        whenever(
            sdk.identityStatus()
        ).thenReturn(IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(sdk.paymentRequests()).thenReturn(emptyList())
        whenever(usdt.paymentEndpoint()).thenReturn(Result.success(endpoint))
        whenever(
            service.paymentProof(any(), any())
        ).thenReturn(UsdtPaymentProof("42161", "0x" + "a".repeat(64), "0", "0x" + "b".repeat(130)))
        whenever(service.wallet()).thenReturn(wallet)
        whenever(usdt.operation<Any?>(any())).doSuspendableAnswer {
            val block = it.getArgument<suspend UsdtService.() -> Any?>(0)
            runCatching { block(service) }
        }
        stubStoredTransfer(Result.success(null))
    }

    @After
    fun tearDown() { if (::core.isInitialized) core.close() }

    private fun stubStoredTransfer(result: Result<UsdtTransfer?>) {
        result.getOrNull()?.let { whenever(it.id).thenReturn(quote.id) }
        org.mockito.kotlin.doAnswer { result.getOrThrow()?.let(::listOf).orEmpty() }.whenever(wallet).history()
    }

    private suspend fun stubVerification(
        first: Result<UsdtVerifiedPayment?>,
        second: Result<UsdtVerifiedPayment?>? = null,
    ) {
        val results = listOfNotNull(first, second)
        var index = 0
        whenever(wallet.verifyPaymentProof(any(), any())).doSuspendableAnswer {
            results[(index++).coerceAtMost(results.lastIndex)].getOrThrow()
        }
    }

    private suspend fun stubSend(result: Result<UsdtTransfer>) {
        whenever(usdt.send(any(), any())).doSuspendableAnswer { invocation ->
            runSuspendCatching {
                invocation.getArgument<suspend () -> Unit>(1).invoke()
                result.getOrThrow()
            }
        }
    }

    private fun repo() = PaykitUsdtPaymentRepo(testDispatcher, keychain, sdk, proofs, usdt, clock)
    private fun context(request: PaykitPaymentRequest = this.request) =
        ContactPaymentContext(
            contact,
            privatePaymentContext = PrivatePaykitPaymentContext(
                mapOf(MethodId.UsdtArbitrum.rawValue to "bitkit"),
                null
            ),
            incomingPaymentRequest = request,
            endpoints = listOf(PaykitUsdt.endpoint(address))
        )

    @Test
    fun `cancellation protection reads durable started state and scopes wallet and identity`() = test {
        val period = PaykitBillingPeriod(clock.now(), clock.now() + kotlin.time.Duration.parse("1d"))
        val recurring = request.copy(billingPeriod = period)
        val subscription = PaykitSubscriptionId(request.paymentRequestId, contact)
        val repo = repo()
        repo.prepare(
            context(recurring),
            quote,
            amount,
            recurring.payment(MethodId.UsdtArbitrum, clock.now())
        ).getOrThrow()
        assertTrue(repo.protectedRequestIdsForSubscriptionCancellation(identity, subscription).getOrThrow().isEmpty())
        stubSend(Result.success(mock()))
        repo.send(quote) {
            assertEquals(
                setOf(recurring.id),
                repo().protectedRequestIdsForSubscriptionCancellation(identity, subscription).getOrThrow(),
            )
        }.getOrThrow()
        val restarted = repo()
        restarted.prepare(
            context(recurring),
            quote,
            amount,
            recurring.payment(MethodId.UsdtArbitrum, clock.now()),
        ).getOrThrow()
        assertTrue(restarted.attempts.value.single().paymentStarted)
        assertEquals(
            setOf(recurring.id),
            restarted.protectedRequestIdsForSubscriptionCancellation(identity, subscription).getOrThrow()
        )
        assertTrue(
            restarted.protectedRequestIdsForSubscriptionCancellation(contact, subscription).getOrThrow().isEmpty()
        )
        val otherWallet = "0x2222222222222222222222222222222222222222"
        core.`when`<UsdtPaymentRequest> { usdtParsePaymentRequest(otherWallet) }
            .thenReturn(UsdtPaymentRequest(otherWallet, null, null))
        val otherEndpoint = PaykitUsdt.endpoint(otherWallet)
        whenever(usdt.paymentEndpoint()).thenReturn(Result.success(otherEndpoint))
        assertTrue(
            restarted.protectedRequestIdsForSubscriptionCancellation(identity, subscription).getOrThrow().isEmpty()
        )
        val ownEndpoint = PaykitUsdt.endpoint(address)
        whenever(usdt.paymentEndpoint()).thenReturn(Result.success(ownEndpoint))
        stubStoredTransfer(
            Result.success(
                mock {
                    on { status }.thenReturn(UsdtTransferStatus.FAILED)
                }
            )
        )
        assertTrue(
            restarted.protectedRequestIdsForSubscriptionCancellation(identity, subscription).getOrThrow().isEmpty()
        )
    }

    @Test
    fun `execution authorization failure releases an unsubmitted attempt`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        stubSend(Result.success(mock()))
        val result = repo.send(quote) { throw PaykitPaymentRequestError.RequestUnavailable }
        assertTrue(result.isFailure)
        assertEquals(false, repo.attempts.value.single().paymentStarted)
    }

    @Test
    fun `binding survives restart and blocks replacement of a pending payment`() = test {
        repo().prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        assertNotNull(stored)
        val pending = mock<UsdtTransfer> { on { status }.thenReturn(UsdtTransferStatus.PENDING) }
        stubStoredTransfer(Result.success(pending))
        val restarted = repo()
        assertTrue(
            restarted.prepare(
                context(),
                quote.copy(id = "replacement"),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now())
            ).isFailure
        )
        restarted.reconcile().getOrThrow()
        assertEquals(quote.id, restarted.attempts.value.single().quoteId)
        assertTrue(restarted.resumableRequests.value.isEmpty())
        stubStoredTransfer(Result.success(null))
        restarted.reconcile().getOrThrow()
        assertEquals(setOf(request.id), restarted.resumableRequests.value)
        restarted.prepare(
            context(),
            quote.copy(id = "replacement"),
            amount,
            request.payment(MethodId.UsdtArbitrum, clock.now())
        ).getOrThrow()
        assertEquals("replacement", restarted.attempts.value.single().quoteId)
    }

    @Test
    fun `restored started payment remains protected without local core history`() = test {
        val original = repo()
        original.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        val unstartedState = stored
        stubSend(Result.success(mock()))
        original.send(quote).getOrThrow()
        val backup = original.backupSnapshot()
        for (localState in listOf(null, unstartedState)) {
            stored = localState
            val restored = repo()
            restored.restoreBackup(backup)
            restored.reconcile().getOrThrow()
            assertTrue(restored.resumableRequests.value.isEmpty())
            assertTrue(
                restored.prepare(
                    context(),
                    quote.copy(id = "replacement"),
                    amount,
                    request.payment(MethodId.UsdtArbitrum, clock.now())
                ).isFailure
            )
            assertEquals(backup, restored.backupSnapshot())
        }
    }

    @Test
    fun `failed send allows replacement only when its execution journal confirms no submission`() = test {
        val pending = mock<UsdtTransfer> { on { status }.thenReturn(UsdtTransferStatus.PENDING) }
        val histories = listOf(
            Result.success(null),
            Result.success(pending),
            Result.failure<UsdtTransfer?>(UsdtException.Storage("unreadable history")),
        )
        for (history in histories) {
            stored = null
            val repo = repo()
            stubStoredTransfer(history)
            stubSend(Result.failure(UsdtException.QuoteExpired()))
            repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
            assertTrue(repo.send(quote).isFailure)
            val replacement = repo.prepare(
                context(),
                quote.copy(id = "replacement"),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now())
            )
            assertEquals(history.isSuccess && history.getOrNull() == null, replacement.isSuccess)
        }
    }

    @Test
    fun `unreadable persistence prevents sending and is preserved`() = test {
        stored = "unreadable"
        assertTrue(
            repo().prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).isFailure
        )
        assertEquals("unreadable", stored)
    }

    @Test
    fun `preparation requires the approved amount recipient and accepted endpoint`() = test {
        val repo = repo()
        assertTrue(
            repo.prepare(
                context(),
                quote.copy(amount = 49_999u),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now())
            ).isFailure
        )
        assertTrue(
            repo.prepare(
                context(),
                quote.copy(recipient = "0x2222222222222222222222222222222222222222"),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now())
            ).isFailure
        )
        assertTrue(
            repo.prepare(
                context(request.copy(acceptedPaymentEndpointIdentifiers = listOf(MethodId.Bolt11.rawValue))),
                quote,
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now())
            ).isFailure
        )
        assertEquals(null, stored)
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
    }

    @Test
    fun `proof delivery can retry after restart without replacing the payment`() = test {
        repo().prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        val confirmed = mock<UsdtTransfer> {
            on { status }.thenReturn(UsdtTransferStatus.CONFIRMED)
            on { txHash }.thenReturn("transaction")
        }
        stubStoredTransfer(Result.success(confirmed))
        whenever(proofs.submit(any())).thenReturn(Result.success(false), Result.success(true))
        val first = repo()
        first.reconcile().getOrThrow()
        assertEquals(false, first.attempts.value.single().proofQueued)
        val restarted = repo()
        restarted.reconcile().getOrThrow()
        assertEquals(true, restarted.attempts.value.single().proofQueued)
        assertTrue(
            restarted.prepare(
                context(),
                quote.copy(id = "replacement"),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now())
            ).isFailure
        )
    }

    @Test
    fun `receipt verification uses actual funds and claims each payment once`() = test {
        val record = receivedRequest()
        val second = receivedRequest("550e8400-e29b-41d4-a716-446655440001")
        whenever(sdk.paymentRequests()).thenReturn(listOf(record, second))
        val payment = UsdtVerifiedPayment(
            "42161:transaction:0",
            "transaction:0",
            "sender",
            address,
            49_999u,
            1_800_000_000u
        )
        stubVerification(Result.success(payment))
        val repo = repo()
        repo.reconcile().getOrThrow()
        assertEquals(1, repo.receipts.value.size)
        assertEquals(49_999uL, repo.receipts.value.single().amount.atomic)
        assertEquals(false, repo.receipts.value.single().satisfied)
        repo.reconcile().getOrThrow()
        assertEquals(1, repo.receipts.value.size)
        assertEquals(quote.amount - 1u, repo.receipts.value.single().amount.atomic)
    }

    @Test
    fun `recurring receipt verifies the selected quote and verbatim proof period`() = test {
        val period = BillingPeriod("2027-01-01T08:00:00.000Z", "2027-02-01T08:00:00.000Z")
        val quoteId = "650e8400-e29b-41d4-a716-446655440000"
        val base = receivedRequest()
        val submission = base.paymentProofs.single()
        whenever(submission.billingPeriod).thenReturn(period)
        whenever(submission.conversionQuoteId).thenReturn(quoteId)
        val record = base.copy(
            state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            terms = requireNotNull(base.terms).copy(
                recurrence = PaymentRequestRecurrence(1u, "month", period.startsAt, period.startsAt, null),
                conversion = PaymentConversion.PerPeriod,
            ),
            conversionQuotes = listOf(
                PaymentConversionQuoteRecord(
                    quoteId,
                    period,
                    listOf(ConversionRate("usdt", "1")),
                    period.startsAt,
                    period.endsAt,
                    null,
                )
            ),
        )
        whenever(sdk.paymentRequests()).thenReturn(listOf(record))
        stubVerification(
            Result.success(
                UsdtVerifiedPayment("42161:transaction:0", "transaction:0", "sender", address, 50_000u, 1_800_000_000u)
            )
        )
        val unverified = requireNotNull(record.toPaykitSubscription())
        assertTrue(unverified.paidPeriods.isEmpty())
        assertEquals(1, unverified.receivedPaymentRequests().size)
        val repo = repo()
        repo.reconcile().getOrThrow()
        val receipts = repo.verifiedReceipts(identity).getOrThrow()
        assertEquals(1, record.toPaykitSubscription(verifiedUsdtReceipts = receipts)?.paidPeriods?.size)
        assertUnsatisfiedReceiptsDoNotPayPeriod(record, receipts.single())
        val binding = argumentCaptor<UsdtPaymentProofBinding>()
        verify(wallet).verifyPaymentProof(binding.capture(), any())
        assertEquals(period.startsAt, binding.firstValue.periodStartsAt)
        assertEquals(period.endsAt, binding.firstValue.periodEndsAt)
        assertEquals(quoteId, binding.firstValue.conversionQuoteId)
        assertTrue(repo.receipts.value.single().satisfied)
        assertEquals(
            Instant.parse(period.startsAt),
            repo.receipts.value.single().requestId.billingPeriodStartsAt?.let(Instant::parse)
        )
    }

    private fun assertUnsatisfiedReceiptsDoNotPayPeriod(record: PaymentRequestRecord, receipt: PaykitUsdtReceipt) {
        for (invalidReceipt in listOf(
            receipt.copy(underpaid = true),
            receipt.copy(afterExpiry = true),
            receipt.copy(verified = false),
            receipt.copy(proofEventId = "unrelated")
        )) {
            assertTrue(
                requireNotNull(
                    record.toPaykitSubscription(verifiedUsdtReceipts = listOf(invalidReceipt))
                ).paidPeriods.isEmpty()
            )
        }
    }

    @Test
    fun `receipt status survives restart and reflects canonical execution and payment deadline`() = test {
        val record = receivedRequest().let {
            it.copy(
                terms = it.terms?.copy(
                    paymentDeadline = com.synonym.paykit.PaymentDeadline.At(clock.now().toString())
                )
            )
        }
        whenever(sdk.paymentRequests()).thenReturn(listOf(record))
        val paid = UsdtVerifiedPayment(
            "42161:transaction:0",
            "transaction:0",
            "sender",
            address,
            50_000u,
            1_800_000_000u
        )
        stubVerification(Result.success(paid))
        repo().reconcile().getOrThrow()
        val restarted = repo()
        restarted.reconcile().getOrThrow()
        assertTrue(restarted.receipts.value.single().satisfied)
        stubVerification(Result.failure(UsdtException.NetworkUnavailable()))
        assertTrue(restarted.reconcile().isFailure)
        assertTrue(restarted.receipts.value.single().verified)
        stubVerification(Result.success(null))
        restarted.reconcile().getOrThrow()
        assertEquals(false, restarted.receipts.value.single().verified)
        stubVerification(Result.success(paid.copy(timestamp = paid.timestamp + 1u)))
        restarted.reconcile().getOrThrow()
        assertTrue(restarted.receipts.value.single().verified)
        assertTrue(restarted.receipts.value.single().afterExpiry)
        assertEquals(false, restarted.receipts.value.single().satisfied)
    }

    @Test
    fun `proof envelope includes the application id at the UTF-8 message boundary`() {
        val binding = PaykitUsdtPaymentRepo.binding(request, identity, "bitkit", null, null)
        // The rc63 envelope and largest ERC-20 receipt proof reserve 692 bytes before the reference.
        val exactLimit = binding.copy(paymentReference = "é".repeat(154))
        PaykitUsdtPaymentRepo.validateProofSize(exactLimit)
        kotlin.test.assertFailsWith<PaykitPaymentRequestError.RequestUnavailable> {
            PaykitUsdtPaymentRepo.validateProofSize(
                exactLimit.copy(paymentReference = exactLimit.paymentReference + "x")
            )
        }
    }

    @Test
    fun `proof size is bounded before the payment is prepared`() = test {
        val binding = PaykitUsdtPaymentRepo.binding(request, identity, "bitkit", null, request.billingPeriod?.sdkValue)
        PaykitUsdtPaymentRepo.validateProofSize(binding)
        val oversized = context(request.copy(paymentReference = "é".repeat(256)))
        assertTrue(
            repo().prepare(oversized, quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).isFailure
        )
        assertEquals(null, stored)
    }

    @Test
    fun `invalid evidence does not prevent another request from completing`() = test {
        val records = listOf(receivedRequest(), receivedRequest("550e8400-e29b-41d4-a716-446655440001"))
        whenever(sdk.paymentRequests()).thenReturn(records)
        val payment = UsdtVerifiedPayment(
            "42161:transaction:0",
            "transaction:0",
            "sender",
            address,
            50_000u,
            1_800_000_000u
        )
        stubVerification(
            Result.failure(UsdtException.InvalidPaymentProof()),
            Result.success(payment)
        )
        val repo = repo()
        repo.reconcile().getOrThrow()
        assertEquals(records.last().paymentRequestId, repo.receipts.value.single().requestId.paymentRequestId)
        assertEquals(true, repo.receipts.value.single().satisfied)
    }

    private fun receivedRequest(id: String = request.paymentRequestId): PaymentRequestRecord {
        val json = Json.encodeToString(
            PaykitUsdtProof(UsdtPaymentProof("42161", "0x" + "a".repeat(64), "0", "0x" + "b".repeat(130)))
        )
        val proof = mock<PrivateJsonObject> { on { exportText() }.thenReturn(json) }
        val submission = mock<PaymentProofRecord> {
            on { eventId }.thenReturn("proof-$id")
            on { billingPeriod }.thenReturn(null)
            on { paymentAppId }.thenReturn("bitkit")
            on { paymentEndpointIdentifier }.thenReturn(MethodId.UsdtArbitrum.rawValue)
            on { this.proof }.thenReturn(proof)
        }
        val reference = mock<PaymentReference> { on { exportText() }.thenReturn("payment-reference") }
        return PaymentRequestRecord(
            counterparty = contact, paymentRequestId = id,
            localRole = PaymentRequestLocalRole.PAYEE, state = PaymentRequestLifecycleState.PROOF_SUBMITTED,
            proposalStreamItemId = 1u, proposalOutboundMessageId = null, proposalOutboundStatus = null,
            proposalEventId = "proposal",
            proposalAppId = "bitkit",
            payerAppId = "bitkit",
            executionClaimAppId = "bitkit",
            terms = PaymentRequestTerms(
                PaymentRequestAmount("0.05", "usd"),
                reference,
                null,
                null,
                listOf(MethodId.UsdtArbitrum.rawValue),
                null, "bitkit",
                PaymentConversion.Fixed(listOf(ConversionRate("usdt", "1"))),
                null,
                mock()
            ),
            acceptedEventId = "accepted", acceptedOutboundStatus = null,
            rejectedEventId = null, rejectedOutboundStatus = null,
            canceledEventId = null, canceledOutboundStatus = null,
            paymentProofs = listOf(
                submission
            ),
            conversionQuotes = emptyList(),
            lastStreamItemId = 2u,
            lastOutboundMessageId = null, lastOutboundStatus = null,
            lastEventAt = "2027-01-15T08:00:00Z", invalidReason = null,
        )
    }

    @Test
    fun `both request participants construct the same reference`() {
        val outgoing = request.copy(counterparty = identity, direction = PaykitPaymentRequestDirection.Outgoing)
        val reference = PaykitUsdtPaymentRepo.binding(
            request,
            identity,
            "bitkit",
            null,
            request.billingPeriod?.sdkValue
        )
        assertEquals(
            reference,
            PaykitUsdtPaymentRepo.binding(outgoing, contact, "bitkit", null, outgoing.billingPeriod?.sdkValue)
        )
        assertTrue(
            reference != PaykitUsdtPaymentRepo.binding(
                request.copy(paymentReference = "other"), identity, "bitkit", null, null
            )
        )
    }

    @Test
    fun `endpoint requires a direct address and the pinned network and token`() {
        val endpoint = PaykitUsdt.endpoint(address)
        assertEquals(address, PaykitUsdt.address(endpoint.rawPayload))
        for (invalid in listOf(
            endpoint.rawPayload.replace("42161", "1"),
            endpoint.rawPayload.replace(PaykitUsdt.TOKEN, address),
            endpoint.rawPayload.replace("\"42161\"", "42161"),
            endpoint.rawPayload.replace(address, endpoint.paymentRequest)
        )) {
            assertEquals(null, PaykitUsdt.address(invalid))
        }
    }
}
