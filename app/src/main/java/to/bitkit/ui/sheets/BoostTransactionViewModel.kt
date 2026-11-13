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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
import to.bitkit.repositories.RbfBoostRepo
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
    private val rbfBoostRepo: RbfBoostRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoostTransactionUiState())
    val uiState = _uiState.asStateFlow()

    private val _boostTransactionEffect = MutableSharedFlow<SheetEffect>(extraBufferCapacity = 1)
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
    private var walletGeneration: Long? = null
    private var attemptObserver: Job? = null

    fun setupActivity(activity: Activity.Onchain): Activity.Onchain {
        clearActivity()
        walletGeneration = rbfBoostRepo.walletGeneration()
        Logger.debug("Setup activity $activity", context = TAG)
        val origin = Activity.Onchain(activity.v1)
        this.activity = origin

        _uiState.update {
            it.copy(
                loading = true,
                boosting = false,
                isRbf = activity.boostType() == BoostType.RBF,
                feeLocked = false,
                publicationSlow = false,
            )
        }

        val attempt = rbfBoostRepo.find(activity.v1, walletGeneration)
        if (attempt != null && activity.boostType() == BoostType.RBF) {
            if (attempt.state.value == RbfBoostRepo.Status.Published) {
                restoreQuote(attempt)
            } else {
                observeAttempt(attempt, origin, reportFailure = false)
            }
        } else {
            initializeFeeEstimates()
        }
        return origin
    }

    fun clearActivity(origin: Activity.Onchain? = activity) {
        if (activity !== origin) return
        activity = null
        attemptObserver?.cancel()
        attemptObserver = null
    }

    fun canDeliver(event: SheetEffect, item: Activity.Onchain): Boolean =
        event.origin != null && activity === event.origin &&
            event.origin.v1.walletId == item.v1.walletId && event.origin.v1.txId == item.v1.txId &&
            rbfBoostRepo.isCurrentWallet(event.walletGeneration)

    private fun restoreQuote(attempt: RbfBoostRepo.Attempt) {
        _uiState.update {
            it.copy(
                feeRate = attempt.quote.feeRate,
                totalFeeSats = attempt.quote.totalFeeSats,
                loading = false,
                feeLocked = true,
            )
        }
    }

    private fun observeAttempt(attempt: RbfBoostRepo.Attempt, origin: Activity.Onchain, reportFailure: Boolean) {
        attemptObserver?.cancel()
        restoreQuote(attempt)
        val generation = walletGeneration
        attemptObserver = viewModelScope.launch {
            attempt.state.first { status ->
                if (activity !== origin || !rbfBoostRepo.isCurrentWallet(generation)) return@first true
                when (status) {
                    RbfBoostRepo.Status.Broadcasting -> {
                        _uiState.update { it.copy(boosting = true, publicationSlow = false) }
                        false
                    }

                    is RbfBoostRepo.Status.Publishing -> {
                        _uiState.update { it.copy(boosting = true, publicationSlow = status.slow) }
                        false
                    }

                    is RbfBoostRepo.Status.Pending -> {
                        _uiState.update { it.copy(boosting = false, publicationSlow = true) }
                        if (reportFailure && !status.cancelled) {
                            setBoostTransactionEffect(BoostTransactionEffects.OnBoostPending, origin)
                        }
                        true
                    }

                    RbfBoostRepo.Status.Published -> {
                        _uiState.update { it.copy(boosting = false, publicationSlow = false) }
                        setBoostTransactionEffect(BoostTransactionEffects.OnBoostSuccess, origin)
                        true
                    }

                    RbfBoostRepo.Status.Failed, RbfBoostRepo.Status.Cancelled -> {
                        _uiState.update { it.copy(boosting = false, feeLocked = false, publicationSlow = false) }
                        if (reportFailure && status == RbfBoostRepo.Status.Failed) {
                            setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed, origin)
                        } else {
                            initializeFeeEstimates()
                        }
                        true
                    }
                }
            }
        }
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
        val attempt = activity?.v1?.let { rbfBoostRepo.find(it, walletGeneration) }
        if (attempt != null) {
            restoreQuote(attempt)
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
        if (_uiState.value.boosting || _uiState.value.loading) return
        val currentActivity = activity
        if (currentActivity == null) {
            handleError("Cannot boost: activity is null")
            return
        }

        if (currentActivity.v1.txType == PaymentType.SENT) {
            val state = _uiState.value
            val quote = RbfBoostRepo.Quote(feeRate = state.feeRate, totalFeeSats = state.totalFeeSats)
            val attempt = rbfBoostRepo.confirm(currentActivity.v1, walletGeneration, quote) ?: return
            observeAttempt(attempt, currentActivity, reportFailure = true)
            return
        }

        _uiState.update { it.copy(boosting = true) }
        viewModelScope.launch {
            runSuspendCatching { handleCpfpBoost(currentActivity) }.onFailure {
                handleError("Unexpected error during boost", it, currentActivity)
            }
        }
    }

    private suspend fun handleCpfpBoost(activity: Activity.Onchain) {
        lightningRepo.accelerateByCpfp(
            satsPerVByte = _uiState.value.feeRate,
            originalTxId = activity.v1.txId,
            destinationAddress = walletRepo.getOnchainAddress(),
        ).fold(
            onSuccess = { newTxId ->
                handleCpfpSuccess(activity, newTxId)
            },
            onFailure = { error ->
                handleError("CPFP boost failed", error, activity)
            }
        )
    }

    private suspend fun handleCpfpSuccess(origin: Activity.Onchain, newTxId: Txid) {
        Logger.debug("Boost successful. newTxId: $newTxId", context = TAG)
        val result = handleCPFPUpdate(origin.v1, newTxId)
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
                feeLocked = activity?.v1?.let { rbfBoostRepo.find(it, walletGeneration) } != null,
            )
        }
        setBoostTransactionEffect(BoostTransactionEffects.OnBoostFailed, origin)
    }

    private fun setBoostTransactionEffect(effect: BoostTransactionEffects, origin: Activity.Onchain? = null) {
        val generation = walletGeneration
        val owner = origin ?: activity
        viewModelScope.launch {
            if (owner != null && (activity !== owner || !rbfBoostRepo.isCurrentWallet(generation))) return@launch
            _boostTransactionEffect.emit(SheetEffect(effect, owner, generation))
        }
    }

    data class SheetEffect(
        val effect: BoostTransactionEffects,
        internal val origin: Activity.Onchain?,
        internal val walletGeneration: Long?,
    )
}

sealed interface BoostTransactionEffects {
    data object OnBoostSuccess : BoostTransactionEffects
    data object OnBoostFailed : BoostTransactionEffects
    data object OnBoostPending : BoostTransactionEffects
    data object OnMaxFee : BoostTransactionEffects
    data object OnMinFee : BoostTransactionEffects
}

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
    val publicationSlow: Boolean = false,
)
