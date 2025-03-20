package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.data.SettingsStore
import to.bitkit.services.CoreService
import to.bitkit.services.CurrencyService
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import uniffi.bitkitcore.BtOrderState2
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.IBtOrder
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

const val RETRY_INTERVAL = 5 * 60 * 1000L // 5 minutes in ms
const val GIVE_UP = 30 * 60 * 1000L // 30 minutes in ms

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val lightningService: LightningService,
    private val coreService: CoreService,
    private val currencyService: CurrencyService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _spendingUiState = MutableStateFlow(TransferToSpendingUiState())
    val spendingUiState = _spendingUiState.asStateFlow()

    val lightningSetupStep: StateFlow<Int> = settingsStore.lightningSetupStep
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _selectedChannelIdsState = MutableStateFlow<Set<String>>(emptySet())
    val selectedChannelIdsState = _selectedChannelIdsState.asStateFlow()

    private val _transferValues = MutableStateFlow(TransferValues())
    val transferValues = _transferValues.asStateFlow()

    // region Spending

    fun onOrderCreated(order: IBtOrder) {
        _spendingUiState.update { it.copy(order = order, isAdvanced = false, defaultOrder = null) }
    }

    fun onAdvancedOrderCreated(order: IBtOrder) {
        val defaultOrder = _spendingUiState.value.order
        _spendingUiState.update { it.copy(order = order, defaultOrder = defaultOrder, isAdvanced = true) }
    }

    /** Pays for the order and start watching it for state updates */
    fun onTransferToSpendingConfirm(order: IBtOrder) {
        viewModelScope.launch {
            try {
                lightningService.send(address = order.payment.onchain.address, sats = order.feeSat)
                settingsStore.setLightningSetupStep(0)
                watchOrder(order.id)
            } catch (e: Throwable) {
                ToastEventBus.send(e)
            }
        }
    }

    private fun watchOrder(orderId: String, frequencyMs: Long = 2_500) {
        var isSettled = false
        var error: Throwable? = null

        viewModelScope.launch {
            Logger.debug("Started to watch order order $orderId")

            while (!isSettled && error == null) {
                try {
                    Logger.debug("Refreshing order $orderId")
                    val order = coreService.blocktank.orders(orderIds = listOf(orderId), refresh = true).firstOrNull()
                    if (order == null) {
                        error = Exception("Order not found $orderId")
                        Logger.error("Order not found $orderId", context = "TransferViewModel")
                        break
                    }

                    val step = updateOrder(order)
                    settingsStore.setLightningSetupStep(step)
                    Logger.debug("LN setup step: $step")

                    if (order.state2 == BtOrderState2.EXPIRED) {
                        error = Exception("Order expired $orderId")
                        Logger.error("Order expired $orderId", context = "TransferViewModel")
                        break
                    }
                    if (step > 2) {
                        Logger.debug("Order settled, stopping watch")
                        isSettled = true
                        break
                    }
                } catch (e: Throwable) {
                    Logger.error("Failed to watch order", e)
                    error = e
                    break
                }
                delay(frequencyMs)
            }
            Logger.debug("Stopped watching order $orderId")
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

                try {
                    coreService.blocktank.open(order.id)
                } catch (e: Throwable) {
                    Logger.error("Error opening channel: ${e.message}", e)
                }
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

    fun updateTransferValues(clientBalanceSat: ULong, blocktankInfo: IBtInfo?) {
        _transferValues.value = calculateTransferValues(clientBalanceSat, blocktankInfo)
    }

    fun calculateTransferValues(clientBalanceSat: ULong, blocktankInfo: IBtInfo?): TransferValues {
        if (blocktankInfo == null) return TransferValues()

        // Calculate the total value of existing Blocktank channels
        val channelsSize = totalBtChannelsValueSats(blocktankInfo)

        val minChannelSizeSat = blocktankInfo.options.minChannelSizeSat
        val maxChannelSizeSat = blocktankInfo.options.maxChannelSizeSat

        // Because LSP limits constantly change depending on network fees
        // Add a 2% buffer to avoid fluctuations while making the order
        val maxChannelSize1 = (maxChannelSizeSat.toDouble() * 0.98).roundToLong().toULong()

        // The maximum channel size the user can open including existing channels
        val maxChannelSize2 = if (maxChannelSize1 > channelsSize) maxChannelSize1 - channelsSize else 0u
        val maxChannelSize = min(maxChannelSize1, maxChannelSize2)

        val minLspBalance = getMinLspBalance(clientBalanceSat, minChannelSizeSat)
        val maxLspBalance = if (maxChannelSize > clientBalanceSat) maxChannelSize - clientBalanceSat else 0u
        val defaultLspBalance = getDefaultLspBalance(clientBalanceSat, maxLspBalance)
        val maxClientBalance = getMaxClientBalance(maxChannelSize)

        return TransferValues(
            defaultLspBalance = defaultLspBalance,
            minLspBalance = minLspBalance,
            maxLspBalance = maxLspBalance,
            maxClientBalance = maxClientBalance,
        )
    }

    private fun getDefaultLspBalance(clientBalanceSat: ULong, maxLspBalance: ULong): ULong {
        val rates = currencyService.loadCachedRates()
        val eurRate = rates?.let { currencyService.getCurrentRate("EUR", it) }
        if (eurRate == null) {
            Logger.error("Failed to get rates for getDefaultLspBalance", context = "TransferViewModel")
            return 0u
        }

        // Calculate thresholds in sats
        val threshold1 = currencyService.convertFiatToSats(BigDecimal("225"), eurRate)
        val threshold2 = currencyService.convertFiatToSats(BigDecimal("495"), eurRate)
        val defaultLspBalanceSats = currencyService.convertFiatToSats(BigDecimal("450"), eurRate)

        Logger.debug("getDefaultLspBalance - clientBalanceSat: $clientBalanceSat")
        Logger.debug("getDefaultLspBalance - maxLspBalance: $maxLspBalance")
        Logger.debug("getDefaultLspBalance - defaultLspBalanceSats: $defaultLspBalanceSats")

        // Safely calculate lspBalance to avoid arithmetic overflow
        var lspBalance: ULong = 0u
        if (defaultLspBalanceSats > clientBalanceSat) {
            lspBalance = defaultLspBalanceSats - clientBalanceSat
        }
        if (lspBalance > threshold1) {
            lspBalance = clientBalanceSat
        }
        if (lspBalance > threshold2) {
            lspBalance = maxLspBalance
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
        val channels = lightningService.channels ?: return 0u
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
                        Logger.info("Closing channel: ${channel.channelId}")
                        lightningService.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
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
    fun startCoopCloseRetries(channels: List<ChannelDetails>, startTime: Long) {
        channelsToClose = channels
        coopCloseRetryJob?.cancel()

        coopCloseRetryJob = viewModelScope.launch {
            val giveUpTime = startTime + GIVE_UP

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

                delay(RETRY_INTERVAL)
            }

            Logger.info("Giving up on coop close.")
            // TODO: showBottomSheet: forceTransfer
        }
    }

    // endregion
}

// region state
data class TransferToSpendingUiState(
    val order: IBtOrder? = null,
    val defaultOrder: IBtOrder? = null,
    val isAdvanced: Boolean = false,
)

data class TransferValues(
    val defaultLspBalance: ULong = 0u,
    val minLspBalance: ULong = 0u,
    val maxLspBalance: ULong = 0u,
    val maxClientBalance: ULong = 0u,
)
// endregion
