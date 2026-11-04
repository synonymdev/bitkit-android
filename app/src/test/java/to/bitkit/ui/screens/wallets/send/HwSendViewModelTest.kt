package to.bitkit.ui.screens.wallets.send

import android.content.Context
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.BroadcastException
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.TrezorException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.HeldHwSend
import to.bitkit.ext.create
import to.bitkit.models.HwConnectedDevice
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWallet
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.Toast
import to.bitkit.models.TransportType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.HwWalletMismatchError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.PaykitPaymentProofRepo
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.RetainedHardwareOnchainPayment
import to.bitkit.services.ActivityService
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import to.bitkit.utils.SignedTransactionId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HwSendViewModelTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val coreService = mock<CoreService>()
    private val activityService = mock<ActivityService>()
    private val activityRepo = mock<ActivityRepo>()
    private val proofRepo = mock<PaykitPaymentProofRepo>()
    private val walletActivities = MutableStateFlow<ImmutableList<Activity>>(persistentListOf())
    private val hwWallets = MutableStateFlow<ImmutableList<HwWallet>>(persistentListOf(hwWallet(WALLET_ID)))

    private lateinit var sut: HwSendViewModel
    private var now = Instant.parse("2026-10-06T11:59:59Z")

    @Before
    fun setUp() {
        whenever(coreService.activity).thenReturn(activityService)
        whenever { proofRepo.hasRetainedHardwareOnchainPayment(any()) }.thenReturn(false)

        whenever {
            proofRepo.retainHardwareOnchainCandidate(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull()
            )
        }
            .thenReturn(true)
        whenever {
            proofRepo.clearHardwareOnchainCandidateBeforeDispatch(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
                any()
            )
        }.thenReturn(false)
        whenever {
            proofRepo.markHardwareOnchainDispatch(any(), any(), any(), anyOrNull(), any(), any())
        }
            .thenReturn(true)
        whenever { hwWalletRepo.broadcastFundingAtBoundary(any(), anyOrNull(), any()) }
            .doSuspendableAnswer {
                val beforeDispatch = it.getArgument<suspend () -> Unit>(2)
                beforeDispatch()
                hwWalletRepo.broadcastFunding(it.getArgument(0), it.getArgument(1))
            }
        whenever { hwWalletRepo.reconnectTimeout(any()) }.thenReturn(30.seconds)
        whenever(hwWalletRepo.activities).thenReturn(walletActivities)
        whenever(hwWalletRepo.wallets).thenReturn(hwWallets)
        whenever { hwWalletRepo.heldSendFor(any(), any(), any()) }.thenReturn(Result.success(null))
        whenever { hwWalletRepo.holdSend(any()) }.thenReturn(Result.success(Unit))
        whenever { hwWalletRepo.releaseHeldSend(any()) }.thenReturn(Result.success(Unit))
        sut = HwSendViewModel(
            context = context,
            hwWalletRepo = hwWalletRepo,
            preActivityMetadataRepo = preActivityMetadataRepo,
            coreService = coreService,
            activityRepo = activityRepo,
            paykitPaymentProofRepo = proofRepo,
            clock = object : Clock {
                override fun now() = now
            },
        )
    }

    @Test
    fun `signing reconnects and retries once after THP channel failure`() = test {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_000uL,
            feeRate = 2.0f,
            totalSpent = 26_000uL,
            satsPerVByte = 2uL,
        )
        val signedTx = HwFundingSignedTx(
            serializedTx = signedFixtureHex(),
            miningFeeSats = funding.miningFeeSats,
            feeRate = 2uL,
            totalSpent = funding.totalSpent,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = "txid",
            miningFeeSats = signedTx.miningFeeSats,
            feeRate = signedTx.feeRate,
            totalSpent = signedTx.totalSpent,
        )
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.success(connectedDevice()))
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(WALLET_ID, funding)).thenReturn(
            Result.failure(TrezorException.ProtocolException("THP decryption error: aead::Error")),
            Result.success(signedTx),
        )
        whenever(hwWalletRepo.broadcastFunding(signedTx)).thenReturn(Result.success(broadcast))

        sut.signAndBroadcast(
            HwSendRequest(
                walletId = WALLET_ID,
                address = ADDRESS,
                amountSats = AMOUNT_SATS,
                satsPerVByte = SATS_PER_VBYTE,
                tags = emptyList(),
            )
        )
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).ensureConnected(WALLET_ID)
        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, funding)
        verify(hwWalletRepo).broadcastFunding(signedTx)
        verify(activityService).createSentOnchainActivityFromSendResult(
            txid = broadcast.txId,
            address = ADDRESS,
            amount = AMOUNT_SATS,
            fee = broadcast.miningFeeSats,
            feeRate = broadcast.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = WALLET_ID,
        )
        assertFalse(sut.uiState.value.isSigning)
    }

    @Test
    fun `contact payment is prepared once and authorized before each broadcast attempt`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)),
            Result.success(fixture.broadcast),
        )
        var preparationCalls = 0
        val prepareContactPayment: suspend (HwFundingSignedTx) -> Boolean = {
            verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
            verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
            preparationCalls += 1
            true
        }
        val authorizationAttempts = mutableListOf<Boolean>()
        val authorizeContactPayment: suspend (Boolean) -> Boolean = {
            authorizationAttempts += it
            true
        }

        sut.signAndBroadcast(request(), prepareContactPayment, authorizeContactPayment)
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        assertEquals(listOf(false), authorizationAttempts)
        assertTrue(sut.uiState.value.hasPendingBroadcast)

        sut.signAndBroadcast(request(), prepareContactPayment, authorizeContactPayment)
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        assertEquals(listOf(false, true), authorizationAttempts)
        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        sut.completeBroadcast()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
    }

    @Test
    fun `denied retry keeps the signed transaction after a failed broadcast`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)))
        var isAuthorized = true
        var preparationCalls = 0
        val authorizationAttempts = mutableListOf<Boolean>()

        sut.signAndBroadcast(
            request = request(),
            prepareContactPayment = {
                preparationCalls += 1
                true
            },
            authorizeContactPayment = {
                authorizationAttempts += it
                isAuthorized
            },
        )
        advanceUntilIdle()

        isAuthorized = false
        sut.signAndBroadcast(
            request = request(),
            prepareContactPayment = {
                preparationCalls += 1
                true
            },
            authorizeContactPayment = {
                authorizationAttempts += it
                isAuthorized
            },
        )
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        assertEquals(listOf(false, true), authorizationAttempts)
        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.uiState.value.isSigning)
    }

    @Test
    fun `passphrase reconnect keeps contact preparation before broadcast`() = test {
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true, false)
        whenever(hwWalletRepo.reconnectWithPassphrase(WALLET_ID, "hidden wallet"))
            .thenReturn(Result.success(Unit))
        var preparationCalls = 0
        val prepareContactPayment: suspend (HwFundingSignedTx) -> Boolean = {
            preparationCalls += 1
            true
        }
        val authorizationAttempts = mutableListOf<Boolean>()
        val authorizeContactPayment: suspend (Boolean) -> Boolean = {
            authorizationAttempts += it
            true
        }

        sut.signAndBroadcast(request(), prepareContactPayment, authorizeContactPayment)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPassphraseRequired)

        sut.submitPassphrase(WALLET_ID, "hidden wallet") {
            sut.signAndBroadcast(request(), prepareContactPayment, authorizeContactPayment)
        }
        advanceUntilIdle()

        assertEquals(1, preparationCalls)
        assertEquals(listOf(false), authorizationAttempts)
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
        assertFalse(sut.uiState.value.isPassphraseRequired)
    }

    @Test
    fun `a device holding another wallet shows the wallet mismatch`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.failure(HwWalletMismatchError()))
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.hardware__wallet_mismatch)).thenReturn("Different wallet")

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        assertEquals("Different wallet", toasts.single().description)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
    }

    @Test
    fun `composition timeout shows payment timeout`() = test {
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.success(connectedDevice()))
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.failure(timeout))
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        whenever(context.getString(R.string.wallet__payment_timeout)).thenReturn("Payment timed out")

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        assertEquals("Payment timed out", toasts.single().description)
        assertFalse(sut.uiState.value.isSigning)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
    }

    @Test
    fun `broadcast result survives collector reattachment until acknowledged`() = test {
        val fixture = stubSuccessfulPayment()

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)

        sut.completeBroadcast()

        assertFalse(sut.uiState.value.isBroadcastUnresolved)
    }

    @Test
    fun `Shop broadcast connectivity failure retains original signed payment across cancel`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException("connection failed")),
            Result.success(fixture.broadcast),
        )
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        sut.cancel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        assertEquals(original.paymentRequestId, sut.results.first().paymentRequestId)
        assertEquals(original.paymentIdentity, sut.results.first().paymentIdentity)
    }

    @Test
    fun `Shop invalid broadcast failure retains original signed payment across cancel`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.InvalidTransaction("invalid transaction")),
            Result.success(fixture.broadcast),
        )
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.canLeave)
        sut.cancel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        assertEquals(original.paymentRequestId, sut.results.first().paymentRequestId)
        assertEquals(original.paymentIdentity, sut.results.first().paymentIdentity)
    }

    @Test
    fun `Shop backend refusal allows leaving while retaining original signed payment`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(
                BroadcastException.ElectrumException(
                    """Broadcast failed: Electrum server error: "sendrawtransaction RPC error -26: """ +
                        """insufficient fee, rejecting replacement """ +
                        """b3f63e62aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; new feerate """ +
                        """0.00001000 BTC/kvB <= old feerate 0.00001018 BTC/kvB"""",
                ),
            ),
            Result.failure(
                BroadcastException.ElectrumException(
                    """Broadcast failed: Electrum server error: "sendrawtransaction RPC error -25: """ +
                        """bad-txns-inputs-missingorspent"""",
                ),
            ),
        )
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        sut.cancel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        verify(proofRepo, times(2)).markHardwareOnchainDispatch(
            requireNotNull(original.paymentRequestId),
            WALLET_ID,
            SignedTransactionId.fromHex(fixture.signedTx.serializedTx),
            original.paymentIdentity,
            original.address,
            original.amountSats,
        )
        verify(proofRepo, never()).clearHardwareOnchainCandidateBeforeDispatch(
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            any(),
            any(),
        )
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        verify(proofRepo, times(2)).markHardwareRefusedForNavigation(
            requireNotNull(original.paymentRequestId),
            WALLET_ID,
            SignedTransactionId.fromHex(fixture.signedTx.serializedTx),
            original.paymentIdentity,
            original.address,
            original.amountSats,
        )
    }

    @Test
    fun `broadcast connectivity failure unblocks navigation`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isSigning)
    }

    @Test
    fun `broadcast timeout unblocks navigation`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val timeout = runCatching { withTimeout(Duration.ZERO) { Unit } }
            .exceptionOrNull() as TimeoutCancellationException
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(timeout))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertEquals(listOf(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS)), sut.uiState.value.unresolvedSends)
        assertFalse(sut.uiState.value.isSigning)
    }

    @Test
    fun `a broadcast that succeeds after two minutes is recorded as sent without a retry prompt`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val broadcastResult = CompletableDeferred<Result<HwFundingBroadcastResult>>()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer {
            withContext(NonCancellable) { broadcastResult.await() }
        }

        sut.signAndBroadcast(request())
        advanceTimeBy(121.seconds)
        runCurrent()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.uiState.value.canLeave)

        broadcastResult.complete(Result.success(fixture.broadcast))
        advanceUntilIdle()
        toastJob.cancel()

        assertTrue(toasts.isEmpty())
        verify(activityService).createSentOnchainActivityFromSendResult(
            txid = fixture.broadcast.txId,
            address = ADDRESS,
            amount = AMOUNT_SATS,
            fee = fixture.broadcast.miningFeeSats,
            feeRate = fixture.broadcast.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = WALLET_ID,
        )
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `broadcast failure warns the payment was not confirmed`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)))
        whenever(context.getString(R.string.hardware__send_broadcast_failed_title)).thenReturn("Payment not confirmed")
        whenever(context.getString(R.string.hardware__send_broadcast_failed_text))
            .thenReturn("Check your connection and try again.")

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals("Payment not confirmed", toasts.single().title)
        assertEquals("Check your connection and try again.", toasts.single().description)
    }

    @Test
    fun `cancel drops the signed transaction after a broadcast that could not connect`() = test {
        assertCancelReleasesSignedTransaction(BroadcastException.ElectrumException(CONNECT_FAILURE))
    }

    @Test
    fun `cancel drops the signed transaction after a node refusal`() = test {
        assertCancelReleasesSignedTransaction(BroadcastException.ElectrumException(NODE_REFUSAL))
    }

    @Test
    fun `an invalid transaction drops the signed transaction at once`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.InvalidTransaction("Invalid transaction data: unexpected end of file")),
            Result.success(fixture.broadcast),
        )

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertFalse(sut.uiState.value.hasPendingBroadcast)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
    }

    @Test
    fun `a broadcast lost after its write keeps the signed transaction and never sends it again`() = test {
        assertUnknownBroadcastKeepsSignedTransaction(BroadcastException.ElectrumException(LOST_AFTER_WRITE))
    }

    @Test
    fun `a failed broadcast task keeps the signed transaction and never sends it again`() = test {
        assertUnknownBroadcastKeepsSignedTransaction(
            BroadcastException.TaskException("Broadcast task failed: task 7 panicked"),
        )
    }

    @Test
    fun `an unknown broadcast completes as sent once the wallet sees its transaction`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)))
        val results = mutableListOf<HwSendResult>()
        backgroundScope.launch { sut.heldSendEvents.collect { results += it.send } }

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        walletActivities.value = persistentListOf(sentActivity(walletId = "other-wallet"))
        advanceUntilIdle()
        assertTrue(results.isEmpty())

        walletActivities.value = persistentListOf(sentActivity())
        advanceUntilIdle()

        assertEquals(listOf(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS)), results)
        assertTrue(sut.uiState.value.unresolvedSends.isEmpty())
        verify(activityService).createSentOnchainActivityFromSendResult(
            txid = SIGNED_TXID,
            address = ADDRESS,
            amount = AMOUNT_SATS,
            fee = fixture.signedTx.miningFeeSats,
            feeRate = fixture.signedTx.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = WALLET_ID,
        )
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
        sut.completeBroadcast()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
    }

    @Test
    fun `a failure before the broadcast is dispatched releases the signed transaction`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val setupFailure = AppError("Trezor setup failed")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(setupFailure))
        whenever(hwWalletRepo.broadcastFundingAtBoundary(any(), anyOrNull(), any()))
            .thenReturn(Result.failure(setupFailure), Result.success(fixture.broadcast))
        val results = mutableListOf<HwSendResult>()
        backgroundScope.launch { sut.results.collect { results += it } }

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertNull(sut.uiState.value.unresolvedSend)
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        assertEquals(listOf(fixture.broadcast.txId), results.map { it.txId })
        verify(hwWalletRepo, times(2)).releaseHeldSend(SIGNED_TXID)
    }

    @Test
    fun `a send held after an unknown broadcast is kept beyond the sheet and released once the wallet sees it`() =
        test {
            whenever(context.getString(any())).thenReturn("message")
            val fixture = stubSuccessfulPayment()
            whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
            whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
                .thenReturn(Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)))

            sut.signAndBroadcast(request())
            advanceUntilIdle()

            verify(hwWalletRepo).holdSend(heldSend(fixture.signedTx))
            verify(hwWalletRepo, never()).releaseHeldSend(any())

            walletActivities.value = persistentListOf(sentActivity())
            advanceUntilIdle()

            verify(hwWalletRepo).releaseHeldSend(SIGNED_TXID)
        }

    @Test
    fun `a new send of a payment held before a restart is refused and retried only as the same bytes`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(context.getString(any())).thenReturn("message")
        whenever(context.getString(R.string.wallet__send_pending__blocked_new_send)).thenReturn("blocked")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.heldSendFor(WALLET_ID, ADDRESS, AMOUNT_SATS))
            .thenReturn(Result.success(heldSend(fixture.signedTx)))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.success(fixture.broadcast))
        val results = mutableListOf<HwSendResult>()
        backgroundScope.launch { sut.heldSendEvents.collect { results += it.send } }

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(1, toasts.count { it.description == "blocked" })
        assertEquals(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS), sut.uiState.value.unresolvedSend)
        assertEquals(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS), sut.refuseNewSend(WALLET_ID, ADDRESS, AMOUNT_SATS))

        sut.retryUnresolvedSend(SIGNED_TXID)
        advanceUntilIdle()
        toastJob.cancel()

        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo).releaseHeldSend(SIGNED_TXID)
        assertEquals(listOf(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS)), results)
    }

    @Test
    fun `a send is held before its broadcast and stays held while the broadcast is unanswered`() = test {
        val fixture = stubSuccessfulPayment()
        whenever { hwWalletRepo.broadcastFundingAtBoundary(any(), anyOrNull(), any()) }.doSuspendableAnswer {
            it.getArgument<suspend () -> Unit>(2)()
            awaitCancellation()
        }

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        inOrder(hwWalletRepo) {
            verify(hwWalletRepo).holdSend(heldSend(fixture.signedTx))
            verify(hwWalletRepo).broadcastFundingAtBoundary(any(), anyOrNull(), any())
        }
        verify(hwWalletRepo, never()).releaseHeldSend(any())
        assertFalse(sut.uiState.value.canLeave)
    }

    @Test
    fun `a send whose hold cannot be written is never broadcast`() = test {
        whenever(context.getString(any())).thenReturn("message")
        stubSuccessfulPayment()
        whenever { hwWalletRepo.holdSend(any()) }.thenReturn(Result.failure(AppError("Store unwritable")))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFundingAtBoundary(any(), anyOrNull(), any())
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertNull(sut.uiState.value.unresolvedSend)
    }

    @Test
    fun `a held send retried from pending stays held on any failure and completes once sent`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(context.getString(any())).thenReturn("message")
        whenever(context.getString(R.string.hardware__send_broadcast_failed_text)).thenReturn("try again")
        whenever(context.getString(R.string.hardware__send_broadcast_refused_text)).thenReturn("refused")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)),
            // Proof that a first broadcast sent nothing, but not that the one before this retry did not
            Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)),
            Result.failure(BroadcastException.ElectrumException(NODE_REFUSAL)),
            Result.success(fixture.broadcast),
        )
        val results = mutableListOf<HwSendResult>()
        backgroundScope.launch { sut.heldSendEvents.collect { results += it.send } }
        val held = HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS)

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()
        repeat(2) {
            sut.retryUnresolvedSend(SIGNED_TXID)
            advanceUntilIdle()

            assertEquals(listOf(held), sut.uiState.value.unresolvedSends)
            assertTrue(sut.uiState.value.retryingSendTxIds.isEmpty())
        }
        assertTrue(results.isEmpty())
        assertEquals(1, toasts.count { it.description == "try again" })
        assertEquals(1, toasts.count { it.description == "refused" })

        sut.retryUnresolvedSend(SIGNED_TXID)
        advanceUntilIdle()
        walletActivities.value = persistentListOf(sentActivity())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(listOf(held), results)
        assertTrue(sut.uiState.value.unresolvedSends.isEmpty())
        verify(hwWalletRepo, times(1)).signFunding(any(), any())
        verify(hwWalletRepo, times(4)).broadcastFunding(fixture.signedTx)
        verify(activityService, times(1)).createSentOnchainActivityFromSendResult(
            txid = SIGNED_TXID,
            address = ADDRESS,
            amount = AMOUNT_SATS,
            fee = fixture.signedTx.miningFeeSats,
            feeRate = fixture.signedTx.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = WALLET_ID,
        )
    }

    @Test
    fun `a held send whose retry the network refuses says so and stays held`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(context.getString(any())).thenReturn("message")
        whenever(context.getString(R.string.hardware__send_broadcast_failed_text)).thenReturn("try again")
        whenever(context.getString(R.string.hardware__send_broadcast_refused_text)).thenReturn("refused")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)),
            Result.failure(BroadcastException.ElectrumException(INPUTS_SPENT)),
        )
        val results = mutableListOf<HwSendResult>()
        backgroundScope.launch { sut.heldSendEvents.collect { results += it.send } }

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.retryUnresolvedSend(SIGNED_TXID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(1, toasts.count { it.description == "refused" })
        assertEquals(0, toasts.count { it.description == "try again" })
        assertEquals(listOf(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS)), sut.uiState.value.unresolvedSends)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `a new send is refused while one is held and points to the held payment`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(context.getString(any())).thenReturn("message")
        whenever(context.getString(R.string.wallet__send_pending__blocked_new_send)).thenReturn("blocked")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)))

        assertNull(sut.refuseNewSend(WALLET_ID, ADDRESS, AMOUNT_SATS))
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        assertEquals(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS), sut.refuseNewSend(WALLET_ID, ADDRESS, AMOUNT_SATS))
        advanceUntilIdle()
        assertEquals(1, toasts.count { it.description == "blocked" })

        walletActivities.value = persistentListOf(sentActivity())
        advanceUntilIdle()
        sut.completeBroadcast()
        toastJob.cancel()

        assertNull(sut.refuseNewSend(WALLET_ID, ADDRESS, AMOUNT_SATS))
        assertEquals(1, toasts.count { it.description == "blocked" })
    }

    @Test
    fun `a held send blocks only its own payment`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)),
            Result.success(fixture.broadcast),
        )
        val other = request().copy(amountSats = AMOUNT_SATS + 1uL)
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, other.amountSats, SATS_PER_VBYTE))
            .thenReturn(Result.success(fixture.funding))
        val results = mutableListOf<HwSendResult>()
        backgroundScope.launch { sut.results.collect { results += it } }

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        assertNull(sut.refuseNewSend(WALLET_ID, ADDRESS, other.amountSats))
        assertNull(sut.refuseNewSend(OTHER_WALLET_ID, ADDRESS, AMOUNT_SATS))
        sut.signAndBroadcast(other)
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        assertEquals(listOf(fixture.broadcast.txId), results.map { it.txId })
        assertEquals(listOf(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS)), sut.uiState.value.unresolvedSends)
    }

    @Test
    fun `removing a wallet forgets its held send for the session`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)))

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        hwWallets.value = persistentListOf(hwWallet(OTHER_WALLET_ID))
        advanceUntilIdle()

        assertTrue(sut.uiState.value.unresolvedSends.isEmpty())
        assertNull(sut.uiState.value.unresolvedSend)
        assertNull(sut.refuseNewSend(WALLET_ID, ADDRESS, AMOUNT_SATS))
        verify(hwWalletRepo, never()).releaseHeldSend(any())
    }

    @Test
    fun `a held send completed while no sheet listens is not replayed as a result`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(LOST_AFTER_WRITE)))

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        walletActivities.value = persistentListOf(sentActivity())
        advanceUntilIdle()
        val replayed = mutableListOf<Any>()
        backgroundScope.launch { sut.results.collect { replayed += it } }
        backgroundScope.launch { sut.heldSendEvents.collect { replayed += it } }
        advanceUntilIdle()

        assertTrue(replayed.isEmpty())
        assertTrue(sut.uiState.value.unresolvedSends.isEmpty())
        verify(hwWalletRepo).releaseHeldSend(SIGNED_TXID)
    }

    private suspend fun TestScope.assertCancelReleasesSignedTransaction(error: Throwable) {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(error),
            Result.success(fixture.broadcast),
        )

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.hasPendingBroadcast)

        sut.cancel()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertNull(sut.uiState.value.unresolvedSend)
        verify(hwWalletRepo).disconnectStaleSession(WALLET_ID)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
    }

    private suspend fun TestScope.assertUnknownBroadcastKeepsSignedTransaction(error: Throwable) {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(context.getString(any())).thenReturn("message")
        whenever(context.getString(R.string.hardware__send_broadcast_failed_text)).thenReturn("try again")
        whenever(context.getString(R.string.wallet__send_pending__blocked_new_send)).thenReturn("blocked")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(error),
            Result.success(fixture.broadcast),
        )

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(toasts.none { it.description == "try again" })
        assertEquals(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS), sut.uiState.value.unresolvedSend)
        sut.cancel()
        advanceUntilIdle()
        val held = listOf(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS))
        assertEquals(held, sut.uiState.value.unresolvedSends)

        sut.signAndBroadcast(request().copy(address = ADDRESS.uppercase()))
        advanceUntilIdle()
        toastJob.cancel()

        verify(hwWalletRepo, times(1)).signFunding(any(), any())
        verify(hwWalletRepo, times(1)).broadcastFunding(any(), anyOrNull())
        assertEquals(1, toasts.count { it.description == "blocked" })
        assertEquals(HwSendResult(WALLET_ID, SIGNED_TXID, AMOUNT_SATS), sut.uiState.value.unresolvedSend)
        assertEquals(held, sut.uiState.value.unresolvedSends)
        assertTrue(sut.uiState.value.canLeave)
    }

    @Test
    fun `sheet can be left while the device connects`() = test {
        val fixture = stubSuccessfulPayment()
        val connectStarted = CompletableDeferred<Unit>()
        val connectResult = CompletableDeferred<Result<HwConnectedDevice>>()
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).doSuspendableAnswer {
            connectStarted.complete(Unit)
            connectResult.await()
        }

        sut.signAndBroadcast(request())
        connectStarted.await()

        assertTrue(sut.uiState.value.isSigning)
        assertTrue(sut.uiState.value.isConnectingDevice)
        assertTrue(sut.uiState.value.canLeave)

        connectResult.complete(Result.success(connectedDevice()))
        advanceUntilIdle()

        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
        assertFalse(sut.uiState.value.isConnectingDevice)
    }

    @Test
    fun `sheet cannot be left while the device signs`() = test {
        val fixture = stubSuccessfulPayment()
        val signStarted = CompletableDeferred<Unit>()
        val signResult = CompletableDeferred<Result<HwFundingSignedTx>>()
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).doSuspendableAnswer {
            signStarted.complete(Unit)
            signResult.await()
        }

        sut.signAndBroadcast(request())
        signStarted.await()

        assertTrue(sut.uiState.value.isSigning)
        assertFalse(sut.uiState.value.isConnectingDevice)
        assertFalse(sut.uiState.value.canLeave)

        signResult.complete(Result.success(fixture.signedTx))
        advanceUntilIdle()
    }

    @Test
    fun `sheet cannot be left while a broadcast is unresolved`() = test {
        assertFalse(HwSendUiState(isSigning = true, isBroadcastUnresolved = true).canLeave)
        assertFalse(HwSendUiState(isSigning = true, isConnectingDevice = true, isBroadcastUnresolved = true).canLeave)
        assertTrue(HwSendUiState().canLeave)
    }

    @Test
    fun `cancel while connecting stops before signing and broadcasting`() = test {
        val fixture = stubSuccessfulPayment()
        val connectStarted = CompletableDeferred<Unit>()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        var connectCalls = 0
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).doSuspendableAnswer {
            connectCalls += 1
            if (connectCalls > 1) return@doSuspendableAnswer Result.success(connectedDevice())
            connectStarted.complete(Unit)
            awaitCancellation()
        }

        sut.signAndBroadcast(request())
        connectStarted.await()
        sut.cancel()
        advanceUntilIdle()

        verify(hwWalletRepo).disconnectStaleSession(WALLET_ID)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(HwSendUiState(), sut.uiState.value)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `expiry in broadcast queue releases only a never submitted attempt`() = test {
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(AppError(ServiceError.PaymentDeadlineExpired())))
        val attempts = mutableListOf<Boolean>()

        sut.signAndBroadcast(
            request().copy(paymentDeadlineAt = deadline),
            authorizeContactPayment = {
                attempts += it
                true
            },
            onPaymentDeadlineExpired = { attempts += it },
        )
        advanceUntilIdle()

        assertEquals(listOf(false, false), attempts)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.uiState.value.isSigning)
        sut.cancel()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        verify(hwWalletRepo).releaseHeldSend(SIGNED_TXID)
    }

    @Test
    fun `changed Shop receipt at native boundary prevents hardware submission`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(
            proofRepo.markHardwareOnchainDispatch(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any()
            )
        )
            .thenReturn(false)
        sut.signAndBroadcast(
            request().copy(
                paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
                paymentIdentity = "original-identity"
            )
        )
        advanceUntilIdle()
        verify(proofRepo).markHardwareOnchainDispatch(any(), any(), any(), anyOrNull(), any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `first queued Shop expiry clears exact retained candidate before releasing preparation`() = test {
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        val requestId = PaykitPaymentRequestId("request", "counterparty")
        val original = request().copy(
            paymentRequestId = requestId,
            paymentIdentity = "original-identity",
            paymentDeadlineAt = deadline
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(AppError(ServiceError.PaymentDeadlineExpired())))
        var cleared = false
        whenever(
            proofRepo.clearHardwareOnchainCandidateBeforeDispatch(
                requestId,
                WALLET_ID,
                "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e",
                "original-identity",
                ADDRESS,
                AMOUNT_SATS,
                false
            )
        ).doSuspendableAnswer {
            cleared = true
            true
        }
        var expired: Boolean? = null
        sut.signAndBroadcast(original, onPaymentDeadlineExpired = {
            assertTrue(cleared)
            expired = it
        })
        advanceUntilIdle()
        assertEquals(false, expired)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
    }

    @Test
    fun `failed queued Shop candidate rollback preserves uncertainty`() = test {
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(AppError(ServiceError.PaymentDeadlineExpired())))
        var expired: Boolean? = null
        sut.signAndBroadcast(
            request().copy(
                paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
                paymentIdentity = "original-identity",
                paymentDeadlineAt = deadline
            ),
            onPaymentDeadlineExpired = { expired = it }
        )
        advanceUntilIdle()
        assertEquals(true, expired)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
    }

    @Test
    fun `expiry during rebroadcast keeps the earlier uncertain attempt`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline)).thenReturn(
            Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)),
            Result.failure(AppError(ServiceError.PaymentDeadlineExpired())),
        )
        val attempts = mutableListOf<Boolean>()
        val authorize: suspend (Boolean) -> Boolean = {
            attempts += it
            attempts.size < 3
        }
        val request = request().copy(paymentDeadlineAt = deadline)

        sut.signAndBroadcast(
            request,
            authorizeContactPayment = authorize,
            onPaymentDeadlineExpired = { attempts += it }
        )
        advanceUntilIdle()
        sut.signAndBroadcast(
            request,
            authorizeContactPayment = authorize,
            onPaymentDeadlineExpired = { attempts += it }
        )
        advanceUntilIdle()

        assertEquals(listOf(false, true, true), attempts)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.uiState.value.isSigning)
        sut.cancel()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
        // Only the first broadcast, which could not connect, released its hold
        verify(hwWalletRepo, times(1)).releaseHeldSend(SIGNED_TXID)
    }

    @Test
    fun `expiry before rebroadcast preserves uncertainty without repeating authorization`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)))
        val request = request().copy(paymentDeadlineAt = deadline)
        var authorizations = 0
        var expiredPriorAttempt: Boolean? = null
        val authorize: suspend (Boolean) -> Boolean = {
            authorizations++
            true
        }
        sut.signAndBroadcast(request, authorizeContactPayment = authorize)
        advanceUntilIdle()
        now = deadline + 1.seconds

        sut.signAndBroadcast(
            request,
            authorizeContactPayment = authorize,
            onPaymentDeadlineExpired = { expiredPriorAttempt = it },
        )
        advanceUntilIdle()
        sut.cancel()

        assertEquals(1, authorizations)
        assertEquals(true, expiredPriorAttempt)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isSigning)
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx, deadline)
    }

    @Test
    fun `expiry during retry authorization preserves the earlier uncertain attempt`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(BroadcastException.ElectrumException(CONNECT_FAILURE)))
        val request = request().copy(paymentDeadlineAt = deadline)
        sut.signAndBroadcast(request)
        advanceUntilIdle()

        sut.signAndBroadcast(request, authorizeContactPayment = {
            assertTrue(it)
            now = deadline + 1.seconds
            false
        })
        advanceUntilIdle()
        sut.cancel()

        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isSigning)
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx, deadline)
    }

    private suspend fun stubSuccessfulPayment(): PaymentFixture {
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = 1_000uL,
            feeRate = 2.0f,
            totalSpent = 26_000uL,
            satsPerVByte = SATS_PER_VBYTE,
        )
        val signedTx = HwFundingSignedTx(
            serializedTx = signedFixtureHex(),
            miningFeeSats = funding.miningFeeSats,
            feeRate = SATS_PER_VBYTE,
            totalSpent = funding.totalSpent,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = "txid",
            miningFeeSats = signedTx.miningFeeSats,
            feeRate = signedTx.feeRate,
            totalSpent = signedTx.totalSpent,
        )
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.success(connectedDevice()))
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(WALLET_ID, funding)).thenReturn(Result.success(signedTx))
        whenever(hwWalletRepo.broadcastFunding(signedTx)).thenReturn(Result.success(broadcast))
        return PaymentFixture(funding, signedTx, broadcast)
    }

    @Test
    fun `retained hardware receipt blocks ordinary and foreign request signing after restart`() = test {
        stubSuccessfulPayment()
        whenever(context.getString(org.mockito.kotlin.any())).thenReturn("payment blocked")
        whenever { proofRepo.hasRetainedHardwareOnchainPayment(WALLET_ID) }.thenReturn(true)
        for (next in listOf(
            request(),
            request().copy(
                paymentRequestId = PaykitPaymentRequestId("foreign-request", "foreign-counterparty"),
                paymentIdentity = "original-identity"
            )
        )) {
            sut = HwSendViewModel(
                context = context,
                hwWalletRepo = hwWalletRepo,
                preActivityMetadataRepo = preActivityMetadataRepo,
                coreService = coreService,
                activityRepo = activityRepo,
                paykitPaymentProofRepo = proofRepo,
                clock = object : Clock {
                    override fun now() = now
                },
            )
            sut.signAndBroadcast(next)
            advanceUntilIdle()
        }
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
    }

    @Test
    fun `restored hardware Shop receipt retries only after authorization without signing again`() = test {
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity"
        )
        whenever {
            proofRepo.retainedHardwareOnchainPayment(
                requireNotNull(original.paymentRequestId),
                WALLET_ID,
                original.paymentIdentity,
                ADDRESS,
                AMOUNT_SATS
            )
        }.thenReturn(to.bitkit.repositories.RetainedHardwareOnchainPayment(fixture.signedTx, true))
        sut.signAndBroadcast(
            original,
            prepareContactPayment = { error("must retain original preparation") },
            authorizeContactPayment = { attempted ->
                assertTrue(attempted)
                false
            }
        )
        advanceUntilIdle()
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        sut.signAndBroadcast(
            original,
            prepareContactPayment = { error("must retain original preparation") },
            authorizeContactPayment = { attempted ->
                assertTrue(attempted)
                true
            }
        )
        advanceUntilIdle()
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `restored unattempted Shop receipt expires without claiming dispatch or signing again`() = test {
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
            paymentDeadlineAt = deadline
        )
        whenever {
            proofRepo.retainedHardwareOnchainPayment(
                requireNotNull(original.paymentRequestId),
                WALLET_ID,
                original.paymentIdentity,
                ADDRESS,
                AMOUNT_SATS
            )
        }.thenReturn(to.bitkit.repositories.RetainedHardwareOnchainPayment(fixture.signedTx, false))
        now = deadline + 1.seconds
        var attempted: Boolean? = null
        sut.signAndBroadcast(
            original,
            prepareContactPayment = { error("must retain original preparation") },
            authorizeContactPayment = { error("expired before authorization") },
            onPaymentDeadlineExpired = { attempted = it }
        )
        advanceUntilIdle()
        assertEquals(false, attempted)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
    }

    @Test
    fun `hardware Shop never broadcasts when candidate persistence fails`() = test {
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity"
        )
        whenever {
            proofRepo.retainHardwareOnchainCandidate(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull()
            )
        }
            .thenReturn(false)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(proofRepo).retainHardwareOnchainCandidate(
            requireNotNull(original.paymentRequestId),
            WALLET_ID,
            "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e",
            original.paymentIdentity,
            ADDRESS,
            AMOUNT_SATS,
            fixture.signedTx
        )
    }

    @Test
    fun `cancel cannot discard signed hardware receipt while candidate save is suspended`() = test {
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        val resume = CompletableDeferred<Boolean>()
        whenever {
            proofRepo.retainHardwareOnchainCandidate(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull()
            )
        }.doSuspendableAnswer { resume.await() }
        var submissionActive = false
        val finishSubmission = { submissionActive = false }
        sut.signAndBroadcast(
            original,
            beginPaymentSubmission = {
                submissionActive = true
                finishSubmission
            },
        )
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        sut.cancel()
        assertTrue(submissionActive)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        resume.complete(true)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
        assertFalse(submissionActive)
    }

    @Test
    fun `hardware candidate save denial retains signed receipt across cancel and retries without signing`() = test {
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever {
            proofRepo.retainHardwareOnchainCandidate(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull()
            )
        }
            .thenReturn(false, true)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        sut.cancel()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `hardware candidate save exception retains signed receipt across cancel and retries without signing`() = test {
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever {
            proofRepo.retainHardwareOnchainCandidate(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
                anyOrNull()
            )
        }
            .thenThrow(IllegalStateException("storage unavailable")).thenReturn(true)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        sut.cancel()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `observed Shop completion ignores a late suspended broadcast result`() = test {
        val fixture = stubSuccessfulPayment()
        val resume = CompletableDeferred<Unit>()
        val txid = "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e"
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer {
            withContext(NonCancellable) { resume.await() }
            Result.success(fixture.broadcast.copy(txId = txid))
        }
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("original", "counterparty"),
            paymentIdentity = "original-identity",
        )
        var emitted = 0
        backgroundScope.launch { sut.results.collect { emitted++ } }
        sut.signAndBroadcast(original)
        runCurrent()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        var completed: HwSendResult? = null
        assertTrue(sut.completeReconciledBroadcast(WALLET_ID, txid) { completed = it })
        assertEquals(original.paymentRequestId, completed?.paymentRequestId)
        resume.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, emitted)
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.uiState.value.isSigning)
        assertFalse(sut.completeReconciledBroadcast(WALLET_ID, txid))
    }

    @Test
    fun `exact asynchronous completion clears a timed out Shop broadcast without a result`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val timeout = runCatching { withTimeout(Duration.ZERO) { Unit } }
            .exceptionOrNull() as TimeoutCancellationException
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(timeout))
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("original", "counterparty"),
            paymentIdentity = "original-identity",
        )
        sut.signAndBroadcast(original)
        advanceUntilIdle()

        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        val txid = "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e"
        assertFalse(sut.completeReconciledBroadcast("other-wallet", txid))
        assertFalse(sut.completeReconciledBroadcast(WALLET_ID, "other-tx"))
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        var completed: HwSendResult? = null
        assertTrue(sut.completeReconciledBroadcast(WALLET_ID, txid.uppercase()) { completed = it })
        assertEquals(
            HwSendResult(
                WALLET_ID,
                txid,
                original.amountSats,
                original.paymentRequestId,
                original.paymentIdentity
            ),
            completed
        )
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.completeReconciledBroadcast(WALLET_ID, txid))

        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.success(fixture.broadcast))
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `matching asynchronous completion consumes retained result and permits the next hardware send`() = test {
        val fixture = stubSuccessfulPayment()
        sut.signAndBroadcast(request().copy(paymentRequestId = PaykitPaymentRequestId("original", "counterparty")))
        advanceUntilIdle()
        assertFalse(sut.completeReconciledBroadcast("other-wallet", fixture.broadcast.txId))
        assertFalse(sut.completeReconciledBroadcast(WALLET_ID, "other-tx"))
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertTrue(sut.completeReconciledBroadcast(WALLET_ID, fixture.broadcast.txId))
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `core completed hardware result retains original request and never rebroadcasts while proof is pending`() = test {
        val fixture = stubSuccessfulPayment()
        val originalId = PaykitPaymentRequestId("original-request", "counterparty", "receiver")
        val original = request().copy(paymentRequestId = originalId, paymentIdentity = "original-identity")
        sut.signAndBroadcast(original)
        advanceUntilIdle()

        assertEquals(originalId, sut.results.first().paymentRequestId)
        assertEquals("original-identity", sut.results.first().paymentIdentity)
        // A failed proof write keeps the result: closing/reopening must replay its exact identity.
        sut.cancel()
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertEquals(originalId, sut.results.first().paymentRequestId)
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        // Local proof work has not consumed the result yet: neither this request nor another may resend.
        sut.signAndBroadcast(original)
        sut.signAndBroadcast(original.copy(paymentRequestId = originalId.copy(paymentRequestId = "different-request")))
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `hardware Shop core result does not create Sent activity before exact observation`() = test {
        val fixture = stubSuccessfulPayment()
        val original = request().copy(paymentRequestId = PaykitPaymentRequestId("request", "counterparty", "receiver"))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        verify(activityService, never()).createSentOnchainActivityFromSendResult(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
        )
        verify(activityRepo, never()).notifyPaymentActivityChanged()
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `ordinary hardware Core result preserves existing Sent activity behavior`() = test {
        val fixture = stubSuccessfulPayment()
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        verify(activityService).createSentOnchainActivityFromSendResult(
            fixture.broadcast.txId,
            ADDRESS,
            AMOUNT_SATS,
            fixture.broadcast.miningFeeSats,
            fixture.broadcast.feeRate,
            false,
            null,
            WALLET_ID,
        )
    }

    @Test
    fun `refused Shop payment stays dismissable after its deadline`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
            paymentDeadlineAt = deadline,
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(BroadcastException.InvalidTransaction("bad-txns-inputs-missingorspent")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        now = deadline + 1.seconds
        var expiredAttempted: Boolean? = null
        sut.signAndBroadcast(original, onPaymentDeadlineExpired = { expiredAttempted = it })
        advanceUntilIdle()
        assertEquals(true, expiredAttempted)
        assertTrue(sut.uiState.value.canLeave)
        sut.cancel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx, deadline)
    }

    @Test
    fun `retained Shop payment retries original bytes after display fee changes`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.InvalidTransaction("bad-txns-inputs-missingorspent")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        sut.cancel()
        advanceUntilIdle()
        sut.signAndBroadcast(original.copy(satsPerVByte = SATS_PER_VBYTE + 1uL))
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `retained Shop payment retries original bytes after tags change`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
            tags = listOf("original-tag"),
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.InvalidTransaction("bad-txns-inputs-missingorspent")),
            Result.failure(BroadcastException.ElectrumException("response lost after dispatch")),
        )
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        sut.cancel()
        advanceUntilIdle()
        sut.signAndBroadcast(original.copy(tags = listOf("new-display-tag")))
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        verify(proofRepo, times(2)).retainHardwareOnchainCandidate(
            requireNotNull(original.paymentRequestId),
            original.walletId,
            SignedTransactionId.fromHex(fixture.signedTx.serializedTx),
            original.paymentIdentity,
            original.address,
            original.amountSats,
            fixture.signedTx,
        )
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.canLeave)
    }

    @Test
    fun `pre-dispatch retry marker denial after refusal keeps the sheet dismissable`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.InvalidTransaction("bad-txns-inputs-missingorspent")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        whenever(
            proofRepo.markHardwareOnchainDispatch(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
                any(),
            )
        ).thenReturn(false)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        verify(proofRepo, never()).clearHardwareOnchainCandidateBeforeDispatch(
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            any(),
            any(),
        )
    }

    @Test
    fun `retry setup failure before boundary after refusal keeps the sheet dismissable`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.InvalidTransaction("bad-txns-inputs-missingorspent")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        whenever(hwWalletRepo.broadcastFundingAtBoundary(any(), anyOrNull(), any()))
            .thenReturn(Result.failure(IllegalStateException("device setup failed before callback")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(proofRepo, times(1)).markHardwareOnchainDispatch(
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            any(),
        )
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
    }

    @Test
    fun `retained Shop retry still rejects changed payment identity or recipient`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.InvalidTransaction("bad-txns-inputs-missingorspent")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        for (changed in listOf(
            original.copy(paymentIdentity = "different-payer"),
            original.copy(paymentRequestId = PaykitPaymentRequestId("different-request", "counterparty")),
            original.copy(address = "different-recipient"),
            original.copy(amountSats = original.amountSats + 1uL),
        )) {
            sut.signAndBroadcast(changed)
            advanceUntilIdle()
            assertTrue(sut.uiState.value.canLeave)
            assertTrue(sut.uiState.value.hasPendingBroadcast)
        }
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `restored refused Shop receipt remains dismissable after expiry without sending`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
            paymentDeadlineAt = now - 1.seconds,
        )
        whenever(
            proofRepo.retainedHardwareOnchainPayment(
                requireNotNull(original.paymentRequestId),
                WALLET_ID,
                original.paymentIdentity,
                original.address,
                original.amountSats,
            )
        ).thenReturn(RetainedHardwareOnchainPayment(fixture.signedTx, true, true))
        var attempted: Boolean? = null
        sut.signAndBroadcast(original, onPaymentDeadlineExpired = { attempted = it })
        advanceUntilIdle()
        assertEquals(true, attempted)
        assertTrue(sut.uiState.value.canLeave)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
    }

    @Test
    fun `fresh unknown retry after refusal re-arms navigation protection`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val original = request().copy(
            paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity",
            paymentDeadlineAt = now + 1.seconds,
        )
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, original.paymentDeadlineAt)).thenReturn(
            Result.failure(BroadcastException.InvalidTransaction("invalid transaction")),
            Result.failure(BroadcastException.ElectrumException("response lost after dispatch")),
        )
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.canLeave)
        now += 2.seconds
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.canLeave)
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx, original.paymentDeadlineAt)
    }

    private fun signedFixtureHex() = requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction.hex"))
        .bufferedReader().readText().trim()

    private fun heldSend(signedTx: HwFundingSignedTx) = HeldHwSend(
        walletId = WALLET_ID,
        txId = SIGNED_TXID,
        address = ADDRESS,
        amountSats = AMOUNT_SATS,
        satsPerVByte = SATS_PER_VBYTE,
        serializedTx = signedTx.serializedTx,
        miningFeeSats = signedTx.miningFeeSats,
        feeRate = signedTx.feeRate,
        totalSpent = signedTx.totalSpent,
    )

    private fun request() = HwSendRequest(
        walletId = WALLET_ID,
        address = ADDRESS,
        amountSats = AMOUNT_SATS,
        satsPerVByte = SATS_PER_VBYTE,
        tags = emptyList(),
    )

    private data class PaymentFixture(
        val funding: HwFundingTransaction,
        val signedTx: HwFundingSignedTx,
        val broadcast: HwFundingBroadcastResult,
    )

    private fun connectedDevice() = HwConnectedDevice(vendor = HwWalletVendor.TREZOR, id = "dev1")

    private fun hwWallet(id: String) = HwWallet(
        id = id,
        name = id,
        model = null,
        transportType = TransportType.USB,
        isConnected = true,
        balanceSats = 100_000uL,
        activities = persistentListOf(),
    )

    private fun sentActivity(walletId: String = WALLET_ID) = Activity.Onchain(
        OnchainActivity.create(
            walletId = walletId,
            id = SIGNED_TXID,
            txType = PaymentType.SENT,
            txId = SIGNED_TXID,
            value = AMOUNT_SATS,
            fee = 1_000uL,
            address = ADDRESS,
            timestamp = 1_700_000_000uL,
        )
    )

    private companion object {
        const val WALLET_ID = "hardware-wallet"
        const val OTHER_WALLET_ID = "other-wallet"
        const val SIGNED_TXID = "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e"
        const val CONNECT_FAILURE = "Failed to connect to Electrum: I/O error: Connection refused (os error 111)"
        const val LOST_AFTER_WRITE =
            "Broadcast failed: Made one or multiple attempts, all errored:\n\t- Broken pipe (os error 32)\n"
        const val NODE_REFUSAL = "Broadcast failed: Electrum server error: " +
            """"sendrawtransaction RPC error -26: min relay fee not met, 110 < 141""""
        const val INPUTS_SPENT = "Broadcast failed: Electrum server error: " +
            """"sendrawtransaction RPC error -25: bad-txns-inputs-missingorspent""""
        const val ADDRESS = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        const val AMOUNT_SATS = 25_000uL
        const val SATS_PER_VBYTE = 2uL
    }
}
