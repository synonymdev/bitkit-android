package to.bitkit.ui.screens.wallets.activity

import app.cash.turbine.test
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostTransactionViewModelSimplifiedTest : BaseUnitTest() {

    private lateinit var sut: BoostTransactionViewModel
    private val lightningRepo: LightningRepo = mock()
    private val walletRepo: WalletRepo = mock()
    private val activityRepo: ActivityRepo = mock()

    // Test data
    private val mockTxId = "test_txid_123"
    private val mockNewTxId = "new_txid_456"
    private val mockAddress = "bc1qtest123"
    private val testFeeRate = 10UL
    private val testTotalFee = 1000UL
    private val testValue = 50000UL

    private val mockOnchainActivity = OnchainActivity(
        id = "test_id",
        txType = PaymentType.SENT,
        txId = mockTxId,
        value = testValue,
        fee = 500UL,
        feeRate = 10UL,
        address = mockAddress,
        confirmed = false,
        timestamp = 1234567890UL,
        isBoosted = false,
        isTransfer = false,
        doesExist = true,
        confirmTimestamp = null,
        channelId = null,
        transferTxId = null,
        createdAt = null,
        updatedAt = null
    )

    private val mockActivitySent = Activity.Onchain(v1 = mockOnchainActivity)

    @Before
    fun setUp() {
        sut = BoostTransactionViewModel(
            lightningRepo = lightningRepo,
            walletRepo = walletRepo,
            activityRepo = activityRepo
        )
    }

    @Test
    fun `initial ui state should have default values`() = runTest {
        sut.uiState.test {
            val initialState = awaitItem()
            assertEquals(0UL, initialState.totalFeeSats)
            assertEquals(0UL, initialState.feeRate)
            assertTrue(initialState.isDefaultMode)
            assertTrue(initialState.decreaseEnabled)
            assertTrue(initialState.increaseEnabled)
            assertFalse(initialState.boosting)
            assertFalse(initialState.loading)
        }
    }

    @Test
    fun `setupActivity should set loading state initially`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any()))
            .thenReturn(Result.success(testFeeRate))
        whenever(lightningRepo.calculateTotalFee(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(testTotalFee))

        sut.uiState.test {
            awaitItem() // initial state
            sut.setupActivity(mockActivitySent)

            val loadingState = awaitItem()
            assertTrue(loadingState.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setupActivity should call correct repository methods for sent transaction`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(TransactionSpeed.Fast))
            .thenReturn(Result.success(testFeeRate))
        whenever(walletRepo.getOnchainAddress())
            .thenReturn(mockAddress)
        whenever(lightningRepo.calculateTotalFee(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(testTotalFee))

        sut.setupActivity(mockActivitySent)

        verify(lightningRepo).getFeeRateForSpeed(TransactionSpeed.Fast)
        verify(lightningRepo).calculateTotalFee(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `setupActivity should call CPFP method for received transaction`() = runTest {
        val receivedActivity = Activity.Onchain(
            v1 = mockOnchainActivity.copy(txType = PaymentType.RECEIVED)
        )

        whenever(lightningRepo.calculateCpfpFeeRate(eq(mockTxId)))
            .thenReturn(Result.success(testFeeRate))

        sut.setupActivity(receivedActivity)

        verify(lightningRepo).calculateCpfpFeeRate(eq(mockTxId))
        verify(lightningRepo, never()).getFeeRateForSpeed(any())
    }

    @Test
    fun `onClickEdit should switch to custom mode`() = runTest {
        sut.uiState.test {
            awaitItem() // initial state
            sut.onClickEdit()

            val updatedState = awaitItem()
            assertFalse(updatedState.isDefaultMode)
        }
    }

    @Test
    fun `onConfirmBoost should handle null activity gracefully`() = runTest {
        sut.boostTransactionEffect.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
    }

    @Test
    fun `onChangeAmount should emit OnMaxFee when at maximum rate`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any()))
            .thenReturn(Result.success(100UL)) // MAX_FEE_RATE
        whenever(walletRepo.getOnchainAddress())
            .thenReturn(mockAddress)
        whenever(lightningRepo.calculateTotalFee(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(testTotalFee))

        sut.setupActivity(mockActivitySent)

        sut.boostTransactionEffect.test {
            sut.onChangeAmount(increase = true)
            assertEquals(BoostTransactionEffects.OnMaxFee, awaitItem())
        }
    }

    @Test
    fun `onChangeAmount should emit OnMinFee when at minimum rate`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any()))
            .thenReturn(Result.success(1UL)) // MIN_FEE_RATE
        whenever(walletRepo.getOnchainAddress())
            .thenReturn(mockAddress)
        whenever(lightningRepo.calculateTotalFee(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(testTotalFee))

        sut.setupActivity(mockActivitySent)

        sut.boostTransactionEffect.test {
            sut.onChangeAmount(increase = false)
            assertEquals(BoostTransactionEffects.OnMinFee, awaitItem())
        }
    }

    @Test
    fun `setupActivity failure should emit OnBoostFailed`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any()))
            .thenReturn(Result.failure(Exception("Fee estimation failed")))

        sut.boostTransactionEffect.test {
            sut.setupActivity(mockActivitySent)
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
    }

    @Test
    fun `successful CPFP boost should call correct repository methods`() = runTest {
        val receivedActivity = Activity.Onchain(
            v1 = mockOnchainActivity.copy(txType = PaymentType.RECEIVED)
        )

        whenever(lightningRepo.calculateCpfpFeeRate(any()))
            .thenReturn(Result.success(testFeeRate))
        whenever(lightningRepo.calculateTotalFee(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(testTotalFee))
        whenever(walletRepo.getOnchainAddress())
            .thenReturn(mockAddress)
        whenever(lightningRepo.accelerateByCpfp(eq(mockTxId), any(), eq(mockAddress)))
            .thenReturn(Result.success(mockNewTxId))

        val newActivity = mockOnchainActivity.copy(
            txType = PaymentType.RECEIVED,
            txId = mockNewTxId,
            isBoosted = true
        )
        whenever(activityRepo.findActivityByPaymentId(any(), any(), any()))
            .thenReturn(Result.success(Activity.Onchain(v1 = newActivity)))
        whenever(activityRepo.updateActivity(any(), any()))
            .thenReturn(Result.success(Unit))

        sut.setupActivity(receivedActivity)

        sut.boostTransactionEffect.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }

        verify(lightningRepo).accelerateByCpfp(mockTxId, testFeeRate.toUInt(), mockAddress)
        verify(activityRepo).updateActivity(any(), any())
        verify(activityRepo, never()).deleteActivity(any())
    }
}
