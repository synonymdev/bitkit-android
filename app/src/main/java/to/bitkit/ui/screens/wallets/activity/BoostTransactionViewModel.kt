package to.bitkit.ui.screens.wallets.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Txid
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class BoostTransactionViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoostTransactionUiState())
    val uiState = _uiState.asStateFlow()

    private val _boostTransactionEffect = MutableSharedFlow<BoostTransactionEffects>(extraBufferCapacity = 1)
    val boostTransactionEffect = _boostTransactionEffect.asSharedFlow()
    private fun setBoostTransactionEffect(effect: BoostTransactionEffects) =
        viewModelScope.launch { _boostTransactionEffect.emit(effect) }


    private var totalFeeSatsRecommended: ULong = 0U
    private var maxTotalFee: ULong = 0U
    private var feeRateRecommended: ULong = 0U

    private var activity: Activity.Onchain? = null

    fun setupActivity(activity: Activity.Onchain) {
        Logger.debug("Setup activity $activity", context = TAG)
        this.activity = activity

        val speed = TransactionSpeed.Fast

        viewModelScope.launch {
            lightningRepo.estimateTotalFee(speed = speed).onSuccess { totalFee ->
                totalFeeSatsRecommended = totalFee
                maxTotalFee = BigDecimal.valueOf(
                    activity.v1.value.toLong()
                ).times(
                    BigDecimal.valueOf(0.5)
                ).toLong().toULong()

                lightningRepo.getFeeRateForSpeed(speed).onSuccess { feeRate ->
                    feeRateRecommended = feeRate
                    _uiState.update {
                        it.copy(
                            totalFeeSats = totalFee,
                            feeRate = feeRate,
                            increaseEnabled = totalFee <= maxTotalFee,
                            loading = false
                        )
                    }
                }.onFailure { e ->
                    Logger.error("error getting fee rate", e, context = TAG)
                    setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed)
                }
            }.onFailure { e ->
                Logger.error("error getting total fee ", e, context = TAG)
                setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed)
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

            when(activity?.v1?.txType) {
                PaymentType.SENT -> {
                    lightningRepo.bumpFeeByRbf(
                        satsPerVByte = _uiState.value.feeRate.toUInt(),
                        originalTxId = activity?.v1?.txId.orEmpty()
                    ).onSuccess { newTxId ->
                        Logger.debug("Success boosting transaction. newTxId:$newTxId", context = TAG)
                        updateActivity(newTxId = newTxId, isRBF = true).onSuccess {
                            _uiState.update { it.copy(boosting = false) }
                            setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess)
                        }.onFailure { e ->
                            _uiState.update { it.copy(boosting = false) }
                            setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess)
                            Logger.warn("Boost successful but there was a failure updating the activity", e = e, context = TAG)
                        }
                    }.onFailure { e ->
                        Logger.error("Failure boosting transaction: ${e.message}", e, context = TAG)
                        setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed)
                        _uiState.update { it.copy(boosting = false) }
                    }
                }

                PaymentType.RECEIVED -> {
                    lightningRepo.accelerateByCpfp(
                        satsPerVByte = _uiState.value.feeRate.toUInt(),
                        originalTxId = activity?.v1?.txId.orEmpty(),
                        destinationAddress = walletRepo.getOnchainAddress()
                    ).onSuccess { newTxId ->
                        Logger.debug("Success boosting transaction. newTxId:$newTxId", context = TAG)
                        updateActivity(newTxId = newTxId, isRBF = true).onSuccess {
                            _uiState.update { it.copy(boosting = false) }
                            setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess)
                        }.onFailure { e ->
                            _uiState.update { it.copy(boosting = false) }
                            setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess)
                            Logger.warn("Boost successful but there was a failure updating the activity", e = e, context = TAG)
                        }
                    }.onFailure { e ->
                        Logger.error("Failure boosting transaction: ${e.message}", e, context = TAG)
                        setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed)
                        _uiState.update { it.copy(boosting = false) }
                    }
                }
                null -> {
                    Logger.error("Failure boosting transaction: null activity", context = TAG)
                    setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed)
                    _uiState.update { it.copy(boosting = false) }
                }
            }
        }
    }

    fun onChangeAmount(increase: Boolean) {
        viewModelScope.launch {

            val newFeeRate = if (increase) {
                _uiState.value.feeRate + 1U
            } else {
                _uiState.value.feeRate - 1U
            }

            _uiState.update {
                it.copy(
                    feeRate = newFeeRate,
                )
            }

            lightningRepo.estimateTotalFee(TransactionSpeed.Custom(newFeeRate.toUInt()))
                .onSuccess { newTotalFee ->
                    val maxFeeReached = newTotalFee >= maxTotalFee
                    val minFeeReached = newTotalFee <= (activity?.v1?.fee ?: 0u)

                    _uiState.update {
                        it.copy(
                            totalFeeSats = newTotalFee,
                            increaseEnabled = !maxFeeReached,
                            decreaseEnabled = !minFeeReached
                        )
                    }

                    if (maxFeeReached && increase) {
                        setBoostTransactionEffect(BoostTransactionEffects.OnMaxFee)
                    }

                    if (minFeeReached && !increase) {
                        setBoostTransactionEffect(BoostTransactionEffects.OnMinFee)
                    }
                }
        }

    }

    private suspend fun updateActivity(newTxId: Txid, isRBF: Boolean): Result<Unit> {
        Logger.debug("Searching activity $newTxId", context = TAG)
        return walletRepo.getOnChainActivityByTxId(txId = newTxId, txType = PaymentType.SENT).fold(
            onSuccess = { newActivity ->
                Logger.debug("Activity found $newActivity", context = TAG)
                (newActivity as? Activity.Onchain)?.let { newOnChainActivity ->
                    val updatedActivity = Activity.Onchain(
                        v1 = newOnChainActivity.v1.copy(
                            isBoosted = true,
                            txId = newTxId,
                        )
                    )

                    if (isRBF) {
                        walletRepo.updateActivity(id = updatedActivity.v1.id, activity = updatedActivity)
                    } else {
                        // TODO HANDLE CPFP
                        Result.failure(Exception("Not implemented"))
                    }
                } ?: Result.failure(Exception("Activity not onChain type"))
            },
            onFailure = { e ->
                Logger.error("Activity $newTxId not found", e = e, context = TAG)
                Result.failure(e)
            }
        )
    }

    companion object {
        private const val TAG = "BoostTransactionViewModel"
    }
}

sealed interface BoostTransactionEffects {
    data object OnBoostSuccess : BoostTransactionEffects
    data object OnBoostFailed : BoostTransactionEffects
    data object OnMaxFee : BoostTransactionEffects
    data object OnMinFee : BoostTransactionEffects
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
