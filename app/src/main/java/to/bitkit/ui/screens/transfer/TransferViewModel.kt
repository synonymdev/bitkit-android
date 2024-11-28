package to.bitkit.ui.screens.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.AppDb
import to.bitkit.data.entities.OrderEntity
import to.bitkit.models.Toast
import to.bitkit.models.blocktank.BtOrder
import to.bitkit.services.BlocktankService
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val db: AppDb,
    private val blocktankService: BlocktankService,
    private val lightningService: LightningService,
) : ViewModel() {
    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Create)
    val uiState = _uiState.asStateFlow()

    suspend fun createOrder(sats: Int) {
        runCatching { blocktankService.createOrder(spendingBalanceSats = sats, 6) }
            .onSuccess { order ->
                viewModelScope.launch { db.ordersDao().upsert(OrderEntity(order.id)) }
                _uiState.value = TransferUiState.Confirm(
                    order = order,
                    txId = null,
                )
            }
            .onFailure {
                ToastEventBus.send(it)
                throw it
            }
    }

    fun payOrder(order: BtOrder) {
        viewModelScope.launch {
            runCatching { lightningService.send(order.payment.onchain.address, order.feeSat) }
                .onSuccess { txId ->
                    (_uiState.value as? TransferUiState.Confirm)?.let {
                        _uiState.value = it.copy(txId = txId)
                    }
                    ToastEventBus.send(Toast.ToastType.SUCCESS, "Success", "Payment sent $txId")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun manualOpenChannel(order: BtOrder) {
        viewModelScope.launch {
            runCatching { blocktankService.openChannel(order.id) }
                .onSuccess {
                    ToastEventBus.send(Toast.ToastType.SUCCESS, "Success", "Manual open success")
                }
                .onFailure { ToastEventBus.send(it) }
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
