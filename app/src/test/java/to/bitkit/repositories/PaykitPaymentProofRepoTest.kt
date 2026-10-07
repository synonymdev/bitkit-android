package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaymentDeadline
import com.synonym.paykit.PaymentProofRecord
import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.WalletScope
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
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
    private var shouldFailProofRemoval = false
    private val keychain = mock<Keychain>()
    private val projectionStore = PaykitPaymentProofStore(keychain)
    private val projectionIdentity = MutableStateFlow<String?>(LOCAL_IDENTITY)
    private var storedProjectionJson: String? = null
    private var unreadableProjectionState = false
    private var requestStateChanges = 0

    @Before
    fun setUp() = test {
        storedProofs = emptyList()
        shouldFailNextLoad = false
        shouldFailNextSave = false
        shouldFailProofRemoval = false
        whenever(store.hasPendingProofs()).thenReturn(true)
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
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
        whenever(keychain.loadString(any())).thenAnswer {
            if (unreadableProjectionState) "not-json" else storedProjectionJson
        }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer {
            storedProjectionJson = it.getArgument(1)
        }
        whenever(keychain.delete(any())).doSuspendableAnswer { storedProjectionJson = null }
    }

    @Test
    fun `unstarted proof preparation notifies backup without refreshing requests`() = test {
        observeRequestStateChanges()
        val repo = paymentProofRepo(projectionStore)
        val request = paymentRequest(MethodId.Bolt11.rawValue)

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        runCurrent()
        assertEquals(0, requestStateChanges)
        assertEquals(1L, projectionStore.backupStateVersion.value)

        repo.cancelPreparation(request)
        runCurrent()
        assertEquals(0, requestStateChanges)
        assertEquals(2L, projectionStore.backupStateVersion.value)
    }

    @Test
    fun `request refresh follows completed and in flight proof projections`() = test {
        observeRequestStateChanges()
        val started = readyLightningProof(PAYMENT_REQUEST_ID).copy(proofData = null)
        val changes = listOf(
            listOf(started) to 1,
            listOf(started.copy(paymentIdentifier = "different-payment")) to 1,
            listOf(started.copy(proofData = PREIMAGE)) to 2,
            listOf(started.copy(proofData = PREIMAGE, kind = PaykitPaymentProofKind.Onchain)) to 3,
            emptyList<PendingPaykitPaymentProof>() to 4,
        )

        changes.forEach { (proofs, expectedChanges) ->
            projectionStore.save(proofs)
            runCurrent()
            assertEquals(expectedChanges, requestStateChanges)
        }
        assertEquals(changes.size.toLong(), projectionStore.backupStateVersion.value)
    }

    @Test
    fun `request proof projection is scoped to identity and resets after sign out`() = test {
        observeRequestStateChanges()
        projectionStore.save(listOf(readyLightningProof(PAYMENT_REQUEST_ID).copy(identity = COUNTERPARTY)))
        runCurrent()
        assertEquals(0, requestStateChanges)

        projectionIdentity.update { COUNTERPARTY }
        runCurrent()
        assertEquals(1, requestStateChanges)
        projectionIdentity.update { null }
        runCurrent()
        assertEquals(2, requestStateChanges)
        projectionStore.save(emptyList())
        runCurrent()
        assertEquals(2, requestStateChanges)
        projectionIdentity.update { LOCAL_IDENTITY }
        runCurrent()
        assertEquals(3, requestStateChanges)
    }

    @Test
    fun `unreadable proof changes keep requesting refresh and recover after repair`() = test {
        observeRequestStateChanges()
        unreadableProjectionState = true
        repeat(2) { index ->
            projectionStore.save(emptyList())
            runCurrent()
            assertEquals(index + 1, requestStateChanges)
        }

        unreadableProjectionState = false
        projectionStore.save(emptyList())
        runCurrent()
        assertEquals(3, requestStateChanges)
        projectionStore.save(emptyList())
        runCurrent()
        assertEquals(3, requestStateChanges)
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
    fun `completed lightning proof retains the payment app when retrying after repository restart`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(IllegalStateException("temporary failure"))
            .thenReturn(record)
        val firstRepo = paymentProofRepo()

        firstRepo.prepare(request, MethodId.Bolt11.rawValue, "merchant", PaykitPaymentProofKind.Lightning).getOrThrow()
        firstRepo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "merchant").getOrThrow()
        firstRepo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        assertEquals(PREIMAGE, storedProofs.single().proofData)

        paymentProofRepo().reconcile()

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService, times(2)).submitPaymentProof(
            counterparty = any(),
            paymentRequestId = any(),
            paymentAppId = eq("merchant"),
            paymentEndpointIdentifier = endpointCaptor.capture(),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
            conversionQuoteId = isNull(),
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
    fun `completed payments remain reconcilable after their payment deadline`() = test {
        val deadline = PaymentDeadline.At("2000-01-01T00:00:00Z")
        val record = paymentRequestRecord().let {
            it.copy(
                state = PaymentRequestLifecycleState.ACCEPTED,
                terms = requireNotNull(it.terms).copy(paymentDeadline = deadline),
            )
        }
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenReturn(record)
        val lightning = readyLightningProof(PAYMENT_REQUEST_ID)
        val onchain = lightning.copy(
            kind = PaykitPaymentProofKind.Onchain,
            paymentEndpointIdentifier = MethodId.P2wpkh.rawValue,
            paymentIdentifier = "ab".repeat(32),
            proofData = "ab".repeat(32),
        )

        listOf(lightning, onchain).forEach { proof ->
            storedProofs = listOf(proof)

            paymentProofRepo().reconcile()

            assertTrue(storedProofs.isEmpty())
            verify(paykitSdkService).submitPaymentProof(
                counterparty = eq(COUNTERPARTY),
                paymentRequestId = eq(PAYMENT_REQUEST_ID),
                paymentAppId = eq("bitkit"),
                paymentEndpointIdentifier = eq(proof.paymentEndpointIdentifier),
                proofJson = any(),
                billingPeriod = isNull(),
            )
        }
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(IllegalStateException("temporary failure"))
            .thenReturn(paymentRequestRecord(paymentRequestId = secondPaymentRequestId))

        paymentProofRepo().reconcile()

        val paymentRequestIdCaptor = argumentCaptor<String>()
        verify(paykitSdkService, times(2)).submitPaymentProof(
            counterparty = any(),
            paymentRequestId = paymentRequestIdCaptor.capture(),
            paymentAppId = eq("bitkit"),
            paymentEndpointIdentifier = any(),
            proofJson = any(),
            billingPeriod = isNull(),
            conversionQuoteId = isNull(),
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
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val firstRepo = paymentProofRepo()

        firstRepo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        firstRepo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        assertNull(storedProofs.single().proofData)

        paymentProofRepo().reconcile()

        verify(lightningRepo).getPayments()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            paymentRequestId = any(),
            paymentAppId = eq("bitkit"),
            paymentEndpointIdentifier = any(),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
            conversionQuoteId = isNull(),
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
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `mismatched lightning preimage is not submitted`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, "01".repeat(32))

        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
    }

    @Test
    fun `existing proof suppresses duplicate submission`() = test {
        val existingProofJson = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"type":"${PaykitPaymentProofKind.Lightning.type}","data":"$PREIMAGE"}"""
        }
        val existingProof = mock<PaymentProofRecord> {
            on { billingPeriod } doReturn null
            on { paymentEndpointIdentifier } doReturn MethodId.Bolt11.rawValue
            on { paymentAppId } doReturn "bitkit"
            on { proof } doReturn existingProofJson
        }
        val record = paymentRequestRecord(listOf(existingProof))
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
    }

    @Test
    fun `failed lightning payment clears persisted correlation`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.failLightningPayment(PAYMENT_HASH)

        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
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
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        for (error in errors) {
            val request = paymentRequest(MethodId.Bolt11.rawValue)
            val repo = paymentProofRepo()
            repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
            repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()

            val failed = repo.failLightningPayment(PAYMENT_HASH, error)
            repo.cancelPreparation(request)
            val restartedRepo = paymentProofRepo()
            restartedRepo.reconcile()

            assertFalse(failed)
            assertTrue(storedProofs.single().paymentStarted)
            assertEquals(PAYMENT_HASH, storedProofs.single().paymentIdentifier)
            val retry = restartedRepo.prepare(
                request,
                MethodId.Bolt11.rawValue,
                "bitkit",
                PaykitPaymentProofKind.Lightning
            )
            assertTrue(retry.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)

            restartedRepo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)
            assertTrue(storedProofs.isEmpty())
        }
        verify(
            paykitSdkService,
            times(errors.size)
        ).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
    }

    @Test
    fun `definite lightning submission failure clears proof`() = test {
        val errors = listOf(
            ServiceError.NodeNotSetup(),
            ServiceError.NodeNotStarted(),
            to.bitkit.utils.AppError(ServiceError.PaymentDeadlineExpired()),
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
            repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
            repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()

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
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        assertTrue(storedProofs.single().paymentStarted)
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit")

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            any(),
            any(),
            any(),
            endpointCaptor.capture(),
            proofCaptor.capture(),
            isNull(),
            conversionQuoteId = isNull(),
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

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        repo.cancelPreparation(request)

        assertTrue(storedProofs.single().paymentStarted)
    }

    @Test
    fun `definite onchain failure clears started proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        repo.failOnchainPayment(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `onchain failure clears started proof without a live identity`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        repo.failOnchainPayment(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `cancel preparation clears proof without a live identity`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        repo.cancelPreparation(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `cancel preparation retains proofs when identity lookup fails`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        val preparedProofs = storedProofs
        doSuspendableAnswer {
            throw PaykitException.ConcurrentUpdate("concurrent_update", "Identity read locked")
        }.whenever(paykitSdkService).identityStatus()

        repo.cancelPreparation(request)

        assertEquals(preparedProofs, storedProofs)
        doSuspendableAnswer { throw CancellationException("Cancelled") }.whenever(paykitSdkService).identityStatus()
        assertFailsWith<CancellationException> { repo.cancelPreparation(request) }
        assertEquals(preparedProofs, storedProofs)
    }

    @Test
    fun `broadcast transaction id is retained without a live identity`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        val txid = "ab".repeat(32)
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit")

        assertEquals(txid, storedProofs.single().paymentIdentifier)
        assertEquals(txid, storedProofs.single().proofData)
    }

    @Test
    fun `onchain proof submits without prepared proof`() = test {
        val txid = "ab".repeat(32)
        val endpoint = MethodId.P2wpkh.rawValue
        val request = paymentRequest(endpoint)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.completeOnchainPayment(request, txid, endpoint, "bitkit")

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), eq(endpoint), any(), isNull(), isNull())
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
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), any(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        val periodCaptor = argumentCaptor<PaykitBillingPeriod>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            paymentRequestId = any(),
            paymentAppId = eq("bitkit"),
            paymentEndpointIdentifier = any(),
            proofJson = any(),
            billingPeriod = periodCaptor.capture(),
            conversionQuoteId = isNull(),
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
            on { paymentAppId } doReturn "bitkit"
            on { proof } doReturn existingProofJson
        }
        val record = paymentRequestRecord(listOf(existingProof))
        val request = paymentRequest(MethodId.Bolt11.rawValue, billingPeriod = currentPeriod)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), any(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `lightning retry is rejected while earlier payment is unresolved`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        val retry = repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning)

        assertTrue(retry.exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        assertEquals(PAYMENT_HASH, storedProofs.single().paymentIdentifier)

        repo.failLightningPayment(PAYMENT_HASH)
        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        assertEquals(1, storedProofs.size)
    }

    @Test
    fun `cleared store does not restore cached proofs`() = test {
        val firstRequest = paymentRequest(MethodId.Bolt11.rawValue)
        val secondRequestId = "550e8400-e29b-41d4-a716-446655440001"
        val secondRequest = paymentRequest(MethodId.Bolt11.rawValue, secondRequestId)
        val repo = paymentProofRepo()

        repo.prepare(firstRequest, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        storedProofs = emptyList()
        repo.prepare(secondRequest, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()

        assertEquals(1, storedProofs.size)
        assertEquals(secondRequestId, storedProofs.single().requestId.paymentRequestId)
    }

    @Test
    fun `onchain proof submits when completed proof cannot be persisted`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit")

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `completed onchain proof remains durable when persistence and submission initially fail`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(IllegalStateException("transient submission failure"))
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit")

        assertEquals(txid, storedProofs.single().proofData)
        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
    }

    @Test
    fun `onchain proof cleanup failure does not retry delivery`() = test {
        val txid = "ab".repeat(32)
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        shouldFailProofRemoval = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit")

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        assertEquals(txid, storedProofs.single().proofData)
    }

    @Test
    fun `onchain proof submits when prepared proof cannot be loaded`() = test {
        val txid = "ab".repeat(32)
        val endpoint = MethodId.P2wpkh.rawValue
        val request = paymentRequest(endpoint)
        val record = paymentRequestRecord()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, endpoint, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        shouldFailNextLoad = true
        repo.completeOnchainPayment(request, txid, endpoint, "bitkit")

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            paymentRequestId = any(),
            paymentAppId = eq("bitkit"),
            paymentEndpointIdentifier = endpointCaptor.capture(),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
            conversionQuoteId = isNull(),
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
        whenever(
            paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull())
        ).thenReturn(record)
        whenever(
            onchainPaymentLookup.transactionId(
                ONCHAIN_ADDRESS,
                request.amount.atomic,
                emptySet(),
                WalletScope.default,
            )
        ).thenReturn(txid)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        repo.reconcile()

        assertTrue(storedProofs.isEmpty())
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = eq(request.counterparty),
            paymentRequestId = eq(request.paymentRequestId),
            paymentAppId = eq("bitkit"),
            paymentEndpointIdentifier = eq(MethodId.P2wpkh.rawValue),
            proofJson = proofCaptor.capture(),
            billingPeriod = isNull(),
            conversionQuoteId = isNull(),
        )
        assertTrue(proofCaptor.firstValue.contains(txid))
    }

    @Test
    fun `reconcile publishes every onchain resolution`() = test {
        val secondPaymentRequestId = "550e8400-e29b-41d4-a716-446655440001"
        val firstRequest = paymentRequest(MethodId.P2wpkh.rawValue)
        val secondRequest = paymentRequest(MethodId.P2wpkh.rawValue, secondPaymentRequestId)
        whenever(paykitSdkService.paymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(paymentRequestId = PAYMENT_REQUEST_ID),
                paymentRequestRecord(paymentRequestId = secondPaymentRequestId),
            ),
        )
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull(), isNull()))
            .thenReturn(paymentRequestRecord())
        whenever(onchainPaymentLookup.transactionId(any(), any(), any(), any()))
            .thenReturn("ab".repeat(32), "cd".repeat(32))
        val repo = paymentProofRepo()
        repo.prepare(firstRequest, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(firstRequest, ONCHAIN_ADDRESS, firstRequest.amount.atomic).getOrThrow()
        repo.prepare(secondRequest, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(secondRequest, ONCHAIN_ADDRESS, secondRequest.amount.atomic, "hardware-wallet").getOrThrow()

        repo.reconcile()

        assertEquals(
            listOf(PAYMENT_REQUEST_ID, secondPaymentRequestId),
            repo.onchainPaymentResolutions.value.map { it.requestId.paymentRequestId },
        )
        assertEquals(
            listOf(WalletScope.default, "hardware-wallet"),
            repo.onchainPaymentResolutions.value.map { it.walletId },
        )
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
                request.amount.atomic,
                setOf(oldTransactionId),
                WalletScope.default,
            )
        ).thenReturn(null)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, request.amount.atomic).getOrThrow()
        repo.reconcile()

        assertEquals(setOf(oldTransactionId), storedProofs.single().onchainMatchingTransactionIdsBeforeAttempt)
        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `cancel preparation does not remove another identity proof`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val otherIdentityProof = PendingPaykitPaymentProof(
            identity = "pubky${"a".repeat(52)}",
            requestId = request.id,
            paymentEndpointIdentifier = MethodId.Bolt11.rawValue,
            kind = PaykitPaymentProofKind.Lightning,
            paymentAppId = "bitkit",
        )
        storedProofs = listOf(otherIdentityProof)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
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
        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()

        val protectedRequestIds = repo.protectedRequestIdsForSubscriptionCancellation(
            LOCAL_IDENTITY,
            PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY),
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
        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()

        val protectedRequestIds = repo.protectedRequestIdsForSubscriptionCancellation(
            LOCAL_IDENTITY,
            PaykitSubscriptionId(PAYMENT_REQUEST_ID, COUNTERPARTY),
        ).getOrThrow()

        assertEquals(setOf(request.id), protectedRequestIds)
        assertEquals(listOf(request.id), storedProofs.map { it.requestId })
    }

    private fun TestScope.observeRequestStateChanges() {
        paymentProofRepo(projectionStore).paymentRequestStateChanges(projectionIdentity)
            .onEach { requestStateChanges++ }
            .launchIn(backgroundScope)
        runCurrent()
    }

    private fun paymentProofRepo(store: PaykitPaymentProofStore = this.store) = PaykitPaymentProofRepo(
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
        paymentReference = "test-reference",
        amount = PaykitAmount(PaykitAsset.BTC, 1_000uL),
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf(endpoint),
        billingPeriod = billingPeriod,
    )

    private fun readyLightningProof(paymentRequestId: String) = PendingPaykitPaymentProof(
        identity = LOCAL_IDENTITY,
        requestId = PaykitPaymentRequestId(
            counterparty = COUNTERPARTY,
            paymentRequestId = paymentRequestId,
        ),
        paymentEndpointIdentifier = MethodId.Bolt11.rawValue,
        kind = PaykitPaymentProofKind.Lightning,
        paymentStarted = true,
        paymentIdentifier = PAYMENT_HASH,
        proofData = PREIMAGE,
        paymentAppId = "bitkit",
    )

    private fun paymentRequestRecord(
        paymentProofs: List<PaymentProofRecord> = emptyList(),
        paymentRequestId: String = PAYMENT_REQUEST_ID,
    ) = PaymentRequestRecord(
        counterparty = COUNTERPARTY,
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
        lastEventAt = "2027-01-15T08:00:00Z",
        invalidReason = null,
        proposalAppId = "bitkit",
        payerAppId = null,
        executionClaimAppId = null,
    )
}
