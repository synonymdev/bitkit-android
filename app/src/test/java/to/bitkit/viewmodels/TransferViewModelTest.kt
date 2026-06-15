package to.bitkit.viewmodels

import android.content.Context
import com.synonym.bitkitcore.ChannelLiquidityOptions
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.NodeStatus
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BalanceState
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class TransferViewModelTest : BaseUnitTest() {
    private lateinit var sut: TransferViewModel

    private val context = mock<Context>()
    private val lightningRepo = mock<LightningRepo>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val cacheStore = mock<CacheStore>()
    private val transferRepo = mock<TransferRepo>()
    private val clock = mock<Clock>()

    private val balanceState = MutableStateFlow(BalanceState())
    private val blocktankState = MutableStateFlow(BlocktankState())
    private val feeResponse = mock<IBtEstimateFeeResponse2>()

    @Before
    fun setUp() {
        whenever(feeResponse.feeSat).thenReturn(LSP_FEE)
        whenever(feeResponse.networkFeeSat).thenReturn(NETWORK_FEE)
        whenever(feeResponse.serviceFeeSat).thenReturn(SERVICE_FEE)
        whenever(context.getString(any())).thenReturn("")
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        val nodeStatus = mock<NodeStatus>()
        whenever(nodeStatus.isRunning).thenReturn(true)
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState(nodeStatus = nodeStatus)))
        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(blocktankRepo.blocktankState).thenReturn(blocktankState)

        sut = TransferViewModel(
            context = context,
            lightningRepo = lightningRepo,
            blocktankRepo = blocktankRepo,
            walletRepo = walletRepo,
            settingsStore = settingsStore,
            cacheStore = cacheStore,
            transferRepo = transferRepo,
            clock = clock,
        )
    }

    @Test
    fun `updateLimits caps spending max at LSP max client balance when on-chain balance exceeds it`() = test {
        balanceState.value = BalanceState(maxSendOnchainSats = ON_CHAIN_BALANCE)
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
        balanceState.value = BalanceState(maxSendOnchainSats = ON_CHAIN_BALANCE)
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
        balanceState.value = BalanceState(maxSendOnchainSats = ON_CHAIN_BALANCE)
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(blocktankRepo.calculateLiquidityOptions(any()))
            .thenReturn(Result.success(liquidityOptions(maxClientBalanceSat = 0uL)))
        whenever(blocktankRepo.estimateOrderFee(any(), any(), any())).thenReturn(Result.success(feeResponse))

        sut.updateLimits()
        advanceUntilIdle()

        assertEquals(0L, sut.spendingUiState.value.maxAllowedToSend)
    }

    private fun liquidityOptions(maxClientBalanceSat: ULong) = ChannelLiquidityOptions(
        defaultLspBalanceSat = LSP_BALANCE,
        minLspBalanceSat = LSP_BALANCE,
        maxLspBalanceSat = 0uL,
        maxClientBalanceSat = maxClientBalanceSat,
    )

    private fun btInfo(lspMaxClientBalance: ULong): IBtInfo {
        val options = mock<IBtInfoOptions>()
        whenever(options.maxClientBalanceSat).thenReturn(lspMaxClientBalance)
        return mock<IBtInfo>().also { whenever(it.options).thenReturn(options) }
    }

    private companion object {
        const val ON_CHAIN_BALANCE = 10_000_000uL
        const val LSP_MAX_CLIENT_BALANCE = 1_766_193uL
        const val OPTION_MAX_CLIENT_BALANCE = 1_687_598uL
        const val LSP_BALANCE = 252_368uL
        const val NETWORK_FEE = 2_112uL
        const val SERVICE_FEE = 286uL
        const val LSP_FEE = 2_398uL // NETWORK_FEE + SERVICE_FEE
    }
}
