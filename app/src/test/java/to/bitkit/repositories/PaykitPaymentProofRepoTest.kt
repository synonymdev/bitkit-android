package to.bitkit.repositories

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
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaykitPaymentProofRepoTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val LOCAL_IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PAYMENT_REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000"
        private const val PAYMENT_HASH = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"
        private val PREIMAGE = "00".repeat(32)
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private val lightningRepo = mock<LightningRepo>()
    private val store = mock<PaykitPaymentProofStore>()
    private var storedProofs = emptyList<PendingPaykitPaymentProof>()
    private var shouldFailNextLoad = false
    private var shouldFailNextSave = false
    private var shouldFailProofRemoval = false

    @Before
    fun setUp() = test {
        storedProofs = emptyList()
        shouldFailNextLoad = false
        shouldFailNextSave = false
        shouldFailProofRemoval = false
        whenever(store.hasPendingProofs()).thenReturn(true)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(paykitSdkService.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(store.load()).thenAnswer {
            if (shouldFailNextLoad) {
                shouldFailNextLoad = false
                error("temporary load failure")
            }
            storedProofs
        }
        whenever(store.save(any())).doSuspendableAnswer {
            val proofs = it.getArgument<List<PendingPaykitPaymentProof>>(0)
            if (shouldFailNextSave) {
                shouldFailNextSave = false
                error("temporary save failure")
            }
            if (shouldFailProofRemoval && proofs.isEmpty()) {
                shouldFailProofRemoval = false
                error("temporary proof removal failure")
            }
            storedProofs = proofs
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any()))
            .thenThrow(IllegalStateException("temporary failure"))
            .thenReturn(record)
        val firstRepo = paymentProofRepo()

        firstRepo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        firstRepo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
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
    fun `failed proof reconciliation does not stop later proofs`() = test {
        val secondPaymentRequestId = "550e8400-e29b-41d4-a716-446655440001"
        storedProofs = listOf(
            readyLightningProof(PAYMENT_REQUEST_ID),
            readyLightningProof(secondPaymentRequestId),
        )
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(paymentRequestId = PAYMENT_REQUEST_ID),
                paymentRequestRecord(paymentRequestId = secondPaymentRequestId),
            ),
        )
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any()))
            .thenThrow(IllegalStateException("temporary failure"))
            .thenReturn(paymentRequestRecord(paymentRequestId = secondPaymentRequestId))

        paymentProofRepo().reconcile()

        val paymentRequestIdCaptor = argumentCaptor<String>()
        verify(paykitSdkService, times(2)).submitPaymentProof(
            counterparty = any(),
            counterpartyReceiverPath = any(),
            paymentRequestId = paymentRequestIdCaptor.capture(),
            paymentEndpointIdentifier = any(),
            proofJson = any(),
        )
        assertEquals(listOf(PAYMENT_REQUEST_ID, secondPaymentRequestId), paymentRequestIdCaptor.allValues)
        assertEquals(listOf(PAYMENT_REQUEST_ID), storedProofs.map { it.requestId.paymentRequestId })
    }

    @Test
    fun `associated lightning proof completes after repository restart`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val paymentKind = mock<PaymentKind.Bolt11> {
            on { preimage } doReturn PREIMAGE
        }
        val payment = mock<PaymentDetails> {
            on { id } doReturn PAYMENT_HASH
            on { kind } doReturn paymentKind
            on { direction } doReturn PaymentDirection.OUTBOUND
            on { status } doReturn PaymentStatus.SUCCEEDED
        }
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(listOf(payment)))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val firstRepo = paymentProofRepo()

        firstRepo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        firstRepo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        assertNull(storedProofs.single().proofData)

        paymentProofRepo().reconcile()

        verify(lightningRepo).getPayments()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            counterpartyReceiverPath = any(),
            paymentRequestId = any(),
            paymentEndpointIdentifier = any(),
            proofJson = proofCaptor.capture(),
        )
        assertEquals(
            """{"data":"$PREIMAGE","type":"${PaykitPaymentProofKind.Lightning.type}"}""",
            proofCaptor.firstValue,
        )
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `lightning proof completes without prepared proof`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `mismatched lightning preimage is not submitted`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, "01".repeat(32))

        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any())
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
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any())
    }

    @Test
    fun `failed lightning payment clears persisted correlation`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.failLightningPayment(PAYMENT_HASH)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any())
    }

    @Test
    fun `onchain proof uses selected endpoint and transaction id`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue)

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            any(),
            any(),
            any(),
            endpointCaptor.capture(),
            proofCaptor.capture(),
        )
        assertEquals(MethodId.P2wpkh.rawValue, endpointCaptor.firstValue)
        assertEquals(
            """{"data":"$txid","type":"${PaykitPaymentProofKind.Onchain.type}"}""",
            proofCaptor.firstValue,
        )
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `onchain proof submits without prepared proof`() = test {
        val txid = "ab".repeat(32)
        val endpoint = MethodId.P2wpkh.rawValue
        val request = paymentRequest(endpoint)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.completeOnchainPayment(request, txid, endpoint)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), eq(endpoint), any())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `lightning retry preserves earlier payment correlation`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, "aa".repeat(32), MethodId.Bolt11.rawValue).getOrThrow()

        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any())
        assertTrue(storedProofs.isEmpty())
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `onchain proof cleanup failure does not retry delivery`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        shouldFailProofRemoval = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any())
        assertEquals(txid, storedProofs.single().proofData)
    }

    @Test
    fun `onchain proof submits when prepared proof cannot be loaded`() = test {
        val txid = "ab".repeat(32)
        val endpoint = MethodId.P2wpkh.rawValue
        val request = paymentRequest(endpoint)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, endpoint, PaykitPaymentProofKind.Onchain).getOrThrow()
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
        )
        assertEquals(endpoint, endpointCaptor.firstValue)
        assertEquals(
            """{"data":"$txid","type":"${PaykitPaymentProofKind.Onchain.type}"}""",
            proofCaptor.firstValue,
        )
        assertTrue(storedProofs.isEmpty())
    }

    private fun paymentProofRepo() = PaykitPaymentProofRepo(
        ioDispatcher = testDispatcher,
        paykitSdkService = paykitSdkService,
        lightningRepo = lightningRepo,
        store = store,
    )

    private fun paymentRequest(
        endpoint: String,
        paymentRequestId: String = PAYMENT_REQUEST_ID,
    ) = PaykitPaymentRequest(
        paymentRequestId = paymentRequestId,
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
        amountValue = "0.00001",
        amountSats = 1_000uL,
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf(endpoint),
    )

    private fun readyLightningProof(paymentRequestId: String) = PendingPaykitPaymentProof(
        identity = LOCAL_IDENTITY,
        requestId = PaykitPaymentRequestId(
            counterparty = COUNTERPARTY,
            counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
            paymentRequestId = paymentRequestId,
        ),
        paymentEndpointIdentifier = MethodId.Bolt11.rawValue,
        kind = PaykitPaymentProofKind.Lightning,
        paymentIdentifier = PAYMENT_HASH,
        proofData = PREIMAGE,
    )

    private fun paymentRequestRecord(
        paymentProofs: List<PaymentProofRecord> = emptyList(),
        paymentRequestId: String = PAYMENT_REQUEST_ID,
    ) = PaymentRequestRecord(
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
        paymentRequestId = paymentRequestId,
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
