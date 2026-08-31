package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.WalletScope
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PaykitPaymentProofRepoTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val LOCAL_IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PAYMENT_REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000"
        private const val PAYMENT_HASH = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
        private const val ONCHAIN_ADDRESS = "bcrt1qpaymentproof"
        private val PREIMAGE = "00".repeat(32)
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private val lightningRepo = mock<LightningRepo>()
    private val onchainPaymentLookup = mock<PaykitOnchainPaymentProofLookup>()
    private val store = mock<PaykitPaymentProofStore>()
    private var storedProofs = emptyList<PendingPaykitPaymentProof>()
    private var shouldFailNextLoad = false
    private var shouldFailNextSave = false

    @Before
    fun setUp() = test {
        storedProofs = emptyList()
        shouldFailNextLoad = false
        shouldFailNextSave = false
        whenever(store.hasPendingProofs()).thenReturn(true)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(onchainPaymentLookup.existingTransactionIds(any(), any(), any())).thenReturn(emptySet())
        whenever(store.load()).thenAnswer {
            if (shouldFailNextLoad) {
                shouldFailNextLoad = false
                error("temporary load failure")
            }
            storedProofs
        }
        whenever(store.save(any())).doSuspendableAnswer {
            if (shouldFailNextSave) {
                shouldFailNextSave = false
                error("transient save failure")
            }
            storedProofs = it.getArgument(0)
        }
    }

    @Test
    fun `reconcile avoids Paykit and proof loading without persisted proofs`() = test {
        whenever(store.hasPendingProofs()).thenReturn(false)

        paymentProofRepo().reconcile()

        verify(store, never()).load()
        verify(paykitSdkService, never()).identityStatus()
        verify(lightningRepo, never()).getPayments()
    }

    @Test
    fun `reconcile removes persisted empty proof state without using Paykit`() = test {
        paymentProofRepo().reconcile()

        verify(store).load()
        verify(store).save(emptyList())
        verify(paykitSdkService, never()).identityStatus()
    }

    @Test
    fun `completed lightning proof retries after repository restart`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenThrow(IllegalStateException("temporary failure"))
            .thenReturn(record)
        val firstRepo = paymentProofRepo()

        firstRepo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        firstRepo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        firstRepo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        assertEquals(PREIMAGE, storedProofs.single().proofData)

        paymentProofRepo().reconcile()

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService, times(2)).submitPaymentProof(
            counterparty = any(),
            counterpartyReceiverPath = any(),
            paymentRequestId = any(),
            paymentEndpointIdentifier = endpointCaptor.capture(),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
        )
        assertEquals(MethodId.Bolt11.rawValue, endpointCaptor.lastValue)
        assertEquals(
            """{"data":"$PREIMAGE","type":"${PaykitPaymentProofKind.Lightning.type}"}""",
            proofCaptor.lastValue,
        )
        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService).processPendingPrivateMessages()
    }

    @Test
    fun `mismatched lightning preimage is not submitted`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, "01".repeat(32))

        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `existing proof suppresses duplicate submission`() = test {
        val existingProofJson = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"type":"${PaykitPaymentProofKind.Lightning.type}","data":"$PREIMAGE"}"""
        }
        val existingProof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn null
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
            on { proof } doReturn existingProofJson
        }
        val record = paymentRequestRecord(listOf(existingProof))
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `failed lightning payment clears persisted correlation`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        repo.failLightningPayment(PAYMENT_HASH)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `onchain proof uses selected endpoint and transaction id`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        assertTrue(storedProofs.single().paymentStarted)
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue)

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            any(),
            any(),
            any(),
            endpointCaptor.capture(),
            proofCaptor.capture(),
            isNull(),
        )
        assertEquals(MethodId.P2wpkh.rawValue, endpointCaptor.firstValue)
        assertEquals(
            """{"data":"$txid","type":"${PaykitPaymentProofKind.Onchain.type}"}""",
            proofCaptor.firstValue,
        )
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `started onchain payment survives preparation cancellation`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.cancelPreparation(request)

        assertTrue(storedProofs.single().paymentStarted)
    }

    @Test
    fun `definite onchain failure clears started proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.failOnchainPayment(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `recurring proof includes the exact billing period`() = test {
        val period = PaykitBillingPeriod(
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = Instant.parse("2027-02-01T08:00:00Z"),
        )
        val request = paymentRequest(MethodId.Bolt11.rawValue, billingPeriod = period)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        val periodCaptor = argumentCaptor<PaykitBillingPeriod>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            counterpartyReceiverPath = any(),
            paymentRequestId = any(),
            paymentEndpointIdentifier = any(),
            proofJson = any(),
            billingPeriod = periodCaptor.capture(),
        )
        assertEquals(period, periodCaptor.firstValue)
    }

    @Test
    fun `proof from earlier billing period does not suppress recurring payment`() = test {
        val currentPeriod = PaykitBillingPeriod(
            startsAt = Instant.parse("2027-02-01T08:00:00Z"),
            endsAt = Instant.parse("2027-03-01T08:00:00Z"),
        )
        val existingProofJson = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"type":"${PaykitPaymentProofKind.Lightning.type}","data":"$PREIMAGE"}"""
        }
        val existingProof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn BillingPeriod(
                startsAt = "2027-01-01T08:00:00.000Z",
                endsAt = "2027-02-01T08:00:00.000Z",
            )
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
            on { proof } doReturn existingProofJson
        }
        val record = paymentRequestRecord(listOf(existingProof))
        val request = paymentRequest(MethodId.Bolt11.rawValue, billingPeriod = currentPeriod)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `lightning retry is rejected while earlier payment is unresolved`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()
        val retry = repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning)

        assertTrue(retry.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        assertEquals(PAYMENT_HASH, storedProofs.single().paymentIdentifier)

        repo.failLightningPayment(PAYMENT_HASH)
        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        assertEquals(1, storedProofs.size)
    }

    @Test
    fun `cleared store does not restore cached proofs`() = test {
        val firstRequest = paymentRequest(MethodId.Bolt11.rawValue)
        val secondRequestId = "550e8400-e29b-41d4-a716-446655440001"
        val secondRequest = paymentRequest(MethodId.Bolt11.rawValue, secondRequestId)
        val repo = paymentProofRepo()

        repo.prepare(firstRequest, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        storedProofs = emptyList()
        repo.prepare(secondRequest, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()

        assertEquals(1, storedProofs.size)
        assertEquals(secondRequestId, storedProofs.single().requestId.paymentRequestId)
    }

    @Test
    fun `onchain proof submits when completed proof cannot be persisted`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `completed onchain proof remains durable when persistence and submission initially fail`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenThrow(IllegalStateException("transient submission failure"))
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue)

        assertEquals(txid, storedProofs.single().proofData)
        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `onchain proof submits when prepared proof cannot be loaded`() = test {
        val txid = "ab".repeat(32)
        val endpoint = MethodId.P2wpkh.rawValue
        val request = paymentRequest(endpoint)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, endpoint, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailNextLoad = true
        repo.completeOnchainPayment(request, txid, endpoint)

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            counterpartyReceiverPath = any(),
            paymentRequestId = any(),
            paymentEndpointIdentifier = endpointCaptor.capture(),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
        )
        assertEquals(endpoint, endpointCaptor.firstValue)
        assertEquals(
            """{"data":"$txid","type":"${PaykitPaymentProofKind.Onchain.type}"}""",
            proofCaptor.firstValue,
        )
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `uncertain onchain payment is reconciled from its private destination`() = test {
        val txid = "ab".repeat(32)
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        whenever(
            onchainPaymentLookup.transactionId(
                ONCHAIN_ADDRESS,
                request.amountSats,
                emptySet(),
                WalletScope.default,
            )
        ).thenReturn(txid)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.reconcile()

        assertTrue(storedProofs.isEmpty())
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = eq(request.counterparty),
            counterpartyReceiverPath = eq(request.counterpartyReceiverPath),
            paymentRequestId = eq(request.paymentRequestId),
            paymentEndpointIdentifier = eq(MethodId.P2wpkh.rawValue),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
        )
        assertTrue(proofCaptor.firstValue.contains(txid))
    }

    @Test
    fun `uncertain onchain payment ignores transaction from before attempt`() = test {
        val oldTransactionId = "ab".repeat(32)
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(onchainPaymentLookup.existingTransactionIds(any(), any(), any())).thenReturn(setOf(oldTransactionId))
        whenever(
            onchainPaymentLookup.transactionId(
                ONCHAIN_ADDRESS,
                request.amountSats,
                setOf(oldTransactionId),
                WalletScope.default,
            )
        ).thenReturn(null)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.reconcile()

        assertEquals(setOf(oldTransactionId), storedProofs.single().onchainMatchingTransactionIdsBeforeAttempt)
        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `cancel preparation does not remove another identity proof`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val otherIdentityProof = PendingPaykitPaymentProof(
            identity = "pubky${"a".repeat(52)}",
            requestId = request.id,
            paymentEndpointIdentifier = MethodId.Bolt11.rawValue,
            kind = PaykitPaymentProofKind.Lightning,
        )
        storedProofs = listOf(otherIdentityProof)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.cancelPreparation(request)

        assertEquals(listOf(otherIdentityProof), storedProofs)
    }

    @Test
    fun `subscription cancellation discards only unstarted preparation`() = test {
        val period = PaykitBillingPeriod(
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = Instant.parse("2027-02-01T08:00:00Z"),
        )
        val request = paymentRequest(MethodId.Bolt11.rawValue, billingPeriod = period)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()

        val protectedRequestIds = repo.protectedRequestIdsForSubscriptionCancellation(
            LOCAL_IDENTITY,
            PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY, PaykitReceiverPaths.WALLET),
        ).getOrThrow()

        assertTrue(protectedRequestIds.isEmpty())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `subscription cancellation preserves a started payment`() = test {
        val period = PaykitBillingPeriod(
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = Instant.parse("2027-02-01T08:00:00Z"),
        )
        val request = paymentRequest(MethodId.Bolt11.rawValue, billingPeriod = period)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH).getOrThrow()

        val protectedRequestIds = repo.protectedRequestIdsForSubscriptionCancellation(
            LOCAL_IDENTITY,
            PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY, PaykitReceiverPaths.WALLET),
        ).getOrThrow()

        assertEquals(setOf(request.id), protectedRequestIds)
        assertEquals(listOf(request.id), storedProofs.map { it.requestId })
    }

    private fun paymentProofRepo() = PaykitPaymentProofRepo(
        ioDispatcher = testDispatcher,
        paykitSdkService = paykitSdkService,
        lightningRepo = lightningRepo,
        onchainPaymentLookup = onchainPaymentLookup,
        store = store,
    )

    private fun paymentRequest(
        endpoint: String,
        paymentRequestId: String = PAYMENT_REQUEST_ID,
        billingPeriod: PaykitBillingPeriod? = null,
    ) = PaykitPaymentRequest(
        paymentRequestId = paymentRequestId,
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
        amountValue = "0.00001",
        amountSats = 1_000uL,
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf(endpoint),
        billingPeriod = billingPeriod,
    )

    private fun paymentRequestRecord(paymentProofs: List<PaymentProofRecord> = emptyList()) = PaymentRequestRecord(
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
        paymentRequestId = PAYMENT_REQUEST_ID,
        localRole = PaymentRequestLocalRole.PAYER,
        state = PaymentRequestLifecycleState.PROPOSED,
        proposalStreamItemId = 1uL,
        proposalOutboundMessageId = null,
        proposalOutboundStatus = null,
        proposalEventId = "proposal-event",
        terms = PaymentRequestTerms(
            amount = PaymentRequestAmount(value = "0.00001", asset = "btc"),
            paymentReference = mock<PaymentReference>(),
            proposalExpiresAt = null,
            recurrence = null,
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.Bolt11.rawValue),
            metadata = mock<PrivateJsonObject>(),
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
        lastEventAt = "2027-01-15T08:00:00Z",
        invalidReason = null,
    )
}
