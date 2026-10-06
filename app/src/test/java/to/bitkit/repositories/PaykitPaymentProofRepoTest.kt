package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.BroadcastException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
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
import to.bitkit.models.HwConnectedDevice
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.WalletScope
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.send.HwSendRequest
import to.bitkit.ui.screens.wallets.send.HwSendViewModel
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
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
    private val store = mock<PaykitPaymentProofStore>()
    private val hwWalletRepo = mock<HwWalletRepo>()
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
        whenever(paykitSdkService.paymentRequests()).thenReturn(emptyList())
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
                error("transient save failure")
            }
            if (shouldFailProofRemoval && proofs.isEmpty()) {
                shouldFailProofRemoval = false
                error("temporary proof removal failure")
            }
            storedProofs = proofs
        }
    }

    @Test
    fun `restart clears only the original proof from interrupted predispatch admission`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        var saved: String? = null
        val keychain = mock<to.bitkit.data.keychain.Keychain>()
        val key = to.bitkit.data.keychain.Keychain.Key.ONCHAIN_SEND_ATTEMPT.name
        whenever(keychain.loadString(key, 0)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(key), any(), eq(0))).doSuspendableAnswer { saved = it.getArgument(1) }
        whenever(keychain.delete(key, 0)).doSuspendableAnswer { saved = null }
        val attempts = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        val admitted = attempts.admit(
            walletId = WalletScope.default, requestId = request.id, orderId = null,
            address = ONCHAIN_ADDRESS, amountSats = request.amountSats, isMaxAmount = false,
            feeRateSatsPerVByte = 1uL, isTransfer = false, channelId = null, tags = emptyList(),
            payerIdentity = LOCAL_IDENTITY,
            beforeSendAttempt = { repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow() },
        )
        assertTrue(admitted.preparationPending)
        assertTrue(storedProofs.single().paymentStarted)
        assertFalse(attempts.releaseInterruptedShopPreparation { error("live preparation must not be cleared") })
        val reopened = OnchainSendAttemptStore(testDispatcher, keychain, mock(), kotlin.time.Clock.System)
        whenever(lightningRepo.currentOnchainSendAttempt()).doSuspendableAnswer { reopened.current() }
        whenever(lightningRepo.releaseInterruptedShopPreparation(any())).doSuspendableAnswer {
            reopened.releaseInterruptedShopPreparation(it.getArgument(0))
        }
        val startedProof = storedProofs.single()
        storedProofs = listOf(startedProof.copy(paymentIdentifier = "ab".repeat(32)))
        repo.reconcile()
        assertEquals(admitted, reopened.current())
        assertEquals("ab".repeat(32), storedProofs.single().paymentIdentifier)
        storedProofs = listOf(startedProof)
        shouldFailProofRemoval = true
        repo.reconcile()
        assertEquals(admitted, reopened.current())
        assertTrue(storedProofs.single().paymentStarted)
        repo.reconcile()
        assertNull(reopened.current())
        assertTrue(storedProofs.isEmpty())
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        assertFalse(storedProofs.single().paymentStarted)
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `lightning association cannot bypass a started onchain request`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue).copy(
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.P2wpkh.rawValue, MethodId.Bolt11.rawValue),
        )
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()

        val result = repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue)

        assertTrue(result.isFailure)
        assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
        assertEquals(1, storedProofs.size)
        assertEquals(PaykitPaymentProofKind.Onchain, storedProofs.single().kind)
    }

    @Test
    fun `onchain callback cannot bypass a started lightning request`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue).copy(
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.P2wpkh.rawValue, MethodId.Bolt11.rawValue),
        )
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()

        val result = repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS)

        assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
        assertEquals(1, storedProofs.count { it.paymentStarted })
        assertEquals(PaykitPaymentProofKind.Lightning, storedProofs.single { it.paymentStarted }.kind)
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
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
            billingPeriod = isNull(),
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
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
            billingPeriod = isNull(),
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
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
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `queued onchain proof blocks same Shop request switched to Lightning`() = test {
        val existingProofJson = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"type":"${PaykitPaymentProofKind.Onchain.type}","data":"${"ab".repeat(32)}"}"""
        }
        val existingProof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn null
            on { paymentEndpointIdentifier } doReturn MethodId.P2wpkh.rawValue
            on { proof } doReturn existingProofJson
        }
        val record = paymentRequestRecord(listOf(existingProof))
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        val repo = paymentProofRepo()

        val result = repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning)

        assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `accepted onchain attempt blocks Lightning before its proof is queued`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(lightningRepo.currentOnchainSendAttempt())
            .thenReturn(acceptedAttempt(request, "ab".repeat(32)))

        val result = paymentProofRepo().prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning)

        assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `failed lightning payment clears persisted correlation`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.failLightningPayment(PAYMENT_HASH)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `uncertain lightning submission preserves proof until settlement`() = test {
        val errors = listOf(
            NodeException.PersistenceFailed("io"),
            LdkError(NodeException.PersistenceFailed("io")),
            NodeException.DuplicatePayment("pending"),
            AppError("payment outcome unknown"),
        )
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(lightningRepo.getPayments()).thenReturn(Result.success(emptyList()))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        for (error in errors) {
            val request = paymentRequest(MethodId.Bolt11.rawValue)
            val repo = paymentProofRepo()
            repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
            repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()

            val failed = repo.failLightningPayment(PAYMENT_HASH, error)
            repo.cancelPreparation(request)
            val restartedRepo = paymentProofRepo()
            restartedRepo.reconcile()

            assertFalse(failed)
            assertTrue(storedProofs.single().paymentStarted)
            assertEquals(PAYMENT_HASH, storedProofs.single().paymentIdentifier)
            val retry = restartedRepo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning)
            assertTrue(retry.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)

            restartedRepo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)
            assertTrue(storedProofs.isEmpty())
        }
        verify(paykitSdkService, times(errors.size)).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `definite lightning submission failure clears proof`() = test {
        val errors = listOf(
            ServiceError.NodeNotSetup(),
            ServiceError.NodeNotStarted(),
            NodeNotRunningError("payInvoice", NodeLifecycleState.Stopped),
            NodeRunTimeoutError("payInvoice"),
            NodeException.NotRunning("stopped"),
            NodeException.InvalidInvoice("invalid"),
            NodeException.InvalidAmount("invalid"),
            LdkError(NodeException.PaymentSendingFailed("no route")),
        )
        for (error in errors) {
            val request = paymentRequest(MethodId.Bolt11.rawValue)
            val repo = paymentProofRepo()
            repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
            repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()

            assertTrue(repo.failLightningPayment(PAYMENT_HASH, error))
            assertTrue(storedProofs.isEmpty())
        }
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
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, OnchainSendOutcome.Accepted(txid))

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
    fun `onchain failure clears started proof without a live identity`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        repo.failOnchainPayment(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `cancel preparation clears proof without a live identity`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        repo.cancelPreparation(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `onchain proof submits without prepared proof`() = test {
        val txid = "ab".repeat(32)
        val endpoint = MethodId.P2wpkh.rawValue
        val request = paymentRequest(endpoint)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.completeOnchainPayment(request, txid, endpoint, OnchainSendOutcome.Accepted(txid))

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), eq(endpoint), any(), isNull())
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
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
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
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `lightning retry is rejected while earlier payment is unresolved`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()
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
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, OnchainSendOutcome.Accepted(txid))

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
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, OnchainSendOutcome.Accepted(txid))

        assertEquals(txid, storedProofs.single().proofData)
        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `onchain proof cleanup failure does not retry delivery`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailProofRemoval = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, OnchainSendOutcome.Accepted(txid))

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
        assertEquals(txid, storedProofs.single().proofData)
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
        repo.completeOnchainPayment(request, txid, endpoint, OnchainSendOutcome.Accepted(txid))

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
    fun `accepted onchain payment is reconciled from its exact recorded transaction`() = test {
        val txid = "ab".repeat(32)
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, txid))
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
    fun `typed accepted callback cannot invent proof when original guard cannot be read`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        val original = storedProofs.single()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenThrow(IllegalStateException("guard unavailable"))
        assertFalse(
            repo.completeOnchainPayment(
                request,
                "ab".repeat(32),
                MethodId.P2wpkh.rawValue,
                OnchainSendOutcome.Accepted("ab".repeat(32)),
            )
        )
        assertEquals(listOf(original), storedProofs)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `shared golden restored software proof authorizes and completes exact observed successor`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/active-onchain-attempt-golden.json")).readBytes()
        val backup = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
        val state = requireNotNull(backup.paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        wire.validateProofs(state.pendingProofs, WalletScope.default)
        val attempt = wire.restored("regtest", wire.wallet.binding, WalletScope.default, 2)
        val repo = paymentProofRepo()
        repo.restoreBackup(state.pendingProofs)
        assertEquals(WalletScope.default, storedProofs.single().onchainWalletId)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(requireNotNull(wire.payerIdentity), true))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt)
        repo.authorizeOnchainRecovery(attempt).getOrThrow()
        assertEquals(state.pendingProofs.single().paymentIdentifier, storedProofs.single().paymentIdentifier)
        val requestId = requireNotNull(wire.requestId)
        val request = paymentRequest(state.pendingProofs.single().paymentEndpointIdentifier).copy(
            paymentRequestId = requestId.paymentRequestId,
            counterparty = requestId.counterparty,
            counterpartyReceiverPath = requestId.counterpartyReceiverPath,
            amountSats = attempt.amountSats,
        )
        val winner = attempt.copy(evidence = OnchainSendEvidence.Observed)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(winner)
        assertTrue(repo.completeRecoveredOnchainPayment(request, winner))
        val completed = storedProofs.single()
        assertTrue(completed.onchainAcceptanceVerified)
        assertEquals(winner.txid, completed.paymentIdentifier)
        assertEquals(winner.txid, completed.proofData)
        storedProofs = listOf(completed.copy(onchainWalletId = "hardware-wallet", proofData = null))
        assertFalse(repo.completeRecoveredOnchainPayment(request, winner))
        storedProofs = listOf(completed.copy(identity = COUNTERPARTY, proofData = null))
        assertFalse(repo.completeRecoveredOnchainPayment(request, winner))
    }

    @Test
    fun `successor winner updates the original payer proof without creating another proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val original = "cd".repeat(32)
        val winner = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        storedProofs = storedProofs.map { it.copy(paymentIdentifier = original) }
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(
            acceptedAttempt(request, winner).copy(
                payerIdentity = LOCAL_IDENTITY,
                originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u)),
                candidateTxids = listOf(original, winner),
            ),
        )
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenThrow(IllegalStateException("delivery temporarily unavailable"))

        assertTrue(
            repo.completeOnchainPayment(
                request,
                winner,
                MethodId.P2wpkh.rawValue,
                OnchainSendOutcome.Accepted(winner),
            )
        )

        val proof = storedProofs.single()
        assertEquals(LOCAL_IDENTITY, proof.identity)
        assertEquals(request.id, proof.requestId)
        assertEquals(winner, proof.paymentIdentifier)
        assertEquals(winner, proof.proofData)
        assertTrue(proof.onchainAcceptanceVerified)
        assertFalse(repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).isSuccess)
    }

    @Test
    fun `original retry authorization retains payer proof and rejects identity switch`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val original = "cd".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        storedProofs = storedProofs.map { it.copy(paymentIdentifier = original) }
        val proof = storedProofs.single()
        val attempt = acceptedAttempt(request, original).copy(
            evidence = OnchainSendEvidence.Unknown,
            payerIdentity = LOCAL_IDENTITY,
            originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u)),
            candidateTxids = listOf(original),
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt)

        repo.authorizeOnchainRecovery(attempt).getOrThrow()
        assertEquals(listOf(proof), storedProofs)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(COUNTERPARTY, true))
        storedProofs = listOf(proof, proof.copy(identity = COUNTERPARTY))
        assertTrue(repo.authorizeOnchainRecovery(attempt).isFailure)
        assertEquals(listOf(proof, proof.copy(identity = COUNTERPARTY)), storedProofs)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt.copy(payerIdentity = null))
        assertTrue(repo.authorizeOnchainRecovery(attempt.copy(payerIdentity = null)).isFailure)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `candidate winner cannot replace a proof bound outside its original family`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val winner = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        storedProofs = storedProofs.map { it.copy(paymentIdentifier = "ef".repeat(32)) }
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(
            acceptedAttempt(request, winner).copy(
                payerIdentity = LOCAL_IDENTITY,
                originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u)),
                candidateTxids = listOf("cd".repeat(32), winner),
            ),
        )
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))

        repo.reconcile()

        assertNull(storedProofs.single().proofData)
        assertEquals("ef".repeat(32), storedProofs.single().paymentIdentifier)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
        verify(lightningRepo, never()).finishAcceptedShopActivity(any(), any())
    }

    @Test
    fun `reconcile only resolves the request tied to exact transaction evidence`() = test {
        val secondPaymentRequestId = "550e8400-e29b-41d4-a716-446655440001"
        val firstRequest = paymentRequest(MethodId.P2wpkh.rawValue)
        val secondRequest = paymentRequest(MethodId.P2wpkh.rawValue, secondPaymentRequestId)
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(paymentRequestId = PAYMENT_REQUEST_ID),
                paymentRequestRecord(paymentRequestId = secondPaymentRequestId),
            ),
        )
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenReturn(paymentRequestRecord())
        val repo = paymentProofRepo()
        repo.prepare(firstRequest, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(firstRequest, ONCHAIN_ADDRESS).getOrThrow()
        repo.prepare(secondRequest, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(secondRequest, ONCHAIN_ADDRESS).getOrThrow()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(firstRequest, "ab".repeat(32)))

        repo.reconcile()

        assertEquals(
            listOf(PAYMENT_REQUEST_ID),
            repo.onchainPaymentResolutions.value.map { it.requestId.paymentRequestId },
        )
        assertNull(storedProofs.single { it.requestId == secondRequest.id }.proofData)
    }

    @Test
    fun `uncertain onchain payment does not infer broadcast from destination or amount`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.reconcile()

        assertTrue(storedProofs.single().onchainMatchingTransactionIdsBeforeAttempt.isEmpty())
        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `Shop reconciliation never holds proof mutex while finishing attempt activity`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, txid))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        val finishing = CompletableDeferred<Unit>()
        val proofLockReleased = CompletableDeferred<Unit>()
        whenever(lightningRepo.finishAcceptedShopActivity(request.id, txid)).doSuspendableAnswer {
            finishing.complete(Unit)
            proofLockReleased.await()
        }
        val reconciliation = launch { repo.reconcile() }
        finishing.await()
        withTimeout(1_000) {
            repo.backupSnapshot()
            proofLockReleased.complete(Unit)
            reconciliation.join()
        }
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `accepted Shop activity failure retains original proof for reconciliation before delivery`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, txid))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(lightningRepo.finishAcceptedShopActivity(request.id, txid))
            .thenThrow(IllegalStateException("local activity unavailable"))

        assertFalse(
            repo.completeOnchainPayment(
                request,
                txid,
                MethodId.P2wpkh.rawValue,
                OnchainSendOutcome.Accepted(txid),
            ),
        )
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().proofData)
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())

        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(COUNTERPARTY, true))
        repo.reconcile()
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().proofData)
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, true))

        doReturn(Unit).whenever(lightningRepo).finishAcceptedShopActivity(request.id, txid)
        repo.reconcile()

        assertTrue(storedProofs.isEmpty())
        verify(lightningRepo).completeAcceptedShopFollowup(request.id, txid)
        verify(paykitSdkService).submitPaymentProof(
            eq(request.counterparty),
            eq(request.counterpartyReceiverPath),
            eq(request.paymentRequestId),
            eq(MethodId.P2wpkh.rawValue),
            any(),
            isNull(),
        )
    }

    @Test
    fun `completed onchain proof retries after a newer send replaces the bounded guard`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenThrow(IllegalStateException("delivery unavailable"))
            .thenReturn(record)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        assertTrue(repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, OnchainSendOutcome.Accepted(txid)))
        assertEquals(txid, storedProofs.single().proofData)
        assertTrue(storedProofs.single().onchainAcceptanceVerified)

        val newerRequest = paymentRequest(MethodId.P2wpkh.rawValue, "550e8400-e29b-41d4-a716-446655440001")
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(newerRequest, "cd".repeat(32)))
        paymentProofRepo().reconcile()

        assertTrue(storedProofs.isEmpty())
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService, times(2)).submitPaymentProof(
            eq(request.counterparty), eq(request.counterpartyReceiverPath), eq(request.paymentRequestId),
            eq(MethodId.P2wpkh.rawValue), proofCaptor.capture(), isNull(),
        )
        assertTrue(proofCaptor.allValues.all { it.contains(txid) })
        verify(lightningRepo, never()).completeAcceptedShopFollowup(newerRequest.id, "cd".repeat(32))
    }

    @Test
    fun `legacy completed onchain proof without acknowledgement stays blocked after guard replacement`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        storedProofs = listOf(PendingPaykitPaymentProof(
            identity = LOCAL_IDENTITY, requestId = request.id, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue,
            kind = PaykitPaymentProofKind.Onchain, paymentStarted = true, paymentIdentifier = txid, proofData = txid,
        ))
        val newerRequest = paymentRequest(MethodId.P2wpkh.rawValue, "550e8400-e29b-41d4-a716-446655440001")
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(newerRequest, "cd".repeat(32)))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))

        paymentProofRepo().reconcile()

        assertEquals(1, storedProofs.size)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
    }

    @Test
    fun `txid-only onchain completion cannot mint acknowledged evidence`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))

        assertFalse(repo.completeOnchainPayment(request, "ab".repeat(32), MethodId.P2wpkh.rawValue))
        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `exact positive guard upgrades an unmarked completed proof durably`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        storedProofs = listOf(PendingPaykitPaymentProof(
            identity = LOCAL_IDENTITY, requestId = request.id, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue,
            kind = PaykitPaymentProofKind.Onchain, paymentStarted = true, paymentIdentifier = txid, proofData = txid,
        ))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(
            acceptedAttempt(request, txid).copy(evidence = OnchainSendEvidence.Observed),
        )
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenThrow(IllegalStateException("delivery unavailable"))
        paymentProofRepo().reconcile()

        assertTrue(storedProofs.single().onchainAcceptanceVerified)
        verify(lightningRepo).completeAcceptedShopFollowup(request.id, txid)
    }

    @Test
    fun `mismatched accepted outcome cannot verify a different proof txid`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()

        assertFalse(repo.completeOnchainPayment(
            request, "ab".repeat(32), MethodId.P2wpkh.rawValue, OnchainSendOutcome.Accepted("cd".repeat(32)),
        ))
        assertFalse(storedProofs.single().onchainAcceptanceVerified)
        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `malformed completed onchain proof is neither delivered nor used to complete the guard`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        storedProofs = listOf(PendingPaykitPaymentProof(
            identity = LOCAL_IDENTITY, requestId = request.id, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue,
            kind = PaykitPaymentProofKind.Onchain, paymentStarted = true, paymentIdentifier = txid,
            proofData = "cd".repeat(32),
        ))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, txid))

        paymentProofRepo().reconcile()

        assertEquals(1, storedProofs.size)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
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
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue).getOrThrow()

        val protectedRequestIds = repo.protectedRequestIdsForSubscriptionCancellation(
            LOCAL_IDENTITY,
            PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY, PaykitReceiverPaths.WALLET),
        ).getOrThrow()

        assertEquals(setOf(request.id), protectedRequestIds)
        assertEquals(listOf(request.id), storedProofs.map { it.requestId })
    }

    @Test
    fun `definite hardware prebroadcast denial releases only the exact original proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        val original = storedProofs.single()
        storedProofs = storedProofs + original.copy(onchainWalletId = "foreign-wallet")
        assertTrue(repo.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
        assertEquals(listOf(original.copy(onchainWalletId = "foreign-wallet")), storedProofs)
    }

    @Test
    fun `hardware cleanup cannot release attempted candidate foreign context or failed durable removal`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        val original = storedProofs.single()
        assertFalse(repo.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, true))
        assertFalse(repo.failHardwareOnchainPaymentBeforeDispatch(request, "foreign-wallet", LOCAL_IDENTITY, false))
        assertFalse(repo.failHardwareOnchainPaymentBeforeDispatch(request, WalletScope.default, LOCAL_IDENTITY, false))
        assertFalse(repo.failHardwareOnchainPaymentBeforeDispatch(request, walletId, COUNTERPARTY, false))
        assertFalse(
            repo.failHardwareOnchainPaymentBeforeDispatch(
                request.copy(paymentRequestId = "different-request"),
                walletId,
                LOCAL_IDENTITY,
                false,
            )
        )
        assertEquals(listOf(original), storedProofs)
        shouldFailNextSave = true
        assertFalse(repo.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
        assertEquals(listOf(original), storedProofs)
        assertEquals(
            PaykitPaymentRequestError.OperationInProgress,
            repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).exceptionOrNull()
        )
        for (retained in listOf(
            original.copy(paymentIdentifier = "ab".repeat(32)),
            original.copy(onchainAcceptanceVerified = true),
        )) {
            storedProofs = listOf(retained)
            assertFalse(repo.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
            assertEquals(listOf(retained), storedProofs)
        }
    }

    @Test
    fun `hardware core txid remains pending until fresh exact observation then resumes original proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(false))

        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(txid, storedProofs.single().paymentIdentifier)
        assertNull(storedProofs.single().proofData)
        assertFalse(storedProofs.single().onchainAcceptanceVerified)
        assertEquals(PaykitPaymentRequestError.OperationInProgress,
            repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).exceptionOrNull())
        repo.failOnchainPayment(request)
        assertEquals(1, storedProofs.size)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())

        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(true))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenReturn(paymentRequestRecord())
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, "cd".repeat(32)))
        paymentProofRepo().reconcile()
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), eq(MethodId.P2wpkh.rawValue),
            eq("""{"data":"$txid","type":"bitcoin-onchain-txid"}"""), isNull())
        assertTrue(storedProofs.isEmpty())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `hardware fresh observation submits completed durable proof with original wallet`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "cd".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(true))
        // No delivery session: preserve the verified proof durably for later delivery.
        assertTrue(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(txid, storedProofs.single().proofData)
        assertTrue(storedProofs.single().onchainAcceptanceVerified)
        assertEquals(walletId, storedProofs.single().onchainWalletId)
        assertEquals(request.id, repo.onchainPaymentResolutions.value.single().requestId)
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `hardware observation errors and changed transaction id retain only the original pending lookup`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.failure(AppError("lookup failed")))
        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertFalse(repo.completeHardwareOnchainPayment(request.id, "different-wallet", txid, LOCAL_IDENTITY))
        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, "cd".repeat(32), LOCAL_IDENTITY))
        repo.reconcile()
        assertEquals(txid, storedProofs.single().paymentIdentifier)
        assertNull(storedProofs.single().proofData)
        assertFalse(storedProofs.single().onchainAcceptanceVerified)
        verify(hwWalletRepo, times(2)).observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)
        verify(hwWalletRepo, never()).broadcastFunding(any())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `hardware callback cannot verify another identity with the same request and wallet`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        val original = storedProofs.single()
        val other = original.copy(identity = COUNTERPARTY)
        storedProofs = listOf(other)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(COUNTERPARTY, true))
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(true))

        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(listOf(other), storedProofs)
        verify(hwWalletRepo, never()).observeExactTransaction(any(), any(), any(), any())

        storedProofs = listOf(original, other)
        assertTrue(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertTrue(storedProofs.first { it.identity == LOCAL_IDENTITY }.onchainAcceptanceVerified)
        assertEquals(other, storedProofs.first { it.identity == COUNTERPARTY })
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `Core exception after hardware Shop start survives dismissal and reopen without a new payment`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.cancelPreparation(request)
        assertTrue(storedProofs.isEmpty())
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        val context = mock<Context>()
        whenever(context.getString(any())).thenReturn("message")
        val connected = mock<HwConnectedDevice>()
        val funding = HwFundingTransaction("psbt", 1000uL, 2.0f, 2000uL, 2uL)
        val signed = HwFundingSignedTx("signed-fixture-tx", 1000uL, 2uL, 2000uL)
        whenever(hwWalletRepo.needsPassphrase(walletId)).thenReturn(false)
        whenever(hwWalletRepo.reconnectTimeout(walletId)).thenReturn(30.seconds)
        whenever(hwWalletRepo.ensureConnected(walletId)).thenReturn(Result.success(connected))
        whenever(hwWalletRepo.composeFundingTransaction(walletId, ONCHAIN_ADDRESS, request.amountSats, 2uL))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(walletId, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("response lost after dispatch")))
        val send = HwSendViewModel(context, hwWalletRepo, mock(), mock<CoreService>(), mock())
        send.signAndBroadcast(HwSendRequest(walletId, ONCHAIN_ADDRESS, request.amountSats, 2uL, emptyList(),
            request.id, LOCAL_IDENTITY)) {
            repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
            true
        }
        advanceUntilIdle()
        assertFalse(send.uiState.value.isSigning)
        assertFalse(send.uiState.value.isBroadcastUnresolved)
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().paymentIdentifier)

        // Both generic failure and preparation cancellation must preserve an already dispatched Shop payment.
        repo.failOnchainPayment(request)
        repo.cancelPreparation(request)
        send.cancel()
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().proofData)
        assertEquals(PaykitPaymentRequestError.OperationInProgress,
            paymentProofRepo().prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain)
                .exceptionOrNull())
        verify(hwWalletRepo, times(1)).broadcastFunding(signed)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `hardware candidate save failure leaves started proof blocking a fresh payment`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        val original = storedProofs.single()
        shouldFailNextSave = true

        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(listOf(original), storedProofs)
        assertTrue(original.paymentStarted)
        assertNull(original.paymentIdentifier)
        assertNull(original.proofData)
        assertTrue(repo.prepare(request, MethodId.P2wpkh.rawValue, PaykitPaymentProofKind.Onchain)
            .exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        verify(hwWalletRepo, never()).observeExactTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    private fun paymentProofRepo() = PaykitPaymentProofRepo(
        ioDispatcher = testDispatcher,
        paykitSdkService = paykitSdkService,
        lightningRepo = lightningRepo,
        store = store,
        hwWalletRepo = hwWalletRepo,
    )

    private fun acceptedAttempt(request: PaykitPaymentRequest, txid: String) = OnchainSendAttempt(
        walletId = WalletScope.default,
        attemptId = "attempt-1",
        requestId = request.id,
        orderId = null,
        address = ONCHAIN_ADDRESS,
        amountSats = request.amountSats,
        isMaxAmount = false,
        feeRateSatsPerVByte = 1uL,
        isTransfer = false,
        channelId = null,
        tags = emptyList(),
        evidence = OnchainSendEvidence.Accepted,
        txid = txid,
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

    private fun readyLightningProof(paymentRequestId: String) = PendingPaykitPaymentProof(
        identity = LOCAL_IDENTITY,
        requestId = PaykitPaymentRequestId(
            counterparty = COUNTERPARTY,
            counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
            paymentRequestId = paymentRequestId,
        ),
        paymentEndpointIdentifier = MethodId.Bolt11.rawValue,
        kind = PaykitPaymentProofKind.Lightning,
        paymentStarted = true,
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
            conversion = null,
            paymentDeadline = null,
            metadata = mock<PrivateJsonObject>(),
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
        lastEventAt = "2027-01-15T08:00:00Z",
        invalidReason = null,
    )
}
