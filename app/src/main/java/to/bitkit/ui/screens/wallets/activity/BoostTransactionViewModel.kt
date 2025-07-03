package to.bitkit.ui.screens.wallets.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.LightningRepo
import to.bitkit.utils.Logger

@HiltViewModel
class BoostTransactionViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoostTransactionUiState())
    val uiState = _uiState.asStateFlow()

    private var activity: Activity.Onchain? = null

    fun setupActivity(activity: Activity.Onchain) {
        this.activity = activity

        val speed = TransactionSpeed.Fast

        viewModelScope.launch {
            lightningRepo.estimateTotalFee(speed = speed).onSuccess { totalFee ->
                lightningRepo.getFeeRateForSpeed(speed).onSuccess { feeRate ->
                    _uiState.update {
                        it.copy(
                            totalFeeSats = totalFee,
                            feeRate = feeRate
                        )
                    }
                }.onFailure { e ->
                    Logger.error("error getting fee rate", e, context = TAG)
                    //TODO Dismiss
                }
            }.onFailure { e ->
                Logger.error("error getting total fee ",e, context = TAG)
                //TODO Dismiss
            }
        }
    }

    companion object {
        private const val TAG = "BoostTransactionViewModel"
    }
}

data class BoostTransactionUiState(
    val totalFeeSats: ULong = 0U,
    val feeRate: ULong = 0U,
)
