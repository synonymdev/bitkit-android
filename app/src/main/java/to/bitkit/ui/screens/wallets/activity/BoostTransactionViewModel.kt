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

    // Configuration constants
    private companion object {
        const val TAG = "BoostTransactionViewModel"
        const val MAX_FEE_PERCENTAGE = 0.5
        const val MIN_FEE_RATE = 1UL
        const val MAX_FEE_RATE = 100UL
        const val FEE_RATE_STEP = 1UL
    }

    // State variables
    private var totalFeeSatsRecommended: ULong = 0U
    private var maxTotalFee: ULong = 0U
    private var feeRateRecommended: ULong = 0U
    private var activity: Activity.Onchain? = null

    fun setupActivity(activity: Activity.Onchain) {
        Logger.debug("Setup activity $activity", context = TAG)
        this.activity = activity

        _uiState.update { it.copy(loading = true) }

        initializeFeeEstimates()
    }

    private fun initializeFeeEstimates() {
        viewModelScope.launch {
            try {
                val speed = TransactionSpeed.Fast
                val activityContent = activity?.v1 ?: run {
                    handleError("Activity value is null")
                    return@launch
                }

                // Calculate max fee (50% of transaction value)
                maxTotalFee = BigDecimal.valueOf(activityContent.value.toLong())
                    .multiply(BigDecimal.valueOf(MAX_FEE_PERCENTAGE))
                    .toLong()
                    .toULong()

                // Get recommended fee estimates
                val feeRateResult = when (activityContent.txType) {
                    PaymentType.SENT -> lightningRepo.getFeeRateForSpeed(speed = speed)
                    PaymentType.RECEIVED -> lightningRepo.calculateCpfpFeeRate(activityContent.txId)
                }

                val totalFeeResult = lightningRepo.estimateTotalFee(
                    speed = TransactionSpeed.Custom(
                        satsPerVByte = feeRateResult.getOrNull()?.toUInt() ?: 0u
                    )
                )

                when {
                    totalFeeResult.isSuccess && feeRateResult.isSuccess -> {
                        totalFeeSatsRecommended = totalFeeResult.getOrThrow()
                        feeRateRecommended = feeRateResult.getOrThrow()

                        updateUiStateWithFeeData(
                            totalFee = totalFeeSatsRecommended,
                            feeRate = feeRateRecommended
                        )
                    }

                    else -> {
                        val error = totalFeeResult.exceptionOrNull() ?: feeRateResult.exceptionOrNull()
                        handleError("Failed to get fee estimates: ${error?.message}", error)
                    }
                }
            } catch (e: Exception) {
                handleError("Unexpected error during fee estimation", e)
            }
        }
    }

    private fun updateUiStateWithFeeData(totalFee: ULong, feeRate: ULong) {
        val currentFee = activity?.v1?.fee ?: 0u
        val isIncreaseEnabled = totalFee < maxTotalFee && feeRate < MAX_FEE_RATE
        val isDecreaseEnabled = totalFee > currentFee && feeRate > MIN_FEE_RATE

        _uiState.update {
            it.copy(
                totalFeeSats = totalFee,
                feeRate = feeRate,
                increaseEnabled = isIncreaseEnabled,
                decreaseEnabled = isDecreaseEnabled,
                loading = false,
            )
        }
    }

    fun onClickEdit() {
        _uiState.update { it.copy(isDefaultMode = false) }
    }

    fun onClickUseSuggestedFee() {
        updateUiStateWithFeeData(
            totalFee = totalFeeSatsRecommended,
            feeRate = feeRateRecommended
        )
        _uiState.update { it.copy(isDefaultMode = true) }
    }

    fun onConfirmBoost() {
        val currentActivity = activity
        if (currentActivity == null) {
            handleError("Cannot boost: activity is null")
            return
        }

        _uiState.update { it.copy(boosting = true) }

        viewModelScope.launch {
            try {
                when (currentActivity.v1.txType) {
                    PaymentType.SENT -> handleRbfBoost(currentActivity)
                    PaymentType.RECEIVED -> handleCpfpBoost(currentActivity)
                }
            } catch (e: Exception) {
                handleError("Unexpected error during boost", e)
            }
        }
    }

    private suspend fun handleRbfBoost(activity: Activity.Onchain) {
        lightningRepo.bumpFeeByRbf(
            satsPerVByte = _uiState.value.feeRate.toUInt(),
            originalTxId = activity.v1.txId
        ).fold(
            onSuccess = { newTxId ->
                handleBoostSuccess(newTxId, isRBF = true)
            },
            onFailure = { error ->
                handleError("RBF boost failed: ${error.message}", error)
            }
        )
    }

    private suspend fun handleCpfpBoost(activity: Activity.Onchain) {
        lightningRepo.accelerateByCpfp(
            satsPerVByte = _uiState.value.feeRate.toUInt(),
            originalTxId = activity.v1.txId,
            destinationAddress = walletRepo.getOnchainAddress()
        ).fold(
            onSuccess = { newTxId ->
                handleBoostSuccess(newTxId, isRBF = false)
            },
            onFailure = { error ->
                handleError("CPFP boost failed: ${error.message}", error)
            }
        )
    }

    private suspend fun handleBoostSuccess(newTxId: Txid, isRBF: Boolean) {
        Logger.debug("Boost successful. newTxId: $newTxId", context = TAG)

        updateActivity(newTxId = newTxId, isRBF = isRBF).fold(
            onSuccess = {
                _uiState.update { it.copy(boosting = false) }
                setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess)
            },
            onFailure = { error ->
                // Boost succeeded but activity update failed - still consider it successful
                Logger.warn("Boost successful but activity update failed", e = error, context = TAG)
                _uiState.update { it.copy(boosting = false) }
                setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess)
            }
        )
    }

    fun onChangeAmount(increase: Boolean) {
        val currentFeeRate = _uiState.value.feeRate
        val newFeeRate = if (increase) {
            (currentFeeRate + FEE_RATE_STEP).coerceAtMost(MAX_FEE_RATE)
        } else {
            (currentFeeRate - FEE_RATE_STEP).coerceAtLeast(MIN_FEE_RATE)
        }

        if (newFeeRate == currentFeeRate) {
            // Rate didn't change, we're at the limit
            val effect = if (increase) {
                BoostTransactionEffects.OnMaxFee
            } else {
                BoostTransactionEffects.OnMinFee
            }
            setBoostTransactionEffect(effect)
            return
        }

        _uiState.update { it.copy(feeRate = newFeeRate) }

        viewModelScope.launch {
            lightningRepo.estimateTotalFee(TransactionSpeed.Custom(newFeeRate.toUInt()))
                .fold(
                    onSuccess = { newTotalFee ->
                        val currentFee = activity?.v1?.fee ?: 0u
                        val maxFeeReached = newTotalFee >= maxTotalFee
                        val minFeeReached = newTotalFee <= currentFee

                        updateUiStateWithFeeData(newTotalFee, newFeeRate)

                        // Send appropriate effect if we hit a limit
                        when {
                            maxFeeReached && increase -> {
                                setBoostTransactionEffect(BoostTransactionEffects.OnMaxFee)
                            }

                            minFeeReached && !increase -> {
                                setBoostTransactionEffect(BoostTransactionEffects.OnMinFee)
                            }
                        }
                    },
                    onFailure = { error ->
                        handleError("Failed to estimate fee for rate $newFeeRate", error)
                    }
                )
        }
    }

    private suspend fun updateActivity(newTxId: Txid, isRBF: Boolean): Result<Unit> {
        Logger.debug("Updating activity for txId: $newTxId", context = TAG)

        return walletRepo.getOnChainActivityByTxId(
            txId = newTxId,
            txType = PaymentType.SENT
        ).fold(
            onSuccess = { newActivity ->
                Logger.debug("Activity found: $newActivity", context = TAG)

                val newOnChainActivity = newActivity as? Activity.Onchain
                    ?: return Result.failure(Exception("Activity is not onchain type"))

                val updatedActivity = Activity.Onchain(
                    v1 = newOnChainActivity.v1.copy(
                        isBoosted = true,
                        txId = newTxId,
                    )
                )

                if (isRBF) {
                    // For RBF, update new activity and delete old one
                    walletRepo.updateActivity(
                        id = updatedActivity.v1.id,
                        updatedActivity = updatedActivity
                    ).fold(
                        onSuccess = {
                            // Delete the old activity
                            activity?.v1?.id?.let { oldId ->
                                walletRepo.deleteActivityById(oldId).map { Unit }
                            } ?: Result.success(Unit)
                        },
                        onFailure = { Result.failure(it) }
                    )
                } else {
                    // For CPFP, just update the activity
                    walletRepo.updateActivity(
                        id = updatedActivity.v1.id,
                        updatedActivity = updatedActivity
                    )
                }
            },
            onFailure = { error ->
                Logger.error("Activity $newTxId not found", e = error, context = TAG)
                Result.failure(error)
            }
        )
    }

    private fun handleError(message: String, error: Throwable? = null) {
        Logger.error(message, error, context = TAG)
        _uiState.update {
            it.copy(
                boosting = false,
                loading = false,
            )
        }
        setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed)
    }

    private fun setBoostTransactionEffect(effect: BoostTransactionEffects) {
        viewModelScope.launch {
            _boostTransactionEffect.emit(effect)
        }
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
    val estimateTime: String = "±10-20 minutes", // TODO: Implement dynamic time estimation
)
