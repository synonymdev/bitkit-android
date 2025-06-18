package to.bitkit.ui.screens.wallets.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.SpendableUtxo
import to.bitkit.env.Env
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class CoinSelectionViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoinSelectionUiState())
    val uiState = _uiState.asStateFlow()

    fun loadUtxos(requiredAmount: ULong) {
        viewModelScope.launch {
            try {
                val availableUtxos = lightningRepo.listSpendableOutputs().getOrThrow()
                val sortedUtxos = availableUtxos.sortedByDescending { it.valueSats }
                val totalRequired = calculateTotalRequired(requiredAmount)

                _uiState.update { state ->
                    val totalSelected = sortedUtxos.sumOf { it.valueSats }

                    state.copy(
                        availableUtxos = sortedUtxos,
                        selectedUtxos = sortedUtxos,
                        autoSelectCoinsOn = true,
                        totalRequired = totalRequired,
                        totalSelected = totalSelected,
                        isSelectionValid = validateCoinSelection(totalSelected, totalRequired),
                    )
                }
            } catch (e: Throwable) {
                Logger.error("Failed to load UTXOs for coin selection", e)
                ToastEventBus.send(Exception("Failed to load UTXOs: ${e.message}"))
            }
        }
    }

    fun onToggleAuto() {
        val currentState = _uiState.value
        if (currentState.autoSelectCoinsOn) {
            _uiState.update {
                it.copy(autoSelectCoinsOn = false)
            }
        } else {
            _uiState.update { state ->
                val allSelected = state.availableUtxos
                val newTotal = allSelected.sumOf { it.valueSats }

                state.copy(
                    autoSelectCoinsOn = true,
                    selectedUtxos = allSelected,
                    totalSelected = newTotal,
                    isSelectionValid = validateCoinSelection(newTotal, state.totalRequired)
                )
            }
        }
    }

    fun onToggleUtxo(utxo: SpendableUtxo) {
        _uiState.update { state ->
            val isSelected = state.selectedUtxos.any { it.outpoint == utxo.outpoint }
            val newSelection = if (isSelected) {
                state.selectedUtxos.filterNot { it.outpoint == utxo.outpoint }
            } else {
                state.selectedUtxos + utxo
            }

            val newTotal = newSelection.sumOf { it.valueSats }

            state.copy(
                selectedUtxos = newSelection,
                totalSelected = newTotal,
                autoSelectCoinsOn = false,
                isSelectionValid = validateCoinSelection(newTotal, state.totalRequired)
            )
        }
    }

    private fun validateCoinSelection(totalSelected: ULong, totalRequired: ULong): Boolean {
        val dustLimit = Env.TransactionDefaults.dustLimit
        return totalSelected > dustLimit &&
            totalRequired > dustLimit &&
            totalSelected >= totalRequired
    }

    private fun calculateTotalRequired(amount: ULong): ULong {
        // TODO: Add proper fee calculation
        val estimatedFee = 1000uL // Mock fee
        return amount + estimatedFee
    }
}

data class CoinSelectionUiState(
    val availableUtxos: List<SpendableUtxo> = emptyList(),
    val selectedUtxos: List<SpendableUtxo> = emptyList(),
    val autoSelectCoinsOn: Boolean = true,
    val totalRequired: ULong = 0u,
    val totalSelected: ULong = 0u,
    val isSelectionValid: Boolean = false,
    val errorMessage: String? = null,
)
