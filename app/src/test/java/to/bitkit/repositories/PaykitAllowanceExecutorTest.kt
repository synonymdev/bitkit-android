package to.bitkit.repositories

import com.synonym.paykit.AllowanceAccountingBlock
import com.synonym.paykit.AllowanceAccountingReconciliation
import com.synonym.paykit.AllowanceAccountingState
import com.synonym.paykit.AllowanceAssociationRecord
import com.synonym.paykit.AllowanceAssociationRevision
import com.synonym.paykit.AllowanceCandidate
import com.synonym.paykit.AllowanceLifecycleState
import com.synonym.paykit.AllowanceSelectionInput
import com.synonym.paykit.ConversionRate
import com.synonym.paykit.OutboundPrivateSendReport
import com.synonym.paykit.PaymentAccountingScope
import com.synonym.paykit.PaymentAttemptDecision
import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PaymentDisposition
import com.synonym.paykit.PaymentExecutionChecks
import com.synonym.paykit.PaymentExecutionMode
import com.synonym.paykit.PaymentExecutionStatus
import com.synonym.paykit.PaymentOccurrence
import com.synonym.paykit.PaymentOccurrenceKey
import com.synonym.paykit.PaymentOccurrenceRecord
import com.synonym.paykit.PaymentOutcome
import com.synonym.paykit.PaymentOutcomeReport
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestScope
import com.synonym.paykit.PrivateStreamIntakeReport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.repositories.PaykitAllowanceFixtures.ALLOWANCE_ID
import to.bitkit.repositories.PaykitAllowanceLocalState.JournalEntry
import to.bitkit.repositories.PaykitAllowanceLocalState.Stage
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.SubscriptionClockOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Suppress("LargeClass")
class PaykitAllowanceExecutorTest : BaseUnitTest() {
    companion object {
        private const val AUTOMATIC_ATTEMPT_ID = "attempt-1"
        private const val MANUAL_ATTEMPT_ID = "manual-1"
        private const val INVOICE = "lnbcrt10u1allowancetestinvoice"
        private const val ONCHAIN_ADDRESS = "bcrt1qallowancetestaddress"
        private const val PAYMENT_APP_ID = "bitkit"
        private val PAYMENT_HASH = "ab".repeat(32)
    }

    private val fixtures = PaykitAllowanceFixtures
    private val identity = fixtures.identityKey
    private val sdk = mock<PaykitSdkService>()
    private val payer = mock<PaykitAllowancePayer>()
    private val keychain = mock<Keychain>()
    private var storedState: String? = null
    private var failSave: (String) -> Boolean = { false }
    private var now = fixtures.now
    private val clock = object : Clock {
        override fun now(): Instant = this@PaykitAllowanceExecutorTest.now
    }

    private var accountingState: AllowanceAccountingState? = fixtures.accountingState()
    private var candidates = listOf(candidate())
    private var automaticReservation: PaymentAttemptDecision? = null
    private var manualReservation: PaymentAttemptDecision? = null
    private var beginDecision: PaymentAttemptDecision? = null
    private val evaluatedTrustedTimes = mutableListOf<String>()
    private val selections = mutableListOf<AllowanceSelectionInput>()
    private val acceptedChecks = mutableListOf<PaymentExecutionChecks>()
    private val reservedAssociationRevisions = mutableListOf<ULong>()
    private val recordedOutcomes = mutableListOf<PaymentOutcomeReport>()
    private val reconciliations = mutableListOf<AllowanceAccountingReconciliation>()
    private val nodeStatuses = mutableMapOf<String, PaymentStatus>()
    private val events = mutableListOf<PaykitAllowanceEvent>()
    private lateinit var sut: PaykitAllowanceExecutor

    @Before
    fun setUp() = test {
        stubLedger()
        stubAttempts()
        stubPayer()
        val amount = fixtures.accountingAmount("0.00001")
        sut = PaykitAllowanceExecutor(
            ioDispatcher = testDispatcher,
            paykitSdkService = sdk,
            store = PaykitAllowanceStore(keychain),
            payer = payer,
            clock = clock,
            accountingAmount = { _, _ -> amount },
        )
    }

