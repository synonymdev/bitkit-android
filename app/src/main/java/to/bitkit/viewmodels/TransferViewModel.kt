package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwapEvent
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.CoinSelectionAlgorithm
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import org.lightningdevkit.ldknode.SpendableUtxo
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
import to.bitkit.ext.toUserMessage
import to.bitkit.models.HwFundingBroadcastResult
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.models.HwFundingTransaction
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwPassphraseMismatchError
import to.bitkit.repositories.HwPassphraseRequiredError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.BoltzService
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
    private val boltzService: BoltzService,
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
    private var hwFeeEstimateJob: Job? = null
    private var confirmFeeJob: Job? = null
    private var confirmPayJob: Job? = null
    private var spendingConfirmFundingPlan: SpendingConfirmFundingPlan? = null
    private var pendingHwFundingBroadcast: PendingHwFundingBroadcast? = null
    private var activeHwTransferWalletId: String? = null

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
                hwFeeEstimateJob?.cancel()
                hwFeeEstimateJob = null
                _spendingUiState.update {
                    it.copy(
                        order = newOrder,
                        defaultOrder = oldOrder,
                        isAdvanced = true,
                        hwMiningFeeSats = 0uL,
                    )
                }
                setTransferEffect(TransferEffect.OnOrderCreated)
            }.onFailure { e ->
                setTransferEffect(TransferEffect.ToastException(e))
            }
        }
    }

    /**
     * Match iOS SpendingConfirm.task: compute real mining fee + drain decision before swipe,
     * so confirm UI can show fees up-front.
     */
    fun prepareSpendingConfirmFunding(order: IBtOrder) {
        confirmFeeJob?.cancel()
        confirmFeeJob = viewModelScope.launch {
            _spendingUiState.update {
                it.copy(isConfirmFeeReady = false, miningFeeSats = 0uL)
            }
            buildSpendingConfirmFundingPlan(order)
                .onSuccess { plan ->
                    spendingConfirmFundingPlan = plan
                    _spendingUiState.update {
                        it.copy(
                            isConfirmFeeReady = true,
                            miningFeeSats = plan.miningFeeSats,
                            shouldUseSendAll = plan.shouldUseSendAll,
                        )
                    }
                }
                .onFailure {
                    spendingConfirmFundingPlan = null
                    _spendingUiState.update {
                        it.copy(isConfirmFeeReady = false, miningFeeSats = 0uL, shouldUseSendAll = false)
                    }
                    Logger.error("Failed to prepare transfer funding fee", it, context = TAG)
                    if (it is AppError) {
                        ToastEventBus.send(it)
                    } else {
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__try_again),
                        )
                    }
                }
        }
    }

    /** Pays for the order using the prepared confirm plan and starts watching it. */
    fun onTransferToSpendingConfirm(order: IBtOrder) {
        if (confirmPayJob?.isActive == true) return

        confirmPayJob = viewModelScope.launch {
            _spendingUiState.update { it.copy(isConfirmPaying = true) }
            try {
                val paid = runSuspendCatching {
                    paySpendingConfirmOrder(order)
                }.onFailure {
                    Logger.error("Failed to pay spending confirm order", it, context = TAG)
                    ToastEventBus.send(it)
                }.getOrDefault(false)

                if (paid) {
                    // Emit from this job (not a nested launch) so navigation is not raced/lost.
                    transferEffects.emit(TransferEffect.OnSpendingFundingPaid)
                } else {
                    _spendingUiState.update { it.copy(isConfirmPaying = false) }
                }
            } finally {
                confirmPayJob = null
            }
        }
    }

    private suspend fun paySpendingConfirmOrder(order: IBtOrder): Boolean {
        val plan = spendingConfirmFundingPlan?.takeIf { it.orderId == order.id }
            ?: buildSpendingConfirmFundingPlan(order).getOrElse {
                Logger.error("Failed to prepare transfer funding fee", it, context = TAG)
                ToastEventBus.send(it)
                return false
            }.also { spendingConfirmFundingPlan = it }

        Logger.debug(
            "BT confirm: spendable=${plan.spendableBalance}, feeSat=${order.feeSat}, " +
                "miningFee=${plan.miningFeeSats}, selectedUtxos=${plan.selectedUtxos?.size}, " +
                "sendAll=${plan.shouldUseSendAll}",
            context = TAG,
        )

        if (plan.shouldUseSendAll && plan.maxSendable < order.feeSat) {
            Logger.error(
                "Insufficient balance for transfer: maxSendable=${plan.maxSendable}, " +
                    "orderFee=${order.feeSat}",
                context = TAG,
            )
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.other__pay_insufficient_savings),
            )
            return false
        }

        val address = order.payment?.onchain?.address.orEmpty()
        return lightningRepo
            .sendOnChain(
                address = address,
                sats = order.feeSat,
                speed = TransactionSpeed.Fast,
                utxosToSpend = if (plan.shouldUseSendAll) null else plan.selectedUtxos,
                isTransfer = true,
                channelId = order.channel?.shortChannelId,
                isMaxAmount = plan.shouldUseSendAll,
            )
            .onSuccess { txId ->
                // Survive ViewModel clearance between broadcast and paid-order cache write.
                withContext(NonCancellable) {
                    fundPaidOrder(
                        order = order,
                        txId = txId,
                        txTotalSats = if (plan.shouldUseSendAll) {
                            plan.spendableBalance
                        } else {
                            order.feeSat.safe() + plan.miningFeeSats.safe()
                        },
                        preTransferOnchainSats = plan.totalOnchainBalance,
                    )
                }
            }
            .onFailure { ToastEventBus.send(it) }
            .isSuccess
    }

    private suspend fun buildSpendingConfirmFundingPlan(
        order: IBtOrder,
    ): Result<SpendingConfirmFundingPlan> = runSuspendCatching {
        val address = order.payment?.onchain?.address.orEmpty()
        require(address.isNotEmpty()) { "Order payment onchain address is nil" }

        val speed = TransactionSpeed.Fast
        val balanceDetails = lightningRepo.getBalancesAsync().getOrThrow()
        val spendableBalance = balanceDetails.spendableOnchainBalanceSats
        val totalOnchainBalance = balanceDetails.totalOnchainBalanceSats
        val satsPerVByte = lightningRepo.getFeeRateForSpeed(speed).getOrThrow()

        // Match iOS SpendingConfirm: normal coin selection + fee first; drain only for real dust.
        resolveNormalSpendingConfirmFunding(
            order = order,
            address = address,
            speed = speed,
            satsPerVByte = satsPerVByte,
            spendableBalance = spendableBalance,
            totalOnchainBalance = totalOnchainBalance,
        ) ?: resolveSendAllSpendingConfirmFunding(
            order = order,
            address = address,
            speed = speed,
            spendableBalance = spendableBalance,
            totalOnchainBalance = totalOnchainBalance,
        )
    }

    private suspend fun resolveNormalSpendingConfirmFunding(
        order: IBtOrder,
        address: String,
        speed: TransactionSpeed,
        satsPerVByte: ULong,
        spendableBalance: ULong,
        totalOnchainBalance: ULong,
    ): SpendingConfirmFundingPlan? {
        val utxos = lightningRepo.selectUtxosWithAlgorithm(
            targetAmountSats = order.feeSat,
            satsPerVByte = satsPerVByte,
            algorithm = CoinSelectionAlgorithm.LARGEST_FIRST,
        ).getOrElse {
            Logger.warn("Normal coin selection failed, using sendAll", it, context = TAG)
            return null
        }

        val normalFee = lightningRepo.calculateTotalFee(
            amountSats = order.feeSat,
            address = address,
            speed = speed,
            utxosToSpend = utxos,
        ).getOrElse {
            Logger.warn("Failed to estimate transfer funding fee", it, context = TAG)
            0uL
        }
        val totalInput = utxos.fold(0uL) { acc, utxo -> acc.safe() + utxo.valueSats.safe() }
        if (wouldCreateDustChange(totalInput = totalInput, amountSats = order.feeSat, normalFee = normalFee)) {
            return null
        }

        return SpendingConfirmFundingPlan(
            orderId = order.id,
            miningFeeSats = normalFee,
            shouldUseSendAll = false,
            selectedUtxos = utxos,
            spendableBalance = spendableBalance,
            totalOnchainBalance = totalOnchainBalance,
            maxSendable = 0uL,
        )
    }

    private suspend fun resolveSendAllSpendingConfirmFunding(
        order: IBtOrder,
        address: String,
        speed: TransactionSpeed,
        spendableBalance: ULong,
        totalOnchainBalance: ULong,
    ): SpendingConfirmFundingPlan {
        val sendAllFee = lightningRepo.estimateSendAllFee(
            address = address,
            speed = speed,
        ).getOrThrow()
        val maxSendable = spendableBalance.safe() - sendAllFee.safe()
        if (maxSendable < order.feeSat) {
            throw AppError(context.getString(R.string.other__pay_insufficient_savings))
        }

        return SpendingConfirmFundingPlan(
            orderId = order.id,
            miningFeeSats = sendAllFee,
            shouldUseSendAll = true,
            selectedUtxos = null,
            spendableBalance = spendableBalance,
            totalOnchainBalance = totalOnchainBalance,
            maxSendable = maxSendable,
        )
    }

    private fun wouldCreateDustChange(
        totalInput: ULong,
        amountSats: ULong,
        normalFee: ULong,
        dustLimit: ULong = Defaults.dustLimit.toULong(),
    ): Boolean {
        val expectedChange = totalInput.toLong() - amountSats.toLong() - normalFee.toLong()
        return expectedChange in 0 until dustLimit.toLong()
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
        activityWalletId: String = WalletScope.default,
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
                walletId = activityWalletId,
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
        hwFeeEstimateJob?.cancel()
        hwFeeEstimateJob = null
        _spendingUiState.update {
            it.copy(
                order = order,
                isAdvanced = false,
                defaultOrder = null,
                hasPendingHwBroadcast = false,
                hwMiningFeeSats = 0uL,
            )
        }
        setTransferEffect(TransferEffect.OnOrderCreated)
    }

    private fun updateAvailableAmount() {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            awaitNodeRunning()

            // Match iOS: start from raw spendable (not maxSendOnchainSats — that already reserved
            // a default-tier send-all fee), then subtract exactly one fast mining fee.
            val spendable = lightningRepo.getBalancesAsync().getOrNull()?.spendableOnchainBalanceSats
                ?: 0uL
            val miningFee = lightningRepo.estimateSendAllFee(
                speed = TransactionSpeed.Fast,
            ).getOrElse {
                Logger.warn("Failed to estimate transfer mining fee reserve", it, context = TAG)
                (spendable.toDouble() * Defaults.fallbackFeePercent).toULong()
            }
            val availableAmount = spendable.safe() - miningFee.safe()

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
            val maxClientBalance = resolveAffordableClientBalance(
                availableAmount = availableAmount,
                receivingAmount = receivingAmount,
                quotedBalance = cappedClientBalance,
                quotedFee = lspFees,
            )
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

    /**
     * Largest client balance that still covers its own order fee, settled against live quotes.
     *
     * [quotedFee] prices [quotedBalance], but the advertised max is usually a different balance, and
     * the LSP charges the client and LSP sides of the channel at different rates. The fee at that
     * other balance can therefore be higher, leaving an order the user cannot fund. Each round
     * re-quotes and steps down by the shortfall; the fee moves by a small fraction of a satoshi per
     * satoshi of balance, so this settles well within [MAX_AFFORDABILITY_ROUNDS].
     */
    private suspend fun resolveAffordableClientBalance(
        availableAmount: ULong,
        receivingAmount: ULong,
        quotedBalance: ULong,
        quotedFee: ULong,
    ): ULong {
        var candidate = quotedBalance
        var fee = quotedFee
        repeat(MAX_AFFORDABILITY_ROUNDS) {
            if (candidate.safe() + fee.safe() <= availableAmount) return candidate
            candidate = availableAmount.safe() - fee.safe()
            fee = blocktankRepo.estimateOrderFee(
                spendingBalanceSats = candidate,
                receivingBalanceSats = receivingAmount,
            ).getOrNull()?.let { it.networkFeeSat.safe() + it.serviceFeeSat.safe() } ?: return candidate
        }
        return if (candidate.safe() + fee.safe() <= availableAmount) {
            candidate
        } else {
            availableAmount.safe() - fee.safe()
        }
    }

    fun onUseDefaultLspBalanceClick() {
        val defaultOrder = _spendingUiState.value.defaultOrder
        hwFeeEstimateJob?.cancel()
        hwFeeEstimateJob = null
        _spendingUiState.update {
            it.copy(
                order = defaultOrder,
                defaultOrder = null,
                isAdvanced = false,
                hwMiningFeeSats = 0uL,
            )
        }
    }

    fun resetSpendingState() {
        hwTransferSignJob?.cancel()
        hwTransferSignJob = null
        hwFeeEstimateJob?.cancel()
        hwFeeEstimateJob = null
        confirmFeeJob?.cancel()
        confirmFeeJob = null
        // Do not cancel confirmPayJob: broadcast + paid-order cache must finish.
        spendingConfirmFundingPlan = null
        pendingHwFundingBroadcast = null
        activeHwTransferWalletId = null
        _spendingUiState.update { TransferToSpendingUiState() }
        _transferValues.update { TransferValues() }
    }

    fun cancelHardwareTransfer() {
        _spendingUiState.update { it.copy(isHwPassphraseRequired = false, isVerifyingHwPassphrase = false) }
        if (pendingHwFundingBroadcast != null) return
        val walletId = activeHwTransferWalletId
        hwTransferSignJob?.cancel()
        hwTransferSignJob = null
        hwFeeEstimateJob?.cancel()
        hwFeeEstimateJob = null
        _spendingUiState.update { it.copy(isSigning = false) }
        if (walletId != null) {
            viewModelScope.launch {
                hwWalletRepo.disconnectStaleSession(walletId)
            }
        }
    }

    // endregion

    // region Hardware Wallet

    fun updateHwLimits(walletId: String) {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            val account = hwWalletRepo.getFundingAccount(walletId).getOrElse {
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
    fun warmUpHardwareConnection(walletId: String) {
        hwWalletRepo.warmUpKnownDevice(walletId)
    }

    /** Best-effort offline mining-fee estimate for the Sign screen (xpub compose, no device session). */
    fun updateHwFundingFeeEstimate(order: IBtOrder, walletId: String) {
        hwFeeEstimateJob?.cancel()
        hwFeeEstimateJob = viewModelScope.launch {
            if (_spendingUiState.value.hasPendingHwBroadcast) return@launch
            val address = order.payment?.onchain?.address.orEmpty()
            if (address.isEmpty()) return@launch
            val orderId = order.id

            runSuspendCatching {
                val satsPerVByte = hwFundingSatsPerVByte()
                hwWalletRepo.composeFundingTransaction(
                    walletId = walletId,
                    address = address,
                    sats = order.feeSat,
                    satsPerVByte = satsPerVByte,
                ).getOrThrow().miningFeeSats
            }.onSuccess { miningFeeSats ->
                _spendingUiState.update { state ->
                    val activeOrderId = state.order?.id
                    if ((activeOrderId != null && activeOrderId != orderId) || state.hasPendingHwBroadcast) {
                        state
                    } else {
                        state.copy(hwMiningFeeSats = miningFeeSats)
                    }
                }
            }.onFailure {
                Logger.debug(
                    "Skipped offline hardware funding fee estimate for '$walletId'",
                    context = TAG,
                )
            }
        }
    }

    fun onTransferToSpendingHwConfirm(order: IBtOrder, walletId: String) {
        if (hwTransferSignJob?.isActive == true) return

        activeHwTransferWalletId = walletId
        hwTransferSignJob = viewModelScope.launch {
            // A hidden wallet whose session is gone can only be reopened with its passphrase, and
            // the device would otherwise sign from whichever wallet the current session holds.
            // Rebroadcasting an already signed transaction never reaches the device, so it must not
            // be held behind that prompt; a different order still asks.
            val address = order.payment?.onchain?.address.orEmpty()
            val isBroadcastRetry = pendingHwFundingBroadcast?.matches(order, walletId, address) == true
            if (!isBroadcastRetry && hwWalletRepo.needsPassphrase(walletId)) {
                _spendingUiState.update { it.copy(isHwPassphraseRequired = true) }
                hwTransferSignJob = null
                return@launch
            }
            _spendingUiState.update { it.copy(isSigning = true) }
            try {
                if (address.isEmpty()) {
                    ToastEventBus.send(type = Toast.ToastType.ERROR, title = context.getString(R.string.common__error))
                    return@launch
                }
                signAndBroadcastHardwareFunding(order, walletId, address)
                    .onSuccess { result ->
                        runSuspendCatching {
                            fundPaidOrder(
                                order = order,
                                txId = result.txId,
                                createTransferActivity = true,
                                fee = result.miningFeeSats,
                                feeRate = result.feeRate,
                                activityWalletId = walletId,
                            )
                        }.onSuccess {
                            pendingHwFundingBroadcast = null
                            activeHwTransferWalletId = null
                            _spendingUiState.update { it.copy(hasPendingHwBroadcast = false) }
                            setTransferEffect(TransferEffect.OnHwTxSigned)
                        }.onFailure {
                            Logger.error("Failed to record broadcast hardware transfer", it, context = TAG)
                            handleHardwareTransferFailure(it, walletId)
                        }
                    }
                    .onFailure { handleHardwareTransferFailure(it, walletId) }
            } finally {
                _spendingUiState.update { it.copy(isSigning = false) }
                hwTransferSignJob = null
            }
        }
    }

    /**
     * Reopens the hidden wallet with the entered passphrase and, once its accounts prove it is the
     * wallet the transfer is for, continues into signing. The passphrase is passed straight through
     * to the device session; it is never kept in UI state.
     */
    fun onHwPassphraseSubmit(order: IBtOrder, walletId: String, passphrase: String) {
        if (passphrase.isEmpty() || hwTransferSignJob?.isActive == true) return

        hwTransferSignJob = viewModelScope.launch {
            _spendingUiState.update { it.copy(isVerifyingHwPassphrase = true) }
            val result = hwWalletRepo.reconnectWithPassphrase(walletId = walletId, passphrase = passphrase)
            _spendingUiState.update { it.copy(isVerifyingHwPassphrase = false) }
            hwTransferSignJob = null
            result
                .onSuccess {
                    // The prompt can be swiped away while the device is still reopening the wallet,
                    // and the confirm below starts a new job that a late cancel would not reach.
                    if (!_spendingUiState.value.isHwPassphraseRequired) return@launch
                    _spendingUiState.update { it.copy(isHwPassphraseRequired = false) }
                    onTransferToSpendingHwConfirm(order, walletId)
                }
                .onFailure { handleHardwarePassphraseFailure(it, walletId) }
        }
    }

    /** Backing out of the prompt also drops the reopen it started, so no signature is requested. */
    fun onHwPassphraseDismiss() {
        hwTransferSignJob?.cancel()
        hwTransferSignJob = null
        _spendingUiState.update { it.copy(isHwPassphraseRequired = false, isVerifyingHwPassphrase = false) }
    }

    private suspend fun handleHardwarePassphraseFailure(e: Throwable, walletId: String) {
        if (e is HwPassphraseMismatchError) {
            Logger.warn("Rejected wrong passphrase for hardware wallet '$walletId'", context = TAG)
            ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.hardware__passphrase_mismatch),
            )
            return
        }
        handleHardwareTransferFailure(e, walletId)
    }

    private suspend fun signAndBroadcastHardwareFunding(
        order: IBtOrder,
        walletId: String,
        address: String,
    ): Result<HwFundingBroadcastResult> {
        val result = runCatching {
            val signedTx = pendingHwFundingBroadcast
                ?.takeIf { it.matches(order, walletId, address) }
                ?.signedTx
                ?.also { pending ->
                    _spendingUiState.update { state -> state.copy(hwMiningFeeSats = pending.miningFeeSats) }
                }
                ?: prepareSignedHardwareFunding(order, walletId, address).also {
                    pendingHwFundingBroadcast = PendingHwFundingBroadcast(
                        orderId = order.id,
                        walletId = walletId,
                        address = address,
                        amountSats = order.feeSat,
                        signedTx = it,
                    )
                    _spendingUiState.update { state ->
                        state.copy(
                            hasPendingHwBroadcast = true,
                            hwMiningFeeSats = it.miningFeeSats,
                        )
                    }
                }
            broadcastHardwareFunding(signedTx)
        }
        result.exceptionOrNull()?.rethrowIfCancellation()
        return result
    }

    private suspend fun prepareSignedHardwareFunding(
        order: IBtOrder,
        walletId: String,
        address: String,
    ): HwFundingSignedTx {
        ensureHardwareConnected(walletId)
        val satsPerVByte = hwFundingSatsPerVByte()
        val funding = composeHardwareFundingTransaction(
            walletId = walletId,
            address = address,
            sats = order.feeSat,
            satsPerVByte = satsPerVByte,
        )
        _spendingUiState.update { it.copy(hwMiningFeeSats = funding.miningFeeSats) }
        return signHardwareFunding(walletId, funding)
    }

    @Suppress("ThrowsCount")
    private suspend fun ensureHardwareConnected(walletId: String) {
        runCatching {
            withTimeout(HW_RECONNECT_TIMEOUT) {
                hwWalletRepo.ensureConnected(walletId).getOrThrow()
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            if (it.isTrezorUserCancellation()) throw it
            throw HardwareReconnectError(it)
        }
    }

    private suspend fun composeHardwareFundingTransaction(
        walletId: String,
        address: String,
        sats: ULong,
        satsPerVByte: ULong,
    ): HwFundingTransaction = runCatching {
        withTimeout(HW_COMPOSE_TIMEOUT) {
            hwWalletRepo.composeFundingTransaction(
                walletId = walletId,
                address = address,
                sats = sats,
                satsPerVByte = satsPerVByte,
            ).getOrThrow()
        }
    }.getOrElse {
        it.rethrowIfCancellation()
        throw HardwareFundingError(it)
    }

    @Suppress("ThrowsCount")
    private suspend fun signHardwareFunding(
        walletId: String,
        funding: HwFundingTransaction,
    ): HwFundingSignedTx {
        return runCatching {
            withTimeout(HW_SIGN_TIMEOUT) {
                hwWalletRepo.signFunding(
                    walletId = walletId,
                    funding = funding,
                ).getOrThrow()
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            if (it is TimeoutCancellationException) {
                hwWalletRepo.disconnectStaleSession(walletId)
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
            it.rethrowIfCancellation()
            throw HardwareBroadcastError(it)
        }
    }

    private suspend fun handleHardwareTransferFailure(e: Throwable, walletId: String) {
        if (e.isTrezorUserCancellation()) {
            Logger.info("Hardware transfer cancelled on device for '$walletId'", context = TAG)
            return
        }
        if (generateSequence(e) { it.cause }.any { it is HwPassphraseRequiredError }) {
            // The device is open on another identity and only the passphrase reopens this one.
            Logger.info("Asking for the passphrase to reopen hardware wallet '$walletId'", context = TAG)
            _spendingUiState.update { it.copy(isHwPassphraseRequired = true) }
            return
        }
        if (e.isTrezorDeviceBusy()) {
            Logger.warn("Blocked hardware transfer for locked or busy Trezor '$walletId'", e, context = TAG)
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.hardware__device_busy),
            )
            return
        }
        if (e.isTrezorFirmwareError()) {
            Logger.warn("Received Trezor firmware error for '$walletId'", e, context = TAG)
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
                showHardwareReconnectError(walletId)
            }
            is HardwareSigningTimeoutError -> {
                Logger.warn("Timed out hardware transfer signing for '$walletId'", e, context = TAG)
                showHardwareTimeoutError()
            }
            is HardwareFundingError -> {
                Logger.warn("Failed to compose hardware transfer funding for '$walletId'", e, context = TAG)
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

    private suspend fun showHardwareReconnectError(walletId: String) {
        if (hwWalletRepo.isKnownBluetoothDevice(walletId)) {
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
            description = context.getString(R.string.wallet__payment_timeout),
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

    private suspend fun fetchHwFundingSatsPerVByte(): Result<ULong> =
        lightningRepo.getFeeRateForSpeed(TransactionSpeed.Fast)

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

    /**
     * How the LN -> onchain "transfer to savings" is executed. Closing a channel is the
     * default because it always works; swapping funds out keeps channels open and is used
     * whenever a priced quote is available.
     */
    private val _savingsTransferMode = MutableStateFlow(SavingsTransferMode.CLOSE)
    val savingsTransferMode = _savingsTransferMode.asStateFlow()

    private val _savingsSwapState = MutableStateFlow(SavingsSwapUiState())
    val savingsSwapState = _savingsSwapState.asStateFlow()

    /** Outcome of the swap started by [startSavingsSwap]; null while none has finished. */
    private val _savingsSwapResult = MutableStateFlow<SavingsSwapResult?>(null)
    val savingsSwapResult = _savingsSwapResult.asStateFlow()

    private var savingsSwapJob: Job? = null

    /** The amount (sat) that will actually be swapped out; adjustable via the confirm slider. */
    private var pendingSwapAmountSat: ULong = 0uL

    /** Cached swap limits so the slider can re-price locally without hitting the network. */
    private var reverseLimits: BoltzPairInfo? = null

    private var savingsSwapQuoteJob: Job? = null

    /**
     * Fetch swap limits, derive the adjustable amount range, and publish an initial fee quote
     * (defaulting to the maximum transferable) so the user sees the cost before confirming.
     * The confirm slider then re-prices locally via [onSwapAmountChange]. A quote is the only
     * thing that unlocks the swap, so every failure simply leaves it null and the transfer
     * falls back to closing a channel.
     * Cancels any in-flight quote so a slower earlier request cannot overwrite a newer one.
     */
    fun loadSavingsSwapQuote(requestedSat: ULong) {
        if (!boltzService.isSwapSupported) return
        savingsSwapQuoteJob?.cancel()
        savingsSwapQuoteJob = viewModelScope.launch {
            val isSwapEnabled = boltzService.isSwapEnabled()
            _savingsSwapState.update { it.copy(isSwapEnabled = isSwapEnabled) }
            if (!isSwapEnabled) return@launch
            _savingsSwapState.update { it.copy(isLoading = true) }
            awaitNodeRunning()

            // Bounded so a hanging Boltz request cannot leave the confirm swipe stuck loading.
            val limits = withTimeoutOrNull(SWAP_QUOTE_TIMEOUT) {
                runSuspendCatching { boltzService.reverseLimits() }
                    .onFailure { Logger.error("Failed to load reverse swap limits", it, context = TAG) }
                    .getOrNull()
            }
            if (limits == null) {
                reverseLimits = null
                _savingsSwapState.update { it.copy(isLoading = false, quote = null) }
                return@launch
            }
            reverseLimits = limits

            // Reserve headroom for Lightning routing fees. Paying an invoice for 100% of
            // outbound capacity leaves nothing for fees and fails with RouteNotFound, so cap
            // the swap at outbound minus ~1% (with a small floor).
            val spendable = walletRepo.balanceState.value.maxSendLightningSats.toLong()
            val routingReserve = (spendable / 100).coerceAtLeast(MIN_LN_ROUTING_FEE_RESERVE_SATS)
            val sendable = (spendable - routingReserve).coerceAtLeast(0).toULong()
            val maxSat = minOf(requestedSat, limits.maximalSat, sendable)
            val minSat = limits.minimalSat

            if (maxSat < minSat) {
                // Below the swap minimum: revert to the pre-swap view where the swipe closes
                // the channel instead. No error text or extra close action is shown.
                pendingSwapAmountSat = 0uL
                _savingsSwapState.update {
                    it.copy(
                        isLoading = false,
                        quote = null,
                        minSat = 0uL,
                        maxSat = 0uL,
                    )
                }
                return@launch
            }

            // Default to transferring as much as possible; the slider can lower it.
            pendingSwapAmountSat = maxSat
            _savingsSwapState.update {
                it.copy(
                    isLoading = false,
                    minSat = minSat,
                    maxSat = maxSat,
                    quote = buildQuote(maxSat, limits),
                )
            }
        }
    }

    /** Re-price the swap for a slider-selected amount, clamped to the allowed range. */
    fun onSwapAmountChange(sat: ULong) {
        val limits = reverseLimits ?: return
        val state = _savingsSwapState.value
        if (state.maxSat < state.minSat) return
        val amount = sat.coerceIn(state.minSat, state.maxSat)
        pendingSwapAmountSat = amount
        _savingsSwapState.update { it.copy(quote = buildQuote(amount, limits)) }
    }

    private fun buildQuote(amount: ULong, limits: BoltzPairInfo): SavingsSwapQuote {
        val swapFee = (amount.toDouble() * limits.feePercentage / 100.0).roundToLong().coerceAtLeast(0).toULong()
        val networkFee = limits.minerFeesSat
        val receive = (amount.toLong() - swapFee.toLong() - networkFee.toLong()).coerceAtLeast(0).toULong()
        return SavingsSwapQuote(
            amountSat = amount,
            networkFeeSat = networkFee,
            swapFeeSat = swapFee,
            receiveSat = receive,
        )
    }

    /**
     * Run the swap in [viewModelScope] so it outlives the progress screen: navigating away
     * mid-flight must not cancel a swap whose hold invoice is already paid. Idempotent once
     * started: a swap already in flight, or one whose outcome is already in [savingsSwapResult],
     * is never re-run, so re-entering the screen or a configuration change cannot create and pay
     * a second swap. A fresh journey clears the outcome in [onTransferToSavingsConfirm].
     */
    fun startSavingsSwap() {
        if (savingsSwapJob?.isActive == true || _savingsSwapResult.value != null) return
        savingsSwapJob = viewModelScope.launch {
            val result = executeSavingsSwap()
            _savingsSwapResult.update { result }
        }
    }

    /**
     * Execute the LN -> onchain swap: derive a fresh claim address, create the swap,
     * pay the returned hold invoice over Lightning, then wait for the on-chain claim.
     * The claim is auto-broadcast by the updates stream as soon as the lockup hits the
     * mempool, so a timeout here is not a failure; the swap completes in the background.
     */
    private suspend fun executeSavingsSwap(): SavingsSwapResult {
        val amount = pendingSwapAmountSat.takeIf { it > 0uL }
            ?: return SavingsSwapResult.Failure(
                context.getString(R.string.lightning__savings_confirm__amount_too_low),
            )

        return runSuspendCatching {
            val claimAddress = lightningRepo.newAddress().getOrThrow()
            val swap = boltzService.createReverseSwap(amountSat = amount, claimAddress = claimAddress)
            Logger.info("Created savings transfer swap ${swap.id}", context = TAG)

            coroutineScope {
                // Whichever resolves first wins: an on-chain claim, a Boltz error, or a Lightning
                // routing failure. The losing watcher is cancelled so this scope can return.
                val outcome = CompletableDeferred<SavingsSwapResult>()

                // UNDISPATCHED so the collector actually subscribes before we pay: the events
                // flow has no replay, so a claim settling faster than the payment call returns
                // would otherwise be missed and the swap would idle until the claim timeout.
                val claim = launch(start = CoroutineStart.UNDISPATCHED) {
                    outcome.complete(awaitSwapClaim(swap.id))
                }

                // Pay the hold invoice (amount is encoded). It stays pending until Boltz
                // locks funds on-chain and we claim them, which is the expected happy path.
                val paymentHash = lightningRepo.payInvoice(bolt11 = swap.invoice).getOrThrow()

                // payInvoice returns as soon as the HTLC is dispatched, and a hold invoice never
                // settles until Boltz claims, so nothing else notices a payment that fails to route.
                // Watch for it so an unroutable payment surfaces as an error instead of idling into
                // the claim timeout and reporting a settling transfer that never completes.
                val failure = launch {
                    outcome.complete(awaitPaymentFailure(paymentHash))
                }

                outcome.await().also {
                    claim.cancel()
                    failure.cancel()
                }
            }
        }.getOrElse { e ->
            Logger.error("Savings transfer swap failed", e, context = TAG)
            SavingsSwapResult.Failure(e.message ?: context.getString(R.string.common__error_body))
        }
    }

    private suspend fun awaitSwapClaim(swapId: String): SavingsSwapResult {
        val event = withTimeoutOrNull(SWAP_CLAIM_TIMEOUT) {
            boltzService.events.first {
                (it is BoltzSwapEvent.Claimed && it.swapId == swapId) ||
                    (it is BoltzSwapEvent.Error && it.swapId == swapId)
            }
        }
        return when (event) {
            is BoltzSwapEvent.Claimed -> SavingsSwapResult.Success(event.txid)
            is BoltzSwapEvent.Error -> SavingsSwapResult.Failure(event.message)
            else -> SavingsSwapResult.Pending
        }
    }

    /** Suspends until the paid hold invoice fails to route on Lightning, then maps it to a reason. */
    private suspend fun awaitPaymentFailure(paymentHash: PaymentId): SavingsSwapResult {
        val event = lightningRepo.nodeEvents
            .filterIsInstance<Event.PaymentFailed>()
            .first { it.paymentHash == paymentHash }
        return SavingsSwapResult.Failure(event.reason.toUserMessage(context))
    }

    fun setSelectedChannelIds(channelIds: Set<String>) {
        _selectedChannelIdsState.update { channelIds }
    }

    /**
     * Commit the transfer and pick how it runs. A swap needs a priced quote, so without one
     * (swaps unsupported on this network, Boltz unreachable, or an amount below the swap
     * minimum) the transfer closes a channel exactly as it did before swaps existed.
     */
    fun onTransferToSavingsConfirm(
        channels: List<ChannelDetails>,
        mode: SavingsTransferMode = resolveSavingsTransferMode(),
    ) {
        _savingsTransferMode.update { mode }
        // Drop any outcome from an earlier attempt so the progress screen cannot act on it.
        _savingsSwapResult.update { null }
        _selectedChannelIdsState.update { emptySet() }
        channelsToClose = channels
    }

    private fun resolveSavingsTransferMode(): SavingsTransferMode =
        if (_savingsSwapState.value.quote != null) SavingsTransferMode.SWAP else SavingsTransferMode.CLOSE

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
        private const val MIN_STEP_DELAY_MS = 500L
        private const val POLL_INTERVAL_MS = 2_500L
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** Live re-quotes allowed while settling the advertised max transfer on an affordable balance. */
        private const val MAX_AFFORDABILITY_ROUNDS = 2

        /** Conservative vbyte reserve for multi-input hardware funding before exact compose runs. */
        private const val HW_FUNDING_TX_VBYTES = 1_200uL

        /** Minimum fallback fee rate when fee estimates are temporarily unavailable. */
        private const val HW_FUNDING_FALLBACK_SATS_PER_VBYTE = 3uL

        /** Upper bound for reconnecting a known device before the UI asks for reconnect. */
        private val HW_RECONNECT_TIMEOUT = 30.seconds

        /** Upper bound for exact hardware funding composition before signing starts. */
        private val HW_COMPOSE_TIMEOUT = 45.seconds

        /** Upper bound for one hardware signing attempt before the UI releases the button. */
        private val HW_SIGN_TIMEOUT = 120.seconds

        /** Upper bound for broadcasting a signed hardware funding transaction. */
        private val HW_BROADCAST_TIMEOUT = 120.seconds

        /** How long the confirm/progress flow waits for the on-chain claim before backgrounding it. */
        private val SWAP_CLAIM_TIMEOUT = 30.seconds

        /** Upper bound for fetching swap limits before the confirm screen gives up on a quote. */
        private val SWAP_QUOTE_TIMEOUT = 15.seconds

        /** Minimum sats held back from a swap to cover Lightning routing fees. */
        private const val MIN_LN_ROUTING_FEE_RESERVE_SATS = 10L

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
    val walletId: String,
    val address: String,
    val amountSats: ULong,
    val signedTx: HwFundingSignedTx,
) {
    fun matches(order: IBtOrder, walletId: String, address: String): Boolean =
        orderId == order.id &&
            this.walletId == walletId &&
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
    /** The hidden wallet needs its passphrase before the device can sign for it. */
    val isHwPassphraseRequired: Boolean = false,
    val isVerifyingHwPassphrase: Boolean = false,
    val hwMiningFeeSats: ULong = 0uL,
    /** Real on-chain mining fee for soft-wallet confirm (iOS transactionFee). */
    val miningFeeSats: ULong = 0uL,
    val isConfirmFeeReady: Boolean = false,
    val isConfirmPaying: Boolean = false,
    val shouldUseSendAll: Boolean = false,
    val receivingAmount: Long = 0,
    val feeEstimate: Long? = null,
)

private data class SpendingConfirmFundingPlan(
    val orderId: String,
    val miningFeeSats: ULong,
    val shouldUseSendAll: Boolean,
    val selectedUtxos: List<SpendableUtxo>?,
    val spendableBalance: ULong,
    val totalOnchainBalance: ULong,
    val maxSendable: ULong,
)

data class TransferValues(
    val defaultLspBalance: ULong = 0u,
    val minLspBalance: ULong = 0u,
    val maxLspBalance: ULong = 0u,
    val maxClientBalance: ULong = 0u,
)

sealed interface TransferEffect {
    data object OnOrderCreated : TransferEffect
    data object OnSpendingFundingPaid : TransferEffect
    data object OnHwTxSigned : TransferEffect
    data class ToastException(val e: Throwable) : TransferEffect
    data class ToastError(val title: String, val description: String) : TransferEffect
}

/** Whether a transfer to savings swaps funds out or closes a channel (default). */
enum class SavingsTransferMode { SWAP, CLOSE }

@Immutable
data class SavingsSwapQuote(
    val amountSat: ULong,
    val networkFeeSat: ULong,
    val swapFeeSat: ULong,
    val receiveSat: ULong,
)

@Immutable
data class SavingsSwapUiState(
    /** Whether swaps are switched on for this network and in dev settings; see [BoltzService.isSwapEnabled]. */
    val isSwapEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val quote: SavingsSwapQuote? = null,
    /** Inclusive adjustable range for the confirm slider (sat). Equal/zero when unavailable. */
    val minSat: ULong = 0uL,
    val maxSat: ULong = 0uL,
)

sealed interface SavingsSwapResult {
    /** Funds landed on-chain during the flow. */
    data class Success(val txid: String) : SavingsSwapResult

    /** Swap created and invoice paid; the claim completes in the background. */
    data object Pending : SavingsSwapResult

    data class Failure(val reason: String) : SavingsSwapResult
}
// endregion

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException && this !is TimeoutCancellationException) throw this
}
