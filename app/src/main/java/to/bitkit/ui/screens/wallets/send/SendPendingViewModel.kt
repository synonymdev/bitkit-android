package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.rawId
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.ui.screens.wallets.send.SendPendingUiState.Resolution
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class SendPendingViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val activityRepo: ActivityRepo,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "SendPendingViewModel"
    }

    private val _uiState = MutableStateFlow(SendPendingUiState())
    val uiState = _uiState.asStateFlow()

    private var isInitialized = false

    fun init(paymentHash: String, amount: Long) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update { it.copy(amount = amount) }
        findActivity(paymentHash)
        observeResolution(paymentHash, amount)
    }

    fun onResolutionHandled() = _uiState.update { it.copy(resolution = null) }

    private fun findActivity(paymentHash: String) {
        viewModelScope.launch {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHash,
                type = ActivityFilter.LIGHTNING,
                txType = PaymentType.SENT,
                retry = true,
            ).onSuccess {
                _uiState.update { state -> state.copy(activityId = it.rawId()) }
            }
        }
    }

    private fun observeResolution(paymentHash: String, amount: Long) {
        viewModelScope.launch {
            lightningRepo.pendingPaymentResolution
                .filter { it.paymentHash == paymentHash }
                .collect { resolution ->
                    Logger.info(
                        "Received payment resolution '${resolution::class.simpleName}' for '$paymentHash'",
                        context = TAG,
                    )
                    _uiState.update {
                        it.copy(
                            resolution = when (resolution) {
                                is PendingPaymentResolution.Success -> Resolution.Success(
                                    NewTransactionSheetDetails(
                                        type = NewTransactionSheetType.LIGHTNING,
                                        direction = NewTransactionSheetDirection.SENT,
                                        paymentHashOrTxId = resolution.paymentHash,
                                        sats = amount,
                                    )
                                )

                                is PendingPaymentResolution.Failure -> Resolution.Error(
                                    resolution.reason ?: context.getString(R.string.wallet__toast_payment_failed_title)
                                )
                            }
                        )
                    }
                }
        }
    }
}

data class SendPendingUiState(
    val amount: Long = 0L,
    val activityId: String? = null,
    val resolution: Resolution? = null,
) {
    sealed interface Resolution {
        data class Success(val details: NewTransactionSheetDetails) : Resolution
        data class Error(val message: String) : Resolution
    }
}
