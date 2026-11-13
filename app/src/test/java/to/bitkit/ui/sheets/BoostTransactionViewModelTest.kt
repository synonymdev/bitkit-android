package to.bitkit.ui.sheets

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOnchain
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.RbfBoostRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import to.bitkit.ui.screens.wallets.activity.shouldEnableBoostButton
import to.bitkit.ui.sheets.BoostTransactionViewModel.Companion.MAX_FEE_RATE
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class BoostTransactionViewModelTest : BaseUnitTest() {
    private lateinit var sut: BoostTransactionViewModel
    private lateinit var rbfBoostRepo: RbfBoostRepo
    private val connectionRecovery = connectionRecoveryFixture(mock<SettingsStore>())

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
        walletId = "wallet0",
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

    private val fastFeeTime = "±10 minutes"
    private val normalFeeTime = "±20 minutes"
    private val slowFeeTime = "±1 hour"
    private val minFeeTime = "+2 hours"

    @Before
    fun setUp() = runBlocking {
        whenever(context.getString(R.string.fee__fast__duration)).thenReturn(fastFeeTime)
        whenever(context.getString(R.string.fee__normal__duration)).thenReturn(normalFeeTime)
        whenever(context.getString(R.string.fee__slow__duration)).thenReturn(slowFeeTime)
        whenever(context.getString(R.string.fee__minimum__duration)).thenReturn(minFeeTime)
        whenever(onchain.feeRates).thenReturn(feeRates)
        whenever(mockBtInfo.onchain).thenReturn(onchain)
        whenever(blocktankRepo.blocktankState).thenReturn(blocktankState)
        whenever(lightningRepo.listSpendableOutputs()).thenReturn(Result.success(emptyList()))
        whenever(lightningRepo.syncAsync()).thenReturn(Job())

        rbfBoostRepo = RbfBoostRepo(
            dispatcher = testDispatcher,
            lightningRepo = lightningRepo,
            activityRepo = activityRepo,
            connectionRecovery = connectionRecovery,
            timeSource = TimeSource.Monotonic,
        )
        sut = createViewModel()
    }

    private fun createViewModel() = BoostTransactionViewModel(
        context = context,
        lightningRepo = lightningRepo,
        walletRepo = walletRepo,
        activityRepo = activityRepo,
        blocktankRepo = blocktankRepo,
        rbfBoostRepo = rbfBoostRepo,
    )

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
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
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
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(totalFee))

        sut.setupActivity(activitySent)

        verify(lightningRepo).getFeeRateForSpeed(eq(TransactionSpeed.Fast), anyOrNull())
        verify(lightningRepo).calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any())
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
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
    }

    @Test
    fun `onChangeAmount should emit OnMaxFee when at maximum rate`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(MAX_FEE_RATE))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(totalFee))

        sut.setupActivity(activitySent)

        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onChangeAmount(increase = true)
            assertEquals(BoostTransactionEffects.OnMaxFee, awaitItem())
        }
    }

    @Test
    fun `onChangeAmount should emit OnMinFee when at minimum rate`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(1UL)) // MIN_FEE_RATE
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(totalFee))

        sut.setupActivity(activitySent)

        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onChangeAmount(increase = false)
            assertEquals(BoostTransactionEffects.OnMinFee, awaitItem())
        }
    }

    @Test
    fun `setupActivity failure should emit OnBoostFailed`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.failure(Exception("error")))

        sut.boostTransactionEffect.map { it.effect }.test {
            sut.setupActivity(activitySent)
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
    }

    @Test
    fun `successful CPFP boost should call correct repository methods`() = runTest {
        val receivedActivity = Activity.Onchain(onchainActivity.copy(txType = PaymentType.RECEIVED))

        whenever(lightningRepo.calculateCpfpFeeRate(any())).thenReturn(Result.success(feeRate))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(totalFee))
        whenever(walletRepo.getOnchainAddress()).thenReturn(address)
        whenever(lightningRepo.accelerateByCpfp(any(), any(), any())).thenReturn(Result.success(newTxId))

        val newActivity = onchainActivity.copy(txType = PaymentType.SENT, txId = newTxId, isBoosted = true)

        whenever(activityRepo.findActivityByPaymentId(any(), any(), any(), any()))
            .thenReturn(Result.success(Activity.Onchain(newActivity)))

        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.success(Unit))

        sut.setupActivity(receivedActivity)

        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }

        verify(lightningRepo).accelerateByCpfp(any(), any(), any())
        verify(lightningRepo).syncAsync()
        verify(activityRepo).updateActivity(any(), any(), any())
        verify(activityRepo, never()).deleteActivity(any(), any())
    }

    @Test
    fun `RBF success waits for publication and never overwrites the original fee`() = runTest {
        prepareRbf()
        val published = CompletableDeferred<Unit>()
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).doSuspendableAnswer {
            published.await()
            Result.success(Unit)
        }
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            sut.onConfirmBoost()
            assertTrue(sut.uiState.value.boosting)
            expectNoEvents()
            advanceTimeBy(RbfBoostRepo.PENDING_FEEDBACK_DELAY.inWholeMilliseconds - 1)
            assertFalse(sut.uiState.value.publicationSlow)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(sut.uiState.value.publicationSlow)
            assertTrue(sut.uiState.value.boosting)
            expectNoEvents()
            published.complete(Unit)
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
            assertFalse(sut.uiState.value.boosting)
        }
        verify(lightningRepo, times(1)).bumpFeeByRbf(mockTxId, feeRate.coerceAtLeast(12u))
        verify(activityRepo, never()).updateActivity(any(), any(), any())
    }

    @Test
    fun `RBF publication failure does not report success or broadcast again`() = runTest {
        prepareRbf()
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId))
            .thenReturn(Result.failure(IllegalStateException("not published")))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostPending, awaitItem())
            expectNoEvents()
        }
        verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
        verify(lightningRepo).syncAsync()
        assertFalse(sut.uiState.value.boosting)
    }

    @Test
    fun `reopening the original while broadcast is pending shares the same broadcast`() = runTest {
        prepareRbf()
        val broadcast = CompletableDeferred<Unit>()
        whenever(lightningRepo.bumpFeeByRbf(any(), any())).doSuspendableAnswer {
            broadcast.await()
            Result.success(newTxId)
        }
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).thenReturn(Result.success(Unit))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            sut.setupActivity(activitySent)
            sut.onConfirmBoost()
            broadcast.complete(Unit)
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
            expectNoEvents()
        }
        verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
    }

    @Test
    fun `cancelled broadcast can be explicitly retried after reopening`() = runTest {
        prepareRbf()
        var attempts = 0
        whenever(lightningRepo.bumpFeeByRbf(any(), any())).doSuspendableAnswer {
            if (attempts++ == 0) throw CancellationException("node stopped before broadcast")
            Result.success(newTxId)
        }
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).thenReturn(Result.success(Unit))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            expectNoEvents()
            assertEquals(1, attempts)
            sut.setupActivity(activitySent)
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `retry after publication failure reuses the broadcast replacement`() = runTest {
        prepareRbf()
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId))
            .thenReturn(Result.failure(IllegalStateException("storage failed")), Result.success(Unit))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostPending, awaitItem())
            expectNoEvents()
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }
        verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
        verify(activityRepo, times(2)).publishRbfBoost(mockTxId, newTxId)
    }

    @Test
    fun `publication retry retains the quote across detail ViewModels and shares in flight work`() = runTest {
        prepareRbf()
        val approvedRate = sut.uiState.value.feeRate
        val approvedFee = sut.uiState.value.totalFeeSats
        val retryPublication = CompletableDeferred<Unit>()
        var publications = 0
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).doSuspendableAnswer {
            if (++publications == 1) {
                Result.failure(IllegalStateException("sync failed"))
            } else {
                retryPublication.await()
                Result.success(Unit)
            }
        }

        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostPending, awaitItem())
            sut.clearActivity()
            whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(25uL))
            val reopened = createViewModel()
            reopened.setupActivity(activitySent)
            reopened.onClickEdit()
            reopened.onChangeAmount(increase = true)
            reopened.onClickUseSuggestedFee()
            assertEquals(approvedRate, reopened.uiState.value.feeRate)
            assertEquals(approvedFee, reopened.uiState.value.totalFeeSats)
            assertTrue(reopened.uiState.value.feeLocked)
            reopened.onConfirmBoost()
            reopened.clearActivity()

            val reattached = createViewModel()
            reattached.boostTransactionEffect.map { it.effect }.test {
                reattached.setupActivity(activitySent)
                reattached.onConfirmBoost()
                assertTrue(reattached.uiState.value.boosting)
                assertEquals(2, publications)
                retryPublication.complete(Unit)
                assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
            }
            expectNoEvents()
        }
        verify(lightningRepo, times(1)).bumpFeeByRbf(mockTxId, approvedRate)
        verify(activityRepo, times(2)).publishRbfBoost(mockTxId, newTxId)
        verify(activityRepo, times(2)).recordRbfBoost(onchainActivity, newTxId, approvedRate)
    }

    @Test
    fun `wallet change drains suspended RBF and discards the old attempt`() = runTest {
        prepareRbf()
        val broadcast = CompletableDeferred<String>()
        whenever(lightningRepo.bumpFeeByRbf(any(), any())).doSuspendableAnswer {
            Result.success(broadcast.await())
        }

        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertTrue(sut.uiState.value.feeLocked)
            connectionRecovery.withIdentityChange {}
            broadcast.complete(newTxId)
            runCurrent()
            expectNoEvents()
            sut.onConfirmBoost()
            verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
            verify(activityRepo, never()).recordRbfBoost(any(), any(), any())
            verify(activityRepo, never()).publishRbfBoost(any(), any())
        }
        val reopened = createViewModel()
        reopened.setupActivity(activitySent)
        assertFalse(reopened.uiState.value.feeLocked)
    }

    @Test
    fun `RBF success records the quoted fee rate before publishing the replacement`() = runTest {
        prepareRbf()
        val quotedRate = sut.uiState.value.feeRate
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).thenReturn(Result.success(Unit))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }
        inOrder(activityRepo) {
            verify(activityRepo).recordRbfBoost(onchainActivity, newTxId, quotedRate)
            verify(activityRepo).publishRbfBoost(mockTxId, newTxId)
        }
        verify(activityRepo, never()).updateActivity(any(), any(), any())
    }

    @Test
    fun `failed fee recording leaves the exact replacement available from activity detail`() = runTest {
        prepareRbf()
        whenever(activityRepo.recordRbfBoost(any(), any(), any()))
            .thenReturn(Result.failure(AppError(message = "storage unavailable")), Result.success(Unit))
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).thenReturn(Result.success(Unit))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostPending, awaitItem())
            verify(activityRepo, never()).publishRbfBoost(any(), any())
            sut.clearActivity()
            val original = Activity.Onchain(onchainActivity.copy(doesExist = false))
            val replacement = Activity.Onchain(
                onchainActivity.copy(
                    id = "replacement",
                    txId = newTxId,
                    isBoosted = true,
                    confirmed = true,
                    boostTxIds = listOf(mockTxId),
                )
            )
            val otherConflict = Activity.Onchain(replacement.v1.copy(txId = "different-replacement"))
            for (item in listOf(original, replacement)) {
                val pending = rbfBoostRepo.observePending(item.v1).first()
                assertTrue(shouldEnableBoostButton(item, false, false, persistentMapOf(), pending))
            }
            assertFalse(rbfBoostRepo.observePending(otherConflict.v1).first())
            assertFalse(shouldEnableBoostButton(otherConflict, false, false, persistentMapOf(), false))

            val reopened = createViewModel()
            reopened.boostTransactionEffect.map { it.effect }.test {
                reopened.setupActivity(replacement)
                assertTrue(reopened.uiState.value.feeLocked)
                reopened.onConfirmBoost()
                assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
            }
            // A pending click can race with publication before the list renders the linked row.
            val staleRow = replacement.copy(v1 = replacement.v1.copy(confirmed = false, isBoosted = false))
            val staleDetail = createViewModel()
            staleDetail.boostTransactionEffect.map { it.effect }.test {
                staleDetail.setupActivity(staleRow)
                assertTrue(staleDetail.uiState.value.feeLocked)
                staleDetail.onConfirmBoost()
                assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
            }
        }
        verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
        verify(activityRepo).publishRbfBoost(mockTxId, newTxId)
    }

    @Test
    fun `a queued boost effect cannot navigate a dismissed or rebound sheet`() = runTest {
        prepareRbf()
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).thenReturn(Result.success(Unit))
        sut.boostTransactionEffect.test {
            sut.onConfirmBoost()
            val event = awaitItem()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, event.effect)
            assertTrue(sut.canDeliver(event, activitySent))
            sut.clearActivity()
            assertFalse(sut.canDeliver(event, activitySent))
            sut.setupActivity(activitySent)
            assertFalse(sut.canDeliver(event, activitySent))
            expectNoEvents()
        }
    }

    @Test
    fun `wallet change cancels pending metadata and prevents publication`() = runTest {
        prepareRbf()
        val recording = CompletableDeferred<Result<Unit>>()
        whenever(activityRepo.recordRbfBoost(any(), any(), any())).doSuspendableAnswer { recording.await() }
        sut.boostTransactionEffect.test {
            sut.onConfirmBoost()
            verify(activityRepo).recordRbfBoost(eq(onchainActivity), eq(newTxId), any())
            connectionRecovery.withIdentityChange {}
            recording.complete(Result.success(Unit))
            runCurrent()
            expectNoEvents()
        }
        verify(activityRepo, never()).publishRbfBoost(any(), any())
        val reopened = createViewModel()
        reopened.setupActivity(activitySent)
        assertFalse(reopened.uiState.value.feeLocked)
    }

    @Test
    fun `failed RBF broadcast records no fee rate`() = runTest {
        prepareRbf()
        whenever(lightningRepo.bumpFeeByRbf(any(), any()))
            .thenReturn(Result.failure(AppError(message = "broadcast rejected")))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
        verify(activityRepo, never()).recordRbfBoost(any(), any(), any())
        verify(activityRepo, never()).publishRbfBoost(any(), any())
    }

    @Test
    fun `stale activity cannot authorize a fresh boost after it confirms`() = runTest {
        prepareRbf()
        whenever(activityRepo.getOnchainActivityByTxId(mockTxId, onchainActivity.walletId))
            .thenReturn(onchainActivity.copy(confirmed = true))
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
        }
        verify(lightningRepo, never()).bumpFeeByRbf(any(), any())
    }

    @Test
    fun `late fee calculation cannot change the quote of an existing broadcast`() = runTest {
        prepareRbf()
        val estimated = CompletableDeferred<ULong>()
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .doSuspendableAnswer { Result.success(estimated.await()) }
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId))
            .thenReturn(Result.failure(IllegalStateException("sync failed")))
        sut.onChangeAmount(increase = true)
        val approved = sut.uiState.value
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostPending, awaitItem())
            estimated.complete(2_000uL)
            assertEquals(approved.feeRate, sut.uiState.value.feeRate)
            assertEquals(approved.totalFeeSats, sut.uiState.value.totalFeeSats)
            assertTrue(sut.uiState.value.feeLocked)
            expectNoEvents()
        }
        verify(lightningRepo).bumpFeeByRbf(mockTxId, approved.feeRate)
    }

    @Test
    fun `definite broadcast failure unlocks fee changes for a new attempt`() = runTest {
        prepareRbf()
        whenever(lightningRepo.bumpFeeByRbf(any(), any()))
            .thenReturn(Result.failure(IllegalStateException("broadcast rejected")), Result.success(newTxId))
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).thenReturn(Result.success(Unit))
        val firstRate = sut.uiState.value.feeRate
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostFailed, awaitItem())
            assertFalse(sut.uiState.value.feeLocked)
            sut.onClickEdit()
            sut.onChangeAmount(increase = true)
            assertEquals(firstRate + 1uL, sut.uiState.value.feeRate)
            sut.onConfirmBoost()
            assertEquals(BoostTransactionEffects.OnBoostSuccess, awaitItem())
        }
        verify(lightningRepo).bumpFeeByRbf(mockTxId, firstRate)
        verify(lightningRepo).bumpFeeByRbf(mockTxId, firstRate + 1uL)
    }

    @Test
    fun `reopening without confirming again unlocks when the shared broadcast fails or cancels`() = runTest {
        for (cancelled in listOf(false, true)) {
            prepareRbf()
            val broadcast = CompletableDeferred<Result<String>>()
            whenever(lightningRepo.bumpFeeByRbf(any(), any())).doSuspendableAnswer { broadcast.await() }
            sut.boostTransactionEffect.map { it.effect }.test {
                sut.onConfirmBoost()
                sut.setupActivity(activitySent)
                assertTrue(sut.uiState.value.feeLocked)
                if (cancelled) {
                    broadcast.cancel()
                } else {
                    broadcast.complete(Result.failure(IllegalStateException("broadcast rejected")))
                }
                assertFalse(sut.uiState.value.feeLocked)
                assertFalse(sut.uiState.value.boosting)
                val previousRate = sut.uiState.value.feeRate
                sut.onClickEdit()
                sut.onChangeAmount(increase = true)
                assertEquals(previousRate + 1uL, sut.uiState.value.feeRate)
                expectNoEvents()
            }
        }
    }

    @Test
    fun `RBF publication cancellation is not converted into boost failure or success`() = runTest {
        prepareRbf()
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).doSuspendableAnswer {
            throw CancellationException("dismissed")
        }
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            expectNoEvents()
        }
        sut.setupActivity(activitySent.copy(v1 = onchainActivity.copy(txId = "next")))
        assertFalse(sut.uiState.value.boosting)
        verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
    }

    @Test
    fun `pending RBF keeps original ownership when another activity opens`() = runTest {
        prepareRbf()
        val published = CompletableDeferred<Unit>()
        whenever(activityRepo.publishRbfBoost(mockTxId, newTxId)).doSuspendableAnswer {
            published.await()
            Result.success(Unit)
        }
        sut.boostTransactionEffect.map { it.effect }.test {
            sut.onConfirmBoost()
            sut.setupActivity(Activity.Onchain(onchainActivity.copy(id = "other", txId = "other-tx")))
            assertFalse(sut.uiState.value.boosting)
            published.complete(Unit)
            expectNoEvents()
        }
        verify(activityRepo).publishRbfBoost(mockTxId, newTxId)
        verify(lightningRepo, times(1)).bumpFeeByRbf(any(), any())
    }

    private suspend fun prepareRbf() {
        whenever(activityRepo.getOnchainActivityByTxId(mockTxId, onchainActivity.walletId))
            .thenReturn(onchainActivity)
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(feeRate))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(totalFee))
        whenever(lightningRepo.bumpFeeByRbf(any(), any())).thenReturn(Result.success(newTxId))
        whenever(activityRepo.recordRbfBoost(any(), any(), any())).thenReturn(Result.success(Unit))
        sut.setupActivity(activitySent)
    }

    // region estimateTime dynamic tier tests

    @Test
    fun `estimateTime shows fast description when fee rate at or above fast threshold`() = runTest {
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(25UL))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
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
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
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
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(Result.success(totalFee))

        sut.uiState.test {
            awaitItem() // initial state
            sut.setupActivity(lowFeeActivity)
            awaitItem() // loading state
            val state = awaitItem()
            assertEquals(slowFeeTime, state.estimateTime)
        }
    }

    @Test
    fun `estimateTime shows minimum description when fee rate below slow threshold`() = runTest {
        val lowFeeActivity = Activity.Onchain(onchainActivity.copy(feeRate = 1UL))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(3UL))
        whenever(lightningRepo.calculateTotalFee(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
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
