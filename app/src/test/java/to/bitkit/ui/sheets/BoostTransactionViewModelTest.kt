package to.bitkit.ui.sheets

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOnchain
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import to.bitkit.R
import to.bitkit.ext.create
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.sheets.BoostTransactionViewModel.Companion.MAX_FEE_RATE
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostTransactionViewModelTest : BaseUnitTest() {
    private lateinit var sut: BoostTransactionViewModel

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()

    private val onchain = mock<IBtInfoOnchain>()
    private val mockBtInfo = mock<IBtInfo>()
    private val feeRates = FeeRates(fast = 20u, mid = 10u, slow = 5u)
    private val blocktankState = MutableStateFlow(BlocktankState(info = mockBtInfo))

    // Test data
    private val mockTxId = "test_txid_123"
    private val newTxId = "new_txid_456"
    private val address = "bc1rt1test123"
    private val feeRate = 10UL
    private val totalFee = 1000UL
    private val testValue = 50000UL

    private val onchainActivity = OnchainActivity.create(
        id = "test_id",
        txType = PaymentType.SENT,
        txId = mockTxId,
        value = testValue,
        fee = 500UL,
        address = address,
        timestamp = 1234567890UL,
        feeRate = 10UL,
    )

    private val activitySent = Activity.Onchain(onchainActivity)

    private val fastFeeTime = "±10m"
    private val normalFeeTime = "±20m"
    private val flowFeeTime = "±1h"
    private val minFeeTime = "+2h"

    @Before
    fun setUp() = runBlocking {
        whenever(context.getString(R.string.fee__fast__shortDescription)).thenReturn(fastFeeTime)
        whenever(context.getString(R.string.fee__normal__shortDescription)).thenReturn(normalFeeTime)
        whenever(context.getString(R.string.fee__slow__shortDescription)).thenReturn(flowFeeTime)
        whenever(context.getString(R.string.fee__minimum__shortDescription)).thenReturn(minFeeTime)
        whenever(onchain.feeRates).thenReturn(feeRates)
        whenever(mockBtInfo.onchain).thenReturn(onchain)
        whenever(blocktankRepo.blocktankState).thenReturn(blocktankState)
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(emptyList()))
        whenever(lightningRepo.syncAsync()).thenReturn(Job())

        sut = BoostTransactionViewModel(
            context = context,
            lightningRepo = lightningRepo,
            walletRepo = walletRepo,
            activityRepo = activityRepo,
            blocktankRepo = blocktankRepo,
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
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(feeRate))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.uiState.test {
            awaitItem() // initial state
            sut.setupActivity(activitySent)

            val loadingState = awaitItem()
            assertTrue(loadingState.loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setupActivity should call correct repository methods for sent transaction`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(feeRate))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.setupActivity(activitySent)

        verify(lightningRepo).getFeeRateForSpeed(eq(TransactionSpeed.Fast), anyOrNull())
        verify(lightningRepo).calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `setupActivity should call CPFP method for received transaction`() = runTest {
        val receivedActivity = Activity.Onchain(onchainActivity.copy(txType = PaymentType.RECEIVED))

        whenever(lightningRepo.calculateCpfpFeeRate(eq(mockTxId)))
            .thenReturn(Result.success(feeRate))

        sut.setupActivity(receivedActivity)

        verify(lightningRepo).calculateCpfpFeeRate(eq(mockTxId))
        verify(lightningRepo, never()).getFeeRateForSpeed(any(), anyOrNull())
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
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(MAX_FEE_RATE))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.setupActivity(activitySent)

        sut.boostTransactionEffect.test {
            sut.onChangeAmount(increase = true)
            assertEquals(BoostTransactionEffects.OnMaxFee, awaitItem())
        }
    }

    @Test
    fun `onChangeAmount should emit OnMinFee when at minimum rate`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(1UL)) // MIN_FEE_RATE
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.setupActivity(activitySent)

        sut.boostTransactionEffect.test {
            sut.onChangeAmount(increase = false)
            assertEquals(BoostTransactionEffects.OnMinFee, awaitItem())
        }
    }

    @Test
    fun `setupActivity failure should emit OnBoostFailed`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.failure(Exception("error")))

        sut.boostTransactionEffect.test {
            sut.setupActivity(activitySent)
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
    }

    @Test
    fun `successful CPFP boost should call correct repository methods`() = runTest {
        val receivedActivity = Activity.Onchain(onchainActivity.copy(txType = PaymentType.RECEIVED))

        whenever(lightningRepo.calculateCpfpFeeRate(any())).thenReturn(Result.success(feeRate))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))
        whenever(walletRepo.getOnchainAddress()).thenReturn(address)
        whenever(lightningRepo.accelerateByCpfp(any(), any(), any())).thenReturn(Result.success(newTxId))

        val newActivity = onchainActivity.copy(txType = PaymentType.SENT, txId = newTxId, isBoosted = true)

        whenever(activityRepo.findActivityByPaymentId(any(), any(), any(), any()))
            .thenReturn(Result.success(Activity.Onchain(newActivity)))

        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.success(Unit))

        sut.setupActivity(receivedActivity)

        sut.boostTransactionEffect.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }

        verify(lightningRepo).accelerateByCpfp(any(), any(), any())
        verify(lightningRepo).syncAsync()
        verify(activityRepo).updateActivity(any(), any(), any())
        verify(activityRepo, never()).deleteActivity(any())
    }

    // region estimateTime dynamic tier tests

    @Test
    fun `estimateTime shows fast description when fee rate at or above fast threshold`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(25UL))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.uiState.test {
            awaitItem()
            sut.setupActivity(activitySent)
            awaitItem()
            val state = awaitItem()
            assertEquals(fastFeeTime, state.estimateTime)
        }
    }

    @Test
    fun `estimateTime shows normal description when fee rate between mid and fast`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(15UL))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.uiState.test {
            awaitItem()
            sut.setupActivity(activitySent)
            awaitItem()
            val state = awaitItem()
            assertEquals(normalFeeTime, state.estimateTime)
        }
    }

    @Test
    fun `estimateTime shows slow description when fee rate between slow and mid`() = runTest {
        val lowFeeActivity = Activity.Onchain(onchainActivity.copy(feeRate = 1UL))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(7UL))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.uiState.test {
            awaitItem() // initial state
            sut.setupActivity(lowFeeActivity)
            awaitItem() // loading state
            val state = awaitItem()
            assertEquals(flowFeeTime, state.estimateTime)
        }
    }

    @Test
    fun `estimateTime shows minimum description when fee rate below slow threshold`() = runTest {
        val lowFeeActivity = Activity.Onchain(onchainActivity.copy(feeRate = 1UL))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(3UL))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(totalFee))

        sut.uiState.test {
            awaitItem() // initial state
            sut.setupActivity(lowFeeActivity)
            awaitItem() // loading state
            val state = awaitItem()
            assertEquals(minFeeTime, state.estimateTime)
        }
    }

    // endregion
}
