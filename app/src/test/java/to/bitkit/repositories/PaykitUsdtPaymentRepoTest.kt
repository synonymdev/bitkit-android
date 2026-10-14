@file:OptIn(ExperimentalTime::class)

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
import com.synonym.paykit.PaymentDeadline
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.models.PaykitUsdt
import to.bitkit.models.PaykitUsdtStateBackup
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.UsdtService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.viewmodels.ContactPaymentContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Suppress("LargeClass")
class PaykitUsdtPaymentRepoTest : BaseUnitTest() {
    @Test
    fun `started missing transfers stay bound but terminal proof free transfers release their rail`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        stubSend(Result.success(mock()))
        repo.send(quote) {}.getOrThrow()
        repo.reconcile().getOrThrow()
        assertTrue(repo.hasBinding(request.id))
        for (status in listOf(UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED)) {
            stubStoredTransfer(Result.success(mock { on { this.status }.thenReturn(status) }))
            repo.reconcile().getOrThrow()
            assertFalse(repo.hasBinding(request.id))
            assertTrue(repo.attempts.value.single().paymentStarted)
        }
        stubStoredTransfer(Result.success(null))
        repo.reconcile().getOrThrow()
        assertTrue(repo.hasBinding(request.id))
    }

    @Test
    fun `unparseable proof period does not block later valid receipts`() = test {
        val malformed = receivedRequest()
        whenever(malformed.paymentProofs.single().billingPeriod)
            .thenReturn(BillingPeriod("not-an-instant", "2027-02-01T08:00:00Z"))
        val valid = receivedRequest("550e8400-e29b-41d4-a716-446655440001")
        whenever(sdk.paymentRequests()).thenReturn(listOf(malformed, valid))
        stubVerification(
            Result.success(UsdtVerifiedPayment("payment", "transfer", "sender", address, 50_000u, 1_800_000_000u))
        )
        val repo = repo()
        repo.reconcile().getOrThrow()
        assertEquals(valid.paymentRequestId, repo.receipts.value.single().requestId.paymentRequestId)
        verify(wallet, times(1)).verifyPaymentProof(any(), any())
    }

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

    @Test
    fun `receipt status belongs only to the same request and proof`() {
        val request = request.copy(paymentProofEventId = "proof")
        val receipt = PaykitUsdtReceipt(
            wallet = address,
            identity = identity,
            requestId = request.id,
            paymentId = "payment",
            proofEventId = "proof",
            verified = true,
            transferId = "transfer",
            amount = amount,
            receivedAtMillis = 0,
            underpaid = false,
            afterExpiry = false,
        )
        assertTrue(receipt.matches(request))
        assertFalse(receipt.matches(request.copy(paymentProofEventId = "replacement-proof")))
        assertFalse(receipt.matches(request.copy(paymentRequestId = "different-request")))
    }

    @Before
    fun setUp() = test {
        core = Mockito.mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt"))
        core.`when`<UsdtPaymentRequest> { usdtParsePaymentRequest(address) }
            .thenReturn(UsdtPaymentRequest(address, null, null))
        val endpoint = PaykitUsdt.endpoint(address)
        whenever(keychain.loadString(any())).thenAnswer { stored }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer { stored = it.getArgument(1) }
        whenever(
            sdk.identityStatus(any())
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
        doAnswer { result.getOrThrow()?.let(::listOf).orEmpty() }.whenever(wallet).history()
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
        whenever(usdt.send(any(), anyOrNull(), any())).doSuspendableAnswer { invocation ->
            runSuspendCatching {
                invocation.getArgument<suspend () -> Unit>(2).invoke()
                result.getOrThrow()
            }
        }
    }

    private fun repo() = PaykitUsdtPaymentRepo(testDispatcher, keychain, sdk, proofs, usdt, clock)

    @Test
    fun `expired quotes fail before waiting for payment state or identity`() = test {
        val repo = repo()
        for (remaining in listOf(0uL, 5uL)) {
            val result = repo.prepare(
                context(),
                quote.copy(expiresAt = clock.now().epochSeconds.toULong() + remaining),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now()),
            )
            assertTrue(result.exceptionOrNull() is UsdtException.QuoteExpired)
        }
        verify(sdk, never()).identityStatus(any())
        verify(keychain, never()).upsertString(any(), any())
    }

    @Test
    fun `receipt verification permits preparation and preserves concurrent attempts`() = test {
        val verifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val record = receivedRequest()
        whenever(sdk.paymentRequests()).thenReturn(listOf(record))
        whenever(wallet.verifyPaymentProof(any(), any())).doSuspendableAnswer {
            verifying.complete(Unit)
            release.await()
            UsdtVerifiedPayment("payment", "transfer", "sender", address, 50_000u, 1_800_000_000u)
        }
        val repo = repo()
        val reconciliation = launch { repo.reconcile().getOrThrow() }
        verifying.await()
        val preparation = async {
            repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        }
        try {
            assertTrue(preparation.isCompleted)
        } finally {
            release.complete(Unit)
        }
        preparation.await()
        reconciliation.join()
        assertEquals(quote.id, repo.backupSnapshot().attempts.single().quoteId)
        assertEquals("payment", repo.receipts.value.single().paymentId)
        verify(sdk).identityStatus(Priority.Interactive)
    }

    @Test
    fun `stale transfer observations cannot reopen an attempt that started sending`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        val observing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(usdt.operation<Any?>(any())).doSuspendableAnswer {
            observing.complete(Unit)
            release.await()
            Result.success(null)
        }
        val reconciliation = launch { repo.reconcile().getOrThrow() }
        observing.await()
        stubSend(Result.success(mock()))
        val send = async { repo.send(quote).getOrThrow() }
        try {
            assertTrue(send.isCompleted)
        } finally {
            release.complete(Unit)
        }
        send.await()
        reconciliation.join()
        assertTrue(repo.attempts.value.single().paymentStarted)
        assertTrue(repo.hasBinding(request.id))
        assertTrue(repo.resumableRequests.value.isEmpty())
    }

    @Test
    fun `restore during proof generation discards the previous reconciliation`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        val backup = repo.backupSnapshot()
        stubStoredTransfer(Result.success(mock { on { status }.thenReturn(UsdtTransferStatus.CONFIRMED) }))
        val generating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(service.paymentProof(any(), any())).doSuspendableAnswer {
            generating.complete(Unit)
            release.await()
            UsdtPaymentProof("42161", "transaction", "0", "signature")
        }
        val reconciliation = launch { repo.reconcile().getOrThrow() }
        generating.await()
        val restore = async { repo.restoreBackup(backup) }
        try {
            assertTrue(restore.isCompleted)
        } finally {
            release.complete(Unit)
        }
        restore.await()
        reconciliation.join()
        assertEquals(backup, repo.backupSnapshot())
        assertTrue(repo.resumableRequests.value.isEmpty())
        verify(proofs, never()).submit(any())
    }

    @Test
    fun `restore during receipt verification discards stale verified evidence`() = test {
        val verifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val record = receivedRequest()
        whenever(sdk.paymentRequests()).thenReturn(listOf(record))
        whenever(wallet.verifyPaymentProof(any(), any())).doSuspendableAnswer {
            verifying.complete(Unit)
            release.await()
            UsdtVerifiedPayment("payment", "transfer", "sender", address, 50_000u, 1_800_000_000u)
        }
        val repo = repo()
        val reconciliation = launch { repo.reconcile().getOrThrow() }
        verifying.await()
        val backup = PaykitUsdtStateBackup(emptyList(), emptyList())
        val restore = async { repo.restoreBackup(backup) }
        try {
            assertTrue(restore.isCompleted)
        } finally {
            release.complete(Unit)
        }
        restore.await()
        reconciliation.join()
        assertEquals(backup, repo.backupSnapshot())
        assertTrue(repo.receipts.value.isEmpty())
    }

    @Test
    fun `ownership changed during context lookup cannot be replaced by stale reconciliation`() = test {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(sdk.identityStatus(Priority.Ordered)).doSuspendableAnswer {
            reading.complete(Unit)
            release.await()
            IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }
        whenever(sdk.identityStatus(Priority.Interactive))
            .thenReturn(IdentityStatus(contact, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        val repo = repo()
        val reconciliation = launch { repo.reconcile().getOrThrow() }
        reading.await()
        val preparation = async {
            repo.prepare(context().copy(incomingPaymentRequest = null), quote, amount, null).getOrThrow()
        }
        try {
            assertTrue(preparation.isCompleted)
        } finally {
            release.complete(Unit)
        }
        preparation.await()
        reconciliation.join()
        assertEquals(contact, repo.attempts.value.single().identity)
        verify(sdk, never()).paymentRequests()
    }

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
        val period = PaykitBillingPeriod(clock.now(), clock.now() + Duration.parse("1d"))
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
        val deadline = clock.now() + Duration.parse("1h")
        repo.send(quote, deadline) {
            assertEquals(
                setOf(recurring.id),
                repo().protectedRequestIdsForSubscriptionCancellation(identity, subscription).getOrThrow(),
            )
        }.getOrThrow()
        verify(usdt).send(eq(quote), eq(deadline), any())
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
    fun `unreadable persistence prevents sending and is preserved`() = test {
        stored = "unreadable"
        assertTrue(repo().hasBinding(request.id))
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
    fun `failed proof generation or submission does not block later attempts or receipts`() = test {
        for (generationFails in listOf(true, false)) {
            stored = null
            val other = request.copy(paymentRequestId = "550e8400-e29b-41d4-a716-446655440001")
            val otherQuote = quote.copy(id = "other-quote")
            val repo = repo()
            repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
            repo.prepare(context(other), otherQuote, amount, other.payment(MethodId.UsdtArbitrum, clock.now()))
                .getOrThrow()
            val transfers = listOf(quote.id, otherQuote.id).map { transferId ->
                mock<UsdtTransfer> {
                    on { id }.thenReturn(transferId)
                    on { status }.thenReturn(UsdtTransferStatus.CONFIRMED)
                }
            }
            doAnswer { transfers }.whenever(wallet).history()
            whenever(service.paymentProof(any(), any())).doSuspendableAnswer {
                if (generationFails && it.getArgument<String>(0) == quote.id) throw AppError("proof unavailable")
                UsdtPaymentProof("42161", "0x" + "a".repeat(64), "0", "0x" + "b".repeat(130))
            }
            whenever(proofs.submit(any())).doSuspendableAnswer {
                val pending = it.getArgument<PendingPaykitPaymentProof>(0)
                if (!generationFails && pending.requestId == request.id) {
                    Result.failure(AppError("proof rejected"))
                } else {
                    Result.success(true)
                }
            }
            val received = receivedRequest("550e8400-e29b-41d4-a716-446655440002")
            whenever(sdk.paymentRequests()).thenReturn(listOf(received))
            stubVerification(
                Result.success(
                    UsdtVerifiedPayment(
                        "42161:transaction:0",
                        "transaction:0",
                        "sender",
                        address,
                        50_000u,
                        1_800_000_000u
                    )
                )
            )

            repeat(2) {
                repo.reconcile().getOrThrow()
                assertFalse(repo.attempts.value.single { it.quoteId == quote.id }.proofQueued)
                assertTrue(repo.attempts.value.single { it.quoteId == otherQuote.id }.proofQueued)
                assertEquals(1, repo.receipts.value.size)
                assertTrue(repo.hasBinding(request.id))
            }
        }
    }

    @Test
    fun `proof cancellation stops reconciliation`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        stubStoredTransfer(Result.success(mock { on { status }.thenReturn(UsdtTransferStatus.CONFIRMED) }))
        whenever(service.paymentProof(any(), any())).thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> { repo.reconcile() }
        verify(sdk, times(0)).paymentRequests()
        assertFalse(repo.attempts.value.single().proofQueued)
        assertTrue(repo.hasBinding(request.id))
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
    fun `unchanged receipt verification preserves backup state after restart`() = test {
        val records = listOf(receivedRequest(), receivedRequest("550e8400-e29b-41d4-a716-446655440001"))
        whenever(sdk.paymentRequests()).thenReturn(records)
        val payment = UsdtVerifiedPayment(
            "42161:transaction:0",
            "transaction:0",
            "sender",
            address,
            50_000u,
            1_800_000_000u,
        )
        val secondPayment = payment.copy(paymentId = "42161:transaction:1", transferId = "transaction:1")
        stubVerification(Result.success(payment), Result.success(secondPayment))
        val repo = repo()
        repo.reconcile().getOrThrow()
        val receipts = repo.receipts.value
        val persisted = stored
        assertEquals(2, receipts.size)
        assertEquals(2L, repo.backupStateVersion.value)

        for (current in listOf(repo, repo())) {
            val version = current.backupStateVersion.value
            stubVerification(Result.success(payment), Result.success(secondPayment))
            current.reconcile().getOrThrow()
            assertEquals(receipts, current.receipts.value)
            assertEquals(version, current.backupStateVersion.value)
            assertEquals(persisted, stored)
        }
        verify(keychain, times(2)).upsertString(any(), any())
        verify(wallet, times(6)).verifyPaymentProof(any(), any())
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
                    paymentDeadline = PaymentDeadline.At(clock.now().toString())
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
        assertEquals(0L, restarted.backupStateVersion.value)
        stubVerification(Result.failure(UsdtException.NetworkUnavailable()))
        assertTrue(restarted.reconcile().isFailure)
        assertTrue(restarted.receipts.value.single().verified)
        assertEquals(0L, restarted.backupStateVersion.value)
        stubVerification(Result.success(null))
        restarted.reconcile().getOrThrow()
        assertEquals(false, restarted.receipts.value.single().verified)
        assertEquals(1L, restarted.backupStateVersion.value)
        stubVerification(Result.success(paid.copy(timestamp = paid.timestamp + 1u)))
        restarted.reconcile().getOrThrow()
        assertTrue(restarted.receipts.value.single().verified)
        assertTrue(restarted.receipts.value.single().afterExpiry)
        assertEquals(false, restarted.receipts.value.single().satisfied)
        assertEquals(2L, restarted.backupStateVersion.value)
    }

    @Test
    fun `proof envelope includes the application id at the UTF-8 message boundary`() {
        val binding = PaykitUsdtPaymentRepo.binding(request, identity, "bitkit", null, null)
        // The rc63 envelope and largest ERC-20 receipt proof reserve 692 bytes before the reference.
        val exactLimit = binding.copy(paymentReference = "é".repeat(154))
        PaykitUsdtPaymentRepo.validateProofSize(exactLimit)
        assertFailsWith<PaykitPaymentRequestError.RequestUnavailable> {
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
