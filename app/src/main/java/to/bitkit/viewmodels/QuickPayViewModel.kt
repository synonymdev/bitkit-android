package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.ext.WatchResult
import to.bitkit.ext.toUserMessage
import to.bitkit.ext.watchUntil
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PaymentPendingException
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class QuickPayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "QuickPayViewModel"
    }

    private val _uiState = MutableStateFlow(QuickPayUiState())
    val uiState = _uiState.asStateFlow()

    val lightningState = lightningRepo.lightningState

    fun pay(quickPayData: QuickPayData) {
        viewModelScope.launch {
            val (bolt11, amount) = when (val data = quickPayData) {
                is QuickPayData.Bolt11 -> {
                    Logger.info("QuickPay: processing bolt11 invoice")
                    data.bolt11 to data.sats
                }

                is QuickPayData.LnurlPay -> {
                    Logger.info("QuickPay: fetching LNURL Pay invoice from callback")
                    val invoice = lightningRepo.fetchLnurlInvoice(callbackUrl = data.callback, amountSats = data.sats)
                        .getOrElse { error ->
                            _uiState.update {
                                it.copy(result = QuickPayResult.Error(error.message.orEmpty()))
                            }
                            return@launch
                        }
                    invoice.bolt11 to quickPayData.sats
                }
            }

            sendLightning(bolt11, amount)
                .onSuccess { paymentHash ->
                    Logger.info("QuickPay lightning payment successful")
                    _uiState.update {
                        it.copy(
                            result = QuickPayResult.Success(
                                paymentHash = paymentHash,
                                amountWithFee = amount.toLong() // TODO GET FEE WHEN AVAILABLE
                            )
                        )
                    }
                }.onFailure { error ->
                    if (error is PaymentPendingException) {
                        Logger.info("QuickPay lightning payment pending", context = TAG)
                        _uiState.update {
                            it.copy(
                                result = QuickPayResult.Pending(
                                    paymentHash = error.paymentHash,
                                    amount = amount.toLong(),
                                )
                            )
                        }
                        return@onFailure
                    }
                    Logger.error("QuickPay lightning payment failed", error, context = TAG)

                    _uiState.update {
                        it.copy(result = QuickPayResult.Error(error.message.orEmpty()))
                    }
                }
        }
    }

    private suspend fun sendLightning(
        bolt11: String,
        amount: ULong? = null,
    ): Result<PaymentId> {
        val hash = lightningRepo.payInvoice(bolt11 = bolt11, sats = amount)
            .onFailure { exception ->
                return Result.failure(exception)
            }
            .getOrDefault("")

        // Wait until matching payment event is received (with timeout for hold invoices)
        val result = lightningRepo.nodeEvents.watchUntil(
            timeout = LightningRepo.SEND_LIGHTNING_TIMEOUT,
        ) { event ->
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
                        val error = Exception(event.reason.toUserMessage(context))
                        WatchResult.Complete(Result.failure(error))
                    } else {
                        WatchResult.Continue()
                    }
                }

                else -> WatchResult.Continue()
            }
        }
        return result ?: Result.failure(PaymentPendingException(hash))
    }
}

sealed class QuickPayResult {
    data class Success(
        val paymentHash: String,
        val amountWithFee: Long,
    ) : QuickPayResult()

    data class Pending(
        val paymentHash: String,
        val amount: Long,
    ) : QuickPayResult()

    data class Error(val message: String) : QuickPayResult()
}

data class QuickPayUiState(
    val result: QuickPayResult? = null,
)
