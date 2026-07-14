package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.env.Defaults
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.isBroadcastConnectivityFailure
import to.bitkit.ext.isTrezorDeviceBusy
import to.bitkit.ext.isTrezorFirmwareError
import to.bitkit.ext.isTrezorUserCancellation
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.safe
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

const val RETRY_INTERVAL_MS = 1 * 60 * 1000L // 1 minutes in ms
const val GIVE_UP_MS = 30 * 60 * 1000L // 30 minutes in ms

@Suppress("LargeClass", "TooManyFunctions", "LongParameterList")
@OptIn(ExperimentalTime::class)
@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val hwWalletRepo: HwWalletRepo,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore,
    private val transferRepo: TransferRepo,
    private val clock: Clock,
) : ViewModel() {
    private val _spendingUiState = MutableStateFlow(TransferToSpendingUiState())
    val spendingUiState = _spendingUiState.asStateFlow()

    private val _isForceTransferLoading = MutableStateFlow(false)
    val isForceTransferLoading = _isForceTransferLoading.asStateFlow()

    val lightningSetupStep: StateFlow<Int> = settingsStore.data.map { it.lightningSetupStep }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val isNodeRunning = lightningRepo.lightningState.map { it.nodeStatus?.isRunning ?: false }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _selectedChannelIdsState = MutableStateFlow<Set<String>>(emptySet())
    val selectedChannelIdsState = _selectedChannelIdsState.asStateFlow()

    private val _transferValues = MutableStateFlow(TransferValues())
    val transferValues = _transferValues.asStateFlow()

    val transferEffects = MutableSharedFlow<TransferEffect>()
    fun setTransferEffect(effect: TransferEffect) = viewModelScope.launch { transferEffects.emit(effect) }
    var maxLspFee = 0uL
    private var hwTransferSignJob: Job? = null
    private var pendingHwFundingBroadcast: PendingHwFundingBroadcast? = null
    private var activeHwTransferDeviceId: String? = null

    // region Spending

    fun onConfirmAmount(satsAmount: Long) {
        val values = blocktankRepo.calculateLiquidityOptions(satsAmount.toULong()).getOrNull()
        if (values == null || values.maxLspBalanceSat == 0uL) {
            setTransferEffect(
                TransferEffect.ToastError(
                    title = context.getString(R.string.lightning__spending_amount__error_max__title),
                    description = context.getString(
                        R.string.lightning__spending_amount__error_max__description_zero
                    ),
                )
            )
            return
        }

        val lspBalance = maxOf(values.defaultLspBalanceSat, values.minLspBalanceSat)

        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            withTimeoutOrNull(1.minutes) {
                isNodeRunning.first { it }
            }

            blocktankRepo.createOrder(
                spendingBalanceSats = satsAmount.toULong(),
                receivingBalanceSats = lspBalance,
            )
                .onSuccess { order ->
                    settingsStore.update { it.copy(lightningSetupStep = 0) }
                    onOrderCreated(order)
                    delay(1.seconds) // Give time to settle the UI
                    _spendingUiState.update { it.copy(isLoading = false) }
                }.onFailure { e ->
                    setTransferEffect(TransferEffect.ToastException(e))
                    delay(1.seconds) // Give time to settle the UI
                    _spendingUiState.update { it.copy(isLoading = false) }
                }
        }
    }

    fun updateLimits(satsAmount: Long = 0) {
        updateTransferValues(satsAmount.toULong())
        updateAvailableAmount()
    }

    fun onReceivingAmountChange(amount: Long) {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(receivingAmount = amount, feeEstimate = null) }

            if (amount == 0L) return@launch

            val transferValues = _transferValues.value
            if (transferValues.minLspBalance == 0uL) return@launch

            val isValid = amount.toULong() >= transferValues.minLspBalance &&
                amount.toULong() <= transferValues.maxLspBalance

            if (!isValid) return@launch

            val result = blocktankRepo.estimateOrderFee(
                spendingBalanceSats = _spendingUiState.value.order?.clientBalanceSat ?: 0u,
                receivingBalanceSats = amount.toULong(),
            )

            result.fold(
                onSuccess = { response ->
                    _spendingUiState.update {
                        it.copy(feeEstimate = response.feeSat.toLong())
                    }
                },
                onFailure = { error ->
                    Logger.error("Failed to estimate fee", error, context = TAG)
                    _spendingUiState.update {
                        it.copy(feeEstimate = null)
                    }
                }
            )
        }
    }

    fun onSpendingAdvancedContinue(receivingAmountSats: Long) {
        viewModelScope.launch {
            runCatching {
                val oldOrder = _spendingUiState.value.order ?: return@launch
                val newOrder = blocktankRepo.createOrder(
                    spendingBalanceSats = oldOrder.clientBalanceSat,
                    receivingBalanceSats = receivingAmountSats.toULong(),
                ).getOrThrow()
                _spendingUiState.update {
                    it.copy(
                        order = newOrder,
                        defaultOrder = oldOrder,
                        isAdvanced = true,
                    )
                }
                setTransferEffect(TransferEffect.OnOrderCreated)
            }.onFailure { e ->
                setTransferEffect(TransferEffect.ToastException(e))
            }
        }
    }

    /** Pays for the order and start watching it for state updates */
    fun onTransferToSpendingConfirm(order: IBtOrder, speed: TransactionSpeed? = null) {
        viewModelScope.launch {
            val address = order.payment?.onchain?.address.orEmpty()

            // Use live spendableOnchainBalanceSats (not cached) to respect anchor reserves
            val balanceDetails = lightningRepo.getBalancesAsync().getOrNull()
            val spendableBalance = balanceDetails?.spendableOnchainBalanceSats ?: 0uL
            val sendAllFee = lightningRepo.estimateSendAllFee(
                address = address,
                speed = speed,
            ).getOrElse {
                Logger.error("Failed to estimate send-all fee", it, context = TAG)
                ToastEventBus.send(it)
                return@launch
            }

            val expectedChange =
                spendableBalance.toLong() - order.feeSat.toLong() - sendAllFee.toLong()
            val shouldUseSendAll =
                expectedChange >= 0 && expectedChange < TRANSFER_SEND_ALL_THRESHOLD_SATS

            val miningFee = if (shouldUseSendAll) {
                sendAllFee
            } else {
                lightningRepo.calculateTotalFee(
                    amountSats = order.feeSat,
                    address = address,
                    speed = speed,
                ).getOrElse {
                    Logger.warn("Failed to estimate transfer funding fee", it, context = TAG)
                    0uL
                }
            }
            val txTotalSats = if (shouldUseSendAll) {
                spendableBalance
            } else {
                order.feeSat.safe() + miningFee.safe()
            }

            Logger.debug(
                "BT confirm: spendable=$spendableBalance, feeSat=${order.feeSat}, " +
                    "sendAllFee=$sendAllFee, expectedChange=$expectedChange, sendAll=$shouldUseSendAll",
                context = TAG,
            )

            lightningRepo
                .sendOnChain(
                    address = address,
                    sats = order.feeSat,
                    speed = speed,
                    isTransfer = true,
                    channelId = order.channel?.shortChannelId,
                    isMaxAmount = shouldUseSendAll,
                )
                .onSuccess { txId ->
                    fundPaidOrder(
                        order = order,
                        txId = txId,
                        txTotalSats = txTotalSats,
                        preTransferOnchainSats = balanceDetails?.totalOnchainBalanceSats ?: spendableBalance,
                    )
                }
                .onFailure { error ->
                    ToastEventBus.send(error)
                }
        }
    }

    /** Records a paid order and starts watching it, after the funding tx was broadcast (local or HW signed). */
    private suspend fun fundPaidOrder(
        order: IBtOrder,
        txId: String,
        createTransferActivity: Boolean = false,
        fee: ULong = 0uL,
        feeRate: ULong = 0uL,
        txTotalSats: ULong? = null,
        preTransferOnchainSats: ULong? = null,
    ) {
        cacheStore.addPaidOrder(orderId = order.id, txId = txId)
        transferRepo.createTransfer(
            type = TransferType.TO_SPENDING,
            amountSats = order.clientBalanceSat.toLong(),
            fundingTxId = txId,
            lspOrderId = order.id,
            txTotalSats = txTotalSats?.toLong(),
            preTransferOnchainSats = preTransferOnchainSats?.toLong(),
        )
        if (createTransferActivity) {
            transferRepo.createPendingToSpendingActivity(
                order = order,
                txId = txId,
                fee = fee,
                feeRate = feeRate,
            )
        }
        viewModelScope.launch { walletRepo.syncBalances() }
        viewModelScope.launch { watchOrder(order.id) }
    }

    private suspend fun watchOrder(orderId: String): Result<Boolean> = runCatching {
        Logger.debug("Started watching order: '$orderId'", context = TAG)

        // Step 0: Starting
        settingsStore.update { it.copy(lightningSetupStep = LN_SETUP_STEP_0) }
        Logger.debug("LN setup step: $LN_SETUP_STEP_0", context = TAG)
        delay(MIN_STEP_DELAY_MS)

        // Poll until payment is confirmed (order state becomes PAID or EXECUTED)
        val paidOrder = pollUntil(orderId) { order ->
            order.state2 == BtOrderState2.PAID || order.state2 == BtOrderState2.EXECUTED
        } ?: return Result.failure(Exception("Order not found or expired"))

        // Step 1: Payment confirmed
        settingsStore.update { it.copy(lightningSetupStep = LN_SETUP_STEP_1) }
        Logger.debug("LN setup step: $LN_SETUP_STEP_1", context = TAG)
        delay(MIN_STEP_DELAY_MS)

        // Try to open channel (idempotent - safe to call multiple times)
        blocktankRepo.openChannel(paidOrder.id)

        // Step 2: Channel opening requested
        settingsStore.update { it.copy(lightningSetupStep = LN_SETUP_STEP_2) }
        Logger.debug("LN setup step: $LN_SETUP_STEP_2", context = TAG)
        delay(MIN_STEP_DELAY_MS)

        // Poll until channel is ready (EXECUTED state or channel has state)
        pollUntil(orderId) { order ->
            order.state2 == BtOrderState2.EXECUTED || order.channel?.state != null
        } ?: return Result.failure(Exception("Order not found or expired"))

        // Step 3: Complete
        transferRepo.syncTransferStates()
        settingsStore.update { it.copy(lightningSetupStep = LN_SETUP_STEP_3) }
        Logger.debug("LN setup step: $LN_SETUP_STEP_3", context = TAG)

        Logger.debug("Order settled: '$orderId'", context = TAG)
        return@runCatching true
    }.onFailure {
        Logger.error("Failed to watch order: '$orderId'", it, context = TAG)
    }.also {
        Logger.debug("Stopped watching order: '$orderId'", context = TAG)
    }

    private suspend fun pollUntil(orderId: String, condition: (IBtOrder) -> Boolean): IBtOrder? {
        var consecutiveErrors = 0

        while (true) {
            blocktankRepo.getOrder(orderId, refresh = true).fold(
                onSuccess = { order ->
                    consecutiveErrors = 0

                    if (order == null) {
                        Logger.error("Order not found: '$orderId'", context = TAG)
                        return null
                    }
                    if (order.state2 == BtOrderState2.EXPIRED) {
                        Logger.error("Order expired: '$orderId'", context = TAG)
                        return null
                    }
                    if (condition(order)) {
                        return order
                    }
                },
                onFailure = {
                    consecutiveErrors++
                    Logger.warn(
                        "Failed to fetch order '$orderId' (attempt $consecutiveErrors/$MAX_CONSECUTIVE_ERRORS)",
                        it,
                        context = TAG
                    )

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Logger.error("Too many consecutive errors polling order '$orderId', giving up", context = TAG)
                        return null
                    }
                }
            )

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun onOrderCreated(order: IBtOrder) {
        settingsStore.update { it.copy(lightningSetupStep = 0) }
        pendingHwFundingBroadcast = null
        _spendingUiState.update {
            it.copy(
                order = order,
                isAdvanced = false,
                defaultOrder = null,
                hasPendingHwBroadcast = false,
            )
        }
        setTransferEffect(TransferEffect.OnOrderCreated)
    }

    private fun updateAvailableAmount() {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            val availableAmount = walletRepo.balanceState.value.maxSendOnchainSats

            awaitNodeRunning()

            val initialLspFees = estimateInitialLspFees(availableAmount)
            if (initialLspFees == null) {
                _spendingUiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val balanceAfterLspFee = availableAmount.safe() - initialLspFees.safe()

            estimateFinalMaxSendAmount(availableAmount, balanceAfterLspFee)
        }
    }

    private suspend fun awaitNodeRunning() {
        withTimeoutOrNull(1.minutes) {
            isNodeRunning.first { it }
        }
    }

    private suspend fun estimateInitialLspFees(availableAmount: ULong): ULong? {
        val liquidity = blocktankRepo
            .calculateLiquidityOptions(availableAmount)
            .getOrNull() ?: return null

        val lspBalance = maxOf(liquidity.defaultLspBalanceSat, liquidity.minLspBalanceSat)

        val orderFee = blocktankRepo.estimateOrderFee(
            spendingBalanceSats = availableAmount,
            receivingBalanceSats = lspBalance,
        ).getOrNull() ?: return null

        return orderFee.networkFeeSat.safe() + orderFee.serviceFeeSat.safe()
    }

    private suspend fun estimateFinalMaxSendAmount(
        availableAmount: ULong,
        balanceAfterLspFee: ULong,
    ) {
        // An on-chain balance larger than the LSP's max channel size makes
        // calculateLiquidityOptions report maxLspBalanceSat = 0 (the client balance already
        // saturates the channel). Clamp the prospective client balance to the LSP's
        // maxClientBalanceSat so the spendable amount caps at that limit instead of collapsing
        // to zero, leaving the rest of the funds on-chain.
        val lspMaxClientBalance = blocktankRepo.blocktankState.value.info?.options?.maxClientBalanceSat
        val cappedClientBalance = lspMaxClientBalance
            ?.let { max -> minOf(balanceAfterLspFee, max) }
            ?: balanceAfterLspFee

        val liquidity = blocktankRepo.calculateLiquidityOptions(cappedClientBalance).getOrNull()
        if (liquidity == null || liquidity.maxClientBalanceSat == 0uL) {
            _spendingUiState.update { it.copy(isLoading = false, maxAllowedToSend = 0) }
            return
        }

        val receivingAmount = maxOf(liquidity.defaultLspBalanceSat, liquidity.minLspBalanceSat)

        blocktankRepo.estimateOrderFee(
            spendingBalanceSats = cappedClientBalance,
            receivingBalanceSats = receivingAmount,
        ).onSuccess { estimate ->
            maxLspFee = estimate.feeSat
            val lspFees = estimate.networkFeeSat.safe() + estimate.serviceFeeSat.safe()
            val maxClientBalance = availableAmount.safe() - lspFees.safe()
            val maxSend = min(
                liquidity.maxClientBalanceSat.toLong(),
                maxClientBalance.toLong()
            )
            val quarterAmount = min((maxSend.toDouble() * 0.25).roundToLong(), maxSend)

            _spendingUiState.update {
                it.copy(
                    maxAllowedToSend = maxSend,
                    isLoading = false,
                    balanceAfterFee = maxSend,
                    quarterAmount = quarterAmount,
                )
            }
        }.onFailure {
            _spendingUiState.update { it.copy(isLoading = false) }
            Logger.error("Failure", it, context = TAG)
            setTransferEffect(TransferEffect.ToastException(it))
        }
    }

    fun onUseDefaultLspBalanceClick() {
        val defaultOrder = _spendingUiState.value.defaultOrder
        _spendingUiState.update {
            it.copy(
                order = defaultOrder,
                defaultOrder = null,
                isAdvanced = false,
            )
        }
    }

    fun resetSpendingState() {
        hwTransferSignJob?.cancel()
        hwTransferSignJob = null
        pendingHwFundingBroadcast = null
        activeHwTransferDeviceId = null
        _spendingUiState.update { TransferToSpendingUiState() }
        _transferValues.update { TransferValues() }
    }

    fun cancelHardwareTransfer() {
        if (pendingHwFundingBroadcast != null) return
        val deviceId = activeHwTransferDeviceId
        hwTransferSignJob?.cancel()
        hwTransferSignJob = null
        _spendingUiState.update { it.copy(isSigning = false) }
        if (deviceId != null) {
            viewModelScope.launch {
                hwWalletRepo.disconnectStaleSession(deviceId)
            }
        }
    }

    // endregion

    // region Hardware Wallet

    fun updateHwLimits(deviceId: String) {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            val account = hwWalletRepo.getFundingAccount(deviceId).getOrElse {
                Logger.error("Failed to load hardware funding account", it, context = TAG)
                _spendingUiState.update { s -> s.copy(isLoading = false, maxAllowedToSend = 0, balanceAfterFee = 0) }
                setTransferEffect(TransferEffect.ToastException(it))
                return@launch
            }

            awaitNodeRunning()
            updateTransferValues(0uL)

            val availableAmount = account.balanceSats.safe() - hwFundingFeeReserve(account.balanceSats).safe()

            val initialLspFees = estimateInitialLspFees(availableAmount)
            if (initialLspFees == null) {
                _spendingUiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val balanceAfterLspFee = availableAmount.safe() - initialLspFees.safe()
            estimateFinalMaxSendAmount(availableAmount, balanceAfterLspFee)
        }
    }

    /** Pays for the order by composing and signing the funding send on the Trezor, then watches it. */
    fun warmUpHardwareConnection(deviceId: String) {
        hwWalletRepo.warmUpKnownDevice(deviceId)
    }

    fun onTransferToSpendingHwConfirm(order: IBtOrder, deviceId: String) {
        if (hwTransferSignJob?.isActive == true) return

        activeHwTransferDeviceId = deviceId
        hwTransferSignJob = viewModelScope.launch {
            _spendingUiState.update { it.copy(isSigning = true) }
            try {
                val address = order.payment?.onchain?.address.orEmpty()
                if (address.isEmpty()) {
                    ToastEventBus.send(type = Toast.ToastType.ERROR, title = context.getString(R.string.common__error))
                    return@launch
                }

                signAndBroadcastHardwareFunding(order, deviceId, address)
                    .onSuccess { result ->
                        runSuspendCatching {
                            fundPaidOrder(
                                order = order,
                                txId = result.txId,
                                createTransferActivity = true,
                                fee = result.miningFeeSats,
                                feeRate = result.feeRate,
                            )
                        }.onSuccess {
                            pendingHwFundingBroadcast = null
                            activeHwTransferDeviceId = null
                            _spendingUiState.update { it.copy(hasPendingHwBroadcast = false) }
                            setTransferEffect(TransferEffect.OnHwTxSigned)
                        }.onFailure {
                            Logger.error("Failed to record broadcast hardware transfer", it, context = TAG)
                            handleHardwareTransferFailure(it, deviceId)
                        }
                    }
                    .onFailure { handleHardwareTransferFailure(it, deviceId) }
            } finally {
                _spendingUiState.update { it.copy(isSigning = false) }
                hwTransferSignJob = null
            }
        }
    }

    private suspend fun signAndBroadcastHardwareFunding(
        order: IBtOrder,
        deviceId: String,
        address: String,
    ): Result<HwFundingBroadcastResult> {
        val result = runCatching {
            val signedTx = pendingHwFundingBroadcast
                ?.takeIf { it.matches(order, deviceId, address) }
                ?.signedTx
                ?: prepareSignedHardwareFunding(order, deviceId, address).also {
                    pendingHwFundingBroadcast = PendingHwFundingBroadcast(
                        orderId = order.id,
                        deviceId = deviceId,
                        address = address,
                        amountSats = order.feeSat,
                        signedTx = it,
                    )
                    _spendingUiState.update { state -> state.copy(hasPendingHwBroadcast = true) }
                }
            broadcastHardwareFunding(signedTx)
        }
        result.exceptionOrNull()?.let {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
        }
        return result
    }

    private suspend fun prepareSignedHardwareFunding(
        order: IBtOrder,
        deviceId: String,
        address: String,
    ): HwFundingSignedTx {
        ensureHardwareConnected(deviceId)
        val satsPerVByte = hwFundingSatsPerVByte()
        val funding = composeHardwareFundingTransaction(
            deviceId = deviceId,
            address = address,
            sats = order.feeSat,
            satsPerVByte = satsPerVByte,
        )
        return signHardwareFunding(deviceId, funding)
    }

    @Suppress("ThrowsCount")
    private suspend fun ensureHardwareConnected(deviceId: String) {
        runCatching {
            withTimeout(HW_RECONNECT_TIMEOUT) {
                hwWalletRepo.ensureConnected(deviceId).getOrThrow()
            }
        }.getOrElse {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            if (it.isTrezorUserCancellation()) throw it
            throw HardwareReconnectError(it)
        }
    }

    private suspend fun composeHardwareFundingTransaction(
        deviceId: String,
        address: String,
        sats: ULong,
        satsPerVByte: ULong,
    ): HwFundingTransaction = runCatching {
        withTimeout(HW_COMPOSE_TIMEOUT) {
            hwWalletRepo.composeFundingTransaction(
                deviceId = deviceId,
                address = address,
                sats = sats,
                satsPerVByte = satsPerVByte,
            ).getOrThrow()
        }
    }.getOrElse {
        if (it is CancellationException && it !is TimeoutCancellationException) throw it
        throw HardwareFundingError(it)
    }

    @Suppress("ThrowsCount")
    private suspend fun signHardwareFunding(
        deviceId: String,
        funding: HwFundingTransaction,
    ): HwFundingSignedTx {
        return runCatching {
            withTimeout(HW_SIGN_TIMEOUT) {
                hwWalletRepo.signFunding(
                    deviceId = deviceId,
                    funding = funding,
                ).getOrThrow()
            }
        }.getOrElse {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            if (it is TimeoutCancellationException) {
                hwWalletRepo.disconnectStaleSession(deviceId)
                throw HardwareSigningTimeoutError(it)
            }
            throw it
        }
    }

    private suspend fun broadcastHardwareFunding(
        signedTx: HwFundingSignedTx,
    ): HwFundingBroadcastResult {
        return runCatching {
            withTimeout(HW_BROADCAST_TIMEOUT) {
                hwWalletRepo.broadcastFunding(signedTx).getOrThrow()
            }
        }.getOrElse {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            throw HardwareBroadcastError(it)
        }
    }

    private suspend fun handleHardwareTransferFailure(e: Throwable, deviceId: String) {
        if (e.isTrezorUserCancellation()) {
            Logger.info("Hardware transfer cancelled on device for '$deviceId'", context = TAG)
            return
        }
        if (e.isTrezorDeviceBusy()) {
            Logger.warn("Blocked hardware transfer for locked or busy Trezor '$deviceId'", e, context = TAG)
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.hardware__device_busy),
            )
            return
        }
        if (e.isTrezorFirmwareError()) {
            Logger.warn("Received Trezor firmware error for '$deviceId'", e, context = TAG)
            showHardwareReconnectRequiredError()
            return
        }
        when (e) {
            is HardwareBroadcastError -> {
                Logger.warn("Hardware funding transaction is signed but not confirmed broadcast", e, context = TAG)
                showHardwareBroadcastError(e)
            }
            is HardwareReconnectError -> {
                Logger.error("Failed to reconnect hardware device", e, context = TAG)
                showHardwareReconnectError(deviceId)
            }
            is HardwareSigningTimeoutError -> {
                Logger.warn("Timed out hardware transfer signing for '$deviceId'", e, context = TAG)
                showHardwareTimeoutError()
            }
            is HardwareFundingError -> {
                Logger.warn("Failed to compose hardware transfer funding for '$deviceId'", e, context = TAG)
                if (e.isHardwareInteractionTimeout()) {
                    showHardwareConnectivityError()
                } else {
                    showHardwareFundingError(e)
                }
            }
            else -> {
                Logger.error("Hardware transfer failed", e, context = TAG)
                ToastEventBus.send(e)
            }
        }
    }

    private suspend fun showHardwareBroadcastError(error: HardwareBroadcastError) {
        if (!error.isBroadcastConnectivityFailure()) {
            pendingHwFundingBroadcast = null
            _spendingUiState.update { it.copy(hasPendingHwBroadcast = false) }
            ToastEventBus.send(error.cause ?: error)
            return
        }
        showHardwareConnectivityError()
    }

    private suspend fun showHardwareConnectivityError() {
        ToastEventBus.send(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.other__connection_issue),
            description = context.getString(R.string.other__connection_issues_explain),
        )
    }

    private fun Throwable.isHardwareInteractionTimeout(): Boolean =
        this is HardwareFundingError &&
            generateSequence<Throwable>(this) { it.cause }.any { it is TimeoutCancellationException }

    private suspend fun showHardwareReconnectError(deviceId: String) {
        if (hwWalletRepo.isKnownBluetoothDevice(deviceId)) {
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.hardware__connect_title),
                description = context.getString(R.string.hardware__connect_error),
            )
            return
        }
        showHardwareReconnectRequiredError()
    }

    private suspend fun showHardwareReconnectRequiredError() {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.lightning__transfer_hw__reconnect_error_title),
            description = context.getString(R.string.lightning__transfer_hw__reconnect_error_description),
        )
    }

    private suspend fun showHardwareTimeoutError() {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.common__error),
            description = context.getString(R.string.wallet__toast_payment_failed_timeout),
        )
    }

    private suspend fun showHardwareFundingError(e: Throwable) {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.common__error),
            description = e.cause?.message ?: e.message ?: context.getString(R.string.common__error_body),
        )
    }

    private suspend fun hwFundingFeeReserve(balanceSats: ULong): ULong {
        val satsPerVByte = fetchHwFundingSatsPerVByte().getOrNull()
            ?: return hwFundingFallbackFeeReserve(balanceSats)
        return satsPerVByte.safe() * HW_FUNDING_TX_VBYTES.safe()
    }

    private fun hwFundingFallbackFeeReserve(balanceSats: ULong): ULong {
        val minReserve = HW_FUNDING_FALLBACK_SATS_PER_VBYTE.safe() * HW_FUNDING_TX_VBYTES.safe()
        val fallback = (balanceSats.toDouble() * Defaults.fallbackFeePercent).toULong()
        return maxOf(minReserve, fallback)
    }

    private suspend fun hwFundingSatsPerVByte(): ULong =
        fetchHwFundingSatsPerVByte().getOrDefault(HW_FUNDING_FALLBACK_SATS_PER_VBYTE)

    private suspend fun fetchHwFundingSatsPerVByte(): Result<ULong> {
        val speed = settingsStore.data.first().defaultTransactionSpeed
        return lightningRepo.getFeeRateForSpeed(speed)
    }

    // endregion

    // region Balance Calc

    fun updateTransferValues(clientBalanceSat: ULong) {
        val options = blocktankRepo.calculateLiquidityOptions(clientBalanceSat).getOrNull()
        _transferValues.value = if (options != null) {
            TransferValues(
                defaultLspBalance = options.defaultLspBalanceSat,
                minLspBalance = options.minLspBalanceSat,
                maxLspBalance = options.maxLspBalanceSat,
                maxClientBalance = options.maxClientBalanceSat,
            )
        } else {
            TransferValues()
        }
    }

    // endregion

    // region Savings

    private var channelsToClose = emptyList<ChannelDetails>()

    fun setSelectedChannelIds(channelIds: Set<String>) {
        _selectedChannelIdsState.update { channelIds }
    }

    fun onTransferToSavingsConfirm(channels: List<ChannelDetails>) {
        _selectedChannelIdsState.update { emptySet() }
        channelsToClose = channels
    }

    /** Closes the channels selected earlier, pending closure */
    suspend fun closeSelectedChannels() = closeChannels(channelsToClose)

    fun separateTrustedChannels(
        channels: List<ChannelDetails>,
    ): Pair<List<ChannelDetails>, List<ChannelDetails>> = lightningRepo.separateTrustedChannels(channels)

    private suspend fun closeChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
        lightningRepo.awaitPeerConnected()
        val channelsFailedToClose = coroutineScope {
            channels.map { channel ->
                async {
                    lightningRepo.closeChannel(channel)
                        .onSuccess {
                            transferRepo.createTransfer(
                                type = TransferType.COOP_CLOSE,
                                amountSats = channel.amountOnClose.toLong(),
                                channelId = channel.channelId,
                                fundingTxId = channel.fundingTxo?.txid,
                            )
                        }
                        .fold(
                            onSuccess = { null },
                            onFailure = { channel }
                        )
                }
            }.awaitAll()
        }.filterNotNull()

        return channelsFailedToClose
    }

    private var coopCloseRetryJob: Job? = null

    /** Retry to coop close the channel(s) for 30 min */
    fun startCoopCloseRetries(
        channels: List<ChannelDetails>,
        onGiveUp: () -> Unit,
        onTransferUnavailable: () -> Unit,
    ) {
        val startTimeMs = clock.now().toEpochMilliseconds()
        channelsToClose = channels
        coopCloseRetryJob?.cancel()

        coopCloseRetryJob = viewModelScope.launch {
            val giveUpTime = startTimeMs + GIVE_UP_MS
            while (isActive && System.currentTimeMillis() < giveUpTime) {
                Logger.info("Trying coop close...")
                val channelsFailedToCoopClose = closeChannels(channelsToClose)

                if (channelsFailedToCoopClose.isEmpty()) {
                    channelsToClose = emptyList()
                    Logger.info("Coop close success.")
                    return@launch
                } else {
                    channelsToClose = channelsFailedToCoopClose
                    Logger.info("Coop close failed: ${channelsFailedToCoopClose.map { it.channelId }}")
                }

                delay(RETRY_INTERVAL_MS)
            }

            Logger.info("Giving up on coop close. Checking if force close is possible.", context = TAG)

            // Check if any channels can be force closed (filter out trusted peers)
            val (_, nonTrustedChannels) = lightningRepo.separateTrustedChannels(channelsToClose)

            if (nonTrustedChannels.isNotEmpty()) {
                onGiveUp()
            } else {
                Logger.warn("All channels are with trusted peers. Cannot force close.", context = TAG)
                channelsToClose = emptyList()
                onTransferUnavailable()
            }
        }
    }

    fun forceTransfer(onComplete: () -> Unit) = viewModelScope.launch {
        _isForceTransferLoading.value = true
        runCatching {
            // Filter out trusted peer channels (cannot force close LSP channels)
            val (trustedChannels, nonTrustedChannels) = lightningRepo.separateTrustedChannels(channelsToClose)

            if (trustedChannels.isNotEmpty()) {
                Logger.warn("Skipping ${trustedChannels.size} trusted peer channel(s)", context = TAG)
            }

            if (nonTrustedChannels.isEmpty()) {
                channelsToClose = emptyList()
                Logger.error("Cannot force close channels with trusted peer", context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__force_failed_title),
                    description = context.getString(R.string.lightning__force_failed_msg)
                )
                return@runCatching
            }

            val failedChannels = forceCloseChannels(nonTrustedChannels)

            // Remove successfully closed channels and trusted peer channels from the list
            val successfulChannelIds = nonTrustedChannels
                .filterNot { channel -> failedChannels.any { it.channelId == channel.channelId } }
                .map { it.channelId }
                .toSet()
            val trustedChannelIds = trustedChannels.map { it.channelId }.toSet()
            channelsToClose = channelsToClose.filterNot {
                it.channelId in successfulChannelIds || it.channelId in trustedChannelIds
            }

            if (failedChannels.isEmpty()) {
                Logger.info("Force close initiated successfully for all channels", context = TAG)
                val initMsg = context.getString(R.string.lightning__force_init_msg)
                val skippedMsg = context.getString(R.string.lightning__force_channels_skipped)
                val description = if (trustedChannels.isNotEmpty()) "$initMsg $skippedMsg" else initMsg
                ToastEventBus.send(
                    type = Toast.ToastType.LIGHTNING,
                    title = context.getString(R.string.lightning__force_init_title),
                    description = description,
                )
            } else {
                Logger.error("Force close failed for ${failedChannels.size} channels", context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__force_failed_title),
                    description = context.getString(R.string.lightning__force_failed_msg)
                )
            }
        }.onFailure {
            Logger.error("Force close failed", e = it, context = TAG)
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.lightning__force_failed_title),
                description = context.getString(R.string.lightning__force_failed_msg)
            )
        }
        _isForceTransferLoading.value = false
        onComplete()
    }

    private suspend fun forceCloseChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
        val channelsFailedToClose = coroutineScope {
            channels.map { channel ->
                async {
                    lightningRepo.closeChannel(channel, force = true)
                        .onSuccess {
                            transferRepo.createTransfer(
                                type = TransferType.FORCE_CLOSE,
                                amountSats = channel.amountOnClose.toLong(),
                                channelId = channel.channelId,
                                fundingTxId = channel.fundingTxo?.txid,
                            )
                        }
                        .onFailure { e -> Logger.error("Error force closing channel: ${channel.channelId}", e) }
                        .fold(
                            onSuccess = { null },
                            onFailure = { channel },
                        )
                }
            }.awaitAll()
        }.filterNotNull()

        return channelsFailedToClose
    }

    // endregion

    companion object {
        private const val TAG = "TransferViewModel"
        private const val TRANSFER_SEND_ALL_THRESHOLD_SATS = 1000
        private const val MIN_STEP_DELAY_MS = 500L
        private const val POLL_INTERVAL_MS = 2_500L
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** Conservative vbyte reserve for multi-input hardware funding before exact compose runs. */
        private const val HW_FUNDING_TX_VBYTES = 1_200uL

        /** Minimum fallback fee rate when fee estimates are temporarily unavailable. */
        private const val HW_FUNDING_FALLBACK_SATS_PER_VBYTE = 1uL

        /** Upper bound for reconnecting a known device before the UI asks for reconnect. */
        private val HW_RECONNECT_TIMEOUT = 30.seconds

        /** Upper bound for exact hardware funding composition before signing starts. */
        private val HW_COMPOSE_TIMEOUT = 45.seconds

        /** Upper bound for one hardware signing attempt before the UI releases the button. */
        private val HW_SIGN_TIMEOUT = 120.seconds

        /** Upper bound for broadcasting a signed hardware funding transaction. */
        private val HW_BROADCAST_TIMEOUT = 120.seconds
        const val LN_SETUP_STEP_0 = 0
        const val LN_SETUP_STEP_1 = 1
        const val LN_SETUP_STEP_2 = 2
        const val LN_SETUP_STEP_3 = 3
    }
}

