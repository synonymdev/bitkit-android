package to.bitkit.ui.screens.wallets.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.blocktank.CJitEntry
import to.bitkit.services.BlocktankService
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val blocktankService: BlocktankService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState = _uiState.asStateFlow()

    fun createCjit(sats: Int, description: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingCjit = true) }
            runCatching { blocktankService.createCjit(sats, description) }
                .onSuccess { entry ->
                    // TODO cache cjit entries in DB
                    _uiState.update {
                        it.copy(
                            cjitEntry = entry,
                            isCreatingCjit = false,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(isCreatingCjit = false) }
                    ToastEventBus.send(it)
                }
        }
    }
}

// region state
data class ReceiveUiState(
    val cjitEntry: CJitEntry? = null,
    val isCreatingCjit: Boolean = false,
)
// endregion
