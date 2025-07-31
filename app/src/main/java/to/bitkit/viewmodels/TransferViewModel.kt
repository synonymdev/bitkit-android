package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.IBtInfo
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

const val RETRY_INTERVAL_MS = 1 * 60 * 1000L // 1 minutes in ms
const val GIVE_UP_MS = 30 * 60 * 1000L // 30 minutes in ms
private const val EUR_CURRENCY = "EUR"

@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val walletRepo: WalletRepo,
    private val currencyRepo: CurrencyRepo,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore,
) : ViewModel() {
    private val _spendingUiState = MutableStateFlow(TransferToSpendingUiState())
    val spendingUiState = _spendingUiState.asStateFlow()

    val lightningSetupStep: StateFlow<Int> = settingsStore.data.map { it.lightningSetupStep }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _selectedChannelIdsState = MutableStateFlow<Set<String>>(emptySet())
    val selectedChannelIdsState = _selectedChannelIdsState.asStateFlow()

    private val _transferValues = MutableStateFlow(TransferValues())
    val transferValues = _transferValues.asStateFlow()

    val transferEffects = MutableSharedFlow<TransferEffect>()
    fun setTransferEffect(effect: TransferEffect) = viewModelScope.launch { transferEffects.emit(effect) }
    var retryTimes = 0

    // region Spending

    fun onClickMaxAmount() {
        _spendingUiState.update {
            it.copy(
                satsAmount = it.maxAllowedToSend,
                overrideSats = it.maxAllowedToSend,
            )
        }
        updateLimits(false)
    }

    fun onClickQuarter() {
        val quarter = (_spendingUiState.value.balanceAfterFee.toDouble() * QUARTER).roundToLong()

        if (quarter > _spendingUiState.value.maxAllowedToSend) {
            setTransferEffect(
                TransferEffect.ToastError(
                    title = context.getString(R.string.lightning__spending_amount__error_max__title),
                    description = context.getString(
                        R.string.lightning__spending_amount__error_max__description
                    ).replace("{amount}", _spendingUiState.value.maxAllowedToSend.toString()),
                )
            )
        }

        _spendingUiState.update {
            it.copy(
                satsAmount = min(quarter, it.maxAllowedToSend),
                overrideSats = min(quarter, it.maxAllowedToSend),
            )
        }
        updateLimits(false)
    }

    fun onConfirmAmount() {
        if (_transferValues.value.maxLspBalance == 0uL) {
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

        _spendingUiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            blocktankRepo.createOrder(_spendingUiState.value.satsAmount.toULong())
                .onSuccess { order ->
                    onOrderCreated(order)
                    _spendingUiState.update { it.copy(isLoading = false) }
                }.onFailure { e ->
                    setTransferEffect(TransferEffect.ToastException(e))
                    _spendingUiState.update { it.copy(isLoading = false) }
                }
        }
    }

    fun onAmountChanged(sats: Long) {
        if (sats > _spendingUiState.value.maxAllowedToSend) {
            setTransferEffect(
                TransferEffect.ToastError(
                    title = context.getString(R.string.lightning__spending_amount__error_max__title),
                    description = context.getString(
                        R.string.lightning__spending_amount__error_max__description
                    ).replace("{amount}", _spendingUiState.value.maxAllowedToSend.toString()),
                )
            )
            _spendingUiState.update { it.copy(overrideSats = it.satsAmount) }
            return
        }

        _spendingUiState.update { it.copy(satsAmount = sats, overrideSats = null) }

        retryTimes = 0
        updateLimits(retry = false)
    }

    fun updateLimits(retry: Boolean) {
        updateTransferValues(_spendingUiState.value.satsAmount.toULong())
        updateAvailableAmount(retry = retry)
    }

    fun onAdvancedOrderCreated(order: IBtOrder) {
        val defaultOrder = _spendingUiState.value.order
        _spendingUiState.update { it.copy(order = order, defaultOrder = defaultOrder, isAdvanced = true) }
    }

    /** Pays for the order and start watching it for state updates */
    fun onTransferToSpendingConfirm(order: IBtOrder, speed: TransactionSpeed? = null) {
        viewModelScope.launch {
            lightningRepo
                .sendOnChain(
                    address = order.payment.onchain.address,
                    sats = order.feeSat,
                    speed = speed,
                    isTransfer = true,
                    channelId = order.channel?.shortChannelId,
                )
                .onSuccess { txId ->
                    cacheStore.addPaidOrder(orderId = order.id, txId = txId)
                    settingsStore.update { it.copy(lightningSetupStep = 0) }
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
                        error = Exception("Order not found '$orderId'")
                        Logger.error("Order not found '$orderId'", context = TAG)
                        break
                    }

                    val step = updateOrder(order)
                    settingsStore.update { it.copy(lightningSetupStep = step) }
                    Logger.debug("LN setup step: $step")

                    if (order.state2 == BtOrderState2.EXPIRED) {
                        error = Exception("Order expired '$orderId'")
                        Logger.error("Order expired '$orderId'", context = TAG)
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

    private fun onOrderCreated(order: IBtOrder) {
        _spendingUiState.update { it.copy(order = order, isAdvanced = false, defaultOrder = null) }
        setTransferEffect(TransferEffect.OnOrderCreated)
    }

    private fun updateAvailableAmount(retry: Boolean) {
        viewModelScope.launch {
            _spendingUiState.update { it.copy(isLoading = true) }

            // Get the max available balance discounting onChain fee
            val availableAmount = walletRepo.getMaxSendAmount()

            // Calculate the LSP fee to the total balance
            blocktankRepo.estimateOrderFee(
                spendingBalanceSats = availableAmount,
                receivingBalanceSats = _transferValues.value.maxLspBalance
            ).onSuccess { estimate ->
                retryTimes = 0
                val maxLspFee = estimate.feeSat

                // Calculate the available balance to send after LSP fee
                val balanceAfterLspFee = availableAmount - maxLspFee

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
                if (exception is ServiceError.NodeNotStarted && retry) {
                    // Retry after delay
                    Logger.warn("Error getting the available amount. Node not started. trying again in 2 seconds")
                    delay(2.seconds)
                    updateAvailableAmount(retry = retryTimes <= RETRY_LIMIT)
                    retryTimes++
                } else {
                    _spendingUiState.update { it.copy(isLoading = false) }
                    Logger.error("Failure", exception)
                    setTransferEffect(TransferEffect.ToastException(exception))
                }
            }
        }
    }

    private suspend fun updateOrder(order: IBtOrder): Int {
        var currentStep = 0
        if (order.channel != null) {
            return 3
        }

        when (order.state2) {
            BtOrderState2.CREATED -> {
                currentStep = 0
            }

            BtOrderState2.PAID -> {
                currentStep = 1
                blocktankRepo.openChannel(order.id)
            }

            BtOrderState2.EXECUTED -> {
                currentStep = 2
            }

            else -> Unit
        }
        return currentStep
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
        viewModelScope.launch {
            _transferValues.value = calculateTransferValues(clientBalanceSat)
        }
    }

    fun calculateTransferValues(clientBalanceSat: ULong): TransferValues {
        val blocktankInfo = blocktankRepo.blocktankState.value.info
        if (blocktankInfo == null) return TransferValues()

        // Calculate the total value of existing Blocktank channels
        val channelsSize = totalBtChannelsValueSats(blocktankInfo)

        val minChannelSizeSat = blocktankInfo.options.minChannelSizeSat
        val maxChannelSizeSat = blocktankInfo.options.maxChannelSizeSat

        // Because LSP limits constantly change depending on network fees
        // Add a 2% buffer to avoid fluctuations while making the order
        val maxChannelSize1 = (maxChannelSizeSat.toDouble() * 0.98).roundToLong().toULong()

        // The maximum channel size the user can open including existing channels
        val maxChannelSize2 = (maxChannelSize1 - channelsSize).coerceAtLeast(0u)
        val maxChannelSizeAvailableToIncrease = min(maxChannelSize1, maxChannelSize2)

        val minLspBalance = getMinLspBalance(clientBalanceSat, minChannelSizeSat)
        val maxLspBalance = (maxChannelSizeAvailableToIncrease - clientBalanceSat).coerceAtLeast(0u)
        val defaultLspBalance = getDefaultLspBalance(clientBalanceSat, maxLspBalance)
        val maxClientBalance = getMaxClientBalance(maxChannelSizeAvailableToIncrease)

        if (maxChannelSizeAvailableToIncrease < clientBalanceSat) {
            Logger.warn(
                "Amount clientBalanceSat:$clientBalanceSat too large, max possible: $maxChannelSizeAvailableToIncrease",
                context = TAG
            )
        }

        if (defaultLspBalance !in minLspBalance..maxLspBalance) {
            Logger.warn(
                "Invalid defaultLspBalance:$defaultLspBalance " +
                    "min possible:$maxLspBalance, " +
                    "max possible: $minLspBalance",
                context = TAG
            )
        }

        if (maxChannelSizeAvailableToIncrease <= 0u) {
            Logger.warn("Max channel size reached. current size: $channelsSize sats", context = TAG)
        }

        if (maxClientBalance <= 0u) {
            Logger.warn("No liquidity available to purchase $maxClientBalance", context = TAG)
        }

        return TransferValues(
            defaultLspBalance = defaultLspBalance,
            minLspBalance = minLspBalance,
            maxLspBalance = maxLspBalance,
            maxClientBalance = maxClientBalance,
        )
    }

    private fun getDefaultLspBalance(clientBalanceSat: ULong, maxLspBalance: ULong): ULong {

        // Calculate thresholds in sats
        val threshold1 = currencyRepo.convertFiatToSats(BigDecimal(225), EUR_CURRENCY).getOrNull()
        val threshold2 = currencyRepo.convertFiatToSats(BigDecimal(495), EUR_CURRENCY).getOrNull()
        val defaultLspBalanceSats = currencyRepo.convertFiatToSats(BigDecimal(450), EUR_CURRENCY).getOrNull()

        Logger.debug("getDefaultLspBalance - clientBalanceSat: $clientBalanceSat")
        Logger.debug("getDefaultLspBalance - maxLspBalance: $maxLspBalance")
        Logger.debug("getDefaultLspBalance - defaultLspBalanceSats: $defaultLspBalanceSats")

        if (threshold1 == null || threshold2 == null || defaultLspBalanceSats == null) {
            Logger.error("Failed to get rates for lspBalance calculation", context = TAG)
            throw ServiceError.CurrencyRateUnavailable
        }

        val lspBalance = if (clientBalanceSat < threshold1) { // 0-225€: LSP balance = 450€ - client balance
            defaultLspBalanceSats - clientBalanceSat
        } else if (clientBalanceSat < threshold2) { // 225-495€: LSP balance = client balance
            clientBalanceSat
        } else if (clientBalanceSat < maxLspBalance) { // 495-950€: LSP balance = max - client balance
            maxLspBalance - clientBalanceSat
        } else {
            maxLspBalance
        }

        return min(lspBalance, maxLspBalance)
    }

    private fun getMinLspBalance(clientBalance: ULong, minChannelSize: ULong): ULong {
        // LSP balance must be at least 2.5% of the channel size for LDK to accept (reserve balance)
        val ldkMinimum = (clientBalance.toDouble() * 0.025).toULong()
        // Channel size must be at least minChannelSize
        val lspMinimum = if (minChannelSize > clientBalance) minChannelSize - clientBalance else 0u

        return max(ldkMinimum, lspMinimum)
    }

    private fun getMaxClientBalance(maxChannelSize: ULong): ULong {
        // Remote balance must be at least 2.5% of the channel size for LDK to accept (reserve balance)
        val minRemoteBalance = (maxChannelSize.toDouble() * 0.025).toULong()

        return maxChannelSize - minRemoteBalance
    }

    /** Calculates the total value of channels connected to Blocktank nodes */
    private fun totalBtChannelsValueSats(info: IBtInfo?): ULong {
        val channels = lightningRepo.getChannels() ?: return 0u
        val btNodeIds = info?.nodes?.map { it.pubkey } ?: return 0u

        val btChannels = channels.filter { btNodeIds.contains(it.counterpartyNodeId) }

        val totalValue = btChannels.sumOf { it.channelValueSats }

        return totalValue
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
    suspend fun closeSelectedChannels(): List<ChannelDetails> {
        return closeChannels(channelsToClose)
    }

    private suspend fun closeChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
        val channelsFailedToClose = coroutineScope {
            channels.map { channel ->
                async {
                    try {
                        lightningRepo.closeChannel(channel).getOrThrow()
                        null
                    } catch (e: Throwable) {
                        Logger.error("Error closing channel: ${channel.channelId}", e)
                        channel
                    }
                }
            }.awaitAll()
        }.filterNotNull()

        return channelsFailedToClose
    }

    private var coopCloseRetryJob: Job? = null

    /** Retry to coop close the channel(s) for 30 min */
    fun startCoopCloseRetries(channels: List<ChannelDetails>, startTimeMs: Long) {
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
            // TODO: showBottomSheet: forceTransfer
        }
    }

    // endregion

    companion object {
        private const val TAG = "TransferViewModel"
        private const val RETRY_LIMIT = 5
        private const val QUARTER = 0.25
    }
}

// region state
data class TransferToSpendingUiState(
    val order: IBtOrder? = null,
    val defaultOrder: IBtOrder? = null,
    val isAdvanced: Boolean = false,
    val satsAmount: Long = 0,
    val overrideSats: Long? = null,
    val maxAllowedToSend: Long = 0,
    val balanceAfterFee: Long = 0,
    val isLoading: Boolean = false,
)

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
