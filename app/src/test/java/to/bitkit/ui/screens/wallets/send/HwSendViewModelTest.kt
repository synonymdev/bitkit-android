package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BroadcastException
import com.synonym.bitkitcore.TrezorException
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.models.FeeRate
import to.bitkit.models.HwConnectedDevice
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.HwPassphraseMismatchError
import to.bitkit.repositories.HwPassphraseRequiredError
import to.bitkit.repositories.HwRetainedSend
import to.bitkit.repositories.HwRetainedSendRepo
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
import to.bitkit.viewmodels.OnchainFeeUi
import to.bitkit.viewmodels.SanityWarning
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.withRetainedSendStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    private val keychain = mock<Keychain>()
    private val retainedEntries = mutableMapOf<String, String>()

    private lateinit var hwRetainedSendRepo: HwRetainedSendRepo
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
                org.mockito.kotlin.anyOrNull(),
                any(),
                any(),
                org.mockito.kotlin.anyOrNull()
            )
        }
            .thenReturn(true)
        whenever {
            proofRepo.clearHardwareOnchainCandidateBeforeDispatch(
                any(),
                any(),
                any(),
                org.mockito.kotlin.anyOrNull(),
                any(),
                any(),
                any()
            )
        }.thenReturn(false)
        whenever {
            proofRepo.markHardwareOnchainDispatch(any(), any(), any(), org.mockito.kotlin.anyOrNull(), any(), any())
        }
            .thenReturn(true)
        whenever { hwWalletRepo.broadcastFundingAtBoundary(any(), org.mockito.kotlin.anyOrNull(), any()) }
            .doSuspendableAnswer {
                val beforeDispatch = it.getArgument<suspend () -> Unit>(2)
                beforeDispatch()
                hwWalletRepo.broadcastFunding(it.getArgument(0), it.getArgument(1))
            }
        whenever { hwWalletRepo.reconnectTimeout(any()) }.thenReturn(30.seconds)
        whenever { hwWalletRepo.observeExactTransaction(any(), any(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(false))
        whenever(keychain.loadScopedString(eq(RETAINED_KEY), any())).thenAnswer {
            retainedEntries[it.getArgument(1)]
        }
        whenever { keychain.upsertScopedString(eq(RETAINED_KEY), any(), any()) }.doSuspendableAnswer {
            retainedEntries[it.getArgument(2)] = it.getArgument(1)
        }
        whenever { keychain.deleteScoped(eq(RETAINED_KEY), any()) }.doSuspendableAnswer {
            retainedEntries.remove(it.getArgument<String>(1))
            Unit
        }
        hwRetainedSendRepo = HwRetainedSendRepo(testDispatcher, keychain)
        sut = newViewModel()
    }

    private fun newViewModel() = HwSendViewModel(
        context = context,
        hwWalletRepo = hwWalletRepo,
        preActivityMetadataRepo = preActivityMetadataRepo,
        coreService = coreService,
        activityRepo = activityRepo,
        paykitPaymentProofRepo = proofRepo,
        hwRetainedSendRepo = hwRetainedSendRepo,
        clock = object : Clock {
            override fun now() = now
        },
    )

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
            Result.failure(BroadcastException.ElectrumException("connection failed")),
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
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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
            org.mockito.kotlin.anyOrNull(),
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
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))

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
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isSigning)
    }

    @Test
    fun `an unknown broadcast failure warns the payment may have been sent`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
        whenever(context.getString(R.string.hardware__send_broadcast_uncertain_title))
            .thenReturn(BROADCAST_UNCERTAIN_TITLE)
        whenever(context.getString(R.string.hardware__send_broadcast_uncertain_text))
            .thenReturn(BROADCAST_UNCERTAIN_TEXT)

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals(BROADCAST_UNCERTAIN_TITLE, toasts.single().title)
        assertEquals(BROADCAST_UNCERTAIN_TEXT, toasts.single().description)
    }

    @Test
    fun `cancel keeps the signed transaction after an unknown broadcast outcome`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.ElectrumException("connection failed")),
            Result.success(fixture.broadcast),
        )

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.hasPendingBroadcast)

        sut.cancel()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.offersRetry(request()))
        assertTrue(sut.uiState.value.canLeave)
        verify(hwWalletRepo).disconnectStaleSession(WALLET_ID)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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
                org.mockito.kotlin.anyOrNull(),
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
        verify(proofRepo).markHardwareOnchainDispatch(any(), any(), any(), org.mockito.kotlin.anyOrNull(), any(), any())
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
            Result.failure(BroadcastException.ElectrumException("connection failed")),
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
    }

    @Test
    fun `expiry before rebroadcast preserves uncertainty without repeating authorization`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
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
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
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
            sut = newViewModel()
            sut.signAndBroadcast(next)
            advanceUntilIdle()
        }
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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
                org.mockito.kotlin.anyOrNull(),
                any(),
                any(),
                org.mockito.kotlin.anyOrNull()
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
                org.mockito.kotlin.anyOrNull(),
                any(),
                any(),
                org.mockito.kotlin.anyOrNull()
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
                org.mockito.kotlin.anyOrNull(),
                any(),
                any(),
                org.mockito.kotlin.anyOrNull()
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
                org.mockito.kotlin.anyOrNull(),
                any(),
                any(),
                org.mockito.kotlin.anyOrNull()
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
            org.mockito.kotlin.anyOrNull(),
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
                org.mockito.kotlin.anyOrNull(),
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
            org.mockito.kotlin.anyOrNull(),
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
        whenever(hwWalletRepo.broadcastFundingAtBoundary(any(), org.mockito.kotlin.anyOrNull(), any()))
            .thenReturn(Result.failure(IllegalStateException("device setup failed before callback")))
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(proofRepo, times(1)).markHardwareOnchainDispatch(
            any(),
            any(),
            any(),
            org.mockito.kotlin.anyOrNull(),
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
        verify(hwWalletRepo, never()).broadcastFunding(any(), org.mockito.kotlin.anyOrNull())
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

    @Test
    fun `a new view model with the same repository rebroadcasts without signing`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(fixture.broadcast),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        sut = newViewModel()
        val attempts = mutableListOf<Boolean>()
        sut.signAndBroadcast(
            request(),
            prepareContactPayment = { error("must keep the original preparation") },
            authorizeContactPayment = {
                attempts += it
                true
            },
        )
        advanceUntilIdle()

        assertEquals(listOf(true), attempts)
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertSignedOnceAndBroadcast(fixture, times = 2)
        verify(hwWalletRepo, times(1)).ensureConnected(WALLET_ID)
        assertNull(retainedRecord())
    }

    @Test
    fun `a changed fee rebroadcasts the original bytes`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(UNKNOWN_FAILURE))
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        for (viewModel in listOf(sut, newViewModel())) {
            viewModel.signAndBroadcast(request().copy(satsPerVByte = SATS_PER_VBYTE + 5uL, tags = listOf("new-tag")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.canLeave)
        }

        assertSignedOnceAndBroadcast(fixture, times = 3)
    }

    @Test
    fun `a task failure keeps the send and warns it may have been sent`() = test {
        val fixture = stubSuccessfulPayment()
        stubBroadcastWording()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.TaskException("task failed")))

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals(BROADCAST_UNCERTAIN_TITLE, toasts.single().title)
    }

    @Test
    fun `a timeout then cancel then retry rebroadcasts the same bytes`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        val timeout = runCatching { withTimeout(Duration.ZERO) { Unit } }
            .exceptionOrNull() as TimeoutCancellationException
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(timeout),
            Result.success(fixture.broadcast),
        )

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
        assertTrue(sut.uiState.value.canLeave)

        sut.cancel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertSignedOnceAndBroadcast(fixture, times = 2)
    }

    @Test
    fun `a different address or amount is refused with no device contact`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(UNKNOWN_FAILURE))
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        for (viewModel in listOf(sut, newViewModel())) {
            for (different in listOf(request().copy(address = OTHER_ADDRESS), request().copy(amountSats = 1uL))) {
                viewModel.signAndBroadcast(different)
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.canLeave)
            }
        }

        verify(hwWalletRepo, times(1)).needsPassphrase(WALLET_ID)
        verify(hwWalletRepo, times(1)).ensureConnected(WALLET_ID)
        verify(hwWalletRepo, times(1)).composeFundingTransaction(any(), any(), any(), any())
        assertSignedOnceAndBroadcast(fixture, times = 1)
        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
    }

    @Test
    fun `a first attempt connect failure leaves no record and leaving drops the bytes`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(CONNECT_FAILURE))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertNull(retainedRecord())
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)

        sut.cancel()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.hasPendingBroadcast)
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
    }

    @Test
    fun `a first attempt connect failure then a refusal drops and signs again`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(CONNECT_FAILURE),
            Result.failure(REFUSAL),
            Result.success(fixture.broadcast),
        )
        val attempts = mutableListOf<Boolean>()
        val authorize: suspend (Boolean) -> Boolean = {
            attempts += it
            true
        }

        sut.signAndBroadcast(request(), authorizeContactPayment = authorize)
        advanceUntilIdle()
        sut.signAndBroadcast(request(), authorizeContactPayment = authorize)
        advanceUntilIdle()

        assertEquals(listOf(false, false), attempts)
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        assertNull(retainedRecord())
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)

        sut.signAndBroadcast(request(), authorizeContactPayment = authorize)
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
    }

    @Test
    fun `a first attempt refusal releases and shows the refusal itself`() = test {
        val fixture = stubSuccessfulPayment()
        stubBroadcastWording()
        whenever(context.getString(R.string.common__error)).thenReturn("Error")
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(BroadcastException.InvalidTransaction("invalid transaction")),
        )

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertNull(retainedRecord())
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
    }

    @Test
    fun `a connect failure on a retry keeps the record unchanged`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.failure(CONNECT_FAILURE),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        val record = assertNotNull(retainedRecord())
        assertFalse(record.retryWasRefused)
        assertEquals(fixture.signedTx, record.signedTx)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        assertSignedOnceAndBroadcast(fixture, times = 2)
    }

    @Test
    fun `a retry refusal that is observed completes with the retained transaction`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.failure(REFUSAL),
        )
        whenever(hwWalletRepo.observeExactTransaction(WALLET_ID, FIXTURE_TX_ID)).thenReturn(Result.success(true))
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        val result = sut.results.first()
        assertEquals(HwSendResult(WALLET_ID, FIXTURE_TX_ID, AMOUNT_SATS), result)
        assertNull(retainedRecord())
        verify(hwWalletRepo, times(1)).observeExactTransaction(WALLET_ID, FIXTURE_TX_ID)
        verify(activityService).createSentOnchainActivityFromSendResult(
            FIXTURE_TX_ID,
            ADDRESS,
            AMOUNT_SATS,
            fixture.signedTx.miningFeeSats,
            fixture.signedTx.feeRate,
            false,
            null,
            WALLET_ID,
        )
        sut.completeBroadcast()
        assertTrue(sut.uiState.value.canLeave)
        assertSignedOnceAndBroadcast(fixture, times = 2)
    }

    @Test
    fun `a retry refusal that is not observed stays retained and blocking`() = test {
        val fixture = stubSuccessfulPayment()
        stubBroadcastWording()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.failure(REFUSAL),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(true, retainedRecord()?.retryWasRefused)
        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
        verify(hwWalletRepo, times(1)).observeExactTransaction(WALLET_ID, FIXTURE_TX_ID)
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        assertEquals(listOf(Toast.ToastType.WARNING, Toast.ToastType.WARNING), toasts.map { it.type })
        assertEquals(BROADCAST_UNCERTAIN_TITLE, toasts.last().title)
        assertTrue(sut.offersRetry(request()))
        assertSignedOnceAndBroadcast(fixture, times = 2)
    }

    @Test
    fun `another wallet is unaffected and its record survives`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(any())).thenReturn(Result.success(Unit))
        val otherSigned = fixture.signedTx.copy(serializedTx = "ab".repeat(100))
        whenever(hwWalletRepo.needsPassphrase(OTHER_WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.ensureConnected(OTHER_WALLET_ID)).thenReturn(Result.success(connectedDevice()))
        whenever(hwWalletRepo.composeFundingTransaction(OTHER_WALLET_ID, OTHER_ADDRESS, 1_000uL, SATS_PER_VBYTE))
            .thenReturn(Result.success(fixture.funding))
        whenever(hwWalletRepo.signFunding(OTHER_WALLET_ID, fixture.funding)).thenReturn(Result.success(otherSigned))
        whenever(hwWalletRepo.broadcastFunding(otherSigned)).thenReturn(Result.failure(UNKNOWN_FAILURE))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(fixture.broadcast),
        )
        val otherRequest = request().copy(walletId = OTHER_WALLET_ID, address = OTHER_ADDRESS, amountSats = 1_000uL)

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()
        sut.signAndBroadcast(otherRequest)
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        verify(hwWalletRepo).signFunding(OTHER_WALLET_ID, fixture.funding)
        verify(hwWalletRepo).broadcastFunding(otherSigned)
        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
        assertEquals(otherSigned, retainedRecord(OTHER_WALLET_ID)?.signedTx)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertSignedOnceAndBroadcast(fixture, times = 2)
        assertNull(retainedRecord())
        assertEquals(otherSigned, retainedRecord(OTHER_WALLET_ID)?.signedTx)
    }

    @Test
    fun `success releases the record`() = test {
        val fixture = stubSuccessfulPayment()

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertNull(retainedRecord())
    }

    @Test
    fun `the record is written before the native broadcast`() = test {
        val fixture = stubSuccessfulPayment()
        var recordAtDispatch: HwFundingSignedTx? = null
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer {
            recordAtDispatch = retainedRecord()?.signedTx
            Result.success(fixture.broadcast)
        }

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(fixture.signedTx, recordAtDispatch)
    }

    @Test
    fun `a failed record write aborts before dispatch`() = test {
        whenever(context.getString(any())).thenReturn("message")
        stubSuccessfulPayment()
        whenever(keychain.upsertScopedString(eq(RETAINED_KEY), any(), any())).doSuspendableAnswer {
            throw KeychainError.FailedToSave(RETAINED_KEY)
        }

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.canLeave)
    }

    @Test
    fun `an unreadable or corrupt record fails closed with no device contact`() = test {
        whenever(context.getString(any())).thenReturn("message")
        stubSuccessfulPayment()

        retainedEntries[WALLET_ID] = CORRUPT_ENTRY
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)

        retainedEntries.remove(WALLET_ID)
        whenever(keychain.loadScopedString(RETAINED_KEY, WALLET_ID)).thenAnswer {
            throw KeychainError.FailedToLoad(RETAINED_KEY)
        }
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canLeave)

        verify(hwWalletRepo, never()).needsPassphrase(any())
        verify(hwWalletRepo, never()).ensureConnected(any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
    }

    @Test
    fun `first attempt expiry in the broadcast queue releases the record`() = test {
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(AppError(ServiceError.PaymentDeadlineExpired())))

        sut.signAndBroadcast(request().copy(paymentDeadlineAt = deadline))
        advanceUntilIdle()

        assertNull(retainedRecord())
    }

    @Test
    fun `a retry after the watcher observed the transaction rebroadcasts and never signs twice`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(fixture.broadcast),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        markObserved()
        assertEquals(true, retainedRecord()?.observed)
        assertTrue(sut.offersRetry(request()))
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertSignedOnceAndBroadcast(fixture, times = 2)
        verify(hwWalletRepo, times(1)).ensureConnected(WALLET_ID)
        assertNull(retainedRecord())
    }

    @Test
    fun `a retry refusal with an observed record is a success with no signing or observation`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.failure(REFUSAL),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        markObserved()

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(HwSendResult(WALLET_ID, FIXTURE_TX_ID, AMOUNT_SATS), sut.results.first())
        assertNull(retainedRecord())
        assertSignedOnceAndBroadcast(fixture, times = 2)
        verify(hwWalletRepo, never()).observeExactTransaction(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `an unknown retry failure with an observed record is a success`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(UNKNOWN_FAILURE))
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        markObserved()

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(HwSendResult(WALLET_ID, FIXTURE_TX_ID, AMOUNT_SATS), sut.results.first())
        assertNull(retainedRecord())
        assertSignedOnceAndBroadcast(fixture, times = 2)
    }

    @Test
    fun `a first attempt refusal with an observed record is a success with one signature`() = test {
        val fixture = stubSuccessfulPayment()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer {
            markObserved()
            Result.failure(REFUSAL)
        }

        sut.signAndBroadcast(request())
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(HwSendResult(WALLET_ID, FIXTURE_TX_ID, AMOUNT_SATS), sut.results.first())
        assertNull(retainedRecord())
        assertTrue(toasts.isEmpty())
        assertSignedOnceAndBroadcast(fixture, times = 1)
        verify(hwWalletRepo, never()).observeExactTransaction(any(), any(), anyOrNull(), anyOrNull())
        verify(activityService).createSentOnchainActivityFromSendResult(
            FIXTURE_TX_ID,
            ADDRESS,
            AMOUNT_SATS,
            fixture.signedTx.miningFeeSats,
            fixture.signedTx.feeRate,
            false,
            null,
            WALLET_ID,
        )
    }

    @Test
    fun `an observed record offers retry for the same payment even after a refused retry`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        hwRetainedSendRepo.markRetryRefused(WALLET_ID).getOrThrow()
        assertFalse(sut.offersRetry(request()))

        markObserved()

        assertTrue(sut.offersRetry(request()))
        assertFalse(sut.offersRetry(request().copy(address = OTHER_ADDRESS)))
    }

    @Test
    fun `a reopened same payment with an observed record warns with no device contact then yes send signs anew`() =
        test {
            whenever(context.getString(any())).thenReturn("message")
            val fixture = stubSuccessfulPayment()
            whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
                Result.failure(UNKNOWN_FAILURE),
                Result.success(fixture.broadcast),
            )
            sut.signAndBroadcast(request())
            advanceUntilIdle()
            markObserved()
            clearInvocations(hwWalletRepo)

            sut = newViewModel()
            assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
            verifyNoInteractions(hwWalletRepo)

            sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
            assertEquals(true, retainedRecord()?.observed)
            assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
            assertFalse(sut.offersRetry(request()))

            sut.signAndBroadcast(request())
            advanceUntilIdle()

            verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
            assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        }

    @Test
    fun `an observed record stays blocking for the same payment after a restart`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()

        hwRetainedSendRepo = HwRetainedSendRepo(testDispatcher, keychain)
        sut = newViewModel()

        assertEquals(true, retainedRecord()?.observed)
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
    }

    @Test
    fun `a different payment with an observed record signs with no warning and releases it`() = test {
        val fixture = stubSuccessfulPayment()
        stubBroadcastWording()
        val otherRequest = request().copy(address = OTHER_ADDRESS)
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(fixture.broadcast),
        )
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.success(fixture.funding))
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        markObserved()

        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, 1uL, WALLET_ID, false))
        assertFalse(sut.offersRetry(otherRequest))

        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        sut.signAndBroadcast(otherRequest)
        advanceUntilIdle()
        toastJob.cancel()

        assertTrue(toasts.isEmpty())
        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
        assertNull(retainedRecord())
    }

    @Test
    fun `a different payment that fails before signing keeps the warning for the observed original`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(Result.failure(AppError("not connected")))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo).ensureConnected(WALLET_ID)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
    }

    @Test
    fun `a different payment replaces an observed record only once it is signed`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()
        val original = retainedEntries[WALLET_ID]
        val signing = CompletableDeferred<Result<HwFundingSignedTx>>()
        whenever(hwWalletRepo.signFunding(WALLET_ID, replacement.funding)).doSuspendableAnswer { signing.await() }

        sut.signAndBroadcast(replacementRequest())
        runCurrent()
        assertEquals(original, retainedEntries[WALLET_ID])

        var atDispatch: HwRetainedSend? = null
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).doSuspendableAnswer {
            atDispatch = retainedRecord()
            Result.success(replacement.broadcast)
        }
        signing.complete(Result.success(replacement.signedTx))
        advanceUntilIdle()

        assertEquals(replacement.signedTx, atDispatch?.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
        assertNull(retainedRecord())
    }

    @Test
    fun `retained status reports none for a wallet without a record or an incoming request`() = test {
        val fixture = stubSuccessfulPayment()
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        retainedEntries[WALLET_ID] = CORRUPT_ENTRY
        clearInvocations(keychain)

        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, true))
        verify(keychain, never()).loadScopedString(any(), any())
    }

    @Test
    fun `retained status offers a retry only for the same payment`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()

        val status = sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false)

        assertEquals(HwRetainedSendStatus.Retry(fixture.signedTx.feeRate, fixture.signedTx.miningFeeSats), status)
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, 1uL, WALLET_ID, false))
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, 1uL, OTHER_WALLET_ID, false))
    }

    @Test
    fun `an observed record keeps the open sheet on retry for the same payment only`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()

        assertTrue(sut.offersRetry(request()))
        assertFalse(sut.offersRetry(request().copy(address = OTHER_ADDRESS)))
        assertFalse(sut.offersRetry(request().copy(amountSats = AMOUNT_SATS + 1uL)))
        verify(hwWalletRepo, never()).ensureConnected(any())
    }

    @Test
    fun `retained status blocks on an unreadable or corrupt entry`() = test {
        retainedEntries[WALLET_ID] = CORRUPT_ENTRY
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        whenever(keychain.loadScopedString(RETAINED_KEY, WALLET_ID)).thenAnswer {
            throw KeychainError.FailedToLoad(RETAINED_KEY)
        }
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
    }

    @Test
    fun `backing out after consenting to a replacement keeps the original with one signature overall`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        stubReplacementPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(fixture.broadcast),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()
        val original = retainedEntries[WALLET_ID]

        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        sut.cancel()
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
        assertIs<HwRetainedSendStatus.Retry>(sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertTrue(sut.offersRetry(request()))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).ensureConnected(WALLET_ID)
        verify(hwWalletRepo, times(1)).signFunding(any(), any())
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `backing out over an observed original warns again with no device contact`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()
        val original = retainedEntries[WALLET_ID]

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        sut.cancel()
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        verifyNoInteractions(hwWalletRepo)
    }

    @Test
    fun `a repeat of an observed payment signs anew after a connect failure`() = test {
        assertRepeatSignsAnewAfter { _, _ ->
            whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(
                Result.failure(AppError("device not connected")),
                Result.success(connectedDevice()),
            )
        }
    }

    @Test
    fun `a repeat of an observed payment signs anew after a Jade PIN timeout`() = test {
        var isFirstConnect = true
        assertRepeatSignsAnewAfter { _, _ ->
            whenever(hwWalletRepo.ensureConnected(WALLET_ID)).doSuspendableAnswer {
                if (isFirstConnect) {
                    isFirstConnect = false
                    awaitCancellation()
                }
                Result.success(connectedDevice())
            }
        }
    }

    @Test
    fun `a repeat of an observed payment signs anew after a device rejection`() = test {
        assertRepeatSignsAnewAfter { fixture, newBytes ->
            whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).thenReturn(
                Result.failure(TrezorException.UserCancelled()),
                Result.success(newBytes.signedTx),
            )
        }
    }

    @Test
    fun `a repeat of an observed payment signs anew after a signing timeout`() = test {
        var isFirstSignature = true
        assertRepeatSignsAnewAfter { fixture, newBytes ->
            whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
            whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).doSuspendableAnswer {
                if (isFirstSignature) {
                    isFirstSignature = false
                    awaitCancellation()
                }
                Result.success(newBytes.signedTx)
            }
        }
    }

    @Test
    fun `a device rejection of a different payment keeps the original byte for byte and the next tap signs anew`() =
        test {
            val fixture = stubSuccessfulPayment()
            val replacement = stubReplacementPayment()
            hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
            val original = retainedEntries[WALLET_ID]
            whenever(hwWalletRepo.signFunding(WALLET_ID, replacement.funding)).thenReturn(
                Result.failure(TrezorException.UserCancelled()),
                Result.success(replacement.signedTx),
            )
            val toasts = mutableListOf<Toast>()
            val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

            sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
            sut.signAndBroadcast(replacementRequest())
            advanceUntilIdle()

            verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
            verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
            assertEquals(original, retainedEntries[WALLET_ID])
            assertEquals(
                HwRetainedSendStatus.None,
                sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
            )
            assertFalse(sut.offersRetry(replacementRequest()))

            sut.signAndBroadcast(replacementRequest())
            advanceUntilIdle()
            toastJob.cancel()

            assertTrue(toasts.isEmpty())
            verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
            verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
            verify(hwWalletRepo, times(1)).broadcastFunding(replacement.signedTx)
            assertEquals(replacement.broadcast.txId, sut.results.first().txId)
        }

    @Test
    fun `a cancel between attempts ends the consent and the status warns again`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.signFunding(WALLET_ID, replacement.funding))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
        assertIs<HwRetainedSendStatus.Retry>(sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(original, retainedEntries[WALLET_ID])

        markObserved()
        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
    }

    @Test
    fun `consent is not usable once the new bytes are signed and retained`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(replacement.broadcast),
        )

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertEquals(replacement.signedTx, retainedRecord()?.signedTx)
        assertIs<HwRetainedSendStatus.Retry>(sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertTrue(sut.offersRetry(replacementRequest()))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(replacement.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a tap after leaving a refused replacement warns with no device contact`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(Result.failure(REFUSAL))
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(context.getString(R.string.hardware__send_broadcast_uncertain_title))
            .thenReturn(BROADCAST_UNCERTAIN_TITLE)
        whenever(context.getString(R.string.hardware__send_broadcast_uncertain_text))
            .thenReturn(BROADCAST_UNCERTAIN_TEXT)
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        toasts.clear()
        sut.cancel()
        advanceUntilIdle()
        clearInvocations(hwWalletRepo)

        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        toastJob.cancel()

        verifyNoInteractions(hwWalletRepo)
        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals(BROADCAST_UNCERTAIN_TITLE, toasts.single().title)
        assertEquals(BROADCAST_UNCERTAIN_TEXT, toasts.single().description)
        assertTrue(sut.uiState.value.canLeave)
    }

    @Test
    fun `a first attempt refusal on a repeat of an observed payment keeps consent and signs anew`() = test {
        val (fixture, newBytes) = stubRepeatOfObservedPayment()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(newBytes.signedTx)).thenReturn(
            Result.failure(REFUSAL),
            Result.success(newBytes.broadcast),
        )

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.offersRetry(request()))
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(2)).broadcastFunding(newBytes.signedTx)
        assertEquals(newBytes.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a first attempt refusal of a different payment keeps consent and signs anew`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(
            Result.failure(REFUSAL),
            Result.success(replacement.broadcast),
        )

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.offersRetry(replacementRequest()))
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(2)).broadcastFunding(replacement.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `leaving after a refused repeat of an observed payment ends consent and the status warns again`() = test {
        val (_, newBytes) = stubRepeatOfObservedPayment()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(newBytes.signedTx)).thenReturn(Result.failure(REFUSAL))
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
    }

    @Test
    fun `a cancel while the broadcast is in flight is not undone by its refusal`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        val gate = CompletableDeferred<Unit>()
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).doSuspendableAnswer {
            gate.await()
            Result.failure(REFUSAL)
        }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        runCurrent()
        sut.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
    }

    @Test
    fun `a refusal after a retry of a consented connect failure keeps consent and signs anew`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(
            Result.failure(CONNECT_FAILURE),
            Result.failure(REFUSAL),
        )

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        assertTrue(sut.offersRetry(replacementRequest()))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
        assertFalse(sut.offersRetry(replacementRequest()))
    }

    @Test
    fun `an observed replacement of a record without consent gets no consent from its refusal`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val unobserved = requireNotNull(retainedEntries[WALLET_ID])
        markObserved()
        val observed = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(Result.failure(REFUSAL))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertEquals(observed, retainedEntries[WALLET_ID])
        // Only a record still in doubt shows whether consent is held: an observed one never warns for another payment.
        retainedEntries[WALLET_ID] = unobserved
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
    }

    @Test
    fun `new bytes the watcher saw before the restore stay the record and get no consent`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(
            WALLET_ID,
            ADDRESS,
            AMOUNT_SATS,
            fixture.signedTx.copy(serializedTx = "cd".repeat(100)),
        ).getOrThrow()
        var staleRead: String? = null
        whenever(keychain.loadScopedString(RETAINED_KEY, WALLET_ID)).thenAnswer {
            staleRead.also { staleRead = null } ?: retainedEntries[WALLET_ID]
        }
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer {
            val beforeObserved = retainedEntries[WALLET_ID]
            markObserved()
            staleRead = beforeObserved
            Result.failure(REFUSAL)
        }

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
        assertEquals(true, retainedRecord()?.observed)
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertTrue(sut.offersRetry(request()))
    }

    @Test
    fun `a failed restore after a refusal keeps the new bytes as the record and gets no consent`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(Result.failure(REFUSAL))
        var writes = 0
        whenever(keychain.upsertScopedString(eq(RETAINED_KEY), any(), any())).doSuspendableAnswer {
            if (++writes > 1) throw KeychainError.FailedToSave(RETAINED_KEY)
            retainedEntries[it.getArgument(2)] = it.getArgument(1)
        }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertEquals(replacement.signedTx, retainedRecord()?.signedTx)
        assertTrue(sut.offersRetry(replacementRequest()))
    }

    @Test
    fun `a failed read after a restore keeps consent`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        stubFailedReadAfterRestore(replacement, REFUSAL)

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertFalse(sut.offersRetry(replacementRequest()))
        assertTrue(hwRetainedSendRepo.record(WALLET_ID).isFailure)

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(2)).broadcastFunding(replacement.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a refused repeat of an observed payment signs anew despite a later failed read`() = test {
        val (fixture, newBytes) = stubRepeatOfObservedPayment()
        val original = retainedEntries[WALLET_ID]
        stubFailedReadAfterRestore(newBytes, REFUSAL)

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])

        // This tap's read is the one that fails. Consent reads nothing before signing, so the failure stops it at
        // retain, after the device and before any dispatch.
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(newBytes.signedTx)
        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.offersRetry(request()))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(3)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(2)).broadcastFunding(newBytes.signedTx)
        assertEquals(newBytes.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a failed read after a restore on expiry keeps consent and the next tap signs anew`() = test {
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        val request = replacementRequest().copy(paymentDeadlineAt = deadline)
        stubFailedReadAfterRestore(replacement, AppError(ServiceError.PaymentDeadlineExpired()), deadline)

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request)
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        assertTrue(hwRetainedSendRepo.record(WALLET_ID).isFailure)

        sut.signAndBroadcast(request)
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `consent is stale after cancel, after a different request and after a restart`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        val consent = { sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS) }

        consent()
        sut.cancel()
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        consent()
        sut.signAndBroadcast(replacementRequest().copy(amountSats = 1uL))
        advanceUntilIdle()
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        consent()
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, 1uL, WALLET_ID, false),
        )
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )

        consent()
        sut = newViewModel()
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).ensureConnected(any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        assertEquals(original, retainedEntries[WALLET_ID])
    }

    @Test
    fun `dismissing the passphrase prompt keeps the consent and a submitted passphrase signs anew`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true)

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isPassphraseRequired)
        assertFalse(sut.offersRetry(replacementRequest()))

        sut.dismissPassphrase()
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPassphraseRequired)
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.reconnectWithPassphrase(WALLET_ID, "secret")).thenReturn(Result.success(Unit))
        assertEquals(original, retainedEntries[WALLET_ID])

        sut.submitPassphrase(WALLET_ID, "secret") { sut.signAndBroadcast(replacementRequest()) }
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `dismissing the passphrase prompt on a repeat of an observed payment prompts again and signs anew`() = test {
        val (fixture, newBytes) = stubRepeatOfObservedPayment()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(newBytes.signedTx)).thenReturn(Result.success(newBytes.broadcast))
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true)

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPassphraseRequired)

        sut.dismissPassphrase()
        assertFalse(sut.uiState.value.isPassphraseRequired)
        assertFalse(sut.offersRetry(request()))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isPassphraseRequired)
        assertFalse(sut.offersRetry(request()))
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.completeReconciledBroadcast(WALLET_ID, fixture.broadcast.txId))
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        assertEquals(original, retainedEntries[WALLET_ID])

        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        whenever(hwWalletRepo.reconnectWithPassphrase(WALLET_ID, "secret")).thenReturn(Result.success(Unit))
        sut.submitPassphrase(WALLET_ID, "secret") { sut.signAndBroadcast(request()) }
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(1)).broadcastFunding(newBytes.signedTx)
        assertEquals(newBytes.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a cancel after dismissing the passphrase prompt ends the consent and the status warns again`() = test {
        stubRepeatOfObservedPayment()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true)
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.dismissPassphrase()
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        sut.cancel()
        advanceUntilIdle()

        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        verify(hwWalletRepo, never()).signFunding(any(), any())
        assertEquals(original, retainedEntries[WALLET_ID])
    }

    @Test
    fun `a failed passphrase submit keeps the consent and the next submit signs anew`() = test {
        val (fixture, newBytes) = stubRepeatOfObservedPayment()
        whenever(hwWalletRepo.broadcastFunding(newBytes.signedTx)).thenReturn(Result.success(newBytes.broadcast))
        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(true)
        whenever(hwWalletRepo.reconnectWithPassphrase(WALLET_ID, "secret")).thenReturn(
            Result.failure(HwPassphraseMismatchError()),
            Result.failure(AppError("reconnect failed")),
            Result.success(Unit),
        )

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        repeat(2) {
            sut.submitPassphrase(WALLET_ID, "secret") { sut.signAndBroadcast(request()) }
            advanceUntilIdle()
            assertTrue(sut.uiState.value.isPassphraseRequired)
            assertFalse(sut.offersRetry(request()))
            assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        }

        whenever(hwWalletRepo.needsPassphrase(WALLET_ID)).thenReturn(false)
        sut.submitPassphrase(WALLET_ID, "secret") { sut.signAndBroadcast(request()) }
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        assertEquals(newBytes.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a passphrase error while connecting keeps the consent for the restarted attempt`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        whenever(hwWalletRepo.ensureConnected(WALLET_ID)).thenReturn(
            Result.failure(HwPassphraseRequiredError()),
            Result.success(connectedDevice()),
        )

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isPassphraseRequired)

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
    }

    @Test
    fun `a failed replace write does not dispatch and leaves the original`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        var isWriteFailing = true
        whenever(keychain.upsertScopedString(eq(RETAINED_KEY), any(), any())).doSuspendableAnswer {
            if (isWriteFailing) throw KeychainError.FailedToSave(RETAINED_KEY)
            retainedEntries[it.getArgument(2)] = it.getArgument(1)
        }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(original, retainedEntries[WALLET_ID])
        assertTrue(sut.uiState.value.canLeave)
        assertFalse(sut.offersRetry(replacementRequest()))

        isWriteFailing = false
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a failed record read while retaining keeps the consent and the next tap signs anew`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        var isReadFailing = true
        whenever(keychain.loadScopedString(RETAINED_KEY, WALLET_ID)).thenAnswer {
            if (isReadFailing) throw AppError("read failed")
            retainedEntries[WALLET_ID]
        }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertFalse(sut.offersRetry(replacementRequest()))

        isReadFailing = false
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a first attempt refusal of the replacement restores the original`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(Result.failure(REFUSAL))

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo).broadcastFunding(replacement.signedTx)
        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.uiState.value.hasPendingBroadcast)
    }

    @Test
    fun `a connect failure of the replacement restores the original and retries in the sheet with no new signature`() =
        test {
            whenever(context.getString(any())).thenReturn("message")
            val fixture = stubSuccessfulPayment()
            val replacement = stubReplacementPayment()
            hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
            val original = retainedEntries[WALLET_ID]
            whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(
                Result.failure(CONNECT_FAILURE),
                Result.success(replacement.broadcast),
            )

            sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
            sut.signAndBroadcast(replacementRequest())
            advanceUntilIdle()

            assertEquals(original, retainedEntries[WALLET_ID])
            assertTrue(sut.offersRetry(replacementRequest()))

            sut.signAndBroadcast(replacementRequest())
            advanceUntilIdle()

            verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
            verify(hwWalletRepo, times(2)).broadcastFunding(replacement.signedTx)
            assertEquals(replacement.broadcast.txId, sut.results.first().txId)
        }

    @Test
    fun `leaving after the replacement failed to connect keeps the original guarded`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(Result.failure(CONNECT_FAILURE))

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        assertFalse(sut.offersRetry(replacementRequest()))
        assertEquals(original, retainedEntries[WALLET_ID])
        assertEquals(
            HwRetainedSendStatus.Blocking,
            sut.retainedSendStatus(OTHER_ADDRESS, AMOUNT_SATS, WALLET_ID, false),
        )
    }

    @Test
    fun `a failed restore keeps the new bytes as the record and the next attempt retries them`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx)).thenReturn(
            Result.failure(CONNECT_FAILURE),
            Result.success(replacement.broadcast),
        )
        var writes = 0
        whenever(keychain.upsertScopedString(eq(RETAINED_KEY), any(), any())).doSuspendableAnswer {
            if (++writes > 1) throw KeychainError.FailedToSave(RETAINED_KEY)
            retainedEntries[it.getArgument(2)] = it.getArgument(1)
        }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        assertEquals(replacement.signedTx, retainedRecord()?.signedTx)

        sut.signAndBroadcast(replacementRequest())
        advanceUntilIdle()

        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(replacement.signedTx)
    }

    @Test
    fun `expiry in the broadcast queue restores the replaced record`() = test {
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx, deadline))
            .thenReturn(Result.failure(AppError(ServiceError.PaymentDeadlineExpired())))

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest().copy(paymentDeadlineAt = deadline))
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
    }

    @Test
    fun `expiry in the broadcast queue keeps consent and the next tap signs anew`() = test {
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        val request = replacementRequest().copy(paymentDeadlineAt = deadline)
        whenever(hwWalletRepo.broadcastFunding(replacement.signedTx, deadline)).thenReturn(
            Result.failure(AppError(ServiceError.PaymentDeadlineExpired())),
            Result.success(replacement.broadcast),
        )

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request)
        advanceUntilIdle()

        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.offersRetry(request))

        sut.signAndBroadcast(request)
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, replacement.funding)
        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx, deadline)
        assertEquals(replacement.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `a send stopped during the replace write restores the original and never dispatches`() = test {
        val fixture = stubSuccessfulPayment()
        val replacement = stubReplacementPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        val original = retainedEntries[WALLET_ID]
        val gate = CompletableDeferred<Unit>()
        whenever(keychain.upsertScopedString(eq(RETAINED_KEY), any(), any())).doSuspendableAnswer {
            gate.await()
            retainedEntries[it.getArgument(2)] = it.getArgument(1)
        }

        sut.consentToNewSignature(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(replacementRequest())
        runCurrent()
        verify(hwWalletRepo).signFunding(WALLET_ID, replacement.funding)
        sut.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(original, retainedEntries[WALLET_ID])
    }

    @Test
    fun `consent signing bytes identical to an attempted record settles as a retry`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(REFUSAL))

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
        val record = assertNotNull(retainedRecord())
        assertTrue(record.retryWasRefused)
        assertEquals(fixture.signedTx, record.signedTx)
    }

    @Test
    fun `a corrupt entry is replaced after signing only with consent`() = test {
        val fixture = stubSuccessfulPayment()
        retainedEntries[WALLET_ID] = CORRUPT_ENTRY
        val signing = CompletableDeferred<Result<HwFundingSignedTx>>()
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).doSuspendableAnswer { signing.await() }
        var atDispatch: HwRetainedSend? = null
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer {
            atDispatch = retainedRecord()
            Result.success(fixture.broadcast)
        }

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        runCurrent()
        assertEquals(CORRUPT_ENTRY, retainedEntries[WALLET_ID])

        signing.complete(Result.success(fixture.signedTx))
        advanceUntilIdle()

        assertEquals(fixture.signedTx, atDispatch?.signedTx)
        assertEquals(fixture.broadcast.txId, sut.results.first().txId)
    }

    @Test
    fun `an undecryptable entry is overwritten after signing and never deleted before it`() = test {
        val fixture = stubSuccessfulPayment()
        whenever(keychain.loadScopedString(RETAINED_KEY, WALLET_ID)).thenAnswer {
            throw KeychainError.FailedToLoad(RETAINED_KEY)
        }
        val signing = CompletableDeferred<Result<HwFundingSignedTx>>()
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).doSuspendableAnswer { signing.await() }

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        runCurrent()
        verify(keychain, never()).upsertScopedString(any(), any(), any())

        signing.complete(Result.success(fixture.signedTx))
        advanceUntilIdle()

        verify(keychain).upsertScopedString(eq(RETAINED_KEY), any(), eq(WALLET_ID))
        verify(keychain, never()).deleteScoped(any(), any())
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
    }

    @Test
    fun `a device rejection with consent leaves a corrupt entry in place`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        retainedEntries[WALLET_ID] = CORRUPT_ENTRY
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding))
            .thenReturn(Result.failure(AppError("rejected on device")))

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertEquals(CORRUPT_ENTRY, retainedEntries[WALLET_ID])
        assertEquals(HwRetainedSendStatus.None, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))

        sut.cancel()
        advanceUntilIdle()

        assertEquals(CORRUPT_ENTRY, retainedEntries[WALLET_ID])
        assertEquals(HwRetainedSendStatus.Blocking, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
    }

    @Test
    fun `consent does nothing while an operation is in flight`() = test {
        val fixture = stubSuccessfulPayment()
        val finish = CompletableDeferred<Result<HwFundingBroadcastResult>>()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).doSuspendableAnswer { finish.await() }

        sut.signAndBroadcast(request())
        runCurrent()
        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)

        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
        assertEquals(RETAINED_FIXTURE_STATUS, sut.retainedSendStatus(ADDRESS, AMOUNT_SATS, WALLET_ID, false))
        finish.complete(Result.success(fixture.broadcast))
        advanceUntilIdle()
    }

    @Test
    fun `a kept ordinary send does not block a Shop payment`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.disconnectStaleSession(WALLET_ID)).thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(UNKNOWN_FAILURE),
            Result.success(fixture.broadcast),
        )
        sut.signAndBroadcast(request())
        advanceUntilIdle()
        sut.cancel()
        advanceUntilIdle()

        sut.signAndBroadcast(
            request().copy(
                paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
                paymentIdentity = "original-identity",
            )
        )
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
        assertEquals(fixture.signedTx, retainedRecord()?.signedTx)
    }

    @Test
    fun `offers retry for a matching record in a reopened sheet without device contact`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()

        assertTrue(newViewModel().offersRetry(request().copy(satsPerVByte = SATS_PER_VBYTE + 5uL)))
        verify(hwWalletRepo, never()).ensureConnected(any())
    }

    @Test
    fun `does not offer retry for another payment or wallet`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()

        assertFalse(sut.offersRetry(request().copy(address = OTHER_ADDRESS)))
        assertFalse(sut.offersRetry(request().copy(amountSats = AMOUNT_SATS + 1uL)))
        assertFalse(sut.offersRetry(request().copy(walletId = OTHER_WALLET_ID)))
    }

    @Test
    fun `does not offer retry for another payment after a kept attempt`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(UNKNOWN_FAILURE))
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.offersRetry(request()))
        assertFalse(sut.offersRetry(request().copy(amountSats = AMOUNT_SATS + 1uL)))
        assertFalse(sut.offersRetry(request().copy(walletId = OTHER_WALLET_ID)))
    }

    @Test
    fun `does not offer retry when the record is unreadable or a reopened retry was refused`() = test {
        val fixture = stubSuccessfulPayment()
        retainedEntries[WALLET_ID] = CORRUPT_ENTRY
        assertFalse(sut.offersRetry(request()))

        retainedEntries.remove(WALLET_ID)
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        hwRetainedSendRepo.markRetryRefused(WALLET_ID).getOrThrow()
        assertFalse(sut.offersRetry(request()))
    }

    @Test
    fun `offers retry in this sheet after a first attempt connect failure leaves no record`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(Result.failure(CONNECT_FAILURE))
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        assertNull(retainedRecord())
        assertTrue(sut.offersRetry(request()))
    }

    @Test
    fun `a Shop payment offers retry only from its pending broadcast`() = test {
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()

        assertFalse(sut.offersRetry(request().copy(paymentRequestId = SHOP_REQUEST_ID)))
    }

    @Test
    fun `a retry status locks the original fee on the review`() {
        val state = reviewState().withStatus(RETAINED_RETRY)

        assertTrue(state.isRetainedHardwarePayment)
        assertEquals(TransactionSpeed.Custom(RETAINED_RETRY.feeRate.toUInt()), state.speed)
        assertEquals(OnchainFeeUi(FeeRate.CUSTOM, RETAINED_RETRY.miningFeeSats.toLong()), state.onchainFeeUi)
        assertFalse(state.isRetainedSendCheckPending)
        assertFalse(state.needsRetainedSendWarning)
        assertNull(state.retainedSendToReplace)
    }

    @Test
    fun `a status for a payment no longer on review changes nothing`() {
        val state = reviewState()

        val stale = state.withStatus(RETAINED_RETRY, address = OTHER_ADDRESS)

        assertEquals(state, stale)
        assertTrue(stale.isRetainedSendCheckPending)
    }

    @Test
    fun `a blocking status allows a replacement only after the warning is confirmed`() {
        val state = reviewState().withStatus(HwRetainedSendStatus.Blocking)
        assertTrue(state.needsRetainedSendWarning)
        assertNull(state.retainedSendToReplace)

        val confirmed = state.copy(confirmedWarnings = persistentListOf(SanityWarning.RETAINED_HARDWARE_SEND))

        assertFalse(confirmed.needsRetainedSendWarning)
        assertEquals(WALLET_ID, confirmed.retainedSendToReplace)
        assertNull(confirmed.copy(amount = AMOUNT_SATS + 1uL).retainedSendToReplace)
        assertTrue(confirmed.copy(amount = AMOUNT_SATS + 1uL).isRetainedSendCheckPending)
    }

    @Test
    fun `reading the same blocking answer again keeps the consent and a new answer drops it`() {
        val confirmed = reviewState()
            .withStatus(HwRetainedSendStatus.Blocking)
            .copy(confirmedWarnings = persistentListOf(SanityWarning.RETAINED_HARDWARE_SEND))

        val reread = confirmed.withStatus(HwRetainedSendStatus.Blocking)
        assertEquals(WALLET_ID, reread.retainedSendToReplace)

        val released = reread.withStatus(HwRetainedSendStatus.None)
        assertTrue(released.confirmedWarnings.isEmpty())

        val blockedAgain = released.withStatus(HwRetainedSendStatus.Blocking)
        assertTrue(blockedAgain.needsRetainedSendWarning)
    }

    @Test
    fun `leaving the retried payment unlocks the fee at the default speed`() {
        val locked = reviewState().withStatus(RETAINED_RETRY)

        val changed = locked
            .copy(amount = AMOUNT_SATS + 1uL)
            .withStatus(HwRetainedSendStatus.Blocking, amountSats = AMOUNT_SATS + 1uL)

        assertFalse(changed.isRetainedHardwarePayment)
        assertEquals(TransactionSpeed.Medium, changed.speed)
        assertEquals(OnchainFeeUi(FeeRate.fromSpeed(TransactionSpeed.Medium)), changed.onchainFeeUi)
        assertTrue(changed.needsRetainedSendWarning)
    }

    @Test
    fun `a Shop review ignores the status and keeps its own lock`() {
        val shop = reviewState().copy(incomingPaymentRequestId = SHOP_REQUEST_ID, isRetainedHardwarePayment = true)

        val state = shop.withStatus(HwRetainedSendStatus.Blocking)

        assertEquals(shop, state)
        assertFalse(state.isRetainedSendCheckPending)
        assertFalse(state.needsRetainedSendWarning)
    }

    private suspend fun stubReplacementPayment(): PaymentFixture {
        val funding = HwFundingTransaction(
            psbt = "other-psbt",
            miningFeeSats = 2_000uL,
            feeRate = 4.0f,
            totalSpent = 27_000uL,
            satsPerVByte = SATS_PER_VBYTE,
        )
        val signedTx = HwFundingSignedTx(
            serializedTx = "ab".repeat(100),
            miningFeeSats = funding.miningFeeSats,
            feeRate = SATS_PER_VBYTE,
            totalSpent = funding.totalSpent,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = "other-txid",
            miningFeeSats = signedTx.miningFeeSats,
            feeRate = signedTx.feeRate,
            totalSpent = signedTx.totalSpent,
        )
        whenever(hwWalletRepo.composeFundingTransaction(WALLET_ID, OTHER_ADDRESS, AMOUNT_SATS, SATS_PER_VBYTE))
            .thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(WALLET_ID, funding)).thenReturn(Result.success(signedTx))
        whenever(hwWalletRepo.broadcastFunding(signedTx)).thenReturn(Result.success(broadcast))
        return PaymentFixture(funding, signedTx, broadcast)
    }

    private suspend fun TestScope.assertRepeatSignsAnewAfter(
        stubFirstAttemptFailure: suspend (PaymentFixture, PaymentFixture) -> Unit,
    ) {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()
        val original = retainedEntries[WALLET_ID]
        val newBytes = PaymentFixture(
            funding = fixture.funding,
            signedTx = fixture.signedTx.copy(serializedTx = "cd".repeat(100)),
            broadcast = fixture.broadcast.copy(txId = "new-txid"),
        )
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).thenReturn(Result.success(newBytes.signedTx))
        whenever(hwWalletRepo.broadcastFunding(newBytes.signedTx)).thenReturn(Result.success(newBytes.broadcast))
        stubFirstAttemptFailure(fixture, newBytes)

        sut.consentToNewSignature(WALLET_ID, ADDRESS, AMOUNT_SATS)
        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(any(), anyOrNull())
        assertEquals(original, retainedEntries[WALLET_ID])
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.completeReconciledBroadcast(WALLET_ID, fixture.broadcast.txId))
        assertFalse(sut.offersRetry(request()))

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(fixture.signedTx)
        verify(hwWalletRepo, times(1)).broadcastFunding(newBytes.signedTx)
        assertEquals(newBytes.broadcast.txId, sut.results.first().txId)
    }

    private suspend fun stubRepeatOfObservedPayment(): Pair<PaymentFixture, PaymentFixture> {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        hwRetainedSendRepo.retain(WALLET_ID, ADDRESS, AMOUNT_SATS, fixture.signedTx).getOrThrow()
        markObserved()
        val newBytes = PaymentFixture(
            funding = fixture.funding,
            signedTx = fixture.signedTx.copy(serializedTx = "cd".repeat(100)),
            broadcast = fixture.broadcast.copy(txId = "new-txid"),
        )
        whenever(hwWalletRepo.signFunding(WALLET_ID, fixture.funding)).thenReturn(Result.success(newBytes.signedTx))
        return fixture to newBytes
    }

    /** The first broadcast fails with [failure], and the one read after the write that restores the record fails. */
    private suspend fun stubFailedReadAfterRestore(
        payment: PaymentFixture,
        failure: Throwable,
        deadline: Instant? = null,
    ) {
        var broadcasts = 0
        var isRestorePending = false
        var isReadFailing = false
        whenever(hwWalletRepo.broadcastFunding(payment.signedTx, deadline)).doSuspendableAnswer {
            if (broadcasts++ > 0) return@doSuspendableAnswer Result.success(payment.broadcast)
            isRestorePending = true
            Result.failure(failure)
        }
        whenever(keychain.upsertScopedString(eq(RETAINED_KEY), any(), any())).doSuspendableAnswer {
            retainedEntries[it.getArgument(2)] = it.getArgument(1)
            isReadFailing = isRestorePending
            isRestorePending = false
        }
        whenever(keychain.loadScopedString(RETAINED_KEY, WALLET_ID)).thenAnswer {
            if (isReadFailing) {
                isReadFailing = false
                throw AppError("read failed")
            }
            retainedEntries[WALLET_ID]
        }
    }

    private fun replacementRequest() = request().copy(address = OTHER_ADDRESS)

    private fun reviewState() = SendUiState(hardwareWalletId = WALLET_ID, address = ADDRESS, amount = AMOUNT_SATS)

    private fun SendUiState.withStatus(
        status: HwRetainedSendStatus,
        address: String = ADDRESS,
        amountSats: ULong = AMOUNT_SATS,
    ) = withRetainedSendStatus(WALLET_ID, address, amountSats, status, TransactionSpeed.Medium)

    private suspend fun retainedRecord(walletId: String = WALLET_ID) =
        hwRetainedSendRepo.record(walletId).getOrThrow()

    private suspend fun markObserved() =
        hwRetainedSendRepo.markObserved(WALLET_ID, listOf(FIXTURE_TX_ID)).getOrThrow()

    private suspend fun assertSignedOnceAndBroadcast(fixture: PaymentFixture, times: Int) {
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(times)).broadcastFunding(fixture.signedTx)
    }

    private fun stubBroadcastWording() {
        whenever(context.getString(any())).thenReturn("message")
        whenever(context.getString(R.string.hardware__send_broadcast_failed_title)).thenReturn(BROADCAST_FAILED_TITLE)
        whenever(context.getString(R.string.hardware__send_broadcast_uncertain_title))
            .thenReturn(BROADCAST_UNCERTAIN_TITLE)
    }

    private fun signedFixtureHex() = requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction.hex"))
        .bufferedReader().readText().trim()

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

    private companion object {
        const val WALLET_ID = "hardware-wallet"
        const val ADDRESS = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
        const val AMOUNT_SATS = 25_000uL
        const val SATS_PER_VBYTE = 2uL
        const val OTHER_WALLET_ID = "other-hardware-wallet"
        const val OTHER_ADDRESS = "bcrt1qother"
        const val FIXTURE_TX_ID = "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e"
        const val BROADCAST_FAILED_TITLE = "Payment not confirmed"
        const val BROADCAST_UNCERTAIN_TITLE = "Transaction may have been sent"
        const val BROADCAST_UNCERTAIN_TEXT = "Your transaction may have been broadcast."
        const val CORRUPT_ENTRY = "{\"walletId\":\"hardware-wallet\""
        val RETAINED_KEY = Keychain.Key.HW_RETAINED_SEND.name
        val SHOP_REQUEST_ID = PaykitPaymentRequestId("request", "counterparty")
        val RETAINED_RETRY = HwRetainedSendStatus.Retry(feeRate = 7uL, miningFeeSats = 300uL)
        val RETAINED_FIXTURE_STATUS = HwRetainedSendStatus.Retry(feeRate = SATS_PER_VBYTE, miningFeeSats = 1_000uL)
        val UNKNOWN_FAILURE = BroadcastException.ElectrumException("Broadcast failed: response lost")
        val CONNECT_FAILURE = BroadcastException.ElectrumException("Failed to connect to Electrum: unreachable")
        val REFUSAL = BroadcastException.ElectrumException(
            """Broadcast failed: Electrum server error: "sendrawtransaction RPC error -25: """ +
                """bad-txns-inputs-missingorspent"""",
        )
    }
}
