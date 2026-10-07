@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.ConversionRate
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateMessageStatus
import com.synonym.paykit.OutboundPrivateSendReport
import com.synonym.paykit.PaymentConversion
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.services.PaykitPaymentRequestProposalTerms
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentRequestRepoSubscriptionTest : BaseUnitTest(StandardTestDispatcher()) {
    private companion object {
        const val PAYMENT_REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        const val LOCAL_IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        const val SECOND_IDENTITY = "pubky4rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
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
    private val diagnostics = mock<PaykitPaymentRequestDiagnostics>()
    private val paymentProofStore = mock<PaykitPaymentProofStore>()
    private val paymentProofRepo = mock<PaykitPaymentProofRepo>()
    private val usdtPayments = mock<PaykitUsdtPaymentRepo>()
    private val notificationScheduler = mock<PaykitSubscriptionNotificationScheduler>()
    private var schedulerOriginMillis = 0L
    private val clock = object : Clock {
        override fun now(): Instant = START_TIME.plus(
            (testDispatcher.scheduler.currentTime - schedulerOriginMillis).milliseconds,
        )
    }
    private var subscriptionOffset = Duration.ZERO
    private val subscriptionClock = object : Clock {
        override fun now(): Instant = clock.now() + subscriptionOffset
    }
    private lateinit var sut: PaykitPaymentRequestRepo

    @Before
    fun setUp() = test {
        schedulerOriginMillis = testDispatcher.scheduler.currentTime
        whenever(paykitSdkService.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(paykitSdkService.processOutboundPrivateMessages(any())).thenReturn(
            OutboundPrivateSendReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        )
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers()).thenReturn(emptyList())
        whenever(paykitSdkService.processPendingPrivateMessages(any())).doSuspendableAnswer {
            paykitSdkService.processPendingPrivateMessages()
        }
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers(any())).doSuspendableAnswer {
            paykitSdkService.receivePrivateMessagesFromLinkedPeers()
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(emptyList())
        whenever(paykitSdkService.linkedPeers()).thenReturn(emptyList())
        whenever(paykitSdkService.identityStatus(any())).doSuspendableAnswer { paykitSdkService.identityStatus() }
        whenever(paykitSdkService.linkedPeers(any())).doSuspendableAnswer { paykitSdkService.linkedPeers() }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull(), any())).doSuspendableAnswer {
            paykitSdkService.allPaymentRequests(it.getArgument(0))
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(any(), any())).doSuspendableAnswer {
            paykitSdkService.processOutboundPrivateMessages(it.getArgument(0))
        }
        whenever(settingsStore.isPaykitEnabled).thenReturn(flowOf(true))
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = true)))
        whenever(presentationStore.load(LOCAL_IDENTITY)).thenReturn(emptySet())
        whenever(
            presentationStore.loadSubscriptionState(any())
        ).thenReturn(PaykitSubscriptionPresentationState())
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY)).thenReturn(emptyMap())
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(emptySet())
        whenever(paymentProofStore.backupStateVersion).thenReturn(MutableStateFlow(0L))
        whenever(paymentProofRepo.protectedRequestIdsForSubscriptionCancellation(any(), any()))
            .thenReturn(Result.success(emptySet()))
        whenever(usdtPayments.verifiedReceipts(any())).thenReturn(Result.success(emptyList()))
        whenever(usdtPayments.protectedRequestIdsForSubscriptionCancellation(any(), any()))
            .thenReturn(Result.success(emptySet()))
        sut = PaykitPaymentRequestRepo(
            testDispatcher,
            paykitSdkService,
            settingsStore,
            presentationStore,
            diagnostics,
            paymentProofStore,
            paymentProofRepo,
            usdtPayments,
            notificationScheduler,
            clock,
            subscriptionClock,
            mock { on { canReceive() }.thenReturn(true) },
            mock { on { currencyState }.thenReturn(kotlinx.coroutines.flow.MutableStateFlow(CurrencyState())) },
        )
        sut.activate(LOCAL_IDENTITY)
    }

    @After
    fun tearDown() = test {
        sut.clear()
    }

    @Test
    fun `dollar subscription requires usdt receiving and fixes parity for future payments`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(COUNTERPARTY)).thenReturn(true)
        val captured = argumentCaptor<PaykitPaymentRequestProposalTerms>()
        whenever(paykitSdkService.proposePaymentRequest(any(), captured.capture(), eq(LOCAL_IDENTITY)))
            .thenReturn(paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE))
        for (enabled in listOf(false, true)) {
            val settings = SettingsData(sharesPrivatePaykitEndpoints = true, publicPaykitUsdtEnabled = enabled)
            whenever(settingsStore.data).thenReturn(flowOf(settings))
            val result = sut.proposeSubscription(
                PaykitSubscriptionDraft(
                    amount = PaykitAmount(PaykitAsset.USD, 500uL),
                    name = "Monthly support",
                    description = "",
                    frequency = PaykitRecurrenceUnit.Month,
                    expiresAt = clock.now() + 60.seconds,
                ),
                target,
                listOf(COUNTERPARTY),
            )
            if (!PublicPaykitRepo.isUsdtPaymentOptionEnabled(settings)) {
                assertEquals(PaykitPaymentRequestError.RequestUnavailable, result.exceptionOrNull())
                assertTrue(captured.allValues.isEmpty())
                continue
            }
            result.getOrThrow()
            val terms = captured.lastValue
            assertEquals(listOf(MethodId.UsdtArbitrum.rawValue), terms.acceptedPaymentEndpointIdentifiers)
            assertEquals(PaymentConversion.Fixed(listOf(ConversionRate("usdt", "1"))), terms.conversion)
            val later = clock.now() + (40 * 86400).seconds
            val period = PaykitBillingPeriod(later, later + (30 * 86400).seconds)
            val pricing = PaykitRequestPricing(terms.conversion, terms.paymentDeadline)
            val payment = pricing.payment(
                PaykitAmount.parse(PaykitAsset.USD, terms.amountValue),
                PaykitAsset.USDT,
                period,
                later,
            )
            assertEquals("5", payment.amount.value)
            assertEquals(null, payment.quoteId)
            assertTrue(payment.isValid(later))
            assertTrue(pricing.quotes.isEmpty())
        }
    }

    @Test
    fun `refresh preserves all supported subscription denominations`() = test {
        val records = PaykitAsset.entries.map { asset ->
            val record = paymentRequestRecord(id = asset.code, state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
            record.copy(terms = requireNotNull(record.terms).copy(amount = PaymentRequestAmount("1", asset.code)))
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(records)
        sut.refresh().getOrThrow()
        assertEquals(PaykitAsset.entries.toSet(), sut.subscriptions.value.map { it.amount.asset }.toSet())
        assertEquals(PaykitAsset.entries.toSet(), sut.pendingRequests.value.map { it.amount.asset }.toSet())
        assertTrue(sut.pendingRequests.value.all { it.amount.value == "1" })
    }

    @Test
    fun `inbox refresh maps active subscription and exposes current unpaid period`() = test {
        val metadataText = """
            {"note":"Mobile plan","subscription":{"version":1,"description":"10 GB every month","benefits":["Roaming"]}}
        """.trimIndent()
        val metadata = mock<PrivateJsonObject> {
            on { exportText() } doReturn metadataText
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    id = "recurring",
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    metadata = metadata,
                ),
            ),
        )

        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
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
    fun `recurring final authorization survives in flight filtering but rejects identity switches`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        sut.accept(request).getOrThrow()
        sut.ensurePaymentAllowed(request).getOrThrow()
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(setOf(request.id))
        sut.refresh().getOrThrow()
        assertTrue(sut.pendingRequests.value.isEmpty())
        sut.ensurePaymentAllowed(request).getOrThrow()

        val checking = CompletableDeferred<Unit>()
        val checked = CompletableDeferred<Unit>()
        whenever(paykitSdkService.linkedPeers()).doSuspendableAnswer {
            checking.complete(Unit)
            checked.await()
            emptyList()
        }
        val authorization = async { sut.ensurePaymentAllowed(request) }
        checking.await()
        sut.activate(SECOND_IDENTITY)
        checked.complete(Unit)

        assertTrue(authorization.await().isFailure)
        assertTrue(sut.ensurePaymentAllowed(request).isFailure)
    }

    @Test
    fun `a canceled subscription cannot finish payment authorization`() = test {
        val record = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        sut.accept(request).getOrThrow()
        val checking = CompletableDeferred<Unit>()
        val checked = CompletableDeferred<Unit>()
        var pauseNextLookup = true
        whenever(paykitSdkService.linkedPeers()).doSuspendableAnswer {
            if (pauseNextLookup) {
                pauseNextLookup = false
                checking.complete(Unit)
                checked.await()
            }
            emptyList()
        }

        val authorization = async { sut.ensurePaymentAllowed(request) }
        checking.await()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
            .thenReturn(listOf(record.copy(state = PaymentRequestLifecycleState.CANCELED)))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        checked.complete(Unit)

        assertTrue(authorization.await().isFailure)
        assertTrue(sut.ensurePaymentAllowed(request).isFailure)
        assertTrue(sut.accept(request).isFailure)
    }

    @Test
    fun `a paid subscription period stays blocked while its next unpaid period is authorized`() = test {
        val record = paymentRequestRecord(
            state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            endpoints = listOf(MethodId.P2wpkh.rawValue),
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val firstPeriod = sut.pendingRequests.value.single()
        sut.ensurePaymentAllowed(firstPeriod).getOrThrow()
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn requireNotNull(firstPeriod.billingPeriod).sdkValue
            on { paymentEndpointIdentifier } doReturn MethodId.P2wpkh.rawValue
            on { outboundStatus } doReturn OutboundPrivateMessageStatus.PENDING
        }
        val paidRecord = record.copy(paymentProofs = listOf(proof))
        whenever(paykitSdkService.claimPaymentRequestForExecution(COUNTERPARTY, PAYMENT_REQUEST_ID))
            .thenReturn(paidRecord)
        assertTrue(sut.subscriptions.value.single().paidPeriods.isEmpty())
        assertTrue(sut.claimForPayment(firstPeriod).exceptionOrNull() is PaykitPaymentRequestError.RequestUnavailable)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
            .thenReturn(listOf(paidRecord))
        subscriptionOffset = 31.days
        sut.refresh().getOrThrow()

        assertTrue(sut.ensurePaymentAllowed(firstPeriod).isFailure)
        val nextPeriod = sut.pendingRequests.value.single()
        assertEquals(Instant.parse("2027-02-01T08:00:00Z"), nextPeriod.billingPeriod?.startsAt)
        sut.claimForPayment(nextPeriod).getOrThrow()
        sut.ensurePaymentAllowed(nextPeriod).getOrThrow()
    }

    @Test
    fun `refresh keeps creator subscription without generating a payer payment`() = test {
        val metadataText = """
            {
              "note":"Creator plan",
              "subscription":{
                "version":1,
                "description":"Monthly support",
                "benefits":[],
                "icon_uri":"pubky://creator/icon"
              }
            }
        """.trimIndent()
        val metadata = mock<PrivateJsonObject> {
            on { exportText() } doReturn metadataText
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    role = PaymentRequestLocalRole.PAYEE,
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    metadata = metadata,
                ),
            ),
        )

        sut.refresh().getOrThrow()

        val subscription = sut.subscriptions.value.single()
        assertTrue(subscription.isCreatedByUser)
        assertEquals("Creator plan", subscription.note)
        assertEquals("Monthly support", subscription.metadata.description)
        assertEquals("pubky://creator/icon", subscription.metadata.iconUri)
        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
    }

    @Test
    fun `creator payments aggregate duplicate proof events for one billing period`() = test {
        val period = BillingPeriod("2027-01-01T08:00:00Z", "2027-02-01T08:00:00Z")
        val first = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn period
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        val second = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn period
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        val offSchedule = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(period.startsAt, "2027-02-02T08:00:00Z")
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        val subscription = requireNotNull(
            paymentRequestRecord(
                role = PaymentRequestLocalRole.PAYEE,
                state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                paymentProofs = listOf(first, second, offSchedule),
            ).toPaykitSubscription()
        )

        assertEquals(1, subscription.paidPeriods.size)
        val received = subscription.receivedPaymentRequests().single()
        assertEquals(PaykitPaymentRequestDirection.Outgoing, received.direction)
        assertEquals(PaymentRequestLifecycleState.PROOF_SUBMITTED, received.lifecycleState)
        assertEquals(PaykitPaymentProofKind.Lightning, received.paymentProofKind)
    }

    @Suppress("LongMethod")
    @Test
    fun `creator proposal sends recurring terms and stays queued until delivery`() = test {
        val target = stubSubscriptionProposal()
        val expiresAt = clock.now().plus(60.seconds)
        val unrelatedDelivery = CompletableDeferred<Unit>()
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer {
            unrelatedDelivery.await()
            emptyList()
        }

        val proposal = async {
            sut.proposeSubscription(
                draft = PaykitSubscriptionDraft(
                    amount = PaykitAmount(PaykitAsset.BTC, 100_000uL),
                    name = " Monthly support ",
                    description = " Thank you ",
                    frequency = PaykitRecurrenceUnit.Month,
                    expiresAt = expiresAt,
                ),
                target = target,
                savedPublicKeys = listOf(COUNTERPARTY),
            )
        }
        runCurrent()
        val completedBeforeUnrelatedDelivery = proposal.isCompleted
        unrelatedDelivery.complete(Unit)
        val creation = proposal.await().getOrThrow()
        assertTrue(completedBeforeUnrelatedDelivery)
        verify(paykitSdkService).processOutboundPrivateMessages(COUNTERPARTY)
        verify(paykitSdkService, never()).processPendingPrivateMessages()

        val captured = argumentCaptor<PaykitPaymentRequestProposalTerms>()
        verifyBlocking(paykitSdkService) {
            proposePaymentRequest(
                eq(COUNTERPARTY),
                captured.capture(),
                eq(LOCAL_IDENTITY),
            )
        }
        with(captured.firstValue) {
            assertEquals("0.001", amountValue)
            assertFalse(MethodId.UsdtArbitrum.rawValue in acceptedPaymentEndpointIdentifiers)
            assertTrue(acceptedPaymentEndpointIdentifiers.isNotEmpty())
            assertEquals(null, conversion)
            assertEquals(expiresAt.toString(), proposalExpiresAt)
            assertEquals(1u, recurrence?.every)
            assertEquals("month", recurrence?.unit)
            assertEquals(clock.now().toString(), recurrence?.startsAt)
            assertEquals(recurrence?.startsAt, recurrence?.anchor)
            assertEquals(null, recurrence?.endsAt)
            assertTrue(metadataJson.contains("\"note\":\"Monthly support\""))
            assertTrue(metadataJson.contains("\"description\":\"Thank you\""))
        }
        assertTrue(creation.subscription.isCreatedByUser)
        assertEquals(PaykitPaymentRequestDeliveryStatus.Queued, creation.subscription.deliveryStatus)
        assertEquals(listOf(creation.subscription), sut.subscriptions.value)
    }

    @Test
    fun `creator proposal reports delivery completed by another drain`() = test {
        val target = stubSubscriptionProposal()
        val record = paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).copy(proposalOutboundMessageId = 7uL)
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))).thenReturn(record)
        whenever(paykitSdkService.allPaymentRequests(LOCAL_IDENTITY)).thenReturn(
            listOf(record.copy(proposalOutboundStatus = OutboundPrivateMessageStatus.SENT)),
        )

        val creation = sut.proposeSubscription(
            draft = PaykitSubscriptionDraft(
                amount = PaykitAmount(PaykitAsset.BTC, 100_000uL),
                name = "Monthly support",
                description = "Thank you",
                frequency = PaykitRecurrenceUnit.Month,
                expiresAt = clock.now().plus(60.seconds),
            ),
            target = target,
            savedPublicKeys = listOf(COUNTERPARTY),
        ).getOrThrow()

        assertEquals(PaykitPaymentRequestDeliveryStatus.Sent, creation.subscription.deliveryStatus)
        assertEquals(listOf(creation.subscription), sut.subscriptions.value)
        verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY)
        verify(paykitSdkService).proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))
    }

    @Test
    fun `creator proposal publishes real time while the subscription clock offset is on`() = test {
        val target = stubSubscriptionProposal()
        subscriptionOffset = 31.days

        sut.proposeSubscription(
            draft = PaykitSubscriptionDraft(
                amount = PaykitAmount(PaykitAsset.BTC, 100_000uL),
                name = "Monthly support",
                description = "Thank you",
                frequency = PaykitRecurrenceUnit.Month,
                expiresAt = subscriptionClock.now().plus(60.seconds),
            ),
            target = target,
            savedPublicKeys = listOf(COUNTERPARTY),
        ).getOrThrow()

        val captured = argumentCaptor<PaykitPaymentRequestProposalTerms>()
        verifyBlocking(paykitSdkService) {
            proposePaymentRequest(any(), captured.capture(), any())
        }
        assertEquals(clock.now().toString(), captured.firstValue.recurrence?.startsAt)
        assertEquals(clock.now().toString(), captured.firstValue.recurrence?.anchor)
    }

    @Test
    fun `oversized creator proposal is rejected before icon upload or enqueue`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
            .thenReturn(true)

        listOf(null, byteArrayOf(0, 1, 2)).forEach { icon ->
            val result = sut.proposeSubscription(
                draft = PaykitSubscriptionDraft(
                    amount = PaykitAmount(PaykitAsset.BTC, 1000uL),
                    name = "Support",
                    description = "💜".repeat(256),
                    frequency = PaykitRecurrenceUnit.Month,
                    expiresAt = clock.now() + 60.seconds,
                    iconBytes = icon,
                ),
                target = target,
                savedPublicKeys = listOf(COUNTERPARTY),
            )
            assertEquals(PaykitPaymentRequestError.SubscriptionTooLong, result.exceptionOrNull())
        }

        verifyBlocking(paykitSdkService, never()) { uploadProfileAvatar(any(), any(), anyOrNull()) }
        verifyBlocking(paykitSdkService, never()) { proposePaymentRequest(any(), any(), any()) }
        assertTrue(sut.subscriptions.value.isEmpty())
        assertFalse(sut.isCreatingRequest.value)
    }

    @Test
    fun `creator can delete a pending proposal without payer proof checks`() = test {
        val proposed = paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE)
        val canceled = paymentRequestRecord(
            role = PaymentRequestLocalRole.PAYEE,
            state = PaymentRequestLifecycleState.CANCELED,
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposed), listOf(canceled))
        whenever(
            paykitSdkService.cancelPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(canceled)
        sut.refresh().getOrThrow()

        sut.cancel(sut.subscriptions.value.single()).getOrThrow()

        verifyBlocking(paymentProofRepo, never()) {
            protectedRequestIdsForSubscriptionCancellation(any(), any())
        }
        verifyBlocking(paykitSdkService) {
            cancelPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID)
        }
        assertEquals(PaymentRequestLifecycleState.CANCELED, sut.subscriptions.value.single().lifecycleState)
        assertFalse(sut.subscriptions.value.single().isCreatedVisible(clock.now()))
    }

    @Test
    fun `deleting a paid creator subscription keeps received history accessible`() = test {
        val period = BillingPeriod("2027-01-01T08:00:00Z", "2027-02-01T08:00:00Z")
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn period
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        val active = paymentRequestRecord(
            role = PaymentRequestLocalRole.PAYEE,
            state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            paymentProofs = listOf(proof),
        )
        val canceled = paymentRequestRecord(
            role = PaymentRequestLocalRole.PAYEE,
            state = PaymentRequestLifecycleState.CANCELED,
            paymentProofs = listOf(proof),
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(active), listOf(canceled))
        whenever(
            paykitSdkService.cancelPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID)
        ).thenReturn(canceled)
        sut.refresh().getOrThrow()
        val subscription = sut.subscriptions.value.single()
        val received = subscription.receivedPaymentRequests()
        assertEquals(1, received.size)

        sut.cancel(subscription).getOrThrow()
        sut.refresh().getOrThrow()

        val retained = sut.subscriptions.value.single()
        assertTrue(retained.isCreatedVisible(clock.now()))
        assertTrue(retained.isExpired(clock.now()))
        assertFalse(retained.isActive(clock.now()))
        assertFalse(retained.canCancel(clock.now()))
        assertFalse(retained.copy(role = PaykitSubscriptionRole.Payer).isCreatedVisible(clock.now()))
        assertEquals(received, retained.receivedPaymentRequests())
        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
    }

    @Test
    fun `refresh does not report subscription proposals as one time parse failures`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(1, sut.subscriptions.value.size)
        verify(diagnostics, never()).logParseRejection(any(), any())
    }

    @Test
    fun `refresh reports unsupported subscription recurrence`() = test {
        val unsupportedRecurrence = PaymentRequestRecurrence(
            every = 1u,
            unit = "fortnight",
            startsAt = "2027-01-01T08:00:00Z",
            anchor = "2027-01-01T08:00:00Z",
            endsAt = null,
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(recurrence = unsupportedRecurrence)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.subscriptions.value.isEmpty())
        verify(diagnostics).logParseRejection(
            COUNTERPARTY,
            PaykitPaymentRequest.ParseFailure.UnsupportedRecurrence,
        )
    }

    @Test
    fun `accepting subscription returns current period and preserves payment targets`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposal), listOf(active))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(active)
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
            .thenReturn(true)
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
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
    fun `subscription clock change refreshes periods after an overlapping refresh`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposal), listOf(active))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(active)
        sut.refresh().getOrThrow()
        sut.accept(sut.subscriptions.value.single()).getOrThrow()
        assertEquals(
            listOf(Instant.parse("2027-01-01T08:00:00Z")),
            sut.pendingRequests.value.mapNotNull { it.billingPeriod?.startsAt },
        )

        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            reading.complete(Unit)
            resume.await()
            listOf(active)
        }
        val first = async { sut.refresh() }
        reading.await()
        subscriptionOffset = 31.days
        val afterClockChange = async { sut.refreshAfterStateChange() }
        runCurrent()
        resume.complete(Unit)
        first.await().getOrThrow()
        afterClockChange.await().getOrThrow()

        assertEquals(
            listOf(Instant.parse("2027-01-01T08:00:00Z"), Instant.parse("2027-02-01T08:00:00Z")),
            sut.pendingRequests.value.mapNotNull { it.billingPeriod?.startsAt }.sorted(),
        )
    }

    @Test
    fun `accepting with the subscription clock offset on keeps the first period due`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposal), listOf(active))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(active)
        sut.refresh().getOrThrow()
        val subscription = sut.subscriptions.value.single()

        subscriptionOffset = 31.days
        sut.accept(subscription).getOrThrow()

        assertEquals(clock.now(), sut.acceptedAt(sut.subscriptions.value.single()))
        assertEquals(
            listOf(Instant.parse("2027-01-01T08:00:00Z"), Instant.parse("2027-02-01T08:00:00Z")),
            sut.pendingRequests.value.mapNotNull { it.billingPeriod?.startsAt }.sorted(),
        )

        subscriptionOffset = Duration.ZERO
        sut.refresh().getOrThrow()

        assertEquals(
            listOf(Instant.parse("2027-01-01T08:00:00Z")),
            sut.pendingRequests.value.mapNotNull { it.billingPeriod?.startsAt },
        )
    }

    @Test
    fun `subscription clock offset lists the paid next period in the payment history`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposal), listOf(active))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(active)
        sut.refresh().getOrThrow()
        sut.accept(sut.subscriptions.value.single()).getOrThrow()
        val completedProofKinds = listOf("2027-01-01T08:00:00Z", "2027-02-01T08:00:00Z").associate {
            PaykitPaymentRequestId(
                paymentRequestId = PAYMENT_REQUEST_ID,
                counterparty = COUNTERPARTY,
                billingPeriodStartsAt = it,
            ) to PaykitPaymentProofKind.Onchain
        }
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY))
            .thenReturn(completedProofKinds)

        subscriptionOffset = 31.days
        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(
            listOf(Instant.parse("2027-01-01T08:00:00Z"), Instant.parse("2027-02-01T08:00:00Z")),
            sut.paymentRequestHistory.value.mapNotNull { it.billingPeriod?.startsAt }.sorted(),
        )
    }

    @Test
    fun `accepting subscription rejects terms changed after review`() = test {
        val reviewedRecord = paymentRequestRecord()
        val changedRecord = paymentRequestRecord(amount = "0.002")
        whenever(
            paykitSdkService.allPaymentRequests(anyOrNull())
        ).thenReturn(listOf(reviewedRecord), listOf(changedRecord))
        sut.refresh().getOrThrow()
        val reviewedSubscription = sut.subscriptions.value.single()
        sut.refresh().getOrThrow()

        val result = sut.accept(reviewedSubscription)

        assertTrue(result.exceptionOrNull() is PaykitPaymentRequestError.RequestUnavailable)
        verifyBlocking(paykitSdkService, never()) { acceptPaymentRequest(any(), any()) }
    }

    @Test
    fun `accepted subscription stays successful when its immediate refresh fails`() = test {
        val proposal = paymentRequestRecord()
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
            .thenReturn(listOf(proposal))
            .thenThrow(IllegalStateException("refresh failed"))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
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
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()

        assertTrue(sut.dismissSubscriptionPayment(request))
        assertTrue(sut.pendingRequests.value.isEmpty())

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.isSubscriptionNotificationHandled(request.id, LOCAL_IDENTITY))
        verifyBlocking(presentationStore) {
            saveSubscriptionState(eq(LOCAL_IDENTITY), argThat { dismissedPaymentIds == setOf(request.id) })
        }
    }

    @Test
    fun `failed dismissal persistence keeps subscription payment in queue`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        whenever(
            presentationStore.saveSubscriptionState(
                eq(LOCAL_IDENTITY),
                argThat { dismissedPaymentIds == setOf(request.id) },
            )
        ).thenThrow(IllegalStateException("persistence failed"))

        val dismissed = sut.dismissSubscriptionPayment(request)

        assertFalse(dismissed)
        assertEquals(listOf(request), sut.pendingRequests.value)
    }

    @Test
    fun `identity switch prevents refresh state from persisting under previous identity`() = test {
        val refreshStarted = CompletableDeferred<Unit>()
        val resumeRefresh = CompletableDeferred<Unit>()
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer {
            refreshStarted.complete(Unit)
            resumeRefresh.await()
            emptyList()
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )
        whenever(presentationStore.load(SECOND_IDENTITY)).thenReturn(emptySet())

        val refresh = async { sut.refresh() }
        runCurrent()
        refreshStarted.await()
        val activation = async { sut.activate(SECOND_IDENTITY) }
        runCurrent()
        resumeRefresh.complete(Unit)
        refresh.await().getOrThrow()
        activation.await()

        verifyBlocking(presentationStore, never()) {
            saveSubscriptionState(eq(LOCAL_IDENTITY), argThat { acceptedAt.isNotEmpty() })
        }
    }

    @Test
    fun `completed subscription payment awaiting proof submission is not offered again`() = test {
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            billingPeriodStartsAt = "2027-01-01T08:00:00Z",
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        assertEquals(requestId, sut.pendingRequests.value.single().id)
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY))
            .thenReturn(mapOf(requestId to PaykitPaymentProofKind.Onchain))

        sut.refreshAfterStateChange(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.automaticPendingRequests().isEmpty())
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        assertEquals(requestId, sut.paymentRequestHistory.value.single().id)
        assertEquals(
            PaymentRequestLifecycleState.PROOF_SUBMITTED,
            sut.paymentRequestHistory.value.single().lifecycleState,
        )
        assertEquals(PaykitPaymentProofKind.Onchain, sut.paymentRequestHistory.value.single().paymentProofKind)
        assertTrue(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))
        val nextPeriodId = requestId.copy(billingPeriodStartsAt = "2027-02-01T08:00:00Z")
        assertFalse(sut.isSubscriptionNotificationHandled(nextPeriodId, LOCAL_IDENTITY))
        assertTrue(sut.pendingRequests.value.none { it.id == nextPeriodId })
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
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
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
    fun `blocking a canceled subscription hides it while its paid period runs`() = test {
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(
                startsAt = "2027-01-01T08:00:00Z",
                endsAt = "2027-02-01T08:00:00Z",
            )
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    state = PaymentRequestLifecycleState.CANCELED,
                    paymentProofs = listOf(proof),
                ),
            ),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.subscriptions.value.isEmpty())
    }

    @Test
    fun `blocking a paid payer subscription keeps payment history`() = test {
        advanceTimeBy(20 * 24 * 60 * 60 * 1000L)
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(
                startsAt = "2027-01-01T08:00:00Z",
                endsAt = "2027-02-01T08:00:00Z",
            )
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    state = PaymentRequestLifecycleState.CANCELED,
                    paymentProofs = listOf(proof),
                ),
            ),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        val retained = sut.subscriptions.value.single()
        assertTrue(retained.isExpired(clock.now()))
        assertFalse(retained.canCancel(clock.now()))
        val paid = sut.paymentRequestHistory.value.single()
        assertEquals(PaymentRequestLifecycleState.PROOF_SUBMITTED, paid.lifecycleState)
        assertEquals(PaykitPaymentProofKind.Lightning, paid.paymentProofKind)
    }

    @Test
    fun `blocking a paid creator subscription keeps received history accessible`() = test {
        advanceTimeBy(20 * 24 * 60 * 60 * 1000L)
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(
                startsAt = "2027-01-01T08:00:00Z",
                endsAt = "2027-02-01T08:00:00Z",
            )
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    role = PaymentRequestLocalRole.PAYEE,
                    state = PaymentRequestLifecycleState.CANCELED,
                    paymentProofs = listOf(proof),
                ),
            ),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
        val retained = sut.subscriptions.value.single()
        assertTrue(retained.isExpired(clock.now()))
        assertFalse(retained.canCancel(clock.now()))
        assertEquals(1, retained.receivedPaymentRequests().size)
    }

    @Test
    fun `blocking ended paid subscription does not offer its unpaid period`() = test {
        advanceTimeBy(46 * 24 * 60 * 60 * 1000L)
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(
                startsAt = "2027-01-01T08:00:00Z",
                endsAt = "2027-02-01T08:00:00Z",
            )
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    recurrence = recurrence.copy(endsAt = "2027-03-01T08:00:00Z"),
                    paymentProofs = listOf(proof),
                ),
            ),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        val retained = sut.subscriptions.value.single()
        assertTrue(retained.isExpired(clock.now()))
        assertFalse(retained.canCancel(clock.now()))
        assertEquals(1, sut.paymentRequestHistory.value.size)
    }

    @Test
    fun `recurring payment deadlines retain paid history and bound unpaid amounts`() = test {
        advanceTimeBy(32 * 24 * 60 * 60 * 1000L)
        val proof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod("2027-01-01T08:00:00Z", "2027-02-01T08:00:00Z")
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
        }
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(PaymentRequestLocalRole.PAYER, PaymentRequestLocalRole.PAYEE).map { role ->
                paymentRequestRecord(
                    id = role.name,
                    role = role,
                    state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
                    paymentDeadline = PaymentDeadline.PeriodStart(3600uL),
                    paymentProofs = listOf(proof),
                )
            },
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.all { !it.payment(PaykitAsset.BTC, clock.now()).isValid(clock.now()) })
        assertEquals(2, sut.subscriptions.value.size)
        sut.subscriptions.value.forEach { subscription ->
            assertEquals(1, subscription.paidPeriods.size)
            assertTrue(subscription.canCancel(clock.now()))
        }
        val paid = sut.paymentRequestHistory.value.single()
        assertEquals(PaymentRequestLifecycleState.PROOF_SUBMITTED, paid.lifecycleState)
        assertEquals(PaykitPaymentProofKind.Lightning, paid.paymentProofKind)
        assertEquals(1, sut.subscriptions.value.single { it.isCreatedByUser }.receivedPaymentRequests().size)
    }

    @Test
    fun `in flight subscription reminder remains unhandled until the same period returns`() = test {
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            billingPeriodStartsAt = "2027-01-01T08:00:00Z",
        )
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(setOf(requestId))
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.paymentRequestHistory.value.isEmpty())
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))

        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(emptySet())
        sut.refreshAfterStateChange(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

        assertEquals(requestId, sut.pendingRequests.value.single().id)
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))
    }

    @Test
    fun `subscription reminder clears for missing or inactive subscriptions`() = test {
        val requestId = PaykitPaymentRequestId(PAYMENT_REQUEST_ID, COUNTERPARTY, "2027-01-01T08:00:00Z")
        for (state in listOf(null, PaymentRequestLifecycleState.CANCELED, PaymentRequestLifecycleState.REJECTED)) {
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
                state?.let { listOf(paymentRequestRecord(state = it)) }.orEmpty(),
            )

            sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

            assertTrue(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY), state.toString())
        }
    }

    @Test
    fun `subscription reminder requires a successful snapshot for the current identity`() = test {
        val requestId = PaykitPaymentRequestId(PAYMENT_REQUEST_ID, COUNTERPARTY, "2027-01-01T08:00:00Z")
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        assertTrue(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, SECOND_IDENTITY))

        whenever(settingsStore.data).thenReturn(flow { throw IllegalStateException("refresh failed") })
        assertTrue(sut.refresh(PaykitPaymentRequestRefreshMode.STORED).isFailure)
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))

        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(sharesPrivatePaykitEndpoints = true)))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        val refreshStarted = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            refreshStarted.complete(Unit)
            awaitCancellation()
        }
        val refresh = async { sut.refresh(PaykitPaymentRequestRefreshMode.STORED) }
        refreshStarted.await()
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))
        refresh.cancel()
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))

        doReturn(emptyList<PaymentRequestRecord>()).whenever(paykitSdkService).allPaymentRequests(anyOrNull())
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        whenever(settingsStore.isPaykitEnabled).thenReturn(flowOf(false))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))

        whenever(settingsStore.isPaykitEnabled).thenReturn(flowOf(true))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        sut.clear()
        sut.activate(LOCAL_IDENTITY)
        assertFalse(sut.isSubscriptionNotificationHandled(requestId, LOCAL_IDENTITY))
    }

    @Test
    fun `subscription cannot be canceled after payment has started`() = test {
        val requestId = PaykitPaymentRequestId(
            paymentRequestId = PAYMENT_REQUEST_ID,
            counterparty = COUNTERPARTY,
            billingPeriodStartsAt = "2027-01-01T08:00:00Z",
        )
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        whenever(paymentProofRepo.protectedRequestIdsForSubscriptionCancellation(eq(LOCAL_IDENTITY), any()))
            .thenReturn(Result.success(setOf(requestId)))
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(active))
        sut.refresh().getOrThrow()

        val result = sut.cancel(sut.subscriptions.value.single())

        assertTrue(result.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        assertEquals(1, sut.subscriptions.value.size)
        verifyBlocking(paykitSdkService, never()) { cancelPaymentRequest(any(), any(), anyOrNull()) }
    }

    @Test
    fun `subscription cannot be canceled while USDT execution is unresolved`() = test {
        val requestId = PaykitPaymentRequestId(PAYMENT_REQUEST_ID, COUNTERPARTY, "2027-01-01T08:00:00Z")
        whenever(usdtPayments.protectedRequestIdsForSubscriptionCancellation(eq(LOCAL_IDENTITY), any()))
            .thenReturn(Result.success(setOf(requestId)))
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING))
        )
        sut.refresh().getOrThrow()

        val result = sut.cancel(sut.subscriptions.value.single())

        assertTrue(result.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        verifyBlocking(paykitSdkService, never()) { cancelPaymentRequest(any(), any(), anyOrNull()) }
    }

    @Test
    fun `an in-flight cancellation prevents authorizing a newly started payment`() = test {
        val active = paymentRequestRecord(state = PaymentRequestLifecycleState.ACTIVE_RECURRING)
        val canceled = active.copy(state = PaymentRequestLifecycleState.CANCELED)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(active))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        sut.ensurePaymentAllowed(request).getOrThrow()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        whenever(paykitSdkService.cancelPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID)).doSuspendableAnswer {
            started.complete(Unit)
            finish.await()
            canceled
        }
        val cancellation = async { sut.cancel(sut.subscriptions.value.single()) }
        started.await()

        assertTrue(sut.ensurePaymentAllowed(request).isFailure)

        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(canceled))
        finish.complete(Unit)
        cancellation.await().getOrThrow()
        assertTrue(sut.ensurePaymentAllowed(request).isFailure)
    }

    @Test
    fun `subscription cancellation proceeds without a started payment`() = test {
        val active = paymentRequestRecord(
            state = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            paymentDeadline = PaymentDeadline.PeriodStart(3600uL),
        )
        val canceled = paymentRequestRecord(
            state = PaymentRequestLifecycleState.CANCELED,
            paymentDeadline = PaymentDeadline.PeriodStart(3600uL),
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(active), emptyList())
        whenever(
            paykitSdkService.cancelPaymentRequest(
                COUNTERPARTY,
                PAYMENT_REQUEST_ID,
            )
        ).thenReturn(canceled)
        sut.refresh().getOrThrow()

        sut.cancel(sut.subscriptions.value.single()).getOrThrow()

        verifyBlocking(paykitSdkService) {
            cancelPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID)
        }
    }

    @Test
    fun `malformed expiry is rejected and unsupported payment details disable acceptance`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
            listOf(
                paymentRequestRecord(id = "malformed", expiresAt = "not-a-timestamp"),
                paymentRequestRecord(id = "deadline", paymentDeadline = PaymentDeadline.PeriodStart(3600uL)),
                paymentRequestRecord(id = "unsupported", endpoints = listOf("btc-unsupported-method")),
            ),
        )

        sut.refresh().getOrThrow()

        val subscriptions = sut.subscriptions.value
        assertEquals(setOf("deadline", "unsupported"), subscriptions.map { it.paymentRequestId }.toSet())
        assertEquals(
            listOf("deadline"),
            subscriptions.filter {
                it.isProposalActionable(clock.now())
            }.map { it.paymentRequestId }
        )
        assertEquals(subscriptions, sut.subscriptionProposals())
        val deadlineSubscription = subscriptions.first { it.paymentRequestId == "deadline" }
        assertTrue(deadlineSubscription.paymentDueOnAcceptance(clock.now()) != null)
    }

    @Test
    fun `presented subscription stays available without auto presenting after reactivation`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
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
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
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
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
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
        val subscriptionId = PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY)
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
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(
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

    private suspend fun stubSubscriptionProposal(): PaykitPaymentRequestTarget {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        whenever(paykitSdkService.identityStatus()).thenReturn(
            IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(true)
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY)))
            .thenAnswer { invocation ->
                val proposal = invocation.getArgument<PaykitPaymentRequestProposalTerms>(1)
                paymentRequestRecord(
                    role = PaymentRequestLocalRole.PAYEE,
                    expiresAt = proposal.proposalExpiresAt,
                    recurrence = requireNotNull(proposal.recurrence).let {
                        PaymentRequestRecurrence(it.every, it.unit, it.startsAt, it.anchor, it.endsAt)
                    },
                    metadata = mock {
                        on { exportText() } doReturn proposal.metadataJson
                    },
                )
            }
        return target
    }

    @Suppress("LongParameterList")
    private fun paymentRequestRecord(
        id: String = PAYMENT_REQUEST_ID,
        role: PaymentRequestLocalRole = PaymentRequestLocalRole.PAYER,
        state: PaymentRequestLifecycleState = PaymentRequestLifecycleState.PROPOSED,
        amount: String = "0.001",
        expiresAt: String? = null,
        paymentDeadline: PaymentDeadline? = null,
        endpoints: List<String> = listOf(MethodId.Bolt11.rawValue),
        metadata: PrivateJsonObject = METADATA,
        recurrence: PaymentRequestRecurrence = this.recurrence,
        paymentProofs: List<PaymentProofRecord> = emptyList(),
    ) = PaymentRequestRecord(
        counterparty = COUNTERPARTY,
        paymentRequestId = id,
        localRole = role,
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

    private val recurrence = PaymentRequestRecurrence(
        every = 1u,
        unit = "month",
        startsAt = "2027-01-01T08:00:00Z",
        anchor = "2027-01-01T08:00:00Z",
        endsAt = null,
    )
}