    private suspend fun stubLedger() {
        whenever(keychain.loadString(any())).thenAnswer { storedState }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer {
            val state = it.getArgument<String>(1)
            if (failSave(state)) throw TestAllowanceError("save")
            storedState = state
        }

        whenever(sdk.allowanceAccountingState()).thenAnswer { accountingState }
        whenever(sdk.reconcileAllowanceAccounting(any())).doSuspendableAnswer {
            val reconciliation = it.getArgument<AllowanceAccountingReconciliation>(0)
            reconciliations += reconciliation
            AllowanceAccountingState(
                revision = (reconciliation.expectedRevision ?: 0uL) + 1uL,
                epoch = accountingState?.epoch ?: "epoch-1",
                requiresReconciliation = false,
                history = reconciliation.history,
            ).also { reconciled -> accountingState = reconciled }
        }
        whenever(sdk.evaluateAllowanceCandidates(any(), any())).doSuspendableAnswer {
            evaluatedTrustedTimes += it.getArgument<String>(1)
            candidates
        }
        whenever(sdk.acceptPaymentRequestAutomatically(any(), any(), any())).doSuspendableAnswer {
            val selection = it.getArgument<AllowanceSelectionInput>(1)
            selections += selection
            acceptedChecks += it.getArgument<PaymentExecutionChecks>(2)
            AllowanceAssociationRecord(
                request = fixtures.accountingScope(it.getArgument<PaymentRequestScope>(0).paymentRequestId),
                revisions = listOf(
                    AllowanceAssociationRevision(
                        revision = 1uL,
                        allowanceId = selection.allowanceId,
                        effectiveFrom = null,
                        authorizationId = null,
                        authorizedAt = selection.trustedTime,
                    ),
                ),
            )
        }
        whenever(sdk.claimPaymentRequestForExecution(any(), any())).thenReturn(mock<PaymentRequestRecord>())
        whenever(sdk.processOutboundPrivateMessages(any()))
            .thenReturn(OutboundPrivateSendReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        whenever(sdk.receivePrivateMessages(any()))
            .thenReturn(PrivateStreamIntakeReport(null, emptyList(), emptyList()))
    }

    private suspend fun stubAttempts() {
        val preparedAutomatic = PaymentAttemptDecision.Ready(
            fixtures.attemptRecord(AUTOMATIC_ATTEMPT_ID, status = PaymentExecutionStatus.PREPARED),
        )
        val submittedAutomatic = PaymentAttemptDecision.Ready(
            fixtures.attemptRecord(AUTOMATIC_ATTEMPT_ID, status = PaymentExecutionStatus.SUBMITTED),
        )
        val preparedManual = PaymentAttemptDecision.Ready(
            fixtures.attemptRecord(
                MANUAL_ATTEMPT_ID,
                mode = PaymentExecutionMode.MANUAL,
                allowanceId = null,
                status = PaymentExecutionStatus.PREPARED,
            ),
        )
        val submittedManual = PaymentAttemptDecision.Ready(
            fixtures.attemptRecord(
                MANUAL_ATTEMPT_ID,
                mode = PaymentExecutionMode.MANUAL,
                allowanceId = null,
                status = PaymentExecutionStatus.SUBMITTED,
            ),
        )
        val recordedAttempt = fixtures.attemptRecord(AUTOMATIC_ATTEMPT_ID, status = PaymentExecutionStatus.SUCCEEDED)

        whenever(sdk.reserveAutomaticPayment(any(), any(), any())).doSuspendableAnswer {
            reservedAssociationRevisions += (it.arguments[1] as Long).toULong()
            automaticReservation ?: preparedAutomatic
        }
        whenever(sdk.reserveManualPayment(any(), any())).doSuspendableAnswer { manualReservation ?: preparedManual }
        whenever(sdk.beginPaymentExecution(any(), any())).doSuspendableAnswer {
            beginDecision ?: if (it.getArgument<String>(0) == MANUAL_ATTEMPT_ID) submittedManual else submittedAutomatic
        }
        whenever(sdk.recordPaymentOutcome(any())).doSuspendableAnswer {
            recordedOutcomes += it.getArgument<PaymentOutcomeReport>(0)
            recordedAttempt
        }
        whenever(sdk.markPaymentManualOnly(any())).doSuspendableAnswer {
            PaymentOccurrenceRecord(
                key = PaymentOccurrenceKey(fixtures.accountingScope("manual-only"), billingPeriod = null),
                disposition = PaymentDisposition.ManualOnly,
                allowanceId = null,
                associationRevision = null,
                attempts = emptyList(),
            )
        }
    }

    private suspend fun stubPayer() {
        whenever(payer.resolve(any(), any())).thenReturn(Result.success(lightningPayment()))
        whenever(payer.acceptOnThisInstall<AllowanceAssociationRecord>(any(), any())).doSuspendableAnswer {
            runCatching { it.getArgument<suspend () -> AllowanceAssociationRecord>(1).invoke() }
        }
        whenever(payer.consumePaymentList(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(payer.releasePaymentList(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(payer.prepareProof(any(), any(), any(), anyOrNull())).thenReturn(Result.success(Unit))
        whenever(payer.associateLightningPayment(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(payer.payLightning(any(), anyOrNull(), anyOrNull(), any())).thenReturn(Result.success(PAYMENT_HASH))
        whenever(payer.failLightningPayment(any(), any())).thenReturn(true)
        whenever(payer.lightningPaymentStatus(any())).thenAnswer { nodeStatuses[it.getArgument<String>(0)] }
    }

    // region Admission

    @Test
    fun `uncovered request never reaches the SDK`() = test {
        val request = fixtures.paymentRequest()
        val uncovering = listOf(
            emptyList(),
            listOf(fixtures.allowance(role = PaykitAllowance.Role.ALLOWEE)),
            listOf(fixtures.allowance(state = AllowanceLifecycleState.PROPOSED)),
            listOf(fixtures.allowance(state = AllowanceLifecycleState.ENDED)),
            listOf(fixtures.allowance(counterparty = fixtures.otherCounterpartyKey)),
        )

        for (allowances in uncovering) {
            assertEquals(PaykitAllowanceAutoPayResult.NOT_COVERED, sut.autoPay(request, allowances, identity))
        }
        verifyNoInteractions(sdk, payer, keychain)
    }

    @Test
    fun `covered lightning request runs admission in order and hands off to the node`() = test {
        val request = fixtures.paymentRequest()

        val result = sut.autoPay(request, listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.STARTED, result)
        val order = inOrder(sdk, payer)
        order.verify(sdk).evaluateAllowanceCandidates(any(), any())
        order.verify(payer).resolve(eq(request), eq(listOf(fixtures.lightningIdentifier)))
        order.verify(payer).acceptOnThisInstall<AllowanceAssociationRecord>(eq(request), any())
        order.verify(sdk).claimPaymentRequestForExecution(request.counterparty, request.paymentRequestId)
        order.verify(sdk).acceptPaymentRequestAutomatically(any(), any(), any())
        order.verify(sdk).reserveAutomaticPayment(any(), any(), any())
        order.verify(sdk).receivePrivateMessages(request.counterparty)
        order.verify(sdk).beginPaymentExecution(eq(AUTOMATIC_ATTEMPT_ID), any())
        order.verify(payer).consumePaymentList(request.counterparty, lightningPayment().context, AUTOMATIC_ATTEMPT_ID)
        order.verify(payer).prepareProof(request, fixtures.lightningIdentifier, PAYMENT_APP_ID, ALLOWANCE_ID)
        order.verify(payer).associateLightningPayment(
            request,
            PAYMENT_HASH,
            fixtures.lightningIdentifier,
            PAYMENT_APP_ID,
        )
        order.verify(payer).payLightning(eq(INVOICE), isNull(), anyOrNull(), any())
        verify(sdk, never()).recordPaymentOutcome(any())
        assertEquals(listOf(PaykitAllowanceTime.format(fixtures.now)), evaluatedTrustedTimes)
        assertEquals(listOf(ALLOWANCE_ID), selections.map { it.allowanceId })
        assertEquals(listOf(fixtures.lightningIdentifier), acceptedChecks.map { it.paymentEndpointIdentifier })
        assertEquals(listOf(1uL), reservedAssociationRevisions)

        val entry = sut.localState(identity).journal.single()
        assertEquals(AUTOMATIC_ATTEMPT_ID, entry.attemptId)
        assertEquals(Stage.SENT, entry.stage)
        assertTrue(entry.isAutomatic)
        assertEquals(request.id, entry.requestId)
        assertEquals(ALLOWANCE_ID, entry.allowanceId)
        assertEquals(1_000uL, entry.amountSats)
        assertEquals(PAYMENT_HASH, entry.paymentHash)
        assertEquals(fixtures.lightningIdentifier, entry.paymentEndpointIdentifier)
        assertFalse(sut.isHandling(request.id))
    }

    @Test
    fun `automatic lightning payment caps routing fees at one percent of the request`() = test {
        sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        // A 1 000 sat request may spend at most 10 sat (10 000 msat) on routing fees.
        verify(payer).payLightning(eq(INVOICE), isNull(), anyOrNull(), eq(10_000uL))
    }

    @Test
    fun `node settlement that arrives before the send call returns is kept`() = test {
        sut.activate(identity)
        whenever(payer.payLightning(any(), anyOrNull(), anyOrNull(), any())).doSuspendableAnswer {
            sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = true)
            Result.success(PAYMENT_HASH)
        }

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.STARTED, result)
        assertEquals(Stage.SUCCEEDED, sut.localState(identity).journal.single().stage)
        assertEquals(listOf(PaymentOutcomeReport(AUTOMATIC_ATTEMPT_ID, PaymentOutcome.SUCCEEDED)), recordedOutcomes)
    }

    @Test
    fun `amount-less invoice is paid exactly the requested amount`() = test {
        whenever(payer.resolve(any(), any()))
            .thenReturn(Result.success(lightningPayment().copy(lightningInvoiceHasAmount = false)))

        sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        verify(payer).payLightning(eq(INVOICE), eq(1_000uL), anyOrNull(), any())
    }

    @Test
    fun `request waits without acceptance while the payee's payment list is pending`() = test {
        whenever(payer.resolve(any(), any())).thenReturn(Result.failure(PaykitAllowanceError.PaymentListPending))

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.DEFERRED, result)
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
    }

    @Test
    fun `blocked candidate stays manual without acceptance`() = test {
        candidates = listOf(candidate(blocked = AllowanceAccountingBlock.SharedRule("amount_outside_range")))

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        verify(sdk).evaluateAllowanceCandidates(any(), any())
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
        verify(sdk, never()).reserveAutomaticPayment(any(), any(), any())
        verifyNoInteractions(payer)
    }

    @Test
    fun `allowance without per-payment and monthly limits never pays automatically`() = test {
        val request = fixtures.paymentRequest(amountSats = 50_000_000uL)
        val unbounded = fixtures.allowance(perPaymentMaxSats = null, monthlyLimitSats = null, monthlyAnchor = null)

        val result = sut.autoPay(request, listOf(unbounded), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `automatic lightning payment carries the request's submission deadline`() = test {
        val request = fixtures.paymentRequest()

        sut.autoPay(request, listOf(fixtures.allowance()), identity)

        verify(payer).payLightning(any(), anyOrNull(), eq(request.submissionDeadline(null)), any())
    }

    @Test
    fun `request above the per-payment limit stays manual and notifies once`() = test {
        collectEvents()
        candidates = listOf(candidate(blocked = AllowanceAccountingBlock.SharedRule("amount_outside_range")))
        val request = fixtures.paymentRequest()
        val allowances = listOf(fixtures.allowance(perPaymentMaxSats = 500uL))

        val first = sut.autoPay(request, allowances, identity)
        val second = sut.autoPay(request, allowances, identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, first)
        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, second)
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
        verifyNoInteractions(payer)
        assertEquals(
            listOf<PaykitAllowanceEvent>(PaykitAllowanceEvent.LimitReached(fixtures.counterpartyKey, 1_000uL)),
            events
        )
    }

    @Test
    fun `over the monthly cap stays manual and notifies once`() = test {
        collectEvents()
        accountingState = stateNearTheMonthlyCap()
        val request = fixtures.paymentRequest()

        val first = sut.autoPay(request, listOf(fixtures.allowance()), identity)
        val second = sut.autoPay(request, listOf(fixtures.allowance()), identity)
        val limitReached: PaykitAllowanceEvent = PaykitAllowanceEvent.LimitReached(fixtures.counterpartyKey, 1_000uL)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, first)
        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, second)
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
        verify(sdk, never()).reserveAutomaticPayment(any(), any(), any())
        verifyNoInteractions(payer)
        assertEquals(listOf(limitReached), events)
    }

    @Test
    fun `another contact's payments under the same allowance id never fill this contact's cap`() = test {
        collectEvents()
        val otherScope = PaymentAccountingScope(
            localPublicKey = fixtures.identityKey,
            counterparty = fixtures.otherCounterpartyKey,
            paymentRequestId = "other-paid",
        )
        accountingState = fixtures.accountingState(
            occurrences = listOf(
                PaymentOccurrenceRecord(
                    key = PaymentOccurrenceKey(request = otherScope, billingPeriod = null),
                    disposition = PaymentDisposition.Automatic,
                    allowanceId = ALLOWANCE_ID,
                    associationRevision = 1uL,
                    attempts = listOf(paidAttempt("other-paid", "0.000495", "2026-09-05T10:00:00Z")),
                ),
            ),
        )
        val allowances = listOf(
            fixtures.allowance(counterparty = fixtures.otherCounterpartyKey),
            fixtures.allowance(),
        )

        val result = sut.autoPay(fixtures.paymentRequest(), allowances, identity)

        assertEquals(PaykitAllowanceAutoPayResult.STARTED, result)
        assertTrue(events.none { it is PaykitAllowanceEvent.LimitReached })
    }

    @Test
    fun `execution claim held by another app keeps the request manual before any acceptance`() = test {
        whenever(sdk.claimPaymentRequestForExecution(any(), any())).doSuspendableAnswer {
            error("Another app owns the execution claim")
        }

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
        verify(sdk, never()).reserveAutomaticPayment(any(), any(), any())
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `blocked reservation marks the payment manual only`() = test {
        collectEvents()
        automaticReservation = PaymentAttemptDecision.Blocked(
            AllowanceAccountingBlock.SharedRule("period_amount_limit"),
        )
        val request = fixtures.paymentRequest()

        val result = sut.autoPay(request, listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        val limitReached: PaykitAllowanceEvent = PaykitAllowanceEvent.LimitReached(fixtures.counterpartyKey, 1_000uL)
        val scope = PaymentRequestScope(request.counterparty, request.paymentRequestId)
        verify(sdk).markPaymentManualOnly(PaymentOccurrence(scope, billingPeriod = null))
        verify(sdk, never()).beginPaymentExecution(any(), any())
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
        assertEquals(emptyList(), sut.localState(identity).journal)
        assertEquals(listOf(limitReached), events)
    }

    @Test
    fun `a bitcoin request that also offers a USDT rail is paid automatically`() = test {
        val request = fixtures.paymentRequest().copy(
            pricing = PaykitRequestPricing(PaymentConversion.Fixed(listOf(ConversionRate("usdt-arbitrum", "100000")))),
        )

        val result = sut.autoPay(request, listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.STARTED, result)
        verify(payer).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `a reservation blocked for another reason keeps the request manual without Limit Reached`() = test {
        collectEvents()
        automaticReservation = PaymentAttemptDecision.Blocked(AllowanceAccountingBlock.SharedRule("not_active"))

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        assertTrue(events.none { it is PaykitAllowanceEvent.LimitReached })
    }

    @Test
    fun `a failing begin call releases the reservation before anything is sent`() = test {
        whenever(sdk.beginPaymentExecution(any(), any())).doSuspendableAnswer { throw TestAllowanceError("begin") }

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        assertEquals(listOf(PaymentOutcomeReport(AUTOMATIC_ATTEMPT_ID, PaymentOutcome.FAILED)), recordedOutcomes)
        verify(payer, never()).consumePaymentList(any(), any(), any())
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `blocked begin records a failed outcome`() = test {
        beginDecision = PaymentAttemptDecision.Blocked(AllowanceAccountingBlock.ManualOnly)

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        assertEquals(listOf(PaymentOutcomeReport(AUTOMATIC_ATTEMPT_ID, PaymentOutcome.FAILED)), recordedOutcomes)
        assertEquals(listOf(Stage.FAILED), sut.localState(identity).journal.map { it.stage })
        verify(payer, never()).consumePaymentList(any(), any(), any())
        verify(payer, never()).prepareProof(any(), any(), any(), anyOrNull())
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `failed proof preparation releases the attempt before any payment`() = test {
        whenever(payer.prepareProof(any(), any(), any(), anyOrNull()))
            .thenReturn(Result.failure(PaykitPaymentRequestError.RequestUnavailable))
        val request = fixtures.paymentRequest()

        val result = sut.autoPay(request, listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        verify(payer).cancelProofPreparation(request)
        verify(payer).releasePaymentList(eq(request.counterparty), any(), any())
        assertEquals(listOf(PaymentOutcomeReport(AUTOMATIC_ATTEMPT_ID, PaymentOutcome.FAILED)), recordedOutcomes)
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `unsaved journal stops the attempt before anything is consumed`() = test {
        failSave = { true }

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        assertEquals(listOf(PaymentOutcomeReport(AUTOMATIC_ATTEMPT_ID, PaymentOutcome.FAILED)), recordedOutcomes)
        verify(sdk, never()).beginPaymentExecution(any(), any())
        verify(payer, never()).consumePaymentList(any(), any(), any())
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `unsaved sending stage releases the payment list and never sends`() = test {
        failSave = { "SENDING" in it }

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        assertEquals(listOf(PaymentOutcomeReport(AUTOMATIC_ATTEMPT_ID, PaymentOutcome.FAILED)), recordedOutcomes)
        verify(payer).discardLightningProof(PAYMENT_HASH)
        verify(payer).releasePaymentList(any(), any(), any())
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `lightning send failure releases only a payment that never left`() = test {
        whenever(
            payer.payLightning(any(), anyOrNull(), anyOrNull(), any())
        ).thenReturn(Result.failure(TestAllowanceError("send")))
        whenever(payer.failLightningPayment(any(), any())).thenReturn(true, false)

        val first = sut.autoPay(fixtures.paymentRequest(id = "request-1"), listOf(fixtures.allowance()), identity)
        val second = sut.autoPay(fixtures.paymentRequest(id = "request-2"), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, first)
        // An uncertain send stays with the node, whose payment event records the outcome.
        assertEquals(PaykitAllowanceAutoPayResult.STARTED, second)
        assertEquals(
            listOf(PaymentOutcome.FAILED, PaymentOutcome.UNKNOWN),
            recordedOutcomes.map { it.outcome },
        )
        assertEquals(Stage.UNKNOWN, sut.localState(identity).journal.single().stage)
    }

    @Test
    fun `a payment list given back by a failed send stays given back after the node's failure event`() = test {
        sut.activate(identity)
        whenever(
            payer.payLightning(any(), anyOrNull(), anyOrNull(), any())
        ).thenReturn(Result.failure(TestAllowanceError("send")))
        whenever(payer.failLightningPayment(any(), any())).thenReturn(true)

        // The node's failure event lands during the failed send's release; a repeated release is owner-checked.
        var isSettling = false
        whenever(payer.releasePaymentList(any(), any(), any())).doSuspendableAnswer {
            if (!isSettling) {
                isSettling = true
                sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)
            }
            Result.success(Unit)
        }

        sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        verify(payer, atLeastOnce()).releasePaymentList(any(), any(), eq(AUTOMATIC_ATTEMPT_ID))
        assertNull(sut.localState(identity).journal.single().paymentListVersion)
    }

    @Test
    fun `a payment list whose cleared version cannot be saved stays journaled for recovery`() = test {
        sut.activate(identity)
        seedJournal(
            journalEntry("attempt-1", fixtures.paymentRequest(), Stage.SENT, paymentHash = PAYMENT_HASH)
                .copy(paymentListVersion = 4uL, previousPaymentListVersion = 3uL),
        )
        failSave = { true }

        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)

        verify(payer, times(1)).releasePaymentList(any(), any(), any())
        assertEquals(4uL, sut.localState(identity).journal.single().paymentListVersion)
    }

    @Test
    fun `a payment list whose cleared claim could not be saved is given back by the next recovery`() = test {
        sut.activate(identity)
        seedJournal(
            journalEntry("attempt-1", fixtures.paymentRequest(), Stage.SENT, paymentHash = PAYMENT_HASH)
                .copy(paymentListVersion = 4uL, previousPaymentListVersion = 3uL),
        )
        var saves = 0
        // The stage save succeeds; only the claim clear that follows it fails.
        failSave = { ++saves == 2 }

        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)

        assertEquals(listOf(Stage.FAILED), sut.localState(identity).journal.map { it.stage })
        assertEquals(4uL, sut.localState(identity).journal.single().paymentListVersion)

        failSave = { false }
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("attempt-1", PaymentExecutionStatus.FAILED)),
        )
        sut.recover(identity)

        verify(payer, times(2)).releasePaymentList(any(), any(), any())
        assertNull(sut.localState(identity).journal.single().paymentListVersion)
    }

    @Test
    fun `recovery gives back a list a failed attempt never consumed only as that attempt`() = test {
        beginDecision = PaymentAttemptDecision.Blocked(AllowanceAccountingBlock.ManualOnly)
        sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)
        verify(payer, never()).consumePaymentList(any(), any(), any())

        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence(AUTOMATIC_ATTEMPT_ID, PaymentExecutionStatus.FAILED)),
        )
        sut.recover(identity)

        // The claim belongs to whoever consumed the list since, so this owner's release changes nothing.
        verify(payer, times(1)).releasePaymentList(any(), any(), eq(AUTOMATIC_ATTEMPT_ID))
    }

    @Test
    fun `a payment list whose release fails is given back by the next recovery`() = test {
        val request = fixtures.paymentRequest()
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("consumed", PaymentExecutionStatus.SUBMITTED)),
        )
        seedJournal(journalEntry("consumed", request, Stage.SUBMITTED).copy(paymentListVersion = 4uL))
        whenever(payer.releasePaymentList(any(), any(), any()))
            .thenReturn(Result.failure(TestAllowanceError("release")))

        sut.recover(identity)

        assertEquals(4uL, sut.localState(identity).journal.single().paymentListVersion)

        whenever(payer.releasePaymentList(any(), any(), any())).thenReturn(Result.success(Unit))
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("consumed", PaymentExecutionStatus.FAILED)),
        )
        sut.recover(identity)

        verify(payer, times(2)).releasePaymentList(any(), any(), eq("consumed"))
        assertNull(sut.localState(identity).journal.single().paymentListVersion)
    }

