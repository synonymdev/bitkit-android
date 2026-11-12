package to.bitkit.ui.screens.transfer

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.synonym.bitkitcore.FeeRates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.BalanceState
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.WalletScope
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendBlockedError
import to.bitkit.repositories.OnchainSendChangedError
import to.bitkit.repositories.OnchainSendEvidence
import to.bitkit.repositories.OnchainSendNotDispatchedError
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.OnchainSendPendingError
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.repositories.SparkDepositClaim
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.transfer.SparkFundingContract.SideEffect
import to.bitkit.ui.shared.toast.ToastEventBus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SparkFundingViewModelTest : BaseUnitTest() {
    private val context = mock<Context>()
    private val walletRepo = mock<WalletRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val sparkRepo = mock<SparkRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val feeRates = FeeRates(fast = 5u, mid = 3u, slow = 1u)
    private val attemptUpdates = MutableStateFlow(0L)

    private fun createSut() =
        SparkFundingViewModel(context, walletRepo, lightningRepo, sparkRepo, currencyRepo, hwWalletRepo)

    @Before
    fun setUp() {
        whenever(context.getString(any())).thenReturn("text {amount}")
        whenever(context.getString(R.string.lightning__spending_amount__error_min__title)).thenReturn("Too small")
        whenever(context.getString(R.string.lightning__spending_confirm__fees_changed_title)).thenReturn("Changed")
        whenever(context.getString(R.string.lightning__spending_amount__error_balance__title)).thenReturn("Short")
        whenever(walletRepo.balanceState).thenReturn(MutableStateFlow(BalanceState(maxSendOnchainSats = 100_000u)))
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
        whenever(lightningRepo.onchainSendAttemptUpdates).thenReturn(attemptUpdates)
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(sparkRepo.depositClaims).thenReturn(MutableStateFlow(emptyMap()))
        whenever { sparkRepo.depositAddress() }.thenReturn(Result.success("bcrt1qspark"))
        whenever { sparkRepo.estimateDepositClaimFeeSats(any()) }.thenReturn(Result.success(1_350uL))
        whenever { sparkRepo.estimateMatureClaimFeeSats() }.thenReturn(Result.success(198uL))
        whenever { lightningRepo.getFeeRates() }.thenReturn(Result.success(feeRates))
        whenever { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balances(100_000uL)))
        whenever { lightningRepo.estimateSendAllFee(any(), any(), any()) }.thenReturn(Result.success(500uL))
        whenever { lightningRepo.calculateTotalFee(any(), any(), any(), anyOrNull(), any(), any()) }
            .thenReturn(Result.success(500uL))
    }

    @Test
    fun `max is the savings balance less the network fee and 25 percent is a quarter of it`() = test {
        val sut = createSut()
        advanceUntilIdle()

        assertEquals(99_500L, sut.uiState.value.maxSats)
        assertEquals(24_875L, sut.quarterSats())
    }

    @Test
    fun `max is zero without savings`() = test {
        whenever { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balances(0uL)))
        val sut = createSut()
        advanceUntilIdle()

        assertEquals(0L, sut.uiState.value.maxSats)
    }

    @Test
    fun `continue refuses an amount that does not cover the instant fee`() = test {
        whenever(context.getString(R.string.lightning__spending_amount__error_min__description))
            .thenReturn("Minimum {amount}")
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(1_000)

        val toasts = collectToasts { sut.onAmountContinue() }

        assertEquals("Too small", toasts.single().title)
        // An amount equal to the 1,350 sat claim fee is refused, so the smallest that passes is one more.
        assertEquals("Minimum ${1_351L.formatToModernDisplay()}", toasts.single().description)
        assertEquals(null, sut.uiState.value.quote)
    }

    @Test
    fun `continue prices the transfer and opens confirm`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        val effects = mutableListOf<SideEffect>()
        val job = launch { sut.effects.collect { effects.add(it) } }

        sut.onAmountContinue()
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf<SideEffect>(SideEffect.AmountReady), effects)
        val quote = requireNotNull(sut.uiState.value.quote)
        assertEquals(50_500L, quote.totalSats)
        assertEquals(48_650L, quote.toSpendingSats(SparkClaimSpeed.Instant))
        assertEquals(49_802L, quote.toSpendingSats(SparkClaimSpeed.Later))
    }

    @Test
    fun `leaving the amount screen while pricing never opens confirm`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        val gate = CompletableDeferred<Unit>()
        whenever(lightningRepo.getFeeRates()).doSuspendableAnswer {
            gate.await()
            Result.success(feeRates)
        }
        val effects = mutableListOf<SideEffect>()
        val job = launch { sut.effects.collect { effects.add(it) } }

        sut.onAmountContinue()
        advanceUntilIdle()
        sut.onAmountLeave()
        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()

        assertEquals(emptyList(), effects)
        assertEquals(false, sut.uiState.value.isLoading)
        assertEquals(null, sut.uiState.value.quote)
    }

    @Test
    fun `swipe asks again when the network fee rose since shown`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        whenever { lightningRepo.calculateTotalFee(any(), any(), any(), anyOrNull(), any(), any()) }
            .thenReturn(Result.success(800uL))

        val toasts = collectToasts { sut.onConfirm() }

        assertEquals("Changed", toasts.single().title)
        assertEquals(50_800L, sut.uiState.value.quote?.totalSats)
        assertEquals(false, sut.uiState.value.isSending)
        verify(lightningRepo, never()).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
    }

    @Test
    fun `a fixed amount whose prepared fee rose is refused and priced again at that fee`() = test {
        whenever(context.getString(R.string.lightning__spending_confirm__fees_changed_network))
            .thenReturn("rose {amount}")
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.failure(OnchainSendNotDispatchedError(OnchainSendChangedError(preparedFeeSats = 700uL))))
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()

        val toasts = collectToasts { sut.onConfirm() }

        verify(lightningRepo).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = eq(500uL),
        )
        assertEquals("Changed", toasts.single().title)
        assertTrue(toasts.single().description.orEmpty().startsWith("rose "), "${toasts.single().description}")
        assertEquals(50_700L, sut.uiState.value.quote?.totalSats, "the new quote shows the fee that was refused")
        assertEquals(false, sut.uiState.value.isSending)

        sut.onConfirm()
        advanceUntilIdle()

        verify(lightningRepo).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = eq(700uL),
        )
    }

    @Test
    fun `swipe on a max transfer whose amount fell names the new amount instead of a fee rise`() = test {
        whenever(context.getString(R.string.lightning__spending_confirm__max_changed_title))
            .thenReturn("Amount changed")
        whenever(context.getString(R.string.lightning__spending_confirm__max_changed)).thenReturn("Max {amount}")
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(sut.uiState.value.maxSats)
        sut.onAmountContinue()
        advanceUntilIdle()
        whenever { lightningRepo.estimateSendAllFee(any(), any(), any()) }.thenReturn(Result.success(800uL))

        val toasts = collectToasts { sut.onConfirm() }

        assertEquals("Amount changed", toasts.single().title)
        // 100,000 savings less the 800 sat network fee and the 1,350 sat claim fee.
        assertEquals("Max ₿ ${97_850L.formatToModernDisplay()}", toasts.single().description)
        assertEquals(false, sut.uiState.value.isSending)
    }

    @Test
    fun `swipe on a max transfer whose claim fee alone rose still names the fee rise`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(sut.uiState.value.maxSats)
        sut.onAmountContinue()
        advanceUntilIdle()
        whenever { sparkRepo.estimateDepositClaimFeeSats(any()) }.thenReturn(Result.success(1_500uL))

        val toasts = collectToasts { sut.onConfirm() }

        assertEquals("Changed", toasts.single().title)
    }

    @Test
    fun `swipe refuses with real copy when savings no longer cover the transfer`() = test {
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        whenever { lightningRepo.getBalancesAsync() }.thenReturn(Result.success(balances(40_000uL)))

        val toasts = collectToasts { sut.onConfirm() }

        assertEquals("Short", toasts.single().title)
        assertEquals(false, sut.uiState.value.isSending)
    }

    @Test
    fun `swipe sends at the quoted fee rates and follows the claim at the chosen speed`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.success(OnchainSendOutcome.Accepted("txid", 50_000uL)))
        whenever(sparkRepo.depositClaims).thenReturn(MutableStateFlow(mapOf("txid" to SparkDepositClaim.Later)))
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        sut.onSpeedChange(SparkClaimSpeed.Later)
        advanceUntilIdle()

        sut.onConfirm()
        advanceUntilIdle()

        verify(lightningRepo).sendOnChain(
            eq("bcrt1qspark"), eq(50_000uL), eq(TransactionSpeed.Fast), anyOrNull(), eq(feeRates), eq(true),
            anyOrNull(), eq(false), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
        verify(sparkRepo).onDepositBroadcast("txid", 50_000uL)
        verify(sparkRepo).followDepositClaim("txid", SparkClaimSpeed.Later, 1_350uL)
        assertEquals("txid", sut.uiState.value.txid)
        assertTrue(sut.uiState.value.claim == SparkDepositClaim.Later)
    }

    @Test
    fun `the transfer banner opens the followed deposit at its claim step`() = test {
        whenever(sparkRepo.depositClaims).thenReturn(MutableStateFlow(mapOf("txid" to SparkDepositClaim.Adding)))
        val sut = createSut()
        advanceUntilIdle()

        sut.showDeposit("txid")

        assertEquals("txid", sut.uiState.value.txid)
        assertEquals(SparkDepositClaim.Adding, sut.uiState.value.claim)
    }

    @Test
    fun `the transfer banner opens a deposit no claim follows, as after a relaunch, as added at maturity`() = test {
        val sut = createSut()
        advanceUntilIdle()

        sut.showDeposit("txid")

        assertEquals("txid", sut.uiState.value.txid)
        assertEquals(SparkDepositClaim.Later, sut.uiState.value.claim)
    }

    @Test
    fun `a funding that was signed but not accepted opens its recovery instead of an error`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.success(OnchainSendOutcome.Unknown("txid")))
        val retained = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(retained)
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        val effects = mutableListOf<SideEffect>()
        backgroundScope.launch { sut.effects.collect { effects += it } }

        val toasts = collectToasts { sut.onConfirm() }

        assertTrue(toasts.isEmpty())
        assertEquals(SideEffect.FundingPending(retained), effects.last())
        verify(sparkRepo, never()).onDepositBroadcast(any(), any())
    }

    @Test
    fun `a retained funding that completes later leaves the confirm screen for setting up`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.success(OnchainSendOutcome.Unknown("txid")))
        val retained = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(retained)
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        val effects = mutableListOf<SideEffect>()
        backgroundScope.launch { sut.effects.collect { effects += it } }
        sut.onConfirm()
        advanceUntilIdle()

        val confirmed = retained.copy(evidence = OnchainSendEvidence.Observed, localFollowupComplete = true)
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(confirmed)
        attemptUpdates.value++
        advanceUntilIdle()

        assertEquals(SideEffect.Sent, effects.last())
        assertEquals("txid", sut.uiState.value.txid)
        verify(sparkRepo).onDepositBroadcast("txid", 50_000uL)
        verify(sparkRepo).followDepositClaim("txid", SparkClaimSpeed.Instant, 1_350uL)
        verify(lightningRepo).followFundingOnScreen("attempt", true)
        verify(lightningRepo, never()).followFundingOnScreen("attempt", false)
    }

    @Test
    fun `a swipe refused for an older funding after a relaunch leaves that funding's early claim asking`() = test {
        val older = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "older", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.failure(OnchainSendBlockedError(older)))
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(older)
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        sut.onConfirm()
        advanceUntilIdle()

        val confirmed = older.copy(evidence = OnchainSendEvidence.Observed, localFollowupComplete = true)
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(confirmed)
        attemptUpdates.value++
        advanceUntilIdle()

        verify(sparkRepo).followDepositClaim("txid", SparkClaimSpeed.Instant, 0uL)
    }

    @Test
    fun `the funding screen owns a kept funding until it closes`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.success(OnchainSendOutcome.Unknown("txid")))
        val retained = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(retained)
        val store = ViewModelStore()
        val sut = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { createSut() } },
        )[SparkFundingViewModel::class]
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        sut.onConfirm()
        advanceUntilIdle()
        whenever { lightningRepo.currentOnchainSendAttempt() }
            .thenReturn(retained.copy(evidence = OnchainSendEvidence.Observed, localFollowupComplete = true))
        attemptUpdates.value++
        advanceUntilIdle()
        verify(lightningRepo, never()).followFundingOnScreen("attempt", false)

        store.clear()

        verify(lightningRepo).followFundingOnScreen("attempt", false)
    }

    @Test
    fun `a funding whose outcome is unknown opens its recovery instead of an error`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.failure(OnchainSendPendingError(IllegalStateException("timeout"), "txid")))
        val retained = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(retained)
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        val effects = mutableListOf<SideEffect>()
        backgroundScope.launch { sut.effects.collect { effects += it } }

        val toasts = collectToasts { sut.onConfirm() }

        assertTrue(toasts.isEmpty())
        assertEquals(SideEffect.FundingPending(retained), effects.last())
    }

    @Test
    fun `a new amount after a kept funding completed off the confirm screen is funded`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.success(OnchainSendOutcome.Unknown("txid")))
        val retained = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(retained)
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        sut.onConfirm()
        advanceUntilIdle()
        // Its Sent effect lands while the Amount screen, which ignores it, is showing.
        val confirmed = retained.copy(evidence = OnchainSendEvidence.Observed, localFollowupComplete = true)
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(confirmed)
        attemptUpdates.value++
        advanceUntilIdle()
        // A new transfer of another amount starts on the Amount screen that showed.
        sut.onAmountChange(40_000)
        sut.onAmountContinue()
        advanceUntilIdle()

        sut.onConfirm()
        advanceUntilIdle()

        verify(lightningRepo, times(2)).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
    }

    @Test
    fun `a kept funding that completed off the confirm screen is not sent again`() = test {
        whenever {
            lightningRepo.sendOnChain(
                any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
                any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                paymentDeadlineAt = anyOrNull(),
                contactPublicKey = anyOrNull(),
                approvedMiningFeeSats = anyOrNull(),
            )
        }.thenReturn(Result.success(OnchainSendOutcome.Unknown("txid")))
        val retained = OnchainSendAttempt(
            walletId = WalletScope.default, attemptId = "attempt", requestId = null,
            orderId = null, address = "bcrt1qspark", amountSats = 50_000uL, isMaxAmount = false,
            feeRateSatsPerVByte = 2uL, isTransfer = true, channelId = null, tags = emptyList(),
            txid = "txid", evidence = OnchainSendEvidence.Unknown, candidateTxids = listOf("txid"),
        )
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(retained)
        val sut = createSut()
        advanceUntilIdle()
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        sut.onConfirm()
        advanceUntilIdle()
        // Its Sent effect lands while the Amount screen, which ignores it, is showing.
        val confirmed = retained.copy(evidence = OnchainSendEvidence.Observed, localFollowupComplete = true)
        whenever { lightningRepo.currentOnchainSendAttempt() }.thenReturn(confirmed)
        attemptUpdates.value++
        advanceUntilIdle()
        val effects = mutableListOf<SideEffect>()
        backgroundScope.launch { sut.effects.collect { effects += it } }

        sut.onConfirm()
        advanceUntilIdle()

        verify(lightningRepo, times(1)).sendOnChain(
            any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(),
            any(), any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            paymentDeadlineAt = anyOrNull(),
            contactPublicKey = anyOrNull(),
            approvedMiningFeeSats = anyOrNull(),
        )
        assertEquals(SideEffect.Sent, effects.lastOrNull())
    }

    @Test
    fun `hardware transfer prices from the device and hands signing a transfer request`() = test {
        whenever { hwWalletRepo.maxSpendableFunding("trezor", "bcrt1qspark", 5uL) }.thenReturn(Result.success(80_000uL))
        whenever { hwWalletRepo.estimateFundingMiningFee("trezor", "bcrt1qspark", 50_000uL, 5uL) }
            .thenReturn(Result.success(700uL))
        val sut = createSut()
        sut.useHardwareWallet("trezor")
        advanceUntilIdle()
        assertEquals(80_000L, sut.uiState.value.maxSats)
        sut.onAmountChange(50_000)
        sut.onAmountContinue()
        advanceUntilIdle()
        val effects = mutableListOf<SideEffect>()
        val job = launch { sut.effects.collect { effects.add(it) } }

        sut.onConfirm()
        advanceUntilIdle()
        job.cancel()

        val request = (effects.single() as SideEffect.SignHw).request
        assertEquals("trezor", request.walletId)
        assertEquals("bcrt1qspark", request.address)
        assertEquals(50_000uL, request.amountSats)
        assertEquals(5uL, request.satsPerVByte)
        assertTrue(request.isTransfer)
        assertEquals(700L, sut.uiState.value.quote?.networkFeeSats)
    }

    private suspend fun TestScope.collectToasts(action: () -> Unit): List<Toast> {
        val toasts = mutableListOf<Toast>()
        val job = launch { ToastEventBus.events.collect { toasts.add(it) } }
        advanceUntilIdle()
        action()
        advanceUntilIdle()
        job.cancel()
        return toasts
    }

    private fun balances(spendable: ULong) = BalanceDetails(
        totalOnchainBalanceSats = spendable,
        spendableOnchainBalanceSats = spendable,
        totalAnchorChannelsReserveSats = 0u,
        totalLightningBalanceSats = 0u,
        lightningBalances = emptyList(),
        pendingBalancesFromChannelClosures = emptyList(),
    )
}
