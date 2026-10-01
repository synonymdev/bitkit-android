package to.bitkit.repositories

import com.synonym.paykit.AllowanceHistoryStatus
import com.synonym.paykit.AllowanceLifecycleState
import com.synonym.paykit.AllowanceLocalRole
import com.synonym.paykit.AllowanceRecord
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateSendReport
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.PaykitAllowanceFixtures.ALLOWANCE_ID
import to.bitkit.repositories.PaykitAllowanceFixtures.SECOND_ALLOWANCE_ID
import to.bitkit.repositories.PaykitAllowanceLocalState.Stage
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class PaykitAllowanceRepoTest : BaseUnitTest() {
    companion object {
        private val PAYMENT_HASH = "ab".repeat(32)
        private val TXID = "c".repeat(64)
    }

    private val fixtures = PaykitAllowanceFixtures
    private val identity = fixtures.identityKey
    private val sdk = mock<PaykitSdkService>()
    private val executor = mock<PaykitAllowanceExecutor>()
    private val lightningRepo = mock<LightningRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val executorEvents = MutableSharedFlow<PaykitAllowanceEvent>(extraBufferCapacity = 8)
    private val nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private val lightningState = MutableStateFlow(LightningState())
    private val terms = fixtures.terms()
    private val clock = object : Clock {
        override fun now(): Instant = PaykitAllowanceFixtures.now
    }
    private val proposedTerms = mutableListOf<Pair<Instant, List<String>>>()
    private var localState = PaykitAllowanceLocalState()
    private var records = listOf<AllowanceRecord>()
    private lateinit var sut: PaykitAllowanceRepo

    @Before
    fun setUp() = test {
        whenever(executor.events).thenReturn(executorEvents)
        whenever(executor.localState(any())).thenAnswer { localState }
        whenever(executor.updateLocalState(any(), any())).doSuspendableAnswer {
            val change = it.getArgument<(PaykitAllowanceLocalState) -> PaykitAllowanceLocalState>(1)
            localState = change(localState)
            localState
        }
        whenever(executor.autoPay(any(), any(), any())).thenReturn(PaykitAllowanceAutoPayResult.STARTED)
        whenever(executor.isHandling(any())).thenReturn(false)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(activityRepo.setContact(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(sdk.listAllowances(any())).thenAnswer { records }
        whenever(sdk.linkedPeers()).thenReturn(emptyList())
        whenever(sdk.processOutboundPrivateMessages(any()))
            .thenReturn(OutboundPrivateSendReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))

        sut = PaykitAllowanceRepo(
            ioDispatcher = testDispatcher,
            paykitSdkService = sdk,
            executor = executor,
            lightningRepo = lightningRepo,
            activityRepo = activityRepo,
            clock = clock,
            allowanceTerms = { _, monthAnchor, endpoints ->
                proposedTerms += monthAnchor to endpoints
                terms
            },
        )
    }

    // region Entries

    @Test
    fun `entries list one grant per contact with the grant's limits`() = test {
        records = listOf(
            fixtures.record(allowanceId = ALLOWANCE_ID),
            fixtures.record(allowanceId = "allowance-other", counterparty = fixtures.otherCounterpartyKey),
            fixtures.record(allowanceId = "allowance-invalid", historyStatus = AllowanceHistoryStatus.INVALID),
        )
        localState = PaykitAllowanceLocalState(groups = listOf(group(ALLOWANCE_ID)))

        sut.activate(identity)

        val entries = sut.entries.value
        assertEquals(listOf("group-1", "allowance-other"), entries.map { it.id })
        val grant = entries.first()
        assertEquals(listOf(ALLOWANCE_ID), grant.allowances.map { it.allowanceId })
        assertEquals(ALLOWANCE_ID, grant.primary.allowanceId)
        assertEquals(fixtures.limits, grant.limits)
        assertEquals(fixtures.counterpartyKey, grant.counterparty)
        assertEquals(PaykitAllowance.Role.ALLOWER, grant.role)
        assertEquals(5_000uL, grant.perPaymentMaxSats)
        assertEquals(50_000uL, grant.monthlyLimitSats)
        assertEquals(PaykitAllowance.Status.ACTIVE, grant.status(fixtures.now))
        assertNull(entries.last().limits)
        assertEquals(ALLOWANCE_ID, sut.entry("group-1")?.primary?.allowanceId)
    }

    @Test
    fun `activation recovers once per identity and deactivation clears entries`() = test {
        records = listOf(fixtures.record())

        sut.activate(identity)
        sut.activate(identity)

        verify(executor, times(1)).recover(identity)
        verify(executor, times(2)).activate(identity)
        assertEquals(1, sut.entries.value.size)

        sut.deactivate()

        verify(executor).activate(null)
        assertEquals(emptyList(), sut.entries.value)
        assertTrue(sut.refresh().isSuccess)
        verify(sdk, times(2)).listAllowances(any())
    }

    @Test
    fun `auto-paid sats and requests come from succeeded automatic payments`() = test {
        records = listOf(fixtures.record(allowanceId = ALLOWANCE_ID))
        val paid = fixtures.paymentRequest(id = "paid")
        val failed = fixtures.paymentRequest(id = "failed")
        val secondPaid = fixtures.paymentRequest(id = "second")
        localState = PaykitAllowanceLocalState(
            groups = listOf(group(ALLOWANCE_ID)),
            journal = listOf(
                journalEntry("a", paid, ALLOWANCE_ID, 1_000uL, Stage.SUCCEEDED),
                journalEntry("b", secondPaid, ALLOWANCE_ID, 2_000uL, Stage.SUCCEEDED),
                journalEntry("c", failed, ALLOWANCE_ID, 5_000uL, Stage.FAILED),
                journalEntry("d", fixtures.paymentRequest(id = "manual"), null, 7_000uL, Stage.SUCCEEDED, false),
            ),
        )

        sut.activate(identity)

        assertEquals(mapOf("group-1" to 3_000uL), sut.autoPaidSats.value)
        assertTrue(sut.isAutoPaid(paid.id))
        assertFalse(sut.isAutoPaid(failed.id))
        assertFalse(sut.isAutoPaid(fixtures.paymentRequest(id = "manual").id))
    }

    // endregion

    // region Coverage

    @Test
    fun `coverage needs an active allower allowance for the request's contact identity`() = test {
        records = listOf(fixtures.record(terms = fixtures.terms(expiresAt = (fixtures.now + 30.days).toString())))

        sut.activate(identity)

        assertTrue(sut.coversRequest(fixtures.paymentRequest()))
        assertFalse(sut.coversRequest(fixtures.paymentRequest(counterparty = fixtures.otherCounterpartyKey)))

        records = listOf(
            fixtures.record(terms = fixtures.terms(expiresAt = "2026-09-20T00:00:00Z")),
            fixtures.record(allowanceId = "allowee", localRole = AllowanceLocalRole.ALLOWEE, proposedByMe = false),
            fixtures.record(allowanceId = "proposed", state = AllowanceLifecycleState.PROPOSED),
        )
        sut.refresh()

        assertFalse(sut.coversRequest(fixtures.paymentRequest()))
    }

    @Test
    fun `requests created before the allowance was accepted stay manual`() = test {
        records = listOf(fixtures.record(lastEventAt = "2026-09-24T11:30:00Z"))

        sut.activate(identity)

        assertFalse(sut.coversRequest(fixtures.paymentRequest(createdAt = "2026-09-24T11:00:00Z")))
        assertTrue(sut.coversRequest(fixtures.paymentRequest(createdAt = "2026-09-24T11:29:40Z")), "Within tolerance")
        assertTrue(sut.coversRequest(fixtures.paymentRequest(createdAt = "2026-09-24T11:45:00Z")))
    }

    // endregion

    // region Lifecycle

    @Test
    fun `propose sends the terms to the linked contact and records one entry`() = test {
        whenever(sdk.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(fixtures.otherCounterpartyKey),
                linkedPeer(fixtures.counterpartyKey),
            ),
        )
        whenever(sdk.proposeAllowance(any(), any(), any())).doSuspendableAnswer {
            fixtures.record(
                allowanceId = ALLOWANCE_ID,
                state = AllowanceLifecycleState.PROPOSED,
                terms = terms,
            ).also { record -> records = records + record }
        }
        sut.activate(identity)

        val result = sut.propose(fixtures.counterpartyKey, fixtures.limits)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        verify(sdk).proposeAllowance(fixtures.counterpartyKey, AllowanceLocalRole.ALLOWER, terms)
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey)
        assertEquals(
            listOf(fixtures.septemberAnchor to PaykitAllowanceRepo.allowedPaymentEndpointIdentifiers()),
            proposedTerms,
        )
        val group = localState.groups.single()
        assertEquals(listOf(ALLOWANCE_ID), group.allowanceIds)
        assertEquals(fixtures.limits, group.limits)
        assertEquals(fixtures.counterpartyKey, group.counterparty)
        val entry = sut.entries.value.single()
        assertEquals(group.id, entry.id)
        assertEquals(fixtures.limits, entry.limits)
        assertEquals(PaykitAllowance.Status.AWAITING_ANSWER, entry.status(fixtures.now))
    }

    @Test
    fun `propose fails when the contact link is not up`() = test {
        whenever(sdk.linkedPeers())
            .thenReturn(listOf(linkedPeer(fixtures.counterpartyKey, LinkedPeerState.LINKING)))
        sut.activate(identity)

        val result = sut.propose(fixtures.counterpartyKey, fixtures.limits)

        assertIs<PaykitAllowanceError.ContactNotLinked>(result.exceptionOrNull())
        verify(sdk, never()).proposeAllowance(any(), any(), any())
        assertTrue(localState.groups.isEmpty())
    }

    @Test
    fun `an allowance stays one grant for the identity when the contact adds another app later`() = test {
        // The grant is bound to the contact's identity, so one that links an app later is covered without a proposal.
        records = listOf(fixtures.record(allowanceId = ALLOWANCE_ID))
        localState = PaykitAllowanceLocalState(groups = listOf(group(ALLOWANCE_ID)))
        whenever(sdk.linkedPeers()).thenReturn(listOf(linkedPeer(fixtures.counterpartyKey)))
        sut.activate(identity)

        sut.refresh()

        verify(sdk, never()).proposeAllowance(any(), any(), any())
        assertTrue(sut.coversRequest(fixtures.paymentRequest()))
        assertEquals(listOf(ALLOWANCE_ID), sut.entries.value.single().allowances.map { it.allowanceId })
    }

    @Test
    fun `received ask is presented once and accepting answers it`() = test {
        val proposalRecord = receivedAsk()
        records = listOf(proposalRecord)
        whenever(sdk.acceptAllowance(any(), any())).thenReturn(proposalRecord)
        sut.activate(identity)

        val proposal = checkNotNull(sut.proposalForPresentation())
        assertEquals(ALLOWANCE_ID, proposal.id)
        sut.markProposalPresented(proposal.id)
        assertNull(sut.proposalForPresentation())
        assertEquals(setOf(ALLOWANCE_ID), localState.presentedProposalIds)

        assertTrue(sut.accept(proposal.id).isSuccess)

        verify(sdk).acceptAllowance(fixtures.counterpartyKey, ALLOWANCE_ID)
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey)
    }

    @Test
    fun `offer from the allower is accepted without the review sheet and not announced twice`() = test {
        // The proposer took the allower role, so this wallet is the allowee.
        val offer = receivedProposal()
        records = listOf(offer)
        whenever(sdk.acceptAllowance(any(), any())).thenAnswer {
            records = listOf(fixtures.record(localRole = AllowanceLocalRole.ALLOWEE, proposedByMe = false))
            records.single()
        }
        sut.activate(identity)
        assertNull(sut.proposalForPresentation(), "An offer from the allower never opens the review sheet")

        val accepted = sut.acceptOffersFromAllowers()

        assertEquals(listOf(fixtures.counterpartyKey), accepted.map { it.counterparty })
        verify(sdk).acceptAllowance(fixtures.counterpartyKey, ALLOWANCE_ID)
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey)
        val entry = sut.entries.value.single()
        assertEquals(PaykitAllowance.Status.ACTIVE, entry.status(fixtures.now))
        assertEquals(PaykitAllowance.Role.ALLOWEE, entry.role)
        assertTrue(entry.canEnd, "The allowee can still end it")
        assertNull(sut.proposalForPresentation())

        assertTrue(sut.acceptOffersFromAllowers().isEmpty(), "An accepted offer is not accepted or announced twice")
        verify(sdk, times(1)).acceptAllowance(any(), any())
    }

    @Test
    fun `failed offer accept stays unanswered and is retried on the next refresh`() = test {
        val offer = receivedProposal()
        records = listOf(offer)
        whenever(sdk.acceptAllowance(any(), any())).thenThrow(RuntimeException("offline"))
        sut.activate(identity)

        assertTrue(sut.acceptOffersFromAllowers().isEmpty())
        assertNull(sut.proposalForPresentation())

        whenever(sdk.acceptAllowance(any(), any())).thenReturn(offer)
        assertEquals(1, sut.acceptOffersFromAllowers().size)
        verify(sdk, times(2)).acceptAllowance(any(), any())
    }

    @Test
    fun `ask from the allowee is left for the review sheet`() = test {
        // The proposer took the allowee role, so this wallet is the allower and pays: the user decides.
        records = listOf(receivedAsk())
        sut.activate(identity)

        assertTrue(sut.acceptOffersFromAllowers().isEmpty())

        verify(sdk, never()).acceptAllowance(any(), any())
        assertEquals(ALLOWANCE_ID, sut.proposalForPresentation()?.id)
        assertEquals(PaykitAllowance.Status.AWAITING_MY_ANSWER, sut.entries.value.single().status(fixtures.now))
    }

    @Test
    fun `proposal sent or already answered is never accepted`() = test {
        records = listOf(
            fixtures.record(allowanceId = "sent", state = AllowanceLifecycleState.PROPOSED, proposedByMe = true),
            fixtures.record(
                allowanceId = "declined",
                localRole = AllowanceLocalRole.ALLOWEE,
                state = AllowanceLifecycleState.REJECTED,
                proposedByMe = false,
            ),
            fixtures.record(
                allowanceId = "ended",
                localRole = AllowanceLocalRole.ALLOWEE,
                state = AllowanceLifecycleState.ENDED,
                proposedByMe = false,
            ),
            fixtures.record(
                allowanceId = "active",
                localRole = AllowanceLocalRole.ALLOWEE,
                state = AllowanceLifecycleState.ACCEPTED,
                proposedByMe = false,
            ),
        )
        sut.activate(identity)

        assertTrue(sut.acceptOffersFromAllowers().isEmpty())

        verify(sdk, never()).acceptAllowance(any(), any())
    }

    @Test
    fun `offers are not accepted without an identity`() = test {
        records = listOf(receivedProposal())

        assertTrue(sut.acceptOffersFromAllowers().isEmpty())

        verify(sdk, never()).acceptAllowance(any(), any())
    }

    @Test
    fun `answering fails when nothing in the entry awaits an answer`() = test {
        records = listOf(fixtures.record())
        sut.activate(identity)

        assertIs<PaykitAllowanceError.Unavailable>(sut.reject(ALLOWANCE_ID).exceptionOrNull())
        assertIs<PaykitAllowanceError.Unavailable>(sut.accept("missing").exceptionOrNull())
        verify(sdk, never()).rejectAllowance(any(), any())
    }

    @Test
    fun `ending an entry ends its allowance and sends the end right away`() = test {
        records = listOf(fixtures.record(allowanceId = ALLOWANCE_ID))
        localState = PaykitAllowanceLocalState(groups = listOf(group(ALLOWANCE_ID)))
        whenever(sdk.endAllowance(any(), any())).thenReturn(records.last())
        sut.activate(identity)

        assertTrue(sut.end("group-1").isSuccess)

        verify(sdk).endAllowance(fixtures.counterpartyKey, ALLOWANCE_ID)
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey)
    }

    // endregion

    // region Automatic payments

    @Test
    fun `covered request stays off the Send sheet until it is found manual`() = test {
        nodeReady()
        records = listOf(fixtures.record())
        whenever(executor.autoPay(any(), any(), any())).thenReturn(PaykitAllowanceAutoPayResult.MANUAL)
        sut.activate(identity)
        val request = fixtures.paymentRequest()

        assertTrue(sut.isAutomaticallyHandling(request))
        assertFalse(sut.isAutomaticallyHandling(fixtures.paymentRequest(counterparty = fixtures.otherCounterpartyKey)))

        assertFalse(sut.processIncomingRequests(listOf(request)))
        assertFalse(sut.isAutomaticallyHandling(request))
        assertFalse(sut.processIncomingRequests(listOf(request)))
        verify(executor, times(1)).autoPay(any(), any(), any())

        records = listOf(fixtures.record(), fixtures.record(allowanceId = SECOND_ALLOWANCE_ID))
        sut.refresh()
        sut.processIncomingRequests(listOf(request))
        verify(executor, times(2)).autoPay(any(), any(), any())
    }

    @Test
    fun `request being paid stays off the Send sheet`() = test {
        sut.activate(identity)
        val request = fixtures.paymentRequest()
        whenever(executor.isHandling(request.id)).thenReturn(true)

        assertTrue(sut.isAutomaticallyHandling(request))
    }

    @Test
    fun `started payment is reported and refreshes the allowances`() = test {
        nodeReady()
        records = listOf(fixtures.record())
        sut.activate(identity)
        val uncovered = fixtures.paymentRequest(id = "other", counterparty = fixtures.otherCounterpartyKey)

        val handled = sut.processIncomingRequests(listOf(fixtures.paymentRequest(), uncovered))

        assertTrue(handled)
        verify(executor).autoPay(eq(fixtures.paymentRequest()), eq(sut.entries.value.single().allowances), eq(identity))
        verify(executor, never()).autoPay(eq(uncovered), any(), any())
        verify(sdk, times(2)).listAllowances(any())
    }

    @Test
    fun `covered request waits while the node's channels reconnect`() = test {
        records = listOf(fixtures.record())
        sut.activate(identity)
        val reconnecting = mock<ChannelDetails> { on { isUsable } doReturn false }
        lightningState.update {
            it.copy(nodeLifecycleState = NodeLifecycleState.Running, channels = persistentListOf(reconnecting))
        }
        val request = fixtures.paymentRequest()

        assertFalse(sut.processIncomingRequests(listOf(request)))
        assertTrue(sut.isAutomaticallyHandling(request))
        verify(executor, never()).autoPay(any(), any(), any())
    }

    @Test
    fun `nothing is processed without an identity`() = test {
        assertFalse(sut.processIncomingRequests(listOf(fixtures.paymentRequest())))
        verify(executor, never()).autoPay(any(), any(), any())
    }

    // endregion

    // region Events

    @Test
    fun `node payment events settle allowance attempts`() = test {
        nodeEvents.emit(
            Event.PaymentSuccessful(
                paymentId = "payment-id",
                paymentHash = PAYMENT_HASH,
                paymentPreimage = "00".repeat(32),
                feePaidMsat = 10uL,
            ),
        )
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = PAYMENT_HASH,
                paymentHash = null,
                reason = PaymentFailureReason.RETRIES_EXHAUSTED,
            ),
        )

        verify(executor).lightningPaymentSettled(PAYMENT_HASH, true)
        verify(executor).lightningPaymentSettled(PAYMENT_HASH, false)
    }

    @Test
    fun `automatic payment is attributed to the contact's activity`() = test {
        records = listOf(fixtures.record())
        sut.activate(identity)
        localState = PaykitAllowanceLocalState(
            journal = listOf(
                journalEntry("a", fixtures.paymentRequest(), ALLOWANCE_ID, 1_000uL, Stage.SUCCEEDED),
            ),
        )

        executorEvents.emit(PaykitAllowanceEvent.PaidAutomatically(fixtures.counterpartyKey, 1_000uL, TXID))

        verify(activityRepo).setContact(eq(fixtures.counterpartyKey), eq(TXID), any(), any())
        assertEquals(mapOf(ALLOWANCE_ID to 1_000uL), sut.autoPaidSats.value)
    }

    @Test
    fun `running node settles open lightning attempts`() = test {
        lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

        verify(executor).settleOpenLightningAttempts()
    }

    // endregion

    // region Helpers

    private fun nodeReady() = lightningState.update { it.copy(nodeLifecycleState = NodeLifecycleState.Running) }

    private fun group(vararg allowanceIds: String) = PaykitAllowanceLocalState.Group(
        id = "group-1",
        counterparty = fixtures.counterpartyKey,
        limits = fixtures.limits,
        allowanceIds = allowanceIds.toList(),
        createdAtMillis = fixtures.now.toEpochMilliseconds(),
    )

    private fun receivedProposal() = fixtures.record(
        localRole = AllowanceLocalRole.ALLOWEE,
        state = AllowanceLifecycleState.PROPOSED,
        proposedByMe = false,
    )

    private fun receivedAsk() = fixtures.record(
        localRole = AllowanceLocalRole.ALLOWER,
        state = AllowanceLifecycleState.PROPOSED,
        proposedByMe = false,
    )

    @Suppress("LongParameterList")
    private fun journalEntry(
        attemptId: String,
        request: PaykitPaymentRequest,
        allowanceId: String?,
        amountSats: ULong,
        stage: Stage,
        isAutomatic: Boolean = true,
    ) = PaykitAllowanceLocalState.JournalEntry(
        attemptId = attemptId,
        isAutomatic = isAutomatic,
        requestId = request.id,
        allowanceId = allowanceId,
        amountSats = amountSats,
        paymentEndpointIdentifier = fixtures.lightningIdentifier,
        stage = stage,
        createdAtMillis = fixtures.now.toEpochMilliseconds(),
    )

    private fun linkedPeer(
        publicKey: String,
        state: LinkedPeerState = LinkedPeerState.LINKED,
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

    // endregion
}
