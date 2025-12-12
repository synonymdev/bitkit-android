package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.ext.amountOnClose
import to.bitkit.models.Toast
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.TransferType
import to.bitkit.models.safe
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val RETRY_INTERVAL_MS = 1 * 60 * 1000L // 1 minutes in ms
const val GIVE_UP_MS = 30 * 60 * 1000L // 30 minutes in ms

@Suppress("LongParameterList")
@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
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

            lightningRepo
                .sendOnChain(
                    address = address,
                    sats = order.feeSat,
                    speed = speed,
                    isTransfer = true,
                    channelId = order.channel?.shortChannelId,
                )
                .onSuccess { txId ->
                    cacheStore.addPaidOrder(orderId = order.id, txId = txId)
                    transferRepo.createTransfer(
                        type = TransferType.TO_SPENDING,
                        amountSats = order.clientBalanceSat.toLong(),
                        lspOrderId = order.id,
                    )
                    launch { walletRepo.syncBalances() }
                    watchOrder(order.id)
                }
                .onFailure { error ->
                    ToastEventBus.send(error)
                }
        }
    }

    private fun watchOrder(orderId: String, frequencyMs: Long = 2_500) {
        var isSettled = false
        var error: Throwable? = null

        viewModelScope.launch {
            Logger.debug("Started to watch order '$orderId'", context = TAG)

            while (!isSettled && error == null) {
                try {
                    Logger.debug("Refreshing order '$orderId'")
                    val order = blocktankRepo.getOrder(orderId, refresh = true).getOrNull()
                    if (order == null) {
                        error = Exception("Order not found '$orderId'").also {
                            Logger.error(it.message, context = TAG)
                        }
                        break
                    }

                    val step = updateOrder(order)
                    settingsStore.update { it.copy(lightningSetupStep = step) }
                    Logger.debug("LN setup step: $step")

                    if (order.state2 == BtOrderState2.EXPIRED) {
                        error = Exception("Order expired '$orderId'").also {
                            Logger.error(it.message, context = TAG)
                        }
                        break
                    }
                    if (step > 2) {
                        Logger.debug("Order settled, stopping watch order '$orderId'", context = TAG)
                        isSettled = true
                        break
                    }
                } catch (e: Throwable) {
                    Logger.error("Failed to watch order '$orderId'", e, context = TAG)
                    error = e
                    break
                }
                delay(frequencyMs)
            }
            Logger.debug("Stopped watching order '$orderId'", context = TAG)
        }
    }

    private suspend fun onOrderCreated(order: IBtOrder) {
        settingsStore.update { it.copy(lightningSetupStep = 0) }
        _spendingUiState.update { it.copy(order = order, isAdvanced = false, defaultOrder = null) }
        setTransferEffect(TransferEffect.OnOrderCreated)
    }

    private fun updateAvailableAmount() {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            // Get the max available balance discounting onChain fee
            val availableAmount = walletRepo.balanceState.value.maxSendOnchainSats

            withTimeoutOrNull(1.minutes) {
                isNodeRunning.first { it }
            }

            // Calculate the LSP fee to the total balance
            blocktankRepo.estimateOrderFee(
                spendingBalanceSats = availableAmount,
                receivingBalanceSats = _transferValues.value.maxLspBalance
            ).onSuccess { estimate ->
                maxLspFee = estimate.feeSat

                // Calculate the available balance to send after LSP fee
                val balanceAfterLspFee = availableAmount.safe() - maxLspFee.safe()

                _spendingUiState.update {
                    // Calculate the max available to send considering the current balance and LSP policy
                    it.copy(
                        maxAllowedToSend = min(
                            _transferValues.value.maxClientBalance.toLong(),
                            balanceAfterLspFee.toLong()
                        ),
                        isLoading = false,
                        balanceAfterFee = availableAmount.toLong()
                    )
                }
            }.onFailure { exception ->
                _spendingUiState.update { it.copy(isLoading = false) }
                Logger.error("Failure", exception)
                setTransferEffect(TransferEffect.ToastException(exception))
            }
        }
    }

    private suspend fun updateOrder(order: IBtOrder): Int {
        if (order.channel != null) {
            transferRepo.syncTransferStates()
            return LN_SETUP_STEP_3
        }

        when (order.state2) {
            BtOrderState2.CREATED -> return 0

            BtOrderState2.PAID -> {
                blocktankRepo.openChannel(order.id)
                return 1
            }

            BtOrderState2.EXECUTED -> return 2

            else -> return 0
        }
    }

    fun onUseDefaultLspBalanceClick() {
        val defaultOrder = _spendingUiState.value.defaultOrder
        _spendingUiState.update { it.copy(order = defaultOrder, defaultOrder = null, isAdvanced = false) }
    }

    fun resetSpendingState() {
        _spendingUiState.value = TransferToSpendingUiState()
        _transferValues.value = TransferValues()
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

    private suspend fun closeChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
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

            Logger.info("Giving up on coop close.")
            onGiveUp()
        }
    }

    fun forceTransfer(onComplete: () -> Unit) = viewModelScope.launch {
        _isForceTransferLoading.value = true
        runCatching {
            val failedChannels = forceCloseChannels(channelsToClose)
            if (failedChannels.isEmpty()) {
                Logger.info("Force close initiated successfully for all channels", context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.LIGHTNING,
                    title = context.getString(R.string.lightning__force_init_title),
                    description = context.getString(R.string.lightning__force_init_msg)
                )
            } else {
                Logger.error("Force close failed for ${failedChannels.size} channels", context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.lightning__force_failed_title),
                    description = context.getString(R.string.lightning__force_failed_msg)
                )
            }
        }.onFailure { e ->
            Logger.error("Force close failed", e = e, context = TAG)
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
        const val LN_SETUP_STEP_3 = 3
    }
}

// region state
data class TransferToSpendingUiState(
    val order: IBtOrder? = null,
    val defaultOrder: IBtOrder? = null,
    val isAdvanced: Boolean = false,
    val maxAllowedToSend: Long = 0,
    val balanceAfterFee: Long = 0,
    val isLoading: Boolean = false,
    val receivingAmount: Long = 0,
    val feeEstimate: Long? = null,
) {
    fun balanceAfterFeeQuarter() = (balanceAfterFee.toDouble() * 0.25).roundToLong()
}

data class TransferValues(
    val defaultLspBalance: ULong = 0u,
    val minLspBalance: ULong = 0u,
    val maxLspBalance: ULong = 0u,
    val maxClientBalance: ULong = 0u,
)

sealed interface TransferEffect {
    data object OnOrderCreated : TransferEffect
    data class ToastException(val e: Throwable) : TransferEffect
    data class ToastError(val title: String, val description: String) : TransferEffect
}
// endregion
