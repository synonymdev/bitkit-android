package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.FeeRates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.safe
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SweepRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class SweepViewModel @Inject constructor(
    private val sweepRepo: SweepRepo,
    private val lightningRepo: LightningRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SweepUiState())
    val uiState = _uiState.asStateFlow()

    fun checkBalance() = viewModelScope.launch {
        _uiState.update { it.copy(checkState = CheckState.Checking) }

        sweepRepo.checkSweepableBalances().fold(
            onSuccess = { balances ->
                if (balances.totalBalance > 0u) {
                    _uiState.update {
                        it.copy(
                            checkState = CheckState.Found(balances.totalBalance),
                            sweepableBalances = balances,
                        )
                    }
                } else {
                    _uiState.update { it.copy(checkState = CheckState.NoFunds) }
                }
            },
            onFailure = { error ->
                Logger.error("Failed to check sweepable balance", error, context = TAG)
                _uiState.update { it.copy(checkState = CheckState.Error(error.message ?: "Unknown error")) }
            }
        )
    }

    fun prepareSweep() = viewModelScope.launch {
        _uiState.update { it.copy(sweepState = SweepState.Preparing) }

        val selectedFeeRate = _uiState.value.selectedFeeRate
        if (selectedFeeRate == null || selectedFeeRate == 0u) {
            val error = "Fee rate not set"
            _uiState.update { it.copy(sweepState = SweepState.Error(error), errorMessage = error) }
            return@launch
        }

        val existingAddress = _uiState.value.destinationAddress
        var destinationAddress = existingAddress
        if (existingAddress == null) {
            lightningRepo.newAddress().fold(
                onSuccess = { address ->
                    destinationAddress = address
                    _uiState.update { it.copy(destinationAddress = address) }
                },
                onFailure = { error ->
                    Logger.error("Failed to get destination address", error, context = TAG)
                    val errorMsg = "Failed to get destination address"
                    _uiState.update {
                        it.copy(sweepState = SweepState.Error(errorMsg), errorMessage = errorMsg)
                    }
                    return@launch
                }
            )
        }

        if (destinationAddress == null) return@launch

        sweepRepo.prepareSweepTransaction(
            destinationAddress = destinationAddress,
            feeRateSatsPerVbyte = selectedFeeRate,
        ).fold(
            onSuccess = { preview ->
                _uiState.update {
                    it.copy(
                        sweepState = SweepState.Ready,
                        transactionPreview = preview,
                    )
                }
            },
            onFailure = { error ->
                Logger.error("Failed to prepare sweep", error, context = TAG)
                _uiState.update {
                    it.copy(
                        sweepState = SweepState.Error(error.message ?: "Unknown error"),
                        errorMessage = error.message,
                    )
                }
            }
        )
    }

    fun broadcastSweep() = viewModelScope.launch {
        val preview = _uiState.value.transactionPreview
        if (preview == null) {
            _uiState.update { it.copy(sweepState = SweepState.Error("No transaction prepared")) }
            return@launch
        }

        _uiState.update { it.copy(sweepState = SweepState.Broadcasting) }

        sweepRepo.broadcastSweepTransaction(preview.psbt).fold(
            onSuccess = { result ->
                _uiState.update {
                    it.copy(
                        sweepState = SweepState.Success(result.txid),
                        sweepResult = result,
                    )
                }
            },
            onFailure = { error ->
                Logger.error("Failed to broadcast sweep", error, context = TAG)
                _uiState.update {
                    it.copy(
                        sweepState = SweepState.Error(error.message ?: "Unknown error"),
                        errorMessage = error.message,
                    )
                }
            }
        )
    }

    fun setFeeRate(speed: TransactionSpeed) {
        _uiState.update { it.copy(selectedSpeed = speed) }

        val feeRate: UInt = when (speed) {
            is TransactionSpeed.Custom -> speed.satsPerVByte
            else -> {
                val rates = _uiState.value.feeRates ?: return
                speed.getFeeRate(rates)
            }
        }
        _uiState.update { it.copy(selectedFeeRate = feeRate) }
    }

    fun loadFeeEstimates() = viewModelScope.launch {
        sweepRepo.getFeeRates().fold(
            onSuccess = { rates ->
                _uiState.update { it.copy(feeRates = rates) }
                if (_uiState.value.selectedFeeRate == null) {
                    setFeeRate(_uiState.value.selectedSpeed)
                }
            },
            onFailure = { error ->
                Logger.error("Failed to load fee estimates", error, context = TAG)
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        )
    }

    fun reset() {
        _uiState.update { SweepUiState() }
    }

    companion object {
        private const val TAG = "SweepViewModel"
    }
}

data class SweepUiState(
    val checkState: CheckState = CheckState.Idle,
    val sweepState: SweepState = SweepState.Idle,
    val sweepableBalances: SweepableBalances? = null,
    val transactionPreview: SweepTransactionPreview? = null,
    val sweepResult: SweepResult? = null,
    val destinationAddress: String? = null,
    val selectedSpeed: TransactionSpeed = TransactionSpeed.Medium,
    val selectedFeeRate: UInt? = null,
    val feeRates: FeeRates? = null,
    val errorMessage: String? = null,
) {
    val estimatedFee: ULong get() = transactionPreview?.estimatedFee ?: 0u
}

sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data class Found(val balance: ULong) : CheckState
    data object NoFunds : CheckState
    data class Error(val message: String) : CheckState
}

sealed interface SweepState {
    data object Idle : SweepState
    data object Preparing : SweepState
    data object Ready : SweepState
    data object Broadcasting : SweepState
    data class Success(val txid: String) : SweepState
    data class Error(val message: String) : SweepState
}

data class SweepableBalances(
    val legacyBalance: ULong = 0u,
    val legacyUtxosCount: UInt = 0u,
    val p2shBalance: ULong = 0u,
    val p2shUtxosCount: UInt = 0u,
    val taprootBalance: ULong = 0u,
    val taprootUtxosCount: UInt = 0u,
) {
    val totalBalance: ULong
        get() = listOf(legacyBalance, p2shBalance, taprootBalance)
            .fold(0uL) { acc, balance -> (acc.safe() + balance.safe()) }
}

data class SweepTransactionPreview(
    val psbt: String,
    val estimatedFee: ULong,
    val amountAfterFees: ULong,
    val estimatedVsize: ULong,
)

data class SweepResult(
    val txid: String,
    val amountSwept: ULong,
)
