package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState = _uiState.asStateFlow()

    val lightningSetupStep: StateFlow<Int> = settingsStore.lightningSetupStep
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    fun onOrderCreated(order: IBtOrder) {
        _uiState.update { it.copy(order = order, isAdvanced = false, defaultOrder = null) }
    }

    fun onAdvancedOrderCreated(order: IBtOrder) {
        val defaultOrder = _uiState.value.order
        _uiState.update { it.copy(order = order, defaultOrder = defaultOrder, isAdvanced = true) }
    }

    fun payOrder(order: IBtOrder) {
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

    fun onDefaultClick() {
        val defaultOrder = _uiState.value.defaultOrder
        _uiState.update { it.copy(order = defaultOrder, defaultOrder = null, isAdvanced = false) }
    }

    fun resetState() {
        _uiState.value = TransferUiState()
    }
}

// region state
data class TransferUiState(
    val order: IBtOrder? = null,
    val defaultOrder: IBtOrder? = null,
    val isAdvanced: Boolean = false,
)
// endregion
