package to.bitkit.viewmodels

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ProbingToolViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProbingToolUiState())
    val uiState = _uiState.asStateFlow()

    fun updateInvoice(invoice: String) {
        _uiState.update { it.copy(invoice = invoice) }
    }

    fun updateAmountSats(amount: String) {
        val filtered = amount.filter { it.isDigit() }
        _uiState.update { it.copy(amountSats = filtered) }
    }

    fun pasteInvoice() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        val pastedInvoice = clipData?.getItemAt(0)?.text?.toString()?.trim()

        if (pastedInvoice.isNullOrEmpty()) {
            viewModelScope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Clipboard is empty",
                )
            }
            return
        }

        _uiState.update { it.copy(invoice = pastedInvoice) }
    }

    fun sendProbe() {
        val invoice = _uiState.value.invoice.trim()
        if (invoice.isEmpty()) {
            viewModelScope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Please enter an invoice",
                )
            }
            return
        }

        if (!invoice.lowercase().startsWith("ln")) {
            viewModelScope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Invalid invoice format",
                    description = "Invoice should start with 'ln'",
                )
            }
            return
        }

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }

            val amountSats = _uiState.value.amountSats.toULongOrNull()

            lightningRepo.sendProbeForInvoice(invoice, amountSats)
                .onSuccess {
                    Logger.info("Probe successful for invoice", context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = "Probe successful",
                    )
                }
                .onFailure { e ->
                    Logger.error("Probe failed", e, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Probe failed",
                        description = e.message,
                    )
                }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        private const val TAG = "ProbingToolViewModel"
    }
}

@Stable
data class ProbingToolUiState(
    val invoice: String = "",
    val amountSats: String = "",
    val isLoading: Boolean = false,
)
