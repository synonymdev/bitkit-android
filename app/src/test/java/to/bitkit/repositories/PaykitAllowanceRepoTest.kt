package to.bitkit.repositories

import org.mockito.kotlin.doReturn
import kotlinx.collections.immutable.persistentListOf
import org.lightningdevkit.ldknode.ChannelDetails
import com.synonym.paykit.AllowanceHistoryStatus
import com.synonym.paykit.AllowanceLifecycleState
import com.synonym.paykit.AllowanceLocalRole
import com.synonym.paykit.AllowanceRecord
import com.synonym.paykit.LinkedPeerRecord
import com.synonym.paykit.LinkedPeerState
import com.synonym.paykit.OutboundPrivateSendReport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.PaykitAllowanceFixtures.SERVER_ALLOWANCE_ID
import to.bitkit.repositories.PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID
import to.bitkit.repositories.PaykitAllowanceLocalState.Stage
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
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
        whenever(sdk.processOutboundPrivateMessages(any(), any()))
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
    fun `entries group one grant across links with the wallet link as primary`() = test {
        records = listOf(
            fixtures.record(allowanceId = SERVER_ALLOWANCE_ID, receiverPath = PaykitReceiverPaths.SERVER),
            fixtures.record(allowanceId = WALLET_ALLOWANCE_ID),
            fixtures.record(allowanceId = "allowance-other", counterparty = fixtures.otherCounterpartyKey),
            fixtures.record(allowanceId = "allowance-invalid", historyStatus = AllowanceHistoryStatus.INVALID),
        )
        localState = PaykitAllowanceLocalState(groups = listOf(group(WALLET_ALLOWANCE_ID, SERVER_ALLOWANCE_ID)))

        sut.activate(identity)

        val entries = sut.entries.value
        assertEquals(listOf("group-1", "allowance-other"), entries.map { it.id })
        val grouped = entries.first()
        assertEquals(listOf(SERVER_ALLOWANCE_ID, WALLET_ALLOWANCE_ID), grouped.allowances.map { it.allowanceId })
        assertEquals(WALLET_ALLOWANCE_ID, grouped.primary.allowanceId)
        assertEquals(fixtures.limits, grouped.limits)
        assertEquals(fixtures.counterpartyKey, grouped.counterparty)
        assertEquals(PaykitAllowance.Role.ALLOWER, grouped.role)
        assertEquals(5_000uL, grouped.perPaymentMaxSats)
        assertEquals(50_000uL, grouped.monthlyLimitSats)
        assertEquals(PaykitAllowance.Status.ACTIVE, grouped.status(fixtures.now))
        assertNull(entries.last().limits)
        assertEquals(WALLET_ALLOWANCE_ID, sut.entry("group-1")?.primary?.allowanceId)
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
        records = listOf(
            fixtures.record(allowanceId = SERVER_ALLOWANCE_ID, receiverPath = PaykitReceiverPaths.SERVER),
            fixtures.record(allowanceId = WALLET_ALLOWANCE_ID),
        )
        val paid = fixtures.paymentRequest(id = "paid")
        val failed = fixtures.paymentRequest(id = "failed")
        val serverPaid = fixtures.paymentRequest(id = "server")
        localState = PaykitAllowanceLocalState(
            groups = listOf(group(WALLET_ALLOWANCE_ID, SERVER_ALLOWANCE_ID)),
            journal = listOf(
                journalEntry("a", paid, WALLET_ALLOWANCE_ID, 1_000uL, Stage.SUCCEEDED),
                journalEntry("b", serverPaid, SERVER_ALLOWANCE_ID, 2_000uL, Stage.SUCCEEDED),
                journalEntry("c", failed, WALLET_ALLOWANCE_ID, 5_000uL, Stage.FAILED),
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
    fun `coverage needs an active allower allowance on the request's exact link`() = test {
        records = listOf(fixtures.record(terms = fixtures.terms(expiresAt = (fixtures.now + 30.days).toString())))

        sut.activate(identity)

        assertTrue(sut.coversRequest(fixtures.paymentRequest()))
        assertFalse(sut.coversRequest(fixtures.paymentRequest(counterparty = fixtures.otherCounterpartyKey)))
        assertFalse(sut.coversRequest(fixtures.paymentRequest(receiverPath = PaykitReceiverPaths.SERVER)))

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
    fun `propose sends the same terms on every supported linked path and records one entry`() = test {
        whenever(sdk.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(fixtures.counterpartyKey, PaykitReceiverPaths.SERVER),
                linkedPeer(fixtures.counterpartyKey, "a/other"),
                linkedPeer(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET),
                linkedPeer(fixtures.otherCounterpartyKey, PaykitReceiverPaths.WALLET),
                linkedPeer(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET, LinkedPeerState.LINKING),
            ),
        )
        whenever(sdk.proposeAllowance(any(), any(), any(), any())).doSuspendableAnswer {
            val receiverPath = it.getArgument<String>(1)
            val isWallet = receiverPath == PaykitReceiverPaths.WALLET
            val allowanceId = if (isWallet) WALLET_ALLOWANCE_ID else SERVER_ALLOWANCE_ID
            fixtures.record(
                allowanceId = allowanceId,
                receiverPath = receiverPath,
                state = AllowanceLifecycleState.PROPOSED,
                terms = terms,
            ).also { record -> records = records + record }
        }
        sut.activate(identity)

        val result = sut.propose(fixtures.counterpartyKey, fixtures.limits)

        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        val allower = AllowanceLocalRole.ALLOWER
        verify(sdk).proposeAllowance(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET, allower, terms)
        verify(sdk).proposeAllowance(fixtures.counterpartyKey, PaykitReceiverPaths.SERVER, allower, terms)
        verify(sdk, never()).proposeAllowance(any(), eq("a/other"), any(), any())
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET)
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey, PaykitReceiverPaths.SERVER)
        assertEquals(
            listOf(fixtures.septemberAnchor to PaykitAllowanceRepo.allowedPaymentEndpointIdentifiers()),
            proposedTerms,
        )
        val group = localState.groups.single()
        assertEquals(listOf(WALLET_ALLOWANCE_ID, SERVER_ALLOWANCE_ID), group.allowanceIds)
        assertEquals(fixtures.limits, group.limits)
        assertEquals(fixtures.counterpartyKey, group.counterparty)
        val entry = sut.entries.value.single()
        assertEquals(group.id, entry.id)
        assertEquals(fixtures.limits, entry.limits)
        assertEquals(PaykitAllowance.Status.AWAITING_ANSWER, entry.status(fixtures.now))
    }

    @Test
    fun `propose keeps the wallet proposal when the server link fails`() = test {
        whenever(sdk.linkedPeers()).thenReturn(
            listOf(
                linkedPeer(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET),
                linkedPeer(fixtures.counterpartyKey, PaykitReceiverPaths.SERVER),
            ),
        )
        whenever(sdk.proposeAllowance(any(), any(), any(), any())).doSuspendableAnswer {
            if (it.getArgument<String>(1) == PaykitReceiverPaths.SERVER) throw TestRepoError("server link")
            fixtures.record(state = AllowanceLifecycleState.PROPOSED, terms = terms)
        }
        sut.activate(identity)

        assertTrue(sut.propose(fixtures.counterpartyKey, fixtures.limits).isSuccess)

        assertEquals(listOf(WALLET_ALLOWANCE_ID), localState.groups.single().allowanceIds)
    }

    @Test
    fun `propose fails when the contact has no linked receiver`() = test {
        whenever(sdk.linkedPeers())
            .thenReturn(
                listOf(linkedPeer(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET, LinkedPeerState.LINKING)),
            )
        sut.activate(identity)

        val result = sut.propose(fixtures.counterpartyKey, fixtures.limits)

        assertIs<PaykitAllowanceError.ContactNotLinked>(result.exceptionOrNull())
        verify(sdk, never()).proposeAllowance(any(), any(), any(), any())
        assertTrue(localState.groups.isEmpty())
    }

    @Test
    fun `received proposal is presented once and accepting answers it`() = test {
        val proposalRecord = receivedProposal()
        records = listOf(proposalRecord)
        whenever(sdk.acceptAllowance(any(), any(), any())).thenReturn(proposalRecord)
        sut.activate(identity)

        val proposal = checkNotNull(sut.proposalForPresentation())
        assertEquals(WALLET_ALLOWANCE_ID, proposal.id)
        sut.markProposalPresented(proposal.id)
        assertNull(sut.proposalForPresentation())
        assertEquals(setOf(WALLET_ALLOWANCE_ID), localState.presentedProposalIds)

        assertTrue(sut.accept(proposal.id).isSuccess)

        verify(sdk).acceptAllowance(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET, WALLET_ALLOWANCE_ID)
        verify(sdk).processOutboundPrivateMessages(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET)
    }

    @Test
    fun `answering fails when nothing in the entry awaits an answer`() = test {
        records = listOf(fixtures.record())
        sut.activate(identity)

        assertIs<PaykitAllowanceError.Unavailable>(sut.reject(WALLET_ALLOWANCE_ID).exceptionOrNull())
        assertIs<PaykitAllowanceError.Unavailable>(sut.accept("missing").exceptionOrNull())
        verify(sdk, never()).rejectAllowance(any(), any(), any())
    }

    @Test
    fun `ending an entry ends every endable allowance`() = test {
        records = listOf(
            fixtures.record(allowanceId = SERVER_ALLOWANCE_ID, receiverPath = PaykitReceiverPaths.SERVER),
            fixtures.record(allowanceId = WALLET_ALLOWANCE_ID),
        )
        localState = PaykitAllowanceLocalState(groups = listOf(group(WALLET_ALLOWANCE_ID, SERVER_ALLOWANCE_ID)))
        whenever(sdk.endAllowance(any(), any(), any())).thenReturn(records.last())
        sut.activate(identity)

        assertTrue(sut.end("group-1").isSuccess)

        verify(sdk).endAllowance(fixtures.counterpartyKey, PaykitReceiverPaths.WALLET, WALLET_ALLOWANCE_ID)
        verify(sdk).endAllowance(fixtures.counterpartyKey, PaykitReceiverPaths.SERVER, SERVER_ALLOWANCE_ID)
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

        records = listOf(fixtures.record(), fixtures.record(allowanceId = SERVER_ALLOWANCE_ID))
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
                journalEntry("a", fixtures.paymentRequest(), WALLET_ALLOWANCE_ID, 1_000uL, Stage.SUCCEEDED),
            ),
        )

        executorEvents.emit(PaykitAllowanceEvent.PaidAutomatically(fixtures.counterpartyKey, 1_000uL, TXID))

        verify(activityRepo).setContact(eq(fixtures.counterpartyKey), eq(TXID), any(), any())
        assertEquals(mapOf(WALLET_ALLOWANCE_ID to 1_000uL), sut.autoPaidSats.value)
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
        receiverPath: String,
        state: LinkedPeerState = LinkedPeerState.LINKED,
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

    // endregion
}

private class TestRepoError(message: String) : AppError(message)
