package to.bitkit.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Txid
import to.bitkit.data.dto.PendingBoostActivity
import to.bitkit.ext.BoostType
import to.bitkit.ext.boostType
import to.bitkit.ext.nowMillis
import to.bitkit.ext.nowTimestamp
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class BoostTransactionViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoostTransactionUiState())
    val uiState = _uiState.asStateFlow()

    private val _boostTransactionEffect = MutableSharedFlow<BoostTransactionEffects>(extraBufferCapacity = 1)
    val boostTransactionEffect = _boostTransactionEffect.asSharedFlow()

    // Configuration constants
    private companion object {
        const val TAG = "BoostTransactionViewModel"
        const val MAX_FEE_PERCENTAGE = 0.5
        const val MAX_FEE_RATE = 100UL
        const val FEE_RATE_STEP = 1UL
        const val RBF_MIN_INCREASE = 2UL
    }

    // State variables
    private var totalFeeSatsRecommended: ULong = 0U
    private var maxTotalFee: ULong = 0U
    private var feeRateRecommended: ULong = 0U
    private var minFeeRate: ULong = 2U
    private var activity: Activity.Onchain? = null

    fun setupActivity(activity: Activity.Onchain) {
        Logger.debug("Setup activity $activity", context = TAG)
        this.activity = activity

        _uiState.update {
            it.copy(
                loading = true,
                isRbf = activity.boostType() == BoostType.RBF,
            )
        }

        initializeFeeEstimates()
    }

    private fun initializeFeeEstimates() {
        viewModelScope.launch {
            try {
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
                    PaymentType.SENT -> {
                        // For RBF, use at least the original fee rate + 2 sat/vbyte, with a minimum of 2 sat/vbyte
                        minFeeRate = (activityContent.feeRate + RBF_MIN_INCREASE)
                        lightningRepo.getFeeRateForSpeed(speed = TransactionSpeed.Fast)
                    }

                    PaymentType.RECEIVED -> lightningRepo.calculateCpfpFeeRate(activityContent.txId)
                }

                val sortedUtxos = lightningRepo.listSpendableOutputs()
                    .getOrDefault(emptyList())
                    .sortedByDescending { it.valueSats }

                val totalFeeResult = lightningRepo.calculateTotalFee(
                    amountSats = activityContent.value,
                    utxosToSpend = sortedUtxos,
                    speed = TransactionSpeed.Custom(
                        feeRateResult
                            .getOrDefault(minFeeRate)
                            .coerceAtLeast(minFeeRate)
                            .toUInt()
                    ),
                )

                when {
                    totalFeeResult.isSuccess && feeRateResult.isSuccess -> {
                        totalFeeSatsRecommended = totalFeeResult.getOrThrow()
                        feeRateRecommended = feeRateResult
                            .getOrDefault(minFeeRate)
                            .coerceAtLeast(minFeeRate)

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
        val isDecreaseEnabled = totalFee > currentFee && feeRate > minFeeRate

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
            destinationAddress = walletRepo.getOnchainAddress(),
        ).fold(
            onSuccess = { newTxId ->
                // For CPFP, immediately update parent with child txId
                Logger.debug("CPFP successful. Appending child txId $newTxId to parent's boostTxIds", context = TAG)
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
            (currentFeeRate - FEE_RATE_STEP).coerceAtLeast(minFeeRate)
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
            lightningRepo
                .calculateTotalFee(
                    amountSats = requireNotNull(activity).v1.value,
                    speed = TransactionSpeed.Custom(newFeeRate.toUInt()),
                )
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

    /**
     * Updates activity based on boost type:
     * - RBF: Updates current activity with boost data, then replaces with new transaction
     * - CPFP: Updates parent activity by appending child txId to boostTxIds
     */
    private suspend fun updateActivity(newTxId: Txid, isRBF: Boolean): Result<Unit> {
        Logger.debug("Updating activity for txId: $newTxId. isRBF: $isRBF", context = TAG)

        val currentActivity = activity?.v1
            ?: return Result.failure(Exception("Activity required"))

        return if (isRBF) {
            handleRBFUpdate(newTxId, currentActivity)
        } else {
            handleCPFPUpdate(currentActivity, newTxId)
        }
    }

    /**
     * Handles CPFP (Child Pays For Parent) update by appending child txId to parent's boostTxIds
     */
    private suspend fun handleCPFPUpdate(currentActivity: OnchainActivity, childTxId: Txid): Result<Unit> {
        val updatedActivity = Activity.Onchain(
            v1 = currentActivity.copy(
                isBoosted = true,
                boostTxIds = currentActivity.boostTxIds + childTxId,
                updatedAt = nowMillis().toULong(),
            )
        )

        return activityRepo.updateActivity(
            id = updatedActivity.v1.id,
            activity = updatedActivity,
        )
    }

    /**
     * Handles RBF (Replace By Fee) update by updating current activity and replacing with new one
     */
    private suspend fun handleRBFUpdate(
        newTxId: Txid,
        currentActivity: OnchainActivity,
    ): Result<Unit> {
        // First update the current activity to show boost status
        val updatedCurrentActivity = Activity.Onchain(
            v1 = currentActivity.copy(
                isBoosted = true,
                feeRate = _uiState.value.feeRate,
                fee = _uiState.value.totalFeeSats,
                updatedAt = nowMillis().toULong(),
            )
        )

        activityRepo.updateActivity(
            id = updatedCurrentActivity.v1.id,
            activity = updatedCurrentActivity,
        )

        // Then find and replace with the new activity
        return findAndReplaceWithNewActivity(newTxId, currentActivity.id)
    }

    /**
     * Finds the new activity and replaces the old one, handling failures gracefully
     */
    private suspend fun findAndReplaceWithNewActivity(
        newTxId: Txid,
        oldActivityId: String,
    ): Result<Unit> {
        return activityRepo.findActivityByPaymentId(
            paymentHashOrTxId = newTxId,
            type = ActivityFilter.ONCHAIN,
            txType = PaymentType.SENT
        ).fold(
            onSuccess = { newActivity ->
                replaceActivityWithNewOne(newActivity, oldActivityId, newTxId)
            },
            onFailure = { error ->
                handleActivityNotFound(error, newTxId, oldActivityId)
            }
        )
    }

    /**
     * Replaces the old activity with the new boosted one
     */
    private suspend fun replaceActivityWithNewOne(
        newActivity: Activity,
        oldActivityId: String,
        newTxId: Txid,
    ): Result<Unit> {
        Logger.debug("Activity found: $newActivity", context = TAG)

        val newOnChainActivity = newActivity as? Activity.Onchain
            ?: return Result.failure(Exception("Activity is not onchain type"))

        val updatedNewActivity = Activity.Onchain(
            v1 = newOnChainActivity.v1.copy(
                isBoosted = true,
                feeRate = _uiState.value.feeRate,
                updatedAt = nowMillis().toULong(),
            )
        )

        return activityRepo.replaceActivity(
            id = updatedNewActivity.v1.id,
            activityIdToDelete = oldActivityId,
            activity = updatedNewActivity,
        ).onFailure {
            cachePendingBoostActivity(newTxId, oldActivityId)
        }
    }

    /**
     * Handles the case when new activity is not found by caching for later retry
     */
    private suspend fun handleActivityNotFound(
        error: Throwable,
        newTxId: Txid,
        oldActivityId: String?,
    ): Result<Unit> {
        Logger.error(
            "Activity $newTxId not found. Caching data to try again on next sync",
            e = error,
            context = TAG
        )

        cachePendingBoostActivity(newTxId, oldActivityId)
        return Result.failure(error)
    }

    /**
     * Caches activity data for pending boost operation
     * - For RBF: stores parent chain (existing boostTxIds + current txId)
     * - For CPFP: stores empty list (child tx is added to parent's boostTxIds during sync)
     */
    private suspend fun cachePendingBoostActivity(newTxId: Txid, activityToDelete: String?) {
        val currentActivity = activity?.v1
        val boostTxIds = if (activityToDelete != null && currentActivity != null) {
            // RBF: Track full parent chain (existing boostTxIds + current txId being replaced)
            currentActivity.boostTxIds + currentActivity.txId
        } else {
            // CPFP: No parent tracking needed
            emptyList()
        }

        activityRepo.addActivityToPendingBoost(
            PendingBoostActivity(
                txId = newTxId,
                updatedAt = nowMillis().toULong(),
                activityToDelete = activityToDelete,
                boostTxIds = boostTxIds,
            )
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
    val isRbf: Boolean = false,
)
