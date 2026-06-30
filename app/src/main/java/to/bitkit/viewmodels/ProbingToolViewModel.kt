package to.bitkit.viewmodels

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
import to.bitkit.ext.callbackAmountMsats
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.maxSendableSat
import to.bitkit.ext.minSendableSat
import to.bitkit.ext.nowMs
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.totalNextOutboundHtlcLimitSats
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LnurlPayInvoiceMismatchError
import to.bitkit.repositories.ProbeError
import to.bitkit.repositories.ProbeOutcome
import to.bitkit.services.CoreService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
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
        val pastedInvoice = context.getClipboardText()?.trim()

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

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun sendProbe() {
        val input = _uiState.value.invoice.trim()
        if (input.isEmpty()) {
            viewModelScope.launch {
                ToastEventBus.send(type = Toast.ToastType.WARNING, title = "Please enter an invoice or node ID")
            }
            return
        }

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true, probeResult = null) }
            val startTime = Clock.System.nowMs()

            try {
                val state = _uiState.value
                val userAmount = state.amountSats.toULongOrNull()
                val nodeId = extractNodeId(input)
                val isNodeIdTarget = nodeId != null

                if (isNodeIdTarget && (userAmount == null || userAmount == 0uL)) {
                    ToastEventBus.send(type = Toast.ToastType.WARNING, title = "Please enter an amount")
                    return@launch
                }

                if (state.isLnurlPay && (userAmount == null || userAmount == 0uL)) {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = "Please enter an amount",
                    )
                    return@launch
                }

                val amountSats = when {
                    isNodeIdTarget -> userAmount
                    state.isLnurlPay -> userAmount
                    else -> userAmount ?: 1uL.takeIf { state.isZeroAmountInvoice }
                }

                val bolt11 = if (isNodeIdTarget) null else extractBolt11Invoice(input, amountSats)
                if (!isNodeIdTarget && bolt11 == null) {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = "Invalid target",
                        description = "Could not extract Lightning invoice",
                    )
                    return@launch
                }

                if (!isNodeIdTarget && bolt11 != null) {
                    val effectiveAmount = amountSats ?: getInvoiceAmount(input)
                    if (effectiveAmount != null && effectiveAmount > 0uL) {
                        val outbound = lightningRepo.lightningState.value.channels
                            .totalNextOutboundHtlcLimitSats()
                        val estimatedFee = getEstimatedFee(bolt11, amountSats)

                        val nearCapacityThreshold = outbound * 95uL / 100uL
                        if (estimatedFee == null && effectiveAmount >= nearCapacityThreshold) {
                            ToastEventBus.send(
                                type = Toast.ToastType.WARNING,
                                title = "Amount too close to capacity",
                                description = "Available: $BITCOIN_SYMBOL $outbound. " +
                                    "Reduce amount to leave room for routing fees.",
                            )
                            return@launch
                        }

                        val totalRequired = effectiveAmount + (estimatedFee ?: 0uL)
                        if (!lightningRepo.canSend(totalRequired)) {
                            ToastEventBus.send(
                                type = Toast.ToastType.WARNING,
                                title = "Amount + fees exceed capacity",
                                description = "Needed: $BITCOIN_SYMBOL $totalRequired" +
                                    "(includes ~${estimatedFee ?: 0uL} fee), " +
                                    "available: $BITCOIN_SYMBOL $outbound",
                            )
                            return@launch
                        }
                    }
                }

                val dispatch = if (isNodeIdTarget) {
                    lightningRepo.sendProbeForNode(requireNotNull(nodeId), requireNotNull(amountSats))
                } else {
                    lightningRepo.sendProbeForInvoice(requireNotNull(bolt11), amountSats)
                }

                dispatch
                    .onSuccess { probe ->
                        lightningRepo.waitForProbeOutcome(probe.paymentIds)
                            .onSuccess { handleProbeOutcome(startTime, it, bolt11, amountSats) }
                            .onFailure { handleProbeFailure(startTime, it) }
                    }
                    .onFailure { handleProbeFailure(startTime, it) }
            } catch (error: LnurlPayInvoiceMismatchError) {
                handleProbeFailure(startTime, error)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun detectInputType(input: String) {
        viewModelScope.launch(bgDispatcher) {
            val trimmed = input.trim()
            if (extractNodeId(trimmed) != null) {
                updateInputType(isNodeId = true)
                return@launch
            }

            val data = runCatching { coreService.decode(trimmed) }.getOrNull()
            when (data) {
                is Scanner.LnurlPay -> {
                    val min = data.data.minSendableSat()
                    val max = data.data.maxSendableSat()
                    val isFixed = min == max && min > 0uL
                    updateInputType(isLnurlPay = true, amountSats = min.toString().takeIf { isFixed })
                }

                is Scanner.Lightning if data.invoice.amountSatoshis == 0uL -> {
                    updateInputType(isZeroAmountInvoice = true)
                }

                is Scanner.OnChain -> {
                    updateInputType(isZeroAmountInvoice = data.hasZeroAmountLightningParam())
                }

                else -> {
                    updateInputType()
                }
            }
        }
    }

    private fun updateInputType(
        isNodeId: Boolean = false,
        isLnurlPay: Boolean = false,
        isZeroAmountInvoice: Boolean = false,
        amountSats: String? = null,
    ) {
        _uiState.update {
            it.copy(
                isNodeId = isNodeId,
                isLnurlPay = isLnurlPay,
                isZeroAmountInvoice = isZeroAmountInvoice,
                amountSats = amountSats ?: it.amountSats,
            )
        }
    }

    private suspend fun Scanner.OnChain.hasZeroAmountLightningParam(): Boolean {
        val lightningParam = invoice.params?.get("lightning")
        val lightning = lightningParam?.let {
            runCatching { coreService.decode(it) }.getOrNull() as? Scanner.Lightning
        }
        return lightning?.invoice?.amountSatoshis == 0uL
    }

    private suspend fun extractBolt11Invoice(input: String, amountSats: ULong?): String? {
        return runSuspendCatching {
            when (val decoded = coreService.decode(input)) {
                is Scanner.Lightning -> decoded.invoice.bolt11
                is Scanner.OnChain -> {
                    val lightningParam = decoded.invoice.params?.get("lightning") ?: return@runSuspendCatching null
                    (coreService.decode(lightningParam) as? Scanner.Lightning)?.invoice?.bolt11
                }

                is Scanner.LnurlPay -> {
                    val amount = amountSats ?: return@runSuspendCatching null
                    lightningRepo.fetchLnurlInvoice(
                        data = decoded.data,
                        amountMsats = decoded.data.callbackAmountMsats(amount),
                    ).getOrThrow().bolt11
                }

                else -> null
            }
        }.getOrElse {
            if (it is LnurlPayInvoiceMismatchError) throw it
            null
        }
    }

    private suspend fun handleProbeOutcome(
        startTime: Long,
        outcome: ProbeOutcome,
        invoice: String?,
        amountSats: ULong?,
    ) {
        val durationMs = Clock.System.nowMs() - startTime
        when (outcome) {
            is ProbeOutcome.Success -> {
                Logger.info(
                    "Received successful probe outcome for paymentId='${outcome.paymentId}' in '${durationMs}ms'",
                    context = TAG,
                )

                val estimatedFee = invoice?.let { getEstimatedFee(it, amountSats) }
                _uiState.update {
                    it.copy(
                        probeResult = ProbeResult(
                            success = true,
                            durationMs = durationMs,
                            estimatedFeeSats = estimatedFee,
                        )
                    )
                }
                ToastEventBus.send(type = Toast.ToastType.SUCCESS, title = "Probe successful")
            }

            is ProbeOutcome.Failure -> {
                Logger.info(
                    "Received failed probe outcome for paymentId='${outcome.paymentId}' " +
                        "paymentHash='${outcome.paymentHash}' shortChannelId='${outcome.shortChannelId}'",
                    context = TAG,
                )

                val message = outcome.shortChannelId?.let { "No route found (SCID: $it)" } ?: "No route found"
                _uiState.update {
                    it.copy(
                        probeResult = ProbeResult(
                            success = false,
                            durationMs = durationMs,
                            errorMessage = message,
                        )
                    )
                }
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Probe failed", description = message)
            }
        }
    }

    private suspend fun handleProbeFailure(startTime: Long, error: Throwable) {
        val durationMs = Clock.System.nowMs() - startTime
        Logger.error("Failed probe in '${durationMs}ms'", error, context = TAG)

        val friendlyMessage = getFriendlyErrorMessage(error)
        _uiState.update {
            it.copy(
                probeResult = ProbeResult(
                    success = false,
                    durationMs = durationMs,
                    errorMessage = friendlyMessage,
                )
            )
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
        private const val NODE_ID_HEX_LENGTH = 66

        private fun getFriendlyErrorMessage(error: Throwable): String {
            if (error is ProbeError.NoProbeHandles) return "Probe was likely skipped"
            if (error is ProbeError.TimedOut) return "Probe timed out"

            val msg = error.message ?: return "Unknown error"
            return when {
                msg.contains("RouteNotFound", ignoreCase = true) -> "No route found to destination"
                msg.contains("InsufficientFunds", ignoreCase = true) -> "Insufficient funds for this probe"
                msg.contains("PaymentPathFailed", ignoreCase = true) -> "Payment path failed"
                msg.contains("SendingFailed", ignoreCase = true) -> "Probe sending failed"
                else -> msg
            }
        }

        private fun extractNodeId(input: String): String? {
            val trimmed = input.trim()
            val nodeId = trimmed.substringBefore("@")
            return nodeId.takeIf { it.length == NODE_ID_HEX_LENGTH && it.all(::isHexChar) }
        }

        private fun isHexChar(value: Char): Boolean =
            value.isDigit() || value.lowercaseChar() in 'a'..'f'
    }
}

@Stable
data class ProbingToolUiState(
    val invoice: String = "",
    val amountSats: String = "",
    val isLoading: Boolean = false,
    val isNodeId: Boolean = false,
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
