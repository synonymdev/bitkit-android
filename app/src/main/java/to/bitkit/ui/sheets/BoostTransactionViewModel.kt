package to.bitkit.ui.sheets

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Txid
import to.bitkit.ext.BoostType
import to.bitkit.ext.boostType
import to.bitkit.ext.nowTimestamp
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.FeeRate
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class BoostTransactionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val activityRepo: ActivityRepo,
    private val blocktankRepo: BlocktankRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoostTransactionUiState())
    val uiState = _uiState.asStateFlow()

    private val _boostTransactionEffect = MutableSharedFlow<BoostTransactionEffects>(extraBufferCapacity = 1)
    val boostTransactionEffect = _boostTransactionEffect.asSharedFlow()

    companion object {
        const val TAG = "BoostTransactionViewModel"
        const val MAX_FEE_PERCENTAGE = 0.5
        const val MAX_FEE_RATE = 100UL
        const val FEE_RATE_STEP = 1UL
        const val RBF_MIN_INCREASE = 2UL
    }

    private var totalFeeSatsRecommended: ULong = 0U
    private var maxTotalFee: ULong = 0U
    private var feeRateRecommended: ULong = 0U
    private var minFeeRate: ULong = 2U
    private var activity: Activity.Onchain? = null
    private val broadcastRbfReplacements = mutableMapOf<String, RbfBroadcastAttempt>()

    fun setupActivity(activity: Activity.Onchain) {
        Logger.debug("Setup activity $activity", context = TAG)
        this.activity = Activity.Onchain(activity.v1)

        _uiState.update {
            it.copy(
                loading = true,
                boosting = false,
                isRbf = activity.boostType() == BoostType.RBF,
                feeLocked = false,
            )
        }

        val broadcast = broadcastRbfReplacements[activity.v1.txId]
        if (broadcast != null && activity.boostType() == BoostType.RBF) {
            restoreBroadcastFee(broadcast)
        } else {
            initializeFeeEstimates()
        }
    }

    private fun restoreBroadcastFee(broadcast: RbfBroadcastAttempt) {
        _uiState.update { broadcast.quote.copy(boosting = it.boosting, loading = false, feeLocked = true) }
    }

    private fun initializeFeeEstimates() {
        val origin = activity
        viewModelScope.launch {
            runSuspendCatching {
                val activityContent = origin?.v1 ?: run {
                    handleError("Activity value is null")
                    return@runSuspendCatching
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

                if (activity !== origin) return@runSuspendCatching
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
                        handleError("Failed to get fee estimates: ${error?.message}", error, origin)
                    }
                }
            }.onFailure {
                handleError("Unexpected error during fee estimation", it, origin)
            }
        }
    }

    private fun updateUiStateWithFeeData(totalFee: ULong, feeRate: ULong) {
        broadcastRbfReplacements[activity?.v1?.txId]?.let {
            restoreBroadcastFee(it)
            return
        }
        val currentFee = activity?.v1?.fee ?: 0u
        val isIncreaseEnabled = totalFee < maxTotalFee && feeRate < MAX_FEE_RATE
        val isDecreaseEnabled = totalFee > currentFee && feeRate > minFeeRate
        val feeRates = blocktankRepo.blocktankState.value.info?.onchain?.feeRates
        val estimateTime = feeRates
            ?.let { FeeRate.fromSatsPerVByte(feeRate, it) }
            ?.let { context.getString(it.duration) }
            ?: context.getString(FeeRate.NORMAL.duration)

        _uiState.update {
            it.copy(
                totalFeeSats = totalFee,
                feeRate = feeRate,
                increaseEnabled = isIncreaseEnabled,
                decreaseEnabled = isDecreaseEnabled,
                loading = false,
                estimateTime = estimateTime,
            )
        }
    }

    fun onClickEdit() {
        if (_uiState.value.feeLocked) return
        _uiState.update { it.copy(isDefaultMode = false) }
    }

    fun onClickUseSuggestedFee() {
        if (_uiState.value.feeLocked) return
        updateUiStateWithFeeData(
            totalFee = totalFeeSatsRecommended,
            feeRate = feeRateRecommended
        )
        _uiState.update { it.copy(isDefaultMode = true) }
    }

    fun onConfirmBoost() {
        if (_uiState.value.boosting) return
        val currentActivity = activity
        if (currentActivity == null) {
            handleError("Cannot boost: activity is null")
            return
        }

        _uiState.update { it.copy(boosting = true) }

        viewModelScope.launch {
            runSuspendCatching {
                when (currentActivity.v1.txType) {
                    PaymentType.SENT -> handleRbfBoost(currentActivity)
                    PaymentType.RECEIVED -> handleCpfpBoost(currentActivity)
                }
            }.onFailure {
                handleError("Unexpected error during boost", it, currentActivity)
            }
        }
    }

    private suspend fun handleRbfBoost(activity: Activity.Onchain) {
        val txid = activity.v1.txId
        val broadcast = broadcastRbfReplacements.getOrPut(txid) {
            val quote = _uiState.value
            RbfBroadcastAttempt(
                quote = quote,
                result = viewModelScope.async {
                    lightningRepo.bumpFeeByRbf(satsPerVByte = quote.feeRate, originalTxId = txid)
                },
            )
        }
        if (this.activity === activity) restoreBroadcastFee(broadcast)
        val result = try {
            broadcast.result.await()
        } finally {
            if (broadcast.result.isCancelled) removeRbfBroadcast(txid, broadcast)
        }
        result.fold(
            onSuccess = { handleBoostSuccess(activity, it, isRBF = true) },
            onFailure = {
                removeRbfBroadcast(txid, broadcast)
                handleError("RBF boost failed", it, activity)
            },
        )
    }

    private fun removeRbfBroadcast(txid: String, broadcast: RbfBroadcastAttempt) {
        if (broadcastRbfReplacements[txid] !== broadcast) return
        broadcastRbfReplacements.remove(txid)
        if (activity?.v1?.txId == txid) {
            _uiState.update { it.copy(feeLocked = false, boosting = false) }
        }
    }

    private suspend fun handleCpfpBoost(activity: Activity.Onchain) {
        lightningRepo.accelerateByCpfp(
            satsPerVByte = _uiState.value.feeRate,
            originalTxId = activity.v1.txId,
            destinationAddress = walletRepo.getOnchainAddress(),
        ).fold(
            onSuccess = { newTxId ->
                handleBoostSuccess(activity, newTxId, isRBF = false)
            },
            onFailure = { error ->
                handleError("CPFP boost failed", error, activity)
            }
        )
    }

    private suspend fun handleBoostSuccess(origin: Activity.Onchain, newTxId: Txid, isRBF: Boolean) {
        Logger.debug("Boost successful. newTxId: $newTxId", context = TAG)
        val result = updateActivity(origin, newTxId = newTxId, isRBF = isRBF)
        if (isRBF && result.isFailure) {
            lightningRepo.syncAsync()
            Logger.warn("Failed to publish RBF replacement", result.exceptionOrNull(), context = TAG)
            if (activity === origin) {
                _uiState.update { it.copy(boosting = false) }
                setBoostTransactionEffect(BoostTransactionEffects.OnBoostPending, origin)
            }
            return
        }
        result.onFailure { error ->
            Logger.warn("Boost successful but activity update failed", error, context = TAG)
        }

        if (activity === origin) {
            _uiState.update { it.copy(boosting = false) }
            setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess, origin)
        }

        // Fire-and-forget sync to keep UI responsive even if LDK sync hangs.
        lightningRepo.syncAsync()
    }

    fun onChangeAmount(increase: Boolean) {
        if (_uiState.value.feeLocked) return
        val origin = activity ?: return
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
            val result = lightningRepo.calculateTotalFee(
                amountSats = origin.v1.value,
                speed = TransactionSpeed.Custom(newFeeRate.toUInt()),
            )
            if (activity !== origin || _uiState.value.feeLocked) return@launch
            result.fold(
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

    private suspend fun updateActivity(origin: Activity.Onchain, newTxId: Txid, isRBF: Boolean): Result<Unit> =
        if (isRBF) {
            activityRepo.publishRbfBoost(origin.v1.txId, newTxId)
        } else {
            handleCPFPUpdate(origin.v1, newTxId)
        }

    /**
     * Handles CPFP (Child Pays For Parent) update by updating the current activity
     * and appending the child transaction ID to the parent's boostTxIds.
     */
    private suspend fun handleCPFPUpdate(currentActivity: OnchainActivity, childTxId: Txid): Result<Unit> {
        val updatedBoostTxIds = currentActivity.boostTxIds + childTxId
        val updatedActivity = Activity.Onchain(
            v1 = currentActivity.copy(
                isBoosted = true,
                boostTxIds = updatedBoostTxIds,
                updatedAt = nowTimestamp().toEpochMilli().toULong()
            )
        )

        return activityRepo.updateActivity(
            id = updatedActivity.v1.id,
            activity = updatedActivity
        )
    }

    private fun handleError(message: String, error: Throwable? = null, origin: Activity.Onchain? = null) {
        Logger.error(message, error, context = TAG)
        if (origin != null && activity !== origin) return
        _uiState.update {
            it.copy(
                boosting = false,
                loading = false,
                feeLocked = broadcastRbfReplacements[activity?.v1?.txId] != null,
            )
        }
        setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed, origin)
    }

    private fun setBoostTransactionEffect(effect: BoostTransactionEffects, origin: Activity.Onchain? = null) {
        viewModelScope.launch {
            if (origin != null && activity !== origin) return@launch
            _boostTransactionEffect.emit(effect)
        }
    }
}

sealed interface BoostTransactionEffects {
    data object OnBoostSuccess : BoostTransactionEffects
    data object OnBoostFailed : BoostTransactionEffects
    data object OnBoostPending : BoostTransactionEffects
    data object OnMaxFee : BoostTransactionEffects
    data object OnMinFee : BoostTransactionEffects
}

private data class RbfBroadcastAttempt(
    val quote: BoostTransactionUiState,
    val result: Deferred<Result<Txid>>,
)

@Immutable
data class BoostTransactionUiState(
    val totalFeeSats: ULong = 0U,
    val feeRate: ULong = 0U,
    val isDefaultMode: Boolean = true,
    val decreaseEnabled: Boolean = true,
    val increaseEnabled: Boolean = true,
    val boosting: Boolean = false,
    val loading: Boolean = false,
    val estimateTime: String = "",
    val isRbf: Boolean = false,
    val feeLocked: Boolean = false,
)
