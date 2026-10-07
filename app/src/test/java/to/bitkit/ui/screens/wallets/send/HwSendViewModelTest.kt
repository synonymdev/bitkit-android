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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PreActivityMetadataRepo
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

    private lateinit var sut: HwSendViewModel
    private var now = Instant.parse("2026-10-06T11:59:59Z")

    @Before
    fun setUp() {
        whenever(coreService.activity).thenReturn(activityService)
        whenever { hwWalletRepo.reconnectTimeout(any()) }.thenReturn(30.seconds)
        sut = HwSendViewModel(
            context = context,
            hwWalletRepo = hwWalletRepo,
            preActivityMetadataRepo = preActivityMetadataRepo,
            coreService = coreService,
            activityRepo = activityRepo,
            clock = object : Clock {
                override fun now() = now
            },
        )
    }

    @Test
    fun `signing reconnects and retries once after THP channel failure`() = test {
        val (funding, signedTx, broadcast) = stubSuccessfulPayment()
        whenever(hwWalletRepo.signFunding(WALLET_ID, funding)).thenReturn(
            Result.failure(TrezorException.ProtocolException("THP decryption error: aead::Error")),
            Result.success(signedTx),
        )
        sut.signAndBroadcast(request())
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
        val prepareContactPayment: suspend () -> Boolean = {
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
    fun `invalid transaction releases the attempt only without an earlier uncertain broadcast`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        for (hadPriorAttempt in listOf(false, true)) {
            val attempts = mutableListOf<Boolean>()
            if (hadPriorAttempt) {
                whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
                    .thenReturn(Result.failure(BroadcastException.ElectrumException("offline")))
                sut.signAndBroadcast(request(), onBroadcastAttemptChanged = { attempts += it })
                advanceUntilIdle()
            }
            whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
                .thenReturn(Result.failure(AppError(BroadcastException.InvalidTransaction("invalid transaction"))))

            sut.signAndBroadcast(request(), onBroadcastAttemptChanged = { attempts += it })
            advanceUntilIdle()

            assertEquals(hadPriorAttempt, attempts.last())
            assertEquals(hadPriorAttempt, sut.uiState.value.hasPendingBroadcast)
            assertTrue(sut.uiState.value.canLeave)
            sut.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun `unclassified broadcast failures retain the signed transaction for retry`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx)).thenReturn(
            Result.failure(AppError("broadcast outcome unknown")),
            Result.success(fixture.broadcast),
        )
        val attempts = mutableListOf<Boolean>()
        sut.signAndBroadcast(request(), onBroadcastAttemptChanged = { attempts += it })
        advanceUntilIdle()

        assertEquals(listOf(true), attempts)
        assertTrue(sut.uiState.value.hasPendingBroadcast)

        sut.signAndBroadcast(request(), onBroadcastAttemptChanged = { attempts += it })
        advanceUntilIdle()

        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(fixture.signedTx)
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
        val prepareContactPayment: suspend () -> Boolean = {
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
        val broadcastAttempts = mutableListOf<Boolean>()

        sut.signAndBroadcast(
            request().copy(paymentDeadlineAt = deadline),
            authorizeContactPayment = {
                attempts += it
                true
            },
            onPaymentDeadlineExpired = { attempts += it },
            onBroadcastAttemptChanged = { broadcastAttempts += it },
        )
        advanceUntilIdle()

        assertEquals(listOf(false, false), attempts)
        assertEquals(listOf(true, false), broadcastAttempts)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertFalse(sut.uiState.value.isSigning)
        sut.cancel()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
    }

    @Test
    fun `expiry during rebroadcast retains the earlier attempt without blocking dismissal`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline)).thenReturn(
            Result.failure(BroadcastException.ElectrumException("connection failed")),
            Result.failure(AppError(ServiceError.PaymentDeadlineExpired())),
        )
        val attempts = mutableListOf<Boolean>()
        val broadcastAttempts = mutableListOf<Boolean>()
        val authorize: suspend (Boolean) -> Boolean = {
            attempts += it
            attempts.size < 3
        }
        val request = request().copy(paymentDeadlineAt = deadline)

        sut.signAndBroadcast(
            request,
            authorizeContactPayment = authorize,
            onPaymentDeadlineExpired = { attempts += it },
            onBroadcastAttemptChanged = { broadcastAttempts += it },
        )
        advanceUntilIdle()
        sut.signAndBroadcast(
            request,
            authorizeContactPayment = authorize,
            onPaymentDeadlineExpired = { attempts += it },
            onBroadcastAttemptChanged = { broadcastAttempts += it },
        )
        advanceUntilIdle()

        assertEquals(listOf(false, true, true), attempts)
        assertEquals(listOf(true, true, true), broadcastAttempts)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        assertFalse(sut.uiState.value.isSigning)
        sut.cancel()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
        verify(hwWalletRepo).signFunding(WALLET_ID, fixture.funding)
    }

    @Test
    fun `confirmed payment resolves an expired hardware retry without rebroadcasting`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        val deadline = Instant.parse("2026-10-06T12:00:00Z")
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx, deadline))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
        val requestId = PaykitPaymentRequestId("request", "counterparty")
        val request = request().copy(paymentDeadlineAt = deadline, paymentRequestId = requestId)
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

        assertEquals(1, authorizations)
        assertEquals(true, expiredPriorAttempt)
        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isSigning)
        assertTrue(sut.uiState.value.canLeave)

        sut.resolveBroadcast(WALLET_ID, requestId, fixture.broadcast.txId)
        advanceUntilIdle()

        assertEquals(HwSendResult(WALLET_ID, fixture.broadcast.txId, AMOUNT_SATS), sut.results.first())
        verify(activityService).createSentOnchainActivityFromSendResult(
            txid = fixture.broadcast.txId,
            address = ADDRESS,
            amount = AMOUNT_SATS,
            fee = fixture.signedTx.miningFeeSats,
            feeRate = fixture.signedTx.feeRate,
            isTransfer = false,
            channelId = null,
            walletId = WALLET_ID,
        )
        sut.completeBroadcast()
        assertTrue(sut.uiState.value.canLeave)
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx, deadline)
    }

    @Test
    fun `payment resolution ignores a different request or wallet and stops an active retry`() = test {
        whenever(context.getString(any())).thenReturn("message")
        val fixture = stubSuccessfulPayment()
        whenever(hwWalletRepo.broadcastFunding(fixture.signedTx))
            .thenReturn(Result.failure(BroadcastException.ElectrumException("connection failed")))
        val requestId = PaykitPaymentRequestId("request", "counterparty")
        val request = request().copy(paymentRequestId = requestId)
        sut.signAndBroadcast(request)
        advanceUntilIdle()

        val authorization = CompletableDeferred<Boolean>()
        sut.signAndBroadcast(request, authorizeContactPayment = { authorization.await() })
        runCurrent()
        sut.resolveBroadcast("other-wallet", requestId, fixture.broadcast.txId)
        sut.resolveBroadcast(WALLET_ID, requestId.copy(paymentRequestId = "other-request"), fixture.broadcast.txId)
        runCurrent()
        verify(activityRepo, never()).notifyPaymentActivityChanged()
        assertTrue(sut.uiState.value.isSigning)

        sut.resolveBroadcast(WALLET_ID, requestId, fixture.broadcast.txId)
        advanceUntilIdle()
        authorization.complete(true)
        advanceUntilIdle()

        assertEquals(HwSendResult(WALLET_ID, fixture.broadcast.txId, AMOUNT_SATS), sut.results.first())
        verify(hwWalletRepo).broadcastFunding(fixture.signedTx)
        sut.completeBroadcast()
        assertTrue(sut.uiState.value.canLeave)
    }

    @Test
    fun `payment resolution cannot complete a hardware send before broadcast was attempted`() = test {
        stubSuccessfulPayment()
        val requestId = PaykitPaymentRequestId("request", "counterparty")
        sut.signAndBroadcast(request().copy(paymentRequestId = requestId), authorizeContactPayment = { false })
        advanceUntilIdle()

        assertFalse(sut.resolveBroadcast(WALLET_ID, requestId, "txid"))
        advanceUntilIdle()

        verify(hwWalletRepo, never()).broadcastFunding(any(), any())
        verify(activityRepo, never()).notifyPaymentActivityChanged()
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
    }

    @Test
    fun `expiry during retry authorization retains the earlier attempt without blocking dismissal`() = test {
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

        assertFalse(sut.uiState.value.isBroadcastUnresolved)
        assertTrue(sut.uiState.value.hasPendingBroadcast)
        assertFalse(sut.uiState.value.isSigning)
        assertTrue(sut.uiState.value.canLeave)
        sut.cancel()
        assertFalse(sut.uiState.value.hasPendingBroadcast)
        assertTrue(sut.uiState.value.canLeave)
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
            serializedTx = "rawtx",
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
