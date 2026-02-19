package to.bitkit.viewmodels

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Scanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.minSendableSat
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.CoreService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ProbingToolViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProbingToolUiState())
    val uiState = _uiState.asStateFlow()

    fun updateInvoice(invoice: String) {
        _uiState.update { it.copy(invoice = invoice, probeResult = null) }
        detectInputType(invoice)
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

        updateInvoice(pastedInvoice)
    }

    fun sendProbe() {
        val input = _uiState.value.invoice.trim()
        if (input.isEmpty()) {
            viewModelScope.launch {
                ToastEventBus.send(type = Toast.ToastType.WARNING, title = "Please enter an invoice")
            }
            return
        }

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true, probeResult = null) }

            val userAmount = _uiState.value.amountSats.toULongOrNull()
            val amountSats = userAmount ?: 1uL.takeIf { _uiState.value.isZeroAmountInvoice }

            val bolt11 = extractBolt11Invoice(input, amountSats)
            if (bolt11 == null) {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Invalid invoice format",
                    description = "Could not extract Lightning invoice",
                )
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val effectiveAmount = amountSats ?: getInvoiceAmount(input)
            if (effectiveAmount != null && effectiveAmount > 0uL && !lightningRepo.canSend(effectiveAmount)) {
                val outbound = lightningRepo.lightningState.value.channels.totalNextOutboundHtlcLimitSats()
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = "Amount exceeds outbound capacity",
                    description = "Available: $outbound sats",
                )
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val startTime = System.currentTimeMillis()

            lightningRepo.sendProbeForInvoice(bolt11, amountSats)
                .onSuccess { handleProbeSuccess(startTime, bolt11, amountSats) }
                .onFailure { handleProbeFailure(startTime, it) }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun detectInputType(input: String) {
        viewModelScope.launch(bgDispatcher) {
            val data = runCatching { coreService.decode(input.trim()) }.getOrNull()
            when {
                data is Scanner.LnurlPay -> {
                    val min = data.data.minSendableSat()
                    val max = data.data.maxSendableSat()
                    val isFixed = min == max && min > 0uL
                    _uiState.update {
                        it.copy(
                            isLnurlPay = true,
                            isZeroAmountInvoice = false,
                            amountSats = if (isFixed) min.toString() else it.amountSats,
                        )
                    }
                }

                data is Scanner.Lightning && data.invoice.amountSatoshis == 0uL -> {
                    _uiState.update { it.copy(isLnurlPay = false, isZeroAmountInvoice = true) }
                }

                else -> {
                    _uiState.update { it.copy(isLnurlPay = false, isZeroAmountInvoice = false) }
                }
            }
        }
    }

    private suspend fun extractBolt11Invoice(input: String, amountSats: ULong?): String? = runCatching {
        when (val decoded = coreService.decode(input)) {
            is Scanner.Lightning -> decoded.invoice.bolt11
            is Scanner.OnChain -> {
                val lightningParam = decoded.invoice.params?.get("lightning") ?: return@runCatching null
                (coreService.decode(lightningParam) as? Scanner.Lightning)?.invoice?.bolt11
            }

            is Scanner.LnurlPay -> {
                val amount = amountSats ?: return@runCatching null
                lightningRepo.fetchLnurlInvoice(decoded.data.callback, amount).getOrThrow().bolt11
            }

            else -> null
        }
    }.getOrNull()

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

        val friendlyMessage = getFriendlyErrorMessage(error)
        _uiState.update {
            it.copy(probeResult = ProbeResult(success = false, durationMs = durationMs, errorMessage = friendlyMessage))
        }
        ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Probe failed", description = friendlyMessage)
    }

    private suspend fun getInvoiceAmount(input: String): ULong? = runCatching {
        when (val decoded = coreService.decode(input.trim())) {
            is Scanner.Lightning -> decoded.invoice.amountSatoshis.takeIf { it > 0uL }
            else -> null
        }
    }.getOrNull()

    private suspend fun getEstimatedFee(invoice: String, amountSats: ULong?): ULong? = run {
        if (amountSats != null) {
            lightningRepo.estimateRoutingFeesForAmount(invoice, amountSats)
        } else {
            lightningRepo.estimateRoutingFees(invoice)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ProbingToolViewModel"

        private fun getFriendlyErrorMessage(error: Throwable): String {
            val msg = error.message ?: return "Unknown error"
            return when {
                msg.contains("RouteNotFound", ignoreCase = true) -> "No route found to destination"
                msg.contains("InsufficientFunds", ignoreCase = true) -> "Insufficient funds for this probe"
                msg.contains("PaymentPathFailed", ignoreCase = true) -> "Payment path failed"
                msg.contains("SendingFailed", ignoreCase = true) -> "Probe sending failed"
                else -> msg
            }
        }
    }
}

@Stable
data class ProbingToolUiState(
    val invoice: String = "",
    val amountSats: String = "",
    val isLoading: Boolean = false,
    val isLnurlPay: Boolean = false,
    val isZeroAmountInvoice: Boolean = false,
    val probeResult: ProbeResult? = null,
)

@Stable
data class ProbeResult(
    val success: Boolean,
    val durationMs: Long,
    val estimatedFeeSats: ULong? = null,
    val errorMessage: String? = null,
)
