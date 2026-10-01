@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.ValidationResult
import com.synonym.bitkitcore.validateBitcoinAddress
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
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
import com.synonym.paykit.PrivateOperationError
import com.synonym.paykit.PrivateStreamCounterpartyIntakeReport
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentRequestRepoTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val PAYMENT_REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000"
        private const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val LOCAL_IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val SECOND_IDENTITY = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private val START_TIME = Instant.parse("2027-01-15T08:00:00Z")
        private val PAYMENT_REFERENCE = mock<PaymentReference> {
            on { exportText() } doReturn "invoice-123"
        }
        private val METADATA = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"order":"123"}"""
        }
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private val settingsStore = mock<SettingsStore>()
    private val presentationStore = mock<PaykitPaymentRequestPresentationStore>()
    private val diagnostics = mock<PaykitPaymentRequestDiagnostics>()
    private val paymentProofStore = mock<PaykitPaymentProofStore>()
    private val paymentProofRepo = mock<PaykitPaymentProofRepo>()
    private val subscriptionNotificationScheduler = mock<PaykitSubscriptionNotificationScheduler>()
    private var schedulerOriginMillis = 0L
    private val clock = object : Clock {
        override fun now(): Instant = START_TIME.plus(
            (testDispatcher.scheduler.currentTime - schedulerOriginMillis).milliseconds,
        )
    }
    private lateinit var sut: PaykitPaymentRequestRepo

    @Before
    fun setUp() = test {
        schedulerOriginMillis = testDispatcher.scheduler.currentTime
        whenever(paykitSdkService.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers()).thenReturn(emptyList())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(emptyList())
        whenever(paykitSdkService.linkedPeers()).thenReturn(emptyList())
        whenever(settingsStore.isPaykitEnabled).thenReturn(flowOf(true))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = true)))
        whenever(presentationStore.load(LOCAL_IDENTITY)).thenReturn(emptySet())
        whenever(
            presentationStore.loadSubscriptionState(any())
        ).thenReturn(PaykitSubscriptionPresentationState())
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY)).thenReturn(emptyMap())
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(emptySet())
        whenever(paymentProofRepo.protectedRequestIdsForSubscriptionCancellation(any(), any()))
            .thenReturn(Result.success(emptySet()))
        sut = PaykitPaymentRequestRepo(
            testDispatcher,
            paykitSdkService,
            settingsStore,
            presentationStore,
            diagnostics,
            paymentProofStore,
            paymentProofRepo,
            subscriptionNotificationScheduler,
            clock,
            clock,
        )
        sut.activate(LOCAL_IDENTITY)
    }

    @After
    fun tearDown() = test {
        sut.clear()
    }

    @Test
    fun `shared app destinations stay out of request UI and clear on identity switch`() = test {
        val address = PaykitReceivedPaymentContactsTest.ADDRESS
        val record = paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).let {
            it.copy(
                proposalAppId = "marketplace",
                terms = requireNotNull(it.terms).copy(
                    acceptedPaymentEndpointIdentifiers = listOf(MethodId.P2wpkh.rawValue),
                    paymentEndpoints = mapOf(
                        MethodId.P2wpkh.rawValue to PaykitReceivedPaymentContactsTest.payload(address),
                    ),
                ),
            )
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")).use { native ->
            native.`when`<ValidationResult> { validateBitcoinAddress(address) }
                .thenReturn(ValidationResult(address, NetworkType.REGTEST, AddressType.P2WPKH))
            sut.refresh().getOrThrow()
        }
        assertEquals(
            setOf(PubkyPublicKeyFormat.normalized(COUNTERPARTY)),
            sut.receivedPaymentContacts.contactsForAddresses(listOf(address)),
        )
        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
        verify(paykitSdkService).allPaymentRequests(PubkyPublicKeyFormat.normalized(LOCAL_IDENTITY))

        sut.activate(SECOND_IDENTITY)
        assertTrue(sut.receivedPaymentContacts.contactsForAddresses(listOf(address)).isEmpty())
        sut.clear()
        assertTrue(sut.receivedPaymentContacts.contactsForAddresses(listOf(address)).isEmpty())
    }

    @Test
    fun `blocking peer hides requests from an earlier snapshot`() = test {
        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        assertEquals(1, sut.pendingRequests.value.size)
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )
        sut.refresh().getOrThrow()
        assertTrue(sut.pendingRequests.value.isEmpty())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(emptyList())
        sut.refresh().getOrThrow()
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
    }

    @Test
    fun `blocking an already presented accepted request prevents payment`() = test {
        val record = paymentRequestRecord(state = PaymentRequestLifecycleState.ACCEPTED)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )
        assertEquals(PaykitPaymentRequestError.RequestUnavailable, sut.accept(request).exceptionOrNull())
    }

    @Test
    fun `accepted request checks blocking after waiting for synchronization`() = test {
        val record = paymentRequestRecord(state = PaymentRequestLifecycleState.ACCEPTED)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        val refreshPaused = CompletableDeferred<Unit>()
        val resumeRefresh = CompletableDeferred<Unit>()
        var blocked = false
        var readCount = 0
        whenever(paykitSdkService.linkedPeers()).doSuspendableAnswer {
            if (readCount++ == 0) {
                refreshPaused.complete(Unit)
                resumeRefresh.await()
                emptyList()
            } else if (blocked) {
                listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED))
            } else {
                emptyList()
            }
        }
        val refresh = async { sut.refresh() }
        runCurrent()
        refreshPaused.await()
        val acceptance = async { sut.accept(request) }
        runCurrent()
        blocked = true
        resumeRefresh.complete(Unit)
        refresh.await().getOrThrow()
        assertEquals(PaykitPaymentRequestError.RequestUnavailable, acceptance.await().exceptionOrNull())
    }

    @Test
    fun `refresh maps actionable bitcoin request`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(60.seconds).toString())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))

        sut.refresh().getOrThrow()

        val request = sut.pendingRequests.value.single()
        assertEquals(100_000uL, request.amountSats)
        assertEquals(listOf(MethodId.Bolt11.rawValue), request.acceptedPaymentEndpointIdentifiers)
    }

    @Test
    fun `peer intake failure does not drop received requests`() = test {
        val record = paymentRequestRecord()
        val error = mock<PrivateOperationError> {
            on { redactedContext() } doReturn "transport failure"
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers()).thenReturn(
            listOf(
                PrivateStreamCounterpartyIntakeReport(
                    counterparty = COUNTERPARTY,
                    report = null,
                    error = error,
                ),
            ),
        )

        sut.refresh().getOrThrow()
        assertEquals(record.paymentRequestId, sut.pendingRequests.value.single().paymentRequestId)
    }

    @Test
    fun `incoming parse failures are reason specific`() {
        val cases = listOf(
            paymentRequestRecord(role = null) to PaykitPaymentRequest.ParseFailure.MissingLocalRole,
            paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE) to
                PaykitPaymentRequest.ParseFailure.OutgoingRequest,
            paymentRequestRecord(role = PaymentRequestLocalRole.UNKNOWN) to
                PaykitPaymentRequest.ParseFailure.UnsupportedLocalRole,
            paymentRequestRecord(state = PaymentRequestLifecycleState.REJECTED) to
                PaykitPaymentRequest.ParseFailure.NonActionableState,
            paymentRequestRecord().copy(terms = null) to PaykitPaymentRequest.ParseFailure.MissingTerms,
            paymentRequestRecord(asset = "BTC") to PaykitPaymentRequest.ParseFailure.UnsupportedAsset,
            paymentRequestRecord(paymentDeadline = PaymentDeadline.At(clock.now().plus(1.seconds).toString())) to
                PaykitPaymentRequest.ParseFailure.UnsupportedPaymentDeadline,
            paymentRequestRecord(amount = "not-bitcoin") to PaykitPaymentRequest.ParseFailure.InvalidAmount,
            paymentRequestRecord(amount = "184467440737.09551615") to
                PaykitPaymentRequest.ParseFailure.AmountOutOfRange,
            paymentRequestRecord(endpoints = listOf("btc-unsupported-method")) to
                PaykitPaymentRequest.ParseFailure.NoSupportedEndpoint,
            paymentRequestRecord(expiresAt = "not-a-timestamp") to
                PaykitPaymentRequest.ParseFailure.InvalidExpiration,
            paymentRequestRecord(expiresAt = clock.now().toString()) to PaykitPaymentRequest.ParseFailure.Expired,
        )

        cases.forEach { (record, expectedReason) ->
            val result = record.parseIncomingPaykitPaymentRequest(clock.now())
                as PaykitPaymentRequestParseResult.Rejected

            assertEquals(expectedReason, result.reason)
        }
    }

    @Test
    fun `refresh emits reason specific parse rejection diagnostic`() = test {
        val record = paymentRequestRecord(
            asset = "BTC",
            counterparty = "secret",
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))

        sut.refresh().getOrThrow()

        verify(diagnostics).logParseRejection("secret", PaykitPaymentRequest.ParseFailure.UnsupportedAsset)
    }

    @Test
    fun `refresh excludes unknown local roles at the app boundary`() = test {
        val record = paymentRequestRecord(
            role = PaymentRequestLocalRole.UNKNOWN,
            counterparty = "secret",
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))

        sut.refresh().getOrThrow()

        verify(diagnostics, never()).logParseRejection(any(), any())
        assertTrue(sut.pendingRequests.value.isEmpty())
    }

    @Test
    fun `refresh does not log outgoing payee requests`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE, counterparty = "secret")),
        )

        sut.refresh().getOrThrow()

        verify(diagnostics, never()).logParseRejection(any(), any())
    }

    @Test
    fun `refresh does not log expired requests`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(expiresAt = clock.now().toString())),
        )

        sut.refresh().getOrThrow()

        verify(diagnostics, never()).logParseRejection(any(), any())
    }

    @Test
    fun `refresh rejects amounts outside the app payment range`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(id = "millisatoshi-safe-max", amount = "184467440.73709551"),
                paymentRequestRecord(id = "millisatoshi-overflow", amount = "184467440.73709552"),
                paymentRequestRecord(id = "long-max", amount = "92233720368.54775807"),
                paymentRequestRecord(id = "long-overflow", amount = "92233720368.54775808"),
                paymentRequestRecord(id = "ulong-max", amount = "184467440737.09551615"),
            ),
        )

        sut.refresh().getOrThrow()

        assertEquals(listOf("millisatoshi-safe-max"), sut.pendingRequests.value.map { it.paymentRequestId })
        assertEquals(listOf(ULong.MAX_VALUE / 1000uL), sut.pendingRequests.value.map { it.amountSats })
    }

    @Test
    fun `lightning invoice amount must exactly match the request in millisatoshis`() {
        val request = PaykitPaymentRequest(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            amountValue = "0.000025",
            amountSats = 2_500uL,
            expiresAt = null,
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.Bolt11.rawValue),
        )

        assertTrue(request.acceptsLightningInvoiceAmountMsats(null))
        assertTrue(request.acceptsLightningInvoiceAmountMsats(2_500_000uL))
        assertFalse(request.acceptsLightningInvoiceAmountMsats(2_499_999uL))
        assertFalse(request.acceptsLightningInvoiceAmountMsats(2_500_001uL))
    }

    @Test
    fun `refresh drops expired unsupported and non payer requests`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(expiresAt = clock.now().toString()),
                paymentRequestRecord(id = "unsupported", endpoints = listOf("btc-unsupported-method")),
                paymentRequestRecord(id = "payee", role = PaymentRequestLocalRole.PAYEE),
            ),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
    }

    @Test
    fun `refresh keeps one time bitcoin lifecycle history`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(id = "incoming"),
                paymentRequestRecord(id = "accepted", state = PaymentRequestLifecycleState.ACCEPTED),
                paymentRequestRecord(id = "rejected", state = PaymentRequestLifecycleState.REJECTED),
                paymentRequestRecord(
                    id = "expired",
                    state = PaymentRequestLifecycleState.PROPOSAL_EXPIRED,
                    expiresAt = clock.now().toString(),
                ),
                paymentRequestRecord(id = "outgoing", role = PaymentRequestLocalRole.PAYEE),
                paymentRequestRecord(id = "unsupported", endpoints = listOf("btc-unsupported-method")),
                paymentRequestRecord(id = "recurring", state = PaymentRequestLifecycleState.ACTIVE_RECURRING),
            ) + listOf(
                PaymentRequestLifecycleState.PROPOSED,
                PaymentRequestLifecycleState.ACCEPTED,
                PaymentRequestLifecycleState.PROOF_SUBMITTED,
                PaymentRequestLifecycleState.CANCELED,
                PaymentRequestLifecycleState.REJECTED,
            ).map { state ->
                paymentRequestRecord(
                    id = "deadline-$state",
                    state = state,
                    paymentDeadline = PaymentDeadline.At(clock.now().toString()),
                )
            },
        )

        sut.refresh().getOrThrow()

        assertEquals(listOf("incoming", "accepted"), sut.pendingRequests.value.map { it.paymentRequestId })
        assertEquals(
            setOf(
                "incoming", "accepted", "rejected", "expired", "outgoing", "unsupported",
                "deadline-PROPOSED", "deadline-ACCEPTED", "deadline-PROOF_SUBMITTED",
                "deadline-CANCELED", "deadline-REJECTED",
            ),
            sut.paymentRequestHistory.value.map { it.paymentRequestId }.toSet(),
        )
        assertEquals(
            PaymentRequestLifecycleState.ACCEPTED,
            sut.paymentRequestHistory.value.first { it.paymentRequestId == "accepted" }.lifecycleState,
        )
        assertEquals(
            PaykitPaymentRequestDirection.Outgoing,
            sut.paymentRequestHistory.value.first { it.paymentRequestId == "outgoing" }.direction,
        )
    }

    @Test
    fun `completed one time payment retains its local payment rail`() = test {
        val record = paymentRequestRecord()
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
        )
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY))
            .thenReturn(mapOf(requestId to PaykitPaymentProofKind.Onchain))
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))

        sut.refresh().getOrThrow()

        val request = sut.paymentRequestHistory.value.single()
        assertEquals(PaymentRequestLifecycleState.PROOF_SUBMITTED, request.lifecycleState)
        assertEquals(PaykitPaymentProofKind.Onchain, request.paymentProofKind)
    }

    @Test
    fun `completed one time payment retains its SDK payment rail`() = test {
        val proof = mock<PaymentProofRecord> {
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    state = PaymentRequestLifecycleState.PROOF_SUBMITTED,
                    paymentProofs = listOf(proof),
                ),
            ),
        )

        sut.refresh().getOrThrow()

        assertEquals(PaykitPaymentProofKind.Lightning, sut.paymentRequestHistory.value.single().paymentProofKind)
    }

    @Test
    fun `pending request is removed exactly when it expires`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(expiresAt = clock.now().plus(10.seconds).toString())),
        )
        sut.refresh().getOrThrow()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, sut.pendingRequests.value.size)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(
            PaymentRequestLifecycleState.PROPOSAL_EXPIRED,
            sut.paymentRequestHistory.value.single().lifecycleState,
        )
    }

    @Test
    fun `outgoing request moves to expired history exactly when it expires`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    role = PaymentRequestLocalRole.PAYEE,
                    expiresAt = clock.now().plus(10.seconds).toString(),
                ),
            ),
        )
        sut.refresh().getOrThrow()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(PaymentRequestLifecycleState.PROPOSED, sut.paymentRequestHistory.value.single().lifecycleState)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(
            PaymentRequestLifecycleState.PROPOSAL_EXPIRED,
            sut.paymentRequestHistory.value.single().lifecycleState,
        )
    }

    @Test
    fun `accept removes current request and delivers queued response`() = test {
        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            ),
        ).thenReturn(record)
        sut.refresh().getOrThrow()
        clearInvocations(paykitSdkService)

        sut.accept(sut.pendingRequests.value.single()).getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(PaymentRequestLifecycleState.ACCEPTED, sut.paymentRequestHistory.value.single().lifecycleState)
        verifyBlocking(paykitSdkService) { processPendingPrivateMessages() }
    }

    @Test
    fun `reject removes current request and delivers queued response`() = test {
        val record = paymentRequestRecord()
        val deliveryStarted = CompletableDeferred<Unit>()
        val finishDelivery = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        whenever(
            paykitSdkService.rejectPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            ),
        ).thenReturn(record)
        sut.refresh().getOrThrow()
        clearInvocations(paykitSdkService)
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer {
            deliveryStarted.complete(Unit)
            finishDelivery.await()
            emptyList()
        }

        val rejection = async { sut.reject(sut.pendingRequests.value.single()) }
        deliveryStarted.await()

        assertEquals(1, sut.pendingRequests.value.size)

        finishDelivery.complete(Unit)
        rejection.await().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(PaymentRequestLifecycleState.REJECTED, sut.paymentRequestHistory.value.single().lifecycleState)
        verifyBlocking(paykitSdkService) { processPendingPrivateMessages() }
    }

    @Test
    fun `surfaced request stays pending and is excluded from automatic presentation`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()

        assertTrue(sut.markPresented(request))

        assertEquals(listOf(request), sut.pendingRequests.value)
        assertTrue(sut.automaticPendingRequests().isEmpty())
        verifyBlocking(presentationStore) { save(LOCAL_IDENTITY, setOf(request.id)) }
    }

    @Test
    fun `switching identity clears request state and restores only that identity suppression`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        sut.markPresented(request)
        whenever(presentationStore.load(SECOND_IDENTITY)).thenReturn(setOf(request.id))

        sut.activate(SECOND_IDENTITY)

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `identity switch invalidates an in-flight refresh before waiting for the operation lock`() = test {
        val refreshStarted = CompletableDeferred<Unit>()
        val resumeRefresh = CompletableDeferred<Unit>()
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer {
            refreshStarted.complete(Unit)
            resumeRefresh.await()
            emptyList()
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))
        whenever(presentationStore.load(SECOND_IDENTITY)).thenReturn(emptySet())

        val refresh = async { sut.refresh() }
        runCurrent()
        refreshStarted.await()
        val activation = async { sut.activate(SECOND_IDENTITY) }
        runCurrent()
        resumeRefresh.complete(Unit)

        refresh.await().getOrThrow()
        activation.await()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `proposal uses exact linked capable path and canonical bitcoin terms`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )
        whenever(
            paykitSdkService.proposePaymentRequest(
                eq(COUNTERPARTY),
                any(),
                eq(LOCAL_IDENTITY),
            ),
        ).thenReturn(
            paymentRequestRecord(
                role = PaymentRequestLocalRole.PAYEE,
                counterparty = COUNTERPARTY,
            ),
        )
        val expiry = clock.now().plus(60.seconds)

        val creation = sut.propose(
            draft = PaykitPaymentRequestDraft(amountSats = 1uL, note = " Lunch ", expiresAt = expiry),
            target = target,
            savedPublicKeys = listOf(COUNTERPARTY),
        ).getOrThrow()
        val request = creation.request

        val proposal = argumentCaptor<PaykitPaymentRequestProposalTerms>()
        verifyBlocking(paykitSdkService) {
            proposePaymentRequest(
                eq(COUNTERPARTY),
                proposal.capture(),
                eq(LOCAL_IDENTITY),
            )
        }
        assertEquals("0.00000001", proposal.firstValue.amountValue)
        assertTrue(proposal.firstValue.paymentReference.startsWith("bitkit-"))
        assertEquals(expiry.toString(), proposal.firstValue.proposalExpiresAt)
        assertTrue(proposal.firstValue.acceptedPaymentEndpointIdentifiers.isNotEmpty())
        assertEquals("{\"note\":\"Lunch\"}", proposal.firstValue.metadataJson)
        assertEquals("Lunch", request.note)
        assertEquals(PaykitPaymentRequestDeliveryStatus.Queued, request.deliveryStatus)
        assertEquals(LOCAL_IDENTITY, creation.creatorIdentity)
        assertTrue(creation.wasPublishedToActiveState)
    }

    @Test
    fun `proposal revalidates only the selected saved contact`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        val stalledDiscovery = CompletableDeferred<Unit>()
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED),
                linkedPeer(SECOND_IDENTITY, LinkedPeerState.LINKED),
            ),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )
        whenever(paykitSdkService.canReceivePaymentRequests(SECOND_IDENTITY)).doSuspendableAnswer {
            stalledDiscovery.await()
            true
        }
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))).thenReturn(
            paymentRequestRecord(
                role = PaymentRequestLocalRole.PAYEE,
                counterparty = COUNTERPARTY,
            ),
        )

        val proposal = async {
            sut.propose(
                draft = PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds)),
                target = target,
                savedPublicKeys = listOf(SECOND_IDENTITY, COUNTERPARTY),
            )
        }
        runCurrent()

        assertTrue(proposal.isCompleted)
        proposal.await().getOrThrow()
        verifyBlocking(paykitSdkService, never()) { canReceivePaymentRequests(SECOND_IDENTITY) }
    }

    @Test
    fun `identity switch keeps a committed proposal out of the replacement identity state`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        val proposalStarted = CompletableDeferred<Unit>()
        val finishProposal = CompletableDeferred<Unit>()
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))).doSuspendableAnswer {
            proposalStarted.complete(Unit)
            finishProposal.await()
            paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE)
        }
        whenever(presentationStore.load(SECOND_IDENTITY)).thenReturn(emptySet())

        val proposal = async {
            sut.propose(
                draft = PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds)),
                target = target,
                savedPublicKeys = listOf(COUNTERPARTY),
            ).getOrThrow()
        }
        proposalStarted.await()
        val activation = async { sut.activate(SECOND_IDENTITY) }
        runCurrent()
        finishProposal.complete(Unit)

        val creation = proposal.await()
        activation.await()

        assertEquals(LOCAL_IDENTITY, creation.creatorIdentity)
        assertFalse(creation.wasPublishedToActiveState)
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
    }

    @Test
    fun `incoming refresh does not wait for recipient discovery`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )

        sut.refresh().getOrThrow()

        assertEquals(1, sut.pendingRequests.value.size)
        verifyBlocking(paykitSdkService, never()) { canReceivePaymentRequests(any()) }
    }

    @Test
    fun `recipient discovery reuses unchanged link state`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(1)) { canReceivePaymentRequests(COUNTERPARTY) }
    }

    @Test
    fun `single recipient refresh adds a newly eligible contact`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )

        val target = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow().target

        val expected = PaykitPaymentRequestTarget(COUNTERPARTY)
        assertEquals(expected, target)
        assertEquals(listOf(expected), sut.eligibleTargets.value)
    }

    @Test
    fun `single recipient refresh removes a contact that is no longer linked`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
            emptyList(),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val target = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow().target

        assertNull(target)
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `single recipient refresh removes a contact that stopped accepting requests`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY))
            .thenReturn(true, false)
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val target = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow().target

        assertNull(target)
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `single recipient refresh keeps a known target while capability lookup fails`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY))
            .thenReturn(true)
            .thenThrow(IllegalStateException("marker unavailable"))
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val check = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow()

        val expected = PaykitPaymentRequestTarget(COUNTERPARTY)
        assertEquals(expected, check.target)
        assertFalse(check.isComplete)
        assertEquals(listOf(expected), sut.eligibleTargets.value)
    }

    @Test
    fun `failed single recipient refresh does not overwrite an older full refresh`() = test {
        val fullLookupStarted = CompletableDeferred<Unit>()
        val releaseFullLookup = CompletableDeferred<Unit>()
        var lookups = 0
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).doSuspendableAnswer {
            lookups += 1
            when (lookups) {
                1 -> true
                2 -> {
                    fullLookupStarted.complete(Unit)
                    releaseFullLookup.await()
                    false
                }
                else -> error("marker unavailable")
            }
        }
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val fullRefresh = async { sut.refreshEligibleTargets(listOf(COUNTERPARTY), force = true) }
        fullLookupStarted.await()
        val check = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow()
        releaseFullLookup.complete(Unit)
        fullRefresh.await().getOrThrow()

        assertFalse(check.isComplete)
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `failed full refresh keeps a newer single recipient result`() = test {
        val fullLinkLookupStarted = CompletableDeferred<Unit>()
        val releaseFullLinkLookup = CompletableDeferred<Unit>()
        var linkLookups = 0
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).doSuspendableAnswer {
            linkLookups += 1
            if (linkLookups > 1) {
                return@doSuspendableAnswer listOf(
                    linkedPeer(SECOND_IDENTITY, LinkedPeerState.LINKED),
                )
            }
            fullLinkLookupStarted.complete(Unit)
            releaseFullLinkLookup.await()
            error("linked peers unavailable")
        }
        whenever(paykitSdkService.canReceivePaymentRequests(SECOND_IDENTITY))
            .thenReturn(true)

        val fullRefresh = async { sut.refreshEligibleTargets(listOf(COUNTERPARTY)) }
        fullLinkLookupStarted.await()
        sut.refreshEligibleTarget(SECOND_IDENTITY).getOrThrow()
        releaseFullLinkLookup.complete(Unit)

        assertTrue(fullRefresh.await().isFailure)
        assertEquals(
            listOf(PaykitPaymentRequestTarget(SECOND_IDENTITY)),
            sut.eligibleTargets.value,
        )
    }

    @Test
    fun `single recipient refresh is not overwritten by an older full refresh`() = test {
        val fullLookupStarted = CompletableDeferred<Unit>()
        val releaseFullLookup = CompletableDeferred<Unit>()
        var lookups = 0
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).doSuspendableAnswer {
            lookups += 1
            if (lookups > 1) return@doSuspendableAnswer false
            fullLookupStarted.complete(Unit)
            releaseFullLookup.await()
            true
        }

        val fullRefresh = async { sut.refreshEligibleTargets(listOf(COUNTERPARTY)) }
        fullLookupStarted.await()
        val target = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow().target
        releaseFullLookup.complete(Unit)
        fullRefresh.await().getOrThrow()

        assertNull(target)
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `older single recipient refresh does not overwrite a newer full refresh`() = test {
        val singleLookupStarted = CompletableDeferred<Unit>()
        val releaseSingleLookup = CompletableDeferred<Unit>()
        var lookups = 0
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).doSuspendableAnswer {
            lookups += 1
            if (lookups > 1) return@doSuspendableAnswer true
            singleLookupStarted.complete(Unit)
            releaseSingleLookup.await()
            false
        }

        val singleRefresh = async { sut.refreshEligibleTarget(COUNTERPARTY) }
        singleLookupStarted.await()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        releaseSingleLookup.complete(Unit)
        val target = singleRefresh.await().getOrThrow().target

        val expected = PaykitPaymentRequestTarget(COUNTERPARTY)
        assertEquals(expected, target)
        assertEquals(listOf(expected), sut.eligibleTargets.value)
    }

    @Test
    fun `older full refresh keeps its results for other contacts`() = test {
        val fullLookupStarted = CompletableDeferred<Unit>()
        val releaseFullLookup = CompletableDeferred<Unit>()
        var counterpartyLookups = 0
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED),
                linkedPeer(SECOND_IDENTITY, LinkedPeerState.LINKED),
            ),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).doSuspendableAnswer {
            counterpartyLookups += 1
            if (counterpartyLookups > 1) return@doSuspendableAnswer false
            fullLookupStarted.complete(Unit)
            releaseFullLookup.await()
            true
        }
        whenever(paykitSdkService.canReceivePaymentRequests(SECOND_IDENTITY))
            .thenReturn(true)

        val fullRefresh = async { sut.refreshEligibleTargets(listOf(COUNTERPARTY, SECOND_IDENTITY)) }
        fullLookupStarted.await()
        sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow()
        releaseFullLookup.complete(Unit)
        fullRefresh.await().getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(SECOND_IDENTITY)),
            sut.eligibleTargets.value,
        )
    }

    @Test
    fun `failed recipient discovery drops contacts that are no longer saved`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers())
            .thenReturn(listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)))
            .thenThrow(IllegalStateException("linked peers unavailable"))
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val result = sut.refreshEligibleTargets(listOf(SECOND_IDENTITY), force = true)

        assertTrue(result.isFailure)
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `recipient discovery retries capabilities that are not published yet`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY))
            .thenReturn(false, true)

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(2)) { canReceivePaymentRequests(COUNTERPARTY) }
    }

    @Test
    fun `recipient discovery retains a known target while capability refresh fails`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY))
            .thenReturn(true)
            .thenThrow(IllegalStateException("marker unavailable"))

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY), force = true).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(2)) { canReceivePaymentRequests(COUNTERPARTY) }
    }

    @Test
    fun `recipient discovery bounds a stalled capability lookup`() = test {
        val discoveryStarted = CompletableDeferred<Unit>()
        val stalledDiscovery = CompletableDeferred<Unit>()
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).doSuspendableAnswer {
            discoveryStarted.complete(Unit)
            stalledDiscovery.await()
            true
        }

        val targetRefresh = async { sut.refreshEligibleTargets(listOf(COUNTERPARTY)) }
        discoveryStarted.await()
        advanceTimeBy(5.seconds.inWholeMilliseconds)
        runCurrent()

        assertTrue(targetRefresh.isCompleted)
        targetRefresh.await().getOrThrow()
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `outgoing requests require private payment publication`() = test {
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = false)))
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertTrue(sut.eligibleTargets.value.isEmpty())
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any()) }
    }

    @Test
    fun `outgoing requests require the active SDK identity`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(SECOND_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(
            true,
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertTrue(sut.eligibleTargets.value.isEmpty())
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any()) }
    }

    @Test
    fun `expired draft is rejected before proposal is queued`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)

        assertFailsWith<PaykitPaymentRequestError.RequestExpired> {
            sut.propose(
                draft = PaykitPaymentRequestDraft(1uL, "", clock.now()),
                target = target,
                savedPublicKeys = listOf(COUNTERPARTY),
            ).getOrThrow()
        }
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any()) }
    }

    @Test
    fun `expired request cannot be accepted`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(1.seconds).toString())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        advanceTimeBy(1_000)

        assertFailsWith<PaykitPaymentRequestError.RequestExpired> {
            sut.accept(request).getOrThrow()
        }
        verifyBlocking(paykitSdkService, never()) {
            acceptPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID)
        }
    }

    @Test
    fun `expired request is no longer pending before the expiration job runs`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(1.seconds).toString())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        advanceTimeBy(1_000)

        assertTrue(!sut.isPending(request))
    }

    @Suppress("LongParameterList")
    private fun paymentRequestRecord(
        id: String = PAYMENT_REQUEST_ID,
        role: PaymentRequestLocalRole? = PaymentRequestLocalRole.PAYER,
        state: PaymentRequestLifecycleState = PaymentRequestLifecycleState.PROPOSED,
        amount: String = "0.001",
        asset: String = "btc",
        expiresAt: String? = null,
        paymentDeadline: PaymentDeadline? = null,
        endpoints: List<String> = listOf(MethodId.Bolt11.rawValue),
        counterparty: String = COUNTERPARTY,
        recurrence: PaymentRequestRecurrence? = null,
        metadata: PrivateJsonObject = METADATA,
        paymentProofs: List<PaymentProofRecord> = emptyList(),
    ) = PaymentRequestRecord(
        counterparty = counterparty,
        paymentRequestId = id,
        localRole = role,
        state = state,
        proposalStreamItemId = 1uL,
        proposalOutboundMessageId = null,
        proposalOutboundStatus = null,
        proposalEventId = "proposal-event",
        terms = PaymentRequestTerms(
            amount = PaymentRequestAmount(value = amount, asset = asset),
            paymentReference = PAYMENT_REFERENCE,
            proposalExpiresAt = expiresAt,
            recurrence = recurrence,
            acceptedPaymentEndpointIdentifiers = endpoints,
            conversion = null,
            paymentDeadline = paymentDeadline,
            metadata = metadata,
            paymentEndpoints = null,
            requiredAppId = "bitkit",
        ),
        acceptedEventId = null,
        acceptedOutboundStatus = null,
        rejectedEventId = null,
        rejectedOutboundStatus = null,
        canceledEventId = null,
        canceledOutboundStatus = null,
        conversionQuotes = emptyList(),
        paymentProofs = paymentProofs,
        lastStreamItemId = 1uL,
        lastOutboundMessageId = null,
        lastOutboundStatus = null,
        lastEventAt = clock.now().toString(),
        invalidReason = null,
        proposalAppId = "bitkit",
        payerAppId = null,
        executionClaimAppId = null,
    )

    private fun linkedPeer(
        publicKey: String,
        state: LinkedPeerState,
    ) = LinkedPeerRecord(
        counterparty = publicKey,
        state = state,
        lastSyncAt = null,
        lastPrivateReceiveAt = null,
        failureCount = 0u,
        localRecoveryAttemptId = null,
        localRecoveryMarkerCreatedAt = null,
        localRecoveryMarkerLastError = null,
        remoteRecoveryAttemptId = null,
        remoteRecoveryMarkerObservedAt = null,
    )
}
