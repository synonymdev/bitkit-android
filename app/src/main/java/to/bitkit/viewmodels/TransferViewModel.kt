package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Txid
import to.bitkit.models.Toast
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import uniffi.bitkitcore.IBtOrder
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val lightningService: LightningService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState = _uiState.asStateFlow()

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
                _uiState.update { it.copy(order = order) }
                ToastEventBus.send(Toast.ToastType.SUCCESS, "Success", "Payment sent $txId")
            } catch (e: Throwable) {
                ToastEventBus.send(e)
            }
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
