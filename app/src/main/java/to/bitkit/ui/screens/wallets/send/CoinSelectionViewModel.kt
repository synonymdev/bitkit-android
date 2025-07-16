package to.bitkit.ui.screens.wallets.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.Activity.Onchain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.SpendableUtxo
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.rawId
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.CoreService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class CoinSelectionViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val coreService: CoreService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoinSelectionUiState())
    val uiState = _uiState.asStateFlow()

    private val _tagsByTxId = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tagsByTxId = _tagsByTxId.asStateFlow()

    private var onchainActivities: List<Activity> = emptyList()

    fun setOnchainActivities(onchainActivities: List<Activity>) {
        this.onchainActivities = onchainActivities
    }

    fun loadUtxos(requiredAmount: ULong, address: String) {
        viewModelScope.launch {
            try {
                val sortedUtxos = lightningRepo.listSpendableOutputs().getOrThrow()
                    .sortedByDescending { it.valueSats }

                val totalRequired = calculateTotalRequired(
                    address = address,
                    amountSats = requiredAmount,
                    utxosToSpend = sortedUtxos,
                )

                val totalSelected = sortedUtxos.sumOf { it.valueSats }

                _uiState.update { state ->
                    state.copy(
                        availableUtxos = sortedUtxos,
                        selectedUtxos = sortedUtxos,
                        autoSelectCoinsOn = true,
                        totalRequiredSat = totalRequired,
                        totalSelectedSat = totalSelected,
                        isSelectionValid = validateCoinSelection(totalSelected, totalRequired),
                    )
                }
            } catch (e: Throwable) {
                Logger.error("Failed to load UTXOs for coin selection", e)
                ToastEventBus.send(Exception("Failed to load UTXOs: ${e.message}"))
            }
        }
    }

    fun loadTagsForUtxo(txId: String) {
        if (_tagsByTxId.value.containsKey(txId)) return

        viewModelScope.launch(bgDispatcher) {
            runCatching {
                // find activity by txId
                onchainActivities.firstOrNull { (it as? Onchain)?.v1?.txId == txId }?.let { activity ->
                    // get tags by activity id
                    coreService.activity.tags(forActivityId = activity.rawId())
                        .takeIf { it.isNotEmpty() }
                        ?.let { tags ->
                            // add map entry linking tags to utxo.outpoint.txid
                            _tagsByTxId.update { currentMap -> currentMap + (txId to tags) }
                        }
                }
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
                val newTotalSat = allSelected.sumOf { it.valueSats }

                state.copy(
                    autoSelectCoinsOn = true,
                    selectedUtxos = allSelected,
                    totalSelectedSat = newTotalSat,
                    isSelectionValid = validateCoinSelection(newTotalSat, state.totalRequiredSat)
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
                totalSelectedSat = newTotal,
                autoSelectCoinsOn = false,
                isSelectionValid = validateCoinSelection(newTotal, state.totalRequiredSat)
            )
        }
    }

    private fun validateCoinSelection(totalSelectedSat: ULong, totalRequiredSat: ULong): Boolean {
        return totalSelectedSat > Env.TransactionDefaults.dustLimit &&
            totalRequiredSat > Env.TransactionDefaults.dustLimit &&
            totalSelectedSat >= totalRequiredSat
    }

    private suspend fun calculateTotalRequired(
        address: String,
        amountSats: ULong,
        utxosToSpend: List<SpendableUtxo>,
    ): ULong {
        return lightningRepo
            .calculateTotalFee(
                amountSats = amountSats,
                address = address,
                utxosToSpend = utxosToSpend,
            )
            .map { fee -> amountSats + fee }
            .getOrThrow()
    }
}

data class CoinSelectionUiState(
    val availableUtxos: List<SpendableUtxo> = emptyList(),
    val selectedUtxos: List<SpendableUtxo> = emptyList(),
    val autoSelectCoinsOn: Boolean = true,
    val totalRequiredSat: ULong = 0u,
    val totalSelectedSat: ULong = 0u,
    val isSelectionValid: Boolean = false,
)
