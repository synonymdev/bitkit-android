@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestRecurrence
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentRequestRepoSubscriptionTest : BaseUnitTest(StandardTestDispatcher()) {
    private companion object {
        const val PAYMENT_REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        const val LOCAL_IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val START_TIME = Instant.parse("2027-01-15T08:00:00Z")
        val PAYMENT_REFERENCE = mock<PaymentReference> {
            on { exportText() } doReturn "invoice-123"
        }
        val METADATA = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"order":"123"}"""
        }
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private val settingsStore = mock<SettingsStore>()
    private val presentationStore = mock<PaykitPaymentRequestPresentationStore>()
    private val paymentProofStore = mock<PaykitPaymentProofStore>()
    private val paymentProofRepo = mock<PaykitPaymentProofRepo>()
    private val notificationScheduler = mock<PaykitSubscriptionNotificationScheduler>()
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
            paymentProofStore,
            paymentProofRepo,
            notificationScheduler,
            clock,
        )
        sut.activate(LOCAL_IDENTITY)
    }

    @After
    fun tearDown() = test {
        sut.clear()
    }

    @Test
    fun `refresh maps active subscription and exposes current unpaid period`() = test {
        val metadataText = """
            {"note":"Mobile plan","subscription":{"version":1,"description":"10 GB every month","benefits":["Roaming"]}}
        """.trimIndent()
        val metadata = mock<PrivateJsonObject> {
            on { exportText() } doReturn metadataText
        }
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(
                    id = "recurring",
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    metadata = metadata,
                ),
            ),
        )

        sut.refresh().getOrThrow()

        val subscription = sut.subscriptions.value.single()
        assertEquals("Mobile plan", subscription.note)
        assertEquals("10 GB every month", subscription.metadata.description)
        assertEquals(listOf("Roaming"), subscription.metadata.benefits)
        val request = sut.pendingRequests.value.single()
        assertEquals("recurring", request.paymentRequestId)
        assertFalse(request.requiresAcceptance)
        assertEquals(Instant.parse("2027-01-01T08:00:00Z"), request.billingPeriod?.startsAt)
        assertEquals(Instant.parse("2027-02-01T08:00:00Z"), request.billingPeriod?.endsAt)
    }

    @Test
    fun `accepting subscription returns current period and preserves payment targets`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(proposal), listOf(active))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PaykitReceiverPaths.SERVER,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(active)
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED, PaykitReceiverPaths.SERVER)),
        )
        whenever(paykitSdkService.paymentRequestReceiverPaths(COUNTERPARTY))
            .thenReturn(listOf(PaykitReceiverPaths.SERVER))
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        sut.refresh().getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val subscription = sut.subscriptions.value.single()
        val dueRequest = sut.accept(subscription).getOrThrow()

        assertEquals(Instant.parse("2027-01-01T08:00:00Z"), dueRequest?.billingPeriod?.startsAt)
        assertEquals(listOf(COUNTERPARTY), sut.eligibleTargets.value.map { it.publicKey })
        verifyBlocking(presentationStore) {
            saveSubscriptionState(
                eq(LOCAL_IDENTITY),
                argThat { subscription.id in acceptedAt },
            )
        }
    }

    @Test
    fun `accepting subscription rejects terms changed after review`() = test {
        val reviewedRecord = paymentRequestRecord()
        val changedRecord = paymentRequestRecord(amount = "0.002")
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(reviewedRecord), listOf(changedRecord))
        sut.refresh().getOrThrow()
        val reviewedSubscription = sut.subscriptions.value.single()
        sut.refresh().getOrThrow()

        val result = sut.accept(reviewedSubscription)

        assertTrue(result.exceptionOrNull() is PaykitPaymentRequestError.RequestUnavailable)
        verifyBlocking(paykitSdkService, never()) { acceptPaymentRequest(any(), any(), any()) }
    }

    @Test
    fun `accepted subscription stays successful when its immediate refresh fails`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.paymentRequests())
            .thenReturn(listOf(proposal))
            .thenThrow(IllegalStateException("refresh failed"))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PaykitReceiverPaths.SERVER,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(active)
        sut.refresh().getOrThrow()

        val dueRequest = sut.accept(sut.subscriptions.value.single()).getOrThrow()

        assertEquals(PaymentRequestLifecycleState.ACTIVE_RECURRING, sut.subscriptions.value.single().lifecycleState)
        assertEquals(Instant.parse("2027-01-01T08:00:00Z"), dueRequest?.billingPeriod?.startsAt)
        assertEquals(listOf(dueRequest), sut.pendingRequests.value)
    }

    @Test
    fun `dismissed subscription period stays out of queue after refresh`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()

        assertTrue(sut.dismissSubscriptionPayment(request))
        assertTrue(sut.pendingRequests.value.isEmpty())

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        verifyBlocking(presentationStore) {
            saveSubscriptionState(eq(LOCAL_IDENTITY), argThat { dismissedPaymentIds == setOf(request.id) })
        }
    }

    @Test
    fun `completed subscription payment awaiting proof submission is not offered again`() = test {
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
            billingPeriodStartsAt = "2027-01-01T08:00:00Z",
        )
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY))
            .thenReturn(mapOf(requestId to PaykitPaymentProofKind.Onchain))
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(requestId, sut.paymentRequestHistory.value.single().id)
        assertEquals(
            PaymentRequestLifecycleState.PROOF_SUBMITTED,
            sut.paymentRequestHistory.value.single().lifecycleState,
        )
        assertEquals(PaykitPaymentProofKind.Onchain, sut.paymentRequestHistory.value.single().paymentProofKind)
    }

    @Test
    fun `completed subscription payment retains its SDK payment rail`() = test {
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(
                startsAt = "2027-01-01T08:00:00Z",
                endsAt = "2027-02-01T08:00:00Z",
            )
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    paymentProofs = listOf(proof),
                ),
            ),
        )

        sut.refresh().getOrThrow()

        val request = sut.paymentRequestHistory.value.single()
        assertEquals(PaymentRequestLifecycleState.PROOF_SUBMITTED, request.lifecycleState)
        assertEquals(PaykitPaymentProofKind.Lightning, request.paymentProofKind)
    }

    @Test
    fun `in flight subscription payment is neither offered nor marked paid`() = test {
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
            billingPeriodStartsAt = "2027-01-01T08:00:00Z",
        )
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(setOf(requestId))
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
    }

    @Test
    fun `subscription cannot be canceled after payment has started`() = test {
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
            billingPeriodStartsAt = "2027-01-01T08:00:00Z",
        )
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paymentProofRepo.protectedRequestIdsForSubscriptionCancellation(eq(LOCAL_IDENTITY), any()))
            .thenReturn(Result.success(setOf(requestId)))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(active))
        sut.refresh().getOrThrow()

        val result = sut.cancel(sut.subscriptions.value.single())

        assertTrue(result.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        assertEquals(1, sut.subscriptions.value.size)
        verifyBlocking(paykitSdkService, never()) { cancelPaymentRequest(any(), any(), any(), anyOrNull()) }
    }

    @Test
    fun `subscription cancellation proceeds without a started payment`() = test {
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        val canceled = paymentRequestRecord(state = PaymentRequestLifecycleState.CANCELED)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(active), emptyList())
        whenever(
            paykitSdkService.cancelPaymentRequest(
                COUNTERPARTY,
                PaykitReceiverPaths.SERVER,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(canceled)
        sut.refresh().getOrThrow()

        sut.cancel(sut.subscriptions.value.single()).getOrThrow()

        verifyBlocking(paykitSdkService) {
            cancelPaymentRequest(COUNTERPARTY, PaykitReceiverPaths.SERVER, PAYMENT_REQUEST_ID)
        }
    }

    @Test
    fun `malformed expiry is rejected and unsupported payment details disable acceptance`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(id = "malformed", expiresAt = "not-a-timestamp"),
                paymentRequestRecord(id = "unsupported", endpoints = listOf("btc-unsupported-method")),
            ),
        )

        sut.refresh().getOrThrow()

        val subscription = sut.subscriptions.value.single()
        assertEquals("unsupported", subscription.paymentRequestId)
        assertFalse(subscription.isProposalActionable(clock.now()))
        assertEquals(listOf(subscription), sut.subscriptionProposals())
    }

    @Test
    fun `presented subscription stays available without auto presenting after reactivation`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(paymentRequestRecord(id = "subscription")),
        )
        sut.refresh().getOrThrow()
        val subscription = sut.subscriptions.value.single()

        assertTrue(sut.markSubscriptionProposalPresented(subscription))
        assertTrue(sut.automaticSubscriptionProposals().isEmpty())
        verifyBlocking(presentationStore) {
            saveSubscriptionState(eq(LOCAL_IDENTITY), argThat { presentedProposalIds == setOf(subscription.id) })
        }

        sut.clear()
        whenever(presentationStore.loadSubscriptionState(LOCAL_IDENTITY)).thenReturn(
            PaykitSubscriptionPresentationState(presentedProposalIds = setOf(subscription.id)),
        )
        sut.activate(LOCAL_IDENTITY)
        sut.refresh().getOrThrow()

        assertEquals(listOf(subscription), sut.subscriptionProposals())
        assertTrue(sut.automaticSubscriptionProposals().isEmpty())
    }

    @Test
    fun `subscription proposal moves to expired at its deadline`() = test {
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(paymentRequestRecord(expiresAt = clock.now().plus(10.seconds).toString())),
        )
        sut.refresh().getOrThrow()

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(PaymentRequestLifecycleState.PROPOSAL_EXPIRED, sut.subscriptions.value.single().lifecycleState)
        assertTrue(sut.subscriptionProposals().isEmpty())
    }

    @Test
    fun `subscription proposal moves to expired when its schedule ends`() = test {
        val endingRecurrence = PaymentRequestRecurrence(
            every = 1u,
            unit = "month",
            startsAt = "2027-01-01T08:00:00Z",
            anchor = "2027-01-01T08:00:00Z",
            endsAt = clock.now().plus(10.seconds).toString(),
        )
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(paymentRequestRecord(recurrence = endingRecurrence)),
        )
        sut.refresh().getOrThrow()

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(PaymentRequestLifecycleState.PROPOSAL_EXPIRED, sut.subscriptions.value.single().lifecycleState)
        assertTrue(sut.subscriptionProposals().isEmpty())
    }

    @Test
    fun `ended subscription keeps its unpaid period available`() = test {
        val subscriptionId = PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY, PaykitReceiverPaths.SERVER)
        val endingRecurrence = PaymentRequestRecurrence(
            every = 1u,
            unit = "month",
            startsAt = "2027-01-01T08:00:00Z",
            anchor = "2027-01-01T08:00:00Z",
            endsAt = "2027-01-10T08:00:00Z",
        )
        sut.clear()
        whenever(presentationStore.loadSubscriptionState(LOCAL_IDENTITY)).thenReturn(
            PaykitSubscriptionPresentationState(
                acceptedAt = mapOf(subscriptionId to Instant.parse("2027-01-01T08:00:00Z")),
            ),
        )
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    recurrence = endingRecurrence,
                ),
            ),
        )
        sut.activate(LOCAL_IDENTITY)

        sut.refresh().getOrThrow()

        assertTrue(sut.subscriptions.value.single().isExpired(clock.now()))
        assertEquals(
            Instant.parse(requireNotNull(endingRecurrence.endsAt)),
            sut.pendingRequests.value.single().billingPeriod?.endsAt,
        )
    }

    @Suppress("LongParameterList")
    private fun paymentRequestRecord(
        id: String = PAYMENT_REQUEST_ID,
        state: PaymentRequestLifecycleState = PaymentRequestLifecycleState.PROPOSED,
        amount: String = "0.001",
        expiresAt: String? = null,
        endpoints: List<String> = listOf(MethodId.Bolt11.rawValue),
        metadata: PrivateJsonObject = METADATA,
        recurrence: PaymentRequestRecurrence = this.recurrence,
        paymentProofs: List<PaymentProofRecord> = emptyList(),
    ) = PaymentRequestRecord(
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
        paymentRequestId = id,
        localRole = PaymentRequestLocalRole.PAYER,
        state = state,
        proposalStreamItemId = 1uL,
        proposalOutboundMessageId = null,
        proposalOutboundStatus = null,
        proposalEventId = "proposal-event",
        terms = PaymentRequestTerms(
            amount = PaymentRequestAmount(value = amount, asset = "btc"),
            paymentReference = PAYMENT_REFERENCE,
            proposalExpiresAt = expiresAt,
            recurrence = recurrence,
            acceptedPaymentEndpointIdentifiers = endpoints,
            metadata = metadata,
        ),
        acceptedEventId = null,
        acceptedOutboundStatus = null,
        rejectedEventId = null,
        rejectedOutboundStatus = null,
        canceledEventId = null,
        canceledOutboundStatus = null,
        paymentProofs = paymentProofs,
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

    private val recurrence = PaymentRequestRecurrence(
        every = 1u,
        unit = "month",
        startsAt = "2027-01-01T08:00:00Z",
        anchor = "2027-01-01T08:00:00Z",
        endsAt = null,
    )
}
