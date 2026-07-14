package to.bitkit.viewmodels

import android.content.Context
import com.synonym.bitkitcore.BroadcastException
import com.synonym.bitkitcore.ChannelLiquidityOptions
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOptions
import com.synonym.bitkitcore.TrezorException
import com.synonym.bitkitcore.TrezorFeatures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.NodeStatus
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
import to.bitkit.models.TransferType
import to.bitkit.models.TransportType
import to.bitkit.models.safe
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.transfer.previewBtOrder
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
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
            hwWalletRepo = hwWalletRepo,
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

    @Test
    fun `updateHwLimits sources the available amount from the hardware account balance`() = test {
        // walletRepo balance stays 0 to prove the limit comes from the hardware account, not on-chain savings.
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(hwWalletRepo.getFundingAccount(DEVICE_ID))
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

        sut.updateHwLimits(DEVICE_ID)
        advanceUntilIdle()

        assertEquals(OPTION_MAX_CLIENT_BALANCE.toLong(), sut.spendingUiState.value.maxAllowedToSend)
    }

    @Test
    fun `updateHwLimits reserves fallback fee when fee rate lookup fails`() = test {
        blocktankState.value = BlocktankState(info = btInfo(lspMaxClientBalance = LSP_MAX_CLIENT_BALANCE))
        whenever(hwWalletRepo.getFundingAccount(DEVICE_ID))
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

        sut.updateHwLimits(DEVICE_ID)
        advanceUntilIdle()

        val fallbackReserve = (ON_CHAIN_BALANCE.toDouble() * Defaults.fallbackFeePercent).toULong()
        verify(blocktankRepo).estimateOrderFee(eq(ON_CHAIN_BALANCE.safe() - fallbackReserve.safe()), any(), any())
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.success(broadcast))

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        verify(hwWalletRepo).composeFundingTransaction(
            eq(DEVICE_ID),
            eq(order.payment?.onchain?.address.orEmpty()),
            eq(order.feeSat),
            eq(FEE_RATE),
        )
        verify(hwWalletRepo).signFunding(eq(DEVICE_ID), eq(funding))
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
        )
        verify(hwWalletRepo).ensureConnected(DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull()))
            .thenReturn(Result.failure(AppError("fee unavailable")))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.success(broadcast))

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        verify(hwWalletRepo).composeFundingTransaction(
            eq(DEVICE_ID),
            eq(order.payment?.onchain?.address.orEmpty()),
            eq(order.feeSat),
            eq(FALLBACK_FEE_RATE),
        )
    }

    @Test
    fun `onTransferToSpendingHwConfirm aborts when hardware reconnect fails`() = test {
        val order = previewBtOrder()
        whenever(hwWalletRepo.wallets)
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.failure(AppError("no device")))
        whenever(hwWalletRepo.isKnownBluetoothDevice(DEVICE_ID)).thenReturn(false)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        verify(hwWalletRepo).ensureConnected(DEVICE_ID)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, never()).signFunding(any(), any())
        verify(hwWalletRepo, never()).broadcastFunding(any())
    }

    @Test
    fun `cancelHardwareTransfer stops an in-flight hardware transfer`() = test {
        val order = previewBtOrder()
        val connectResult = CompletableDeferred<Result<TrezorFeatures>>()
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID)).doSuspendableAnswer { connectResult.await() }
        whenever(hwWalletRepo.disconnectStaleSession(DEVICE_ID)).thenReturn(Result.success(Unit))

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        runCurrent()
        assertEquals(true, sut.spendingUiState.value.isSigning)

        sut.cancelHardwareTransfer()
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.isSigning)
        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo).disconnectStaleSession(DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.failure(AppError("no device")))
        whenever(hwWalletRepo.isKnownBluetoothDevice(DEVICE_ID)).thenReturn(true)
        whenever(context.getString(R.string.hardware__connect_title)).thenReturn(CONNECT_TITLE)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn(CONNECT_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(Toast.ToastType.INFO, toasts.single().type)
        assertEquals(CONNECT_TITLE, toasts.single().title)
        assertEquals(CONNECT_DESCRIPTION, toasts.single().description)
        verify(hwWalletRepo, never()).composeFundingTransaction(any(), any(), any(), any())
    }

    @Test
    fun `onTransferToSpendingHwConfirm disconnects stale session when signing times out`() = test {
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any())).thenReturn(Result.failure(timeout))
        whenever(hwWalletRepo.disconnectStaleSession(DEVICE_ID)).thenReturn(Result.success(Unit))

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        verify(hwWalletRepo).disconnectStaleSession(DEVICE_ID)
        verify(cacheStore, never()).addPaidOrder(any(), any())
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any()))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(any(), any()))
            .thenReturn(Result.failure(AppError(TrezorException.DeviceBusy())))
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(DEVICE_BUSY_MESSAGE)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn("connect error")

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any()))
            .thenReturn(Result.failure(AppError("Device error (code 99): Firmware error")))
        whenever(context.getString(R.string.lightning__transfer_hw__reconnect_error_title))
            .thenReturn(RECONNECT_TITLE)
        whenever(context.getString(R.string.lightning__transfer_hw__reconnect_error_description))
            .thenReturn(RECONNECT_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any()))
            .thenReturn(Result.failure(timeout))
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.failure(AppError(TrezorException.DeviceBusy())))
        whenever(context.getString(R.string.hardware__device_busy)).thenReturn(DEVICE_BUSY_MESSAGE)
        whenever(context.getString(R.string.hardware__connect_error)).thenReturn("connect error")

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = false))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.failure(TrezorException.UserCancelled()))
        whenever(hwWalletRepo.isKnownBluetoothDevice(DEVICE_ID)).thenReturn(false)
        whenever(
            context.getString(R.string.lightning__transfer_hw__reconnect_error_title)
        ).thenReturn("reconnect title")
        whenever(
            context.getString(R.string.lightning__transfer_hw__reconnect_error_description)
        ).thenReturn("reconnect body")

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(DEVICE_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(
                Result.failure(AppError(BroadcastException.ElectrumException("DNS lookup failed"))),
                Result.success(broadcast),
            )
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        assertEquals(true, sut.spendingUiState.value.hasPendingHwBroadcast)
        assertEquals(Toast.ToastType.WARNING, toasts.single().type)
        assertEquals(CONNECTION_ISSUE_TITLE, toasts.single().title)
        verify(cacheStore, never()).addPaidOrder(any(), any())

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()
        toastJob.cancel()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo, times(1)).ensureConnected(DEVICE_ID)
        verify(hwWalletRepo, times(1)).composeFundingTransaction(any(), any(), any(), any())
        verify(hwWalletRepo, times(1)).signFunding(DEVICE_ID, funding)
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
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(DEVICE_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed))
            .thenReturn(Result.failure(AppError(BroadcastException.ElectrumException("DNS lookup failed"))))
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        order = order.copy(
            payment = requireNotNull(order.payment).copy(
                onchain = requireNotNull(order.payment?.onchain).copy(address = "bc1qnewdestination"),
            ),
        )
        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        verify(hwWalletRepo, times(2)).signFunding(DEVICE_ID, funding)
        verify(hwWalletRepo).composeFundingTransaction(
            DEVICE_ID,
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
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(DEVICE_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).doSuspendableAnswer { broadcastResult.await() }

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(DEVICE_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.success(broadcast))
        var bookkeepingAttempts = 0
        whenever(cacheStore.addPaidOrder(order.id, TXID)).thenAnswer {
            if (bookkeepingAttempts++ == 0) throw AppError("cache failed")
            Unit
        }

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(hwWalletRepo, times(1)).signFunding(DEVICE_ID, funding)
        verify(hwWalletRepo, times(2)).broadcastFunding(signed)
        verify(cacheStore, times(2)).addPaidOrder(order.id, TXID)
        verify(transferRepo).createPendingToSpendingActivity(order, TXID, MINING_FEE, FEE_RATE)
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
            .thenReturn(MutableStateFlow(persistentListOf(hwWallet(DEVICE_ID, connected = true))))
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(DEVICE_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.failure(timeout))
        whenever(context.getString(R.string.other__connection_issue)).thenReturn(CONNECTION_ISSUE_TITLE)
        whenever(context.getString(R.string.other__connection_issues_explain)).thenReturn(CONNECTION_ISSUE_DESCRIPTION)

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
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
        whenever(hwWalletRepo.ensureConnected(DEVICE_ID))
            .thenReturn(Result.success(mock<TrezorFeatures>()))
        whenever(lightningRepo.getFeeRateForSpeed(any(), anyOrNull())).thenReturn(Result.success(FEE_RATE))
        whenever(hwWalletRepo.composeFundingTransaction(any(), any(), any(), any())).thenReturn(Result.success(funding))
        whenever(hwWalletRepo.signFunding(DEVICE_ID, funding)).thenReturn(Result.success(signed))
        whenever(hwWalletRepo.broadcastFunding(signed)).thenReturn(Result.failure(AppError("invalid transaction")))

        sut.onTransferToSpendingHwConfirm(order, DEVICE_ID)
        advanceUntilIdle()

        assertEquals(false, sut.spendingUiState.value.hasPendingHwBroadcast)
        verify(cacheStore, never()).addPaidOrder(any(), any())
    }

    private fun signedFunding(
        funding: HwFundingTransaction,
        feeRate: ULong = FEE_RATE,
    ) = HwFundingSignedTx(
        serializedTx = "rawtx",
        miningFeeSats = funding.miningFeeSats,
        feeRate = feeRate,
        totalSpent = funding.totalSpent,
    )

    private fun hwWallet(deviceId: String, connected: Boolean) = HwWallet(
        id = deviceId,
        name = "Trezor",
        model = "Safe 3",
        transportType = TransportType.USB,
        isConnected = connected,
        balanceSats = 0uL,
        activities = persistentListOf(),
        deviceIds = persistentSetOf(deviceId),
    )

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
        const val DEVICE_ID = "dev1"
        const val DEVICE_BUSY_MESSAGE = "Your Trezor is busy. Unlock it on the device, then try again."
        const val CONNECTION_ISSUE_TITLE = "Internet Connectivity Issues"
        const val CONNECTION_ISSUE_DESCRIPTION = "Please check your connection."
        const val CONNECT_TITLE = "Connect Device"
        const val CONNECT_DESCRIPTION = "Check the hardware device and try again."
        const val RECONNECT_TITLE = "Reconnect Hardware Device"
        const val RECONNECT_DESCRIPTION = "Please reconnect your hardware device."
        const val XPUB = "zpub-test"
        const val TXID = "tx-abc"
        const val FEE_RATE = 2uL
        const val FALLBACK_FEE_RATE = 1uL
        const val MINING_FEE = 1_250uL
    }
}
