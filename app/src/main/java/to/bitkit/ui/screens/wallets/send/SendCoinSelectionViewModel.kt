package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.Activity.Onchain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.SpendableUtxo
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Defaults
import to.bitkit.ext.rawId
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class SendCoinSelectionViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "SendCoinSelectionViewModel"
    }

    private val _uiState = MutableStateFlow(CoinSelectionUiState())
    val uiState = _uiState.asStateFlow()

    private val _tagsByTxId = MutableStateFlow<ImmutableMap<String, ImmutableList<String>>>(persistentMapOf())
    val tagsByTxId = _tagsByTxId.asStateFlow()

    private var onchainActivities: List<Activity> = emptyList()

    fun setOnchainActivities(onchainActivities: List<Activity>) {
        this.onchainActivities = onchainActivities
    }

    fun loadUtxos(requiredAmount: ULong, address: String) = viewModelScope.launch {
        runCatching {
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
                    availableUtxos = sortedUtxos.toImmutableList(),
                    selectedUtxos = sortedUtxos.toImmutableList(),
                    autoSelectCoinsOn = true,
                    totalRequiredSat = totalRequired,
                    totalSelectedSat = totalSelected,
                    isSelectionValid = validateCoinSelection(totalSelected, totalRequired),
                )
            }
        }.onFailure {
            Logger.error("Failed to load UTXOs for coin selection", it, context = TAG)
            ToastEventBus.send(Exception("Failed to load UTXOs: ${it.message}"))
        }
    }

    fun loadTagsForUtxo(txId: String) {
        if (_tagsByTxId.value.containsKey(txId)) return

        viewModelScope.launch(bgDispatcher) {
            // find activity by txId
            onchainActivities.firstOrNull { (it as? Onchain)?.v1?.txId == txId }?.let { activity ->
                // get tags by activity id
                activityRepo.getActivityTags(activity.rawId())
                    .onSuccess { tags ->
                        if (tags.isNotEmpty()) {
                            // add map entry linking tags to utxo.outpoint.txid
                            _tagsByTxId.update {
                                (it + (txId to tags.toImmutableList())).toImmutableMap()
                            }
                        }
                    }
                    .onFailure {
                        Logger.error("Failed to load tags for utxo $txId", it, context = TAG)
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
                    selectedUtxos = allSelected.toImmutableList(),
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
                selectedUtxos = newSelection.toImmutableList(),
                totalSelectedSat = newTotal,
                autoSelectCoinsOn = false,
                isSelectionValid = validateCoinSelection(newTotal, state.totalRequiredSat)
            )
        }
    }

    private fun validateCoinSelection(totalSelectedSat: ULong, totalRequiredSat: ULong): Boolean {
        return totalSelectedSat > Defaults.dustLimit &&
            totalRequiredSat > Defaults.dustLimit &&
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

@Immutable
data class CoinSelectionUiState(
    val availableUtxos: ImmutableList<SpendableUtxo> = persistentListOf(),
    val selectedUtxos: ImmutableList<SpendableUtxo> = persistentListOf(),
    val autoSelectCoinsOn: Boolean = true,
    val totalRequiredSat: ULong = 0u,
    val totalSelectedSat: ULong = 0u,
    val isSelectionValid: Boolean = false,
)
