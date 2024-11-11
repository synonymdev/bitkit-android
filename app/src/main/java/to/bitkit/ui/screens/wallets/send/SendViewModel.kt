package to.bitkit.ui.screens.wallets.send

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.di.UiDispatcher
import to.bitkit.env.Tag.APP
import to.bitkit.ext.toast
import to.bitkit.services.LightningService
import to.bitkit.services.ScannerService
import uniffi.bitkitcore.Scanner
import javax.inject.Inject

@HiltViewModel
class SendViewModel @Inject constructor(
    @UiDispatcher private val uiThread: CoroutineDispatcher,
    private val lightningService: LightningService,
    private val scannerService: ScannerService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SendUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<SendEffect>(replay = 0, extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()
    private fun setEffect(effect: SendEffect) = viewModelScope.launch { _effect.emit(effect) }

    private val events = MutableSharedFlow<SendEvent>()
    fun setEvent(event: SendEvent) = viewModelScope.launch { events.emit(event) }

    private var _scanResult: Scanner? = null

    init {
        observeEvents()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect {
                when (it) {
                    SendEvent.EnterManually -> setEffect(SendEffect.NavigateToAddress)
                    is SendEvent.AddressContinue -> onAddressContinue(it.data)
                    is SendEvent.AmountContinue -> onAmountContinue(it.amount)
                    is SendEvent.Paste -> onPasteInvoice(it.data)
                    is SendEvent.Scan -> onScanSuccess(it.data)
                    SendEvent.SwipeToPay -> onPay()
                }
            }
        }
    }

    private fun onPasteInvoice(data: String) {
        if (data.isBlank()) {
            Log.e(APP, "No data in clipboard")
            return
        }
        viewModelScope.launch {
            val scan = decodeDataOrNull(data)
            handleData(scan)
        }
    }

    private fun handleData(scan: Scanner?) {
        when (scan) {
            is Scanner.OnChain -> {
                _uiState.update {
                    it.copy(
                        address = scan.invoice.address,
                        amount = scan.invoice.amountSatoshis,
                        label = scan.invoice.label.orEmpty(),
                        message = scan.invoice.message.orEmpty(),
                    )
                }
                setEffect(SendEffect.NavigateToAmount)
            }

            is Scanner.Lightning -> {
                toast("Lightning coming soon: ${scan.invoice}")
            }

            null -> {
                // TODO implement
                toast("Error…")
            }

            else -> {
                // TODO implement
                toast("Coming soon…")
            }
        }
    }

    private suspend fun decodeDataOrNull(data: String): Scanner? {
        val result = runCatching { scannerService.decode(data) }
            .onFailure { Log.e(APP, "Failed to decode input data", it) }
            .getOrNull()

        _scanResult = result
        return result
    }

    private suspend fun send(bolt11: String) {
        runCatching { lightningService.send(bolt11) }
            .onFailure { withContext(uiThread) { toast("Error sending: $it") } }
    }

    private fun onScanSuccess(data: String) {
        viewModelScope.launch {
            val scan = decodeDataOrNull(data)
            handleData(scan)
        }
    }

    private fun onAddressContinue(data: String) {
        viewModelScope.launch {
            val scan = decodeDataOrNull(data)
            handleData(scan)
        }
    }

    private fun onAmountContinue(amount: String) {
        _uiState.update {
            it.copy(
                amount = amount.toULongOrNull() ?: 0u,
            )
        }
        setEffect(SendEffect.NavigateToReview)
    }

    private fun onPay() {
        toast("Coming soon")
    }

    override fun onCleared() {
        super.onCleared()
        Log.w(APP, "🚫 SendViewModel cleared")
    }
}

// region contract
data class SendUiState(
    val address: String = "",
    val amount: ULong = 0u,
    val label: String = "",
    val message: String = "",
)

sealed class SendEffect {
    data object NavigateToAddress : SendEffect()
    data object NavigateToAmount : SendEffect()
    data object NavigateToReview : SendEffect()
}

sealed class SendEvent {
    data object EnterManually : SendEvent()
    data class Paste(val data: String) : SendEvent()
    data class Scan(val data: String) : SendEvent()
    data class AddressContinue(val data: String) : SendEvent()
    data class AmountContinue(val amount: String) : SendEvent()
    data object SwipeToPay : SendEvent()
}
// endregion
