package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.BoltzSwap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.services.BoltzService
import to.bitkit.utils.Logger
import javax.inject.Inject

/**
 * Dev-tools view model backing the Swaps history screen. Reads persisted swaps from
 * bitkit-core (submarine + reverse) so they can be inspected/tracked, and allows a manual
 * reverse-swap claim for recovery when the automatic claim did not fire.
 */
@HiltViewModel
class SwapsViewModel @Inject constructor(
    private val boltzService: BoltzService,
) : ViewModel() {
    private val _swaps = MutableStateFlow<ImmutableList<BoltzSwap>>(persistentListOf())
    val swaps = _swaps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.update { true }
            runCatching { boltzService.listSwaps() }
                .onSuccess { list ->
                    _swaps.update { list.sortedByDescending { swap -> swap.createdAt }.toImmutableList() }
                    _error.update { null }
                }
                .onFailure { e ->
                    Logger.error("Failed to list swaps", e, context = TAG)
                    _error.update { e.message }
                }
            _isLoading.update { false }
        }
    }

    /** Manually broadcast the claim for a reverse swap (recovery when auto-claim didn't fire). */
    fun claimReverseSwap(id: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { boltzService.claimReverseSwap(id) }
            result
                .onSuccess { refresh() }
                .onFailure { Logger.error("Manual claim failed for '$id'", it, context = TAG) }
            onResult(result)
        }
    }

    companion object {
        private const val TAG = "SwapsViewModel"
    }
}
