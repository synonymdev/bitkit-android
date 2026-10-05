package to.bitkit.ui.screens.wallets.send

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.WalletScope
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendEvidence
import to.bitkit.repositories.OnchainSendInput
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SendPendingViewModelTest : BaseUnitTest() {

    private val pendingPaymentRepo = PendingPaymentRepo()
    private val activityRepo: ActivityRepo = mock()
    private val lightningRepo: LightningRepo = mock()

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
        sut = createViewModel()
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
    )
}
