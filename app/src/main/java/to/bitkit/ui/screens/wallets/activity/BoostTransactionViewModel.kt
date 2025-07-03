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

    private var totalFeeSatsRecommended: ULong = 0U
    private var feeRateRecommended: ULong = 0U

    private var activity: Activity.Onchain? = null

    fun setupActivity(activity: Activity.Onchain) {
        this.activity = activity

        val speed = TransactionSpeed.Fast

        viewModelScope.launch {
            lightningRepo.estimateTotalFee(speed = speed).onSuccess { totalFee ->
                totalFeeSatsRecommended = totalFee

                lightningRepo.getFeeRateForSpeed(speed).onSuccess { feeRate ->
                    feeRateRecommended = feeRate
                    _uiState.update {
                        it.copy(
                            totalFeeSats = totalFee,
                            feeRate = feeRate,
                        )
                    }
                }.onFailure { e ->
                    Logger.error("error getting fee rate", e, context = TAG)
                    //TODO Dismiss
                }
            }.onFailure { e ->
                Logger.error("error getting total fee ", e, context = TAG)
                //TODO Dismiss
            }
        }
    }

    fun onClickEdit() {
        _uiState.update { it.copy(isDefaultMode = false) }
    }

    fun onClickUseSuggestedFee() {
        _uiState.update {
            it.copy(
                totalFeeSats = totalFeeSatsRecommended,
                feeRate = feeRateRecommended,
                isDefaultMode = true
            )
        }
    }

    fun onConfirmBoost() {
        _uiState.update { it.copy(boosting = true) }
        viewModelScope.launch {
            lightningRepo.bumpFeeByRbf(
                satsPerVByte = _uiState.value.feeRate.toUInt(),
                originalTxId = activity?.v1?.txId.orEmpty()
            ).onSuccess {
                Logger.debug("Success boosting transaction", context = TAG)
//                setActivityDetailEffect(ActivityDetailEffects.OnBoostSuccess)
                //TODO REGISTER ACTIVITY
                _uiState.update { it.copy(boosting = false) }
            }.onFailure { e ->
                Logger.error("Failure boosting transaction: ${e.message}", e, context = TAG)
//                setActivityDetailEffect(ActivityDetailEffects.OnBoostFailed)
                _uiState.update { it.copy(boosting = false) }
            }
        }
    }

    fun onChangeAmount(increase: Boolean) {
        viewModelScope.launch {

            val newFeeRate = if (increase) {
                //TODO CHECK MAX FEE
                _uiState.value.feeRate + 1U
            } else {
                //TODO CHECK MIN FEE
                _uiState.value.feeRate - 1U
            }

            _uiState.update {
                it.copy(
                    feeRate = newFeeRate,
                    isDefaultMode = newFeeRate == feeRateRecommended
                )
            }

            lightningRepo.estimateTotalFee(TransactionSpeed.Custom(newFeeRate.toUInt()))
                .onSuccess { newTotalFee ->
                    _uiState.update {
                        it.copy(
                            totalFeeSats = newTotalFee,
                        )
                    }
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
    val isDefaultMode: Boolean = true,
    val decreaseEnabled: Boolean = true,
    val increaseEnabled: Boolean = true,
    val boosting: Boolean = false,
    val loading: Boolean = false,
    val estimateTime: String = "±10-20 minutes", //TODO IMPLEMENT TIME CONFIRMATION CALC
)
