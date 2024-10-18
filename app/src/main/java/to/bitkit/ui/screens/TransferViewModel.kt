package to.bitkit.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.AppDb
import to.bitkit.data.entities.OrderEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.di.UiDispatcher
import to.bitkit.ext.toast
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val db: AppDb,
    private val blocktankService: BlocktankService,
    private val lightningService: LightningService,
) : ViewModel() {
    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Create)
    val uiState = _uiState.asStateFlow()

    fun createOrder(sats: Int) {
        viewModelScope.launch {
            val result = runCatching { blocktankService.createOrder(spendingBalanceSats = sats, 6) }
                .onSuccess { order ->
                    launch { db.ordersDao().upsert(OrderEntity(order.id)) }
                    _uiState.value = TransferUiState.Confirm(
                        order = order,
                        txId = null,
                    )
                }
            withContext(uiThread) {
                toast(if (result.isSuccess) "Order created" else "Failed to create order")
            }
        }
    }

    fun payOrder(order: BtOrder) {
        viewModelScope.launch {
            runCatching { lightningService.send(order.payment.onchain.address, order.feeSat) }
                .onFailure {
                    withContext(uiThread) {
                        toast("Failed to pay for order ${it.message}")
                    }
                }
                .onSuccess { txId ->
                    (_uiState.value as? TransferUiState.Confirm)?.let {
                        _uiState.value = it.copy(txId = txId)
                    }
                    withContext(uiThread) {
                        toast("Payment sent $txId")
                    }
                }
        }
    }

    fun manualOpenChannel(order: BtOrder) {
        viewModelScope.launch {
            runCatching { blocktankService.openChannel(order.id) }
                .onFailure { withContext(uiThread) { toast("Manual open error: ${it.message}") } }
                .onSuccess { withContext(uiThread) { toast("Manual open success") } }
        }
    }
}

// region state
sealed class TransferUiState {
    data object Create : TransferUiState()
    data class Confirm(
        val order: BtOrder,
        val txId: Txid?,
    ) : TransferUiState()
}
// endregion
