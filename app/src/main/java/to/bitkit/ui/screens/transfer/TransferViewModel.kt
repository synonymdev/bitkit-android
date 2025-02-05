package to.bitkit.ui.screens.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Txid
import to.bitkit.models.Toast
import to.bitkit.services.CoreService
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import uniffi.bitkitcore.IBtOrder
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val lightningService: LightningService,
) : ViewModel() {
    private val _uiState = MutableStateFlow<TransferUiState>(TransferUiState.Create)
    val uiState = _uiState.asStateFlow()

    fun onOrderCreated(order: IBtOrder) {
        _uiState.value = TransferUiState.Confirm(
            order = order,
            txId = null,
        )
    }

    fun payOrder(order: IBtOrder) {
        viewModelScope.launch {
            try {
                val txId = lightningService.send(
                    address = order.payment.onchain.address,
                    sats = order.feeSat,
                )
                (_uiState.value as? TransferUiState.Confirm)?.let {
                    _uiState.value = it.copy(txId = txId)
                }
                ToastEventBus.send(Toast.ToastType.SUCCESS, "Success", "Payment sent $txId")
            } catch (e: Throwable) {
                ToastEventBus.send(e)
            }
        }
    }
}

// region state
sealed class TransferUiState {
    data object Create : TransferUiState()
    data class Confirm(
        val order: IBtOrder,
        val txId: Txid?,
    ) : TransferUiState()
}
// endregion
