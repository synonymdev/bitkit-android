@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.ValidationResult
import com.synonym.bitkitcore.validateBitcoinAddress
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateMessageStatus
import com.synonym.paykit.OutboundPrivateSendFailure
import com.synonym.paykit.OutboundPrivateSendReport
import com.synonym.paykit.PaykitException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.mockito.kotlin.doAnswer
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
import to.bitkit.services.PaykitReadLane
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
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
    private val proofStateVersion = MutableStateFlow(0L)
    private val paymentSubmissionActive = MutableStateFlow(false)
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
        whenever(paykitSdkService.isPaymentSubmissionActive).thenReturn(paymentSubmissionActive)
        whenever(paykitSdkService.setPaymentSubmissionActive(any())).thenAnswer {
            paymentSubmissionActive.value = it.getArgument(0)
            Unit
        }
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
        whenever(presentationStore.loadAcceptedOneTimeIds(any())).thenReturn(emptySet())
        whenever(presentationStore.addAcceptedOneTimeId(any(), any())).thenAnswer {
            setOf(it.getArgument<PaykitPaymentRequestId>(1))
        }
        whenever(presentationStore.removeAcceptedOneTimeIds(any(), any())).thenReturn(emptySet())
        whenever(
            presentationStore.loadSubscriptionState(any())
        ).thenReturn(PaykitSubscriptionPresentationState())
        whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY)).thenReturn(emptyMap())
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(emptySet())
        whenever(paymentProofStore.backupStateVersion).thenReturn(proofStateVersion)
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
    fun `refresh modes control message intake and outbound maintenance`() = test {
        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))

        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

        assertEquals(record.paymentRequestId, sut.pendingRequests.value.single().paymentRequestId)
        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()

        sut.refresh(PaykitPaymentRequestRefreshMode.INBOX).getOrThrow()

        verify(paykitSdkService, never()).processPendingPrivateMessages()
        verify(paykitSdkService).receivePrivateMessagesFromLinkedPeers()

        sut.refresh().getOrThrow()

        verify(paykitSdkService).processPendingPrivateMessages()
        verify(paykitSdkService, times(2)).receivePrivateMessagesFromLinkedPeers()
        verify(paykitSdkService, times(3)).allPaymentRequests(LOCAL_IDENTITY, Priority.Background)
        verify(paykitSdkService, times(3)).linkedPeers(Priority.Background)
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Ordered)
        verify(paykitSdkService, times(2)).receivePrivateMessagesFromLinkedPeers(Priority.Ordered)
    }

    @Test
    fun `passive refresh message priority does not change action or forced refresh drains`() = test {
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED, Priority.Background).getOrThrow()
        verify(paykitSdkService, never()).processPendingPrivateMessages(any())
        verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers(any())

        sut.refresh(PaykitPaymentRequestRefreshMode.INBOX, Priority.Background).getOrThrow()
        verify(paykitSdkService, never()).processPendingPrivateMessages(any())
        verify(paykitSdkService).receivePrivateMessagesFromLinkedPeers(Priority.Background)

        sut.refresh(PaykitPaymentRequestRefreshMode.FULL, Priority.Background).getOrThrow()
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Background)
        verify(paykitSdkService, times(2)).receivePrivateMessagesFromLinkedPeers(Priority.Background)

        clearInvocations(paykitSdkService)
        sut.refreshAfterStateChange().getOrThrow()
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Ordered)
        verify(paykitSdkService).receivePrivateMessagesFromLinkedPeers(Priority.Ordered)
        verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Ordered)
        verify(paykitSdkService, never()).processPendingPrivateMessages(Priority.Background)

        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        whenever(paykitSdkService.rejectPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID))
            .thenReturn(record.copy(state = PaymentRequestLifecycleState.REJECTED))
        clearInvocations(paykitSdkService)
        sut.reject(sut.pendingRequests.value.single()).getOrThrow()
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Ordered)
        verify(paykitSdkService, never()).processPendingPrivateMessages(Priority.Background)
    }

    @Test
    fun `refresh refetches after a committed uncertain or cancelled action`() = test {
        val failures = listOf(
            null,
            PaykitException.Storage("write_uncertain", "Request may be rejected"),
            CancellationException("Action cancelled after commit"),
        )
        for (failure in failures) {
            val record = paymentRequestRecord()
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
            sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
            val request = sut.pendingRequests.value.single()
            val reading = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var reads = 0
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
                if (reads++ == 0) {
                    reading.complete(Unit)
                    resume.await()
                    listOf(record)
                } else {
                    listOf(record.copy(state = PaymentRequestLifecycleState.REJECTED))
                }
            }
            doAnswer {
                failure?.let { throw it }
                record.copy(state = PaymentRequestLifecycleState.REJECTED)
            }.whenever(paykitSdkService).rejectPaymentRequest(COUNTERPARTY, PAYMENT_REQUEST_ID)
            clearInvocations(paykitSdkService)
            val refresh = async { sut.refresh(PaykitPaymentRequestRefreshMode.FULL) }
            reading.await()

            if (failure is CancellationException) {
                assertFailsWith<CancellationException> { sut.reject(request) }
            } else {
                assertEquals(failure != null, sut.reject(request).isFailure)
            }
            resume.complete(Unit)
            refresh.await().getOrThrow()

            assertTrue(sut.pendingRequests.value.isEmpty())
            assertEquals(PaymentRequestLifecycleState.REJECTED, sut.paymentRequestHistory.value.single().lifecycleState)
            assertEquals(2, reads)
            verify(paykitSdkService, times(if (failure == null) 2 else 1)).processPendingPrivateMessages()
            verify(paykitSdkService).receivePrivateMessagesFromLinkedPeers()
            verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Background)
            verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Ordered)
        }
    }

    @Test
    fun `refresh applies expiration using current time after request fetch`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(1.seconds).toString())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            reading.complete(Unit)
            resume.await()
            listOf(record)
        }
        val refresh = async { sut.refresh(PaykitPaymentRequestRefreshMode.STORED) }
        reading.await()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(sut.pendingRequests.value.isEmpty())
        resume.complete(Unit)
        refresh.await().getOrThrow()
        assertTrue(sut.pendingRequests.value.isEmpty())
    }

    @Test
    fun `overlapping refreshes reuse a completed full refresh`() = test {
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            reading.complete(Unit)
            resume.await()
            emptyList()
        }
        val first = async { sut.refresh(PaykitPaymentRequestRefreshMode.FULL, Priority.Background) }
        reading.await()
        val queued = PaykitPaymentRequestRefreshMode.entries.map { mode -> async { sut.refresh(mode) } }
        runCurrent()
        resume.complete(Unit)

        first.await().getOrThrow()
        queued.forEach { it.await().getOrThrow() }
        verify(paykitSdkService).allPaymentRequests(anyOrNull())
        verify(paykitSdkService).processPendingPrivateMessages()
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Background)
        verify(paykitSdkService, never()).processPendingPrivateMessages(Priority.Ordered)

        sut.refresh().getOrThrow()
        verify(paykitSdkService, times(2)).allPaymentRequests(anyOrNull())
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Ordered)
    }

    @Test
    fun `full refresh still runs after an overlapping inbox refresh`() = test {
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            reading.complete(Unit)
            resume.await()
            emptyList()
        }
        val inbox = async { sut.refresh(PaykitPaymentRequestRefreshMode.INBOX) }
        reading.await()
        val full = async { sut.refresh() }
        runCurrent()
        resume.complete(Unit)

        inbox.await().getOrThrow()
        full.await().getOrThrow()
        verify(paykitSdkService, times(2)).allPaymentRequests(anyOrNull())
        verify(paykitSdkService).processPendingPrivateMessages()
    }

    @Test
    fun `queued refresh retries a failed refresh`() = test {
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var attempts = 0
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            if (attempts++ == 0) {
                reading.complete(Unit)
                resume.await()
                throw PaykitPaymentRequestError.RequestUnavailable
            }
            emptyList()
        }
        val failed = async { sut.refresh() }
        reading.await()
        val retry = async { sut.refresh() }
        runCurrent()
        resume.complete(Unit)

        assertTrue(failed.await().isFailure)
        retry.await().getOrThrow()
        verify(paykitSdkService, times(2)).allPaymentRequests(anyOrNull())
    }

    @Test
    fun `overlapping refresh rereads proof state changed after the first snapshot`() = test {
        val record = paymentRequestRecord()
        val requestId = PaykitPaymentRequestId(record.paymentRequestId, record.counterparty)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(setOf(requestId))
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(settingsStore.data).thenReturn(
            flow {
                reading.complete(Unit)
                resume.await()
                emit(SettingsData(sharesPrivatePaykitEndpoints = true))
            },
        )
        val first = async { sut.refresh() }
        reading.await()
        assertTrue(sut.pendingRequests.value.isEmpty())

        whenever(paymentProofStore.inFlightRequestIds(LOCAL_IDENTITY)).thenReturn(emptySet())
        proofStateVersion.value += 1
        val afterFailure = async { sut.refreshAfterStateChange(PaykitPaymentRequestRefreshMode.STORED) }
        runCurrent()
        resume.complete(Unit)
        first.await().getOrThrow()
        afterFailure.await().getOrThrow()

        assertEquals(requestId, sut.pendingRequests.value.single().id)
        verify(paykitSdkService, times(2)).allPaymentRequests(anyOrNull())
        verify(paykitSdkService).processPendingPrivateMessages()
        verify(paykitSdkService).receivePrivateMessagesFromLinkedPeers()
    }

    @Test
    fun `refresh rereads proof changes before applying and rejects a changing reload`() = test {
        val record = paymentRequestRecord()
        val requestId = PaykitPaymentRequestId(record.paymentRequestId, record.counterparty)
        val submitted = record.copy(state = PaymentRequestLifecycleState.PROOF_SUBMITTED)
        for (changeDuringReload in listOf(false, true)) {
            val reading = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var reads = 0
            whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY))
                .thenReturn(mapOf(requestId to PaykitPaymentProofKind.Lightning))
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
                if (reads++ == 0) {
                    reading.complete(Unit)
                    resume.await()
                    listOf(record)
                } else {
                    if (changeDuringReload) proofStateVersion.value += 1
                    listOf(submitted)
                }
            }
            clearInvocations(paykitSdkService)
            val refresh = async { sut.refresh(PaykitPaymentRequestRefreshMode.STORED) }
            reading.await()
            whenever(paymentProofStore.completedRequestProofKindsAwaitingSubmission(LOCAL_IDENTITY))
                .thenReturn(emptyMap())
            proofStateVersion.value += 1
            resume.complete(Unit)

            val result = refresh.await()

            assertEquals(changeDuringReload, result.isFailure)
            assertTrue(sut.pendingRequests.value.isEmpty())
            if (!changeDuringReload) {
                assertEquals(
                    PaymentRequestLifecycleState.PROOF_SUBMITTED,
                    sut.paymentRequestHistory.value.single().lifecycleState,
                )
            }
            verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Background)
            verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Ordered)
            verify(paykitSdkService, never()).processPendingPrivateMessages()
            verify(paykitSdkService, never()).receivePrivateMessagesFromLinkedPeers()
        }
    }

    @Test
    fun `state change refresh rereads a peer blocked after the first snapshot`() = test {
        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(settingsStore.data).thenReturn(
            flow {
                reading.complete(Unit)
                resume.await()
                emit(SettingsData(sharesPrivatePaykitEndpoints = true))
            },
        )
        val first = async { sut.refresh() }
        reading.await()
        assertEquals(record.paymentRequestId, sut.pendingRequests.value.single().paymentRequestId)

        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.BLOCKED)),
        )
        val afterBlock = async { sut.refreshAfterStateChange() }
        runCurrent()
        resume.complete(Unit)
        first.await().getOrThrow()
        afterBlock.await().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        verify(paykitSdkService, times(2)).linkedPeers()
        verify(paykitSdkService).linkedPeers(Priority.Background)
        verify(paykitSdkService).linkedPeers(Priority.Ordered)
        verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Background)
        verify(paykitSdkService).allPaymentRequests(LOCAL_IDENTITY, Priority.Ordered)
    }

    @Test
    fun `state change refresh rereads a request drained after the first snapshot`() = test {
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(settingsStore.data).thenReturn(
            flow {
                reading.complete(Unit)
                resume.await()
                emit(SettingsData(sharesPrivatePaykitEndpoints = true))
            },
        )
        val first = async { sut.refresh() }
        reading.await()
        assertTrue(sut.pendingRequests.value.isEmpty())

        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        val afterDrain = async { sut.refreshAfterStateChange() }
        runCurrent()
        resume.complete(Unit)
        first.await().getOrThrow()
        afterDrain.await().getOrThrow()

        assertEquals(record.paymentRequestId, sut.pendingRequests.value.single().paymentRequestId)
        verify(paykitSdkService, times(2)).allPaymentRequests(anyOrNull())
    }

    @Test
    fun `shared app destinations survive refresh failure but clear on identity switch`() = test {
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

        val contacts = sut.receivedPaymentContacts
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers()).doSuspendableAnswer {
            reading.complete(Unit)
            resume.await()
            throw PaykitPaymentRequestError.RequestUnavailable
        }
        val refresh = async { sut.refresh(PaykitPaymentRequestRefreshMode.INBOX) }
        reading.await()
        assertSame(contacts, sut.receivedPaymentContacts)
        resume.complete(Unit)
        assertTrue(refresh.await().isFailure)
        assertSame(contacts, sut.receivedPaymentContacts)

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
        restoreAcceptedRequest()
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
    fun `accepted request checks blocking during synchronization`() = test {
        restoreAcceptedRequest()
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
        blocked = true
        val acceptance = async { sut.accept(request) }
        runCurrent()
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
    fun `absolute payment deadlines preserve fractional seconds and include the deadline instant`() {
        val deadline = START_TIME + 123.milliseconds
        val record = paymentRequestRecord(paymentDeadline = PaymentDeadline.At(deadline.toString()))

        listOf(deadline - 1.nanoseconds, deadline).forEach { now ->
            val parsed = record.parseIncomingPaykitPaymentRequest(now) as PaykitPaymentRequestParseResult.Parsed
            assertEquals(deadline, parsed.request.paymentDeadlineAt)
            assertFalse(parsed.request.isExpired(now))
        }
        val expired = record.parseIncomingPaykitPaymentRequest(deadline + 1.nanoseconds)
            as PaykitPaymentRequestParseResult.Rejected
        assertEquals(PaykitPaymentRequest.ParseFailure.Expired, expired.reason)
    }

    @Test
    fun `malformed and recurring payment deadlines remain non actionable`() {
        val deadlines = listOf(
            PaymentDeadline.At("not-a-timestamp"),
            PaymentDeadline.At("2027-01-15T09:00:00+01:00"),
            PaymentDeadline.PeriodStart(3600uL),
        )

        deadlines.forEach { deadline ->
            val result = paymentRequestRecord(paymentDeadline = deadline).parseIncomingPaykitPaymentRequest(START_TIME)
                as PaykitPaymentRequestParseResult.Rejected
            assertEquals(PaykitPaymentRequest.ParseFailure.UnsupportedPaymentDeadline, result.reason)
        }
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
            paymentRequestRecord(paymentDeadline = PaymentDeadline.PeriodStart(3600uL)) to
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
                    paymentDeadline = PaymentDeadline.At((clock.now() - 1.seconds).toString()),
                )
            },
        )

        sut.refresh().getOrThrow()

        assertEquals(listOf("incoming"), sut.pendingRequests.value.map { it.paymentRequestId })
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
    fun `accept commits locally without waiting for peer delivery during payment`() = test {
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
        sut.setPaymentSubmissionActive(true)

        sut.accept(sut.pendingRequests.value.single()).getOrThrow()
        runCurrent()

        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(PaymentRequestLifecycleState.ACCEPTED, sut.paymentRequestHistory.value.single().lifecycleState)
        verify(paykitSdkService, never()).processPendingPrivateMessages(any())
        verify(paykitSdkService, never()).processOutboundPrivateMessages(any(), any(), anyOrNull())
        verify(presentationStore).addAcceptedOneTimeId(
            LOCAL_IDENTITY,
            PaykitPaymentRequestId(PAYMENT_REQUEST_ID, COUNTERPARTY),
        )
        whenever(paykitSdkService.processOutboundPrivateMessages(COUNTERPARTY, Priority.Background, LOCAL_IDENTITY))
            .doSuspendableAnswer { throw PaykitException.Transport("transport_error", "Offline") }
        sut.setPaymentSubmissionActive(false)
        runCurrent()
        verify(paykitSdkService).processOutboundPrivateMessages(COUNTERPARTY, Priority.Background, LOCAL_IDENTITY)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
            .thenReturn(listOf(record.copy(state = PaymentRequestLifecycleState.ACCEPTED)))
        sut.refresh(PaykitPaymentRequestRefreshMode.FULL, Priority.Background).getOrThrow()
        verify(paykitSdkService).processPendingPrivateMessages(Priority.Background)
        verify(paykitSdkService, times(1)).acceptPaymentRequest(any(), any())
    }

    @Test
    fun `deferred acceptance delivery is discarded when identity changes`() = test {
        val record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        whenever(paykitSdkService.acceptPaymentRequest(any(), any())).thenReturn(record)
        sut.refresh().getOrThrow()
        sut.setPaymentSubmissionActive(true)
        sut.accept(sut.pendingRequests.value.single()).getOrThrow()
        runCurrent()

        sut.activate(SECOND_IDENTITY)
        runCurrent()

        verify(paykitSdkService, never()).processOutboundPrivateMessages(any(), any(), anyOrNull())
        assertFalse(paymentSubmissionActive.value)
    }

    @Test
    fun `local acceptance survives refresh and reconnect without accepting twice`() = test {
        val proposed = paymentRequestRecord()
        val accepted = proposed.copy(state = PaymentRequestLifecycleState.ACCEPTED)
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposed))
        whenever(paykitSdkService.acceptPaymentRequest(any(), any())).thenReturn(accepted)
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        sut.accept(request).getOrThrow()

        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(accepted))
        sut.refresh().getOrThrow()
        sut.accept(sut.pendingRequests.value.single()).getOrThrow()
        whenever(presentationStore.loadAcceptedOneTimeIds(LOCAL_IDENTITY)).thenReturn(setOf(request.id))
        sut.clear()
        sut.activate(LOCAL_IDENTITY)
        sut.refresh().getOrThrow()
        sut.accept(sut.pendingRequests.value.single()).getOrThrow()
        verify(paykitSdkService, times(1)).acceptPaymentRequest(any(), any())
        sut.ensurePaymentAllowed(request).getOrThrow()

        sut.activate(SECOND_IDENTITY)
        assertTrue(sut.ensurePaymentAllowed(request).isFailure)
    }

    @Test
    fun `acceptance cleanup removes only confirmed finished requests`() = test {
        val records = listOf(
            paymentRequestRecord(id = "paid", state = PaymentRequestLifecycleState.PROOF_SUBMITTED),
            paymentRequestRecord(id = "canceled", state = PaymentRequestLifecycleState.CANCELED),
            paymentRequestRecord(id = "rejected", state = PaymentRequestLifecycleState.REJECTED),
            paymentRequestRecord(
                id = "retry",
                state = PaymentRequestLifecycleState.ACCEPTED,
                expiresAt = "2020-01-01T00:00:00Z",
            ),
            paymentRequestRecord(id = "recovery", state = PaymentRequestLifecycleState.RECOVERY_REQUIRED),
            paymentRequestRecord(id = "conflict", state = PaymentRequestLifecycleState.INVALID_CONFLICT),
        )
        val ids = records.mapTo(mutableSetOf()) { PaykitPaymentRequestId(it.paymentRequestId, it.counterparty) } +
            PaykitPaymentRequestId("missing", COUNTERPARTY)
        val finished = setOf("paid", "canceled", "rejected")
            .mapTo(mutableSetOf()) { PaykitPaymentRequestId(it, COUNTERPARTY) }
        whenever(presentationStore.loadAcceptedOneTimeIds(LOCAL_IDENTITY)).thenReturn(ids)
        sut.clear()
        sut.activate(LOCAL_IDENTITY)
        sut.refresh().getOrThrow()
        verify(presentationStore, never()).removeAcceptedOneTimeIds(any(), any())

        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(records)
        whenever(presentationStore.removeAcceptedOneTimeIds(LOCAL_IDENTITY, finished)).thenReturn(ids - finished)
        sut.refresh().getOrThrow()
        verify(presentationStore).removeAcceptedOneTimeIds(LOCAL_IDENTITY, finished)
        val retry = sut.pendingRequests.value.single()
        assertEquals("retry", retry.paymentRequestId)
        sut.ensurePaymentAllowed(retry).getOrThrow()
        sut.refresh().getOrThrow()
        verify(presentationStore, times(1)).removeAcceptedOneTimeIds(any(), any())
    }

    @Test
    fun `failed acceptance cleanup retains ids and retries`() = test {
        val record = paymentRequestRecord(state = PaymentRequestLifecycleState.PROOF_SUBMITTED)
        val ids = setOf(PaykitPaymentRequestId(record.paymentRequestId, record.counterparty))
        whenever(presentationStore.loadAcceptedOneTimeIds(LOCAL_IDENTITY)).thenReturn(ids)
        whenever(presentationStore.removeAcceptedOneTimeIds(LOCAL_IDENTITY, ids))
            .thenThrow(IllegalStateException("disk")).thenReturn(emptySet())
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.clear()
        sut.activate(LOCAL_IDENTITY)

        sut.refresh().getOrThrow()
        assertTrue(sut.pendingRequests.value.isEmpty())
        sut.refresh().getOrThrow()
        sut.refresh().getOrThrow()
        verify(presentationStore, times(2)).removeAcceptedOneTimeIds(LOCAL_IDENTITY, ids)
    }

    @Test
    fun `terminal refresh revokes one time authorization even when acceptance cleanup fails`() = test {
        for (state in listOf(PaymentRequestLifecycleState.CANCELED, PaymentRequestLifecycleState.PROOF_SUBMITTED)) {
            restoreAcceptedRequest()
            val record = paymentRequestRecord(state = PaymentRequestLifecycleState.ACCEPTED)
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
            sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()
            val preparedRequest = sut.pendingRequests.value.single()
            sut.ensurePaymentAllowed(preparedRequest).getOrThrow()

            whenever(presentationStore.removeAcceptedOneTimeIds(any(), any()))
                .thenThrow(IllegalStateException("disk"))
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record.copy(state = state)))
            sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

            assertEquals(state, sut.paymentRequestHistory.value.single().lifecycleState)
            assertTrue(sut.ensurePaymentAllowed(preparedRequest).isFailure)
        }
    }

    @Test
    fun `final authorization rechecks identity after asynchronous peer lookup`() = test {
        restoreAcceptedRequest()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
            .thenReturn(
                listOf(
                    paymentRequestRecord(
                        state = PaymentRequestLifecycleState.ACCEPTED,
                        paymentDeadline = PaymentDeadline.At((clock.now() + 60.seconds).toString()),
                    ),
                ),
            )
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
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
    }

    @Test
    fun `accepted request remains payable after proposal expiry until its payment deadline`() = test {
        restoreAcceptedRequest()
        val deadline = clock.now() + 1.seconds
        val record = paymentRequestRecord(
            state = PaymentRequestLifecycleState.ACCEPTED,
            expiresAt = (clock.now() - 1.seconds).toString(),
            paymentDeadline = PaymentDeadline.At(deadline.toString()),
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()

        sut.ensurePaymentAllowed(request).getOrThrow()
        sut.ensurePaymentAllowed(request.copy(lifecycleState = PaymentRequestLifecycleState.PROPOSED)).getOrThrow()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(sut.isPending(request))
        sut.ensurePaymentAllowed(request).getOrThrow()
        advanceTimeBy(1)
        runCurrent()

        assertFalse(sut.isPending(request))
        assertTrue(sut.pendingRequests.value.isEmpty())
        assertEquals(PaykitPaymentRequestError.RequestExpired, sut.ensurePaymentAllowed(request).exceptionOrNull())
        assertEquals(PaymentRequestLifecycleState.ACCEPTED, sut.paymentRequestHistory.value.single().lifecycleState)
        assertEquals(deadline, sut.paymentRequestHistory.value.single().paymentDeadlineAt)
        verify(presentationStore, never()).removeAcceptedOneTimeIds(any(), any())
    }

    @Test
    fun `final authorization rejects a deadline crossed during peer lookup`() = test {
        restoreAcceptedRequest()
        val record = paymentRequestRecord(
            state = PaymentRequestLifecycleState.ACCEPTED,
            paymentDeadline = PaymentDeadline.At((clock.now() + 1.seconds).toString()),
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        val checking = CompletableDeferred<Unit>()
        val checked = CompletableDeferred<Unit>()
        whenever(paykitSdkService.linkedPeers()).doSuspendableAnswer {
            checking.complete(Unit)
            checked.await()
            emptyList()
        }

        val authorization = async { sut.ensurePaymentAllowed(request) }
        checking.await()
        advanceTimeBy(1_001)
        checked.complete(Unit)

        assertEquals(PaykitPaymentRequestError.RequestExpired, authorization.await().exceptionOrNull())
    }

    @Test
    fun `deadline authorization preserves cancellation during peer lookup`() = test {
        restoreAcceptedRequest()
        val record = paymentRequestRecord(
            state = PaymentRequestLifecycleState.ACCEPTED,
            paymentDeadline = PaymentDeadline.At((clock.now() + 60.seconds).toString()),
        )
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        whenever(paykitSdkService.linkedPeers()).thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> { sut.ensurePaymentAllowed(request) }
    }

    @Test
    fun `queued identity switch invalidates ownership before acquiring the repository mutex`() = test {
        restoreAcceptedRequest()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
            .thenReturn(listOf(paymentRequestRecord(state = PaymentRequestLifecycleState.ACCEPTED)))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        val refreshing = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            refreshing.complete(Unit)
            refreshed.await()
            emptyList()
        }
        val refresh = async { sut.refresh() }
        refreshing.await()
        val activation = async { sut.activate(SECOND_IDENTITY) }
        runCurrent()

        assertTrue(sut.ensurePaymentAllowed(request).isFailure)
        refreshed.complete(Unit)
        refresh.await()
        activation.await()
    }

    @Test
    fun `overlapping identity activations preserve restored acceptance`() = test {
        val record = paymentRequestRecord(state = PaymentRequestLifecycleState.ACCEPTED)
        val requestId = PaykitPaymentRequestId(record.paymentRequestId, record.counterparty)
        for (lastIdentity in listOf(SECOND_IDENTITY, LOCAL_IDENTITY)) {
            sut.clear()
            for (identity in listOf(LOCAL_IDENTITY, SECOND_IDENTITY)) {
                whenever(presentationStore.loadAcceptedOneTimeIds(identity))
                    .thenReturn(if (identity == lastIdentity) setOf(requestId) else emptySet())
            }
            sut.activate(LOCAL_IDENTITY)
            val refreshing = CompletableDeferred<Unit>()
            val refreshed = CompletableDeferred<Unit>()
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
                refreshing.complete(Unit)
                refreshed.await()
                listOf(record)
            }
            val refresh = async { sut.refresh() }
            refreshing.await()
            val firstActivation = async { sut.activate(SECOND_IDENTITY) }
            val secondActivation = async { sut.activate(lastIdentity) }
            runCurrent()
            refreshed.complete(Unit)
            refresh.await().getOrThrow()
            firstActivation.await()
            secondActivation.await()

            sut.refresh().getOrThrow()
            val request = sut.pendingRequests.value.single()
            assertEquals(requestId, request.id)
            sut.ensurePaymentAllowed(request).getOrThrow()
            sut.activate(lastIdentity)
            sut.ensurePaymentAllowed(request).getOrThrow()
        }
    }

    @Test
    fun `failed intent persistence prevents remote acceptance`() = test {
        val proposed = paymentRequestRecord()
        val accepted = proposed.copy(state = PaymentRequestLifecycleState.ACCEPTED)
        whenever(presentationStore.addAcceptedOneTimeId(any(), any())).thenThrow(IllegalStateException("disk"))
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposed))
        sut.refresh().getOrThrow()
        assertTrue(sut.accept(sut.pendingRequests.value.single()).isFailure)
        verify(paykitSdkService, never()).acceptPaymentRequest(any(), any())

        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(accepted))
        sut.refresh().getOrThrow()
        assertTrue(sut.pendingRequests.value.isEmpty())
        assertTrue(sut.accept(sut.paymentRequestHistory.value.single()).isFailure)
        assertTrue(sut.ensurePaymentAllowed(sut.paymentRequestHistory.value.single()).isFailure)
    }

    @Test
    fun `lost acceptance response is reconciled from shared state`() = test {
        for (error in listOf(
            PaykitException.Transport("transport_error", "response lost"),
            PaykitException.ConcurrentUpdate("concurrent_update", "response read locked"),
            PaykitException.SharedStateBusy("shared_state_busy", "response read busy"),
        )) {
            sut.clear()
            whenever(presentationStore.loadAcceptedOneTimeIds(any())).thenReturn(emptySet())
            sut.activate(LOCAL_IDENTITY)
            val proposed = paymentRequestRecord()
            whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposed))
            doSuspendableAnswer { throw error }.whenever(paykitSdkService).acceptPaymentRequest(any(), any())
            sut.refresh().getOrThrow()
            val request = sut.pendingRequests.value.single()
            assertTrue(sut.accept(request).isFailure)
            assertTrue(sut.ensurePaymentAllowed(request).isFailure)
            verify(presentationStore, never()).removeAcceptedOneTimeIds(any(), any())

            whenever(paykitSdkService.allPaymentRequests(anyOrNull()))
                .thenReturn(listOf(proposed.copy(state = PaymentRequestLifecycleState.ACCEPTED)))
            whenever(presentationStore.loadAcceptedOneTimeIds(LOCAL_IDENTITY)).thenReturn(setOf(request.id))
            sut.clear()
            sut.activate(LOCAL_IDENTITY)
            sut.refresh().getOrThrow()
            val retry = sut.pendingRequests.value.single()
            sut.accept(retry).getOrThrow()
            sut.ensurePaymentAllowed(retry).getOrThrow()
        }
    }

    @Test
    fun `final authorization requires durable ownership regardless of snapshot lifecycle`() = test {
        val proposed = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposed))
        whenever(paykitSdkService.acceptPaymentRequest(any(), any()))
            .thenReturn(proposed.copy(state = PaymentRequestLifecycleState.ACCEPTED))
        val saving = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        whenever(presentationStore.addAcceptedOneTimeId(any(), any())).doSuspendableAnswer {
            saving.complete(Unit)
            saved.await()
            setOf(it.getArgument<PaykitPaymentRequestId>(1))
        }
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        val acceptedSnapshot = request.copy(lifecycleState = PaymentRequestLifecycleState.ACCEPTED)
        val acceptance = async { sut.accept(request) }
        saving.await()
        assertFalse(acceptance.isCompleted)
        assertTrue(sut.ensurePaymentAllowed(request).isFailure)
        assertTrue(sut.ensurePaymentAllowed(acceptedSnapshot).isFailure)

        saved.complete(Unit)
        acceptance.await().getOrThrow()
        sut.ensurePaymentAllowed(request).getOrThrow()
        sut.ensurePaymentAllowed(acceptedSnapshot).getOrThrow()
    }

    @Test
    fun `second install cannot accept stale proposal or resume first installs acceptance`() = test {
        val otherStore = mock<PaykitPaymentRequestPresentationStore>()
        whenever(otherStore.removeAcceptedOneTimeIds(any(), any())).thenReturn(emptySet())
        whenever(otherStore.loadSubscriptionState(any())).thenReturn(PaykitSubscriptionPresentationState())
        val other = PaykitPaymentRequestRepo(
            testDispatcher,
            paykitSdkService,
            settingsStore,
            otherStore,
            diagnostics,
            paymentProofStore,
            paymentProofRepo,
            subscriptionNotificationScheduler,
            clock,
            clock,
        )
        other.activate(LOCAL_IDENTITY)
        var record = paymentRequestRecord()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenAnswer { listOf(record) }
        whenever(paykitSdkService.acceptPaymentRequest(any(), any())).thenAnswer {
            check(record.state == PaymentRequestLifecycleState.PROPOSED)
            record = record.copy(state = PaymentRequestLifecycleState.ACCEPTED)
            record
        }
        sut.refresh().getOrThrow()
        other.refresh().getOrThrow()
        val stale = other.pendingRequests.value.single()
        sut.accept(sut.pendingRequests.value.single()).getOrThrow()

        assertTrue(other.accept(stale).isFailure)
        other.refresh().getOrThrow()
        assertTrue(other.pendingRequests.value.isEmpty())
        assertTrue(other.automaticPendingRequests().isEmpty())
        val remoteAccepted = other.paymentRequestHistory.value.single()
        assertNull(other.pendingRequest(remoteAccepted.id))
        assertTrue(other.accept(remoteAccepted).isFailure)
        assertTrue(other.claimForPayment(remoteAccepted).isFailure)
        assertTrue(other.ensurePaymentAllowed(remoteAccepted).isFailure)
        verify(paykitSdkService, never()).claimPaymentRequestForExecution(any(), any())
        verify(otherStore).removeAcceptedOneTimeIds(eq(LOCAL_IDENTITY), eq(setOf(stale.id)))
        sut.refresh().getOrThrow()
        sut.accept(sut.pendingRequests.value.single()).getOrThrow()
        other.clear()
    }

    @Test
    fun `identity switch during acceptance saves only the captured identity and prevents execution`() = test {
        val proposed = paymentRequestRecord()
        val accepting = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(proposed))
        whenever(paykitSdkService.acceptPaymentRequest(any(), any())).doSuspendableAnswer {
            accepting.complete(Unit)
            accepted.await()
            proposed.copy(state = PaymentRequestLifecycleState.ACCEPTED)
        }
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        val acceptance = async { sut.accept(request) }
        accepting.await()
        val activation = async { sut.activate(SECOND_IDENTITY) }
        runCurrent()
        accepted.complete(Unit)

        assertTrue(acceptance.await().isFailure)
        activation.await()
        verify(presentationStore).addAcceptedOneTimeId(LOCAL_IDENTITY, request.id)
        verify(presentationStore, never()).addAcceptedOneTimeId(eq(SECOND_IDENTITY), any())
        val snapshot = request.copy(lifecycleState = PaymentRequestLifecycleState.ACCEPTED)
        assertTrue(sut.ensurePaymentAllowed(snapshot).isFailure)
    }

    @Test
    fun `unreadable accepted ownership prevents activation`() = test {
        sut.clear()
        whenever(presentationStore.loadAcceptedOneTimeIds(LOCAL_IDENTITY))
            .thenThrow(IllegalStateException("unreadable"))
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))
        sut.activate(LOCAL_IDENTITY)
        sut.refresh().getOrThrow()
        assertTrue(sut.pendingRequests.value.isEmpty())
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

        val finishRefresh = CompletableDeferred<Unit>()
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).doSuspendableAnswer {
            finishRefresh.await()
            listOf(paymentRequestRecord())
        }
        val refresh = async { sut.refresh() }
        runCurrent()
        val marking = async { sut.markPresented(request) }
        runCurrent()
        assertTrue(marking.isCompleted)
        assertTrue(marking.await())
        assertTrue(sut.automaticPendingRequests().isEmpty())
        finishRefresh.complete(Unit)
        refresh.await().getOrThrow()
        sut.refresh(PaykitPaymentRequestRefreshMode.STORED).getOrThrow()

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
    fun `identity switch invalidates an in-flight refresh without waiting for network`() = test {
        whenever(paykitSdkService.allPaymentRequests(anyOrNull())).thenReturn(listOf(paymentRequestRecord()))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        val refreshStarted = CompletableDeferred<Unit>()
        val resumeRefresh = CompletableDeferred<Unit>()
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer {
            refreshStarted.complete(Unit)
            resumeRefresh.await()
            emptyList()
        }
        whenever(presentationStore.load(SECOND_IDENTITY)).thenReturn(emptySet())

        val refresh = async { sut.refresh() }
        runCurrent()
        refreshStarted.await()
        sut.clear()
        sut.activate(SECOND_IDENTITY)
        assertFalse(refresh.isCompleted)
        assertFalse(sut.markPresented(request))
        resumeRefresh.complete(Unit)

        refresh.await().getOrThrow()
        verify(presentationStore, never()).save(any(), any())

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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
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
        verify(paykitSdkService).identityStatus(Priority.Interactive)
        verify(paykitSdkService).linkedPeers(Priority.Interactive)
        verify(paykitSdkService).processOutboundPrivateMessages(COUNTERPARTY, Priority.Interactive)
    }

    @Test
    fun `proposal uses fresh delivery status only for the exact committed proposal`() = test {
        val record = paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).copy(proposalOutboundMessageId = 7uL)
        val sent = record.copy(proposalOutboundStatus = OutboundPrivateMessageStatus.SENT)
        val target = stubProposal(record)
        val cases = listOf(
            sent to PaykitPaymentRequestDeliveryStatus.Sent,
            record to PaykitPaymentRequestDeliveryStatus.Queued,
            null to PaykitPaymentRequestDeliveryStatus.Queued,
            sent.copy(counterparty = SECOND_IDENTITY) to PaykitPaymentRequestDeliveryStatus.Queued,
            sent.copy(paymentRequestId = "another-request") to PaykitPaymentRequestDeliveryStatus.Queued,
            sent.copy(proposalOutboundMessageId = 8uL) to PaykitPaymentRequestDeliveryStatus.Queued,
            sent.copy(localRole = PaymentRequestLocalRole.PAYER) to PaykitPaymentRequestDeliveryStatus.Queued,
        )
        for ((freshRecord, expectedStatus) in cases) {
            whenever(paykitSdkService.allPaymentRequests(LOCAL_IDENTITY)).thenReturn(listOfNotNull(freshRecord))

            val creation = sut.propose(
                PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds)),
                target,
                listOf(COUNTERPARTY),
            ).getOrThrow()

            assertEquals(expectedStatus, creation.request.deliveryStatus)
            assertTrue(creation.wasPublishedToActiveState)
            assertEquals(LOCAL_IDENTITY, creation.creatorIdentity)
        }
        verify(paykitSdkService, times(cases.size)).allPaymentRequests(LOCAL_IDENTITY, Priority.Interactive)
        verify(paykitSdkService, times(cases.size)).proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))
    }

    @Test
    fun `proposal skips status lookup only after its durable send failure`() = test {
        val record = paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).copy(proposalOutboundMessageId = 7uL)
        val target = stubProposal(record)
        val error = mock<PrivateOperationError> {
            on { redactedContext() } doReturn "send failed"
        }
        whenever(paykitSdkService.allPaymentRequests(LOCAL_IDENTITY)).thenReturn(
            listOf(record.copy(proposalOutboundStatus = OutboundPrivateMessageStatus.SENT)),
        )
        for (failedMessageId in listOf(7uL, 8uL, null)) {
            whenever(paykitSdkService.processOutboundPrivateMessages(COUNTERPARTY)).doSuspendableAnswer {
                if (failedMessageId == null) throw PaykitException.Storage("write_uncertain", "Send may be committed")
                OutboundPrivateSendReport(
                    attempted = listOf(failedMessageId),
                    sent = emptyList(),
                    failed = listOf(OutboundPrivateSendFailure(failedMessageId, error)),
                    reservationCleanupFailures = emptyList(),
                    recoveryMarkerFailures = emptyList(),
                )
            }
            clearInvocations(paykitSdkService)

            val creation = sut.propose(
                PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds)),
                target,
                listOf(COUNTERPARTY),
            ).getOrThrow()

            val expected = if (failedMessageId == 7uL) {
                PaykitPaymentRequestDeliveryStatus.Queued
            } else {
                PaykitPaymentRequestDeliveryStatus.Sent
            }
            assertEquals(expected, creation.request.deliveryStatus)
            verify(paykitSdkService, times(if (failedMessageId == 7uL) 0 else 1)).allPaymentRequests(LOCAL_IDENTITY)
        }
    }

    @Test
    fun `proposal remains created after status read failure and propagates status read cancellation`() = test {
        val record = paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).copy(proposalOutboundMessageId = 7uL)
        val target = stubProposal(record)
        val draft = PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds))
        var cancelled = false
        whenever(paykitSdkService.allPaymentRequests(LOCAL_IDENTITY)).doSuspendableAnswer {
            if (cancelled) throw CancellationException()
            error("Unavailable")
        }

        val creation = sut.propose(draft, target, listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(PaykitPaymentRequestDeliveryStatus.Queued, creation.request.deliveryStatus)
        assertEquals(listOf(creation.request), sut.paymentRequestHistory.value)
        cancelled = true
        assertFailsWith<CancellationException> { sut.propose(draft, target, listOf(COUNTERPARTY)) }
        verify(paykitSdkService, times(2)).proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))
        assertFalse(sut.isCreatingRequest.value)
    }

    @Test
    fun `proposal already marked sent does not read delivery status again`() = test {
        val target = stubProposal(
            paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).copy(
                proposalOutboundMessageId = 7uL,
                proposalOutboundStatus = OutboundPrivateMessageStatus.SENT,
            ),
        )

        val creation = sut.propose(
            PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds)),
            target,
            listOf(COUNTERPARTY),
        ).getOrThrow()

        assertEquals(PaykitPaymentRequestDeliveryStatus.Sent, creation.request.deliveryStatus)
        verify(paykitSdkService, never()).allPaymentRequests(anyOrNull())
    }

    @Test
    fun `proposal revalidates and drains only the selected saved contact`() = test {
        val target = PaykitPaymentRequestTarget(COUNTERPARTY)
        val stalledDiscovery = CompletableDeferred<Unit>()
        val unrelatedDelivery = CompletableDeferred<Unit>()
        whenever(paykitSdkService.processPendingPrivateMessages()).doSuspendableAnswer {
            unrelatedDelivery.await()
            emptyList()
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(SECOND_IDENTITY)).doSuspendableAnswer {
            unrelatedDelivery.await()
            OutboundPrivateSendReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        whenever(paykitSdkService.processOutboundPrivateMessages(COUNTERPARTY)).thenReturn(
            OutboundPrivateSendReport(listOf(7uL), listOf(7uL), emptyList(), emptyList(), emptyList()),
        )
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED),
                linkedPeer(SECOND_IDENTITY, LinkedPeerState.LINKED),
            ),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
            true,
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(SECOND_IDENTITY), any())).doSuspendableAnswer {
            stalledDiscovery.await()
            true
        }
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))).thenReturn(
            paymentRequestRecord(
                role = PaymentRequestLocalRole.PAYEE,
                counterparty = COUNTERPARTY,
            ).copy(proposalOutboundMessageId = 7uL),
        )

        val proposal = async {
            sut.propose(
                draft = PaykitPaymentRequestDraft(1uL, "Lunch", clock.now().plus(60.seconds)),
                target = target,
                savedPublicKeys = listOf(SECOND_IDENTITY, COUNTERPARTY),
            )
        }
        runCurrent()

        val completedBeforeUnrelatedDelivery = proposal.isCompleted
        unrelatedDelivery.complete(Unit)
        stalledDiscovery.complete(Unit)
        val creation = proposal.await().getOrThrow()
        assertTrue(completedBeforeUnrelatedDelivery)
        assertEquals(PaykitPaymentRequestDeliveryStatus.Sent, creation.request.deliveryStatus)
        verify(paykitSdkService, never()).allPaymentRequests(anyOrNull())
        verifyBlocking(paykitSdkService, never()) { canReceivePaymentRequests(eq(SECOND_IDENTITY), any()) }
        verify(paykitSdkService).processOutboundPrivateMessages(COUNTERPARTY)
        verify(paykitSdkService, never()).processOutboundPrivateMessages(SECOND_IDENTITY)
        verify(paykitSdkService, never()).processPendingPrivateMessages()
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
            true,
        )
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))).doSuspendableAnswer {
            proposalStarted.complete(Unit)
            finishProposal.await()
            paymentRequestRecord(role = PaymentRequestLocalRole.PAYEE).copy(proposalOutboundMessageId = 7uL)
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
        verify(paykitSdkService, never()).allPaymentRequests(anyOrNull())
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
        verifyBlocking(paykitSdkService, never()) { canReceivePaymentRequests(any(), any()) }
    }

    @Test
    fun `recipient discovery reuses unchanged link state`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
            true,
        )

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(1)) { canReceivePaymentRequests(eq(COUNTERPARTY), any()) }
    }

    @Test
    fun `recipient discovery bounds public lookups without a slow peer blocking later peers`() = test {
        val keys = "yb".flatMap { first ->
            "ybndrfg8ejkmcpqxot1uwisza345h769".map { second ->
                COUNTERPARTY.replace("pubky3r", "pubky$first$second")
            }
        }.take(61)
        whenever(paykitSdkService.identityStatus()).thenReturn(
            IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKED) })
        whenever(paykitSdkService.canReceivePaymentRequests(any(), any())).thenReturn(true)
        sut.refreshEligibleTargets(keys).getOrThrow()
        val releaseSlowPeer = CompletableDeferred<Unit>()
        val completedPeers = mutableSetOf<String>()
        var active = 0
        var maxActive = 0
        whenever(paykitSdkService.canReceivePaymentRequests(any(), any())).doSuspendableAnswer {
            val publicKey = it.getArgument<String>(0)
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (publicKey == keys.first()) {
                    releaseSlowPeer.await()
                    null
                } else {
                    delay(500.milliseconds)
                    completedPeers += publicKey
                    if (publicKey == keys[1]) throw PaykitException.Transport("transport", "Registry unavailable")
                    true
                }
            } finally {
                active--
            }
        }
        val startedAt = testScheduler.currentTime
        val discovery = async { sut.refreshEligibleTargets(keys, force = true).getOrThrow() }
        try {
            runCurrent()
            assertEquals(8, active)
            advanceTimeBy(4500)
            runCurrent()
            assertEquals(keys.drop(1).toSet(), completedPeers)
            assertFalse(discovery.isCompleted)
            assertEquals(1, active)
        } finally {
            releaseSlowPeer.complete(Unit)
        }
        discovery.await()

        assertEquals(4500L, testScheduler.currentTime - startedAt)
        assertEquals(8, maxActive)
        assertEquals(0, active)
        assertEquals(keys.map(::PaykitPaymentRequestTarget), sut.eligibleTargets.value)
    }

    @Test
    fun `cancelling recipient discovery cancels every active public lookup`() = test {
        val keys = "ybndrfg8e".map { COUNTERPARTY.replace("pubky3", "pubky$it") }
        whenever(paykitSdkService.identityStatus()).thenReturn(
            IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(keys.map { linkedPeer(it, LinkedPeerState.LINKED) })
        var active = 0
        whenever(paykitSdkService.canReceivePaymentRequests(any(), any())).doSuspendableAnswer {
            active++
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }
        val discovery = async { sut.refreshEligibleTargets(keys) }
        runCurrent()
        assertEquals(8, active)

        discovery.cancel()
        discovery.join()

        assertEquals(0, active)
        verify(paykitSdkService, times(8)).canReceivePaymentRequests(any(), any())
        assertTrue(sut.eligibleTargets.value.isEmpty())
    }

    @Test
    fun `single recipient refresh adds a newly eligible contact`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
            true,
        )

        val target = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow().target

        val expected = PaykitPaymentRequestTarget(COUNTERPARTY)
        assertEquals(expected, target)
        assertEquals(listOf(expected), sut.eligibleTargets.value)
    }

    @Test
    fun `single recipient refresh reads on the interactive lane and a full refresh in bulk`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
            true,
        )

        sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow()
        verifyBlocking(paykitSdkService) { canReceivePaymentRequests(COUNTERPARTY, PaykitReadLane.Interactive) }
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        verifyBlocking(paykitSdkService) { canReceivePaymentRequests(COUNTERPARTY, PaykitReadLane.Bulk) }
        verify(paykitSdkService).identityStatus(Priority.Interactive)
        verify(paykitSdkService).linkedPeers(Priority.Interactive)
        verify(paykitSdkService).identityStatus(Priority.Background)
        verify(paykitSdkService).linkedPeers(Priority.Background)
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).doSuspendableAnswer {
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(SECOND_IDENTITY), any()))
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).doSuspendableAnswer {
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).doSuspendableAnswer {
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).doSuspendableAnswer {
            counterpartyLookups += 1
            if (counterpartyLookups > 1) return@doSuspendableAnswer false
            fullLookupStarted.complete(Unit)
            releaseFullLookup.await()
            true
        }
        whenever(paykitSdkService.canReceivePaymentRequests(eq(SECOND_IDENTITY), any()))
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
            .thenReturn(false, true)

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(2)) { canReceivePaymentRequests(eq(COUNTERPARTY), any()) }
    }

    @Test
    fun `recipient discovery retains a known target while capability refresh fails`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
            .thenReturn(true)
            .thenThrow(IllegalStateException("marker unavailable"))

        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()
        sut.refreshEligibleTargets(listOf(COUNTERPARTY), force = true).getOrThrow()

        assertEquals(
            listOf(PaykitPaymentRequestTarget(COUNTERPARTY)),
            sut.eligibleTargets.value,
        )
        verifyBlocking(paykitSdkService, times(2)) { canReceivePaymentRequests(eq(COUNTERPARTY), any()) }
    }

    @Test
    fun `recipient discovery preserves a known target after a lookup timeout`() = test {
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(paykitSdkService.linkedPeers()).thenReturn(
            listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)),
        )
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any()))
            .thenReturn(true)
            .thenReturn(null)
        sut.refreshEligibleTargets(listOf(COUNTERPARTY)).getOrThrow()

        val result = sut.refreshEligibleTarget(COUNTERPARTY).getOrThrow()
        assertFalse(result.isComplete)
        assertEquals(PaykitPaymentRequestTarget(COUNTERPARTY), result.target)
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
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
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(
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

    private suspend fun stubProposal(record: PaymentRequestRecord): PaykitPaymentRequestTarget {
        whenever(paykitSdkService.identityStatus()).thenReturn(
            IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE),
        )
        whenever(paykitSdkService.linkedPeers()).thenReturn(listOf(linkedPeer(COUNTERPARTY, LinkedPeerState.LINKED)))
        whenever(paykitSdkService.canReceivePaymentRequests(eq(COUNTERPARTY), any())).thenReturn(true)
        whenever(paykitSdkService.proposePaymentRequest(any(), any(), eq(LOCAL_IDENTITY))).thenReturn(record)
        return PaykitPaymentRequestTarget(COUNTERPARTY)
    }

    private suspend fun restoreAcceptedRequest() {
        whenever(presentationStore.loadAcceptedOneTimeIds(LOCAL_IDENTITY)).thenReturn(
            setOf(PaykitPaymentRequestId(PAYMENT_REQUEST_ID, COUNTERPARTY)),
        )
        sut.clear()
        sut.activate(LOCAL_IDENTITY)
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
