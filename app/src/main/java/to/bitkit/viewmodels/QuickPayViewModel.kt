package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.toCompactFailureType
import to.bitkit.ext.toSendFailureDetails
import to.bitkit.models.SendFailureDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.QuickPayAlreadyPaidError
import to.bitkit.repositories.QuickPayConversionError
import to.bitkit.repositories.QuickPayPayRequest
import to.bitkit.repositories.QuickPayPaymentFailedError
import to.bitkit.repositories.QuickPayRepo
import to.bitkit.repositories.QuickPaySession
import to.bitkit.repositories.QuickPaySessionEvent
import javax.inject.Inject

@HiltViewModel
class QuickPayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val quickPayRepo: QuickPayRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(QuickPayUiState())
    val uiState = _uiState.asStateFlow()

    val lightningState = lightningRepo.lightningState
    private var session: QuickPaySession? = null
    private var resultJob: Job? = null
    private var isPayRequested = false

    fun attach(session: QuickPaySession) {
        this.session = session
        isPayRequested = false
        _uiState.update { QuickPayUiState() }
        resultJob?.cancel()
        resultJob = viewModelScope.launch {
            quickPayRepo.attach(session).collect { event ->
                _uiState.update { it.copy(result = event.toUiResult()) }
            }
        }
    }

    fun detach(session: QuickPaySession) {
        quickPayRepo.detach(session)
        if (this.session?.id == session.id) {
            this.session = null
        }
    }

    fun acknowledge(session: QuickPaySession) = quickPayRepo.acknowledge(session)

    fun pay(session: QuickPaySession, data: QuickPayData) {
        if (isPayRequested || _uiState.value.result != null) return
        isPayRequested = true
        quickPayRepo.pay(session, data.toPayRequest())
    }

    override fun onCleared() {
        session?.let { quickPayRepo.detach(it) }
        super.onCleared()
    }

    private fun QuickPaySessionEvent.toUiResult(): QuickPayResult = when (this) {
        is QuickPaySessionEvent.Success -> QuickPayResult.Success(
            paymentHash = paymentHash,
            amountWithFee = amountWithFee,
        )
        is QuickPaySessionEvent.Pending -> QuickPayResult.Pending(
            paymentHash = paymentHash,
            amount = amount,
            paymentRequest = paymentRequest,
        )
        QuickPaySessionEvent.FallBackToConfirm -> QuickPayResult.FallBackToConfirm
        is QuickPaySessionEvent.Error -> QuickPayResult.Error(error.toUiFailure(paymentRequest))
    }

    private fun Throwable.toUiFailure(paymentRequest: String?): SendFailureDetails {
        return when (this) {
            is QuickPayAlreadyPaidError -> SendFailureDetails(
                message = context.getString(R.string.wallet__send_quickpay__already_paid),
                failureType = toCompactFailureType(),
                resetRoutingCachesOnRetry = false,
                paymentRequest = paymentRequest,
            )
            is QuickPayConversionError -> SendFailureDetails(
                message = context.getString(R.string.wallet__send_quickpay__currency_conversion),
                failureType = toCompactFailureType(),
                resetRoutingCachesOnRetry = false,
            )
            is QuickPayPaymentFailedError -> reason.toSendFailureDetails(context, paymentRequest ?: this.paymentRequest)
            else -> toSendFailureDetails(context, paymentRequest)
        }
    }

    private fun QuickPayData.toPayRequest(): QuickPayPayRequest = when (this) {
        is QuickPayData.Bolt11 -> QuickPayPayRequest.Bolt11(bolt11 = bolt11, amountSats = sats)
        is QuickPayData.LnurlPay -> QuickPayPayRequest.LnurlPay(data = data, amountSats = sats)
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
        val paymentRequest: String,
    ) : QuickPayResult()

    data object FallBackToConfirm : QuickPayResult()

    data class Error(val failure: SendFailureDetails) : QuickPayResult()
}

data class QuickPayUiState(
    val result: QuickPayResult? = null,
)
