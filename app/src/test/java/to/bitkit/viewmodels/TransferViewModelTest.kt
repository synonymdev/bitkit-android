package to.bitkit.viewmodels

import android.content.Context
import app.cash.turbine.test
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwapEvent
import com.synonym.bitkitcore.BroadcastException
import com.synonym.bitkitcore.ChannelLiquidityOptions
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOptions
import com.synonym.bitkitcore.ReverseSwapResponse
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorFeatures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.CoinSelectionAlgorithm
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.NodeStatus
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.SpendableUtxo
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Defaults
import to.bitkit.models.BalanceState
import to.bitkit.models.HwFundingAccount
import to.bitkit.models.HwFundingAddressType
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.HwWallet
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.TransportType
import to.bitkit.models.safe
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.HwPassphraseMismatchError
import to.bitkit.repositories.HwPassphraseRequiredError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.BoltzService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.transfer.previewBtOrder
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
@Suppress("LargeClass")
class TransferViewModelTest : BaseUnitTest() {
    private lateinit var sut: TransferViewModel

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val cacheStore = mock<CacheStore>()
    private val transferRepo = mock<TransferRepo>()
    private val clock = mock<Clock>()
    private val boltzService = mock<BoltzService>()

    private val balanceState = MutableStateFlow(BalanceState())
    private val blocktankState = MutableStateFlow(BlocktankState())
    private val boltzEvents = MutableSharedFlow<BoltzSwapEvent>(extraBufferCapacity = 8)
    private val nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private val feeResponse = mock<IBtEstimateFeeResponse2>()

