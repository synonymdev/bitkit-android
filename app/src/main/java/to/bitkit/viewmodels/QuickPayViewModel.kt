package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.ext.WatchResult
import to.bitkit.ext.watchUntil
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class QuickPayViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val ldkNodeEventBus: LdkNodeEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickPayUiState())
    val uiState = _uiState.asStateFlow()

    val lightningState = lightningRepo.lightningState

    fun payInvoice(bolt11: String, amount: ULong? = null) {
        viewModelScope.launch {
            val result = sendLightning(bolt11, amount)
            if (result.isSuccess) {
                Logger.info("QuickPay lightning payment successful")
                _uiState.update { it.copy(result = QuickPayResult.Success) }
            } else {
                val error = result.exceptionOrNull()
                Logger.error("QuickPay lightning payment failed", error)

                _uiState.update {
                    it.copy(result = QuickPayResult.Error(error?.message ?: "Payment failed"))
                }
            }
        }
    }

    private suspend fun sendLightning(
        bolt11: String,
        amount: ULong? = null,
    ): Result<PaymentId> {
        val hash = lightningRepo.payInvoice(bolt11 = bolt11, sats = amount).getOrNull()
            ?: return Result.failure(Exception("Failed to initiate payment"))

        // Wait until matching payment event is received
        val result = ldkNodeEventBus.events.watchUntil { event ->
            when (event) {
                is Event.PaymentSuccessful -> {
                    if (event.paymentHash == hash) {
                        WatchResult.Complete(Result.success(hash))
                    } else {
                        WatchResult.Continue()
                    }
                }

                is Event.PaymentFailed -> {
                    if (event.paymentHash == hash) {
                        val error = Exception(event.reason?.name ?: "Unknown payment failure reason")
                        WatchResult.Complete(Result.failure(error))
                    } else {
                        WatchResult.Continue()
                    }
                }

                else -> WatchResult.Continue()
            }
        }
        return result
    }
}

sealed class QuickPayResult {
    data object Success : QuickPayResult()
    data class Error(val message: String) : QuickPayResult()
}

data class QuickPayUiState(
    val result: QuickPayResult? = null,
)
