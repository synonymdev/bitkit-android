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
import org.lightningdevkit.ldknode.Txid
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.models.Toast
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import to.bitkit.utils.ResourceProvider
import uniffi.bitkitcore.BtBolt11InvoiceState
import uniffi.bitkitcore.BtOpenChannelState
import uniffi.bitkitcore.BtOrderState2
import uniffi.bitkitcore.BtPaymentState2
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

    /**
     * mapOf(orderId to txId)
     * */
    val paidOrders: MutableMap<String, Txid> = mutableMapOf()

    val watchedOrders = mutableListOf<String>()

    val lightningSetupStep: StateFlow<Int> = settingsStore.lightningSetupStep
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)


    fun onOrderCreated(order: IBtOrder) {
        _uiState.update { it.copy(order = order) }
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
                throw e
            }
        }
    }

    private fun watchOrder(orderId: String, frequencyMs: Long = 5_000) {
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

    private suspend fun updateOrder(order: IBtOrder): Int {
        var currentStep = 0
        if (order.channel != null) {
            return 3
        }

        @Suppress("IntroduceWhenSubject")
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
}

// region state
data class TransferUiState(
    val order: IBtOrder? = null,
)
// endregion
