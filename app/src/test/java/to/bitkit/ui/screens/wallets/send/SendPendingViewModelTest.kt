package to.bitkit.ui.screens.wallets.send

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.SyncType
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.WalletScope
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainInputSpend
import to.bitkit.repositories.OnchainOriginalPaidError
import to.bitkit.repositories.OnchainOriginalStatusUnavailableError
import to.bitkit.repositories.OnchainPreparedReceipt
import to.bitkit.repositories.OnchainRecoveryFeeRate
import to.bitkit.repositories.OnchainSendAbandonPendingError
import to.bitkit.repositories.OnchainSendAbandonRefusedError
import to.bitkit.repositories.OnchainSendAbandonSyncError
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendBlockedError
import to.bitkit.repositories.OnchainSendCancelUnderbidsError
import to.bitkit.repositories.OnchainSendEvidence
import to.bitkit.repositories.OnchainSendInput
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.PaykitPaymentProofRepo
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import to.bitkit.viewmodels.SanityWarning
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SendPendingViewModelTest : BaseUnitTest() {

    private val pendingPaymentRepo = PendingPaymentRepo()
    private val activityRepo: ActivityRepo = mock()
    private val lightningRepo: LightningRepo = mock()
    private val paykitPaymentProofRepo: PaykitPaymentProofRepo = mock()

    private val attemptUpdates = MutableStateFlow(0L)
    private val nodeEvents = MutableSharedFlow<Event>()

    private val hash = "test_payment_hash"
    private val amount = 5000L

    private lateinit var sut: SendPendingViewModel

    @Before
    fun setUp() {
        whenever { activityRepo.findActivityByPaymentId(any(), any(), any(), any()) }.thenReturn(
            Result.failure(Exception("not found"))
        )
        whenever { activityRepo.findActivityByPaymentId(any(), any(), any(), any(), any()) }
            .thenReturn(Result.failure(Exception("not found")))
        whenever(lightningRepo.onchainSendAttemptUpdates).thenReturn(attemptUpdates)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever { lightningRepo.findOnchainSendCancellation(any()) }.thenReturn(Result.success(null))
        whenever { lightningRepo.getFeeRateForSpeed(any(), anyOrNull()) }
            .thenReturn(Result.failure(AppError("offline")))
        sut = createViewModel()
    }

    @Test
    fun `speed-up fee estimate and warnings precede its payment authentication`() = test {
        val authorization = async {
            sut.approveSpeedUp(
                feeRateSatsPerVByte = 9uL,
                estimatedFeeSats = 990uL,
                warnings = listOf(SanityWarning.FEE_OVER_HALF_VALUE),
                requiresPin = true,
                useBiometrics = false,
            )
        }
        advanceUntilIdle()
        assertEquals(RecoveryApproval.SpeedUp(9uL, 990uL), sut.uiState.value.recoveryApproval)
        sut.answerRecoveryApproval(true)
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Warning(SanityWarning.FEE_OVER_HALF_VALUE), sut.uiState.value.recoveryApproval)
        sut.answerRecoveryApproval(true)
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Pin, sut.uiState.value.recoveryApproval)
        assertEquals(true, sut.openRecoveryPin())
        sut.answerRecoveryPin(true)
        advanceUntilIdle()
        authorization.await()
        assertNull(sut.uiState.value.recoveryApproval)
    }

    @Test
    fun `exact prepared fee and warnings precede original retry payment authentication`() = test {
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            emptyList(),
            "original",
            1_000uL,
            feeRateSatsPerVByte = 10uL,
            miningFeeSats = 600uL
        )
        val authorization = async {
            sut.approveRecoveryFee(
                receipt,
                listOf(SanityWarning.FEE_OVER_HALF_VALUE, SanityWarning.FEE_OVER_10_USD),
                requiresPin = true,
                useBiometrics = true
            )
        }
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Fee(receipt), sut.uiState.value.recoveryApproval)
        sut.answerRecoveryApproval(true)
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Warning(SanityWarning.FEE_OVER_HALF_VALUE), sut.uiState.value.recoveryApproval)
        sut.answerRecoveryApproval(true)
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Warning(SanityWarning.FEE_OVER_10_USD), sut.uiState.value.recoveryApproval)
        sut.answerRecoveryApproval(true)
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Biometrics, sut.uiState.value.recoveryApproval)
        sut.useRecoveryPin()
        assertEquals(true, sut.openRecoveryPin())
        assertEquals(false, sut.openRecoveryPin(), "Returning from PIN must not navigate to PIN again")
        sut.answerRecoveryPin(true)
        advanceUntilIdle()
        authorization.await()
        assertNull(sut.uiState.value.recoveryApproval)
    }

    @Test
    fun `declined or cancelled recovery approval returns to idle pending`() = test {
        val receipt = OnchainPreparedReceipt(
            "ab".repeat(32),
            emptyList(),
            "original",
            1_000uL,
            feeRateSatsPerVByte = 10uL,
            miningFeeSats = 600uL
        )
        for (usePin in listOf(false, true)) {
            for (cancelCaller in listOf(false, true)) {
                val authorization = launch {
                    sut.approveRecoveryFee(receipt, emptyList(), requiresPin = true, useBiometrics = true)
                }
                runCurrent()
                assertEquals(RecoveryApproval.Fee(receipt), sut.uiState.value.recoveryApproval)
                if (usePin) {
                    sut.answerRecoveryApproval(true)
                    runCurrent()
                    sut.useRecoveryPin()
                    assertTrue(sut.openRecoveryPin())
                }
                if (cancelCaller) {
                    authorization.cancelAndJoin()
                } else {
                    if (usePin) sut.answerRecoveryPin(false) else sut.answerRecoveryApproval(false)
                    runCurrent()
                }
                assertTrue(authorization.isCancelled)
                assertNull(sut.uiState.value.recoveryApproval)
            }
        }
    }

    @Test
    fun `declined recovery pin preserves the original and allows one authorized retry`() = test {
        val original = pendingOriginal()
        val receipt = OnchainPreparedReceipt(
            requireNotNull(original.txid),
            emptyList(),
            original.address,
            original.amountSats,
            feeRateSatsPerVByte = 2uL,
            miningFeeSats = 600uL,
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        runCurrent()
        var preparations = 0
        var sends = 0
        val retry: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome> = { attempt, _ ->
            assertEquals(original, attempt)
            preparations++
            sut.approveRecoveryFee(receipt, emptyList(), requiresPin = true, useBiometrics = true)
            sends++
            Result.success(OnchainSendOutcome.Unknown(requireNotNull(original.txid)))
        }

        for (approved in listOf(false, true)) {
            sut.retryOriginal(2uL, retry)
            sut.retryOriginal(2uL, retry)
            runCurrent()
            assertEquals(RecoveryApproval.Fee(receipt), sut.uiState.value.recoveryApproval)
            sut.answerRecoveryApproval(true)
            runCurrent()
            assertEquals(RecoveryApproval.Biometrics, sut.uiState.value.recoveryApproval)
            sut.useRecoveryPin()
            assertTrue(sut.openRecoveryPin())
            sut.answerRecoveryPin(approved)
            runCurrent()
            sut.answerRecoveryPin(true)
            runCurrent()

            assertNull(sut.uiState.value.recoveryApproval)
            assertEquals(false, sut.uiState.value.isRecovering)
            assertEquals(original, sut.uiState.value.recoveryAttempt)
            assertEquals(if (approved) original.txid else null, sut.uiState.value.currentTxid)
            assertNull(sut.uiState.value.recoveredTxid)
            assertEquals(if (approved) 1 else 0, sends)
        }
        assertEquals(2, preparations)
    }

    @Test
    fun `declined recovery pin preserves expired payment until a later authorized release is sent`() = test {
        val original = pendingOriginal().copy(
            requestId = PaykitPaymentRequestId("request", "payee"),
            orderId = "order",
            payerIdentity = "payer",
        )
        val receipt = OnchainPreparedReceipt(
            "cd".repeat(32),
            emptyList(),
            "release-address",
            original.amountSats,
            feeRateSatsPerVByte = 2uL,
            miningFeeSats = 600uL,
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        var preparations = 0
        var releases = 0
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any()))
            .doSuspendableAnswer {
                assertEquals(original, it.getArgument<OnchainSendAttempt>(0))
                preparations++
                it.getArgument<suspend (OnchainPreparedReceipt) -> Unit>(2)(receipt)
                releases++
                // A sent release holds the guard until it is final
                Result.failure<Unit>(OnchainSendAbandonPendingError())
            }
        sut.initOnchain(original.txid, amount)
        runCurrent()

        for (approved in listOf(false, true)) {
            sut.abandonExpired(requiresPin = true, useBiometrics = true)
            sut.abandonExpired(requiresPin = true, useBiometrics = true)
            runCurrent()
            assertEquals(RecoveryApproval.Release(receipt), sut.uiState.value.recoveryApproval)
            sut.answerRecoveryApproval(true)
            runCurrent()
            assertEquals(RecoveryApproval.Biometrics, sut.uiState.value.recoveryApproval)
            sut.useRecoveryPin()
            assertTrue(sut.openRecoveryPin())
            sut.answerRecoveryPin(approved)
            runCurrent()
            sut.answerRecoveryPin(true)
            runCurrent()

            assertNull(sut.uiState.value.recoveryApproval)
            assertEquals(false, sut.uiState.value.isAbandoning)
            assertEquals(if (approved) AbandonError.ReleasePending else null, sut.uiState.value.abandonError)
            assertEquals(original, sut.uiState.value.recoveryAttempt)
            assertEquals(original, sut.uiState.value.abandonableAttempt)
            assertEquals(!approved, sut.uiState.value.canAbandon)
            assertEquals(if (approved) 1 else 0, releases)
        }
        assertEquals(2, preparations)
    }

    @Test
    fun `pending original retry is explicit serialized and retains original amount`() = test {
        val txid = "ab".repeat(32)
        val original = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "original", requestId = null,
            orderId = null, address = "original-address", amountSats = 1_000uL, isMaxAmount = true,
            feeRateSatsPerVByte = 2uL, isTransfer = false, channelId = null, tags = emptyList(),
            txid = txid, evidence = OnchainSendEvidence.Unknown,
            originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u)),
            candidateTxids = listOf(txid),
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(txid, 9_999L)
        advanceUntilIdle()
        assertEquals(original, sut.uiState.value.recoveryAttempt)
        var sends = 0
        val release = CompletableDeferred<Unit>()
        val retry: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome> =
            { retained, fee ->
                sends++
                assertEquals(original, retained)
                assertEquals(3uL, fee)
                release.await()
                Result.success(OnchainSendOutcome.Unknown("cd".repeat(32)))
            }
        assertEquals(0, sends)
        sut.retryOriginal(ULong.MAX_VALUE, retry)
        advanceUntilIdle()
        assertEquals(0, sends)
        assertEquals(true, sut.uiState.value.invalidFeeRate)
        assertEquals(false, sut.uiState.value.isRecovering)
        sut.retryOriginal(3uL, retry)
        sut.retryOriginal(3uL, retry)
        advanceUntilIdle()
        assertEquals(1, sends)
        assertEquals(true, sut.uiState.value.isRecovering)
        release.complete(Unit)
        advanceUntilIdle()
        assertNull(sut.uiState.value.recoveredTxid)
        assertEquals("cd".repeat(32), sut.uiState.value.currentTxid)
        assertEquals(original.amountSats, sut.uiState.value.recoveryAttempt?.amountSats)
    }

    @Test
    fun `accepted retry waits for exact durable original completion`() = test {
        val original = pendingOriginal()
        val winner = "cd".repeat(32)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        val accepted = original.copy(
            txid = winner,
            candidateTxids = original.candidateTxids + winner,
            evidence = OnchainSendEvidence.Accepted,
            localFollowupComplete = false,
        )
        sut.retryOriginal(2uL) { _, _ ->
            whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(accepted)
            Result.success(OnchainSendOutcome.Accepted(winner))
        }
        advanceUntilIdle()
        assertNull(sut.uiState.value.recoveredTxid)
        assertEquals(winner, sut.uiState.value.currentTxid)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(accepted.copy(localFollowupComplete = true))
        attemptUpdates.value++
        advanceUntilIdle()
        assertEquals(winner, sut.uiState.value.recoveredTxid)
        assertNull(sut.uiState.value.recoveryAttempt)
    }

    @Test
    fun `exact ordinary observed successor resolves only after original local completion`() = test {
        val original = pendingOriginal()
        val winner = "cd".repeat(32)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, 9_999L)
        advanceUntilIdle()
        val lateResult = CompletableDeferred<OnchainSendOutcome>()
        sut.retryOriginal(2uL) { _, _ -> Result.success(lateResult.await()) }
        advanceUntilIdle()
        val observed = original.copy(
            txid = winner,
            candidateTxids = original.candidateTxids + winner,
            evidence = OnchainSendEvidence.Observed,
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(observed)
        attemptUpdates.value++
        advanceUntilIdle()
        assertNull(sut.uiState.value.recoveredTxid)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(observed.copy(localFollowupComplete = true))
        attemptUpdates.value++
        advanceUntilIdle()
        assertEquals(winner, sut.uiState.value.recoveredTxid)
        assertEquals(1_000L, sut.uiState.value.amount)
        assertNull(sut.uiState.value.recoveryAttempt)
        lateResult.complete(OnchainSendOutcome.Unknown(original.txid!!))
        advanceUntilIdle()
        assertEquals(winner, sut.uiState.value.recoveredTxid)
        assertEquals(winner, sut.uiState.value.currentTxid)
        org.mockito.kotlin.verify(activityRepo, org.mockito.kotlin.never()).syncActivities()
    }

    @Test
    fun `pending screen cannot resolve a foreign operation or wallet after update`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        val positive = original.copy(evidence = OnchainSendEvidence.Observed, localFollowupComplete = true)
        listOf(
            positive.copy(attemptId = "another-operation"),
            positive.copy(walletId = "hardware"),
            positive.copy(walletIndex = 1),
            positive.copy(txid = "ef".repeat(32), candidateTxids = listOf("ef".repeat(32))),
            positive.copy(requestId = to.bitkit.repositories.PaykitPaymentRequestId("foreign", "payer", "/pub/paykit")),
        ).forEach { foreign ->
            whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(foreign)
            attemptUpdates.value++
            advanceUntilIdle()
            assertNull(sut.uiState.value.recoveredTxid)
            assertEquals(1_000L, sut.uiState.value.amount)
        }
    }

    @Test
    fun `ordinary queued activity and nonpositive outcomes never resolve pending`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        listOf(OnchainSendEvidence.Pending, OnchainSendEvidence.Rejected, OnchainSendEvidence.Unknown).forEach {
            whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original.copy(evidence = it))
            attemptUpdates.value++
            advanceUntilIdle()
            assertNull(sut.uiState.value.recoveredTxid)
        }
    }

    @Test
    fun `transfer pending follows exact funded order only after durable completion`() = test {
        val original = pendingOriginal().copy(
            isTransfer = true,
            orderId = "original-order",
            transferContext = to.bitkit.repositories.OnchainTransferContext(1_200uL, 20_000uL, 1_000uL, 200uL),
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        sut.retryOriginal(2uL) { _, _ -> Result.success(OnchainSendOutcome.Accepted(original.txid!!)) }
        advanceUntilIdle()
        assertNull(sut.uiState.value.recoveredTxid)
        assertNull(sut.uiState.value.recoveredTransfer)
        val winner = "cd".repeat(32)
        val observed = original.copy(
            txid = winner,
            candidateTxids = original.candidateTxids + winner,
            evidence = OnchainSendEvidence.Observed,
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(observed)
        attemptUpdates.value++
        advanceUntilIdle()
        assertNull(sut.uiState.value.recoveredTransfer)
        val complete = observed.copy(localFollowupComplete = true)
        listOf(
            complete.copy(orderId = "different-order"),
            complete.copy(transferContext = original.transferContext!!.copy(txTotalSats = 1_300uL)),
            complete.copy(attemptId = "different-attempt"),
            complete.copy(walletIndex = 1),
        ).forEach { unrelated ->
            whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(unrelated)
            attemptUpdates.value++
            advanceUntilIdle()
            assertNull(sut.uiState.value.recoveredTransfer)
        }
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(complete)
        attemptUpdates.value++
        advanceUntilIdle()
        assertEquals(winner, sut.uiState.value.currentTxid)
        assertEquals(complete, sut.uiState.value.recoveredTransfer)
        assertNull(sut.uiState.value.recoveredTxid)
        assertNull(sut.uiState.value.recoveryAttempt)
        org.mockito.kotlin.verify(activityRepo, org.mockito.kotlin.never()).syncActivities()
    }

    @Test
    fun `cancellation keeps the original guard until it is released and then closes`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        var cancels = 0
        val refused: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome> = { retained, fee ->
            cancels++
            assertEquals(original, retained)
            assertEquals(3uL, fee)
            Result.success(OnchainSendOutcome.Rejected(cancelTxid, "min relay fee not met"))
        }

        sut.cancelOriginal(1_000uL, refused)
        advanceUntilIdle()
        assertEquals(0, cancels)
        assertTrue(sut.uiState.value.invalidFeeRate)
        sut.cancelOriginal(3uL, refused)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.cancellationFailed)
        assertNull(sut.uiState.value.cancellationTxid)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
        sut.cancelOriginal(4uL) { _, _ ->
            cancels++
            Result.success(OnchainSendOutcome.Accepted(cancelTxid))
        }
        advanceUntilIdle()
        assertEquals(cancelTxid, sut.uiState.value.cancellationTxid)
        assertFalse(sut.uiState.value.cancellationFailed)
        assertFalse(sut.uiState.value.isGuardReleased)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
        // Until the wallet holds it, a sent cancellation can still be outbid by another
        assertTrue(sut.uiState.value.canCancel)
        assertFalse(sut.uiState.value.canSpeedUpCancellation)
        assertFalse(sut.uiState.value.showsCancelHint)
        assertFalse(sut.uiState.value.canRetry)
        assertEquals(5uL, sut.uiState.value.suggestedFeeRate(RecoveryAction.Cancel))
        // Once the wallet holds it, the cancellation keeps the original's coins and is sped up instead
        val held = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(held))
        nodeEvents.emit(Event.SyncCompleted(syncType = SyncType.ONCHAIN_WALLET, syncedBlockHeight = 100u))
        advanceUntilIdle()
        assertFalse(sut.uiState.value.canCancel)
        assertTrue(sut.uiState.value.canSpeedUpCancellation)
        assertEquals(5uL, sut.uiState.value.suggestedFeeRate(RecoveryAction.SpeedUpCancellation))

        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(null)
        attemptUpdates.value += 1
        advanceUntilIdle()

        assertEquals(2, cancels)
        assertTrue(sut.uiState.value.isGuardReleased)
        assertNull(sut.uiState.value.recoveryAttempt)
    }

    @Test
    fun `reopened unconfirmed cancellation can be sped up and stays guarded`() = test {
        val original = pendingOriginal().copy(candidateFeeRates = mapOf("ab".repeat(32) to 3uL))
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        val unconfirmed = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(unconfirmed))

        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        val reopened = sut.uiState.value
        assertEquals(cancelTxid, reopened.cancellationTxid)
        assertEquals(original, reopened.recoveryAttempt)
        assertFalse(reopened.isGuardReleased)
        assertFalse(reopened.canCancel)
        assertTrue(reopened.canSpeedUpCancellation)
        assertFalse(reopened.isReleaseStalled)
        assertFalse(reopened.canRetry)
        assertFalse(reopened.canRetryWithoutChecking)
        assertEquals(4uL, reopened.suggestedFeeRate(RecoveryAction.SpeedUpCancellation))

        val replacementTxid = "cd".repeat(32)
        sut.speedUpCancellation(6uL) { retained, fee ->
            assertEquals(original, retained)
            assertEquals(6uL, fee)
            Result.success(replacementTxid)
        }
        advanceUntilIdle()

        val replaced = sut.uiState.value
        assertEquals(replacementTxid, replaced.cancellationTxid)
        assertTrue(replaced.canSpeedUpCancellation)
        assertEquals(7uL, replaced.suggestedFeeRate(RecoveryAction.SpeedUpCancellation))
        assertEquals(original, replaced.recoveryAttempt)
        assertFalse(replaced.isGuardReleased)
    }

    @Test
    fun `confirming cancellation can no longer be sped up or replaced`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        val confirming = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false, 1u)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(confirming))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        var speedUps = 0

        sut.speedUpCancellation(6uL) { _, _ ->
            speedUps++
            Result.success("cd".repeat(32))
        }
        advanceUntilIdle()

        val state = sut.uiState.value
        assertEquals(1u, state.cancellationConfirmations)
        assertFalse(state.canSpeedUpCancellation)
        assertFalse(state.canCancel)
        assertFalse(state.canRetry)
        assertEquals(0, speedUps)
        assertEquals(cancelTxid, state.cancellationTxid)
        assertEquals(original, state.recoveryAttempt)
    }

    @Test
    fun `final cancellation awaiting release offers no further recovery`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        val final = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), true)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(final))

        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        assertEquals(cancelTxid, sut.uiState.value.cancellationTxid)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
        assertFalse(sut.uiState.value.canCancel)
        assertFalse(sut.uiState.value.canSpeedUpCancellation)
        assertFalse(sut.uiState.value.canRetry)
        // Final, yet the guard is still held, so Pending says the release is unfinished instead of waiting for 6
        assertTrue(sut.uiState.value.isReleaseStalled)
    }

    @Test
    fun `reopened payment with a saved Cancel payment never retries beside it and outbids it`() = test {
        val original = requestOriginal().copy(
            cancellationTxid = cancelTxid,
            cancellationFeeRateSatsPerVByte = 8uL,
            cancellationMiningFeeSats = 1_200uL,
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)

        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        val reopened = sut.uiState.value
        assertFalse(reopened.canRetry)
        assertFalse(reopened.canRetryWithoutChecking)
        // The wallet does not hold it, so it may have dropped out and a replacement can still be built
        assertTrue(reopened.canCancel)
        assertEquals(9uL, reopened.suggestedFeeRate(RecoveryAction.Cancel))
    }

    @Test
    fun `retry without checking says when the request status is unread`() = test {
        val original = requestOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canRetryWithoutChecking)

        sut.retryOriginal(2uL) { _, _ -> Result.failure(OnchainOriginalStatusUnavailableError(AppError("offline"))) }
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isOriginalStatusUnknown)
        assertFalse(sut.uiState.value.isOriginalPaid)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
    }

    @Test
    fun `Cancel payment suggests at least the normal fee rate`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(lightningRepo.getFeeRateForSpeed(TransactionSpeed.Medium, null)).thenReturn(Result.success(12uL))

        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        assertEquals(12uL, sut.uiState.value.suggestedFeeRate(RecoveryAction.Cancel))
        assertEquals(original.feeRateSatsPerVByte, sut.uiState.value.suggestedFeeRate(RecoveryAction.Retry))
        val pricey = SendPendingUiState(recoveryAttempt = original, normalFeeRate = 5_000uL)
        assertEquals(OnchainRecoveryFeeRate.maximum, pricey.suggestedFeeRate(RecoveryAction.Cancel))
    }

    @Test
    fun `a failed speed-up says so and suggests a rate that outbids`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        val held = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(held))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canSpeedUpCancellation)

        sut.speedUpCancellation(3uL) { _, _ -> Result.failure(OnchainSendCancelUnderbidsError(9uL, 1_000uL)) }
        advanceUntilIdle()

        val failed = sut.uiState.value
        assertFalse(failed.speedUpFailed)
        assertEquals(1_000uL, failed.cancelFeeTooLowSats)
        assertEquals(10uL, failed.suggestedFeeRate(RecoveryAction.SpeedUpCancellation))
        assertEquals(cancelTxid, failed.cancellationTxid)
        assertTrue(failed.canSpeedUpCancellation)
    }

    @Test
    fun `failed re-cancel while a cancellation is pending reports the earlier one as still pending`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        val confirming = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(confirming))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isEarlierCancellationPending)

        sut.cancelOriginal(6uL) { _, _ -> Result.failure(IllegalStateException("original inputs unavailable")) }
        advanceUntilIdle()

        val failed = sut.uiState.value
        assertTrue(failed.cancellationFailed)
        assertTrue(failed.isEarlierCancellationPending)
        assertEquals(cancelTxid, failed.cancellationTxid)
        assertEquals(original, failed.recoveryAttempt)
        assertFalse(failed.canCancel)
        assertTrue(failed.canSpeedUpCancellation)
        assertFalse(failed.isGuardReleased)
    }

    @Test
    fun `cancellation with an unknown outcome hides retries but stays replaceable`() = test {
        val original = requestOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        var retries = 0
        val retry: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome> = { _, _ ->
            retries++
            Result.success(OnchainSendOutcome.Accepted("cd".repeat(32)))
        }

        sut.cancelOriginal(3uL) { _, _ ->
            Result.success(OnchainSendOutcome.Rejected(cancelTxid, "min relay fee not met"))
        }
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canRetry)

        whenever(lightningRepo.currentOnchainSendAttempt())
            .thenReturn(original.copy(cancellationTxid = cancelTxid, cancellationSentAtMillis = SENT_AT_MILLIS))
        sut.cancelOriginal(4uL) { _, _ -> Result.success(OnchainSendOutcome.Unknown(cancelTxid)) }
        advanceUntilIdle()
        // It may have been relayed, so it shows as sent
        val unknown = sut.uiState.value
        assertFalse(unknown.cancellationFailed)
        assertFalse(unknown.isEarlierCancellationPending)
        assertEquals(cancelTxid, unknown.cancellationTxid)
        assertFalse(unknown.isCancellationUnheld)
        assertFalse(unknown.canRetry)
        assertFalse(unknown.canRetryWithoutChecking)
        assertTrue(unknown.canCancel)
        assertEquals(5uL, unknown.suggestedFeeRate(RecoveryAction.Cancel))
        sut.retryOriginal(2uL, retry)
        advanceUntilIdle()
        assertEquals(0, retries)

        // A sync that finished before the cancellation was sent says nothing about it
        whenever(lightningRepo.latestOnchainWalletSyncTimestamp()).thenReturn(SYNC_BEFORE_SENT_SECS)
        nodeEvents.emit(Event.SyncCompleted(syncType = SyncType.ONCHAIN_WALLET, syncedBlockHeight = 100u))
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isCancellationUnheld)
        // A later sync whose wallet still lacks it says it may not have reached the network, and offers a higher fee
        whenever(lightningRepo.latestOnchainWalletSyncTimestamp()).thenReturn(SYNC_AFTER_SENT_SECS)
        nodeEvents.emit(Event.SyncCompleted(syncType = SyncType.ONCHAIN_WALLET, syncedBlockHeight = 101u))
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isCancellationUnheld)
        assertTrue(sut.uiState.value.canCancel)
        assertEquals(5uL, sut.uiState.value.suggestedFeeRate(RecoveryAction.Cancel))

        sut.cancelOriginal(5uL) { _, _ ->
            Result.success(OnchainSendOutcome.Rejected("cd".repeat(32), "insufficient fee"))
        }
        advanceUntilIdle()
        assertTrue(sut.uiState.value.cancellationFailed)
        assertFalse(sut.uiState.value.canRetry)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
    }

    @Test
    fun `speed-up is withdrawn once a sync no longer finds the held cancellation`() = test {
        val original = pendingOriginal().copy(cancellationTxid = cancelTxid, cancellationSentAtMillis = SENT_AT_MILLIS)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(lightningRepo.latestOnchainWalletSyncTimestamp()).thenReturn(SYNC_AFTER_SENT_SECS)
        val held = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(held))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canSpeedUpCancellation)

        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(null))
        nodeEvents.emit(Event.SyncCompleted(syncType = SyncType.ONCHAIN_WALLET, syncedBlockHeight = 101u))
        advanceUntilIdle()

        val dropped = sut.uiState.value
        assertFalse(dropped.canSpeedUpCancellation)
        assertTrue(dropped.canCancel)
        assertTrue(dropped.isCancellationUnheld)
        assertFalse(dropped.canRetry)
    }

    @Test
    fun `reopened unheld cancellation is reported only after a sync since it was sent`() = test {
        val original = pendingOriginal().copy(cancellationTxid = cancelTxid, cancellationSentAtMillis = SENT_AT_MILLIS)
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(lightningRepo.latestOnchainWalletSyncTimestamp()).thenReturn(SYNC_BEFORE_SENT_SECS)

        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        val reopened = sut.uiState.value
        assertEquals(cancelTxid, reopened.cancellationTxid)
        assertFalse(reopened.isCancellationUnheld)
        assertTrue(reopened.canCancel)
        assertFalse(reopened.canSpeedUpCancellation)

        whenever(lightningRepo.latestOnchainWalletSyncTimestamp()).thenReturn(SYNC_AFTER_SENT_SECS)
        nodeEvents.emit(Event.SyncCompleted(syncType = SyncType.ONCHAIN_WALLET, syncedBlockHeight = 100u))
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isCancellationUnheld)
    }

    @Test
    fun `retry refused because the original is already paid says so`() = test {
        val original = requestOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canRetryWithoutChecking)

        sut.retryOriginal(2uL) { _, _ -> Result.failure(OnchainOriginalPaidError()) }
        advanceUntilIdle()
        assertTrue(sut.uiState.value.isOriginalPaid)
        assertEquals(original, sut.uiState.value.recoveryAttempt)

        sut.retryOriginal(2uL) { _, _ -> Result.failure(OnchainSendBlockedError(original)) }
        advanceUntilIdle()
        assertFalse(sut.uiState.value.isOriginalPaid)
        assertNotNull(sut.uiState.value.recoveryError)
    }

    @Test
    fun `expired Shop payment cancel waits for its release to be final`() = test {
        val original = expiredShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any()))
            .thenReturn(Result.failure(OnchainSendAbandonSyncError()), Result.failure(OnchainSendAbandonPendingError()))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertEquals(original, sut.uiState.value.abandonableAttempt)

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()
        assertEquals(AbandonError.SyncUnavailable, sut.uiState.value.abandonError)
        assertTrue(sut.uiState.value.canAbandon)

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()
        val pending = sut.uiState.value
        assertEquals(AbandonError.ReleasePending, pending.abandonError)
        assertEquals(original, pending.recoveryAttempt)
        assertFalse(pending.isGuardReleased)
        assertFalse(pending.canAbandon)
        assertFalse(pending.canRetry)
        assertFalse(pending.canRetryWithoutChecking)
        assertTrue(pending.canCancel)

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()
        verify(paykitPaymentProofRepo, times(2)).abandonUnbroadcastOnchainPayment(any(), any(), any())
    }

    @Test
    fun `expired payment cancel is not offered beside a cancellation that may still confirm`() = test {
        val original = expiredShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        val confirming = OnchainInputSpend(cancelTxid, original.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(original)).thenReturn(Result.success(confirming))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertEquals(original, sut.uiState.value.abandonableAttempt)
        assertFalse(sut.uiState.value.canAbandon)

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()
        verify(paykitPaymentProofRepo, never()).abandonUnbroadcastOnchainPayment(any(), any(), any())

        val offered = SendPendingUiState(abandonableAttempt = original, recoveryAttempt = original)
        assertTrue(offered.canAbandon)
        assertFalse(offered.copy(hasUnknownCancellation = true).canAbandon)
    }

    @Test
    fun `the attempt's own updates during an expired cancel keep Payment Pending open`() = test {
        val original = expiredShopOriginal()
        var current: OnchainSendAttempt? = original
        whenever(lightningRepo.currentOnchainSendAttempt()).doSuspendableAnswer { current }
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any())).doSuspendableAnswer {
            // The cancel saves its approval and release, which the screen hears as attempt updates
            current = original.copy(releaseApproved = true, releaseTxid = "77".repeat(32))
            attemptUpdates.value += 1
            Result.failure<Unit>(OnchainSendAbandonPendingError())
        }
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isGuardReleased)
        assertEquals(AbandonError.ReleasePending, sut.uiState.value.abandonError)
        assertEquals(original, sut.uiState.value.recoveryAttempt)
    }

    @Test
    fun `a guard released during a failing expired cancel closes Payment Pending without an error`() = test {
        val original = expiredShopOriginal()
        var current: OnchainSendAttempt? = original
        whenever(lightningRepo.currentOnchainSendAttempt()).doSuspendableAnswer { current }
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any())).doSuspendableAnswer {
            // A final cancellation released the guard, so the cancel finds no attempt to approve
            current = null
            attemptUpdates.value += 1
            Result.failure<Unit>(OnchainSendBlockedError(original))
        }
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()

        assertTrue(sut.uiState.value.isGuardReleased)
        assertNull(sut.uiState.value.abandonError)
        assertNull(sut.uiState.value.recoveryAttempt)
        assertFalse(sut.uiState.value.isAbandoning)
    }

    @Test
    fun `reopened payment with a recorded release offers only Cancel payment`() = test {
        val released = releasedShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(released)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(released)).thenReturn(true)

        sut.initOnchain(released.txid, amount)
        advanceUntilIdle()

        val reopened = sut.uiState.value
        assertEquals(released, reopened.recoveryAttempt)
        assertEquals(released, reopened.abandonableAttempt)
        assertFalse(reopened.canRetry)
        assertFalse(reopened.canRetryWithoutChecking)
        assertFalse(reopened.canAbandon)
        assertTrue(reopened.canCancel)
        // The release may be in a mempool, so a Cancel payment must outbid its saved rate, not only the candidate's
        assertEquals(6uL, reopened.suggestedFeeRate(RecoveryAction.Cancel))
        val savedBeforeItsFee = SendPendingUiState(recoveryAttempt = released.copy(releaseFeeRateSatsPerVByte = null))
        assertEquals(2uL, savedBeforeItsFee.suggestedFeeRate(RecoveryAction.Cancel))
    }

    @Test
    fun `a Cancel payment below the release's fee suggests a rate that pays more`() = test {
        val released = releasedShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(released)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(released)).thenReturn(true)
        sut.initOnchain(released.txid, amount)
        advanceUntilIdle()

        sut.cancelOriginal(6uL) { _, _ ->
            Result.failure(OnchainSendCancelUnderbidsError(feeRate = 8uL, feeSats = 1_000uL))
        }
        advanceUntilIdle()

        val underbid = sut.uiState.value
        assertFalse(underbid.cancellationFailed)
        assertEquals(1_000uL, underbid.cancelFeeTooLowSats)
        assertFalse(underbid.isEarlierCancellationPending)
        assertEquals(9uL, underbid.suggestedFeeRate(RecoveryAction.Cancel))
        assertTrue(underbid.canCancel)
    }

    @Test
    fun `a Cancel payment that fails once the wallet knows the release reports it as pending`() = test {
        val released = releasedShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(released)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(released)).thenReturn(true)
        sut.initOnchain(released.txid, amount)
        advanceUntilIdle()
        // The saved release shows as sent though the wallet does not hold it yet
        assertEquals(released.releaseTxid, sut.uiState.value.cancellationTxid)
        assertTrue(sut.uiState.value.canCancel)
        // A sync added the release, which spends the original's coins, so no Cancel payment builds over them
        val release = OnchainInputSpend("77".repeat(32), released.originalInputs.orEmpty(), listOf("own"), false)
        whenever(lightningRepo.findOnchainSendCancellation(released)).thenReturn(Result.success(release))

        sut.cancelOriginal(6uL) { _, _ -> Result.failure(IllegalStateException("original inputs unavailable")) }
        advanceUntilIdle()

        val failed = sut.uiState.value
        assertTrue(failed.isEarlierCancellationPending)
        assertEquals(release.txid, failed.cancellationTxid)
        assertFalse(failed.canCancel)
        assertTrue(failed.canSpeedUpCancellation)
        assertFalse(failed.canRetry)
    }

    @Test
    fun `expired cancel refused after recording its release offers only Cancel payment`() = test {
        val original = expiredShopOriginal()
        var current: OnchainSendAttempt? = original
        whenever(lightningRepo.currentOnchainSendAttempt()).doSuspendableAnswer { current }
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any())).doSuspendableAnswer {
            // The release was saved before its broadcast, which a node then refused
            current = releasedShopOriginal()
            Result.failure<Unit>(OnchainSendAbandonRefusedError(requestOpen = false))
        }
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertTrue(sut.uiState.value.canRetry)

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()

        val refused = sut.uiState.value
        assertEquals(AbandonError.MayStillConfirm, refused.abandonError)
        assertFalse(refused.canRetry)
        assertFalse(refused.canRetryWithoutChecking)
        assertFalse(refused.canAbandon)
        assertTrue(refused.canCancel)
    }

    @Test
    fun `a pending release the wallet already knows shows as the cancellation`() = test {
        val original = expiredShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        val release = OnchainInputSpend("77".repeat(32), original.originalInputs.orEmpty(), listOf("own"), false)
        // The wallet learns of the release only after the screen opened
        whenever(lightningRepo.findOnchainSendCancellation(original))
            .thenReturn(Result.success(null), Result.success(release))
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any()))
            .thenReturn(Result.failure(OnchainSendAbandonPendingError()))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertNull(sut.uiState.value.cancellationTxid)

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()

        assertEquals(AbandonError.ReleasePending, sut.uiState.value.abandonError)
        assertEquals(release.txid, sut.uiState.value.cancellationTxid)
        assertFalse(sut.uiState.value.isCancellationFinal)
    }

    @Test
    fun `retry clears the expired cancel's error`() = test {
        val original = expiredShopOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any()))
            .thenReturn(Result.failure(OnchainSendAbandonSyncError()))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()
        assertEquals(AbandonError.SyncUnavailable, sut.uiState.value.abandonError)
        val retried = CompletableDeferred<Result<OnchainSendOutcome>>()

        sut.retryOriginal(2uL) { _, _ -> retried.await() }
        runCurrent()

        // The cancel's error is shown first, so left in place it would hide what the retry reports
        assertTrue(sut.uiState.value.isRecovering)
        assertNull(sut.uiState.value.abandonError)
        retried.complete(Result.failure(OnchainSendBlockedError(original)))
        advanceUntilIdle()
        assertNotNull(sut.uiState.value.recoveryError)
        assertNull(sut.uiState.value.abandonError)
    }

    @Test
    fun `failed Cancel payment after a sent release shows the cancel error`() = test {
        val original = expiredShopOriginal()
        val release = OnchainPreparedReceipt(
            "77".repeat(32),
            original.originalInputs.orEmpty(),
            "own",
            500uL,
            feeRateSatsPerVByte = 5uL,
            miningFeeSats = 700uL,
        )
        var current: OnchainSendAttempt? = original
        whenever(lightningRepo.currentOnchainSendAttempt()).doSuspendableAnswer { current }
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any())).doSuspendableAnswer {
            it.getArgument<suspend (OnchainPreparedReceipt) -> Unit>(2)(release)
            current = original.copy(releaseApproved = true, releaseTxid = release.txid)
            Result.failure<Unit>(OnchainSendAbandonPendingError())
        }
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()
        assertEquals(RecoveryApproval.Release(release), sut.uiState.value.recoveryApproval)
        sut.answerRecoveryApproval(true)
        advanceUntilIdle()
        assertEquals(AbandonError.ReleasePending, sut.uiState.value.abandonError)
        // The release may be in a mempool, so a Cancel payment must outbid it
        assertEquals(6uL, sut.uiState.value.suggestedFeeRate(RecoveryAction.Cancel))

        sut.cancelOriginal(6uL) { _, _ -> Result.failure(IllegalStateException("original inputs unavailable")) }
        advanceUntilIdle()

        val failed = sut.uiState.value
        assertNull(failed.abandonError)
        assertTrue(failed.cancellationFailed)
        assertEquals(original, failed.recoveryAttempt)
        assertFalse(failed.canRetry)
        assertTrue(failed.canCancel)
    }

    @Test
    fun `cancel appears once the request ends while Payment Pending stays open`() = test {
        val original = pendingOriginal().copy(
            requestId = PaykitPaymentRequestId("request", "payee"),
            orderId = "order",
            payerIdentity = "payer",
        )
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(false, true)
        sut.initOnchain(original.txid, amount)
        runCurrent()
        assertNull(sut.uiState.value.abandonableAttempt)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(original, sut.uiState.value.abandonableAttempt)
    }

    @Test
    fun `refused Blocktank funding offers cancel after one check while transfers are unavailable`() = test {
        val original = pendingOriginal().copy(isTransfer = true, orderId = "order")
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        sut.initOnchain(original.txid, amount)
        runCurrent()
        assertEquals(original, sut.uiState.value.abandonableAttempt)
    }

    @Test
    fun `retryable Blocktank funding is checked once and not polled`() = test {
        val original = pendingOriginal().copy(isTransfer = true, orderId = "order")
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(false)
        sut.initOnchain(original.txid, amount)
        runCurrent()
        advanceTimeBy(61.seconds)
        runCurrent()
        assertNull(sut.uiState.value.abandonableAttempt)
        org.mockito.kotlin.verify(paykitPaymentProofRepo, org.mockito.kotlin.times(1)).onchainRequestHasEnded(original)
    }

    @Test
    fun `a second cancel uses the attempt the first one saved`() = test {
        val original = expiredShopOriginal()
        val saved = original.copy(releaseApproved = true, releaseTxid = "77".repeat(32))
        // Read once when the screen opens, once by the cancellation watch, once by the cancel poll, then by the cancel.
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original, original, original, saved)
        whenever(paykitPaymentProofRepo.onchainRequestHasEnded(original)).thenReturn(true)
        whenever(paykitPaymentProofRepo.abandonUnbroadcastOnchainPayment(any(), any(), any()))
            .thenReturn(Result.failure(OnchainSendAbandonPendingError()))
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()

        sut.abandonExpired(requiresPin = false, useBiometrics = false)
        advanceUntilIdle()

        verify(paykitPaymentProofRepo).abandonUnbroadcastOnchainPayment(eq(saved), any(), any())
    }

    @Test
    fun `ordinary unknown payment offers no cancel`() = test {
        val original = pendingOriginal()
        whenever(lightningRepo.currentOnchainSendAttempt()).thenReturn(original)
        sut.initOnchain(original.txid, amount)
        advanceUntilIdle()
        assertNull(sut.uiState.value.abandonableAttempt)
    }

    private fun pendingOriginal() = OnchainSendAttempt(
        walletId = WalletScope.default, attemptId = "original", requestId = null,
        orderId = null, address = "original-address", amountSats = 1_000uL, isMaxAmount = false,
        feeRateSatsPerVByte = 1uL, isTransfer = false, channelId = null, tags = emptyList(),
        txid = "ab".repeat(32), evidence = OnchainSendEvidence.Unknown,
        originalInputs = listOf(OnchainSendInput("11".repeat(32), 0u)),
        candidateTxids = listOf("ab".repeat(32)),
    )

    private val cancelTxid = "ef".repeat(32)

    private companion object {
        const val SENT_AT_MILLIS = 1_800_000_000_000L
        const val SYNC_BEFORE_SENT_SECS = 1_799_999_999uL
        const val SYNC_AFTER_SENT_SECS = 1_800_000_001uL
    }

    // Without a payer identity it cannot be cancelled as an expired Shop payment, so nothing polls its request
    private fun requestOriginal() = pendingOriginal().copy(requestId = PaykitPaymentRequestId("request", "payee"))

    private fun expiredShopOriginal() = pendingOriginal().copy(
        requestId = PaykitPaymentRequestId("request", "payee"),
        orderId = "order",
        payerIdentity = "payer",
    )

    private fun releasedShopOriginal() = expiredShopOriginal().copy(
        releaseApproved = true,
        releaseTxid = "77".repeat(32),
        releaseFeeRateSatsPerVByte = 5uL,
        releaseMiningFeeSats = 700uL,
    )

    @Test
    fun `onchain activity enables original wallet Details without resolving acceptance`() = test {
        val txid = "ab".repeat(32)
        val onchainActivity = mock<OnchainActivity> { on { id } doReturn "queued-local-activity" }
        val activity = mock<Activity.Onchain> { on { v1 } doReturn onchainActivity }
        whenever(
            activityRepo.findActivityByPaymentId(
                txid,
                ActivityFilter.ONCHAIN,
                PaymentType.SENT,
                true,
                "original-wallet"
            )
        )
            .thenReturn(Result.success(activity))
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success(txid, amountWithFeeSats = 9_999L))

        sut.initOnchain(txid, amount, "original-wallet")
        advanceUntilIdle()

        assertEquals("queued-local-activity", sut.uiState.value.activityId)
        assertEquals(amount, sut.uiState.value.amount)
        assertNull(sut.uiState.value.resolution)
        assertEquals(false, pendingPaymentRepo.isActive(txid))
    }

    @Test
    fun `missing onchain txid never uses request identifier as Details or acceptance`() = test {
        sut.initOnchain("request-id", amount)
        advanceUntilIdle()
        assertNull(sut.uiState.value.activityId)
        assertNull(sut.uiState.value.resolution)
        org.mockito.kotlin.verifyNoInteractions(activityRepo)
    }

    @Test
    fun `init sets amount in uiState`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        assertEquals(amount, sut.uiState.value.amount)
    }

    @Test
    fun `init sets activeHash on repo`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        assertEquals(true, pendingPaymentRepo.isActive(hash))
    }

    @Test
    fun `init applies an already resolved hash`() = test {
        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success(hash, amountWithFeeSats = 510L))

        sut.init(hash, amount)
        advanceUntilIdle()

        val resolution = sut.uiState.value.resolution
        assertIs<PendingPaymentResolution.Success>(resolution)
        assertEquals(510L, resolution.amountWithFeeSats)
        assertNull(pendingPaymentRepo.consumeResolution(hash))
    }

    @Test
    fun `init is idempotent`() = test {
        sut.init(hash, amount)
        sut.init(hash, 9999L)
        advanceUntilIdle()

        assertEquals(amount, sut.uiState.value.amount)
    }

    @Test
    fun `findActivity sets activityId`() = test {
        val activityId = "activity_id_123"
        val activityV1 = mock<LightningActivity> { on { id } doReturn activityId }
        val activity = mock<Activity.Lightning> { on { v1 } doReturn activityV1 }
        whenever(activityRepo.findActivityByPaymentId(any(), any(), any(), any())).thenReturn(Result.success(activity))

        sut.init(hash, amount)
        advanceUntilIdle()

        assertEquals(activityId, sut.uiState.value.activityId)
    }

    @Test
    fun `findActivity failure leaves activityId null`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        assertNull(sut.uiState.value.activityId)
    }

    @Test
    fun `observeResolution Success updates uiState`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success(hash))
        advanceUntilIdle()

        val resolution = sut.uiState.value.resolution
        assertIs<PendingPaymentResolution.Success>(resolution)
        assertEquals(hash, resolution.paymentHash)
        assertNull(pendingPaymentRepo.consumeResolution(hash))
    }

    @Test
    fun `observeResolution Failure updates uiState`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Failure(hash))
        advanceUntilIdle()

        val resolution = sut.uiState.value.resolution
        assertIs<PendingPaymentResolution.Failure>(resolution)
    }

    @Test
    fun `observeResolution ignores other hashes`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track("other_hash")
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success("other_hash"))
        advanceUntilIdle()

        assertNull(sut.uiState.value.resolution)
    }

    @Test
    fun `onResolutionHandled clears resolution`() = test {
        sut.init(hash, amount)
        advanceUntilIdle()

        pendingPaymentRepo.track(hash)
        pendingPaymentRepo.resolve(PendingPaymentResolution.Success(hash))
        advanceUntilIdle()

        sut.onResolutionHandled()

        assertNull(sut.uiState.value.resolution)
    }

    private fun createViewModel() = SendPendingViewModel(
        pendingPaymentRepo = pendingPaymentRepo,
        activityRepo = activityRepo,
        lightningRepo = lightningRepo,
        paykitPaymentProofRepo = paykitPaymentProofRepo,
    )
}