    @Before
    fun setUp() {
        whenever(feeResponse.feeSat).thenReturn(LSP_FEE)
        whenever(feeResponse.networkFeeSat).thenReturn(NETWORK_FEE)
        whenever(feeResponse.serviceFeeSat).thenReturn(SERVICE_FEE)
        whenever(context.getString(any())).thenReturn("")
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        whenever { hwWalletRepo.needsPassphrase(any()) }.thenReturn(false)
        val nodeStatus = mock<NodeStatus>()
        whenever(nodeStatus.isRunning).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(nodeStatus = nodeStatus)))
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(blocktankRepo.blocktankState).thenReturn(blocktankState)
        whenever(boltzService.events).thenReturn(boltzEvents)
        whenever(lightningRepo.nodeEvents).thenReturn(nodeEvents)
        whenever(boltzService.isSwapSupported).thenReturn(true)
        whenever { boltzService.isSwapEnabled() }.thenReturn(true)
        // Default: no mining-fee reserve so existing limit tests keep their balances.
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever { lightningRepo.getFeeRateForSpeed(any(), anyOrNull()) }
            .thenReturn(Result.success(2uL))
        whenever {
            lightningRepo.selectUtxosWithAlgorithm(any(), any(), any(), anyOrNull())
        }.thenReturn(Result.success(listOf(stubUtxo(ON_CHAIN_BALANCE))))

        sut = TransferViewModel(
            context = context,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            hwWalletRepo = hwWalletRepo,
            walletRepo = walletRepo,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
            transferRepo = transferRepo,
            clock = clock,
            boltzService = boltzService,
        )
    }

    @Test
    fun `updateLimits caps spending max at LSP max client balance when on-chain balance exceeds it`() = test {
        stubSpendableBalances(ON_CHAIN_BALANCE)
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        // The LSP reports no room for receiving liquidity (maxLspBalanceSat = 0) because the
        // client balance saturates the channel — the regression this guards against.
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        val state = sut.spendingUiState.value
        assertEquals(OPTION_MAX_CLIENT_BALANCE.toLong(), state.maxAllowedToSend)
        assertEquals(OPTION_MAX_CLIENT_BALANCE.toLong(), state.balanceAfterFee)
        assertEquals((OPTION_MAX_CLIENT_BALANCE.toDouble() * 0.25).roundToLong(), state.quarterAmount)

        // The order fee must be estimated against the clamped client balance, not the full balance.
        verify(blocktankRepo).estimateOrderFee(eq(LSP_MAX_CLIENT_BALANCE), any(), any())
    }

    @Test
    fun `updateLimits uses the full balance when LSP info is unavailable`() = test {
        stubSpendableBalances(ON_CHAIN_BALANCE)
        blocktankState.value = BlocktankState(info = null)
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        assertEquals(OPTION_MAX_CLIENT_BALANCE.toLong(), sut.spendingUiState.value.maxAllowedToSend)
        // Without an LSP cap the order fee is estimated against the balance after the LSP fee.
        verify(blocktankRepo).estimateOrderFee(eq(ON_CHAIN_BALANCE - LSP_FEE), any(), any())
    }

    @Test
    fun `updateLimits sets max to zero when LSP reports zero client balance`() = test {
        stubSpendableBalances(ON_CHAIN_BALANCE)
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = 0uL)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        assertEquals(0L, sut.spendingUiState.value.maxAllowedToSend)
    }

    @Test
    fun `updateLimits reserves fast mining fee before sizing max transfer`() = test {
        // multi_address_2-style tight balance: without this reserve, feeSat + miningFee > spendable.
        val spendable = 100_000uL
        val miningFee = 1_058uL
        val availableAfterMining = spendable - miningFee
        // maxSendOnchainSats is already fee-adjusted for send UI — limits must ignore it and
        // reserve exactly one fast fee from raw spendable (not double-subtract).
        balanceState.value = BalanceState(maxSendOnchainSats = spendable - miningFee)
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = spendable)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        val expectedMax = (availableAfterMining - LSP_FEE).toLong()
        assertEquals(expectedMax, sut.spendingUiState.value.maxAllowedToSend)
        verify(lightningRepo).estimateSendAllFee(
            address = anyOrNull(),
            speed = eq(TransactionSpeed.Fast),
            feeRates = anyOrNull(),
        )
        verify(blocktankRepo).estimateOrderFee(eq(availableAfterMining), any(), any())
    }

    @Test
    fun `updateLimits caps spending max at the balance the LSP fee was quoted for`() = test {
        // Real-world numbers from issue #899: the LSP service fee grows with the client balance, so
        // the second (cheaper) quote must not be used to derive a larger balance than it priced.
        val spendable = 265_904uL
        val miningFee = 178uL
        val availableAmount = spendable - miningFee
        val initialLspFees = 4_165uL
        val balanceAfterLspFee = availableAmount - initialLspFees
        val finalLspFees = 4_128uL
        val initialFeeResponse = stubFeeResponse(initialLspFees)
        val finalFeeResponse = stubFeeResponse(finalLspFees)
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = spendable)))
        whenever(blocktankRepo.estimateOrderFee(eq(availableAmount), any(), any()))
            .thenReturn(Result.success(initialFeeResponse))
        whenever(blocktankRepo.estimateOrderFee(eq(balanceAfterLspFee), any(), any()))
            .thenReturn(Result.success(finalFeeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        val maxAllowedToSend = sut.spendingUiState.value.maxAllowedToSend
        assertEquals(balanceAfterLspFee.toLong(), maxAllowedToSend)
        // The order the user can build at this max must stay within what they can actually pay.
        assertTrue(maxAllowedToSend.toULong() + finalLspFees <= availableAmount)
    }

    @Test
    fun `updateLimits settles the spending max when the LSP fee falls as the client balance rises`() = test {
        // Live quotes from the staging LSP, which charges the LSP side harder than the client side,
        // so the second quote is dearer than the first and no ordering assumption can hold.
        val spendable = 266_656uL
        val miningFee = 178uL
        val availableAmount = spendable - miningFee // 266_478
        val quotes = mapOf(
            266_478uL to 1_798uL, // f(A)     -> balanceAfterLspFee = 264_680
            264_680uL to 1_800uL, // f(C)     -> first candidate is unaffordable
            264_678uL to 1_801uL, // round 1  -> still one sat over
            264_677uL to 1_801uL, // round 2  -> affordable
        )
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = spendable)))
        val responses = quotes.mapValues { (_, fee) -> stubFeeResponse(fee) }
        responses.forEach { (balance, response) ->
            whenever(blocktankRepo.estimateOrderFee(eq(balance), any(), any()))
                .thenReturn(Result.success(response))
        }

        sut.updateLimits()
        advanceUntilIdle()

        val maxAllowedToSend = sut.spendingUiState.value.maxAllowedToSend
        assertEquals(264_677L, maxAllowedToSend)
        // The settled max must fund its own order rather than merely undercut the first quote.
        assertTrue(maxAllowedToSend.toULong() + quotes.getValue(maxAllowedToSend.toULong()) <= availableAmount)
    }

    @Test
    fun `updateLimits re-quotes against the channel split the order will actually use`() = test {
        // Order creation recomputes the LSP balance from the chosen amount, so a re-quote priced
        // against the earlier balance would verify an order that is never created.
        val maxChannel = 1_403_872uL
        val spendable = 266_656uL
        val miningFee = 178uL
        val availableAmount = spendable - miningFee // 266_478
        val quotes = mapOf(266_478uL to 1_798uL, 264_680uL to 1_800uL, 264_678uL to 1_801uL, 264_677uL to 1_801uL)
        val responses = quotes.mapValues { (_, fee) -> stubFeeResponse(fee) }
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))
        responses.forEach { (balance, response) ->
            // each client balance gets its own LSP side, mirroring maxChannelSize - clientBalance
            whenever(blocktankRepo.calculateLiquidityOptions(eq(balance))).thenReturn(
                Result.success(
                    ChannelLiquidityOptions(
                        defaultLspBalanceSat = maxChannel - balance,
                        minLspBalanceSat = maxChannel - balance,
                        maxLspBalanceSat = maxChannel - balance,
                        maxClientBalanceSat = spendable,
                    )
                )
            )
            whenever(blocktankRepo.estimateOrderFee(eq(balance), any(), any()))
                .thenReturn(Result.success(response))
        }

        sut.updateLimits()
        advanceUntilIdle()

        assertEquals(264_677L, sut.spendingUiState.value.maxAllowedToSend)
        // the re-quote must price 264_678 against its own split, not the one taken at 264_680
        verify(blocktankRepo).estimateOrderFee(eq(264_678uL), eq(maxChannel - 264_678uL), any())
        verify(blocktankRepo, never()).estimateOrderFee(eq(264_678uL), eq(maxChannel - 264_680uL), any())
    }

    @Test
    fun `onConfirmAmount refuses to create an order the balance cannot fund`() = test {
        val amount = 260_000uL
        val budget = 265_000uL
        val response = stubFeeResponse(6_000uL) // 260_000 + 6_000 is over the budget
        stubSpendableBalances(budget)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(response))
        sut.updateLimits()
        advanceUntilIdle()

        sut.transferEffects.test {
            sut.onConfirmAmount(amount.toLong())
            advanceUntilIdle()

            assertIs<TransferEffect.ToastError>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(blocktankRepo, never()).createOrder(any(), any(), any())
        assertFalse(sut.spendingUiState.value.isLoading)
    }

    @Test
    fun `onConfirmAmount creates the order when it fits the funding budget`() = test {
        val amount = 260_000uL
        val response = stubFeeResponse(1_000uL)
        stubSpendableBalances(265_000uL)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(response))
        whenever(blocktankRepo.createOrder(any(), any(), any()))
            .thenReturn(Result.success(previewBtOrder(clientBalanceSat = amount)))
        sut.updateLimits()
        advanceUntilIdle()

        sut.onConfirmAmount(amount.toLong())
        advanceUntilIdle()

        verify(blocktankRepo).createOrder(eq(amount), any(), any())
    }

    @Test
    fun `onConfirmAmount proceeds when the on-chain balance cannot be read`() = test {
        val amount = 260_000uL
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.failure(AppError("node unavailable")))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.createOrder(any(), any(), any()))
            .thenReturn(Result.success(previewBtOrder(clientBalanceSat = amount)))
        sut.updateLimits()
        advanceUntilIdle()
        assertNull(sut.spendingUiState.value.fundingBudgetSats)

        sut.onConfirmAmount(amount.toLong())
        advanceUntilIdle()

        // an unreadable balance must not block the flow; confirm stays the authority
        verify(blocktankRepo).createOrder(eq(amount), any(), any())
    }

    @Test
    fun `onConfirmAmount proceeds when the confirm-time fee estimate fails`() = test {
        val amount = 260_000uL
        val response = stubFeeResponse(1_000uL)
        stubSpendableBalances(265_000uL)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(response))
        whenever(blocktankRepo.createOrder(any(), any(), any()))
            .thenReturn(Result.success(previewBtOrder(clientBalanceSat = amount)))
        sut.updateLimits()
        advanceUntilIdle()
        // the budget is sized, so this is the failed-quote path rather than the unset-budget one
        assertNotNull(sut.spendingUiState.value.fundingBudgetSats)

        // the LSP stops quoting only after the limits were sized
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any()))
            .thenReturn(Result.failure(AppError("lsp unreachable")))

        sut.onConfirmAmount(amount.toLong())
        advanceUntilIdle()

        // a quote the LSP will not give must not block the user; confirm stays the authority
        verify(blocktankRepo).createOrder(eq(amount), any(), any())
    }

    @Test
    fun `updateLimits keeps the last candidate when a re-quote fails`() = test {
        val spendable = 266_656uL
        val miningFee = 178uL
        val availableAmount = spendable - miningFee // 266_478
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = spendable)))
        val first = stubFeeResponse(1_798uL)
        val second = stubFeeResponse(1_800uL)
        whenever(blocktankRepo.estimateOrderFee(eq(availableAmount), any(), any()))
            .thenReturn(Result.success(first))
        whenever(blocktankRepo.estimateOrderFee(eq(264_680uL), any(), any()))
            .thenReturn(Result.success(second))
        whenever(blocktankRepo.estimateOrderFee(eq(264_678uL), any(), any()))
            .thenReturn(Result.failure(AppError("lsp unreachable")))

        sut.updateLimits()
        advanceUntilIdle()

        // the step-down candidate is still published rather than the unaffordable quoted balance
        assertEquals(264_678L, sut.spendingUiState.value.maxAllowedToSend)
    }

    @Test
    fun `updateLimits falls back to the shortfall balance when rounds are exhausted`() = test {
        val spendable = 266_656uL
        val miningFee = 178uL
        val availableAmount = spendable - miningFee // 266_478
        // the fee rises as fast as the balance steps down, so no candidate ever becomes affordable
        val quotes = mapOf(
            266_478uL to 1_800uL, // f(A)     -> balanceAfterLspFee = 264_678
            264_678uL to 2_000uL, // f(C)     -> 266_678, over budget
            264_478uL to 2_200uL, // round 1  -> 266_678, still over
            264_278uL to 2_400uL, // round 2  -> 266_678, rounds exhausted
        )
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = spendable)))
        val responses = quotes.mapValues { (_, fee) -> stubFeeResponse(fee) }
        responses.forEach { (balance, response) ->
            whenever(blocktankRepo.estimateOrderFee(eq(balance), any(), any()))
                .thenReturn(Result.success(response))
        }

        sut.updateLimits()
        advanceUntilIdle()

        // the exhausted loop advertises availableAmount minus the last quote, not the last candidate
        assertEquals((availableAmount - 2_400uL).toLong(), sut.spendingUiState.value.maxAllowedToSend)
    }

    @Test
    fun `updateLimits uses percent fallback when fast mining fee estimate fails`() = test {
        val spendable = 100_000uL
        val fallbackMiningFee = (spendable.toDouble() * Defaults.fallbackFeePercent).toULong()
        val availableAfterMining = spendable - fallbackMiningFee
        stubSpendableBalances(spendable)
        blocktankState.value = BlocktankState(info = null)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.failure(AppError("fee unavailable")))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = spendable)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        assertEquals((availableAfterMining - LSP_FEE).toLong(), sut.spendingUiState.value.maxAllowedToSend)
        verify(blocktankRepo).estimateOrderFee(eq(availableAfterMining), any(), any())
    }

    @Test
    fun `updateHwLimits sources the available amount from the hardware account balance`() = test {
        // walletRepo balance stays 0 to prove the limit comes from the hardware account, not on-chain savings.
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(hwWalletRepo.getFundingAccount(HARDWARE_WALLET_ID))
            .thenReturn(
                Result.success(
                    HwFundingAccount.Trezor(
                        xpub = XPUB,
                        addressType = HwFundingAddressType.NATIVE_SEGWIT,
                        balanceSats = ON_CHAIN_BALANCE,
                    ),
                ),
            )
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(1uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateHwLimits(HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(OPTION_MAX_CLIENT_BALANCE.toLong(), sut.spendingUiState.value.maxAllowedToSend)
    }

    @Test
    fun `onConfirmAmount funds a hardware transfer from the device balance not the on-chain wallet`() = test {
        // Regression: the funding check must not read on-chain savings here, or every hardware
        // transfer is rejected because those funds live on the device.
        val amount = 100_000uL
        stubSpendableBalances(0uL) // empty on-chain wallet, as in the hardware e2e
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(hwWalletRepo.getFundingAccount(HARDWARE_WALLET_ID)).thenReturn(
            Result.success(
                HwFundingAccount.Trezor(
                    xpub = XPUB,
                    addressType = HwFundingAddressType.NATIVE_SEGWIT,
                    balanceSats = ON_CHAIN_BALANCE,
                ),
            ),
        )
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(1uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))
        whenever(blocktankRepo.createOrder(any(), any(), any()))
            .thenReturn(Result.success(previewBtOrder(clientBalanceSat = amount)))
        sut.updateHwLimits(HARDWARE_WALLET_ID)
        advanceUntilIdle()

        sut.onConfirmAmount(amount.toLong())
        advanceUntilIdle()

        verify(blocktankRepo).createOrder(eq(amount), any(), any())
    }

    @Test
    fun `updateHwLimits reserves fallback fee when fee rate lookup fails`() = test {
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(hwWalletRepo.getFundingAccount(HARDWARE_WALLET_ID))
            .thenReturn(
                Result.success(
                    HwFundingAccount.Trezor(
                        xpub = XPUB,
                        addressType = HwFundingAddressType.NATIVE_SEGWIT,
                        balanceSats = ON_CHAIN_BALANCE,
                    ),
                ),
            )
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("fee unavailable")))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateHwLimits(HARDWARE_WALLET_ID)
        advanceUntilIdle()

        val fallbackReserve = (ON_CHAIN_BALANCE.toDouble() * Defaults.fallbackFeePercent).toULong()
        verify(blocktankRepo).estimateOrderFee(eq(ON_CHAIN_BALANCE.safe() - fallbackReserve.safe()), any(), any())
    }

    @Test
    fun `updateHwFundingFeeEstimate sets mining fee before signing`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))

        sut.updateHwFundingFeeEstimate(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(MINING_FEE, sut.spendingUiState.value.hwMiningFeeSats)
        verify(hwWalletRepo).composeFundingTransaction(
            eq(HARDWARE_WALLET_ID),
            eq(order.payment?.onchain?.address.orEmpty()),
            eq(order.feeSat),
            eq(FEE_RATE),
        )
        verify(hwWalletRepo, never()).signFunding(any(), any())
    }

    @Test
    fun `updateHwFundingFeeEstimate ignores superseded estimate`() = test {
        val orderA = previewBtOrder()
        val orderB = previewBtOrder().copy(id = "order-b-id")
        val staleCompose = CompletableDeferred<Result<HwFundingTransaction>>()
        val fundingB = HwFundingTransaction(
            psbt = "psbt-b",
            miningFeeSats = 999uL,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = orderB.feeSat + 999uL,
            satsPerVByte = FEE_RATE,
        )
        val staleFunding = HwFundingTransaction(
            psbt = "psbt-a",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = orderA.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )

        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.createOrder(any(), any(), any()))
            .thenReturn(Result.success(orderA))
            .thenReturn(Result.success(orderB))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).doSuspendableAnswer {
            if (sut.spendingUiState.value.order?.id == orderA.id) {
                staleCompose.await()
            } else {
                Result.success(fundingB)
            }
        }

        sut.onConfirmAmount(OPTION_MAX_CLIENT_BALANCE.toLong())
        advanceUntilIdle()

        sut.updateHwFundingFeeEstimate(orderA, HARDWARE_WALLET_ID)
        runCurrent()

        sut.onSpendingAdvancedContinue(LSP_BALANCE.toLong())
        advanceUntilIdle()

        sut.updateHwFundingFeeEstimate(orderB, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(999uL, sut.spendingUiState.value.hwMiningFeeSats)

        staleCompose.complete(Result.success(staleFunding))
        advanceUntilIdle()

        assertEquals(999uL, sut.spendingUiState.value.hwMiningFeeSats)
    }

    @Test
    fun `updateAdvancedFundingBudget reserves the fast mining fee from spendable`() = test {
        val spendable = 300_000uL
        val miningFee = 178uL
        stubSpendableBalances(spendable)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(miningFee))

        sut.updateAdvancedFundingBudget()
        advanceUntilIdle()

        assertEquals(spendable - miningFee, sut.spendingUiState.value.advancedBudgetSats)
    }

    @Test
    fun `onSpendingAdvancedContinue rejects a receiving capacity the balance cannot fund`() = test {
        val clientBalance = 260_000uL
        val order = previewBtOrder(clientBalanceSat = clientBalance)
        val budget = 265_000uL
        val raisedCapacity = LSP_BALANCE * 2u
        // the default capacity is affordable, the raised one is not
        val affordable = stubFeeResponse(1_000uL)
        val unaffordable = stubFeeResponse(6_000uL)
        stubSpendableBalances(budget)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.createOrder(any(), any(), any())).thenReturn(Result.success(order))
        whenever(blocktankRepo.estimateOrderFee(eq(clientBalance), eq(LSP_BALANCE), any()))
            .thenReturn(Result.success(affordable))
        whenever(blocktankRepo.estimateOrderFee(eq(clientBalance), eq(raisedCapacity), any()))
            .thenReturn(Result.success(unaffordable))
        sut.onConfirmAmount(clientBalance.toLong())
        advanceUntilIdle()
        sut.updateAdvancedFundingBudget()
        advanceUntilIdle()

        sut.transferEffects.test {
            sut.onSpendingAdvancedContinue(raisedCapacity.toLong())
            advanceUntilIdle()

            assertIs<TransferEffect.ToastError>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // only the initial order from onConfirmAmount, no unaffordable one on top of it
        verify(blocktankRepo, times(1)).createOrder(any(), any(), any())
    }

    @Test
    fun `onSpendingAdvancedContinue creates the order when the capacity fits the budget`() = test {
        val clientBalance = 260_000uL
        val order = previewBtOrder(clientBalanceSat = clientBalance)
        val budget = 265_000uL
        val response = stubFeeResponse(1_000uL)
        stubSpendableBalances(budget)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.createOrder(any(), any(), any())).thenReturn(Result.success(order))
        whenever(blocktankRepo.estimateOrderFee(eq(clientBalance), any(), any()))
            .thenReturn(Result.success(response))
        sut.onConfirmAmount(clientBalance.toLong())
        advanceUntilIdle()
        sut.updateAdvancedFundingBudget()
        advanceUntilIdle()

        sut.onSpendingAdvancedContinue(LSP_BALANCE.toLong())
        advanceUntilIdle()

        assertTrue(sut.spendingUiState.value.isAdvanced)
        verify(blocktankRepo, times(2)).createOrder(any(), any(), any())
    }

    @Test
    fun `onSpendingAdvancedContinue rejects an unaffordable capacity without a cached budget`() = test {
        val clientBalance = 260_000uL
        val order = previewBtOrder(clientBalanceSat = clientBalance)
        val raisedCapacity = LSP_BALANCE * 2u
        val affordable = stubFeeResponse(1_000uL)
        val unaffordable = stubFeeResponse(6_000uL)
        stubSpendableBalances(265_000uL)
        whenever { lightningRepo.estimateSendAllFee(anyOrNull(), anyOrNull(), anyOrNull()) }
            .thenReturn(Result.success(0uL))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.createOrder(any(), any(), any())).thenReturn(Result.success(order))
        whenever(blocktankRepo.estimateOrderFee(eq(clientBalance), eq(LSP_BALANCE), any()))
            .thenReturn(Result.success(affordable))
        whenever(blocktankRepo.estimateOrderFee(eq(clientBalance), eq(raisedCapacity), any()))
            .thenReturn(Result.success(unaffordable))
        sut.onConfirmAmount(clientBalance.toLong())
        advanceUntilIdle()
        // deliberately no updateAdvancedFundingBudget call, so the cached budget stays null
        assertNull(sut.spendingUiState.value.advancedBudgetSats)

        sut.transferEffects.test {
            sut.onSpendingAdvancedContinue(raisedCapacity.toLong())
            advanceUntilIdle()

            assertIs<TransferEffect.ToastError>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(blocktankRepo, times(1)).createOrder(any(), any(), any())
    }

    @Test
    fun `onSpendingAdvancedContinue proceeds when the on-chain balance cannot be read`() = test {
        val clientBalance = 260_000uL
        val order = previewBtOrder(clientBalanceSat = clientBalance)
        val response = stubFeeResponse(6_000uL)
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptionsForCreate(maxClientBalanceSat = OPTION_MAX_CLIENT_BALANCE)))
        whenever(blocktankRepo.createOrder(any(), any(), any())).thenReturn(Result.success(order))
        whenever(blocktankRepo.estimateOrderFee(eq(clientBalance), any(), any()))
            .thenReturn(Result.success(response))
        sut.onConfirmAmount(clientBalance.toLong())
        advanceUntilIdle()
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.failure(AppError("node unavailable")))

        sut.onSpendingAdvancedContinue(LSP_BALANCE.toLong())
        advanceUntilIdle()

        // an unreadable balance must not block the user; confirm stays the authority
        assertTrue(sut.spendingUiState.value.isAdvanced)
        verify(blocktankRepo, times(2)).createOrder(any(), any(), any())
    }

    @Test
    fun `prepareSpendingConfirmFunding exposes real mining fee for confirm UI`() = test {
        val order = previewBtOrder(feeSat = 98_000uL)
        val selected = listOf(stubUtxo(100_000u))
        stubSpendableBalances(spendable = 100_000u)
        whenever {
            lightningRepo.selectUtxosWithAlgorithm(any(), any(), any(), anyOrNull())
        }.thenReturn(Result.success(selected))
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(1_000uL))

        sut.prepareSpendingConfirmFunding(order)
        advanceUntilIdle()

        val state = sut.spendingUiState.value
        assertEquals(true, state.isConfirmFeeReady)
        assertEquals(1_000uL, state.miningFeeSats)
        assertEquals(false, state.shouldUseSendAll)
    }

    @Test
    fun `onTransferToSpendingConfirm uses send-all when selected inputs would create dust change`() = test {
        val order = previewBtOrder(feeSat = 99_000uL)
        val selected = listOf(stubUtxo(100_000u))
        stubSpendableBalances(spendable = 100_000u)
        whenever(lightningRepo.estimateSendAllFee(any(), any(), anyOrNull())).thenReturn(Result.success(500uL))
        whenever {
            lightningRepo.selectUtxosWithAlgorithm(any(), any(), any(), anyOrNull())
        }.thenReturn(Result.success(selected))
        // totalInput 100000 - feeSat 99000 - normalFee 500 = 500 dust (< Defaults.dustLimit)
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(500uL))
        stubSendOnChainSuccess()
        var fundingPaidEmitted = false
        backgroundScope.launch {
            sut.transferEffects.collect { effect ->
                if (effect is TransferEffect.OnSpendingFundingPaid) {
                    fundingPaidEmitted = true
                }
            }
        }

        sut.onTransferToSpendingConfirm(order)
        advanceUntilIdle()

        assertEquals(true, sut.spendingUiState.value.isConfirmPaying)
        assertTrue(fundingPaidEmitted)
        verify(lightningRepo).selectUtxosWithAlgorithm(
            targetAmountSats = eq(order.feeSat),
            satsPerVByte = any(),
            algorithm = eq(CoinSelectionAlgorithm.LARGEST_FIRST),
            utxos = anyOrNull(),
        )
        verify(lightningRepo).sendOnChain(
            address = eq(order.payment?.onchain?.address.orEmpty()),
            sats = eq(order.feeSat),
            speed = eq(TransactionSpeed.Fast),
            utxosToSpend = anyOrNull(),
            feeRates = anyOrNull(),
            isTransfer = eq(true),
            channelId = anyOrNull(),
            isMaxAmount = eq(true),
            tags = any(),
        )
        verify(cacheStore).addPaidOrder(eq(order.id), eq(TXID))
    }

    @Test
    fun `onTransferToSpendingConfirm does not drain when normal fee leaves non-dust change`() = test {
        // Regression: 41x1k UTXOs — send-all fee made expectedChange look like 0, but normal
        // coin selection fee leaves real change and must not wipe the wallet.
        val order = previewBtOrder(feeSat = 35_341uL)
        val selected = listOf(stubUtxo(41_000u))
        stubSpendableBalances(spendable = 41_000u)
        whenever(lightningRepo.estimateSendAllFee(any(), any(), anyOrNull()))
            .thenReturn(Result.success(5_659uL))
        whenever {
            lightningRepo.selectUtxosWithAlgorithm(any(), any(), any(), anyOrNull())
        }.thenReturn(Result.success(selected))
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(2_830uL))
        stubSendOnChainSuccess()

        sut.onTransferToSpendingConfirm(order)
        advanceUntilIdle()

        assertEquals(true, sut.spendingUiState.value.isConfirmPaying)
        verify(lightningRepo).sendOnChain(
            address = eq(order.payment?.onchain?.address.orEmpty()),
            sats = eq(order.feeSat),
            speed = eq(TransactionSpeed.Fast),
            utxosToSpend = eq(selected),
            feeRates = anyOrNull(),
            isTransfer = eq(true),
            channelId = anyOrNull(),
            isMaxAmount = eq(false),
            tags = any(),
        )
        verify(lightningRepo, never()).sendOnChain(
            address = any(),
            sats = any(),
            speed = any(),
            utxosToSpend = anyOrNull(),
            feeRates = anyOrNull(),
            isTransfer = any(),
            channelId = anyOrNull(),
            isMaxAmount = eq(true),
            tags = any(),
        )
        verify(cacheStore).addPaidOrder(eq(order.id), eq(TXID))
    }

    @Test
    fun `onTransferToSpendingConfirm surfaces error when fixed send fails without draining`() = test {
        // Match iOS: dust was already decided up front; do not surprise-drain on send failure.
        val order = previewBtOrder(feeSat = 98_000uL)
        val selected = listOf(stubUtxo(100_000u))
        stubSpendableBalances(spendable = 100_000u)
        whenever(lightningRepo.estimateSendAllFee(any(), any(), anyOrNull()))
            .thenReturn(Result.success(1_000uL))
        whenever {
            lightningRepo.selectUtxosWithAlgorithm(any(), any(), any(), anyOrNull())
        }.thenReturn(Result.success(selected))
        // 100000 - 98000 - 1000 = 1000, above dust → fixed send; drain would still cover order.
        whenever(lightningRepo.calculateTotalFee(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(1_000uL))
        whenever(
            lightningRepo.sendOnChain(
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                any(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).thenReturn(Result.failure(AppError("Coin selection failed")))

        sut.onTransferToSpendingConfirm(order)
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.isConfirmPaying)
        verify(lightningRepo, times(1)).sendOnChain(
            address = eq(order.payment?.onchain?.address.orEmpty()),
            sats = eq(order.feeSat),
            speed = eq(TransactionSpeed.Fast),
            utxosToSpend = eq(selected),
            feeRates = anyOrNull(),
            isTransfer = eq(true),
            channelId = anyOrNull(),
            isMaxAmount = eq(false),
            tags = any(),
        )
        verify(lightningRepo, never()).sendOnChain(
            address = any(),
            sats = any(),
            speed = any(),
            utxosToSpend = anyOrNull(),
            feeRates = anyOrNull(),
            isTransfer = any(),
            channelId = anyOrNull(),
            isMaxAmount = eq(true),
            tags = any(),
        )
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm signs the funding send and records the paid order`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = TXID,
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE,
            totalSpent = order.feeSat + MINING_FEE,
        )
        val signed = signedFunding(funding)
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.success(broadcast))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(MINING_FEE, sut.spendingUiState.value.hwMiningFeeSats)
        verify(hwWalletRepo).composeFundingTransaction(
            eq(HARDWARE_WALLET_ID),
            eq(order.payment?.onchain?.address.orEmpty()),
            eq(order.feeSat),
            eq(FEE_RATE),
        )
        verify(hwWalletRepo).signFunding(eq(HARDWARE_WALLET_ID), eq(funding))
        verify(hwWalletRepo).broadcastFunding(signed)
        verify(cacheStore).addPaidOrder(eq(order.id), eq(TXID))
        verify(transferRepo).createTransfer(
            eq(TransferType.TO_SPENDING),
            eq(order.clientBalanceSat.toLong()),
            isNull<String>(),
            eq(TXID),
            eq(order.id),
            isNull<UInt>(),
            isNull<Long>(),
            isNull<Long>(),
        )
        verify(transferRepo).createPendingToSpendingActivity(
            eq(order),
            eq(TXID),
            eq(MINING_FEE),
            eq(FEE_RATE),
            eq(HARDWARE_WALLET_ID),
        )
        verify(hwWalletRepo).ensureConnected(HARDWARE_WALLET_ID)
    }

    @Test
    fun `onTransferToSpendingHwConfirm asks for the passphrase when the hidden wallet session is gone`() = test {
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever { hwWalletRepo.needsPassphrase(HARDWARE_WALLET_ID) }.thenReturn(true)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertTrue(sut.spendingUiState.value.isHwPassphraseRequired)
        assertFalse(sut.spendingUiState.value.isSigning)
        verify(hwWalletRepo, never()).ensureConnected(any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
    }

    @Test
    fun `asks for the passphrase when the device session belongs to another identity`() = test {
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.failure(HwPassphraseRequiredError()))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertTrue(sut.spendingUiState.value.isHwPassphraseRequired)
        verify(hwWalletRepo, never()).signFunding(any(), any())
    }

    @Test
    fun `retries a pending broadcast without asking for the passphrase`() = test {
        // Rebroadcasting an already signed transaction is electrum-only and never reaches the
        // device, so holding it behind a passphrase prompt strands a signed transfer.
        val order = previewBtOrder()
        val address = order.payment?.onchain?.address.orEmpty()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        val broadcast = HwFundingBroadcastResult(
            txId = TXID,
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE,
            totalSpent = order.feeSat + MINING_FEE,
        )
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(Result.failure(AppError(BroadcastException.ElectrumException("DNS lookup failed"))))
            .thenReturn(Result.success(broadcast))

        // sign once so a broadcast is left pending, then lose the session
        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        assertTrue(sut.spendingUiState.value.hasPendingHwBroadcast)
        whenever { hwWalletRepo.needsPassphrase(HARDWARE_WALLET_ID) }.thenReturn(true)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertFalse(sut.spendingUiState.value.isHwPassphraseRequired)
        verify(hwWalletRepo, times(2)).broadcastFunding(signed)
        verify(hwWalletRepo, times(1)).signFunding(any(), any())
    }

    @Test
    fun `cancelHardwareTransfer clears the passphrase prompt`() = test {
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever { hwWalletRepo.needsPassphrase(HARDWARE_WALLET_ID) }.thenReturn(true)
        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        assertTrue(sut.spendingUiState.value.isHwPassphraseRequired)

        sut.cancelHardwareTransfer()
        advanceUntilIdle()

        assertFalse(sut.spendingUiState.value.isHwPassphraseRequired)
        assertFalse(sut.spendingUiState.value.isVerifyingHwPassphrase)
    }

    @Test
    fun `onHwPassphraseSubmit signs once the reopened wallet matches`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever { hwWalletRepo.needsPassphrase(HARDWARE_WALLET_ID) }.thenReturn(true, false)
        whenever { hwWalletRepo.reconnectWithPassphrase(HARDWARE_WALLET_ID, "secret") }
            .thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(
            Result.success(
                HwFundingBroadcastResult(
                    txId = TXID,
                    miningFeeSats = MINING_FEE,
                    feeRate = FEE_RATE,
                    totalSpent = order.feeSat + MINING_FEE,
                )
            )
        )
        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        sut.onHwPassphraseSubmit(order, HARDWARE_WALLET_ID, "secret")
        advanceUntilIdle()

        assertFalse(sut.spendingUiState.value.isHwPassphraseRequired)
        verify(hwWalletRepo).reconnectWithPassphrase(HARDWARE_WALLET_ID, "secret")
        verify(hwWalletRepo).signFunding(eq(HARDWARE_WALLET_ID), eq(funding))
    }

    @Test
    fun `dismissing the passphrase prompt stops the reopen from starting a signature`() = test {
        // The sheet can be swiped away while the device is still reopening the wallet; the transfer
        // the user backed out of must not go on to ask the device for a signature.
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever { hwWalletRepo.reconnectWithPassphrase(HARDWARE_WALLET_ID, "secret") }
            .thenReturn(Result.success(Unit))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))

        sut.onHwPassphraseSubmit(order, HARDWARE_WALLET_ID, "secret")
        sut.onHwPassphraseDismiss()
        advanceUntilIdle()

        assertFalse(sut.spendingUiState.value.isHwPassphraseRequired)
        assertFalse(sut.spendingUiState.value.isVerifyingHwPassphrase)
        verify(hwWalletRepo, never()).ensureConnected(any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
    }

    @Test
    fun `onHwPassphraseSubmit does not sign when the passphrase opens another wallet`() = test {
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever { hwWalletRepo.needsPassphrase(HARDWARE_WALLET_ID) }.thenReturn(true)
        whenever { hwWalletRepo.reconnectWithPassphrase(HARDWARE_WALLET_ID, "wrong") }
            .thenReturn(Result.failure(HwPassphraseMismatchError()))
        whenever(context.getString(R.string.hardware__passphrase_mismatch)).thenReturn(PASSPHRASE_MISMATCH)
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }

        sut.onHwPassphraseSubmit(order, HARDWARE_WALLET_ID, "wrong")
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(PASSPHRASE_MISMATCH, toasts.single().description)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm composes with fallback fee rate when fee lookup fails`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FALLBACK_FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FALLBACK_FEE_RATE,
        )
        val broadcast = HwFundingBroadcastResult(
            txId = TXID,
            miningFeeSats = MINING_FEE,
            feeRate = FALLBACK_FEE_RATE,
            totalSpent = order.feeSat + MINING_FEE,
        )
        val signed = signedFunding(funding, feeRate = FALLBACK_FEE_RATE)
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("fee unavailable")))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.success(broadcast))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        verify(lightningRepo).getFeeRateForSpeed(eq(TransactionSpeed.Fast), anyOrNull())
        verify(hwWalletRepo).composeFundingTransaction(
            eq(HARDWARE_WALLET_ID),
            eq(order.payment?.onchain?.address.orEmpty()),
            eq(order.feeSat),
            eq(FALLBACK_FEE_RATE),
        )
    }

    @Test
    fun `onTransferToSpendingHwConfirm aborts when hardware reconnect fails`() = test {
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.failure(AppError("no device")))
        whenever(hwWalletRepo.isKnownBluetoothDevice(HARDWARE_WALLET_ID)).thenReturn(false)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        verify(hwWalletRepo).ensureConnected(HARDWARE_WALLET_ID)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `cancelHardwareTransfer stops an in-flight hardware transfer`() = test {
        val order = previewBtOrder()
        val connectResult = CompletableDeferred<Result<TrezorFeatures>>()
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID)).doSuspendableAnswer { connectResult.await() }
        whenever(hwWalletRepo.disconnectStaleSession(HARDWARE_WALLET_ID)).thenReturn(Result.success(Unit))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        runCurrent()
        assertEquals(true, sut.spendingUiState.value.isSigning)

        sut.cancelHardwareTransfer()
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.isSigning)
        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo).disconnectStaleSession(HARDWARE_WALLET_ID)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm shows connection guidance for bluetooth reconnect failure`() = test {
        val order = previewBtOrder()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.failure(AppError("no device")))
        whenever(hwWalletRepo.isKnownBluetoothDevice(HARDWARE_WALLET_ID)).thenReturn(true)
        whenever(context.getString(R.string.hardware__connect_title)).thenReturn(CONNECT_TITLE)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn(CONNECT_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.INFO, toasts.single().type)
        assertEquals(CONNECT_TITLE, toasts.single().title)
        assertEquals(CONNECT_DESCRIPTION, toasts.single().description)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm disconnects stale session when signing fails with timeout`() = test {
        val order = previewBtOrder()
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.failure(timeout))
        whenever(hwWalletRepo.disconnectStaleSession(HARDWARE_WALLET_ID)).thenReturn(Result.success(Unit))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        verify(hwWalletRepo).disconnectStaleSession(HARDWARE_WALLET_ID)
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm disconnects when HW_SIGN_TIMEOUT elapses`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val order = previewBtOrder()
            val funding = HwFundingTransaction(
                psbt = "psbt",
                miningFeeSats = MINING_FEE,
                feeRate = FEE_RATE.toFloat(),
                totalSpent = order.feeSat + MINING_FEE,
                satsPerVByte = FEE_RATE,
            )
            whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
                .thenReturn(Result.success(mock<TrezorFeatures>()))
            whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
            whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any()))
                .thenReturn(Result.success(funding))
            whenever(hwWalletRepo.signFunding(any(), any())).doSuspendableAnswer {
                delay(Long.MAX_VALUE)
                Result.success(signedFunding(funding))
            }
            whenever(hwWalletRepo.disconnectStaleSession(HARDWARE_WALLET_ID)).thenReturn(Result.success(Unit))

            val viewModel = TransferViewModel(
                context = context,
                lightningRepo = lightningRepo,
                blocktankRepo = blocktankRepo,
                hwWalletRepo = hwWalletRepo,
                walletRepo = walletRepo,
                settingsStore = settingsStore,
                cacheStore = cacheStore,
                transferRepo = transferRepo,
                clock = clock,
                boltzService = boltzService,
            )

            viewModel.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
            runCurrent()
            advanceTimeBy(120.seconds.inWholeMilliseconds + 1)
            runCurrent()
            advanceUntilIdle()

            verify(hwWalletRepo).disconnectStaleSession(HARDWARE_WALLET_ID)
            verify(cacheStore, never()).addPaidOrder(any(), any())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `onTransferToSpendingHwConfirm does not fund order when user cancels on device`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any()))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm shows unlock prompt when device is busy`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any()))
            .thenReturn(Result.failure(AppError(TrezorException.DeviceBusy())))
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(DEVICE_BUSY_MESSAGE)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn("connect error")

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(1, toasts.size)
        assertEquals(Toast.ToastType.INFO, toasts.single().type)
        assertEquals(DEVICE_BUSY_MESSAGE, toasts.single().title)
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm shows reconnect prompt for firmware error response`() = test {
        val order = previewBtOrder()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any()))
            .thenReturn(Result.failure(AppError("Device error (code 99): Firmware error")))
        whenever(context.getString(R.string.lightning__transfer_hw__reconnect_error_title))
            .thenReturn(RECONNECT_TITLE)
        whenever(context.getString(R.string.lightning__transfer_hw__reconnect_error_description))
            .thenReturn(RECONNECT_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(1, toasts.size)
        assertEquals(Toast.ToastType.ERROR, toasts.single().type)
        assertEquals(RECONNECT_TITLE, toasts.single().title)
        assertEquals(RECONNECT_DESCRIPTION, toasts.single().description)
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm shows connection warning when composition times out`() = test {
        val order = previewBtOrder()
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any()))
            .thenReturn(Result.failure(timeout))
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(1, toasts.size)
        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals(CONNECTION_ISSUE_TITLE, toasts.single().title)
        assertEquals(CONNECTION_ISSUE_DESCRIPTION, toasts.single().description)
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm shows unlock prompt when device is busy during reconnect`() = test {
        val order = previewBtOrder()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.failure(AppError(TrezorException.DeviceBusy())))
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(DEVICE_BUSY_MESSAGE)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn("connect error")

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(1, toasts.size)
        assertEquals(Toast.ToastType.INFO, toasts.single().type)
        assertEquals(DEVICE_BUSY_MESSAGE, toasts.single().title)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm does not toast when user cancels during reconnect`() = test {
        val order = previewBtOrder()
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))
        whenever(hwWalletRepo.isKnownBluetoothDevice(HARDWARE_WALLET_ID)).thenReturn(false)
        whenever(
            context.getString(R.string.lightning__transfer_hw__reconnect_error_title)
        ).thenReturn("reconnect title")
        whenever(
            context.getString(R.string.lightning__transfer_hw__reconnect_error_description)
        ).thenReturn("reconnect body")

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertTrue(toasts.isEmpty())
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm retries broadcast without signing again`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        val broadcast = HwFundingBroadcastResult(
            txId = TXID,
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE,
            totalSpent = order.feeSat + MINING_FEE,
        )
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(HARDWARE_WALLET_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(
                Result.failure(AppError(BroadcastException.ElectrumException("DNS lookup failed"))),
                Result.success(broadcast),
            )
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(true, sut.spendingUiState.value.hasPendingHwBroadcast)
        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals(CONNECTION_ISSUE_TITLE, toasts.single().title)
        verify(cacheStore, never()).addPaidOrder(any(), any())

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo, times(1)).ensureConnected(HARDWARE_WALLET_ID)
        verify(hwWalletRepo, times(1)).composeFundingTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, times(1)).signFunding(HARDWARE_WALLET_ID, funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(signed)
        verify(cacheStore).addPaidOrder(order.id, TXID)
    }

    @Test
    fun `onTransferToSpendingHwConfirm signs again when pending order address changes`() = test {
        var order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(HARDWARE_WALLET_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(Result.failure(AppError(BroadcastException.ElectrumException("DNS lookup failed"))))
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        order = order.copy(
            payment = requireNotNull(order.payment).copy(
                onchain = requireNotNull(order.payment?.onchain).copy(address = "bc1qnewdestination"),
            ),
        )
        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(HARDWARE_WALLET_ID, funding)
        verify(hwWalletRepo).composeFundingTransaction(
            HARDWARE_WALLET_ID,
            "bc1qnewdestination",
            order.feeSat,
            FEE_RATE,
        )
    }

    @Test
    fun `cancelHardwareTransfer keeps tracking after signing completes`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        val broadcast = HwFundingBroadcastResult(
            txId = TXID,
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE,
            totalSpent = order.feeSat + MINING_FEE,
        )
        val broadcastResult = CompletableDeferred<Result<HwFundingBroadcastResult>>()
        var hwTxSignedEmitted = false
        backgroundScope.launch {
            sut.transferEffects.collect { effect ->
                if (effect is TransferEffect.OnHwTxSigned) {
                    hwTxSignedEmitted = true
                }
            }
        }
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(HARDWARE_WALLET_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).doSuspendableAnswer { broadcastResult.await() }

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        runCurrent()
        assertEquals(true, sut.spendingUiState.value.hasPendingHwBroadcast)

        sut.cancelHardwareTransfer()
        broadcastResult.complete(Result.success(broadcast))
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        assertTrue(hwTxSignedEmitted)
        verify(cacheStore).addPaidOrder(order.id, TXID)
        verify(transferRepo).createTransfer(
            eq(TransferType.TO_SPENDING),
            eq(order.clientBalanceSat.toLong()),
            isNull<String>(),
            eq(TXID),
            eq(order.id),
            isNull<UInt>(),
            isNull<Long>(),
            isNull<Long>(),
        )
    }

    @Test
    fun `onTransferToSpendingHwConfirm retries core-derived txid after bookkeeping fails`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        val broadcast = HwFundingBroadcastResult(
            txId = TXID,
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE,
            totalSpent = order.feeSat + MINING_FEE,
        )
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(HARDWARE_WALLET_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.success(broadcast))
        var bookkeepingAttempts = 0
        whenever(cacheStore.addPaidOrder(order.id, TXID)).thenAnswer {
            if (bookkeepingAttempts++ == 0) throw AppError("cache failed")
            Unit
        }

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(true, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo).broadcastFunding(signed)
        verify(transferRepo, never()).createTransfer(
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo, times(1)).signFunding(HARDWARE_WALLET_ID, funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(signed)
        verify(cacheStore, times(2)).addPaidOrder(order.id, TXID)
        verify(transferRepo).createPendingToSpendingActivity(
            order,
            TXID,
            MINING_FEE,
            FEE_RATE,
            HARDWARE_WALLET_ID,
        )
    }

    @Test
    fun `onTransferToSpendingHwConfirm keeps signed transaction when broadcast times out`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException
        val toasts = mutableListOf<Toast>()
        val toastJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(HARDWARE_WALLET_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(HARDWARE_WALLET_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.failure(timeout))
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(true, sut.spendingUiState.value.hasPendingHwBroadcast)
        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        verify(hwWalletRepo, never()).disconnectStaleSession(any())
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm clears signed transaction after permanent broadcast failure`() = test {
        val order = previewBtOrder()
        val funding = HwFundingTransaction(
            psbt = "psbt",
            miningFeeSats = MINING_FEE,
            feeRate = FEE_RATE.toFloat(),
            totalSpent = order.feeSat + MINING_FEE,
            satsPerVByte = FEE_RATE,
        )
        val signed = signedFunding(funding)
        whenever(hwWalletRepo.ensureConnected(HARDWARE_WALLET_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(HARDWARE_WALLET_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.failure(AppError("invalid transaction")))

        sut.onTransferToSpendingHwConfirm(order, HARDWARE_WALLET_ID)
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    @Test
    fun `loadSavingsSwapQuote defaults to max transferable within limits and spendable balance`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        val state = sut.savingsSwapState.value
        val expectedMax = SPENDABLE_LN - SPENDABLE_LN / 100uL // 1% routing fee reserve
        assertEquals(SWAP_MIN, state.minSat)
        assertEquals(expectedMax, state.maxSat)
        val quote = assertNotNull(state.quote)
        assertEquals(expectedMax, quote.amountSat)
        assertEquals(SWAP_MINER_FEE, quote.networkFeeSat)
        val expectedSwapFee = (expectedMax.toLong() * SWAP_FEE_PERCENT / 100.0).roundToLong().toULong()
        assertEquals(expectedSwapFee, quote.swapFeeSat)
        assertEquals(expectedMax - expectedSwapFee - SWAP_MINER_FEE, quote.receiveSat)
    }

    @Test
    fun `loadSavingsSwapQuote falls back to close when below the swap minimum`() = test {
        whenever(context.getString(R.string.lightning__savings_confirm__amount_too_low)).thenReturn(TOO_LOW)
        balanceState.value = BalanceState(maxSendLightningSats = SWAP_MIN - 1uL)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        val state = sut.savingsSwapState.value
        assertNull(state.quote)
        assertEquals(0uL, state.maxSat)

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)

        sut.startSavingsSwap()
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(TOO_LOW), sut.savingsSwapResult.value)
    }

    @Test
    fun `loadSavingsSwapQuote falls back to close when the limits fetch fails`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenAnswer { throw AppError(BOLTZ_ERROR) }

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        assertNull(sut.savingsSwapState.value.quote)
        assertFalse(sut.savingsSwapState.value.isLoading)

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `loadSavingsSwapQuote skips the network when swaps are unsupported`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.isSwapSupported).thenReturn(false)

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        assertEquals(SavingsSwapUiState(), sut.savingsSwapState.value)
        verify(boltzService, never()).reverseLimits(anyOrNull())

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `loadSavingsSwapQuote skips the network when swaps are disabled in dev settings`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.isSwapEnabled()).thenReturn(false)

        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        assertEquals(SavingsSwapUiState(), sut.savingsSwapState.value)
        verify(boltzService, never()).reverseLimits(anyOrNull())

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `onTransferToSavingsConfirm swaps when a quote is ready and closes when the user opts out`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.onTransferToSavingsConfirm(emptyList())
        assertEquals(SavingsTransferMode.SWAP, sut.savingsTransferMode.value)

        sut.onTransferToSavingsConfirm(emptyList(), SavingsTransferMode.CLOSE)
        assertEquals(SavingsTransferMode.CLOSE, sut.savingsTransferMode.value)
    }

    @Test
    fun `onTransferToSavingsConfirm clears the outcome of an earlier swap`() = test {
        stubSavingsSwapHappyPath()
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.failure(AppError(PAY_ERROR)))
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()
        sut.startSavingsSwap()
        advanceUntilIdle()
        assertEquals(SavingsSwapResult.Failure(PAY_ERROR), sut.savingsSwapResult.value)

        sut.onTransferToSavingsConfirm(emptyList())

        assertNull(sut.savingsSwapResult.value)
    }

    @Test
    fun `onSwapAmountChange clamps to the allowed range and reprices`() = test {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.onSwapAmountChange(SWAP_MIN - 10_000uL)

        assertEquals(SWAP_MIN, sut.savingsSwapState.value.quote?.amountSat)
    }

    @Test
    fun `startSavingsSwap succeeds when the claim event arrives`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = TXID))
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap fails when the swap reports an error event`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Error(swapId = SWAP_ID, message = BOLTZ_ERROR))
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(BOLTZ_ERROR), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap returns pending when the claim does not arrive in time`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Pending, sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap fails when the invoice payment fails`() = test {
        stubSavingsSwapHappyPath()
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.failure(AppError(PAY_ERROR)))
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(PAY_ERROR), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap ignores a second start while a swap is in flight`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = TXID))
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)
        verify(boltzService).createReverseSwap(any(), any(), anyOrNull(), anyOrNull())
        verify(lightningRepo).payInvoice(any(), anyOrNull())
    }

    @Test
    fun `startSavingsSwap fails when the paid invoice reports a lightning routing failure`() = test {
        stubSavingsSwapHappyPath()
        whenever(context.getString(R.string.wallet__payment_route_not_found))
            .thenReturn(ROUTE_NOT_FOUND_MSG)
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        // payInvoice returns as soon as the HTLC is dispatched; the routing failure lands later.
        nodeEvents.emit(
            Event.PaymentFailed(
                paymentId = PAYMENT_ID,
                paymentHash = PAYMENT_ID,
                reason = PaymentFailureReason.ROUTE_NOT_FOUND,
            ),
        )
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Failure(ROUTE_NOT_FOUND_MSG), sut.savingsSwapResult.value)
    }

    @Test
    fun `startSavingsSwap ignores a restart once an outcome is already delivered`() = test {
        stubSavingsSwapHappyPath()
        sut.loadSavingsSwapQuote(REQUESTED_SAT)
        advanceUntilIdle()

        sut.startSavingsSwap()
        runCurrent()
        boltzEvents.emit(BoltzSwapEvent.Claimed(swapId = SWAP_ID, txid = TXID))
        advanceUntilIdle()
        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)

        // A configuration change re-fires startSavingsSwap; the delivered outcome must block a rerun.
        sut.startSavingsSwap()
        advanceUntilIdle()

        assertEquals(SavingsSwapResult.Success(TXID), sut.savingsSwapResult.value)
        verify(boltzService).createReverseSwap(any(), any(), anyOrNull(), anyOrNull())
        verify(lightningRepo).payInvoice(any(), anyOrNull())
    }

    private suspend fun stubSavingsSwapHappyPath() {
        balanceState.value = BalanceState(maxSendLightningSats = SPENDABLE_LN)
        whenever(boltzService.reverseLimits(anyOrNull())).thenReturn(reverseLimits())
        whenever(lightningRepo.newAddress()).thenReturn(Result.success(CLAIM_ADDRESS))
        whenever(boltzService.createReverseSwap(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(reverseSwapResponse())
        whenever(lightningRepo.payInvoice(any(), anyOrNull())).thenReturn(Result.success(PAYMENT_ID))
    }

    private fun reverseLimits() = BoltzPairInfo(
        hash = "hash",
        rate = 1.0,
        minimalSat = SWAP_MIN,
        maximalSat = SWAP_MAX,
        feePercentage = SWAP_FEE_PERCENT,
        minerFeesSat = SWAP_MINER_FEE,
    )

    private fun reverseSwapResponse() = ReverseSwapResponse(
        id = SWAP_ID,
        invoice = "lnbc1invoice",
        lockupAddress = "bcrt1qlockup",
        onchainAmountSat = SWAP_MIN,
        timeoutBlockHeight = 800uL,
    )

    private fun signedFunding(
        funding: HwFundingTransaction,
        feeRate: ULong = FEE_RATE,
    ) = HwFundingSignedTx(
        serializedTx = "rawtx",
        miningFeeSats = funding.miningFeeSats,
        feeRate = feeRate,
        totalSpent = funding.totalSpent,
    )

    private fun hwWallet(walletId: String, connected: Boolean) = HwWallet(
        id = walletId,
        name = "Trezor",
        model = "Safe 3",
        transportType = TransportType.USB,
        isConnected = connected,
        balanceSats = 0uL,
        activities = persistentListOf(),
        deviceIds = persistentSetOf("dev1"),
    )

    private fun liquidityOptions(maxClientBalanceSat: ULong) = ChannelLiquidityOptions(
        defaultLspBalanceSat = LSP_BALANCE,
        minLspBalanceSat = LSP_BALANCE,
        maxLspBalanceSat = 0uL,
        maxClientBalanceSat = maxClientBalanceSat,
    )

    private fun stubFeeResponse(lspFees: ULong): IBtEstimateFeeResponse2 = mock<IBtEstimateFeeResponse2>().also {
        whenever(it.feeSat).thenReturn(lspFees)
        whenever(it.networkFeeSat).thenReturn(lspFees)
        whenever(it.serviceFeeSat).thenReturn(0uL)
    }

    private fun liquidityOptionsForCreate(maxClientBalanceSat: ULong) = ChannelLiquidityOptions(
        defaultLspBalanceSat = LSP_BALANCE,
        minLspBalanceSat = LSP_BALANCE,
        maxLspBalanceSat = LSP_BALANCE,
        maxClientBalanceSat = maxClientBalanceSat,
    )

    private fun btInfo(lspMaxClientBalance: ULong): IBtInfo {
        val options = mock<IBtInfoOptions>()
        whenever(options.maxClientBalanceSat).thenReturn(lspMaxClientBalance)
        return mock<IBtInfo>().also { whenever(it.options).thenReturn(options) }
    }

    private suspend fun stubSpendableBalances(spendable: ULong) {
        val balances = BalanceDetails(
            totalOnchainBalanceSats = spendable,
            spendableOnchainBalanceSats = spendable,
            totalAnchorChannelsReserveSats = 0u,
            totalLightningBalanceSats = 0u,
            lightningBalances = emptyList(),
            pendingBalancesFromChannelClosures = emptyList(),
        )
        whenever(lightningRepo.getBalancesAsync()).thenReturn(Result.success(balances))
    }

    private fun stubUtxo(valueSats: ULong): SpendableUtxo = SpendableUtxo(
        outpoint = OutPoint(txid = "stub-utxo-txid", vout = 0u),
        valueSats = valueSats,
    )

    private suspend fun stubSendOnChainSuccess() {
        whenever(
            lightningRepo.sendOnChain(
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                any(),
                anyOrNull(),
                any(),
                any(),
            ),
        ).thenReturn(Result.success(TXID))
    }

    private companion object {
        const val ON_CHAIN_BALANCE = 10_000_000uL
        const val LSP_MAX_CLIENT_BALANCE = 1_766_193uL
        const val OPTION_MAX_CLIENT_BALANCE = 1_687_598uL
        const val LSP_BALANCE = 252_368uL
        const val NETWORK_FEE = 2_112uL
        const val SERVICE_FEE = 286uL
        const val LSP_FEE = 2_398uL // NETWORK_FEE + SERVICE_FEE
        const val DEVICE_BUSY_MESSAGE = "Your Trezor is busy. Unlock it on the device, then try again."
        const val CONNECTION_ISSUE_TITLE = "Internet Connectivity Issues"
        const val CONNECTION_ISSUE_DESCRIPTION = "Please check your connection."
        const val CONNECT_TITLE = "Connect Device"
        const val CONNECT_DESCRIPTION = "Check the hardware device and try again."
        const val HARDWARE_WALLET_ID = "hardware-wallet"
        const val PASSPHRASE_MISMATCH = "That passphrase opens a different wallet."
        const val RECONNECT_TITLE = "Reconnect Hardware Device"
        const val RECONNECT_DESCRIPTION = "Please reconnect your hardware device."
        const val XPUB = "zpub-test"
        const val TXID = "tx-abc"
        const val FEE_RATE = 2uL
        const val FALLBACK_FEE_RATE = 3uL
        const val MINING_FEE = 1_250uL
        const val SPENDABLE_LN = 150_000uL
        const val REQUESTED_SAT = 200_000uL
        const val SWAP_MIN = 25_000uL
        const val SWAP_MAX = 1_000_000uL
        const val SWAP_FEE_PERCENT = 0.5
        const val SWAP_MINER_FEE = 300uL
        const val SWAP_ID = "swap1"
        const val CLAIM_ADDRESS = "bcrt1qclaim"
        const val TOO_LOW = "Amount is too low"
        const val PAY_ERROR = "no route found"
        const val BOLTZ_ERROR = "boltz unavailable"
        const val PAYMENT_ID = "paymentId"
        const val ROUTE_NOT_FOUND_MSG = "No route to destination"
    }
}
