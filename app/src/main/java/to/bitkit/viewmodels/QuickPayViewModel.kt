package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.data.quickPayCapCents
import to.bitkit.data.quickPayReserveCents
import to.bitkit.ext.WatchResult
import to.bitkit.ext.callbackAmountMsats
import to.bitkit.ext.quickPaySpendDayKey
import to.bitkit.ext.supportPaymentRequest
import to.bitkit.ext.toCompactFailureType
import to.bitkit.ext.toSendFailureDetails
import to.bitkit.ext.watchUntil
import to.bitkit.models.SendFailureDetails
import to.bitkit.models.USD
import to.bitkit.models.msatFloorOf
import to.bitkit.models.safe
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PaymentPendingException
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class QuickPayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val currencyRepo: CurrencyRepo,
    private val cacheStore: CacheStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    companion object {
        private const val TAG = "QuickPayViewModel"
    }

    private val _uiState = MutableStateFlow(QuickPayUiState())
    val uiState = _uiState.asStateFlow()

    val lightningState = lightningRepo.lightningState

    fun pay(data: QuickPayData) {
        viewModelScope.launch { payNow(data) }
    }

    internal suspend fun payNow(data: QuickPayData) {
        val invoice = resolveQuickPayInvoice(data) ?: return
        val dayKey = quickPaySpendDayKey()
        val reservedCents = reserveSpend(invoice.displaySats, dayKey) ?: return

        sendLightning(invoice, reservedCents, dayKey)
            .onSuccess { onPaymentSuccess(it.paymentHash, invoice.displaySats, it.feePaidSats) }
            .onFailure { onPaymentFailure(it, invoice, reservedCents, dayKey) }
    }

    private suspend fun reserveSpend(amountSats: ULong, dayKey: String): Long? {
        val settings = settingsStore.data.first()
        val converted = currencyRepo.convertSatsToFiat(amountSats.toLong(), USD).getOrNull()
        if (converted == null) {
            setError(QuickPayCurrencyConversionError())
            return null
        }
        val reserveCents = quickPayReserveCents(converted.toUsdCents(), settings.quickPayAmount)
        val reserved = cacheStore.tryReserveQuickPaySpendCents(
            amountCents = reserveCents,
            dayKey = dayKey,
            dailyCapCents = quickPayCapCents(settings.quickPayAmount, settings.quickPayDailyLimitMultiplier),
        )
        if (!reserved) {
            Logger.info("Skipping QuickPay pay: daily spend reserve failed for '$amountSats'", context = TAG)
            _uiState.update { it.copy(result = QuickPayResult.FallBackToConfirm) }
            return null
        }
        return reserveCents
    }

    private suspend fun onPaymentSuccess(paymentHash: String, displaySats: ULong, feePaidSats: ULong) {
        Logger.info("QuickPay lightning payment successful")
        cacheStore.clearQuickPayReservation(paymentHash)
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
        reservedCents: Long,
        dayKey: String,
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
            cacheStore.releaseQuickPayReservation(error.paymentHash)
        } else {
            cacheStore.releaseQuickPaySpendCents(reservedCents, dayKey)
        }
        handleQuickPayFailure(error, invoice)
    }

    private fun setError(error: Throwable, paymentRequest: String? = null) {
        val localizedMessage = when (error) {
            is QuickPayCurrencyConversionError -> {
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
        reservedCents: Long,
        dayKey: String,
    ): Result<SettledQuickPayPayment> {
        val hash = lightningRepo.payInvoice(bolt11 = invoice.bolt11, sats = invoice.amount)
            .onFailure { exception ->
                return Result.failure(exception)
            }
            .getOrDefault("")

        cacheStore.rememberQuickPayReservation(
            paymentHash = hash,
            amountCents = reservedCents,
            dayKey = dayKey,
        )

        // Wait until matching payment event is received (with timeout for hold invoices)
        val result = lightningRepo.nodeEvents.watchUntil(LightningRepo.SEND_LN_TIMEOUT) {
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
        if (result != null) return result

        pendingPaymentRepo.track(hash)
        return Result.failure(PaymentPendingException(hash))
    }
}

private data class SettledQuickPayPayment(
    val paymentHash: PaymentId,
    val feePaidSats: ULong,
)

private class QuickPayCurrencyConversionError : AppError("Currency conversion failed")

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