    @Test
    fun `recovery failing an attempt while its settlement records it gives the payment list back once`() = test {
        sut.activate(identity)
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("attempt-1", PaymentExecutionStatus.SUBMITTED)),
        )
        seedJournal(
            journalEntry("attempt-1", fixtures.paymentRequest(), Stage.SENT, paymentHash = PAYMENT_HASH)
                .copy(paymentListVersion = 4uL),
        )
        nodeStatuses[PAYMENT_HASH] = PaymentStatus.FAILED
        var isRecovering = false
        whenever(sdk.recordPaymentOutcome(any())).doSuspendableAnswer {
            recordedOutcomes += it.getArgument<PaymentOutcomeReport>(0)
            if (!isRecovering) {
                isRecovering = true
                sut.recover(identity)
            }
            fixtures.attemptRecord("attempt-1", status = PaymentExecutionStatus.FAILED)
        }

        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)

        verify(payer, times(1)).releasePaymentList(any(), any(), any())
    }

    @Test
    fun `a settlement during recovery's node lookup gives the payment list back once`() = test {
        sut.activate(identity)
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("attempt-1", PaymentExecutionStatus.SUBMITTED)),
        )
        seedJournal(
            journalEntry("attempt-1", fixtures.paymentRequest(), Stage.SENT, paymentHash = PAYMENT_HASH)
                .copy(paymentListVersion = 4uL),
        )
        var isSettling = false
        whenever(payer.lightningPaymentStatus(PAYMENT_HASH)).doSuspendableAnswer {
            if (!isSettling) {
                isSettling = true
                sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)
            }
            PaymentStatus.FAILED
        }

        sut.recover(identity)

        verify(payer, times(1)).releasePaymentList(any(), any(), any())
    }

    @Test
    fun `a payment list the node's failure event gave back during the send is not given back again`() = test {
        sut.activate(identity)
        whenever(payer.payLightning(any(), anyOrNull(), anyOrNull(), any())).doSuspendableAnswer {
            sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)
            Result.failure(TestAllowanceError("send"))
        }
        whenever(payer.failLightningPayment(any(), any())).thenReturn(true)

        sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        verify(payer, times(1)).releasePaymentList(any(), any(), any())
    }

    @Test
    fun `payee offering only an on-chain endpoint stays manual before any acceptance`() = test {
        whenever(payer.resolve(any(), any())).thenReturn(Result.success(onchainPayment()))

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
        verify(sdk, never()).reserveAutomaticPayment(any(), any(), any())
        verify(payer, never()).consumePaymentList(any(), any(), any())
    }

    @Test
    fun `request in another asset stays manual`() = test {
        val request = fixtures.paymentRequest().copy(amount = PaykitAmount(PaykitAsset.USDT, 1_000_000uL))

        val result = sut.autoPay(request, listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.MANUAL, result)
        verify(sdk, never()).evaluateAllowanceCandidates(any(), any())
    }

    @Test
    fun `lightning settlement records success for the journaled attempt`() = test {
        collectEvents()
        val request = fixtures.paymentRequest()
        seedJournal(journalEntry("attempt-1", request, Stage.SENT, paymentHash = PAYMENT_HASH))
        sut.activate(identity)

        sut.lightningPaymentSettled("f".repeat(64), succeeded = true)
        assertEquals(emptyList(), recordedOutcomes)

        sut.lightningPaymentSettled(PAYMENT_HASH.uppercase(), succeeded = true)
        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = true)

        assertEquals(listOf(PaymentOutcomeReport("attempt-1", PaymentOutcome.SUCCEEDED)), recordedOutcomes)
        assertEquals(listOf(Stage.SUCCEEDED), sut.localState(identity).journal.map { it.stage })
        assertEquals(listOf("attempt-1"), sut.succeededAutomaticPayments(identity).map { it.attemptId })
        assertEquals(
            listOf(PaykitAllowanceEvent.PaidAutomatically(fixtures.counterpartyKey, 1_000uL, PAYMENT_HASH)),
            events.filterIsInstance<PaykitAllowanceEvent.PaidAutomatically>(),
        )
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `an automatic payment keeps its lasting total once the journal trims it`() = test {
        sut.activate(identity)
        val request = fixtures.paymentRequest()
        seedJournal(journalEntry("attempt-1", request, Stage.SENT, paymentHash = PAYMENT_HASH))
        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = true)
        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = true)

        var manualAttempts = 0
        whenever(sdk.reserveManualPayment(any(), any())).doSuspendableAnswer {
            PaymentAttemptDecision.Ready(
                fixtures.attemptRecord(
                    "manual-${manualAttempts++}",
                    mode = PaymentExecutionMode.MANUAL,
                    allowanceId = null,
                    status = PaymentExecutionStatus.PREPARED,
                ),
            )
        }
        repeat(200) { sut.beginManualPayment(fixtures.paymentRequest(id = "manual-$it"), fixtures.onchainIdentifier) }

        val state = sut.localState(identity)
        assertTrue(state.journal.none { it.attemptId == "attempt-1" }, "The journal keeps only its newest entries")
        val key = PaykitAllowance.key(request.counterparty, ALLOWANCE_ID)
        assertEquals(mapOf(key to 1_000uL), state.autoPaidSatsByAllowance)
        assertEquals(setOf(request.id), state.autoPaidRequestIds)
    }

    @Test
    fun `a failed automatic lightning payment gives the payee's payment list back`() = test {
        sut.activate(identity)
        val request = fixtures.paymentRequest()
        seedJournal(
            journalEntry("attempt-1", request, Stage.SENT, paymentHash = PAYMENT_HASH)
                .copy(paymentListVersion = 4uL, previousPaymentListVersion = 3uL),
        )

        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = false)

        assertEquals(listOf(PaymentOutcomeReport("attempt-1", PaymentOutcome.FAILED)), recordedOutcomes)
        verify(payer).releasePaymentList(
            eq(request.counterparty),
            eq(PrivatePaykitPaymentContext(emptyMap(), paymentListVersion = 4uL, previousPaymentListVersion = 3uL)),
            any(),
        )
        assertTrue(sut.isAutomaticPayment(PAYMENT_HASH.uppercase()))
        assertFalse(sut.isAutomaticPayment("f".repeat(64)))
    }

    @Test
    fun `a later manual retry of the same invoice is not an automatic payment`() = test {
        sut.activate(identity)
        val request = fixtures.paymentRequest()
        seedJournal(
            journalEntry("automatic", request, Stage.FAILED, paymentHash = PAYMENT_HASH),
            journalEntry("manual", request, Stage.SENT, paymentHash = PAYMENT_HASH).copy(isAutomatic = false),
        )

        assertFalse(sut.isAutomaticPayment(PAYMENT_HASH))
    }

    @Test
    fun `automatic attempts journal the payment list they consume`() = test {
        sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        val entry = sut.localState(identity).journal.single()
        assertEquals(lightningPayment().context.paymentListVersion, entry.paymentListVersion)
    }

    @Test
    fun `open lightning attempts settle from the node's payment status`() = test {
        val paidHash = "1".repeat(64)
        val pendingHash = "2".repeat(64)
        val request = fixtures.paymentRequest()
        seedJournal(
            journalEntry("paid", request, Stage.UNKNOWN, paymentHash = paidHash),
            journalEntry("pending", request, Stage.SENT, paymentHash = pendingHash),
        )
        nodeStatuses[paidHash] = PaymentStatus.SUCCEEDED
        nodeStatuses[pendingHash] = PaymentStatus.PENDING
        sut.activate(identity)

        sut.settleOpenLightningAttempts()

        assertEquals(listOf(PaymentOutcomeReport("paid", PaymentOutcome.SUCCEEDED)), recordedOutcomes)
    }

    @Test
    fun `recovery reports an automatic payment that settled while the app was closed`() = test {
        collectEvents()
        val request = fixtures.paymentRequest()
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("sent-paid", PaymentExecutionStatus.SUBMITTED)),
        )
        seedJournal(journalEntry("sent-paid", request, Stage.SENT, paymentHash = PAYMENT_HASH))
        nodeStatuses[PAYMENT_HASH] = PaymentStatus.SUCCEEDED

        sut.recover(identity)

        assertEquals(
            listOf(PaykitAllowanceEvent.PaidAutomatically(fixtures.counterpartyKey, 1_000uL, PAYMENT_HASH)),
            events.filterIsInstance<PaykitAllowanceEvent.PaidAutomatically>(),
        )
    }

    @Test
    fun `recovery gives the payment list back when it fails an automatic attempt`() = test {
        val request = fixtures.paymentRequest()
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("consumed", PaymentExecutionStatus.SUBMITTED)),
        )
        seedJournal(journalEntry("consumed", request, Stage.SUBMITTED).copy(paymentListVersion = 4uL))

        sut.recover(identity)

        assertEquals(listOf(PaymentOutcomeReport("consumed", PaymentOutcome.FAILED)), recordedOutcomes)
        verify(payer).releasePaymentList(
            eq(request.counterparty),
            eq(PrivatePaykitPaymentContext(emptyMap(), paymentListVersion = 4uL)),
            any(),
        )
    }

    @Test
    fun `recovery syncs an attempt the ledger already settled and announces it once`() = test {
        collectEvents()
        val request = fixtures.paymentRequest()
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("settled", PaymentExecutionStatus.SUCCEEDED)),
        )
        seedJournal(journalEntry("settled", request, Stage.SENT, paymentHash = PAYMENT_HASH))
        sut.activate(identity)

        sut.recover(identity)

        // Recovery alone settles it, before any node event arrives.
        val paid = PaykitAllowanceEvent.PaidAutomatically(fixtures.counterpartyKey, 1_000uL, PAYMENT_HASH)
        assertEquals(listOf(Stage.SUCCEEDED), sut.localState(identity).journal.map { it.stage })
        assertEquals(listOf(paid), events.filterIsInstance<PaykitAllowanceEvent.PaidAutomatically>())

        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = true)

        assertEquals(listOf(paid), events.filterIsInstance<PaykitAllowanceEvent.PaidAutomatically>())
    }

    @Test
    fun `recovery syncs an attempt the ledger already failed and gives its payment list back`() = test {
        val request = fixtures.paymentRequest()
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence("failed", PaymentExecutionStatus.FAILED)),
        )
        seedJournal(
            journalEntry("failed", request, Stage.SENT, paymentHash = PAYMENT_HASH).copy(paymentListVersion = 5uL),
        )

        sut.recover(identity)

        assertEquals(listOf(Stage.FAILED), sut.localState(identity).journal.map { it.stage })
        verify(payer).releasePaymentList(
            eq(request.counterparty),
            eq(PrivatePaykitPaymentContext(emptyMap(), paymentListVersion = 5uL)),
            any(),
        )
    }

    @Test
    fun `local capacity check flags only requests that fit no allowance`() = test {
        collectEvents()
        accountingState = stateNearTheMonthlyCap()

        assertTrue(sut.classifyAboveLimit(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity))
        assertFalse(
            sut.classifyAboveLimit(fixtures.paymentRequest(amountSats = 1uL), listOf(fixtures.allowance()), identity)
        )
        assertFalse(sut.classifyAboveLimit(fixtures.paymentRequest(), emptyList(), identity))
        assertEquals(1, events.count { it is PaykitAllowanceEvent.LimitReached })
    }

    @Test
    fun `recovery resolves open attempts without paying again`() = test {
        val request = fixtures.paymentRequest()
        val paidHash = "1".repeat(64)
        val pendingHash = "2".repeat(64)
        accountingState = fixtures.accountingState(
            revision = 4uL,
            occurrences = listOf(
                occurrence("prepared", PaymentExecutionStatus.PREPARED),
                occurrence("old-epoch", PaymentExecutionStatus.PREPARED, epoch = "epoch-0"),
                occurrence("never-sent", PaymentExecutionStatus.SUBMITTED),
                occurrence("sent-paid", PaymentExecutionStatus.SUBMITTED),
                occurrence("sent-pending", PaymentExecutionStatus.SUBMITTED),
                occurrence("done", PaymentExecutionStatus.SUCCEEDED),
            ),
        )
        seedJournal(
            journalEntry("prepared", request, Stage.PREPARED),
            journalEntry("never-sent", request, Stage.SUBMITTED),
            journalEntry("sent-paid", request, Stage.SENT, paymentHash = paidHash),
            journalEntry("sent-pending", request, Stage.SENT, paymentHash = pendingHash),
        )
        nodeStatuses[paidHash] = PaymentStatus.SUCCEEDED
        nodeStatuses[pendingHash] = PaymentStatus.PENDING

        sut.recover(identity)

        val expected = mapOf(
            "prepared" to PaymentOutcome.FAILED,
            "never-sent" to PaymentOutcome.FAILED,
            "sent-paid" to PaymentOutcome.SUCCEEDED,
            "sent-pending" to PaymentOutcome.UNKNOWN,
        )
        assertEquals(expected, recordedOutcomes.associate { it.attemptId to it.outcome })
        assertEquals(4, recordedOutcomes.size)
        assertEquals(
            mapOf(
                "prepared" to Stage.FAILED,
                "never-sent" to Stage.FAILED,
                "sent-paid" to Stage.SUCCEEDED,
                "sent-pending" to Stage.UNKNOWN,
            ),
            sut.localState(identity).journal.associate { it.attemptId to it.stage },
        )
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
        verify(payer, never()).resolve(any(), any())
        assertEquals(emptyList(), reconciliations)
    }

    @Test
    fun `recovery settles an on-chain attempt only on the wallet's positive evidence`() = test {
        val request = fixtures.paymentRequest()
        val refusedTxid = "a".repeat(64)
        val acceptedTxid = "b".repeat(64)
        accountingState = fixtures.accountingState(
            occurrences = listOf(
                occurrence("onchain-refused", PaymentExecutionStatus.UNKNOWN),
                occurrence("onchain-accepted", PaymentExecutionStatus.UNKNOWN),
            ),
        )
        seedJournal(
            journalEntry("onchain-refused", request, Stage.UNKNOWN).copy(transactionId = refusedTxid),
            journalEntry("onchain-accepted", request, Stage.UNKNOWN).copy(transactionId = acceptedTxid),
        )
        whenever(payer.onchainPaymentOutcome(request.id, refusedTxid)).thenReturn(null)
        whenever(payer.onchainPaymentOutcome(request.id, acceptedTxid)).thenReturn(PaymentOutcome.SUCCEEDED)

        sut.recover(identity)

        assertEquals(
            listOf(PaymentOutcomeReport("onchain-accepted", PaymentOutcome.SUCCEEDED)),
            recordedOutcomes,
        )
        assertEquals(
            mapOf("onchain-refused" to Stage.UNKNOWN, "onchain-accepted" to Stage.SUCCEEDED),
            sut.localState(identity).journal.associate { it.attemptId to it.stage },
        )
    }

    @Test
    fun `recovery leaves a wallet without a ledger untouched`() = test {
        accountingState = null

        sut.recover(identity)

        verify(sdk).allowanceAccountingState()
        verify(sdk, never()).reconcileAllowanceAccounting(any())
        verify(sdk, never()).recordPaymentOutcome(any())
    }

    @Test
    fun `recovery leaves a manual payment in progress alone`() = test {
        sut.activate(identity)
        val attemptId = sut.beginManualPayment(fixtures.paymentRequest(), fixtures.lightningIdentifier).getOrThrow()
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence(MANUAL_ATTEMPT_ID, PaymentExecutionStatus.SUBMITTED)),
        )

        sut.recover(identity)
        assertEquals(emptyList(), recordedOutcomes)

        sut.finishManualPayment(checkNotNull(attemptId), PaymentOutcome.SUCCEEDED)
        assertEquals(listOf(PaymentOutcomeReport(MANUAL_ATTEMPT_ID, PaymentOutcome.SUCCEEDED)), recordedOutcomes)
    }

    // endregion

    // region Manual payments

    @Test
    fun `recovery never fails a manual on-chain payment that may have left before the kill`() = test {
        sut.activate(identity)
        val attemptId =
            checkNotNull(sut.beginManualPayment(fixtures.paymentRequest(), fixtures.onchainIdentifier).getOrThrow())
        accountingState = fixtures.accountingState(
            occurrences = listOf(occurrence(attemptId, PaymentExecutionStatus.SUBMITTED)),
        )
        val restarted = PaykitAllowanceExecutor(
            ioDispatcher = testDispatcher,
            paykitSdkService = sdk,
            store = PaykitAllowanceStore(keychain),
            payer = payer,
            clock = clock,
            accountingAmount = { _, _ -> fixtures.accountingAmount("0.00001") },
        )

        restarted.recover(identity)

        assertEquals(
            emptyList(),
            recordedOutcomes.filter { it.attemptId == attemptId && it.outcome == PaymentOutcome.FAILED },
        )
    }

    @Test
    fun `a released manual on-chain payment is recorded failed`() = test {
        sut.activate(identity)
        val request = fixtures.paymentRequest()
        val attemptId = checkNotNull(sut.beginManualPayment(request, fixtures.onchainIdentifier).getOrThrow())
        sut.finishManualPayment(attemptId, PaymentOutcome.UNKNOWN, transactionId = "c".repeat(64))
        recordedOutcomes.clear()

        sut.onchainSendReleased(fixtures.otherCounterpartyKey, request.id)
        assertEquals(emptyList(), recordedOutcomes, "Another identity's release changes nothing")

        sut.onchainSendReleased(identity, request.id)

        assertEquals(listOf(PaymentOutcomeReport(attemptId, PaymentOutcome.FAILED)), recordedOutcomes)
        assertEquals(Stage.FAILED, sut.localState(identity).journal.single().stage)
    }

    @Test
    fun `manual payment fails when the request is already recorded`() = test {
        sut.activate(identity)
        manualReservation = PaymentAttemptDecision.Blocked(AllowanceAccountingBlock.PaymentAlreadyRecorded)

        val result = sut.beginManualPayment(fixtures.paymentRequest(), fixtures.lightningIdentifier)

        assertIs<PaykitAllowanceError.PaymentAlreadyRecorded>(result.exceptionOrNull())
        verify(sdk, never()).beginPaymentExecution(any(), any())
    }

    @Test
    fun `manual payment is journaled and submitted when ready`() = test {
        sut.activate(identity)
        val request = fixtures.paymentRequest()

        val attemptId = sut.beginManualPayment(request, fixtures.lightningIdentifier).getOrThrow()

        assertEquals(MANUAL_ATTEMPT_ID, attemptId)
        val entry = sut.localState(identity).journal.single()
        assertEquals(Stage.SUBMITTED, entry.stage)
        assertFalse(entry.isAutomatic)
        assertNull(entry.allowanceId)

        sut.manualLightningPaymentSent(MANUAL_ATTEMPT_ID, PAYMENT_HASH.uppercase())
        assertEquals(PAYMENT_HASH, sut.localState(identity).journal.single().paymentHash)
        assertEquals(Stage.SENT, sut.localState(identity).journal.single().stage)

        manualReservation = PaymentAttemptDecision.Blocked(AllowanceAccountingBlock.ManualOnly)
        assertNull(sut.beginManualPayment(request, fixtures.lightningIdentifier).getOrThrow())
    }

    @Test
    fun `manual lightning attempt settled by the node is recorded once and never counts as automatic`() = test {
        collectEvents()
        sut.activate(identity)
        val attemptId = checkNotNull(
            sut.beginManualPayment(fixtures.paymentRequest(), fixtures.lightningIdentifier).getOrThrow(),
        )
        sut.manualLightningPaymentSent(attemptId, PAYMENT_HASH)

        sut.lightningPaymentSettled(PAYMENT_HASH, succeeded = true)
        sut.finishManualPayment(attemptId, PaymentOutcome.SUCCEEDED)

        assertEquals(listOf(PaymentOutcomeReport(MANUAL_ATTEMPT_ID, PaymentOutcome.SUCCEEDED)), recordedOutcomes)
        assertEquals(emptyList(), events.filterIsInstance<PaykitAllowanceEvent.PaidAutomatically>())
        assertEquals(emptyList(), sut.succeededAutomaticPayments(identity))
    }

    @Test
    fun `manual payment is not reported without an identity or for an outgoing request`() = test {
        assertNull(sut.beginManualPayment(fixtures.paymentRequest(), fixtures.lightningIdentifier).getOrThrow())

        sut.activate(identity)
        val outgoing = fixtures.paymentRequest().copy(direction = PaykitPaymentRequestDirection.Outgoing)
        assertNull(sut.beginManualPayment(outgoing, fixtures.lightningIdentifier).getOrThrow())
        verifyNoInteractions(sdk)
    }

    // endregion

    // region Trusted time and reconciliation

    @Test
    fun `trusted time never moves backwards`() = test {
        val start = fixtures.now

        val first = sut.trustedTime(identity)
        now = start - 1.hours
        val afterClockMovedBack = sut.trustedTime(identity)
        now = start + 1.minutes
        val afterClockMovedForward = sut.trustedTime(identity)

        assertEquals(PaykitAllowanceTime.format(start), first)
        assertEquals(PaykitAllowanceTime.format(start), afterClockMovedBack)
        assertEquals(PaykitAllowanceTime.format(start + 1.minutes), afterClockMovedForward)
        assertEquals((start + 1.minutes).toEpochMilliseconds(), sut.localState(identity).lastTrustedTimeMillis)
    }

    @Test
    fun `missing ledger is reconciled with an empty history`() = test {
        accountingState = null

        val reconciled = sut.ensureReconciled(identity)
        sut.ensureReconciled(identity)

        val reconciliation = reconciliations.single()
        assertNull(reconciliation.expectedRevision)
        assertTrue(reconciliation.history.associations.isEmpty())
        assertTrue(reconciliation.history.occurrences.isEmpty())
        assertTrue(reconciliation.history.watermarks.isEmpty())
        assertEquals(emptyList(), reconciliation.outcomes)
        assertEquals(PaykitAllowanceTime.format(fixtures.now), reconciliation.trustedTime)
        assertFalse(reconciled.requiresReconciliation)
    }

    @Test
    fun `ledger that requires reconciliation is reconciled at its revision`() = test {
        val request = fixtures.paymentRequest()
        val paidHash = "3".repeat(64)
        accountingState = fixtures.accountingState(
            revision = 7uL,
            requiresReconciliation = true,
            occurrences = listOf(
                occurrence("prepared", PaymentExecutionStatus.PREPARED),
                occurrence("sent-paid", PaymentExecutionStatus.SUBMITTED),
            ),
        )
        seedJournal(journalEntry("sent-paid", request, Stage.SENT, paymentHash = paidHash))
        nodeStatuses[paidHash] = PaymentStatus.SUCCEEDED

        sut.ensureReconciled(identity)

        val reconciliation = reconciliations.single()
        assertEquals(7uL, reconciliation.expectedRevision)
        assertEquals(
            listOf("prepared", "sent-paid"),
            reconciliation.history.occurrences.flatMap { it.attempts }.map { it.attemptId },
        )
        assertEquals(
            listOf(
                PaymentOutcomeReport("prepared", PaymentOutcome.FAILED),
                PaymentOutcomeReport("sent-paid", PaymentOutcome.SUCCEEDED),
            ),
            reconciliation.outcomes,
        )
        verify(payer, never()).payLightning(any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `admission uses the injected clock for the monthly window`() = test {
        accountingState = stateNearTheMonthlyCap()
        now = Instant.parse("2026-10-02T12:00:00Z")

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(PaykitAllowanceAutoPayResult.STARTED, result, "September's attempts must not count in October")
        assertEquals(listOf(PaykitAllowanceTime.format(now)), evaluatedTrustedTimes)
    }

    @Test
    fun `admission ignores the subscription clock offset`() = test {
        offsetSubscriptionClock(days = 400)
        accountingState = stateNearTheMonthlyCap()

        val result = sut.autoPay(fixtures.paymentRequest(), listOf(fixtures.allowance()), identity)

        assertEquals(
            PaykitAllowanceAutoPayResult.MANUAL,
            result,
            "September's attempts must count; the offset would move the window a year ahead",
        )
        assertEquals(listOf(PaykitAllowanceTime.format(fixtures.now)), evaluatedTrustedTimes)
        verify(sdk, never()).acceptPaymentRequestAutomatically(any(), any(), any())
    }

    // endregion

    // region Helpers

    private fun offsetSubscriptionClock(days: Int) {
        SubscriptionClockOffset.setOffsetDays(days)
        assumeTrue(
            "The subscription clock offset is unavailable in this build",
            SubscriptionClockOffset.offsetDays == days,
        )
    }

    @After
    fun resetSubscriptionClockOffset() {
        SubscriptionClockOffset.setOffsetDays(0)
    }

    private fun TestScope.collectEvents() {
        backgroundScope.launch { sut.events.collect { events += it } }
    }

    private fun candidate(blocked: AllowanceAccountingBlock? = null) = AllowanceCandidate(
        allowanceId = ALLOWANCE_ID,
        eligiblePaymentEndpointIdentifiers = listOf(PaykitAllowanceFixtures.lightningIdentifier),
        blocked = blocked,
    )

    private fun lightningPayment() = PrivatePaykitAllowancePayment(
        endpoint = Endpoint(
            methodId = MethodId.Bolt11,
            value = INVOICE,
            rawPayload = """{"value":"$INVOICE"}""",
        ),
        appId = PAYMENT_APP_ID,
        context = PrivatePaykitPaymentContext(mapOf(MethodId.Bolt11.rawValue to PAYMENT_APP_ID), 3uL),
        lightningPaymentHash = PAYMENT_HASH,
        lightningInvoiceHasAmount = true,
    )

    private fun onchainPayment() = PrivatePaykitAllowancePayment(
        endpoint = Endpoint(
            methodId = MethodId.P2wpkh,
            value = ONCHAIN_ADDRESS,
            rawPayload = """{"value":"$ONCHAIN_ADDRESS"}""",
        ),
        appId = PAYMENT_APP_ID,
        context = PrivatePaykitPaymentContext(mapOf(MethodId.P2wpkh.rawValue to PAYMENT_APP_ID), 3uL),
        lightningPaymentHash = null,
        lightningInvoiceHasAmount = false,
    )

    /** Three succeeded automatic payments this month total 49,500 sats of the 50,000 sat cap. */
    private fun stateNearTheMonthlyCap() = fixtures.accountingState(
        occurrences = listOf(
            fixtures.occurrence(
                "paid-1",
                listOf(paidAttempt("paid-1", "0.0002", "2026-09-05T10:00:00Z")),
            ),
            fixtures.occurrence(
                "paid-2",
                listOf(paidAttempt("paid-2", "0.0002", "2026-09-12T10:00:00Z")),
            ),
            fixtures.occurrence(
                "paid-3",
                listOf(paidAttempt("paid-3", "0.000095", "2026-09-20T10:00:00Z")),
            ),
        ),
    )

    private fun paidAttempt(id: String, amount: String, admittedAt: String) = fixtures.attemptRecord(
        id = id,
        amount = amount,
        admittedAt = admittedAt,
        status = PaymentExecutionStatus.SUCCEEDED,
    )

    private fun occurrence(
        attemptId: String,
        status: PaymentExecutionStatus,
        epoch: String = "epoch-1",
    ) = fixtures.occurrence(
        requestId = "req-$attemptId",
        attempts = listOf(fixtures.attemptRecord(id = attemptId, status = status, epoch = epoch)),
    )

    private fun journalEntry(
        attemptId: String,
        request: PaykitPaymentRequest,
        stage: Stage,
        paymentHash: String? = null,
    ) = JournalEntry(
        attemptId = attemptId,
        isAutomatic = true,
        requestId = request.id,
        allowanceId = ALLOWANCE_ID,
        amountSats = request.amount.atomic,
        paymentEndpointIdentifier = fixtures.lightningIdentifier,
        paymentHash = paymentHash,
        stage = stage,
        createdAtMillis = fixtures.now.toEpochMilliseconds(),
    )

    private suspend fun seedJournal(vararg entries: JournalEntry) {
        sut.updateLocalState(identity) { it.copy(journal = entries.toList()) }
    }

    // endregion
}

private class TestAllowanceError(message: String) : AppError(message)
