package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.R
import to.bitkit.data.QuickPaySpendReservation
import to.bitkit.ext.WatchResult
import to.bitkit.ext.callbackAmountMsats
import to.bitkit.ext.supportPaymentRequest
import to.bitkit.ext.toCompactFailureType
import to.bitkit.ext.toSendFailureDetails
import to.bitkit.ext.watchUntil
import to.bitkit.models.SendFailureDetails
import to.bitkit.models.msatFloorOf
import to.bitkit.models.safe
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PaymentPendingException
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.QuickPayConversionError
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class QuickPayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val quickPayRepo: QuickPayRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "QuickPayViewModel"
    }

    private val _uiState = MutableStateFlow(QuickPayUiState())
    val uiState = _uiState.asStateFlow()

    val lightningState = lightningRepo.lightningState
    private var payJob: Job? = null

    fun pay(data: QuickPayData) {
        if (payJob?.isActive == true || _uiState.value.result != null) return
        payJob = viewModelScope.launch { payNow(data) }
    }

    internal suspend fun payNow(data: QuickPayData) {
        val invoice = resolveQuickPayInvoice(data) ?: return
        val reservation = reserveSpend(invoice.displaySats) ?: return

        sendLightning(invoice, reservation)
            .onSuccess { onPaymentSuccess(it.paymentHash, invoice.displaySats, it.feePaidSats) }
            .onFailure { onPaymentFailure(it, invoice, reservation) }
    }

    private suspend fun reserveSpend(amountSats: ULong): QuickPaySpendReservation? {
        val reserved = quickPayRepo.tryReserve(amountSats).getOrElse {
            setError(it)
            return null
        }
        if (reserved == null) {
            Logger.info("Skipping QuickPay pay: daily spend reserve failed for '$amountSats'", context = TAG)
            _uiState.update { it.copy(result = QuickPayResult.FallBackToConfirm) }
            return null
        }
        return reserved
    }

    private suspend fun onPaymentSuccess(paymentHash: String, displaySats: ULong, feePaidSats: ULong) {
        Logger.info("QuickPay lightning payment successful", context = TAG)
        quickPayRepo.clear(paymentHash)
        _uiState.update {
            it.copy(
                result = QuickPayResult.Success(
                    paymentHash = paymentHash,
                    amountWithFee = (displaySats.safe() + feePaidSats.safe()).toLong(),
                )
            )
        }
    }

    private suspend fun onPaymentFailure(
        error: Throwable,
        invoice: QuickPayInvoice,
        reservation: QuickPaySpendReservation,
    ) {
        if (error is PaymentPendingException) {
            Logger.info("QuickPay lightning payment pending", context = TAG)
            _uiState.update {
                it.copy(
                    result = QuickPayResult.Pending(
                        paymentHash = error.paymentHash,
                        amount = invoice.displaySats.toLong(),
                        paymentRequest = invoice.paymentRequest,
                    )
                )
            }
            return
        }
        Logger.error("QuickPay lightning payment failed", error, context = TAG)
        if (error is QuickPayPaymentFailedError) {
            quickPayRepo.release(error.paymentHash)
        } else {
            quickPayRepo.releaseUnbound(reservation)
        }
        handleQuickPayFailure(error, invoice)
    }

    private fun setError(error: Throwable, paymentRequest: String? = null) {
        val localizedMessage = when (error) {
            is QuickPayConversionError -> {
                context.getString(R.string.wallet__send_quickpay__currency_conversion)
            }
            else -> null
        }
        val failure = if (localizedMessage != null) {
            SendFailureDetails(
                message = localizedMessage,
                failureType = error.toCompactFailureType(),
                resetRoutingCachesOnRetry = false,
                paymentRequest = paymentRequest,
            )
        } else {
            error.toSendFailureDetails(context, paymentRequest)
        }
        _uiState.update { it.copy(result = QuickPayResult.Error(failure)) }
    }

    private suspend fun resolveQuickPayInvoice(data: QuickPayData): QuickPayInvoice? {
        return when (data) {
            is QuickPayData.Bolt11 -> {
                Logger.info("QuickPay: processing bolt11 invoice")
                QuickPayInvoice(data.bolt11, null, data.sats, data.bolt11)
            }

            is QuickPayData.LnurlPay -> {
                Logger.info("QuickPay: fetching LNURL Pay invoice from callback")
                lightningRepo.fetchLnurlInvoice(
                    data = data.data,
                    amountMsats = data.data.callbackAmountMsats(data.sats),
                ).fold(
                    onSuccess = { QuickPayInvoice(it.bolt11, null, data.sats, data.data.supportPaymentRequest()) },
                    onFailure = {
                        _uiState.update { state ->
                            state.copy(
                                result = QuickPayResult.Error(
                                    it.toSendFailureDetails(context, data.data.supportPaymentRequest())
                                )
                            )
                        }
                        null
                    },
                )
            }
        }
    }

    private fun handleQuickPayFailure(error: Throwable, invoice: QuickPayInvoice) {
        val failure = when (error) {
            is QuickPayPaymentFailedError -> error.reason.toSendFailureDetails(context, error.paymentRequest)
            else -> error.toSendFailureDetails(context, invoice.bolt11.ifBlank { invoice.fallbackPaymentRequest })
        }
        _uiState.update {
            it.copy(result = QuickPayResult.Error(failure))
        }
    }

    private suspend fun sendLightning(
        invoice: QuickPayInvoice,
        reservation: QuickPaySpendReservation,
    ): Result<SettledQuickPayPayment> {
        val hash = lightningRepo.payInvoice(bolt11 = invoice.bolt11, sats = invoice.amount)
            .onFailure { exception ->
                return Result.failure(exception)
            }
            .getOrDefault("")

        return coroutineScope {
            val settled = async {
                lightningRepo.nodeEvents.watchUntil(LightningRepo.SEND_LN_TIMEOUT) {
                    when (it) {
                        is Event.PaymentSuccessful if it.paymentHash == hash -> WatchResult.Complete(
                            Result.success(
                                SettledQuickPayPayment(
                                    paymentHash = hash,
                                    feePaidSats = msatFloorOf(it.feePaidMsat ?: 0u),
                                )
                            )
                        )

                        is Event.PaymentFailed if it.paymentHash == hash -> WatchResult.Complete(
                            Result.failure(
                                QuickPayPaymentFailedError(
                                    paymentHash = hash,
                                    reason = it.reason,
                                    paymentRequest = invoice.bolt11,
                                )
                            )
                        )

                        else -> WatchResult.Continue()
                    }
                }
            }
            quickPayRepo.remember(paymentHash = hash, reservation = reservation)
            val result = settled.await()
            if (result != null) return@coroutineScope result
            pendingPaymentRepo.track(hash)
            Result.failure(PaymentPendingException(hash))
        }
    }
}

private data class SettledQuickPayPayment(
    val paymentHash: PaymentId,
    val feePaidSats: ULong,
)

sealed class QuickPayResult {
    data class Success(
        val paymentHash: String,
        val amountWithFee: Long,
    ) : QuickPayResult()

    data class Pending(
        val paymentHash: String,
        val amount: Long,
        val paymentRequest: String,
    ) : QuickPayResult()

    data object FallBackToConfirm : QuickPayResult()

    data class Error(val failure: SendFailureDetails) : QuickPayResult()
}

data class QuickPayUiState(
    val result: QuickPayResult? = null,
)

private data class QuickPayInvoice(
    val bolt11: String,
    val amount: ULong?,
    val displaySats: ULong,
    val fallbackPaymentRequest: String,
) {
    val paymentRequest get() = bolt11.ifBlank { fallbackPaymentRequest }
}

private class QuickPayPaymentFailedError(
    val paymentHash: String,
    val reason: PaymentFailureReason?,
    val paymentRequest: String?,
) : AppError(reason?.name)
