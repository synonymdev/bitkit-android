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
        if (!validateInvoice(invoice)) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true, probeResult = null) }

            val amountSats = _uiState.value.amountSats.toULongOrNull()
            val startTime = System.currentTimeMillis()

            lightningRepo.sendProbeForInvoice(invoice, amountSats)
                .onSuccess { handleProbeSuccess(startTime, invoice, amountSats) }
                .onFailure { handleProbeFailure(startTime, it) }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun validateInvoice(invoice: String): Boolean {
        if (invoice.isEmpty()) {
            viewModelScope.launch {
                ToastEventBus.send(type = Toast.ToastType.WARNING, title = "Please enter an invoice")
            }
            return false
        }
        if (!invoice.lowercase().startsWith("ln")) {
            viewModelScope.launch {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Invalid invoice format",
                    description = "Invoice should start with 'ln'",
                )
            }
            return false
        }
        return true
    }

    private suspend fun handleProbeSuccess(startTime: Long, invoice: String, amountSats: ULong?) {
        val durationMs = System.currentTimeMillis() - startTime
        Logger.info("Probe successful for invoice in ${durationMs}ms", context = TAG)

        val estimatedFee = getEstimatedFee(invoice, amountSats)
        _uiState.update {
            it.copy(probeResult = ProbeResult(success = true, durationMs = durationMs, estimatedFeeSats = estimatedFee))
        }
        ToastEventBus.send(type = Toast.ToastType.SUCCESS, title = "Probe successful")
    }

    private suspend fun handleProbeFailure(startTime: Long, error: Throwable) {
        val durationMs = System.currentTimeMillis() - startTime
        Logger.error("Probe failed in ${durationMs}ms", error, context = TAG)

        _uiState.update {
            it.copy(probeResult = ProbeResult(success = false, durationMs = durationMs, errorMessage = error.message))
        }
        ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Probe failed", description = error.message)
    }

    private suspend fun getEstimatedFee(invoice: String, amountSats: ULong?): ULong? = run {
        if (amountSats != null) {
            lightningRepo.estimateRoutingFeesForAmount(invoice, amountSats)
        } else {
            lightningRepo.estimateRoutingFees(invoice)
        }.getOrNull()
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
    val probeResult: ProbeResult? = null,
)

@Stable
data class ProbeResult(
    val success: Boolean,
    val durationMs: Long,
    val estimatedFeeSats: ULong? = null,
    val errorMessage: String? = null,
)
