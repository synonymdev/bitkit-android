@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentProof
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.UsdtWallet
import com.synonym.bitkitcore.usdtParsePaymentRequest
import com.synonym.paykit.ConversionRate
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.models.PaykitUsdt
import to.bitkit.models.PaykitUsdtStateBackup
import to.bitkit.models.WalletBackupV1
import to.bitkit.services.PaykitSdkService
import to.bitkit.services.UsdtService
import to.bitkit.test.BaseUnitTest
import to.bitkit.viewmodels.ContactPaymentContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitUsdtPaymentRepoRecoveryTest : BaseUnitTest() {
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
            sdk.identityStatus(any())
        ).thenReturn(IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(sdk.paymentRequests()).thenReturn(emptyList())
        whenever(usdt.paymentEndpoint()).thenReturn(Result.success(endpoint))
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

    private suspend fun stubSend(result: Result<UsdtTransfer>) {
        whenever(usdt.send(any(), anyOrNull(), any())).doSuspendableAnswer { invocation ->
            runSuspendCatching {
                invocation.getArgument<suspend () -> Unit>(2).invoke()
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
    fun `request protection survives restart and releases only after definite failure`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        val original = repo.backupSnapshot().attempts.single()
        val started = original.copy(paymentStarted = true)
        val otherWallet = started.copy(wallet = "other-wallet", requestId = request.id.copy(paymentRequestId = "other"))
        repo.restoreBackup(PaykitUsdtStateBackup(listOf(started, otherWallet), emptyList()))
        val restarted = repo()
        assertTrue(restarted.protectedPaymentAttempts(identity).getOrThrow().contains(started))
        assertTrue(restarted.protectedPaymentAttempts(contact).getOrThrow().isEmpty())
        stubStoredTransfer(Result.success(mock { on { status }.thenReturn(UsdtTransferStatus.PENDING) }))
        restarted.reconcile().getOrThrow()
        assertEquals(listOf(started), restarted.protectedPaymentAttempts(identity).getOrThrow())
        stubStoredTransfer(Result.success(mock { on { status }.thenReturn(UsdtTransferStatus.FAILED) }))
        val beforeFailure = restarted.paymentRequestStateVersion.value
        restarted.reconcile().getOrThrow()
        assertTrue(restarted.paymentRequestStateVersion.value > beforeFailure)
        assertTrue(restarted.protectedPaymentAttempts(identity).getOrThrow().isEmpty())
        val releasedVersion = restarted.paymentRequestStateVersion.value
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(sdk.identityStatus(any())).doSuspendableAnswer {
            reading.complete(Unit)
            resume.await()
            IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }
        val refresh = async { restarted.reconcile() }
        reading.await()
        assertEquals(listOf(started), restarted.protectedPaymentAttempts(identity).getOrThrow())
        resume.complete(Unit)
        refresh.await().getOrThrow()
        assertTrue(restarted.paymentRequestStateVersion.value > releasedVersion)
        assertTrue(restarted.protectedPaymentAttempts(identity).getOrThrow().isEmpty())
    }

    @Test
    fun `failed retry remains payable only when non-submission is established`() = test {
        for (historyReadable in listOf(true, false)) {
            stored = null
            val repo = repo()
            val terms = request.payment(MethodId.UsdtArbitrum, clock.now())
            stubStoredTransfer(Result.success(null))
            repo.prepare(context(), quote, amount, terms).getOrThrow()
            stubSend(Result.success(mock()))
            repo.send(quote).getOrThrow()
            stubStoredTransfer(Result.success(mock { on { status }.thenReturn(UsdtTransferStatus.FAILED) }))
            repo.reconcile().getOrThrow()
            val retry = quote.copy(id = "retry")
            repo.prepare(context(), retry, amount, terms).getOrThrow()
            assertTrue(repo.protectedPaymentAttempts(identity).getOrThrow().isEmpty())
            val result = repo.send(retry) {
                assertTrue(repo.protectedPaymentAttempts(identity).getOrThrow().isNotEmpty())
                if (!historyReadable) stubStoredTransfer(Result.failure(UsdtException.Storage("unreadable history")))
                throw PaykitPaymentRequestError.RequestUnavailable
            }
            assertTrue(result.isFailure)
            assertEquals(historyReadable, repo.protectedPaymentAttempts(identity).getOrThrow().isEmpty())
        }
    }

    @Test
    fun `restore protects another wallet until its owner can be resolved`() = test {
        val endpoint = PaykitUsdt.endpoint(address)
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        val restored = repo.backupSnapshot().attempts.single().copy(wallet = "restored-wallet", paymentStarted = true)
        stored = null
        repo.restoreBackup(PaykitUsdtStateBackup(listOf(restored), emptyList()))
        whenever(sdk.identityStatus(any())).thenReturn(null)
        repo.reconcile().getOrThrow()
        assertEquals(listOf(restored), repo.protectedPaymentAttempts(identity).getOrThrow())
        whenever(sdk.identityStatus(any())).thenReturn(
            IdentityStatus(identity, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        )
        whenever(usdt.paymentEndpoint()).thenReturn(Result.failure(UsdtException.InvalidCredentials()))
        assertTrue(repo.reconcile().isFailure)
        assertEquals(listOf(restored), repo.protectedPaymentAttempts(identity).getOrThrow())
        whenever(usdt.paymentEndpoint()).thenReturn(Result.success(endpoint))
        repo.reconcile().getOrThrow()
        assertTrue(repo.protectedPaymentAttempts(identity).getOrThrow().isEmpty())
        repo.resetForWipe()
        assertTrue(repo.attempts.value.isEmpty())
        assertTrue(repo.receipts.value.isEmpty())
        assertEquals(listOf(restored), repo.protectedPaymentAttempts(identity).getOrThrow())
    }

    @Test
    fun `restored attempts all protect the same request regardless of order`() = test {
        val terms = request.payment(MethodId.UsdtArbitrum, clock.now())
        for (status in listOf(
            UsdtTransferStatus.PENDING,
            UsdtTransferStatus.CONFIRMED,
            UsdtTransferStatus.FAILED,
            UsdtTransferStatus.REPLACED
        )) {
            for (startedFirst in listOf(false, true)) {
                stored = null
                val repo = repo()
                repo.prepare(context(), quote, amount, terms).getOrThrow()
                val unstarted = repo.backupSnapshot().attempts.single()
                val started = unstarted.copy(quoteId = "started", paymentStarted = true)
                stored = null
                val attempts = if (startedFirst) listOf(started, unstarted) else listOf(unstarted, started)
                val backup = PaykitUsdtStateBackup(attempts, emptyList())
                repo.restoreBackup(backup)
                repo.restoreBackup(backup)
                val transfer = mock<UsdtTransfer> {
                    on { id }.thenReturn(started.quoteId)
                    on { this.status }.thenReturn(status)
                }
                whenever(wallet.history()).thenReturn(listOf(transfer))
                repo.reconcile().getOrThrow()
                val canReplace = status == UsdtTransferStatus.FAILED || status == UsdtTransferStatus.REPLACED
                assertEquals(!canReplace, repo.resumableRequests.value.isEmpty())
                val result = repo.prepare(context(), quote.copy(id = "replacement"), amount, terms)
                assertEquals(canReplace, result.isSuccess)
                if (canReplace) {
                    val expected = listOf(started, unstarted.copy(quoteId = "replacement"))
                    assertEquals(expected, repo.backupSnapshot().attempts)
                } else {
                    assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
                    assertEquals(attempts, repo.backupSnapshot().attempts)
                }
            }
        }
    }

    @Test
    fun `preparing the same quote preserves proof and restored progress`() = test {
        val repo = repo()
        val terms = request.payment(MethodId.UsdtArbitrum, clock.now())
        repo.prepare(context(), quote, amount, terms).getOrThrow()
        val funded = repo.backupSnapshot().attempts.single().copy(
            paymentStarted = true,
            proof = PaykitUsdtProof(UsdtPaymentProof("42161", "transaction", "0", "signature")),
        )
        repo.restoreBackup(PaykitUsdtStateBackup(listOf(funded), emptyList()))
        repo.prepare(context(), quote, amount, terms).getOrThrow()
        assertEquals(listOf(funded), repo.backupSnapshot().attempts)
        assertEquals(
            PaykitPaymentRequestError.OperationInProgress,
            repo.prepare(context(), quote.copy(id = "replacement"), amount, terms).exceptionOrNull(),
        )
        assertEquals(listOf(funded), repo.backupSnapshot().attempts)
    }

    @Test
    fun `unsubmitted binding requires current journal evidence after restart`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        stubSend(Result.failure(UsdtException.QuoteExpired()))
        assertTrue(repo.send(quote).isFailure)
        assertTrue(repo.hasBinding(request.id))
        repo.reconcile().getOrThrow()
        assertFalse(repo.hasBinding(request.id))
        val restarted = repo()
        assertTrue(restarted.hasBinding(request.id))
        restarted.reconcile().getOrThrow()
        assertFalse(restarted.hasBinding(request.id))

        for (history in listOf(
            Result.failure<UsdtTransfer?>(UsdtException.Storage("unreadable history")),
            Result.success(mock<UsdtTransfer> { on { status }.thenReturn(UsdtTransferStatus.PENDING) }),
            Result.success(mock<UsdtTransfer> { on { status }.thenReturn(UsdtTransferStatus.CONFIRMED) }),
        )) {
            stubStoredTransfer(history)
            whenever(proofs.submit(any())).thenReturn(Result.success(false))
            restarted.reconcile().getOrThrow()
            assertTrue(restarted.hasBinding(request.id))
        }
    }

    @Test
    fun `changed or unreadable persistence invalidates unsubmitted binding evidence`() = test {
        val repo = repo()
        repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        repo.reconcile().getOrThrow()
        assertFalse(repo.hasBinding(request.id))
        val unsubmittedState = stored
        val original = repo.backupSnapshot().attempts.single()
        val proof = PaykitUsdtProof(UsdtPaymentProof("42161", "transaction", "0", "signature"))
        for (changed in listOf(
            original.copy(paymentStarted = true),
            original.copy(proof = proof),
            original.copy(proofQueued = true),
            original.copy(quoteId = "replacement"),
        )) {
            stored = unsubmittedState
            repo.reconcile().getOrThrow()
            assertFalse(repo.hasBinding(request.id))
            stored = Json.encodeToString(PaykitUsdtStateBackup(listOf(changed), emptyList()))
            assertTrue(repo.hasBinding(request.id))
        }
        stored = unsubmittedState
        repo.reconcile().getOrThrow()
        repo.restoreBackup(PaykitUsdtStateBackup(listOf(original), emptyList()))
        assertTrue(repo.hasBinding(request.id))
        assertTrue(repo.resumableRequests.value.isEmpty())
        stubStoredTransfer(Result.success(mock { on { status }.thenReturn(UsdtTransferStatus.PENDING) }))
        repo.reconcile().getOrThrow()
        assertTrue(repo.hasBinding(request.id))
        stubStoredTransfer(Result.success(null))
        repo.reconcile().getOrThrow()
        assertFalse(repo.hasBinding(request.id))
        repo.restoreBackup(PaykitUsdtStateBackup(listOf(original.copy(paymentStarted = true)), emptyList()))
        assertTrue(repo.hasBinding(request.id))
        repo.reconcile().getOrThrow()
        assertFalse(repo.hasBinding(request.id))
        stored = unsubmittedState
        repo.reconcile().getOrThrow()
        stored = "unreadable"
        assertTrue(repo.hasBinding(request.id))
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
    fun `started payment remains bound after restart before reconciliation`() = test {
        val original = repo()
        original.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
        stubSend(Result.success(mock()))
        original.send(quote).getOrThrow()

        val restarted = repo()
        assertTrue(restarted.hasBinding(request.id))
        assertFalse(restarted.hasBinding(request.id.copy(paymentRequestId = "different-request")))
        assertFalse(restarted.hasBinding(request.id.copy(counterparty = identity)))
        assertFalse(restarted.hasBinding(request.id.copy(billingPeriodStartsAt = clock.now().toString())))
    }

    @Test
    fun `wallet backup restores contact attempts with omitted optional fields`() = test {
        val fixture = """
            {"version":1,"createdAt":1,"transfers":[],"paykitPaymentState":{
              "subscriptions":{},"pendingProofs":[],"usdt":{"attempts":[{
                "quoteId":"quote","wallet":"$address","identity":"$identity","contact":"$contact",
                "proofQueued":false,"paymentStarted":true
              }],"receipts":[]}
            }}
        """.trimIndent()
        val walletBackup = json.decodeFromString<WalletBackupV1>(fixture)
        val repo = repo()
        repo.restoreBackup(requireNotNull(walletBackup.paykitPaymentState?.usdt))
        repo.reconcile().getOrThrow()
        val expected = PaykitUsdtAttempt("quote", address, identity, contact, paymentStarted = true)
        assertEquals(listOf(expected), repo.attempts.value)
        assertEquals(listOf(expected), repo().backupSnapshot().attempts)
    }

    @Test
    fun `restored started payment is released after confirming no local core history`() = test {
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
            assertEquals(setOf(request.id), restored.resumableRequests.value)
            assertTrue(
                restored.prepare(
                    context(),
                    quote.copy(id = "replacement"),
                    amount,
                    request.payment(MethodId.UsdtArbitrum, clock.now())
                ).isSuccess
            )
            assertFalse(restored.backupSnapshot().attempts.single().paymentStarted)
        }
    }

    @Test
    fun `cancelled send allows replacement only when its execution journal confirms no submission`() = test {
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
            stubSend(Result.success(mock()))
            repo.prepare(context(), quote, amount, request.payment(MethodId.UsdtArbitrum, clock.now())).getOrThrow()
            val sending = CompletableDeferred<Unit>()
            var returned = false
            val job = launch {
                repo.send(quote) {
                    sending.complete(Unit)
                    awaitCancellation()
                }
                returned = true
            }
            sending.await()
            job.cancelAndJoin()
            assertFalse(returned)
            val canReplace = history.isSuccess && history.getOrNull() == null
            assertEquals(!canReplace, repo().backupSnapshot().attempts.single().paymentStarted)
            val replacement = repo().prepare(
                context(),
                quote.copy(id = "replacement"),
                amount,
                request.payment(MethodId.UsdtArbitrum, clock.now()),
            )
            assertEquals(canReplace, replacement.isSuccess)
        }
    }
}
