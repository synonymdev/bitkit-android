package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.BroadcastException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import com.synonym.paykit.PubkyIdentityCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
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
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.models.WalletScope
import to.bitkit.services.CoreService
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.send.HwSendRequest
import to.bitkit.ui.screens.wallets.send.HwSendViewModel
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import to.bitkit.utils.ServiceError
import to.bitkit.utils.SignedTransactionId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
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
    private val store = mock<PaykitPaymentProofStore>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
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
        whenever(privatePaykitRepo.consumePrivatePaymentList(any(), any())).thenReturn(Result.success(Unit))
        whenever(store.hasPendingProofs()).thenReturn(true)
        whenever(
            paykitSdkService.identityStatus()
        ).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
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
        whenever(keychain.loadString(any())).thenAnswer {
            if (unreadableProjectionState) "not-json" else storedProjectionJson
        }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer {
            storedProjectionJson = it.getArgument(1)
        }
        whenever(keychain.delete(any())).doSuspendableAnswer { storedProjectionJson = null }
    }

    @Test
    fun `proof without original app provenance stays retained and is not submitted`() = test {
        storedProofs = listOf(readyLightningProof(PAYMENT_REQUEST_ID).copy(paymentAppId = ""))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        paymentProofRepo().reconcile()
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
        assertEquals("", storedProofs.single().paymentAppId)
        assertTrue(storedProofs.single().paymentStarted)
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        var admitted = attempts.admit(
            walletId = WalletScope.default, requestId = request.id, orderId = null,
            address = ONCHAIN_ADDRESS, amountSats = request.amountSats, isMaxAmount = false,
            feeRateSatsPerVByte = 1uL, isTransfer = false, channelId = null, tags = emptyList(),
            payerIdentity = LOCAL_IDENTITY,
            beforeSendAttempt = {
                repo.markOnchainPaymentStarted(
                    request,
                    ONCHAIN_ADDRESS,
                    privatePaymentListVersion = 7uL,
                    previousPrivatePaymentListVersion = 6uL,
                ).getOrThrow()
            },
        )
        assertTrue(admitted.preparationPending)
        assertTrue(storedProofs.single().paymentStarted)
        assertFalse(attempts.releaseInterruptedShopPreparation { error("live preparation must not be cleared") })
        admitted = attempts.retainPreparedReceipt(admitted.attemptId, 0,
            OnchainPreparedReceipt("ab".repeat(32), listOf(OnchainSendInput("11".repeat(32), 0u)),
                ONCHAIN_ADDRESS, request.amountSats), false)
        assertFalse(attempts.releaseInterruptedShopPreparation { error("live prepared receipt must not be cleared") })
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
        whenever(privatePaykitRepo.releasePrivatePaymentListVersion(request.id.counterparty, 7uL, 6uL))
            .thenReturn(Result.failure(IllegalStateException("private boundary storage failed")), Result.success(Unit))
        repo.reconcile()
        assertEquals(admitted, reopened.current())
        assertEquals(listOf(startedProof), storedProofs)
        shouldFailProofRemoval = true
        repo.reconcile()
        assertEquals(admitted, reopened.current())
        assertTrue(storedProofs.single().paymentStarted)
        repo.reconcile()
        assertNull(reopened.current())
        assertTrue(storedProofs.isEmpty())
        verify(privatePaykitRepo, times(3)).releasePrivatePaymentListVersion(request.id.counterparty, 7uL, 6uL)
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        assertFalse(storedProofs.single().paymentStarted)
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `lightning association cannot bypass a started onchain request`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue).copy(
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.P2wpkh.rawValue, MethodId.Bolt11.rawValue),
        )
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()

        val result = repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit")

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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()

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
    fun `completed lightning proof retains the payment app when retrying after repository restart`() = test {
        val record = paymentRequestRecord()
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
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
            onchainAddress = ONCHAIN_ADDRESS,
            onchainAmountSats = paymentRequest(MethodId.P2wpkh.rawValue).amountSats,
        )

        listOf(lightning, onchain).forEach { proof ->
            if (proof.kind == PaykitPaymentProofKind.Onchain) {
                whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(
                    acceptedAttempt(paymentRequest(MethodId.P2wpkh.rawValue), requireNotNull(proof.paymentIdentifier)),
                )
            }
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
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

        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
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
            on { paymentAppId } doReturn "bitkit"
            on { proof } doReturn existingProofJson
        }
        val record = paymentRequestRecord(listOf(existingProof))
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(record))
        val repo = paymentProofRepo()

        val result = repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning)

        assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
        assertTrue(storedProofs.isEmpty())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `accepted onchain attempt blocks Lightning before its proof is queued`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        whenever(lightningRepo.currentOnchainSendAttempt())
            .thenReturn(acceptedAttempt(request, "ab".repeat(32)))

        val result = paymentProofRepo().prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning)

        assertEquals(PaykitPaymentRequestError.OperationInProgress, result.exceptionOrNull())
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `failed lightning payment clears persisted correlation`() = test {
        val request = paymentRequest(MethodId.Bolt11.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
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
        verify(paykitSdkService, times(errors.size)).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        assertTrue(storedProofs.single().paymentStarted)
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted(txid))

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

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.cancelPreparation(request)

        assertTrue(storedProofs.single().paymentStarted)
    }

    @Test
    fun `definite onchain failure clears started proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        repo.failOnchainPayment(request)

        assertTrue(storedProofs.isEmpty())
    }

    @Test
    fun `definite software failure retains proof until private boundary release succeeds`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(
            request, ONCHAIN_ADDRESS,
            privatePaymentListVersion = 7uL, previousPrivatePaymentListVersion = 6uL,
        ).getOrThrow()
        val original = storedProofs.single()
        whenever(privatePaykitRepo.releasePrivatePaymentListVersion(request.id.counterparty, 7uL, 6uL))
            .thenReturn(Result.failure(IllegalStateException("storage")), Result.success(Unit))
        repo.failOnchainPayment(request)
        assertEquals(listOf(original), storedProofs)
        repo.failOnchainPayment(request)
        assertTrue(storedProofs.isEmpty())
        verify(privatePaykitRepo, times(2)).releasePrivatePaymentListVersion(request.id.counterparty, 7uL, 6uL)
    }

    @Test
    fun `onchain failure clears started proof without a live identity`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
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
    fun `accepted callback without live payer identity keeps proof pending`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        val txid = "ab".repeat(32)
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(paykitSdkService.identityStatus()).thenReturn(null)

        assertFalse(repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted(txid)))

        assertNull(storedProofs.single().paymentIdentifier)
        assertNull(storedProofs.single().proofData)
        assertTrue(storedProofs.single().paymentStarted)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
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

        repo.completeOnchainPayment(request, txid, endpoint, "bitkit", OnchainSendOutcome.Accepted(txid))

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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), any())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.Bolt11.rawValue, "bitkit", PaykitPaymentProofKind.Lightning).getOrThrow()
        repo.associateLightningPayment(request, PAYMENT_HASH, MethodId.Bolt11.rawValue, "bitkit").getOrThrow()
        repo.completeLightningPayment(PAYMENT_HASH, PREIMAGE)

        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), any(), any(), any())
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
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(record)
        val repo = paymentProofRepo()

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted(txid))

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

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailNextSave = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted(txid))

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

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailProofRemoval = true
        repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted(txid))

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

        repo.prepare(request, endpoint, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        shouldFailNextLoad = true
        repo.completeOnchainPayment(request, txid, endpoint, "bitkit", OnchainSendOutcome.Accepted(txid))

        val endpointCaptor = argumentCaptor<String>()
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService).submitPaymentProof(
            counterparty = any(),
            paymentRequestId = any(),
            paymentAppId = eq("bitkit"),
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

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, txid))
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
        )
        assertTrue(proofCaptor.firstValue.contains(txid))
    }

    @Test
    fun `typed accepted callback cannot invent proof when original guard cannot be read`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        val original = storedProofs.single()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenThrow(IllegalStateException("guard unavailable"))
        assertFalse(
            repo.completeOnchainPayment(
                request,
                "ab".repeat(32),
                MethodId.P2wpkh.rawValue, "bitkit",
                OnchainSendOutcome.Accepted("ab".repeat(32)),
            )
        )
        assertEquals(listOf(original), storedProofs)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `shared golden restored software proof authorizes and completes exact observed successor`() = test {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val backup = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<to.bitkit.models.WalletBackupV1>(bytes.decodeToString())
        val state = requireNotNull(backup.paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        wire.validateProofs(state.pendingProofs, WalletScope.default)
        val attempt = wire.restored("regtest", wire.wallet.binding, WalletScope.default, 2)
        val repo = paymentProofRepo()
        repo.restoreBackup(state.pendingProofs)
        assertEquals(WalletScope.default, storedProofs.single().onchainWalletId)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(requireNotNull(wire.payerIdentity), PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt)
        repo.authorizeOnchainRecovery(attempt).getOrThrow()
        assertEquals(state.pendingProofs.single().paymentIdentifier, storedProofs.single().paymentIdentifier)
        val requestId = requireNotNull(wire.requestId)
        val request = paymentRequest(state.pendingProofs.single().paymentEndpointIdentifier).copy(
            paymentRequestId = requestId.paymentRequestId,
            counterparty = requestId.counterparty,
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
                MethodId.P2wpkh.rawValue, "bitkit",
                OnchainSendOutcome.Accepted(winner),
            )
        )

        val proof = storedProofs.single()
        assertEquals(LOCAL_IDENTITY, proof.identity)
        assertEquals(request.id, proof.requestId)
        assertEquals(winner, proof.paymentIdentifier)
        assertEquals(winner, proof.proofData)
        assertTrue(proof.onchainAcceptanceVerified)
        assertFalse(repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).isSuccess)
    }

    @Test
    fun `original retry authorization retains payer proof and rejects identity switch`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val original = "cd".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(COUNTERPARTY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        storedProofs = listOf(proof, proof.copy(identity = COUNTERPARTY))
        assertTrue(repo.authorizeOnchainRecovery(attempt).isFailure)
        assertEquals(listOf(proof, proof.copy(identity = COUNTERPARTY)), storedProofs)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(attempt.copy(payerIdentity = null))
        assertTrue(repo.authorizeOnchainRecovery(attempt.copy(payerIdentity = null)).isFailure)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `candidate winner cannot replace a proof bound outside its original family`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val winner = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
        repo.prepare(firstRequest, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(firstRequest, ONCHAIN_ADDRESS).getOrThrow()
        repo.prepare(secondRequest, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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

        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(request, txid))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(lightningRepo.finishAcceptedShopActivity(request.id, txid))
            .thenThrow(IllegalStateException("local activity unavailable"))

        assertFalse(
            repo.completeOnchainPayment(
                request,
                txid,
                MethodId.P2wpkh.rawValue, "bitkit",
                OnchainSendOutcome.Accepted(txid),
            ),
        )
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().proofData)
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())

        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(COUNTERPARTY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
        repo.reconcile()
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().proofData)
        verify(lightningRepo, never()).completeAcceptedShopFollowup(any(), any())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(LOCAL_IDENTITY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))

        doReturn(Unit).whenever(lightningRepo).finishAcceptedShopActivity(request.id, txid)
        repo.reconcile()

        assertTrue(storedProofs.isEmpty())
        verify(lightningRepo).completeAcceptedShopFollowup(request.id, txid)
        verify(paykitSdkService).submitPaymentProof(
            eq(request.counterparty),
            eq(request.paymentRequestId),
            eq("bitkit"),
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        assertTrue(repo.completeOnchainPayment(request, txid, MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted(txid)))
        assertEquals(txid, storedProofs.single().proofData)
        assertTrue(storedProofs.single().onchainAcceptanceVerified)

        val newerRequest = paymentRequest(MethodId.P2wpkh.rawValue, "550e8400-e29b-41d4-a716-446655440001")
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(acceptedAttempt(newerRequest, "cd".repeat(32)))
        paymentProofRepo().reconcile()

        assertTrue(storedProofs.isEmpty())
        val proofCaptor = argumentCaptor<String>()
        verify(paykitSdkService, times(2)).submitPaymentProof(
            eq(request.counterparty), eq(request.paymentRequestId), eq("bitkit"),
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
            identity = LOCAL_IDENTITY, requestId = request.id, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue, paymentAppId = "bitkit",
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))

        assertFalse(repo.completeOnchainPayment(request, "ab".repeat(32), MethodId.P2wpkh.rawValue, "bitkit"))
        assertNull(storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `exact positive guard upgrades an unmarked completed proof durably`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        storedProofs = listOf(PendingPaykitPaymentProof(
            identity = LOCAL_IDENTITY, requestId = request.id, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue, paymentAppId = "bitkit",
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS).getOrThrow()

        assertFalse(repo.completeOnchainPayment(
            request, "ab".repeat(32), MethodId.P2wpkh.rawValue, "bitkit", OnchainSendOutcome.Accepted("cd".repeat(32)),
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
            identity = LOCAL_IDENTITY, requestId = request.id, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue, paymentAppId = "bitkit",
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

    @Test
    fun `definite hardware prebroadcast denial releases only the exact original proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
            repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).exceptionOrNull()
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
    fun `denied hardware cleanup retries original private version before removing proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId, 7uL).getOrThrow()
        assertTrue(repo.retainHardwareOnchainCandidate(request.id, walletId, txid, LOCAL_IDENTITY,
            ONCHAIN_ADDRESS, request.amountSats))
        assertTrue(repo.clearHardwareOnchainCandidateBeforeDispatch(request.id, walletId, txid, LOCAL_IDENTITY,
            ONCHAIN_ADDRESS, request.amountSats, false))
        whenever(privatePaykitRepo.releasePrivatePaymentListVersion(request.id.counterparty, 7uL))
            .thenReturn(Result.failure(IllegalStateException("storage")), Result.success(Unit))
        assertTrue(runCatching { repo.backupSnapshot() }.isFailure)
        paymentProofRepo().reconcile()
        assertTrue(storedProofs.single().hardwareDispatchDenied)
        assertEquals(txid, storedProofs.single().paymentIdentifier)
        paymentProofRepo().reconcile()
        assertTrue(storedProofs.isEmpty())
        verify(privatePaykitRepo, times(2)).releasePrivatePaymentListVersion(request.id.counterparty, 7uL)
    }

    @Test
    fun `queued hardware denial survives reopen and finishes cleanup before another preparation`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        assertTrue(repo.retainHardwareOnchainCandidate(request.id, walletId, txid, LOCAL_IDENTITY,
            ONCHAIN_ADDRESS, request.amountSats))
        assertTrue(repo.clearHardwareOnchainCandidateBeforeDispatch(request.id, walletId, txid, LOCAL_IDENTITY,
            ONCHAIN_ADDRESS, request.amountSats, false))
        // Kill after the durable denial, before the sheet's cleanup callback.
        val reopened = paymentProofRepo()
        reopened.reconcile()
        assertTrue(storedProofs.isEmpty())
        assertTrue(reopened.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).isSuccess)
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `first queued expiry clears only the exact unsubmitted hardware candidate`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        assertTrue(repo.retainHardwareOnchainCandidate(request.id, walletId, txid, LOCAL_IDENTITY,
            ONCHAIN_ADDRESS, request.amountSats))
        val retained = storedProofs.single()
        suspend fun clear(prior: Boolean = false, candidate: String = txid, address: String = ONCHAIN_ADDRESS) =
            repo.clearHardwareOnchainCandidateBeforeDispatch(request.id, walletId, candidate, LOCAL_IDENTITY,
                address, request.amountSats, prior)
        assertFalse(clear(prior = true))
        assertFalse(clear(candidate = "cd".repeat(32)))
        assertFalse(clear(address = "other-address"))
        for (accepted in listOf(retained.copy(onchainAcceptanceVerified = true), retained.copy(proofData = txid))) {
            storedProofs = listOf(accepted)
            assertFalse(clear())
            assertEquals(listOf(accepted), storedProofs)
        }
        storedProofs = listOf(retained)
        assertTrue(clear())
        assertEquals(txid, storedProofs.single().paymentIdentifier)
        assertTrue(storedProofs.single().hardwareDispatchDenied)
        assertTrue(repo.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
        assertTrue(storedProofs.isEmpty())
    }

    @Test
    @Suppress("LongMethod")
    fun `signed hardware Shop receipt restores exact bytes before explicit retry`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val signed =
            HwFundingSignedTx(
                "02000000000101f7c5a048189164c6b05b07516b5dbb9c826c601d12dc4ed97f0069618b8b7c1601" +
                    "00000000fdffffff024179010000000000160014f066a63663b0d464b31a7a88619beae011c3fb7b" +
                    "e80300000000000016001483ea855bb508cb08ed9e8cf9152d8927871c19aa02473044022052c5a1" +
                    "5ade616af16f314bcc2ae15bf4ef4996e0f2315794e647ba6c955745b602200f3095f4a7deb39a94" +
                    "716c0fd2001a2fbff1861a8ff0c2015739a40a62891c22012102cb13c86b55418d0e3bccf2911539" +
                    "4e1fb6a9f209d3f59dc9bbb0805b253464cb724c0300",
                141uL,
                2uL,
                request.amountSats + 141uL
            )
        val txid = SignedTransactionId.fromHex(signed.serializedTx)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        // Snapshot at the authorization boundary, before any broadcast callback can save a receipt.
        repo.markOnchainPaymentStarted(
            request,
            ONCHAIN_ADDRESS,
            walletId,
            7uL,
            signedTx = signed,
            previousPrivatePaymentListVersion = 6uL,
        ).getOrThrow()
        assertEquals(false, storedProofs.single().hardwareDispatchAttempted)
        val encoded = Json.encodeToString(repo.backupSnapshot())
        storedProofs = emptyList()
        val reopened = paymentProofRepo()
        reopened.restoreBackup(Json.decodeFromString<List<PaykitPaymentStateBackup.Proof>>(encoded))
        whenever(privatePaykitRepo.consumePrivatePaymentList(eq(request.counterparty), any())).thenReturn(Result.success(Unit))
        assertEquals(
            RetainedHardwareOnchainPayment(signed, false),
            reopened.retainedHardwareOnchainPayment(
                request.id,
                walletId,
                LOCAL_IDENTITY,
                ONCHAIN_ADDRESS,
                request.amountSats
            )
        )
        verify(privatePaykitRepo).consumePrivatePaymentList(eq(request.counterparty), argThat { paymentListVersion == 7uL })
        val otherRequest = paymentRequest(MethodId.P2wpkh.rawValue, paymentRequestId = "another-order")
        assertTrue(reopened.prepare(otherRequest, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).isFailure)
        whenever(privatePaykitRepo.consumePrivatePaymentList(eq(request.counterparty), any()))
            .thenReturn(Result.failure(IllegalStateException("private state unavailable")))
        var blockedByStorage = false
        try {
            reopened.retainedHardwareOnchainPayment(request.id, walletId, LOCAL_IDENTITY, ONCHAIN_ADDRESS, request.amountSats)
        } catch (_: IllegalStateException) {
            blockedByStorage = true
        }
        assertTrue(blockedByStorage)
        whenever(privatePaykitRepo.consumePrivatePaymentList(eq(request.counterparty), any()))
            .thenReturn(Result.failure(PrivatePaykitError.PaymentListAlreadyConsumed))
        assertEquals(signed, reopened.retainedHardwareOnchainPayment(request.id, walletId, LOCAL_IDENTITY, ONCHAIN_ADDRESS, request.amountSats)?.signedTx)
        whenever(privatePaykitRepo.consumePrivatePaymentList(eq(request.counterparty), any())).thenReturn(Result.success(Unit))
        assertNull(
            reopened.retainedHardwareOnchainPayment(
                request.id,
                "other-wallet",
                LOCAL_IDENTITY,
                ONCHAIN_ADDRESS,
                request.amountSats
            )
        )
        assertNull(
            reopened.retainedHardwareOnchainPayment(
                request.id,
                walletId,
                COUNTERPARTY,
                ONCHAIN_ADDRESS,
                request.amountSats
            )
        )
        assertNull(
            reopened.retainedHardwareOnchainPayment(
                request.id,
                walletId,
                LOCAL_IDENTITY,
                "other-address",
                request.amountSats
            )
        )
        assertNull(
            reopened.retainedHardwareOnchainPayment(
                request.id,
                walletId,
                LOCAL_IDENTITY,
                ONCHAIN_ADDRESS,
                request.amountSats + 1uL
            )
        )
        assertFalse(storedProofs.single().onchainAcceptanceVerified)
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
        assertEquals(7uL, storedProofs.single().privatePaymentListVersion)
        assertEquals(6uL, storedProofs.single().previousPrivatePaymentListVersion)
        whenever(privatePaykitRepo.releasePrivatePaymentListVersion(request.id.counterparty, 7uL, 6uL))
            .thenReturn(
                Result.failure(IllegalStateException("private state storage")),
                Result.success(Unit),
                Result.success(Unit)
            )
        val original = storedProofs.single()
        assertFalse(reopened.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
        assertEquals(listOf(original), storedProofs)
        // A restart must retain the original version while cleanup is unfinished.
        val retryCleanup = paymentProofRepo()
        shouldFailProofRemoval = true
        assertFalse(retryCleanup.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
        assertEquals(listOf(original), storedProofs)
        assertTrue(
            paymentProofRepo().failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false)
        )
        assertTrue(storedProofs.isEmpty())
        verify(privatePaykitRepo, times(3)).releasePrivatePaymentListVersion(request.id.counterparty, 7uL, 6uL)
        reopened.restoreBackup(Json.decodeFromString<List<PaykitPaymentStateBackup.Proof>>(encoded))
        assertTrue(
            reopened.retainHardwareOnchainCandidate(
                request.id,
                walletId,
                txid,
                LOCAL_IDENTITY,
                ONCHAIN_ADDRESS,
                request.amountSats,
                signed
            )
        )
        assertEquals(false, storedProofs.single().hardwareDispatchAttempted,
            "Retaining the signed candidate is not native dispatch")
        assertFalse(reopened.markHardwareOnchainDispatch(request.id, walletId, txid, COUNTERPARTY,
            ONCHAIN_ADDRESS, request.amountSats))
        assertEquals(false, storedProofs.single().hardwareDispatchAttempted)
        assertTrue(reopened.markHardwareOnchainDispatch(request.id, walletId, txid, LOCAL_IDENTITY,
            ONCHAIN_ADDRESS, request.amountSats))
        val dispatched = Json.encodeToString(reopened.backupSnapshot())
        storedProofs = emptyList()
        reopened.restoreBackup(Json.decodeFromString<List<PaykitPaymentStateBackup.Proof>>(dispatched))
        assertEquals(
            true,
            reopened.retainedHardwareOnchainPayment(
                request.id,
                walletId,
                LOCAL_IDENTITY,
                ONCHAIN_ADDRESS,
                request.amountSats
            )?.hasAttemptedBroadcast
        )
        assertFalse(reopened.failHardwareOnchainPaymentBeforeDispatch(request, walletId, LOCAL_IDENTITY, false))
        assertEquals(1, storedProofs.size)
    }

    @Test
    fun `prebroadcast hardware candidate survives repository reopen without claiming acceptance`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        assertFalse(repo.retainHardwareOnchainCandidate(request.id, walletId, txid, LOCAL_IDENTITY, "other-address", request.amountSats))
        assertFalse(repo.retainHardwareOnchainCandidate(request.id, walletId, txid, LOCAL_IDENTITY, ONCHAIN_ADDRESS, request.amountSats + 1uL))
        assertTrue(repo.retainHardwareOnchainCandidate(request.id, walletId, txid, LOCAL_IDENTITY, ONCHAIN_ADDRESS, request.amountSats))
        assertEquals(txid, storedProofs.single().paymentIdentifier)
        assertNull(storedProofs.single().proofData)
        assertFalse(storedProofs.single().onchainAcceptanceVerified)
        assertFalse(repo.retainHardwareOnchainCandidate(request.id, walletId, "cd".repeat(32), LOCAL_IDENTITY, ONCHAIN_ADDRESS, request.amountSats))
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(true))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull())).thenReturn(paymentRequestRecord())
        paymentProofRepo().reconcile()
        verify(paykitSdkService).submitPaymentProof(any(), any(), any(), eq(MethodId.P2wpkh.rawValue),
            eq("""{"data":"$txid","type":"bitcoin-onchain-txid"}"""), isNull())
        assertTrue(storedProofs.isEmpty())
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `hardware core txid remains pending until fresh exact observation then resumes original proof`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(false))

        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(txid, storedProofs.single().paymentIdentifier)
        assertNull(storedProofs.single().proofData)
        assertFalse(storedProofs.single().onchainAcceptanceVerified)
        assertEquals(PaykitPaymentRequestError.OperationInProgress,
            repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).exceptionOrNull())
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `restored verified hardware proof waits for original activity reconstruction`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        storedProofs = listOf(storedProofs.single().copy(
            paymentIdentifier = txid, proofData = txid, onchainAcceptanceVerified = true,
        ))
        whenever(paykitSdkService.paymentRequests()).thenReturn(listOf(paymentRequestRecord()))
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats))
            .thenReturn(Result.success(false))
        paymentProofRepo().reconcile()
        assertEquals(txid, storedProofs.single().proofData)
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats))
            .thenReturn(Result.success(true))
        whenever(paykitSdkService.submitPaymentProof(any(), any(), any(), any(), any(), isNull()))
            .thenReturn(paymentRequestRecord())
        paymentProofRepo().reconcile()
        assertTrue(storedProofs.isEmpty())
        verify(hwWalletRepo, times(2)).observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `hardware fresh observation submits completed durable proof with original wallet`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "cd".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        whenever(hwWalletRepo.observeExactTransaction(walletId, txid, ONCHAIN_ADDRESS, request.amountSats)).thenReturn(Result.success(true))
        // No delivery session: preserve the verified proof durably for later delivery.
        assertTrue(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(txid, storedProofs.single().proofData)
        assertTrue(storedProofs.single().onchainAcceptanceVerified)
        assertEquals(walletId, storedProofs.single().onchainWalletId)
        assertEquals(request.id, repo.onchainPaymentResolutions.value.single().requestId)
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
    }

    @Test
    fun `hardware observation errors and changed transaction id retain only the original pending lookup`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val txid = "ab".repeat(32)
        val walletId = "original-hardware-wallet"
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
        verify(paykitSdkService, never()).submitPaymentProof(any(), any(), any(), any(), any(), isNull())
    }

    @Test
    fun `hardware callback cannot verify another identity with the same request and wallet`() = test {
        val request = paymentRequest(MethodId.P2wpkh.rawValue)
        val walletId = "original-hardware-wallet"
        val txid = "ab".repeat(32)
        val repo = paymentProofRepo()
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        val original = storedProofs.single()
        val other = original.copy(identity = COUNTERPARTY)
        storedProofs = listOf(other)
        whenever(paykitSdkService.identityStatus()).thenReturn(IdentityStatus(COUNTERPARTY, PubkyIdentityCapability.PRIVATE_LINK_CAPABLE))
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.cancelPreparation(request)
        assertTrue(storedProofs.isEmpty())
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        val context = mock<Context>()
        whenever(context.getString(any())).thenReturn("message")
        val connected = mock<HwConnectedDevice>()
        val funding = HwFundingTransaction("psbt", 1000uL, 2.0f, 2000uL, 2uL)
        val signed = HwFundingSignedTx(requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction.hex")).bufferedReader().readText().trim(), 1000uL, 2uL, 2000uL)
        whenever(hwWalletRepo.needsPassphrase(walletId)).thenReturn(false)
        whenever(hwWalletRepo.reconnectTimeout(walletId)).thenReturn(30.seconds)
        whenever(hwWalletRepo.ensureConnected(walletId)).thenReturn(Result.success(connected))
        whenever(hwWalletRepo.composeFundingTransaction(walletId, ONCHAIN_ADDRESS, request.amountSats, 2uL))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(walletId, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("response lost after dispatch")))
        whenever(hwWalletRepo.broadcastFundingAtBoundary(any(), org.mockito.kotlin.anyOrNull(), any()))
            .doSuspendableAnswer {
                it.getArgument<suspend () -> Unit>(2)()
                hwWalletRepo.broadcastFunding(it.getArgument(0), it.getArgument(1))
            }
        val send = HwSendViewModel(context, hwWalletRepo, mock(), mock<CoreService>(), mock(), repo)
        send.signAndBroadcast(
            HwSendRequest(
                walletId,
                ONCHAIN_ADDRESS,
                request.amountSats,
                2uL,
                emptyList(),
                request.id,
                LOCAL_IDENTITY,
            ),
            prepareContactPayment = { receipt ->
                assertEquals(signed, receipt)
                repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId, signedTx = receipt).getOrThrow()
                true
            },
            authorizeContactPayment = {
                verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
                val backup = Json.encodeToString(repo.backupSnapshot())
                storedProofs = emptyList()
                val reopened = paymentProofRepo()
                reopened.restoreBackup(Json.decodeFromString<List<PaykitPaymentStateBackup.Proof>>(backup))
                assertEquals(
                    RetainedHardwareOnchainPayment(signed, false),
                    reopened.retainedHardwareOnchainPayment(
                        request.id,
                        walletId,
                        LOCAL_IDENTITY,
                        ONCHAIN_ADDRESS,
                        request.amountSats,
                    ),
                )
                true
            },
        )
        advanceUntilIdle()
        assertFalse(send.uiState.value.isSigning)
        assertTrue(send.uiState.value.isBroadcastUnresolved)
        assertTrue(storedProofs.single().paymentStarted)
        assertEquals("605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e", storedProofs.single().paymentIdentifier)
        assertEquals(true, storedProofs.single().hardwareDispatchAttempted)

        // Both generic failure and preparation cancellation must preserve an already dispatched Shop payment.
        repo.failOnchainPayment(request)
        repo.cancelPreparation(request)
        send.cancel()
        assertTrue(send.uiState.value.hasPendingBroadcast)
        assertTrue(storedProofs.single().paymentStarted)
        assertNull(storedProofs.single().proofData)
        assertEquals(PaykitPaymentRequestError.OperationInProgress,
            paymentProofRepo().prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain)
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
        repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain).getOrThrow()
        repo.markOnchainPaymentStarted(request, ONCHAIN_ADDRESS, walletId).getOrThrow()
        val original = storedProofs.single()
        shouldFailNextSave = true

        assertFalse(repo.completeHardwareOnchainPayment(request.id, walletId, txid, LOCAL_IDENTITY))
        assertEquals(listOf(original), storedProofs)
        assertTrue(original.paymentStarted)
        assertNull(original.paymentIdentifier)
        assertNull(original.proofData)
        assertTrue(repo.prepare(request, MethodId.P2wpkh.rawValue, "bitkit", PaykitPaymentProofKind.Onchain)
            .exceptionOrNull() is PaykitPaymentRequestError.OperationInProgress)
        verify(hwWalletRepo, never()).observeExactTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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
        store = store,
        hwWalletRepo = hwWalletRepo,
        privatePaykitRepo = dagger.Lazy { privatePaykitRepo },
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
