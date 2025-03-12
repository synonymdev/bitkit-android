package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.data.SettingsStore
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import uniffi.bitkitcore.BtOrderState2
import uniffi.bitkitcore.IBtOrder
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val lightningService: LightningService,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _spendingUiState = MutableStateFlow(TransferToSpendingUiState())
    val spendingUiState = _spendingUiState.asStateFlow()

    val lightningSetupStep: StateFlow<Int> = settingsStore.lightningSetupStep
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _selectedChannelIdsState = MutableStateFlow<Set<String>>(emptySet())
    val selectedChannelIdsState = _selectedChannelIdsState.asStateFlow()

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
                lightningService.send(
                    address = order.payment.onchain.address,
                    sats = order.feeSat,
                )
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
            while (!isSettled && error == null) {
                try {
                    Logger.debug("Refreshing order $orderId")
                    val order = coreService.blocktank.orders(orderIds = listOf(orderId), refresh = true).first()
                    val step = updateOrder(order)
                    settingsStore.setLightningSetupStep(step)
                    Logger.debug("LN setup step: $step")

                    if (order.state2 == BtOrderState2.EXPIRED) {
                        error = Exception("Order expired $orderId")
                        break
                    }
                    if (step > 2) {
                        isSettled = true
                        break
                    }
                } catch (e: Throwable) {
                    error = e
                    break
                }
                delay(frequencyMs)
            }
            Logger.debug("Stopped watching order $orderId")
        }
    }

    @Suppress("IntroduceWhenSubject")
    private suspend fun updateOrder(order: IBtOrder): Int {
        var currentStep = 0
        if (order.channel != null) {
            return 3
        }

        when {
            order.state2 == BtOrderState2.CREATED -> {
                currentStep = 0
            }

            order.state2 == BtOrderState2.PAID -> {
                currentStep = 1

                try {
                    coreService.blocktank.open(order.id)
                } catch (e: Throwable) {
                    Logger.error("Error opening channel: ${e.message}", e)
                }
            }

            order.state2 == BtOrderState2.EXECUTED -> {
                currentStep = 2
            }
        }
        return currentStep
    }

    fun onUseDefaultLspBalanceClick() {
        val defaultOrder = _spendingUiState.value.defaultOrder
        _spendingUiState.update { it.copy(order = defaultOrder, defaultOrder = null, isAdvanced = false) }
    }

    fun resetSpendingState() {
        _spendingUiState.value = TransferToSpendingUiState()
    }

    // endregion

    // region Savings

    fun setSelectedChannelIds(channelIds: Set<String>) {
        _selectedChannelIdsState.update { channelIds }
    }

    /** Closes the provided channels */
    fun onTransferToSavingsConfirm(channels: List<ChannelDetails>) {
        viewModelScope.launch {
            val channelsFailedToCoopClose = closeChannels(channels)
            // TODO emit effect: TransferToSavingsProgressDone(channelsFailedToCoopClose)
            //  and update UI on SavingsProgress screen

            if (channelsFailedToCoopClose.isNotEmpty()) {
                // TODO: schedule retries in background for 30min
                // TODO later: use background service
                Logger.info("Channels failed to coop close: ${channelsFailedToCoopClose.joinToString { it.channelId }}")
            }
        }
    }

    private suspend fun closeChannels(channels: List<ChannelDetails>): List<ChannelDetails> {
        val failedChannels = coroutineScope {
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

        return failedChannels
    }

    // endregion
}

// region state
data class TransferToSpendingUiState(
    val order: IBtOrder? = null,
    val defaultOrder: IBtOrder? = null,
    val isAdvanced: Boolean = false,
)
// endregion
