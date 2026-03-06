package to.bitkit.ui.screens.wallets.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ext.rawId
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class SendPendingViewModel @Inject constructor(
    private val pendingPaymentRepo: PendingPaymentRepo,
    private val activityRepo: ActivityRepo,
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
        pendingPaymentRepo.setActiveHash(paymentHash)
        _uiState.update { it.copy(amount = amount) }
        findActivity(paymentHash)
        observeResolution(paymentHash)
    }

    override fun onCleared() {
        pendingPaymentRepo.setActiveHash(null)
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

    private fun observeResolution(paymentHash: String) {
        viewModelScope.launch {
            pendingPaymentRepo.resolution
                .filter { it.paymentHash == paymentHash }
                .collect { resolution ->
                    Logger.info(
                        "Received payment resolution '${resolution::class.simpleName}' for '$paymentHash'",
                        context = TAG,
                    )
                    _uiState.update { it.copy(resolution = resolution) }
                }
        }
    }
}

data class SendPendingUiState(
    val amount: Long = 0L,
    val activityId: String? = null,
    val resolution: PendingPaymentResolution? = null,
)
