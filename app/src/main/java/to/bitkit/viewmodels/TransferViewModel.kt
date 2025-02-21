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
                val txId = lightningService.send(
                    address = order.payment.onchain.address,
                    sats = order.feeSat,
                )
                _uiState.update { it.copy(order = order, txId = txId) }
                paidOrders[order.id] = txId
                settingsStore.setLightningSetupStep(0)
                watchOrder(order.id)
                ToastEventBus.send(Toast.ToastType.SUCCESS, "Success", "Payment sent $txId")
            } catch (e: Throwable) {
                ToastEventBus.send(e)
            }
        }
    }

    fun watchOrder(orderId: String, frequencyMs: Long = 150_000) {
        if (watchedOrders.contains(orderId)) return
        watchedOrders.add(orderId)
        var isSettled = false
        var error: Throwable? = null

        viewModelScope.launch {
            while (!isSettled && error == null) {
                try {
                    Logger.debug("refreshing order $orderId")
                    val order = refreshOrder(orderId)
                    if (order.state2 == BtOrderState2.EXPIRED) {
                        error = Exception("Order expired")
                        break
                    }
                    if (order.state2 == BtOrderState2.EXECUTED) {
                        isSettled = true
                        break
                    }
                } catch (e: Throwable) {
                    error = e
                    break
                }
                delay(frequencyMs)
            }
            Logger.debug("stopped watching order $orderId")
        }
        watchedOrders.remove(orderId)
    }

    suspend fun refreshOrder(orderId: String): IBtOrder {
        val currentOrder = _uiState.value.order ?: error("current order not found")
        try {
            var order = coreService.blocktank.orders(orderIds = listOf(orderId)).first()
            val isPaid = paidOrders.containsKey(order.id)

            // attempt to finalise channel open
            if (order.state2 == BtOrderState2.PAID &&
                order.payment.state2 == BtPaymentState2.PAID) {
                settingsStore.setLightningSetupStep(1)
                try {
                    val updatedOrder = coreService.blocktank.open(order.id)
                    settingsStore.setLightningSetupStep(3)
                    order = updatedOrder
                } catch (e: Throwable) {
                    throw e
                }
            }

            // order state is not changed
            if (currentOrder.state2 == order.state2 &&
                currentOrder.payment.state2 == order.payment.state2 &&
                currentOrder.channel?.state == order.channel?.state) {
                return order
            }

            // update stored order
            _uiState.update { it.copy(order = order) }

            // handle state change for paid orders
            if (isPaid &&
                currentOrder.state2 != order.state2 ||
                currentOrder.payment.state2 != order.payment.state2) {
                handleOrderStateChange(order)
            }

            return order
        } catch (e: Throwable) {
            throw e
        }
    }

    private suspend fun handleOrderStateChange(order: IBtOrder) {
        // val paymentTxId = order.payment.onchain.transactions.first().txId

        // queued for opening
        if (order.channel == null) {
            settingsStore.setLightningSetupStep(2)
        }

        // opening connection
        if (order.channel?.state == BtOpenChannelState.OPENING) {
            settingsStore.setLightningSetupStep(3)
        }

        // given up
        if (order.payment.bolt11Invoice.state == BtBolt11InvoiceState.CANCELED) {
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = ResourceProvider.getString(R.string.lightning__order_given_up_title),
                description = ResourceProvider.getString(R.string.lightning__order_given_up_msg),
            )
        }

        // order expired
        if (order.state2 == BtOrderState2.EXPIRED) {
            ToastEventBus.send(
                type = Toast.ToastType.WARNING,
                title = ResourceProvider.getString(R.string.lightning__order_expired_title),
                description = ResourceProvider.getString(R.string.lightning__order_expired_msg),
            )
        }

        // new channel open
        if (order.state2 == BtOrderState2.EXECUTED) {
            // todo refresh ldk-node state
        }
    }

    fun resetState() {
        _uiState.value = TransferUiState()
    }
}

// region state
data class TransferUiState(
    val order: IBtOrder? = null,
    val txId: Txid? = null,
)
// endregion
