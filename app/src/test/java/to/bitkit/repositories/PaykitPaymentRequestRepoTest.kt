@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(emptyList())
        whenever(settingsStore.isPaykitEnabled).thenReturn(flowOf(true))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = true)))
        whenever(presentationStore.load(LOCAL_IDENTITY)).thenReturn(emptySet())
        sut = PaykitPaymentRequestRepo(testDispatcher, paykitSdkService, settingsStore, presentationStore, clock)
        sut.activate(LOCAL_IDENTITY)
    }

    @After
    fun tearDown() = test {
        sut.clear()
    }

    @Test
    fun `refresh maps actionable bitcoin request`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(60.seconds).toString())
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))

        sut.refresh().getOrThrow()

        val request = sut.pendingRequests.value.single()
        assertEquals(100_000uL, request.amountSats)
        assertEquals(listOf(MethodId.Bolt11.rawValue), request.acceptedPaymentEndpointIdentifiers)
    }

    @Test
    fun `incoming parse failures are reason specific`() {
        val cases = listOf(
            paymentRequestRecord(role = null) to PaykitPaymentRequest.ParseFailure.MissingLocalRole,
            paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE) to
                PaykitPaymentRequest.ParseFailure.UnsupportedLocalRole,
            paymentRequestRecord(state = PaymentRequestLifecycleState.ACCEPTED) to
                PaykitPaymentRequest.ParseFailure.NonActionableState,
            paymentRequestRecord().copy(terms = null) to PaykitPaymentRequest.ParseFailure.MissingTerms,
            paymentRequestRecord(asset = "BTC") to PaykitPaymentRequest.ParseFailure.UnsupportedAsset,
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
    fun `incoming parse rejection log excludes request data`() {
        val requestId = "do-not-log-this-id"
        val record = paymentRequestRecord(
            id = requestId,
            asset = "BTC",
            counterparty = COUNTERPARTY,
        )
        val result = record.parseIncomingPaykitPaymentRequest(clock.now())
            as PaykitPaymentRequestParseResult.Rejected

        val output = record.incomingPaymentRequestRejectionLog(result.reason)

        assertTrue(output.contains("category='parse' reason='unsupported_asset'"))
        assertTrue(output.contains("counterparty='${PubkyPublicKeyFormat.redacted(COUNTERPARTY)}'"))
        assertFalse(output.contains(COUNTERPARTY))
        assertFalse(output.contains(requestId))
        assertFalse(output.contains(record.terms?.amount?.value.orEmpty()))
        assertFalse(output.contains(record.terms?.acceptedPaymentEndpointIdentifiers?.single().orEmpty()))
    }

    @Test
    fun `refresh rejects amounts outside the app payment range`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(
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
            counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(
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
            ),
        )

        sut.refresh().getOrThrow()

        assertEquals(listOf("incoming"), sut.pendingRequests.value.map { it.paymentRequestId })
        assertEquals(
            setOf("incoming", "accepted", "rejected", "expired", "outgoing", "unsupported"),
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
    fun `pending request is removed exactly when it expires`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PaykitReceiverPaths.SERVER,
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.rejectPaymentRequest(
                COUNTERPARTY,
                PaykitReceiverPaths.SERVER,
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()

        assertTrue(sut.markPresented(request))

        assertEquals(listOf(request), sut.pendingRequests.value)
        assertTrue(sut.automaticPendingRequests().isEmpty())
        verifyBlocking(presentationStore) { save(LOCAL_IDENTITY, setOf(request.id)) }
    }

    @Test
    fun `switching identity clears request state and restores only that identity suppression`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
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
        val target = PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).thenReturn(
            listOf(PaykitReceiverPaths.SERVER),
        )
        whenever(
            paykitSdkService.proposePaymentRequest(
                eq(COUNTERPARTY),
                eq(PaykitReceiverPaths.SERVER),
                any(),
                eq(LOCAL_IDENTITY),
            ),
        ).thenReturn(
            paymentRequestRecord(
                role = PaymentRequestLocalRole.PAYEE,
                counterparty = COUNTERPARTY,
                receiverPath = PaykitReceiverPaths.SERVER,
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
                eq(PaykitReceiverPaths.SERVER),
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
        val target = PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)
        val stalledDiscovery = CompletableDeferred<Unit>()
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER),
                linkedPeer(SECOND_IDENTITY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER),
            ),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).thenReturn(
            listOf(PaykitReceiverPaths.SERVER),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(SECOND_IDENTITY)).doSuspendableAnswer {
            stalledDiscovery.await()
            listOf(PaykitReceiverPaths.SERVER)
        }
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), any(), eq(LOCAL_IDENTITY))).thenReturn(
            paymentRequestRecord(
                role = PaymentRequestLocalRole.PAYEE,
                counterparty = COUNTERPARTY,
                receiverPath = PaykitReceiverPaths.SERVER,
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
        verifyBlocking(paykitSdkService, never()) { paymentRequestReceiverPaths(SECOND_IDENTITY) }
    }

    @Test
    fun `identity switch keeps a committed proposal out of the replacement identity state`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)
        val proposalStarted = CompletableDeferred<Unit>()
        val finishProposal = CompletableDeferred<Unit>()
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).thenReturn(
            listOf(PaykitReceiverPaths.SERVER),
        )
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), any(), eq(LOCAL_IDENTITY))).doSuspendableAnswer {
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )

        sut.refresh().getOrThrow()

        assertEquals(1, sut.pendingRequests.value.size)
        verifyBlocking(paykitSdkService, never()) { paymentRequestReceiverPaths(any()) }
    }

    @Test
    fun `recipient discovery reuses unchanged link state`() = test {
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).thenReturn(
            listOf(PaykitReceiverPaths.SERVER),
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(1)) { paymentRequestReceiverPaths(COUNTERPARTY) }
    }

    @Test
    fun `recipient discovery retries capabilities that are not published yet`() = test {
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY))
            .thenReturn(emptyList(), listOf(PaykitReceiverPaths.SERVER))

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(2)) { paymentRequestReceiverPaths(COUNTERPARTY) }
    }

    @Test
    fun `recipient discovery retains a known target while capability refresh fails`() = test {
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY))
            .thenReturn(listOf(PaykitReceiverPaths.SERVER))
            .thenThrow(IllegalStateException("marker unavailable"))

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY), force = true).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(2)) { paymentRequestReceiverPaths(COUNTERPARTY) }
    }

    @Test
    fun `recipient discovery bounds a stalled capability lookup`() = test {
        val discoveryStarted = CompletableDeferred<Unit>()
        val stalledDiscovery = CompletableDeferred<Unit>()
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).doSuspendableAnswer {
            discoveryStarted.complete(Unit)
            stalledDiscovery.await()
            listOf(PaykitReceiverPaths.SERVER)
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
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).thenReturn(
            listOf(PaykitReceiverPaths.SERVER),
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertTrue(sut.eligibleTargets.value.isEmpty())
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any(), any()) }
    }

    @Test
    fun `outgoing requests require the active SDK identity`() = test {
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(SECOND_IDENTITY, true))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY)).thenReturn(
            listOf(PaykitReceiverPaths.SERVER),
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertTrue(sut.eligibleTargets.value.isEmpty())
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any(), any()) }
    }

    @Test
    fun `expired draft is rejected before proposal is queued`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY, PaykitReceiverPaths.SERVER)

        assertFailsWith<PaykitPaymentRequestError.RequestExpired> {
            sut.propose(
                draft = PaykitPaymentRequestDraft(1uL, "", clock.now()),
                target = target,
                savedPublicKeys = listOf(COUNTERPARTY),
            ).getOrThrow()
        }
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any(), any()) }
    }

    @Test
    fun `expired request cannot be accepted`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(1.seconds).toString())
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        advanceTimeBy(1_000)

        assertFailsWith<PaykitPaymentRequestError.RequestExpired> {
            sut.accept(request).getOrThrow()
        }
        verifyBlocking(paykitSdkService, never()) {
            acceptPaymentRequest(COUNTERPARTY, PaykitReceiverPaths.SERVER, PAYMENT_REQUEST_ID)
        }
    }

    @Test
    fun `expired request is no longer pending before the expiration job runs`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(1.seconds).toString())
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
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
        endpoints: List<String> = listOf(MethodId.Bolt11.rawValue),
        counterparty: String = COUNTERPARTY,
        receiverPath: String = PaykitReceiverPaths.SERVER,
    ) = PaymentRequestRecord(
        counterparty = counterparty,
        counterpartyReceiverPath = receiverPath,
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
            recurrence = null,
            acceptedPaymentEndpointIdentifiers = endpoints,
            metadata = METADATA,
        ),
        acceptedEventId = null,
        acceptedOutboundStatus = null,
        rejectedEventId = null,
        rejectedOutboundStatus = null,
        canceledEventId = null,
        canceledOutboundStatus = null,
        paymentProofs = emptyList(),
        lastStreamItemId = 1uL,
        lastOutboundMessageId = null,
        lastOutboundStatus = null,
        lastEventAt = clock.now().toString(),
        invalidReason = null,
    )

    private fun linkedPeer(
        publicKey: String,
        state: LinkedPeerState,
        receiverPath: String,
    ) = LinkedPeerRecord(
        counterparty = publicKey,
        counterpartyReceiverPath = receiverPath,
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
