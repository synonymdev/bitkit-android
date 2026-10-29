package to.bitkit.ui.settings.primaryNode

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.PrimaryNode
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PrimaryNodeRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.utils.primaryErrorMessageRes
import javax.inject.Inject

@HiltViewModel
class PrimaryNodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val primaryNodeRepo: PrimaryNodeRepo,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrimaryNodeUiState())
    val uiState: StateFlow<PrimaryNodeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(bgDispatcher) {
            combine(
                primaryNodeRepo.primaryNode,
                primaryNodeRepo.isFundingPaused,
                lightningRepo.lightningState,
            ) { primary, isPaused, state ->
                Triple(primary, isPaused, state.peers.any { it.nodeId == primary?.nodeId && it.isConnected })
            }.collect { (primary, isPaused, isConnected) ->
                _uiState.update {
                    it.copy(
                        currentUri = primary?.uri.orEmpty(),
                        input = if (it.hasEdited) it.input else primary?.uri.orEmpty(),
                        isConnected = isConnected,
                        isFundingPaused = isPaused,
                    )
                }
            }
        }
    }

    fun setInput(value: String) = _uiState.update { it.copy(input = value.trim(), hasEdited = true, error = null) }

    fun onScan(data: String) {
        val primary = PrimaryNode.parseOrNull(data)
        if (primary == null) {
            _uiState.update { it.copy(error = context.getString(R.string.primary_node__invalid)) }
            return
        }
        setInput(primary.uri)
    }

    fun resetToDefault() = setInput(Env.defaultPrimaryNodeUri)

    fun connect() {
        val input = _uiState.value.input
        if (input.isBlank() || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch(bgDispatcher) {
            primaryNodeRepo.pair(input)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, hasEdited = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.primary_node__connected),
                        testTag = "PrimaryNodeConnectedToast",
                    )
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = context.getString(error.primaryErrorMessageRes()))
                    }
                }
        }
    }
}

@Immutable
data class PrimaryNodeUiState(
    val currentUri: String = "",
    val input: String = "",
    val hasEdited: Boolean = false,
    val defaultUri: String = Env.defaultPrimaryNodeUri,
    val isConnected: Boolean = false,
    val isFundingPaused: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isInputDefault: Boolean get() = input == defaultUri
}
