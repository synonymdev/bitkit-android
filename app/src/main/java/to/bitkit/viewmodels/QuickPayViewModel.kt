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
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.ext.WatchResult
import to.bitkit.ext.callbackAmountMsats
import to.bitkit.ext.quickPaySpendDayKey
import to.bitkit.ext.toUserMessage
import to.bitkit.ext.watchUntil
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
        viewModelScope.launch {
            val prepared = preparePayment(data) ?: return@launch
            val dayKey = quickPaySpendDayKey()
            if (!reserveSpend(prepared.amountUsd, dayKey)) return@launch

            sendLightning(prepared.bolt11, prepared.amount)
                .onSuccess { onPaymentSuccess(it, prepared.displaySats) }
                .onFailure { onPaymentFailure(it, prepared.displaySats, prepared.amountUsd, dayKey) }
        }
    }

    private suspend fun preparePayment(data: QuickPayData): PreparedQuickPay? {
        val (bolt11, amount, displaySats) = when (data) {
            is QuickPayData.Bolt11 -> {
                Logger.info("QuickPay: processing bolt11 invoice")
                Triple(data.bolt11, null, data.sats)
            }

            is QuickPayData.LnurlPay -> {
                Logger.info("QuickPay: fetching LNURL Pay invoice from callback")
                val invoice = lightningRepo.fetchLnurlInvoice(
                    data = data.data,
                    amountMsats = data.data.callbackAmountMsats(data.sats),
                ).getOrElse { error ->
                    _uiState.update { it.copy(result = QuickPayResult.Error(error.message.orEmpty())) }
                    return null
                }
                Triple(invoice.bolt11, null, data.sats)
            }
        }
        val amountUsd = currencyRepo.convertSatsToFiat(displaySats.toLong(), "USD").getOrNull()?.value?.toDouble()
        if (amountUsd == null) {
            _uiState.update { it.copy(result = QuickPayResult.Error("Currency conversion failed")) }
            return null
        }
        return PreparedQuickPay(bolt11, amount, displaySats, amountUsd)
    }

    private suspend fun reserveSpend(amountUsd: Double, dayKey: String): Boolean {
        val dailyCapUsd = resolveDailyCapUsd()
        if (dailyCapUsd == null) {
            _uiState.update { it.copy(result = QuickPayResult.Error("Currency conversion failed")) }
            return false
        }
        val reserved = cacheStore.tryReserveQuickPaySpendUsd(amountUsd, dayKey, dailyCapUsd)
        if (!reserved) {
            Logger.info("Skipping QuickPay pay: daily spend reserve failed for '$amountUsd'", context = TAG)
            _uiState.update { it.copy(result = QuickPayResult.Error("Daily QuickPay limit reached")) }
        }
        return reserved
    }

    private fun onPaymentSuccess(paymentHash: String, displaySats: ULong) {
        Logger.info("QuickPay lightning payment successful")
        _uiState.update {
            it.copy(
                result = QuickPayResult.Success(
                    paymentHash = paymentHash,
                    amountWithFee = displaySats.toLong() // TODO GET FEE WHEN AVAILABLE
                )
            )
        }
    }

    private suspend fun onPaymentFailure(
        error: Throwable,
        displaySats: ULong,
        amountUsd: Double,
        dayKey: String,
    ) {
        if (error is PaymentPendingException) {
            Logger.info("QuickPay lightning payment pending", context = TAG)
            pendingPaymentRepo.track(error.paymentHash)
            _uiState.update {
                it.copy(
                    result = QuickPayResult.Pending(
                        paymentHash = error.paymentHash,
                        amount = displaySats.toLong(),
                    )
                )
            }
            return
        }
        Logger.error("QuickPay lightning payment failed", error, context = TAG)
        cacheStore.releaseQuickPaySpendUsd(amountUsd, dayKey)
        _uiState.update { it.copy(result = QuickPayResult.Error(error.message.orEmpty())) }
    }

    private suspend fun resolveDailyCapUsd(): Double? {
        val settings = settingsStore.data.first()
        val thresholdSats = currencyRepo.convertFiatToSats(settings.quickPayAmount.toDouble(), "USD").getOrNull()
            ?: return null
        val dailyCapSats = thresholdSats * settings.quickPayDailyLimitMultiplier.toULong()
        return currencyRepo.convertSatsToFiat(dailyCapSats.toLong(), "USD").getOrNull()?.value?.toDouble()
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
        val result = lightningRepo.nodeEvents.watchUntil(LightningRepo.SEND_LN_TIMEOUT) {
            when (it) {
                is Event.PaymentSuccessful if it.paymentHash == hash -> WatchResult.Complete(Result.success(hash))
                is Event.PaymentFailed if it.paymentHash == hash -> WatchResult.Complete(
                    Result.failure(AppError(it.reason.toUserMessage(context)))
                )

                else -> WatchResult.Continue()
            }
        }
        return result ?: Result.failure(PaymentPendingException(hash))
    }
}

private data class PreparedQuickPay(
    val bolt11: String,
    val amount: ULong?,
    val displaySats: ULong,
    val amountUsd: Double,
)

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
