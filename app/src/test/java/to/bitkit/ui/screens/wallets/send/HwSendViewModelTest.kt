package to.bitkit.ui.screens.wallets.send

import android.content.Context
import com.synonym.bitkitcore.BroadcastException
import com.synonym.bitkitcore.TrezorException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.HwConnectedDevice
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.Toast
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.HwWalletMismatchError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.PreActivityMetadataRepo
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitPaymentProofRepo
import to.bitkit.services.ActivityService
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private lateinit var sut: HwSendViewModel
    private var now = Instant.parse("2026-10-06T11:59:59Z")

    @Before
    fun setUp() {
        whenever(coreService.activity).thenReturn(activityService)
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
        whenever { hwWalletRepo.reconnectTimeout(any()) }.thenReturn(30.seconds)
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

        sut.submitPassphrase(request(), "hidden wallet", prepareContactPayment, authorizeContactPayment)
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
    fun `broadcast failure warns the payment was not confirmed`() = test {
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
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
    fun `cancel drops the signed transaction after a failed broadcast`() = test {
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
        verify(hwWalletRepo).disconnectStaleSession(WALLET_ID)

        sut.signAndBroadcast(request())
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(WALLET_ID, fixture.funding)
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
    fun `first queued Shop expiry clears exact retained candidate before releasing preparation`() = test {
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        val requestId = PaykitPaymentRequestId("request", "counterparty")
        val original = request().copy(paymentRequestId = requestId, paymentIdentity = "original-identity",
            paymentDeadlineAt = deadline)
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(AppError(ServiceError.PaymentDeadlineExpired())))
        var cleared = false
        whenever(proofRepo.clearHardwareOnchainCandidateBeforeDispatch(requestId, WALLET_ID,
            "605fe246a6d51450ecff51ac3d0415f8824964e06a60ed6e186fa163cf1e9d4e",
            "original-identity", ADDRESS, AMOUNT_SATS, false)).doSuspendableAnswer {
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
        sut.signAndBroadcast(request().copy(paymentRequestId = PaykitPaymentRequestId("request", "counterparty"),
            paymentIdentity = "original-identity", paymentDeadlineAt = deadline),
            onPaymentDeadlineExpired = { expired = it })
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
        sut.signAndBroadcast(original)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isBroadcastUnresolved)
        sut.cancel()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        resume.complete(true)
        advanceUntilIdle()
        verify(hwWalletRepo, times(1)).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(1)).broadcastFunding(fixture.signedTx)
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
            any(), any(), any(), any(), any(), any(), org.mockito.kotlin.anyOrNull(), any(),
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
            fixture.broadcast.txId, ADDRESS, AMOUNT_SATS, fixture.broadcast.miningFeeSats,
            fixture.broadcast.feeRate, false, null, WALLET_ID,
        )
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
    }
}
