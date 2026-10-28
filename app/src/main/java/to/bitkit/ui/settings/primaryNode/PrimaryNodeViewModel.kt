package to.bitkit.ui.settings.primaryNode

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.models.PrimaryNode
import to.bitkit.models.PrimaryNodeError
import to.bitkit.repositories.PrimaryNodeRepo
import to.bitkit.services.primaryWalletMessage
import javax.inject.Inject

@HiltViewModel
class PrimaryNodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val primaryNodeRepo: PrimaryNodeRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PrimaryNodeUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            primaryNodeRepo.primaryNodeUri.collect { uri ->
                _uiState.update {
                    it.copy(configuredUri = uri, input = if (it.hasEdited) it.input else uri)
                }
            }
        }
        viewModelScope.launch {
            primaryNodeRepo.fundingPaused.collect { paused ->
                _uiState.update { it.copy(isFundingPaused = paused) }
            }
        }
    }

    fun setInput(value: String) {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(input = value, hasEdited = true, error = null, isConnected = false) }
    }

    fun paste() = setInput(context.getClipboardText().orEmpty().trim())

    fun onScan(value: String) {
        if (_uiState.value.isLoading) return
        runCatching { PrimaryNode.parse(value) }
            .onSuccess { setInput(it.uri) }
            .onFailure {
                _uiState.update { state ->
                    state.copy(error = context.getString(R.string.primary_node__invalid))
                }
            }
    }

    fun connect() {
        if (_uiState.value.isLoading) return
        val input = _uiState.value.input.trim()
        _uiState.update { it.copy(isLoading = true, error = null, isConnected = false) }
        viewModelScope.launch {
            primaryNodeRepo.configure(input)
                .onSuccess { primary ->
                    _uiState.update {
                        it.copy(input = primary.uri, configuredUri = primary.uri, hasEdited = false, isConnected = true)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            error = when (error) {
                                is PrimaryNodeError.InvalidInput -> context.getString(R.string.primary_node__invalid)
                                is PrimaryNodeError.AddressUnknown ->
                                    context.getString(R.string.primary_node__address_required)
                                is PrimaryNodeError.ConnectionInUse ->
                                    context.getString(R.string.primary_node__connection_in_use)
                                else -> error.primaryWalletMessage(context)
                                    ?: context.getString(R.string.primary_node__connection_failed)
                            },
                        )
                    }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}

@Immutable
data class PrimaryNodeUiState(
    val input: String = "",
    val configuredUri: String = "",
    val hasEdited: Boolean = false,
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val isFundingPaused: Boolean = false,
    val error: String? = null,
)