private class HardwareReconnectError(cause: Throwable) : AppError(cause)
private class HardwareFundingError(cause: Throwable) : AppError(cause)
private class HardwareSigningTimeoutError(cause: Throwable) : AppError(cause)
private class HardwareBroadcastError(cause: Throwable) : AppError(cause)

private data class PendingHwFundingBroadcast(
    val orderId: String,
    val deviceId: String,
    val address: String,
    val amountSats: ULong,
    val signedTx: HwFundingSignedTx,
) {
    fun matches(order: IBtOrder, deviceId: String, address: String): Boolean =
        orderId == order.id &&
            this.deviceId == deviceId &&
            this.address == address &&
            amountSats == order.feeSat
}

// region state
data class TransferToSpendingUiState(
    val order: IBtOrder? = null,
    val defaultOrder: IBtOrder? = null,
    val isAdvanced: Boolean = false,
    val maxAllowedToSend: Long = 0,
    val balanceAfterFee: Long = 0,
    val quarterAmount: Long = 0,
    val isLoading: Boolean = false,
    val isSigning: Boolean = false,
    val hasPendingHwBroadcast: Boolean = false,
    val receivingAmount: Long = 0,
    val feeEstimate: Long? = null,
)

data class TransferValues(
    val defaultLspBalance: ULong = 0u,
    val minLspBalance: ULong = 0u,
    val maxLspBalance: ULong = 0u,
    val maxClientBalance: ULong = 0u,
)

sealed interface TransferEffect {
    data object OnOrderCreated : TransferEffect
    data object OnHwTxSigned : TransferEffect
    data class ToastException(val e: Throwable) : TransferEffect
    data class ToastError(val title: String, val description: String) : TransferEffect
}
// endregion
